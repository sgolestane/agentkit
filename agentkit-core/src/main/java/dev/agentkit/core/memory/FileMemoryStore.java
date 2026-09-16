package dev.agentkit.core.memory;

import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.util.ResourcePaths;
import dev.agentkit.core.util.SafePaths;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link MemoryStore} backed by a directory on disk, so memory survives process
 * restarts and is shared across runs that point at the same root.
 *
 * <p>Keys mean what {@link MemoryKeys} says they mean, and are named on disk the way
 * {@link MemoryFilenames} says — percent-encoded, so what this store can hold does not
 * depend on the locale the JVM started in. Length is a key rule now rather than something
 * this class discovers at open time (#83): {@link MemoryKeys} refuses a key whose encoded
 * name would be too long, so both stores refuse it alike and the model is told why.
 *
 * <p>All keys are confined to the root via {@link SafePaths}, rejecting traversal
 * ({@code ..}, absolute paths) and the root itself. A key reached through a symbolic link
 * is refused whether or not the link leaves the root (#99): one that leaves is a
 * containment failure, one that stays is an alias for a document another key already
 * names, and this store answers for the documents it wrote. The root itself may be a
 * symlink — that is a different thing, and it is resolved. Not thread-safe for concurrent
 * writers to the same path.
 *
 * <h2>Who the confinement is against, which is narrower than "confinement" (#143)</h2>
 *
 * <p>The sentence above is a claim about <strong>keys</strong>: a model that names
 * {@code ../../etc/passwd}, or a key that happens to reach a link somebody left lying
 * around, does not get out. That is the threat this store is built for, and it holds.
 *
 * <p>It is <strong>not</strong> a claim about the directory itself. Anyone who can create
 * names under the root is inside the boundary, not outside it, and has several moves this
 * class does not answer:
 *
 * <ul>
 *   <li><strong>Racing the check.</strong> A path can change between being judged clean and
 *       being opened. A symbolic link at the <em>final</em> component is refused by the
 *       kernel as part of the open, so that particular race closed in #164 — it used to be
 *       winnable on the first attempt. A link swapped into an intermediate <em>directory</em>
 *       component was not, and that one was measured rather than feared: over 20,000
 *       attempts per run, with the canary restored after every win so the counts are
 *       independent wins, {@code write} left model-chosen bytes outside the root 12, 15 and
 *       12 times, {@code append} added to a file outside it 14, 17 and 21 times,
 *       {@code delete} destroyed one 5, 31 and 9 times, {@code read} handed back its
 *       content 18, 34 and 28 times, and {@code list} reported names from outside the root
 *       as keys 0, 1 and 1 times (#168). It is closed now, and not by a better check:
 *       {@link PinnedDirectory} opens the key's folder one component at a time from a
 *       descriptor on the root, so every operation names one entry in one <em>open
 *       directory</em> instead of re-walking a path the kernel resolves again each time.
 *       Re-measured on the same harness, every count is zero.
 *
 *       <p>Two things survive, both narrower, and both written down where they happen.
 *       {@code append} takes a hard link at a name under the root to read through, and a
 *       lost race there gives an outside inode a name inside the root for the length of one
 *       {@code lstat} before the attempt is dropped; nothing outside the root is created,
 *       changed, removed, read or opened. And a FIFO raced into an intermediate component
 *       used to hang the thread — about twice in six thousand attempts, because the
 *       {@code openat} the descent uses carries no {@code O_DIRECTORY}, and that paragraph
 *       called it the price of the four rows above. It is not the price any more.
 *       {@code PinnedDirectory.withoutHangingOnAFifo} frees such an open by becoming the
 *       writer the syscall is waiting for (#235), and the last directory open this write
 *       path still made <em>by name</em> — the subtree walk in {@link #whatStandsAt(Located)},
 *       which was inside {@code java.nio.file}'s own {@code walkFileTree} and so out of that
 *       method's reach — is at-relative now (#236). What survives is two conditions rather
 *       than one: an attacker who wins the {@code fstatat}-to-open race <em>and</em> takes
 *       the FIFO's name away within two milliseconds, measured in {@link PinnedDirectory}.
 *
 *       <p>Neither writer races the check any more for the <em>node</em> question, which is
 *       a different thing and is #186/#189: a FIFO arriving in that window used to block the
 *       thread forever — 28% of writes, 173 of 900 appends, permanent, unobservable. Both
 *       operations now reach the key only through {@code rename(2)}, and {@code append}
 *       reads what is there through a hard link at a name nothing else holds, so no check
 *       this class makes is followed by an open of the name it checked.</li>
 *   <li><strong>Hard links, in the read direction.</strong> A second name for an inode
 *       outside the root is not a traversal, so there is nothing for the open to refuse.
 *       Measured with no race at all: a hard link at a key reads a file outside the root
 *       back in. The <em>write</em> direction closed as a side effect of #145 — a write
 *       renames over the key rather than opening it, so the link is unlinked and the
 *       outside inode keeps what it held. {@code append} kept it for three issues longer,
 *       because {@code MemoryRaceTest} raced {@code write} and not {@code append} and was
 *       green over a live hole; measured before #189, the outside file received
 *       {@code ORIGINAL\nREACHED-BY-HARD-LINK}. Both writers rename now. Measured both
 *       ways, for both of them.</li>
 *   <li><strong>Rotating the root</strong> underneath a live store. Closed (#165), and it
 *       was the widest of all of these: {@code rm -rf mem && ln -s /attacker mem} needs no
 *       race, and every one of {@code read}, {@code write}, {@code append}, {@code delete}
 *       and {@code list} then operated on the attacker's directory in 200 attempts out of
 *       200. The store records which <em>inode</em> its root was when it was built and
 *       compares that against {@code fstat} of the descriptor each descent opens, which is
 *       a question about a thing already held rather than about a name — see
 *       {@code PinnedDirectory.rootAsOpened}, including the benign rotation it still
 *       honours. Re-measured, every count is zero.
 *
 *       <p>One thing survives it, and it used to be two. {@code mkdir} and {@code link}
 *       are the two syscalls {@code java.nio.file} will only spell as a path, and everything
 *       else the descent does is at-relative. A rotation racing the staging {@code mkdir}
 *       still leaves an empty directory outside the root, with nothing in it. A rotation
 *       racing {@code append}'s second name used to leave a readable hard link to a memory
 *       document out there — 16, 7 and 23 per 20,000 appends — and that call is gone: the
 *       read leg opens the key through the pinned folder instead, and
 *       {@link #whatTheKeyHolds(String, PinnedDirectory)} carries the measurement, why it
 *       was a disclosure and not just a stray name, and what stands in its place (#227).</li>
 *   <li><strong>An ancestor that cannot be inspected</strong> no longer answers "no link".
 *       {@code Files.isSymbolicLink} folds "I could not look" into {@code false}, and the
 *       containment walk was built on it; a key whose lexical path is longer than
 *       {@code PATH_MAX} was therefore invisible to that walk while this store wrote and
 *       read through it in full. Measured, on any uid, in {@link #aLinkStandsAt} — along
 *       with the {@code chmod 000} half of the same finding, which does <strong>not</strong>
 *       reproduce unprivileged and was an artefact of a review run as root.</li>
 * </ul>
 *
 * <p>None of that is new, and none of it is a reason to run without the confinement — a
 * model naming keys is the attacker this is for, and against that attacker it works. It is
 * written down because the previous version of this paragraph said only "all keys are
 * confined to the root", which a reader could reasonably take as the stronger claim. If the
 * memory root is somewhere a hostile local process can write, the boundary is the
 * filesystem's — permissions, a dedicated uid, a mount — and not this class's.
 *
 * <h2>Which filesystem the root is on (#254)</h2>
 *
 * <p>Whichever one the caller handed in. A {@link Path} knows its own provider, so this
 * class asks it rather than assuming the default: the temporary a write stages through is
 * named by {@code root}'s filesystem, and whether a document is created {@code rw-------}
 * is decided by that filesystem's attribute views rather than by the host's. Both were
 * asked of the default filesystem until #254. The first was not a wrong answer but a
 * refused one — {@code SecureDirectoryStream.newByteChannel} rejects a path from another
 * provider, so every {@code write} to a root on a zip or in-memory filesystem threw
 * {@link java.nio.file.ProviderMismatchException}. The second, {@link #ownerOnlyOn}, has no
 * reproduction and is repaired by construction; that method says exactly how far the
 * measurement goes, which is not as far as this paragraph on its own would suggest.
 *
 * <p><strong>One requirement of the medium survives, and it is not portable by
 * specification.</strong> On a medium that offers {@code openat}, both writing operations
 * finish by moving a staged temporary onto the key with {@link SecureDirectoryStream#move},
 * which is where the check-to-open race went to die (#145, #168) and which is therefore not
 * negotiable. That method makes replacing an existing target <em>optional</em>: it is
 * specified to raise {@link FileAlreadyExistsException} "if a file already exists in the
 * target directory and cannot be replaced". The platform's {@code renameat} always can,
 * which is why nothing on the default filesystem meets this; a provider whose {@code move}
 * refuses instead lets a key be written once and no more. Jimfs 1.3.0 is such a provider,
 * and {@code FileMemoryStoreOnAnotherFilesystemTest} pins the behaviour rather than leaving
 * it to be discovered — 24 of {@code MediumBackedMemoryStoreContract}'s 26 clauses hold over
 * such a root, and the two that do not are the two that write a key twice.
 *
 * <p>It is the pinned branch's requirement and not the fallback's, which was measured too:
 * the same Jimfs code on {@code Configuration.osX()}, whose directory stream is not a
 * {@link SecureDirectoryStream}, overwrites a key perfectly happily through
 * {@link #writeWithoutFollowing(Path, String)}'s {@code Files.move} with
 * {@code REPLACE_EXISTING}. That is not a reason to prefer the fallback — it is the branch
 * with the window in it — but it is why the sentence above says {@code openat} rather than
 * "always". Re-measured while #273 was decided, on jimfs 1.3.0: {@code unix()} refuses the
 * second write with {@link FileAlreadyExistsException}, {@code osX()} and
 * {@code windows()} take it, and a plain {@code Files.move} with {@code REPLACE_EXISTING}
 * and {@code ATOMIC_MOVE} replaces on all three. The provider is willing; the overload
 * this branch has to use is the one with no options argument to ask with.
 *
 * <p><strong>The requirement is stated in {@link MemoryStore}'s contract since #273</strong>,
 * in the SPI section beside the other things owed where there is a medium, rather than only
 * here and in a test's javadoc. What lives here is this implementation's mechanism; what
 * lives there is the consequence, which is what someone choosing a medium for a store of
 * their own needs and would not think to look for in this file.
 *
 * <h2>Why it is not detected when the store is built (#273)</h2>
 *
 * <p>A store that refused a root it cannot serve would be better than one that works once
 * per key, and the detection is not hard: stage two temporaries under the root and try to
 * move one onto the other. It is not done, and that is a decision rather than an omission.
 *
 * <p><strong>The detection is itself a write, and the root is the only place to do it.</strong>
 * Measured unprivileged — the qualification matters, since the same probe run as root
 * reports the opposite, which is what the {@code chmod 000} half of the finding in
 * {@link #aLinkStandsAt} turned out to be — against a root at mode {@code r-xr-xr-x}:
 * this store constructs, {@code list} returns the documents in it and {@code read} hands
 * them back, while the {@code CREATE_NEW} a probe would have staged fails with
 * {@code AccessDeniedException} on its first temporary. So the probe would turn a store
 * serving a read-only snapshot perfectly well into one that cannot be constructed at all; and a probe that swallowed the denial instead would
 * answer "cannot tell" in exactly the case it was added for. Pointing a store at a root it
 * may only read is not exotic — {@code read}, {@code exists} and {@code list} are three of
 * the six operations, and a restored backup is one of the shapes this class's contract
 * explicitly serves.
 *
 * <p><strong>Every construction pays, and the failure it would move is already loud.</strong>
 * 2,000 constructions per run over four runs on the default filesystem: 66.5, 102.2, 117.7
 * and 173.1 microseconds each as it stands, against 95.0, 159.4, 200.1 and 281.5 with the
 * probe — roughly half as much again, on every store that will never fail. What that buys
 * is a failure arriving earlier, not a failure being caught: the second write already
 * throws {@link UncheckedIOException} with {@link FileAlreadyExistsException} under it, and
 * moving a loud failure earlier is worth much less than closing a silent one. The checks
 * #145 paid for were bought against a FIFO that hung the thread forever with no error and
 * nothing to time out; this is not that.
 *
 * <p><strong>And it would not be conclusive if it were free, because it would be asking in
 * the wrong directory.</strong> The replace happens in the key's own parent — the
 * temporary is staged there and {@code move}d within it — and a directory under the root
 * can be a different filesystem from the root itself, which is what a mount is. A probe in
 * the root therefore answers for the root, while {@code write("vol/a.md")} lands wherever
 * {@code vol} really is. The refusal it is meant to pre-empt stays reachable; it would
 * convert most occurrences into an earlier message rather than all of them into none. (The
 * root's <em>own</em> filesystem does not drift — the {@link Path} carries its provider,
 * and #165's inode pinning is what stops the directory itself being swapped for another —
 * so that much of it a probe would genuinely settle.)
 *
 * <p>The other repair the {@code osX()} measurement above suggests — probe once, and fall
 * back to {@link #writeWithoutFollowing(Path, String)} where the pinned {@code move} says
 * no — is rejected for the reason that paragraph already gives: the fallback is the branch
 * with the containment window in it, and trading a refusal on an unshipped medium for that
 * window on every write to such a root is the wrong way round.
 */
public final class FileMemoryStore implements MemoryStore {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(FileMemoryStore.class);

    private final Path root;

    /**
     * Which inode {@link #root} named when this store was built (#165).
     *
     * <p>The name is re-resolved by the kernel on every syscall, so a name is not a root:
     * {@code rm -rf mem && ln -s /attacker mem} redirected {@code read}, {@code write},
     * {@code append}, {@code delete} and {@code list} alike, 200 attempts out of 200, with
     * no race to win. Every descent now compares this against {@code fstat} of the
     * descriptor it just opened, which is a question about an inode rather than about a
     * name and so has no interval to lose. {@code PinnedDirectory.rootAsOpened} carries the
     * measurement, why it is not another check, and the benign rotation it still honours.
     */
    private final Object rootIs;

    /**
     * A store under {@code root}, having decided what to do if it cannot be contained.
     *
     * <p>There is deliberately no overload that omits {@code containment}. See
     * {@link Containment} for why: the containment this class documents is unavailable on any
     * platform whose JDK has no {@code SecureDirectoryStream} — which is every platform except
     * Linux — and a default would put a caller on the wrong side of that without their ever
     * having considered it. That is not hypothetical; it is what happened, and it stood in the
     * README for two years.
     *
     * @throws IllegalStateException if {@code containment} is {@link Containment#PINNED_OR_FAIL}
     *     and this platform cannot descend {@code root} with {@code openat}
     */
    public FileMemoryStore(Path root, Containment containment) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(containment, "containment");
        boolean pinnedHere;
        try {
            Files.createDirectories(root);
            // Real, not merely absolute. Files.walk does not follow the start path when it
            // is a link, so a lexically-rooted store answered every read and listed nothing
            // — 'Memory is empty' over a full directory. Pointing the root at a mounted
            // volume through a symlink is what this class's own javadoc invites.
            this.root = root.toRealPath();
            // Construction time is the one moment this store may trust the name: whatever
            // it leads to now is what "this store's root" means for the rest of its life.
            this.rootIs = PinnedDirectory.identityOf(this.root);
            pinnedHere = Containment.isAvailableFor(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create memory root: " + root, e);
        }
        if (pinnedHere) {
            return;
        }
        if (containment == Containment.PINNED_OR_FAIL) {
            throw new IllegalStateException(unpinnedHere()
                    + " This store was built with PINNED_OR_FAIL, so it refuses to start"
                    + " rather than hold data under a guarantee it cannot keep. Build it with"
                    + " Containment.BEST_EFFORT to proceed without that guarantee, knowing"
                    + " what is given up — see the Containment javadoc.");
        }
        // Once, at construction, naming the platform. Not per operation: a line on every
        // write is a line nobody reads, and this is a property of the deployment rather than
        // of any one call.
        LOG.warn("{} This store was built with BEST_EFFORT, so it will proceed. Keys are"
                + " still refused for traversal, absolute paths and symbolic links; what is"
                + " given up is containment against a concurrent writer inside the root.",
                unpinnedHere());
    }

    /** The exposure, in one sentence, for both the refusal and the warning. */
    private String unpinnedHere() {
        return "The memory root " + Quoted.of(String.valueOf(root)) + " cannot be descended"
                + " with openat on this platform (" + System.getProperty("os.name")
                + " returns no SecureDirectoryStream — only Linux's JDK does), so the"
                + " containment this store documents is INACTIVE and the races its pentest"
                + " suite reports as closed are open here.";
    }

    /**
     * {@code prefix} in the vocabulary the listed keys are in.
     *
     * <p>The prefix is the one key-shaped argument in the store, and leaving it out of the
     * key rules made three natural spellings of the documented directory scope —
     * {@code ' notes/'}, {@code './notes/'}, {@code 'notes//'} — all answer "empty" over a
     * populated directory. Matching stays <em>lexical</em> afterwards, because {@code 'notes/'}
     * is the documented way to scope to a directory and segment matching would break it;
     * the trailing slash is therefore preserved rather than folded away.
     */
    private static String scopeOf(String prefix) {
        if (prefix.isBlank()) {
            return "";
        }
        try {
            String key = MemoryKeys.normalize(prefix);
            return prefix.strip().endsWith("/") ? key + "/" : key;
        } catch (IllegalArgumentException notAKey) {
            // A prefix that is not key-shaped cannot match a key; let it match nothing
            // rather than turning a listing into an error.
            return prefix.strip();
        }
    }

    /**
     * Where a key lands, and the name it was spelled as, derived once.
     *
     * <p>Both are needed together and neither can be recovered from the other, which is why
     * they travel as a pair. {@code resolved} is what {@code SafePaths} hands back: absolute
     * and symlink-free, the path to actually open — and the path {@code delete} unlinks,
     * which is safe precisely because it only ever reaches {@code delete} when the key was
     * not reached through a link. {@code lexical} is the name under the root that the key
     * spells out, following nothing; it is what {@link #reachedThroughALink} walks.
     *
     * <p>Derived once because the alternative was three derivations per call: {@code delete}
     * ran {@code normalize} and {@code onDisk} three times over, and the symlink question a
     * fourth.
     */
    private record Located(String key, Path lexical, Path resolved) {

        /**
         * The key's on-disk name relative to the root, component by component.
         *
         * <p>What {@link PinnedDirectory} descends. Taken from {@code lexical} rather than
         * from {@code resolved}: the descent asks the kernel for each component in turn, so
         * it wants the name the key spells and not a spelling that has already had links
         * resolved out of it — and on a filesystem that folds case (#112) the two differ for
         * essentially every key while naming the same entry.
         */
        Path relative(Path root) {
            return root.relativize(lexical);
        }
    }

    /**
     * How many names a walk found, and as many of them as a warning will print.
     *
     * <p>A count is O(1) and a list is not. These populations are directory entries under a
     * root that anything with write access can fill, so the honest shape is "how many" plus
     * "here are some" rather than "here are all of them" — the same trade {@code
     * Quoted.each} makes at the rendering, moved back to where the memory is actually
     * spent.
     */
    private static final class Sample {

        /** As many as {@code Quoted.each} will print, so nothing is retained unread. */
        private static final int KEPT = 20;

        private final List<String> names = new ArrayList<>();
        private int total;

        void add(String name) {
            total++;
            if (names.size() < KEPT) {
                names.add(name);
            }
        }

        boolean isEmpty() {
            return total == 0;
        }

        int total() {
            return total;
        }

        List<String> names() {
            return names;
        }
    }

    /**
     * Where a key lands, or the refusal that a string which is not a key earns.
     *
     * <p>Through {@link MemoryKeys}, then {@link MemoryFilenames}, then {@link SafePaths}
     * — three questions, in order. What the key <em>is</em> has to be settled the same way
     * every store settles it, or {@code "  a.md  "} is one key here and another in memory.
     * What it is <em>called</em> is this store's own question, and answering it in ASCII is
     * what keeps the vocabulary off the host locale. Only then is it worth asking whether
     * the name stays inside the root.
     */
    private Located locate(String path) {
        String key = MemoryKeys.normalize(path);
        String name = MemoryFilenames.onDisk(key);
        try {
            return new Located(key, root.resolve(name), SafePaths.resolveWithin(root, name));
        } catch (java.nio.file.InvalidPathException cannotName) {
            // Before the IllegalArgumentException below, which it extends — the other
            // order does not compile at all ("has already been caught"). The encoded name is
            // printable ASCII, so there is little left here for a filesystem to refuse, but
            // "little" is not "nothing" and a path-layer exception is not one a MemoryStore
            // caller has any reason to catch.
            throw MemoryRefusal.NOT_A_KEY.of(
                    "this filesystem cannot name the key '" + key + "'"
                            + " (" + cannotName.getReason() + ")", cannotName);
        } catch (IllegalArgumentException outsideTheRoot) {
            throw new OutsideTheRoot(inTheCallersVocabulary(
                    outsideTheRoot.getMessage(), name, key), outsideTheRoot);
        }
    }

    /**
     * A well-formed key that does not land inside this root.
     *
     * <p>A subtype so the read side can tell it from a malformed key by catching rather than
     * by re-deriving. The previous version re-ran {@code MemoryKeys.normalize} inside the
     * catch to work out which of the two had happened, which was sound — normalize is pure —
     * but incomplete: it classified everything that was not a bad key as a containment
     * failure, so the third case, a name this filesystem cannot express, was logged as
     * "resolves outside the store" while saying it could not be named. Two contradictory
     * claims in one line.
     *
     * <p>Still an {@link IllegalArgumentException}: a write that would land outside must
     * refuse, and {@code MemoryTools} passes that message to the model.
     */
    private static final class OutsideTheRoot extends MemoryRefusal.Refused {

        private static final long serialVersionUID = 1L;

        OutsideTheRoot(String message, Throwable cause) {
            super(MemoryRefusal.OUTSIDE_THE_STORE, message, cause);
        }
    }


    /**
     * {@code message} with the on-disk name put back into the key the caller asked about.
     *
     * <p>These messages reach the model verbatim through {@code MemoryTools}, and it asked
     * about {@code facts/café.md}; being told about {@code facts/caf%C3%A9.md} would be a
     * refusal naming something it never wrote.
     *
     * <p>What makes a global replace safe here is the <em>haystack</em>, not the needle.
     * {@code name} differing from {@code key} implies {@code name} contains a {@code %},
     * and no fixed text in a {@link SafePaths} message does — those messages quote the
     * requested string and nothing else, which that class documents and keeps to because
     * they are model-facing. So the only occurrence the replace can find is the one put
     * there. A {@code SafePaths} message that grew a literal {@code %} would break this,
     * which is the coupling to weigh against the alternative: a {@code resolveWithin}
     * overload taking the name to confine and the name to quote separately. That is the
     * tidier fix and was not taken, because it widens a public signature used by skill
     * loading as well, to remove a hazard that is currently bounded by a rule the other
     * class already keeps.
     */
    private static String inTheCallersVocabulary(String message, String name, String key) {
        return message == null ? null : message.replace(name, key);
    }

    /**
     * The file a key names, or empty if the key names nothing this store may reach.
     *
     * <p>The read side of the contract is total: a well-formed key answers rather than
     * throwing, whatever the filesystem underneath is doing. A key reached through a
     * symbolic link is not a memory this store holds — whether the link leaves the root or
     * stays inside it — so "no memory here" is the true answer — and it is the answer the in-memory store gives, which is the point
     * (#83). A caller looping {@code list} then {@code read} cannot be made to catch an
     * exception for a name it was just handed.
     *
     * <p>Logged rather than swallowed. Nothing inside the root should link out of it, so the
     * operator is the one who needs to hear about it; the model gets the same "not found" it
     * would get for any absent key, which tells it nothing it could use to probe.
     */
    private Optional<Located> locateForReading(String path) {
        try {
            Located located = locate(path);
            if (reachedThroughALink(located)) {
                // A link is not a memory, and this is where the other three operations
                // learn that. list() has always skipped one — it walks without following,
                // so neither a linked name nor anything under a linked directory is a
                // regular file to it — while read, exists and delete followed it and found
                // a document (#99). Three operations answering one way and the fourth
                // another is the disagreement; which way they agree is the decision, and
                // "a link is not a key" is the one that matches list(), the operation
                // whose answer a model iterates.
                //
                // DEBUG rather than WARN. The model picks the keys, so a WARN here is a
                // line an agent can emit without limit by reading one aliased key in a
                // loop. The operator-facing signal belongs to list(), which fires once per
                // call and names the links it found — the operation an operator actually
                // runs when they wonder where their memory went.
                //
                // The OutsideTheRoot branch below stays at WARN under the same repetition,
                // and deliberately: an in-root alias is something an operator did on
                // purpose and this store has stopped honouring, while a link out of the
                // root is a containment failure. Noise is the right price for the second
                // and the wrong one for the first.
                LOG.debug("Key '{}' under {} is reached through a symbolic link rather than"
                        + " named directly, and is treated as absent", Quoted.of(path), root);
                return Optional.empty();
            }
            return Optional.of(located);
        } catch (OutsideTheRoot notInside) {
            LOG.warn("Key '{}' under {} resolves outside the store and is treated as absent: {}",
                    Quoted.of(path), root, Quoted.of(notInside.getMessage()));
            return Optional.empty();
        } catch (CannotTell cannotLook) {
            // Absent, which is the same answer a key that IS reached through a link gets,
            // and that is the point: the store could not rule a link out, so it assumes the
            // one it would refuse. Refusing to proceed rather than proceeding on a
            // predicate that could not look is what #165 asked for, and answering absent
            // keeps the read side total while doing it.
            //
            // WARN rather than the DEBUG the ordinary link branch above takes. That one is
            // model-driven — an agent can emit it in a loop by reading an aliased key — and
            // this one is not: nothing a model can name makes a component unreadable, so an
            // operator is both the cause and the only reader who can act.
            LOG.warn("Key '{}' under {} has a component this store could not inspect, so it"
                    + " cannot rule out a symbolic link and treats the key as absent: {}",
                    Quoted.of(path), root, Quoted.of(cannotLook.toString()));
            return Optional.empty();
        } catch (SecurityException cannotLook) {
            LOG.warn("Key '{}' under {} could not be resolved: {}",
                    Quoted.of(path), root, Quoted.of(cannotLook.toString()));
            return Optional.empty();
        }
        // A malformed key — and a key this filesystem cannot express — is a bad argument on
        // every operation, read included: the caller asked something that has no answer
        // rather than something that is absent. Both propagate.
    }

    @Override
    public Optional<String> read(String path) {
        Optional<Located> located = locateForReading(path);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        Path file = located.get().resolved();
        try (PinnedDirectory pinned =
                PinnedDirectory.toFind(root, rootIs, located.get().relative(root))) {
            if (!standsAsADocument(pinned, file)) {
                return Optional.empty();
            }
            return Optional.of(pinned.isPinned()
                    ? readWithoutFollowing(pinned.parent(), pinned.name())
                    : readWithoutFollowing(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read memory " + path, e);
        }
    }

    /**
     * Whether a document — not a folder, not a link, not a FIFO — stands at the key.
     *
     * <p>The three asking operations all need this and all used to spell it
     * {@code Files.isRegularFile(resolved)}, which re-walks the whole path and so answers
     * about whatever the components point at <em>now</em>. Asked through
     * {@link PinnedDirectory} it is one {@code fstatat} against a directory descriptor,
     * which is the same object the read or the unlink will reach.
     *
     * <p>A {@code null} {@code pinned} is the descent finding nothing it may enter — an
     * absent folder, a document where a folder should be, a symbolic link somewhere along
     * the way. All of those used to fold into {@code isRegularFile} answering false, and
     * they still answer absent, which is what {@code MemoryStore} promises the asking
     * operations: absence is an answer.
     */
    private static boolean standsAsADocument(PinnedDirectory pinned, Path file)
            throws IOException {
        if (pinned == null) {
            return false;
        }
        if (!pinned.isPinned()) {
            return Files.isRegularFile(file);
        }
        BasicFileAttributes standing = pinned.standing();
        return standing != null && standing.isRegularFile();
    }

    /**
     * Replaces {@code file}'s contents, refusing a symbolic link at the last component
     * <em>as part of the open</em> (#143).
     *
     * <p>The check this class already does is worth keeping — it produces a message naming
     * the key and what stands there — but it cannot make the open safe, because a check is
     * not a lock. Measured against a thread flipping the key between absent and a link
     * pointing outside the root: the write landed on the canary outside on the first
     * attempt, repeatedly.
     *
     * <p>How wide that window is depends on how deep the root sits — the containment walk
     * issues a {@code readlink} per ancestor — so no single figure describes it. An earlier
     * version of this paragraph said "roughly six syscalls"; measured with {@code strace},
     * one write to a fresh two-segment key under a deep root ran 45 before the open, and a
     * shallow root is fewer. It is never six, and the figure was doing rhetorical work —
     * making the window sound tight — that no measurement supports.
     *
     * <p>{@link LinkOption#NOFOLLOW_LINKS} closes that window atomically rather than
     * earlier: the kernel refuses to traverse a symbolic link at the final component in the
     * same syscall that opens the file. Verified on this platform rather than assumed —
     * Java's contract does not promise {@code O_NOFOLLOW} — by observing that the open
     * fails with "Too many levels of symbolic links (NOFOLLOW_LINKS specified)" while a
     * plain file, new or existing, writes normally.
     *
     * <h4>Exactly what it buys, which is narrower than "the open is now safe"</h4>
     *
     * <p><strong>Symbolic links only.</strong> A <em>hard</em> link is not a link as far as
     * the kernel is concerned — it is a second name for the same inode, and there is no
     * traversal to refuse. Hard links are documented out of scope in {@link SafePaths},
     * {@code README.md} and {@code docs/PENTEST.md}, and this method does not change that,
     * but the distinction matters here because a reader could otherwise take the paragraph
     * above to mean the final component is now safe outright. It is not — for reads.
     * Measured, with no race at all: a hard link at a key reads {@code TOP-SECRET} back in.
     *
     * <p>The <strong>write</strong> direction closed as a side effect of #145, which was
     * about something else entirely. {@code write} no longer opens the key: it renames a
     * temporary file over it, and {@code rename(2)} replaces the directory entry rather
     * than following the second name to the shared inode. Measured — the same hard link
     * that used to receive {@code PWNED-BY-HARDLINK} now keeps {@code ORIGINAL}, and the
     * key holds the new content. {@code MemoryRaceTest} pins both halves separately, since
     * they are no longer one statement.
     *
     * <p><strong>The last component only, and the one above it is demonstrably open.</strong>
     * An intermediate component that is a link is caught by the walk instead, which is a
     * check and so has the same weakness one level up. Closing that needs {@code openat}
     * against a directory descriptor, one component at a time, which {@code java.nio.file}
     * cannot express without JNI (#168).
     *
     * <p>That is measured, not feared, and an earlier version of this javadoc called it
     * unestablished. Against a thread flipping a directory component between a real
     * directory and a link pointing outside the root, 20,000 attempts per run:
     * {@code write} escaped in 3 runs of 3, first at attempts 52, 153 and 247, and
     * {@code delete} <strong>destroyed the canary outside the root</strong> in 3 of 3,
     * first at attempts 231, 211 and 502. The mechanism is that
     * {@code Files.createDirectories(file.getParent())} runs between the walk and the open,
     * and {@link LinkOption#NOFOLLOW_LINKS} guards only the final name.
     *
     * <p>So the honest summary: this removes the raced <em>symbolic</em> link at the final
     * component, which was winnable in a single attempt and is now closed. A hard link
     * needs no race at all and still reads outside the root, though it no longer writes
     * there. A raced directory component wins in a few hundred attempts. A strict reduction
     * in surface — not containment.
     */
    /**
     * Replaces {@code file}'s contents without ever opening what stands at the key (#145).
     *
     * <p>The bytes go to a fresh temporary file in the same directory and are renamed over
     * the key. {@code rename(2)} does not open its target, so a FIFO arriving between the
     * check and the write cannot block — the hang is not made narrower, it is made
     * impossible.
     *
     * <p>That matters because the check alone is nowhere near enough, and the first version
     * of this change said otherwise on a number that was wrong. It reported "1 hang in
     * 3,000 attempts" from a loop bound, while the loop broke on the first hang and the
     * run's own output — {@code wrote=78 refused=146 BLOCKED=1} — sums to 225 attempts. Re-
     * measured properly, with a pre-made FIFO linked and unlinked rather than a process
     * forked per flip:
     *
     * <pre>
     * attemptsMade=300 wrote=20 refused=192 BLOCKED=88 rate=29.33%
     * attemptsMade=300 wrote=18 refused=198 BLOCKED=84 rate=28.00%
     * attemptsMade=300 wrote=20 refused=199 BLOCKED=81 rate=27.00%
     * </pre>
     *
     * <p>Roughly one write in four, and the hang is permanent: it survives the FIFO being
     * unlinked, because the thread is already inside {@code open}. At that rate a deferral
     * would have left the exact failure this class calls unobservable — no error, no
     * result, no timeout — available on demand to anything that can write under the root.
     *
     * <p><strong>The check above still earns its place</strong>, and is not made redundant:
     * a node that is <em>there</em> when the key is examined is refused, so an operator's
     * deliberate mount survives and is named in the transcript. Only a node that appears
     * inside the window is replaced, which is a node the store had already established was
     * not there.
     *
     * <p><strong>{@code append} goes through here too now</strong> (#189), having been the
     * one writer left opening the key. It reads what the key holds and hands the whole
     * document back to this method; {@link #appendWithoutFollowing} carries the costs and
     * why they were paid.
     *
     * <p><strong>This overload is the one that runs where the platform has no
     * {@code openat}</strong> (#168). It names the key as a path, so every syscall in it
     * re-walks the intermediate components and the window {@link PinnedDirectory} closes is
     * open here. The pinned overload below is what Unix takes; this is kept, rather than
     * deleted, because deleting it would leave that platform with no writer at all.
     */
    private static void writeWithoutFollowing(Path file, String content) throws IOException {
        // Encoded before anything is created, for the reason encodeStrictly gives:
        // an unencodable string must not leave a temporary file behind either.
        ByteBuffer encoded = encodeStrictly(content);
        // createTempFile opens O_CREAT|O_EXCL on a name nothing else holds, so this open
        // cannot land on somebody else's node. In the same directory, because ATOMIC_MOVE
        // is only guaranteed within one filesystem.
        // The prefix is a deliberately malformed percent-escape, so the name this creates
        // is not one MemoryFilenames.keyOf can decode. That matters if the process dies
        // between the create and the rename: list() already asks keyOf of every entry and
        // skips what it cannot decode, so a leftover is neither listed as a memory nor
        // readable as one -- and it is reported to the operator through the same "skipped"
        // warning that names any other file somebody dropped in the root. Measured before
        // the prefix was chosen this way: list() returned [.write123.tmp, notes.md] and
        // read(".write123.tmp") returned the leftover content.
        Path temp = Files.createTempFile(file.getParent(), TEMP_PREFIX, ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                out.write(encoded.array(), encoded.arrayOffset() + encoded.position(),
                        encoded.remaining());
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            // A temporary file left in the memory root would be listed as a memory and
            // read back as one. Suppressed rather than chained, so the failure the caller
            // sees is the one that happened.
            try {
                Files.deleteIfExists(temp);
            } catch (IOException couldNotTidy) {
                failed.addSuppressed(couldNotTidy);
            }
            throw failed;
        }
    }

    /**
     * Not a decodable filename, so a leftover is never mistaken for a memory (#145).
     *
     * <p>Package-private rather than private since #168, because {@link PinnedDirectory}
     * stages folders under the same rule and one constant is what keeps {@code list()}
     * skipping both.
     */
    static final String TEMP_PREFIX = "%.write-";

    /**
     * UTF-8, refusing rather than substituting, and before any file is touched.
     *
     * <p>Strictly, because {@code String.getBytes(UTF_8)} and {@code Charset.encode} both
     * SUBSTITUTE: a string carrying an unpaired surrogate becomes {@code 0x3F}, a literal
     * {@code '?'}, with nothing reported. Measured on {@code "before\uD800after"},
     * {@code getBytes} writes {@code 62 65 66 6F 72 65 3F 61 66 74 65 72} while
     * {@code Files.writeString} throws. That would be a loud failure turned into a quiet
     * corruption, and it would put this store out of step with
     * {@code InMemoryMemoryStore}, which round-tripped the string intact until #147 — the
     * divergence {@code MemoryStoreDifferentialTest} exists to catch and did not, since it
     * fuzzed keys rather than content.
     *
     * <p><strong>This paragraph was orphaned.</strong> It sat above a second doc comment,
     * so javadoc attached the second one to {@code TEMP_PREFIX} and this method had no
     * documentation at all — the exact gap the javadoc configuration in the root
     * {@code pom.xml} warns about ("a doc comment orphaned by a second comment below it
     * needs JDK 22+ to detect, so it slips through here. It has bitten this repo once").
     * Twice, now. Moved back onto the method it describes; nothing in it changed but the
     * tense.
     *
     * <p><strong>The {@link CharacterCodingException} is the one {@code IOException} on
     * this path that is not the medium</strong> (#245). Every other one — a full disk, a
     * read-only mount, a directory that went away — is the medium refusing, and
     * {@code write} and {@code append} are right to wrap those as
     * {@link UncheckedIOException}. This one is a fact about the string the caller passed,
     * which the caller can fix by passing another, so it is raised as the reason channel
     * before it can reach that {@code catch}. {@link MemoryValues} carries the argument and
     * writes the message, so this store and the map store refuse identically.
     */
    private static ByteBuffer encodeStrictly(String content) throws IOException {
        CharsetEncoder strict = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strict.encode(CharBuffer.wrap(content));
        } catch (CharacterCodingException cannotBeWrittenDown) {
            throw MemoryValues.cannotBeWrittenDown(content, cannotBeWrittenDown);
        }
    }

    /**
     * Replaces what the key names, reaching it through an open directory rather than a path
     * (#168).
     *
     * <p>The same temporary-then-{@code rename} shape as the overload above, and for the
     * same reason — {@code rename(2)} never opens its target — with every syscall in it made
     * relative to a descriptor {@link PinnedDirectory} opened one refused-link at a time.
     * The temporary is created with {@code openat(dirfd, name, O_CREAT|O_EXCL|O_WRONLY)} and
     * moved with {@code renameat(dirfd, temp, dirfd, key)}, so nothing here names a path the
     * kernel would resolve from the root again.
     *
     * <p><strong>Measured.</strong> Against a thread flipping one intermediate component
     * between a real directory and a symbolic link pointing outside the root, 20,000
     * attempts per run and every attempt counted rather than a loop bound: the path-based
     * overload left {@code PWNED-n} in the canary outside the root 12, 15 and 12 times,
     * first at attempts 235, 24 and 4. Through here: 0, 0 and 0.
     *
     * <p><strong>The mode is carried deliberately.</strong> {@code Files.createTempFile}
     * makes an owner-only file, and the {@code rename} keeps that mode, so a memory document
     * has been {@code rw-------} since #145. {@code openat} without an explicit mode would
     * have made it {@code 0666 & ~umask} — world-readable on a default umask — which is a
     * permission change smuggled in under a containment fix. The attribute is omitted where
     * the filesystem has no POSIX view, since there it would only throw.
     */
    private static void writeWithoutFollowing(PinnedDirectory pinned, String content)
            throws IOException {
        // Encoded before anything is created, for the reason encodeStrictly gives:
        // an unencodable string must not leave a temporary file behind either.
        ByteBuffer encoded = encodeStrictly(content);
        SecureDirectoryStream<Path> dir = pinned.parent();
        Path temp = freshFileHolding(pinned, encoded);
        try {
            dir.move(temp, dir, pinned.name());
        } catch (IOException | RuntimeException failed) {
            // A temporary file left in the memory root would be listed as a memory and
            // read back as one. Suppressed rather than chained, so the failure the caller
            // sees is the one that happened.
            try {
                dir.deleteFile(temp);
            } catch (IOException couldNotTidy) {
                failed.addSuppressed(couldNotTidy);
            }
            throw failed;
        }
    }

    /**
     * A fresh file in {@code dir} holding {@code encoded}, at a name nothing else holds.
     *
     * <p>{@code CREATE_NEW} is {@code O_CREAT|O_EXCL}, which fails on an existing name
     * whatever that name happens to be — so somebody who guessed the name back and planted a
     * symbolic link at it does not get the write, they get a retry at another name. That is
     * the same exclusivity {@code Files.createTempFile} was providing before this went
     * at-relative, and it is why the name is random rather than derived from the key.
     *
     * <h4>Whose filesystem the name is built by (#254)</h4>
     *
     * <p>{@code Path.of} is always the <em>default</em> filesystem, and this name is handed
     * straight to {@link SecureDirectoryStream#newByteChannel}, which rejects a path from
     * another provider rather than misreading it. {@code FileMemoryStore(Path)} takes any
     * {@link Path}, so a root on a zip or an in-memory provider made every {@code write}
     * throw {@link java.nio.file.ProviderMismatchException} — measured, not feared: on a
     * Jimfs {@code Configuration.unix()} root, {@code write("a.md", "hello")} failed with
     * <em>"path %.write-….tmp is not associated with a Jimfs file system"</em>.
     *
     * <p>So the name is built by the medium's own filesystem, which is the repair #81 made
     * at the four sites of this shape in the skill loader and the promise
     * {@link ResourcePaths#display} already keeps in the other direction. The filesystem is
     * taken from {@link PinnedDirectory#name()} rather than from the store's root, because
     * that is the path this name is about to be a sibling of; the two are the same
     * filesystem, and asking the nearer one is what keeps them so.
     */
    private static Path freshFileHolding(PinnedDirectory pinned, ByteBuffer encoded)
            throws IOException {
        SecureDirectoryStream<Path> dir = pinned.parent();
        FileSystem medium = pinned.name().getFileSystem();
        for (int attempt = 0; attempt < TEMP_NAME_ATTEMPTS; attempt++) {
            Path temp = ResourcePaths.pathIn(medium, PinnedDirectory.freshName());
            SeekableByteChannel claimed;
            try {
                claimed = dir.newByteChannel(temp,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        ownerOnlyOn(medium));
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            try (SeekableByteChannel out = claimed) {
                ByteBuffer bytes = encoded.duplicate();
                while (bytes.hasRemaining()) {
                    out.write(bytes);
                }
            } catch (IOException | RuntimeException failed) {
                try {
                    dir.deleteFile(temp);
                } catch (IOException couldNotTidy) {
                    failed.addSuppressed(couldNotTidy);
                }
                throw failed;
            }
            return temp;
        }
        throw new IOException("no free temporary name in the folder holding the key");
    }

    /** How many random names are tried before a collision is treated as a failure. */
    private static final int TEMP_NAME_ATTEMPTS = 8;

    /**
     * The mode a memory document is created with, where the medium has modes.
     *
     * <p>{@code rw-------}, which is what {@code Files.createTempFile} was giving these
     * files before #168 moved their creation onto {@code openat}. Stated rather than
     * inherited, because the two defaults differ and the difference is a security property.
     */
    private static final FileAttribute<?>[] OWNER_ONLY = new FileAttribute<?>[] {
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) };

    /** Nothing to say about the mode, where the medium has none. */
    private static final FileAttribute<?>[] NO_MODE = new FileAttribute<?>[0];

    /**
     * {@link #OWNER_ONLY} where {@code medium} has POSIX modes, and nothing where it has not.
     *
     * <p><strong>Asked of the medium the document lands on, not of the default filesystem
     * (#254).</strong> This used to be a constant decided once by
     * {@code FileSystems.getDefault().supportedFileAttributeViews()}, which is the second
     * site of the shape that issue names: on a POSIX host the answer was yes, so the
     * {@code rw-------} attribute was handed to a {@code newByteChannel} on <em>whatever</em>
     * filesystem the root was on. Setting an attribute a provider does not have is
     * {@link UnsupportedOperationException}, which is not even one of the two channels
     * {@code MemoryStore} names. The question and the open now go to the same filesystem.
     *
     * <h4>What was measured, which is less than the paragraph above would suggest</h4>
     *
     * <p>Unlike the {@code Path.of} site, this one has no reproduction. The only
     * non-default provider this repository has to hand is Jimfs, and jimfs 1.3.0's
     * {@code JimfsSecureDirectoryStream.newByteChannel} <strong>discards</strong> its
     * {@code FileAttribute} argument rather than honouring or refusing it — so the old code
     * was harmless there by accident. That the refusal is real on the same filesystem, once
     * something actually tries to apply the attribute, was measured against that
     * filesystem's own {@code Files.createFile}:
     *
     * <pre>
     * jimfs Configuration.unix() supportedFileAttributeViews  = [basic]
     * secure newByteChannel with posix:permissions            = accepted (attribute dropped)
     * Files.createFile     with posix:permissions             = UnsupportedOperationException
     *                                                           cannot set attribute
     *                                                           'posix:permissions'
     * </pre>
     *
     * <p>So this is a hazard repaired by construction rather than a failure repaired by
     * measurement, and it is said that way rather than dressed up. The consequence for
     * testing is that no behavioural probe can tell the two answers apart on any medium
     * reachable from here, which is why {@code FileMemoryStoreOnAnotherFilesystemTest} asks
     * this method directly — see there for why a seam is the right call at this one site
     * and was the wrong one at {@code ResourcePaths.display}.
     *
     * <p>Two constants and a lookup rather than a computed array, so the write path still
     * allocates nothing: {@code supportedFileAttributeViews()} is a fixed set on both
     * providers this repository meets.
     */
    static FileAttribute<?>[] ownerOnlyOn(FileSystem medium) {
        return medium.supportedFileAttributeViews().contains("posix") ? OWNER_ONLY : NO_MODE;
    }

    /**
     * Adds to the end of {@code file} without ever opening what stands at the key (#189).
     *
     * <p>Read-modify-write: the document is read through a name of this store's own making,
     * concatenated, and handed to {@link #writeWithoutFollowing}, which renames a fresh
     * temporary file over the key. So neither leg opens the key, and the FIFO hang that
     * {@code write} lost in #145 is gone from the last operation that still had it.
     *
     * <p><strong>Measured, because the rate is the whole argument.</strong> A pre-made FIFO
     * linked and unlinked at the key while {@code append} runs on a bounded daemon thread,
     * hangs counted rather than broken out of — the denominator is attempts actually made,
     * which is the arithmetic #145's first version got wrong:
     *
     * <pre>
     * before: attemptsMade=300 wrote=83 refused=147 BLOCKED=70  rate=23.33%
     *         attemptsMade=300 wrote=96 refused=133 BLOCKED=71  rate=23.67%
     *         attemptsMade=300 wrote=69 refused=199 BLOCKED=32  rate=10.67%
     * after:  attemptsMade=300 ... BLOCKED=0 / 0 / 0
     * </pre>
     *
     * <p>173 permanent hangs in 900 attempts, and permanent means it survives the FIFO being
     * unlinked — the thread is already inside {@code open}, where no error, no result and no
     * timeout can reach it.
     *
     * <h4>Why the read leg is not a plain open, which is where this nearly went wrong</h4>
     *
     * <p>The obvious read-modify-write asks {@code isRegularFile} and then opens the key,
     * which is a check and an open on a <em>name</em> — the very shape #145 removed from the
     * write leg. Doing that would have moved the hang rather than closed it, and would have
     * reported a smaller number for it. Raced honestly, with a regular document at the key
     * that the attacker swaps for a FIFO (the absent/FIFO flip above never lets
     * {@code isRegularFile} say yes, so it never reaches the open under test):
     *
     * <pre>
     * lstat the key, then open the key: BLOCKED=28/400, 8/400, 10/400  — 46 in 1,200, 3.8%
     * through a name of our own:        BLOCKED= 0/400, 0/400,  0/400  —  0 in 1,200
     * </pre>
     *
     * <p>So the read leg goes through {@link #whatTheKeyHolds}, which asks its questions at
     * a name {@link #aSecondNameForWhateverStandsThere} made and nothing else has.
     * {@code link(2)} does not open either end, and an inode cannot change type, so the
     * check and the open finally refer to one object rather than to one name twice.
     *
     * <h4>What this costs, which is real and is in {@link MemoryStore}'s contract</h4>
     *
     * <p><strong>O(1) becomes O(n).</strong> Every append rewrites the whole document.
     * Measured on this machine: 0.100 ms per raw append before, and a whole-document rewrite
     * costs 0.271 ms at 1 KiB, 0.313 ms at 16 KiB, 1.6 ms at 256 KiB, 6.5 ms at 1 MiB and
     * 24 ms at 4 MiB. Nothing bounds a document — {@link MemoryKeys} bounds keys, in encoded
     * bytes, and no limit anywhere applies to content — so that last figure is reachable in
     * principle. It is not reached in practice by the only caller in this repository:
     * {@code LessonBook.record} already reads the entire document before every append, to
     * refuse a duplicate, so the O(n) read was there before this change was. Measured
     * end-to-end, 2,000 records: 0.425 ms each, against 0.100 ms for the bare append the
     * read already dwarfed.
     *
     * <p><strong>A concurrent appender's write is lost</strong>, and this is the cost worth
     * arguing about rather than the complexity one. Measured, 8 threads appending 200 lines
     * each to one key:
     *
     * <pre>
     * before: expected 1600, landed 1600, lost 0     (three runs of three)
     * after:  expected 1600, landed  367, lost 1233  (77.1%)
     *         expected 1600, landed  401, lost 1199  (74.9%)
     *         expected 1600, landed  578, lost 1022  (63.9%)
     * </pre>
     *
     * <p>{@code O_APPEND} is atomic in the kernel, so every append used to land; read-modify-
     * write has no such property and the later rename wins whole. This class has said "not
     * thread-safe for concurrent writers to the same path" since it was written,
     * {@link #requireTheKeyIsFree} says "not a lock", and {@code LessonBook} says the same of
     * its own topic — but a promise nobody made is still a behaviour somebody may have, so
     * the number is in {@code MemoryStore}'s contract rather than left to be found. What it
     * buys is the 173-in-900 above: a thread that never returns, with nothing in the
     * transcript to say so.
     *
     * <h4>Why the costs are not avoided by appending to the private name, which looks free</h4>
     *
     * <p>The obvious improvement, once the read leg has a hard link in hand, is to skip the
     * read entirely and open <em>that</em> name with {@code O_APPEND}: writes through any
     * name reach the inode, {@code O_APPEND} stays atomic, and both costs above disappear.
     * Built and measured, rather than reasoned about — the race closes and the concurrency
     * is kept:
     *
     * <pre>
     * race:       attemptsMade=300 BLOCKED=0 / 0 / 0
     * concurrent: 8 threads x 200 appends, landed 1600 of 1600, three runs of three
     * </pre>
     *
     * <p>And it reopens the hole the paragraph below is about, deterministically and with no
     * race at all, because that is the same property seen from the other side: a write
     * through <em>any</em> name reaches the inode, and when the key is a hard link to a file
     * outside the root, that inode is outside the root. Measured on the same shape, the
     * outside file received {@code ORIGINAL\nREACHED-BY-HARD-LINK} — and
     * {@code MemoryRaceTest} went red on the assertion written to notice, which is that
     * suite working rather than breaking.
     *
     * <p>The rescue for it is to count the inode's names: our own link makes {@code nlink}
     * two for a document the store wrote and three for one that was already linked
     * elsewhere, which separates the two deterministically (measured: 2 and 3). It is not
     * taken, for reasons that are not about taste. {@code nlink} is reachable only through
     * the {@code unix} attribute view, and Windows offers {@code [owner, dos, basic, acl,
     * user]} while NTFS supports hard links perfectly well — so the check is absent exactly
     * where the hole would still be open. It is also a check and not a lock, and unlike the
     * type question above it is one the answer can change under: an inode cannot change
     * type, but it can gain a name between the count and the open. And it would put the two
     * writers back into disagreement — {@code write} closes this structurally, because
     * {@code rename(2)} replaces a directory entry and cannot reach a second name for the
     * old inode, whereas an {@code nlink}-guarded append would close it conditionally.
     *
     * <p>So the trade is real and it is the one taken deliberately: concurrent appends, under
     * a usage this store has always disavowed and no caller here performs, against a
     * containment property that holds structurally, on every platform, with no window. If
     * that weighting is ever revisited, revisit it with the numbers above rather than with
     * the two that make it look free.
     *
     * <p><strong>And it closes a hole nobody had written down.</strong> {@code append} wrote
     * through a hard link to a file outside the root, which is the direction #145 closed for
     * {@code write} as a side effect of the same rename. Measured before this change: a key
     * hard-linked to an outside file received {@code ORIGINAL\nREACHED-BY-HARD-LINK}, in the
     * outside file. {@code MemoryRaceTest} pinned that direction for {@code write} only, so
     * the suite was green over it. The two writers now agree again.
     */
    private static void appendWithoutFollowing(String key, Path file, String content)
            throws IOException {
        writeWithoutFollowing(file, whatTheKeyHolds(key, file) + content);
    }

    /**
     * Adds to the end of the key, reaching its folder through an open directory (#168).
     *
     * <p>The write leg is {@link #writeWithoutFollowing(PinnedDirectory, String)} and needs
     * nothing said about it here. The read leg has to satisfy two requirements that pull in
     * opposite directions — #189 wants the type question and the open to reach one object,
     * #168 wants no path re-walked — and {@link #whatTheKeyHolds(String, PinnedDirectory)}
     * carries how both are met, what happened when only one of them was, and what the
     * version that met both by taking a hard link cost (#227).
     */
    private static void appendWithoutFollowing(String key, PinnedDirectory pinned,
            String content) throws IOException {
        writeWithoutFollowing(pinned, whatTheKeyHolds(key, pinned) + content);
    }

    /**
     * {@code O_RDWR|O_NOFOLLOW}: the only open in {@code java.nio.file} a FIFO cannot block.
     *
     * <p>{@code WRITE} is here for what the <em>open</em> does, not for anything written
     * through it. Nothing is ever written through this channel.
     */
    private static final Set<OpenOption> READ_WRITE = Set.<OpenOption>of(
            StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

    /**
     * What the key holds, opened through the pinned folder and proved by its descriptor
     * (#168, #189, #227).
     *
     * <h4>What the two issues before this one each demanded</h4>
     *
     * <p>#189 wants the type question and the open to reach <em>one object</em>: asking
     * {@code isRegularFile} of a name and then opening that name asks two questions about a
     * name, and a FIFO raced into the gap blocks the thread inside {@code open(2)} forever,
     * with no error, no result and no timeout. #168 wants no path re-walked: every component
     * named again is one a symbolic link can be swapped into.
     *
     * <p>The version that met both took a hard link. {@code link(2)} opens neither end and
     * fixes an inode for good, so a name this store had just made could be asked its type
     * and then opened, about one object — but {@code link(2)} takes two <em>paths</em>,
     * {@code java.nio.file} has no {@code linkat} and {@link SecureDirectoryStream} has no
     * {@code createLink}, so the second name had to be spelled under the root's own name. A
     * root rotation landing in that call put the second name in the rotating process's
     * directory, and a rotation back before the {@code deleteIfExists} that tidied it left it
     * there for good: a readable hard link to a memory document, in a directory the attacker
     * holds. Measured against a thread renaming the root away and back, 20,000 appends per
     * run and every attempt counted:
     *
     * <pre>
     * attemptsMade=20000  flips=45575  declined=18965  acted=1035  strays outside the root = 16
     * attemptsMade=20000  flips=52057  declined=17338  acted=2662  strays outside the root =  7
     * attemptsMade=20000  flips=64130  declined=16937  acted=3063  strays outside the root = 23
     * </pre>
     *
     * <p>That is a disclosure rather than an untidy name, because renaming a directory needs
     * write access to its <em>parent</em> and not read access to the directory: the attacker
     * position this assumes is one that could rotate {@code mem} without being able to read
     * what was inside it, and each stray hands them one document's inode under a name they
     * own. It is #227.
     *
     * <h4>Why the open below meets both requirements with no second name at all</h4>
     *
     * <p>{@code openat(dirfd, name, O_RDWR|O_NOFOLLOW)}, through the folder the descent
     * pinned. It is at-relative, so no path is walked and nothing outside the root can be
     * named, reached or left behind — #227's window is not narrowed, it is deleted along with
     * the call that had it. And it cannot block: a FIFO opened {@code O_RDWR} returns
     * immediately rather than waiting for the other end, which is what makes this the one
     * spelling of an open that {@code java.nio.file} offers and a FIFO cannot wedge.
     * {@code O_RDWR} is also why a directory raced in answers {@code EISDIR} instead of
     * opening, and {@code O_NOFOLLOW} is why a symbolic link answers {@code ELOOP}.
     *
     * <p><strong>Then the descriptor is asked, and the descriptor is the proof.</strong>
     * {@code lseek} fails with {@code ESPIPE} on a pipe, a FIFO or a socket and succeeds on a
     * regular file, so seeking the channel separates the thing that can be read from the
     * thing whose read never returns. It is a question about an object already held rather
     * than about a name, and about the one property of that object that cannot change:
     * an inode cannot change type. That is the same reason this package lets {@code step}
     * ask a component's type and refused to let {@code append} lean on {@code nlink} — a
     * count the answer can change under. The {@code fstatat} above it is the message: it
     * names what stands at the key in the vocabulary a model can act on, and it is not what
     * makes the open safe.
     *
     * <p><strong>Which is why {@code append} may now refuse a document it cannot open for
     * writing, and why {@code read} does not do any of this.</strong> The write permission is
     * for what the <em>open</em> does rather than for anything written through the channel —
     * nothing ever is — but it is still a permission this leg did not need before. A document
     * this store wrote is {@code rw-------} and owned by this process; what reaches the
     * refusal is one it did not write, or one whose mode was changed outside it, and
     * appending to that means reading it and renaming a replacement over it anyway.
     * {@code read} cannot pay that price — a memory somebody deliberately made read-only is
     * one {@code read} must still answer — so it opens {@code O_RDONLY} and keeps the FIFO
     * window this leg has just lost. That is the same exposure {@code read} carried before
     * this change and it is named rather than quietly inherited.
     *
     * <p><strong>What is left, and it needs a stronger attacker than any of this.</strong>
     * A character or block device raced to the key opens {@code O_RDWR}, seeks, and reads —
     * and its open or its read may block. Planting one needs {@code CAP_MKNOD} inside the
     * memory root, which is a capability that reaches past every guard in this package
     * directly; a FIFO needs nothing. On a platform where {@code open(2)} on a FIFO blocks
     * even with {@code O_RDWR} — POSIX leaves it undefined, Linux and the BSDs define it as
     * this relies on — the window is the one that was here before, not a new one.
     *
     * <p><strong>The refusal for a non-document at the key is the second of two layers, and
     * a mutant deleting it survives.</strong> {@code requireTheKeyIsFree} refuses a FIFO, a
     * socket or a device node that is <em>already</em> at the key before any of this runs,
     * so this branch is reachable only by a node raced in after that check — and there the
     * seek below catches it and the attempt is retried. What deleting it costs is the
     * message: eight retries and a {@code FileSystemException} saying the key changed on
     * every attempt, instead of one naming what stands there. Measured: every memory suite
     * green with it deleted, and no oracle that does not assert on the message text of a
     * race can tell the two apart.
     *
     * <p><strong>Retried rather than refused when the key changes underneath</strong>, up to
     * a small bound. A key unlinked between the {@code fstatat} and the open, a symbolic link
     * or a directory raced into it, or a descriptor that will not seek, are all what a
     * concurrent writer looks like as well as what an attacker looks like — {@code write}
     * replaces the key by {@code rename}, which changes its inode — and turning an ordinary
     * collision between two callers into an I/O error would be a new failure mode in exchange
     * for nothing. An attacker holding the key different for eight tries in a row gets the
     * refusal instead.
     */
    private static String whatTheKeyHolds(String key, PinnedDirectory pinned)
            throws IOException {
        for (int attempt = 0; attempt < TEMP_NAME_ATTEMPTS; attempt++) {
            BasicFileAttributes standing = pinned.standing();
            if (standing == null) {
                // append creates, so a key naming nothing is an empty document rather than
                // a failure.
                return "";
            }
            if (!standing.isRegularFile()) {
                // Refused rather than replaced, which is the answer requireTheKeyIsFree
                // gives for a node that was already standing there when the key was
                // examined. write replaces such a node because rename(2) gives it no
                // choice; here there is a choice, and #145's reason holds: a store that did
                // not create it should not destroy it.
                throw MemoryRefusal.NOT_A_DOCUMENT.of(
                        "cannot append to '" + key + "': something that is not a document"
                                + " stands there — a FIFO, a socket or a device node — and"
                                + " this store only writes regular files; choose another"
                                + " key");
            }
            SeekableByteChannel opened;
            try {
                opened = pinned.parent().newByteChannel(pinned.name(), READ_WRITE);
            } catch (NoSuchFileException wentAway) {
                // Unlinked between the fstatat and the open. Look again.
                continue;
            } catch (AccessDeniedException cannotOpenForWriting) {
                // The open is read-write for what the open does rather than for anything
                // written through it, so a document this store cannot open for writing
                // stops here. A document this store wrote is rw------- and owned by this
                // process, so what reaches this is one it did not write or one whose mode
                // was changed outside it -- and appending to that means reading it and
                // renaming a replacement over it, which is not a thing to do quietly.
                // Reported rather than retried: the mode will not change on the next try.
                FileSystemException refused = new FileSystemException(key, null,
                        "the document at this key cannot be opened for writing, so this"
                                + " store will not append to it");
                refused.initCause(cannotOpenForWriting);
                throw refused;
            } catch (FileSystemException racedIntoTheKey) {
                // ELOOP for a symbolic link and EISDIR for a directory, both raced in after
                // the fstatat said neither. Look again rather than report: this is what a
                // concurrent writer looks like too.
                continue;
            }
            try (SeekableByteChannel document = opened) {
                try {
                    document.position();
                } catch (IOException willNotSeek) {
                    // ESPIPE: a FIFO, a pipe or a socket got here after the fstatat. The
                    // open did not block -- O_RDWR is why -- and the read is not attempted,
                    // because that is what would never come back.
                    continue;
                }
                return decodeStrictly(everythingIn(document));
            }
        }
        throw new FileSystemException(key, null,
                "what stands at this key changed on every attempt to read it");
    }

    /**
     * Everything left in an open document, read straight from the descriptor.
     *
     * <p>A plain loop rather than {@code Channels.newInputStream(...).readAllBytes()}, and
     * the difference is a guard rather than a style. Measured: with the seek in
     * {@link #whatTheKeyHolds(String, PinnedDirectory)} deleted, a FIFO raced to the key
     * still did not hang — 0 in 2,000, twice — because
     * {@code ChannelInputStream.readAllBytes} asks a {@link SeekableByteChannel} its
     * {@code size} and {@code position} before reading, so the {@code lseek} happened
     * anyway, one layer down and by accident. Every mutant that removed the seek therefore
     * survived the whole suite. That is safety by an undocumented optimisation in one JDK:
     * the same method falls back to reading without asking for channels it will not size,
     * and this package's history says what a read of a FIFO does. Read here instead, so the
     * check above is the thing that makes the read safe and a test can say so — measured
     * after this change, the same mutant hangs 117 and 98 appends in 2,000.
     *
     * <p>The buffer is reused and copied out rather than accumulated, which is the same
     * shape {@code readAllBytes} has underneath; a document is bounded by nothing here, so
     * the growth is the caller's memory rather than this method's business.
     */
    private static byte[] everythingIn(SeekableByteChannel document) throws IOException {
        ByteArrayOutputStream held = new ByteArrayOutputStream();
        ByteBuffer chunk = ByteBuffer.allocate(8192);
        while (document.read(chunk) != -1) {
            held.write(chunk.array(), 0, chunk.position());
            chunk.clear();
        }
        return held.toByteArray();
    }

    /**
     * What stands at the key, read through a name nothing else holds — or {@code ""} when
     * the key names nothing, since {@code append} creates.
     *
     * <p>{@code Files.createLink} is {@code link(2)}, which opens neither end and does not
     * follow a symbolic link at its source. What it buys is a <em>second name for the same
     * inode</em>, and the name is one this method just made, so nobody can substitute
     * anything at it. The type question and the open therefore reach one object. Asking
     * {@code isRegularFile} of the key and then opening the key asks two questions about a
     * name, which is what let a FIFO in — see {@link #appendWithoutFollowing} for the rate.
     *
     * <p>Not a document — a FIFO, a socket, a device node that raced into the window — is
     * refused rather than replaced, which is the answer {@link #requireTheKeyIsFree} gives
     * for one that was already standing there. {@code write} replaces such a node because
     * {@code rename(2)} gives it no choice; here there is a choice, and #145's reason for
     * refusing holds: a store that did not create it should not destroy it.
     *
     * <p><strong>Where hard links are not available it falls back to opening the key</strong>,
     * which is the exposure {@code read} already carries and no more. A filesystem with no
     * hard links has no FIFOs either, in the cases that arise; and {@code fs.protected_hardlinks}
     * refuses a link to a file this process neither owns nor may write, which for a document
     * this store wrote is not the case. The fallback is named rather than silent because a
     * fallback that quietly reinstates the defect is how this kind of fix rots.
     */
    private static String whatTheKeyHolds(String key, Path file) throws IOException {
        Path asking = aSecondNameForWhateverStandsThere(file);
        if (asking == null) {
            return "";
        }
        try {
            BasicFileAttributes standing;
            try {
                standing = Files.readAttributes(asking, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException wentAway) {
                // Only reachable on the fallback path, where `asking` is the key and the key
                // can still be unlinked underneath. append creates, so this is a document
                // with nothing in it rather than a failure.
                return "";
            }
            if (!standing.isRegularFile()) {
                // Refused rather than replaced, which is the answer requireTheKeyIsFree gives
                // for a node that was already standing there when the key was examined.
                // write replaces such a node because rename(2) gives it no choice; here
                // there is a choice, and #145's reason holds: a store that did not create it
                // should not destroy it.
                throw MemoryRefusal.NOT_A_DOCUMENT.of(
                        "cannot append to '" + key + "': something that is not a document"
                                + " stands there — a FIFO, a socket or a device node — and"
                                + " this store only writes regular files; choose another"
                                + " key");
            }
            return readWithoutFollowing(asking);
        } finally {
            if (asking != file) {
                // A second name for a live document, so removing it cannot lose anything.
                // Leaving one behind would put a %.write- entry in the root for every
                // append, which list() skips but an operator would have to sweep.
                Files.deleteIfExists(asking);
            }
        }
    }

    /**
     * A name for whatever stands at {@code file} that nothing else holds — or {@code file}
     * itself where this filesystem will not give one, or {@code null} when the key names
     * nothing.
     *
     * <p>{@code Files.createLink} is {@code link(2)}, which opens neither end and does not
     * follow a symbolic link at its source. What it buys is a <em>second name for the same
     * inode</em>, and this method just made the name, so nobody can substitute anything at
     * it: the type question and the open above therefore reach one object. Asking
     * {@code isRegularFile} of the key and then opening the key asks two questions about a
     * <em>name</em>, which is the shape #145 removed from the write leg and which blocked 46
     * times in 1,200 when it was raced honestly here.
     *
     * <p>The three answers are one type because the caller does one thing with each: read at
     * the name it is given, and unlink it afterwards only if it made one. Splitting the
     * fallback out as its own read-and-return is what the first version did, and a mutant
     * emptying that copy <strong>survived the whole suite</strong> — no test on a Linux
     * runner reaches it, because hard links work there. It is written this way so that the
     * branch nothing can test carries as little of this store's behaviour as possible.
     *
     * <p>That branch is real rather than defensive. {@code fs.protected_hardlinks} refuses a
     * link to a file this process neither owns nor may write — not the case for a document
     * this store wrote, but the case for one an operator seeded — and exFAT has no hard
     * links at all. Where it is taken, the exposure is a check followed by an open of the
     * key, which is exactly what {@code read} already does and no more.
     */
    private static Path aSecondNameForWhateverStandsThere(Path file) throws IOException {
        Path via = Files.createTempFile(file.getParent(), TEMP_PREFIX, ".tmp");
        // Deleted, not reused: createLink needs a name that does not exist, and
        // createTempFile is how the name is claimed exclusively in the first place. Somebody
        // who guessed it back could take it in between, which createLink reports as
        // FileAlreadyExistsException -- a FileSystemException, so it lands in the fallback
        // rather than anywhere silent.
        Files.delete(via);
        try {
            Files.createLink(via, file);
            return via;
        } catch (NoSuchFileException nothingThere) {
            // Distinct from the fallback, and it matters which: handing the key back here
            // would send the read at whatever arrives in the window instead, which is the
            // window this method exists to shut. There is nothing to read.
            return null;
        } catch (UnsupportedOperationException | FileSystemException noHardLinkHere) {
            // FileSystemException is what link(2) reports through for every reason it can
            // refuse -- EPERM under fs.protected_hardlinks, EMLINK, a read-only or full
            // medium -- and taking them all the same way is deliberate: the alternative is
            // enumerating errno strings, which differ by platform. A medium that is really
            // out of room fails again at the write, where it belongs, and reaches the caller
            // as the UncheckedIOException the contract promises.
            return file;
        }
    }

    /**
     * Reads {@code file}, refusing a symbolic link at the last component as part of the
     * open — with the same "symbolic links, last component" limits as the writers above.
     *
     * <p>A won read is the worse half of that window rather than the lesser one: a won
     * write puts model-chosen bytes somewhere they should not be, while a won read puts
     * somebody else's file into the model's context, and from there into whatever the run
     * does next. Measured before this: 15 of 4,000 raced reads returned a file from outside
     * the root.
     *
     * <p><strong>Decoding is strict, which {@link Files#readString} also is and an earlier
     * version of this method was not.</strong> Swapping {@code readString} for
     * {@code new String(bytes, UTF_8)} looks like a null change and is not: the constructor
     * replaces malformed input with U+FFFD, so a corrupt memory file that used to fail
     * loudly would instead have handed the model mojibake with no indication anything was
     * wrong. Measured on the bytes {@code C3 28 78}: {@code readString} throws
     * {@code MalformedInputException}, the constructor returns {@code U+FFFD U+0028 U+0078}.
     * Reporting is the behaviour this class already had, so it is the behaviour kept.
     */
    private static String readWithoutFollowing(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            return decodeStrictly(in.readAllBytes());
        }
    }

    /**
     * Reads {@code name} inside an open directory, so no intermediate component is named
     * again (#168).
     *
     * <p>{@code openat(dirfd, name, O_RDONLY|O_NOFOLLOW)}. Measured against a thread
     * flipping an intermediate component between a real directory and a link pointing
     * outside the root, 20,000 attempts per run and every attempt counted: the path-based
     * overload above handed back content from outside the root 18, 34 and 28 times, first
     * at attempts 49, 2,716 and 2,016. Through here: 0, 0 and 0.
     *
     * <p>A won read is the worse half of the window rather than the lesser one — a won write
     * puts model-chosen bytes somewhere they should not be, while a won read puts somebody
     * else's file into the model's context and from there into whatever the run does next.
     */
    private static String readWithoutFollowing(SecureDirectoryStream<Path> dir, Path name)
            throws IOException {
        try (InputStream in = Channels.newInputStream(dir.newByteChannel(name,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return decodeStrictly(in.readAllBytes());
        }
    }

    /**
     * UTF-8, reporting rather than replacing.
     *
     * <p>{@code new String(bytes, UTF_8)} replaces malformed input with U+FFFD, so a corrupt
     * memory file would hand the model mojibake with nothing anywhere saying so. Measured on
     * the bytes {@code C3 28 78}: this throws {@code MalformedInputException}, the
     * constructor returns {@code U+FFFD U+0028 U+0078}. Shared by both readers so the two
     * cannot drift.
     */
    private static String decodeStrictly(byte[] bytes) throws IOException {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return strict.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * {@link Located} for a key a write may land on, or the refusal a linked one earns.
     *
     * <p>Refuses rather than answering absent, because the two writing operations are the
     * two that cannot treat absence as an answer — {@code MemoryStore} says so, and this is
     * the third reason after a blank key and one that escapes the root. Silently following
     * the link instead is what the first version did, and it was worse than the defect it
     * was fixing: {@code write("alias.md", x)} succeeded, {@code MemoryTools} told the model
     * "Wrote memory 'alias.md'", the document belonging to {@code plain.md} was overwritten,
     * and {@code read("alias.md")} then answered empty. A store that denies the write it
     * just accepted, having destroyed a different key's memory to do it.
     */
    private Located requireItIsNotALink(String path) {
        Located located = locate(path);
        if (reachedThroughALink(located)) {
            // The key, not the raw argument. Every other model-facing refusal in this class
            // goes through inTheCallersVocabulary so the model hears about the key it asked
            // for; quoting the argument here would have answered '  today.md  ' for a write
            // MemoryTools reports as "Wrote memory 'today.md'". MemoryKeys has already
            // refused anything in a key that could restructure the line.
            throw MemoryRefusal.REACHED_THROUGH_A_LINK.of(
                    "path is reached through a symbolic link rather than naming a document"
                            + " directly, and this store will not write through one: '"
                            + located.key() + "'");
        }
        return located;
    }

    /**
     * Whether some component of the key is a symbolic link.
     *
     * <p>Asked of the filesystem rather than inferred from a string comparison. The
     * comparison this replaces was "the path {@code SafePaths} returned differs from the
     * name the key spells", and it was exact on Linux and wrong elsewhere (#112).
     * {@code toRealPath} returns the spelling stored on disk, so on a filesystem that folds
     * case a key asking for {@code Notes/a.md} against a file stored as {@code notes/a.md}
     * resolved to a path that differed from the lexical one <em>with no link anywhere</em>.
     * (Case only. A filesystem that normalises Unicode has nothing here to renormalise,
     * because {@code MemoryFilenames.onDisk} percent-encodes every code point outside
     * printable ASCII — a caveat the first version of this javadoc stated and the second
     * dropped in favour of a broader claim that is false.) The store then answered {@code read} empty, {@code exists}
     * false, {@code delete} false, and refused the write — an ordinary memory made
     * unreachable, and the refusal named symbolic links that did not exist. macOS and
     * Windows are both affected in principle, and neither is in CI, which is exactly the
     * shape of bug a string comparison invites.
     *
     * <h4>Two syscalls in the ordinary case, O(depth) only where the depth is the question</h4>
     *
     * <p>The first version of this walked every component on every call, and justified the
     * cost by saying {@code SafePaths.resolveWithin} had already spent O(depth) syscalls on
     * the same path. <strong>That was measured and it is false.</strong> {@code toRealPath}
     * is a single {@code realpath}, resolved inside the kernel: one path syscall per
     * operation at every depth. So the walk was not amortised against anything, and a
     * {@code read} went from 1 syscall to 513 at the 512-component depth {@code MemoryKeys}
     * admits — 34µs to 13ms, which a model chooses the size of by naming a key.
     *
     * <p>So the walk runs only when the two spellings disagree. If they agree, one
     * {@code lstat} on the name itself settles it: agreement means no link was followed on
     * the way. That {@code lstat} is unreachable defence rather than a case that arises —
     * the one shape it would catch is a link naming <em>itself</em>, and {@code SafePaths}
     * refuses a path it cannot resolve to a real location before this class is asked. A
     * mutant deleting it survives the suite, which is how that is known rather than assumed;
     * it stays because "the spellings agree, so nothing was followed" is an inference about
     * another class's behaviour, and one syscall is a cheap price for not depending on it.
     * If they disagree, something is there to find —
     * a link on Linux, or a folded spelling on a filesystem that folds — and the walk is
     * what tells those apart. The case that costs O(depth) is exactly the case that needs it.
     *
     * <p>It also subsumes what the comparison needed two special cases for. A
     * <strong>rotated root</strong> — {@code mv mem mem2 && ln -s mem2 mem}, which this
     * class's constructor note invites — used to move every resolved path out from under the
     * lexical one and make the whole store answer empty; here the walk stops at the root and
     * never asks about it. And the write path no longer needs a second check bolted beside
     * the first, because there is only one question now and one place that answers it.
     *
     * <p>Asked component by component rather than of the whole path at once, so a self-loop
     * in a <em>directory</em> is caught as well as one at the name. Asking once about the
     * whole path cannot distinguish a loop from the ordinary case of writing {@code a/b.md}
     * where {@code a} is a document: both fail the lookup, and reporting the second as a
     * symbolic link would take away the "a key is a document or a folder and never both"
     * message that {@code requireTheKeyIsFree} exists to give. What keeps the two separate
     * is {@link #holdsALink} walking <em>down</em> from the root and stopping at the first
     * component that is not a directory, so it never asks about a name underneath one — it
     * used to be {@code isSymbolicLink} answering {@code false} for everything it could not
     * read, which kept them separate by keeping nothing at all (#165).
     */
    private boolean reachedThroughALink(Located located) {
        if (located.resolved().equals(located.lexical())) {
            try {
                return aLinkStandsAt(located.lexical());
            } catch (CannotTell oneQuestionWasNotEnough) {
                // Fall through to the walk rather than refuse. One lstat of the whole path
                // cannot separate "an ancestor I may not read" from "a key written under a
                // document", because ENOTDIR and EACCES arrive here as the same
                // FileSystemException — measured: readAttributes on a name under a regular
                // file answers "Not a directory" as a bare FileSystemException, not as
                // NotDirectoryException, so no catch could tell them apart. The walk
                // separates them by construction: it stops at the first component that is
                // not a directory, so it never asks about a name under one.
            }
        }
        return holdsALink(located.lexical());
    }

    /**
     * Whether a symbolic link stands at {@code name}, refusing when it cannot be told.
     *
     * <p><strong>{@code Files.isSymbolicLink} answers {@code false} for a name it cannot
     * {@code lstat}</strong> — it catches the {@link IOException} and returns — and that is
     * a fail-open in a predicate whose entire job is to fail closed. #165 asked for it to be
     * measured rather than assumed, and both halves of the measurement matter.
     *
     * <p><strong>Under an ordinary uid it reproduces.</strong> With a {@code chmod 000}
     * directory at {@code a} holding a symbolic link at {@code a/b.md}, run as uid 65534:
     *
     * <pre>
     * Files.isSymbolicLink(root/a/b.md) = false      (it IS a symbolic link)
     * readAttributes(NOFOLLOW)          = AccessDeniedException
     * </pre>
     *
     * <p><strong>The consequence #139's review reported does not.</strong> That review said
     * the store "read and wrote normally" under such an ancestor, and #165 said the finding
     * proved nothing because the review ran as uid 0. It was right: re-run as uid 0, the
     * name is perfectly stattable — {@code readAttributes} succeeds and
     * {@code isSymbolicLink} answers {@code true} — because {@code chmod 000} is not
     * enforced against root at all, so there was never an unstattable ancestor in that
     * measurement. Re-run as uid 65534, every operation refuses on its own:
     * {@code read}, {@code exists} and {@code delete} answer absent, {@code write} and
     * {@code append} raise {@code AccessDeniedException} naming {@code a}, {@code list}
     * skips the folder and warns, and the file outside the root is untouched. The
     * permission that hides the name from the {@code lstat} hides it from the open too.
     *
     * <p><strong>So the branch is repaired for the case where the two do not coincide, and
     * that case is not hypothetical.</strong> {@code lstat} fails for reasons no permission
     * bit explains, and one of them is reachable with no privilege at all: a key whose
     * <em>lexical</em> path is longer than {@code PATH_MAX}. Every {@code lstat} along it
     * answers {@code ENAMETOOLONG}, so this walk sees no link anywhere — while
     * {@link PinnedDirectory}'s descent, which names one component per syscall, has no such
     * limit and walks the whole way. Measured, with a root 3,655 characters deep and a key
     * taking the lexical path to 4,263:
     *
     * <pre>
     * Files.isSymbolicLink(the key's lexical path) = false
     * readAttributes(NOFOLLOW)                     = FileSystemException: File name too long
     * store.write(key, "PLAIN")                    = returned
     * store.read(key)                              = Optional[PLAIN]
     * </pre>
     *
     * <p>That is this class operating in full through a path its own containment walk is
     * blind to, on any uid. Nothing escaped — the descent refuses a link at a component with
     * {@code O_NOFOLLOW}, so a link planted there is caught one layer down — but the whole of
     * this store's link policy was resting on a layer added in #168, and the refusal a model
     * gets for it is "Memory operation failed." rather than the sentence
     * {@link #requireItIsNotALink} words. A predicate that cannot look must say so.
     *
     * <p><strong>What refusing costs, stated rather than discovered.</strong> A key whose
     * lexical path exceeds {@code PATH_MAX} now refuses where it used to work. That is the
     * honest price and it is small: {@link MemoryKeys} admits 1,024 bytes of key, so
     * reaching it needs a memory root some three thousand characters deep, and "this store
     * cannot establish where that key lands" is the true answer for it.
     *
     * <p>{@link NoSuchFileException} is the one refusal that is an answer: a name that is
     * not there is not a symbolic link, and both callers ask about components that a write
     * is about to create.
     *
     * <h4>The other places a failed question becomes a permissive answer, and why they stay</h4>
     *
     * <p>#165 asked for every one of them to be found and measured rather than for this one
     * to be fixed in isolation. There are four more in this class and one in
     * {@link SafePaths}, and none of them is this defect:
     *
     * <ul>
     *   <li>{@code whatStandsAt} folds an unreadable key into {@code NOTHING_IN_THE_WAY},
     *       which its own comment argues for at length and which is a <em>collision</em>
     *       question rather than a containment one — where the write lands is
     *       {@link PinnedDirectory}'s answer, not this one's. Measured under a
     *       {@code chmod 000} ancestor as uid 65534: the write goes on to fail with
     *       {@code AccessDeniedException} naming the ancestor, which is the medium error
     *       {@code MemoryStore} promises. Refusing here instead would tell the model to
     *       choose another key on a root where every key fails the same way — the mistake
     *       {@code requireTheKeyIsFree} already records making once. What used to be folded
     *       in <em>with</em> it is {@code ENOTDIR}, where a sibling key genuinely does work
     *       and the advice is therefore true; the pinned descent tells the two apart and
     *       names the component (#188), so this bullet is now about the one cause it always
     *       argued for.</li>
     *   <li>{@code requireTheKeyIsFree}'s {@code Files.isRegularFile} of an ancestor's name
     *       reports "free" when it cannot look, for the same reason and with the same
     *       measured outcome.</li>
     *   <li>{@code standsAsADocument} and {@code clearByWalking} both fall back to path
     *       questions, and only where {@code PinnedDirectory.isPinned()} is false — which is
     *       no platform in this CI. The first fails <em>closed</em> anyway: it answers
     *       absent.</li>
     *   <li>{@code SafePaths.realWithin} returns the lexical path when the base cannot be
     *       resolved. That was a redirection before this issue and is not one after it: the
     *       path it returns no longer decides where anything lands, because the descent
     *       starts from the root's recorded inode. Its own javadoc argues the case, and
     *       changing it would take totality away from {@code read}, {@code exists} and
     *       {@code delete} for a missing directory.</li>
     * </ul>
     *
     * @throws CannotTell if the filesystem would not say what stands at {@code name}
     */
    private static boolean aLinkStandsAt(Path name) {
        BasicFileAttributes standing = standingAt(name);
        return standing != null && standing.isSymbolicLink();
    }

    /**
     * What stands at {@code name}, {@code null} if nothing does, and a refusal if the
     * filesystem would not say.
     *
     * <p>The three answers this class needs kept apart, and the split
     * {@code Files.isSymbolicLink} does not make: it folds the third into the second.
     * {@link #aLinkStandsAt} carries the measurement of what that costs.
     */
    private static BasicFileAttributes standingAt(Path name) {
        try {
            return Files.readAttributes(name, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException nothingThere) {
            return null;
        } catch (IOException cannotTell) {
            throw new CannotTell(name, cannotTell);
        }
    }

    /**
     * A component of a key that the store could not inspect, so no answer about it is safe.
     *
     * <p>An {@link UncheckedIOException} because {@code MemoryStore} assigns "the medium
     * would not answer" to that channel, and because {@code MemoryTools} reduces every
     * {@code RuntimeException} that is not an {@code IllegalArgumentException} to "Memory
     * operation failed." while logging the detail — which is what keeps the host path in
     * this message away from the model. An {@code IllegalArgumentException} would put it in
     * front of the model and would also be the wrong advice: "choose another key" is useless
     * when an unreadable ancestor makes every key under it fail the same way.
     */
    private static final class CannotTell extends UncheckedIOException {

        private static final long serialVersionUID = 1L;

        CannotTell(Path name, IOException cause) {
            super("could not establish whether " + name + " is a symbolic link", cause);
        }
    }

    /**
     * The component-by-component walk, for when the spellings disagree.
     *
     * <p>Asking each component rather than the resolved path is also what catches the one
     * shape {@code SafePaths} folds lexically: a {@code ..} that jumps over a link, where
     * the resolved path is a name the kernel never visited. That property is why the walk
     * was added in the first place, it survives the merge, and {@code PlantedLinkTest} is
     * what holds it.
     *
     * <p>The loop is bounded by the key's own components, and it <strong>refuses</strong> a
     * lexical path that is not under the root rather than walking to the filesystem root or
     * falling out with "no link". {@code MemoryKeys} admits no key whose lexical path leaves
     * the root, so that case is a broken guarantee rather than a key — and a containment
     * predicate that fails open on the case it did not expect has failed. An earlier version
     * had that comparison in the loop condition, where it produced exactly that answer.
     *
     * <h4>Downward from the root, which is what makes refusing safe</h4>
     *
     * <p>This used to walk <em>up</em> from the key, and the direction was free while
     * {@code Files.isSymbolicLink} answered {@code false} for everything it could not read.
     * It is not free now that {@link #standingAt} refuses instead (#165). Going up asks
     * about {@code a/b.md} before it asks about {@code a}, so a key written under a document
     * — the ordinary {@code write("a/b.md")} where {@code a} is a memory — is an
     * {@code ENOTDIR} on the first question, arriving as a bare {@code FileSystemException}
     * indistinguishable from an unreadable ancestor. Refusing it would take away the "a key
     * is a document or a folder and never both" message {@code requireTheKeyIsFree} exists
     * to give, and would do it for the commonest collision a model makes.
     *
     * <p>Going down, the walk stops at the first component that is not a directory, so it
     * <em>never asks</em> about a name underneath one and that whole family of refusals
     * cannot arise. What is left when a question fails is the case the refusal is for: a
     * component that <em>is</em> a directory and whose entries this process may not read, or
     * a path the kernel will not accept. It also settles a self-referencing link earlier —
     * the loop is found as a link at its own component instead of as {@code ELOOP} from
     * below.
     *
     * <p>Both directions give the same answer whenever every component can be read, which
     * is what let this change be made without moving any test: a link anywhere strictly
     * between the root and the key answers true, and nothing else does.
     */
    private boolean holdsALink(Path lexical) {
        if (!lexical.startsWith(root)) {
            // Unreachable by construction -- locate() has already put the key inside
            // the root -- and given a reason anyway, because an unreachable branch that
            // classifies itself differently from every other refusal is one the differential
            // oracle would report as a divergence if it ever did become reachable (#257).
            throw MemoryRefusal.OUTSIDE_THE_STORE.of(
                    "this key resolves outside the memory root, which should be impossible;"
                            + " refusing rather than guessing");
        }
        Path at = root;
        for (Path component : root.relativize(lexical)) {
            at = at.resolve(component);
            BasicFileAttributes standing = standingAt(at);
            if (standing == null) {
                // Nothing here. The walk carries on rather than stopping, and the
                // difference is not academic: stopping made the answer depend on whether
                // the key had been written yet. A key whose lexical path is longer than
                // PATH_MAX has an absent first component and an unreadable fourth, so the
                // first write walked one step, found nothing, and proceeded -- and the read
                // that followed walked past three real directories into the refusal.
                // Measured: write returned and read answered empty, for the same key. The
                // components below an absent one are absent too, so carrying on costs a
                // handful of ENOENTs and buys the same answer at every point in a key's
                // life. Only the reasons a name CANNOT be read differ between the two, and
                // those are exactly what must not depend on timing.
                continue;
            }
            if (standing.isSymbolicLink()) {
                return true;
            }
            if (!standing.isDirectory()) {
                // A document, or a FIFO, part way along. The key does not exist and cannot
                // without this being cleared first, which is somebody else's refusal to
                // give — and there is nothing under it to be a link.
                return false;
            }
        }
        return false;
    }

    @Override
    public void write(String path, String content) {
        Objects.requireNonNull(content, "content");
        Located located = requireItIsNotALink(path);
        Path file = located.resolved();
        requireTheKeyIsFree(located);
        // toWrite makes the folders the key implies, which is what createDirectories used to
        // do here -- and it makes them one renameat at a time into descriptors it opened,
        // rather than by naming a path whose middle somebody else can rewrite (#168).
        try {
            throughTheKeysFolder(located, pinned -> writeWithoutFollowing(pinned, content),
                    () -> {
                        Files.createDirectories(file.getParent());
                        writeWithoutFollowing(file, content);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write memory " + path, e);
        }
    }

    /**
     * Runs a writing leg against the key's folder, held open, remaking it if it goes away.
     *
     * <p><strong>The remaking is the point, and it is paying for something this class does
     * to itself.</strong> {@code renameat} has no {@code RENAME_NOREPLACE} in
     * {@code java.nio.file}, so when two writers find the same folder missing and both stage
     * one, the second rename <em>replaces</em> the first writer's folder — and the first
     * writer is holding a descriptor on it. A descriptor on an unlinked directory still
     * reads, but Linux refuses to create or rename anything into it, so the write came back
     * as {@code NoSuchFileException} naming a {@code %.write-} temporary nobody asked about.
     * Measured over 16 threads writing 100 keys each under one folder that did not exist
     * yet: 2 refusals in 1,600 before {@link PinnedDirectory} looked again just before its
     * rename, 1 in 9,600 after that, and 0 in 9,600 with this loop as well. Nothing was ever
     * lost — the refusal is loud — but a store that occasionally will not write a key
     * because another thread wrote a sibling is a store with a bug.
     *
     * <p>Bounded, like every other retry here, because the loop is racing somebody: eight is
     * far past what honest writers need, and an attacker who removes the folder eight times
     * running gets the refusal.
     */
    private void throughTheKeysFolder(Located located, PinnedLeg leg, PathLeg withoutOpenat)
            throws IOException {
        NoSuchFileException lastWentAway = null;
        for (int attempt = 0; attempt < TEMP_NAME_ATTEMPTS; attempt++) {
            try (PinnedDirectory pinned =
                    PinnedDirectory.toWrite(root, rootIs, located.relative(root))) {
                if (!pinned.isPinned()) {
                    withoutOpenat.run();
                    return;
                }
                leg.run(pinned);
                return;
            } catch (NoSuchFileException wentAway) {
                // The folder this was writing into was unlinked underneath -- replaced by
                // another writer's, or removed outright. Descend again: the folder is
                // remade if it is still missing, and the leg runs against whatever is
                // actually there.
                lastWentAway = wentAway;
            }
        }
        throw lastWentAway;
    }

    /** A writing leg that has the key's folder held open. */
    private interface PinnedLeg {
        void run(PinnedDirectory pinned) throws IOException;
    }

    /** The same leg where the platform has no {@code openat}, spelled as paths. */
    private interface PathLeg {
        void run() throws IOException;
    }

    @Override
    public void append(String path, String content) {
        Objects.requireNonNull(content, "content");
        Located located = requireItIsNotALink(path);
        Path file = located.resolved();
        requireTheKeyIsFree(located);
        try {
            throughTheKeysFolder(located,
                    pinned -> appendWithoutFollowing(located.key(), pinned, content),
                    () -> {
                        Files.createDirectories(file.getParent());
                        appendWithoutFollowing(located.key(), file, content);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append memory " + path, e);
        }
    }

    /**
     * Refuses a write whose root has been repointed, before anything here inspects a path.
     *
     * <p><strong>Not the containment, and it must not be read as it.</strong> What keeps a
     * rotated root from being written to is {@code PinnedDirectory.rootAsOpened}, which
     * every descent goes through and which compares an inode rather than a name. This is
     * one open and one {@code fstat} spent earlier for two smaller reasons, both of them
     * about what happens <em>before</em> the descent runs.
     *
     * <p>The first is blast radius. The three questions below are all spelled as paths —
     * {@code Files.isRegularFile} on an ancestor's name, and a {@code walkFileTree} of
     * whatever stands at the key — so on a repointed root they enumerate a stranger's
     * directory and decide this store's answer from what they find there. Nothing is
     * written and nothing reaches the model but a refusal, which is why this is a tidiness
     * argument rather than a containment one; a store that reads somebody else's directory
     * listing to word an error message is still doing something it has no business doing.
     *
     * <p>The second is the message. Without this, a repointed root reaches the model
     * through whichever of the three below happens to trip first, and the file already
     * records what that costs: "One root cause, two answers, depending on whether a
     * leftover empty folder happened to be sitting there."
     *
     * <p>Its answer is deliberately not carried anywhere. A root that is rotated back
     * between here and the descent is a root the descent accepts, which is correct — it is
     * this store's root again — and one rotated away is refused there. This narrows nothing
     * and is not counted on to.
     */
    private void requireTheRootIsStillOurs(String key) {
        try (DirectoryStream<Path> stillOurs = PinnedDirectory.rootAsOpened(root, rootIs)) {
            // Opened and handed straight back; the open IS the question. A missing root
            // arrives here too, and gets the same medium error it got from the descent
            // before this method existed.
        } catch (IOException notOurs) {
            throw new UncheckedIOException("Failed to write memory " + key, notOurs);
        }
    }

    /**
     * Refuses a key that collides with the hierarchy, before the filesystem does.
     *
     * <p>The filesystem would refuse these anyway — that is where #83's divergence came
     * from — but it refuses them as {@code FileAlreadyExistsException} or
     * {@code FileSystemException}, which differ by platform and reach the model as "Memory
     * operation failed." Asking first turns both into the reason {@link MemoryNamespace}
     * states, identically to the in-memory store.
     *
     * <p>Three questions, not two (#115). The first two are about <em>documents</em> — an
     * empty folder is easy to come by and treating one as a collision would poison the key
     * permanently — and a folder holding entries that are <em>not</em> documents answered
     * both of them "free" and then could not be got out of the way. The third asks
     * {@link #clearTheWay} whether it succeeded, which is the only one of the three whose
     * answer actually predicts whether the write can proceed.
     *
     * <p>Not a lock. A directory can appear between this check and the write, and then the
     * I/O error stands — which is the honest outcome for a race rather than something worth
     * pretending away.
     */
    private void requireTheKeyIsFree(Located located) {
        String key = located.key();
        requireTheRootIsStillOurs(key);
        // Once, and it used to be twice. holdsAnyDocument was whatStandsAt(file) ==
        // A_DOCUMENT and the line below asked whatStandsAt(file) again, so every write to a
        // key with a folder at it walked the same subtree twice and paid twice for #146's
        // measurement. One walk, one answer, and the answer is what every question below
        // reads.
        AtTheKey found = whatStandsAt(located);
        Standing standing = found.standing();
        MemoryNamespace.requireFree(key,
                above -> Files.isRegularFile(root.resolve(MemoryFilenames.onDisk(above))),
                folder -> standing == Standing.A_DOCUMENT);
        if (standing == Standing.NOT_A_FOLDER_ABOVE) {
            // #188, and it is placed AFTER requireFree deliberately. An ancestor that is a
            // regular file reaches both: the descent refuses the component, and requireFree
            // refuses the key. requireFree's message is the better one for that case -- it
            // names the rule ("a key cannot be both a document and a folder") rather than
            // the geometry -- so it must go first, and what is left here is the case it
            // cannot see: a FIFO, a socket or a device node part way along, which is not a
            // regular file and so is not a hierarchy collision.
            //
            // The component is named because the descent already knew which one it was.
            // #188 called that the "complete" fix and priced it at a second walk stat-ing
            // every ancestor, with its own check-to-open race, on the hot path of every
            // write; through the pinned descent it costs nothing, because the descent IS
            // that walk and it is holding the parent's descriptor when it asks.
            //
            // MemoryStore'S CONTRACT STILL SAYS THE OLD BEHAVIOUR, and deliberately so:
            // "At the key itself, and not at an ANCESTOR component -- a FIFO at 'a' still
            // makes 'a/b.md' a medium error rather than this refusal, which is #188." That
            // sentence is now false and this file is not the place to fix it, because
            // MemoryStore's contract is being worked in the same batch (#153, #147) and two
            // hands on one paragraph is how a contract ends up saying two things. The
            // wording it needs is in this branch's pull request; the behaviour is here.
            throw MemoryRefusal.NOT_A_DOCUMENT.of(
                    "cannot write '" + key + "': '" + found.ancestor() + "' stands in that"
                            + " path and is not a folder, so no key can live under it —"
                            + " choose a key that does not pass through it");
        }
        if (standing == Standing.TOO_MANY_ENTRIES) {
            // #146. Refused with a reason rather than walked, which is what this class does
            // with every other question it cannot answer cheaply, and the honest cost is
            // stated in PinnedDirectory.survey: a document stops the walk, so a subtree
            // reaching this bound is one in which nothing examined was a document -- and a
            // subtree holding no document holds no memory.
            //
            // "none of the ones it looked at", not "none of it". The walk stopped, so what
            // stands past the bound is exactly what this store declines to know; a message
            // claiming the whole subtree holds no document would be asserting the thing the
            // bound exists to avoid finding out.
            //
            // A refusal reason MemoryStore's contract does not list yet, for the reason the
            // branch above gives: that list is being worked in the same batch.
            throw MemoryRefusal.NOT_DECIDABLE_WITHIN_A_BOUND.of(
                    "cannot write '" + key + "': more than " + SUBTREE_ENTRIES + " entries"
                            + " stand under that key and none of the ones this store looked"
                            + " at is a document, and it will not walk further to decide"
                            + " whether the key is free — choose another key, or have the"
                            + " folder cleared");
        }
        if (standing == Standing.TOO_DEEP) {
            // #146, and the half the entry bound cannot cover: a chain one entry wide is one
            // entry per level, and the walk holds a descriptor per level. The depth is
            // derived rather than picked -- see SUBTREE_DEPTH -- so what is refused here is
            // nested deeper than any key of this store can be.
            throw MemoryRefusal.NOT_DECIDABLE_WITHIN_A_BOUND.of(
                    "cannot write '" + key + "': folders are nested more than " + SUBTREE_DEPTH
                            + " deep under that key, which is deeper than any key of this"
                            + " store can be, and it will not descend further to decide"
                            + " whether the key is free — choose another key, or have the"
                            + " folder cleared");
        }
        if (standing == Standing.SOMETHING_ELSE) {
            // #115. The two questions above are about *documents*, deliberately — an empty
            // folder is easy to come by, since a write that fails after createDirectories
            // leaves exactly one, and calling that a collision would poison the key
            // permanently. clearTheWay removes those. What neither covers is a folder
            // holding entries which are not documents. Symbolic links are the case that
            // arises, since this store stopped treating them as memories in #99 — but a
            // FIFO, a socket or a device node does it just as well, and none of those has
            // anything to do with #99. An earlier version of this comment named symbolic
            // links and meant them as the whole set.
            //
            // Such a key was reported free, could not be cleared, and the write then walked
            // into the open and died on "Is a directory" — which MemoryTools reduces to
            // "Memory operation failed.", a message the model cannot act on. (The open was
            // Files.writeString when this was found; it is Files.newOutputStream since #143,
            // and it was verified to fail the same way, so the reasoning is unchanged.)
            // That is the exact failure this method exists to prevent, and its own javadoc
            // says so: the asking happened, it just asked a question whose answer did not
            // predict whether the write could proceed. Asking a third settles it.
            throw MemoryRefusal.NOT_A_DOCUMENT.of(
                    "cannot write '" + key + "': a folder stands there holding entries that"
                            + " are not documents, and a key cannot be both a document and a"
                            + " folder — choose another key");
        }
        if (standing == Standing.NOT_A_DOCUMENT) {
            // Its own message, because "a folder stands there" is not true of a FIFO and
            // the model is the reader that matters: MemoryTools hands this string through
            // while reducing everything else to "Memory operation failed."
            throw MemoryRefusal.NOT_A_DOCUMENT.of(
                    "cannot write '" + key + "': something that is not a document stands"
                            + " there — a FIFO, a socket or a device node — and this store"
                            + " only writes regular files; choose another key");
        }
        if (!clearTheWay(located, standing)) {
            // A different cause and so a different channel. The folder held nothing but
            // folders and still would not go, which means the medium refused — a read-only
            // root is the case — and MemoryStore's contract assigns that to
            // UncheckedIOException: "a disk that is full or read-only is an
            // UncheckedIOException, and no contract can promise it away."
            //
            // The first version reported it as the argument error above, telling the model
            // to choose another key on a root where every key fails the same way. One root
            // cause, two answers, depending on whether a leftover empty folder happened to
            // be sitting there.
            throw new UncheckedIOException("Failed to write memory " + key,
                    new IOException("a folder at that key could not be removed"));
        }
    }

    /**
     * Removes an empty folder standing where a document is about to be written, and says
     * whether the way is now clear.
     *
     * <p>Repair at the point of need, rather than tidying after a delete. An earlier version
     * pruned empty folders inside {@code delete}, which put the work in the wrong place
     * three times over: it could not reach a folder left by a <em>failed write</em>, it
     * unlinked symbolic links to folders whose targets still held live keys, and it made
     * {@code delete} on one key fail a concurrent write on another. Here it runs only when a
     * write has already established that no key lives under the name, so what is removed
     * holds nothing.
     *
     * <p>Depth-first and real directories only.
     *
     * <p><strong>It used to return nothing and leave failure "to the write that follows"
     * (#115.)</strong> The write that follows dies on "Is a directory" and arrives at the
     * model as "Memory operation failed." —
     * so the folder that would not go said nothing anybody could act on. The case is a
     * folder holding entries that are not documents — symbolic links most often, since #99,
     * and equally a FIFO or a device node:
     * {@code holdsAnyDocument} reports the key free because none of them is a document, and
     * then {@code deleteIfExists} raises {@code DirectoryNotEmptyException} on a folder that
     * is plainly not empty. Answering the question is what turns that into a refusal with a
     * reason.
     *
     * <p>The equivalence below holds single-threaded and not under a race: with the tree
     * replaced by a regular file between the two, {@code isDirectory} sees the file and
     * allows an ordinary update while {@code exists} refuses. Measured at 11 of 12 trials
     * with a sleep injected at the divergence, and never in eight seconds of natural
     * racing. Refusing is the safe direction for a check that is not a lock.
     *
     * @return whether anything is still standing at {@code file}. Not "a regular file can
     *     now be written", and the difference used to matter here: a FIFO made the open
     *     block for a reader that never came, and a character device accepted the bytes and
     *     stored nothing. This sentence used to end "neither is affected by any of this",
     *     which #145 was opened to record and #145's fix removed — {@code requireTheKeyIsFree}
     *     now refuses both two lines above the call, so neither standing can reach this
     *     method. The distinction is kept because the predicate still answers the narrower
     *     question, and depending on a caller's guard is what made the old sentence true.
     */
    private boolean clearTheWay(Located located, Standing standing) {
        Path file = located.resolved();
        if (standing != Standing.ONLY_EMPTY_FOLDERS) {
            // Nothing to remove, or nothing this may remove. The caller has already turned
            // the second into a refusal, and it does so *before* calling here — which the
            // first version did not: it deleted depth-first and discovered the obstruction
            // on the way back up, so a refused write had already pruned every empty folder
            // below it. Nothing was lost, since an empty folder holds nothing, but an
            // operation that refuses should refuse rather than half-succeed, and the test
            // asserting "refusing is not deleting" checked only the link.
            return standing == Standing.NOTHING_IN_THE_WAY;
        }
        // At-relative since #168. This was the last destructive syscall in the write path
        // still spelled as a path, and a path is re-resolved on every call: with an
        // intermediate component flipped to a link pointing outside, the walk below removed
        // directories out there. Narrower than write's or delete's reach — it only ever
        // removed *empty* directories, so nothing outside could be lost — but a directory
        // outside the root being removed is still a write outside the root.
        try (PinnedDirectory pinned =
                PinnedDirectory.toFind(root, rootIs, located.relative(root))) {
            if (pinned == null) {
                // No folder to reach the key through, so nothing is standing at it and the
                // write that follows makes the folders itself. Same answer the exists()
                // question below used to give for an absent parent.
                return true;
            }
            if (!pinned.isPinned()) {
                return clearByWalking(file);
            }
            try {
                pinned.clear();
            } catch (IOException couldNotClear) {
                // Logged rather than reported, and the caller refuses either way. The
                // operator gets the cause; the model gets "a folder at that key could not
                // be removed", which is the actionable half and does not guess at why —
                // this branch and a folder holding links are different causes with the same
                // answer.
                LOG.debug("Could not clear the folder at {}: {}", file,
                        Quoted.of(couldNotClear.toString()));
            }
            // Asked of the filesystem rather than inferred from the loop. Clearing stops on
            // the first thing that is not an empty folder, and the catch above swallowed
            // that by design, so "the clearing returned" never meant "the folder is gone".
            //
            // "Is anything still standing here", not "is it still a directory", because
            // what follows is the open and that fails on anything which is not a writable
            // regular file. Asked through the pinned folder rather than as
            // Files.exists(file, NOFOLLOW), which is the same question of the same name one
            // fstatat later -- and which a swapped component would answer about somebody
            // else's directory, in the one method whose answer decides whether a write
            // proceeds.
            return pinned.standing() == null;
        } catch (PinnedDirectory.RootRotated rotated) {
            // Not "the folder would not go" (#165). Answering false here would reach the
            // caller as "a folder at that key could not be removed", which is a true
            // sentence about the wrong subject: the key is fine and the store's root has
            // been taken away. One root cause, one answer — the mistake this method's
            // caller already records making once.
            throw new UncheckedIOException("Failed to write memory " + located.key(), rotated);
        } catch (IOException couldNotLook) {
            // The descent itself would not go: an intermediate component that is a link, a
            // document, or a medium that refused. Nothing was cleared, so nothing is clear.
            LOG.debug("Could not reach the folder at {}: {}", file,
                    Quoted.of(couldNotLook.toString()));
            return false;
        }
    }

    /**
     * {@link #clearTheWay}'s removal where the platform has no {@code openat}.
     *
     * <p>The code that was there before #168, unchanged and reachable only on a platform
     * whose {@code Files.newDirectoryStream} is not a {@link SecureDirectoryStream}. Kept
     * rather than deleted for the reason {@link #writeWithoutFollowing(Path, String)} gives:
     * deleting it would leave that platform with no way to clear a folder at all.
     */
    private boolean clearByWalking(Path file) {
        try {
            Files.walkFileTree(file, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult postVisitDirectory(Path folder, IOException failed)
                        throws IOException {
                    // After its children, which is what depth-first means and what the
                    // sorted list was standing in for. deleteIfExists still refuses a
                    // non-empty folder, so this cannot outrun the check above if the tree
                    // changed underneath: it stops, and the caller refuses.
                    Files.deleteIfExists(folder);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException couldNotLook) {
            LOG.debug("Could not clear the folder at {}: {}", file,
                    Quoted.of(couldNotLook.toString()));
        }
        return !Files.exists(file, LinkOption.NOFOLLOW_LINKS);
    }

    /** What a write finds standing at its key's path. */
    private enum Standing {
        /** No directory here; a regular file may be written or overwritten. */
        NOTHING_IN_THE_WAY,
        /** A tree of directories and nothing else, which {@link #clearTheWay} may remove. */
        ONLY_EMPTY_FOLDERS,
        /** At least one document, which is the collision {@link MemoryNamespace} words. */
        A_DOCUMENT,
        /** Entries that are neither documents nor directories, or a tree we cannot read. */
        SOMETHING_ELSE,
        /**
         * A FIFO, a socket or a device node standing at the key itself (#145).
         *
         * <p>Separate from {@link #SOMETHING_ELSE}, which is about what a <em>folder</em>
         * holds. This one is not a folder at all, and it needs its own answer because the
         * two failures are different: that one cannot be cleared, this one must never be
         * opened.
         */
        NOT_A_DOCUMENT,

        /**
         * A component of the key's path is not a folder, so no key can live under it (#188).
         *
         * <p>Not {@link #NOTHING_IN_THE_WAY}, which is where every cause of "I could not
         * look" used to land together. That fold is still right for the cause it was written
         * for — an unsearchable parent, where every key under it fails alike and "choose
         * another key" is the mistake {@code requireTheKeyIsFree} already records making
         * once — and it was wrong for this one, where a sibling key genuinely does work.
         * {@code PinnedDirectory.ComponentIsNotAFolder} is what tells them apart, and it
         * names the component.
         */
        NOT_A_FOLDER_ABOVE,

        /** More stands under the key than the store will enumerate to decide (#146). */
        TOO_MANY_ENTRIES,

        /** More is nested under the key than the store will descend to decide (#146). */
        TOO_DEEP
    }

    /**
     * How many entries a write will look at under its key before refusing (#146).
     *
     * <p>The number is chosen against what the walk that reaches it actually is, which is
     * narrower than "a subtree": a document stops the walk, so the only subtree enumerated in
     * full is one holding <strong>no document at all</strong>, and such a subtree holds no
     * memory. {@code PinnedDirectory.survey} carries that argument and the measurement behind
     * it — 500 keys under one prefix answer in under a millisecond at every population tried,
     * because the first document settles it.
     *
     * <p>So the population this has to clear is not "keys under a prefix" but the residue of
     * {@code write} then {@code delete} at distinct sub-prefixes of one key, one directory per
     * pair, which is the shape #146 measured as model-reachable. 4,096 of those is more
     * write-and-delete pairs under a single key than a default {@code AgentConfig.maxSteps} of
     * ten could make in four hundred runs against one store.
     *
     * <p>And it holds the cost to a constant. Measured on this machine, best of three, with
     * the tree planted directly so the number is the walk's and not the planting's:
     *
     * <pre>
     * entries under "a"    write("a") before    write("a") after
     *      4,097                  277 ms              123 ms   refused
     *     20,000                1,347 ms              129 ms   refused
     *    100,000                7,033 ms              121 ms   refused
     * </pre>
     *
     * <p>Flat, which is the property being bought: the attacker chooses the tree and no
     * longer chooses the cost. A number an order of magnitude smaller would buy about 12 ms
     * and start refusing roots a long-lived store could plausibly reach; one an order larger
     * spends 1.2 seconds and stops being a bound worth having.
     */
    private static final int SUBTREE_ENTRIES = 4_096;

    /**
     * How many folders deep a write will descend under its key before refusing (#146).
     *
     * <p>Derived rather than picked. {@code MemoryKeys} admits {@code MAX_KEY_BYTES} of
     * encoded key and {@code /} is never escaped, so a key costs at least two bytes per
     * component and no key of this store has more than {@code MAX_KEY_BYTES / 2} of them.
     * A document nested deeper than that under a key is therefore not a memory this store
     * wrote, and refusing to descend to it cannot make a real key unreachable.
     *
     * <p>Bounded at all because the walk holds one descriptor per level. The entry budget
     * above does not do this job on its own — a chain one entry wide is one entry per level —
     * and unbounded depth here would be a file-descriptor exhaustion reachable by
     * {@code mkdir -p} alone.
     */
    private static final int SUBTREE_DEPTH = MemoryFilenames.MAX_KEY_BYTES / 2;

    /**
     * What a write found at its key, and — where the answer is about an ancestor — which one.
     *
     * <p>A record rather than the bare {@link Standing} because {@link #NOT_A_FOLDER_ABOVE}
     * is the one answer whose message has to name something the enum cannot carry. Null
     * everywhere else, and only ever read in that branch.
     */
    private record AtTheKey(Standing standing, String ancestor) {

        static AtTheKey of(Standing standing) {
            return new AtTheKey(standing, null);
        }
    }

    /**
     * What is in the way at a key's path, decided at-relative and with a budget.
     *
     * <h4>The walk this asked with was the last one in the write path spelled as a path</h4>
     *
     * <p>It was {@code Files.readAttributes} of the key's path followed by
     * {@code Files.walkFileTree} of it, and both halves were re-resolved by the kernel from
     * the root down on every call, in the one method whose answer decides whether a write
     * proceeds. {@code read}, {@code write}, {@code append}, {@code delete} and {@code list}
     * have all gone through {@link PinnedDirectory} since #168; this one did not, and the two
     * issues it left open are opposite halves of the same sentence.
     *
     * <p><strong>#236 — how it resolved.</strong> {@code walkFileTree} opens every directory
     * it descends into with {@code Files.newDirectoryStream}, and that {@code open(2)} carries
     * no {@code O_DIRECTORY}, so a FIFO raced under the key blocked the thread inside the
     * JDK's own walk. {@code PinnedDirectory.withoutHangingOnAFifo} frees every such open
     * <em>this package</em> makes and could not reach that one. Measured with everything in
     * #235 applied: 3, 4 and 6 of 2,000 attempts still blocked, every one of them in
     * {@code Files.walkFileTree}. Through {@link PinnedDirectory#survey} the walk is this
     * package's again and the wrapper reaches it.
     *
     * <p><strong>#146 — how it was bounded, which was not at all.</strong>
     * {@link PinnedDirectory#survey} carries the measurement, the bound, and the argument for
     * why a bound here does not refuse a real key.
     *
     * <p><strong>What the descent adds beyond both.</strong> The path version could not say
     * <em>why</em> a key was unreachable: {@code ENOTDIR} from a FIFO at an ancestor and
     * {@code EACCES} from an unsearchable parent arrived as the same {@link IOException} and
     * were folded into {@link Standing#NOTHING_IN_THE_WAY} together (#188). The descent asks
     * one component at a time, so they arrive as different exceptions and the first of them
     * names the component.
     *
     * <h4>What it costs, stated rather than discovered</h4>
     *
     * <p>An ordinary write pays for this. The question used to be two {@code lstat}s of one
     * path — one per call, and it was called twice — and it is a descent now: the root
     * opened and {@code fstat}ed, then one {@code fstatat} and one {@code openat} per
     * component, each under a rendezvous that costs a parked virtual thread. Measured, best
     * of six rounds of 2,000 writes into fresh roots:
     *
     * <pre>
     * per 2,000 writes        before   after
     * a/bN/cI.md  fresh       376 ms   464 ms      +44 us per write
     * a/bN/cI.md  overwrite   341 ms   526 ms      +93 us per write
     * nI.md       fresh       334 ms   403 ms      +35 us per write
     * nI.md       overwrite   290 ms   379 ms      +45 us per write
     * </pre>
     *
     * <p>That is the price of the two defects above, and it is paid on every write rather
     * than only on the hostile ones. It is worth naming what it buys: not a faster refusal,
     * but a walk that cannot be redirected by a component swapped mid-question and cannot be
     * wedged by a FIFO raced into it.
     *
     * <p><strong>The obvious saving was measured and not taken.</strong>
     * {@link #requireTheRootIsStillOurs} opens the root and {@code fstat}s it one line before
     * this method opens the root and {@code fstat}s it, and its own javadoc's two reasons —
     * blast radius, and one message for one root cause — are both covered here now: this
     * runs before {@code requireFree}'s path question, and it turns {@code RootRotated} into
     * the same {@code UncheckedIOException} that method does. Deleting it recovers 403 ms to
     * 316 ms on a flat key and 464 ms to 425 ms on a deep one, which is most of the flat
     * regression and a quarter of the deep one. It stays: it is another issue's
     * defence-in-depth (#165), the argument for removing it rests entirely on this method
     * having just been reordered, and a security line removed as a side effect of a
     * performance measurement is the wrong shape of change. It is worth its own decision.
     *
     * <h4>What a mutation pass does not kill here</h4>
     *
     * <p>Two, and both are stated because a surviving mutant that nobody wrote down is a
     * defect waiting to be reintroduced.
     *
     * <ul>
     *   <li><strong>The symbolic-link branch answering {@code NOTHING_IN_THE_WAY}.</strong>
     *       Unreachable from either writer, and provably: {@code requireItIsNotALink} asks
     *       about every component including the last, and refuses, before this method is
     *       called. It is kept because it is what the path version answered for the same
     *       node, and a branch that quietly changes its answer under a refactor is how this
     *       kind of thing rots.</li>
     *   <li><strong>The {@code RootRotated} branch answering
     *       {@code NOTHING_IN_THE_WAY}.</strong> {@link #requireTheRootIsStillOurs} refuses a
     *       rotated root one line before this runs, so a rotation that is standing still
     *       never reaches here — which is {@code RotatedRootTest}'s shape. What reaches here
     *       is a rotation landing in the microseconds between the two, and this branch is
     *       what keeps "one root cause, one answer" true for it. Not equivalent; not
     *       reachable by a test that does not race those two calls specifically.</li>
     * </ul>
     *
     * <p><strong>What it does not change.</strong> An unsearchable ancestor is still folded
     * into {@link Standing#NOTHING_IN_THE_WAY}, deliberately and for the reason the old
     * comment gave at length: every key under that parent fails the same way, so telling the
     * model to choose another is the mistake {@code requireTheKeyIsFree} already records
     * making once, and the write that follows fails with the {@code AccessDeniedException}
     * naming the ancestor that {@code MemoryStore}'s contract assigns to the medium.
     */
    private AtTheKey whatStandsAt(Located located) {
        try (PinnedDirectory pinned =
                PinnedDirectory.toExamine(root, rootIs, located.relative(root))) {
            if (!pinned.isPinned()) {
                return AtTheKey.of(whatStandsAtByName(located.resolved()));
            }
            return AtTheKey.of(whatStandsAt(pinned));
        } catch (PinnedDirectory.ComponentIsNotAFolder notAFolder) {
            // #188. Before the NoSuchFileException below, which it does not extend, and
            // before the general IOException, which it does.
            return new AtTheKey(Standing.NOT_A_FOLDER_ABOVE,
                    ancestorAt(located.key(), notAFolder.component()));
        } catch (NoSuchFileException nothingThere) {
            // A component of the path is absent, so nothing stands at the key and the write
            // that follows makes the folders itself. The same answer the path version gave
            // for the same cause.
            return AtTheKey.of(Standing.NOTHING_IN_THE_WAY);
        } catch (PinnedDirectory.RootRotated rotated) {
            // Not an answer about the key (#165). The path version never noticed a rotated
            // root here at all -- it walked whatever the name led to and worded this store's
            // refusal from a stranger's directory listing, which is the blast radius
            // requireTheRootIsStillOurs was added to narrow. Reported the way clearTheWay
            // reports it: one root cause, one answer.
            throw new UncheckedIOException("Failed to write memory " + located.key(), rotated);
        } catch (IOException cannotLook) {
            // An unsearchable parent, and everything else the medium would not answer. See
            // the javadoc: this fold is the one the old code got right.
            LOG.debug("Could not examine {}: {}", located.resolved(),
                    Quoted.of(cannotLook.toString()));
            return AtTheKey.of(Standing.NOTHING_IN_THE_WAY);
        }
    }

    /**
     * The key's own node, and then what is under it if it is a folder.
     *
     * <p>Split from the descent above so that the node questions — which are one
     * {@code fstatat} against a descriptor this store opened — read as the cheap answers they
     * are, and so that the walk is reached only by the one standing that needs it.
     */
    private static Standing whatStandsAt(PinnedDirectory pinned) throws IOException {
        BasicFileAttributes attributes = pinned.standing();
        if (attributes == null) {
            return Standing.NOTHING_IN_THE_WAY;
        }
        if (attributes.isRegularFile()) {
            // A document, which a write overwrites. This is the overwhelmingly common
            // answer and the reason the walk below costs nothing in practice.
            return Standing.NOTHING_IN_THE_WAY;
        }
        if (attributes.isOther()) {
            // isOther, not !isDirectory. The two differ only for a symbolic link, which
            // requireItIsNotALink refuses before either writer reaches here — so the
            // broader form carried an unreachable branch that no test could kill, and a
            // mutant swapping one for the other passed all 1,015 core tests. The narrow
            // form says what this branch is actually for: a FIFO, a socket or a device
            // node, which is exactly what isOther means.
            // #145. This used to be folded into NOTHING_IN_THE_WAY by asking only
            // isDirectory, so a FIFO or a device node at the key was reported free and
            // handed to the open. Measured:
            //
            //   FIFO           write BLOCKED (still waiting after 5s), forever
            //   device node    write returned normally, and nothing was stored
            //   unix socket    write threw UncheckedIOException
            //
            // The first is the one that matters. A hang is not a failure a caller can
            // observe: no error, no result, nothing in the transcript, and no timeout to
            // trip. The second is a store confirming a memory it does not hold, so the
            // next read answers empty after the model was told the write succeeded.
            //
            // read has always asked this question — Files.isRegularFile, and it answers
            // Optional.empty for all three — so this is the write side finally asking what
            // the read side already did.
            //
            // A check, not a lock — but for write the race it leaves is now closed by
            // renaming rather than opening, so this branch is about the node that is THERE
            // when the key is examined: an operator's deliberate mount, refused and named
            // rather than silently replaced.
            //
            // An earlier version of this comment argued the residual race was negligible,
            // on numbers that were wrong twice over. It reported "0 hangs in 300 attempts,
            // 1 in 3,000" — the second from a loop bound, while the loop broke on the first
            // hang and its own output summed to 225 attempts. Re-measured with a pre-made
            // FIFO linked and unlinked rather than a process forked per flip: 88, 84 and 81
            // hangs in 300, about 28%. It then called that "far narrower than #143's
            // symlink window", which #164 had already closed — the live successor race
            // (#168) is roughly 1 in 100 to 1 in 400, so the FIFO window was WIDER than the
            // race it was favourably compared to. And counting the two syscalls in between
            // is the move writeWithoutFollowing's own javadoc disavows: "the figure was
            // doing rhetorical work — making the window sound tight — that no measurement
            // supports."
            return Standing.NOT_A_DOCUMENT;
        }
        if (!attributes.isDirectory()) {
            // A symbolic link, and unreachable from either writer: requireItIsNotALink asks
            // about the key's own component before this method is called. Answered rather
            // than fallen through because that is what the path version did -- walkFileTree
            // does not follow a link at the start path, so it visited it as a file, found it
            // was not a regular one, and answered exactly this. The old comment here claimed
            // a link "would fall through to NOTHING_IN_THE_WAY", which was not what the code
            // did; the standing is preserved rather than the sentence.
            return Standing.SOMETHING_ELSE;
        }
        try {
            return switch (pinned.survey(SUBTREE_ENTRIES, SUBTREE_DEPTH)) {
                case A_DOCUMENT -> Standing.A_DOCUMENT;
                case NOTHING_BUT_FOLDERS -> Standing.ONLY_EMPTY_FOLDERS;
                case SOMETHING_ELSE -> Standing.SOMETHING_ELSE;
                case TOO_MANY_ENTRIES -> Standing.TOO_MANY_ENTRIES;
                case TOO_DEEP -> Standing.TOO_DEEP;
            };
        } catch (IOException cannotWalk) {
            // SOMETHING_ELSE and not the caller's NOTHING_IN_THE_WAY, which is the
            // distinction the two catches in the path version made and which a first draft
            // of this method lost by letting one exception out through one door.
            //
            // The path version folded every failure of readAttributes of the KEY into
            // NOTHING_IN_THE_WAY -- the write that follows fails with the medium error
            // naming the cause -- and every failure of walkFileTree of what is UNDER it into
            // SOMETHING_ELSE, because unreadable is not empty and refusing is what keeps a
            // write from landing on top of documents nobody could enumerate. Opening the
            // key's own folder belongs to the second, and the first draft gave it to the
            // first. Measured as uid 65534, with a mode-000 folder standing at the key and
            // one document inside it:
            //
            //   origin/main              IllegalArgumentException, "a folder stands there
            //                            holding entries that are not documents"
            //   the first draft          UncheckedIOException -> "Memory operation failed."
            //   with this catch          IllegalArgumentException, as before
            //
            // The middle row is the regression, and it is the exact outcome #115 exists to
            // remove. It does NOT reproduce as root, because chmod 000 is not enforced
            // against uid 0 at all -- so NonDocumentAtAKeyTest's pin for it skips there, and
            // a mutant folding this back survives a root-only run. Said here rather than
            // left to be rediscovered.
            //
            // pinned.standing() above still throws into the caller's fold, which is the
            // first of the two and is deliberate.
            LOG.debug("Could not survey what is under the key: {}",
                    Quoted.of(cannotWalk.toString()));
            return Standing.SOMETHING_ELSE;
        }
    }

    /**
     * The ancestor key standing {@code component} components down from the root.
     *
     * <p>In the key's vocabulary, not the filesystem's. The descent counts components of the
     * <em>encoded</em> name and the model asked about the key, which is the distinction
     * {@link #inTheCallersVocabulary} exists for — and it is a counting job rather than a
     * decoding one, because {@code MemoryFilenames.onDisk} leaves {@code /} alone, so the two
     * spellings have the same components in the same order.
     */
    private static String ancestorAt(String key, int component) {
        int cut = -1;
        for (int seen = 0; seen <= component; seen++) {
            cut = key.indexOf('/', cut + 1);
            if (cut < 0) {
                // Unreachable: the descent only walks components above the last, so a
                // refusal at index n means the key has at least n + 2 of them. Answered
                // rather than thrown because a message is not worth an exception, and the
                // whole key is the true superset of any ancestor of it.
                return key;
            }
        }
        return key.substring(0, cut);
    }

    /**
     * {@link #whatStandsAt(Located)} where the platform has no {@code openat}.
     *
     * <p>The code that was there before #236, unchanged except for the two answers it never
     * had, and reachable only on a platform whose {@code Files.newDirectoryStream} is not a
     * {@link SecureDirectoryStream}. Kept rather than deleted for the reason
     * {@link #clearByWalking} and {@link #walkByName} are: deleting it would leave that
     * platform unable to write at all. It is unbounded and it resolves by name, which is
     * #146 and #236 exactly — that platform has neither {@code openat} to be at-relative with
     * nor a way to bound a walk it does not own, and saying so is better than a fallback that
     * quietly reinstates the defect somewhere no CI looks.
     *
     * <p>{@code walkFileTree} rather than {@code Files.walk}. A stream's terminal operation
     * raises {@code UncheckedIOException} for any directory it cannot open; that is a
     * {@code RuntimeException}, and no {@code catch (IOException)} could see it. Measured: a
     * subtree planted past {@code PATH_MAX} made {@code write} throw
     * {@code UncheckedIOException} out of this question, which {@code MemoryTools} reduces to
     * "Memory operation failed." — the precise outcome #115 exists to remove, reached through
     * a sibling door in the same method that removes it. It also retained the whole tree:
     * {@code Files.walk(...).sorted().toList()} materialised every path, and 500,000 entries
     * under one key exhausted a 48 MB heap from inside {@code write}.
     */
    private static Standing whatStandsAtByName(Path folder) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(folder, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException nothingThere) {
            return Standing.NOTHING_IN_THE_WAY;
        } catch (IOException cannotLook) {
            // ENOTDIR and EACCES arrive here as the same exception, which is #188 and is
            // why the pinned answer above exists. Nothing here can tell them apart, so the
            // fold that is right for one of them is kept for both.
            LOG.debug("Could not examine {}: {}", folder, Quoted.of(cannotLook.toString()));
            return Standing.NOTHING_IN_THE_WAY;
        }
        if (attributes.isRegularFile()) {
            return Standing.NOTHING_IN_THE_WAY;
        }
        if (attributes.isOther()) {
            return Standing.NOT_A_DOCUMENT;
        }
        Standing[] found = {Standing.ONLY_EMPTY_FOLDERS};
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        found[0] = Standing.A_DOCUMENT;
                        return FileVisitResult.TERMINATE;
                    }
                    found[0] = Standing.SOMETHING_ELSE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException cannotLook) {
                    found[0] = Standing.SOMETHING_ELSE;
                    return FileVisitResult.TERMINATE;
                }
            });
        } catch (IOException cannotLook) {
            return Standing.SOMETHING_ELSE;
        }
        return found[0];
    }

    @Override
    public boolean delete(String path) {
        Optional<Located> located = locateForReading(path);
        if (located.isEmpty()) {
            return false;
        }
        // The path this unlinks is the one SafePaths resolved, and what makes that safe is
        // that resolveForReading answered at all: it returns empty for anything reached
        // through a link, so nothing that got here was.
        //
        // It used to say the resolved path and the lexical name are "provably the same
        // string", which was carried by the comparison this class no longer makes — and
        // which was never the property that mattered. On a filesystem that folds case, the
        // platform #112 is about, they differ for essentially every key, and the resolved
        // path is still the right thing to unlink because it is the realpath. What the
        // check buys is that no link was followed to get it.
        //
        // Deleting through a link destroyed the document a *different* key named, left the
        // link that was asked about in place, and returned true — the store reporting
        // success for losing the wrong memory. Re-deriving the name here was the third
        // derivation of it in one call, and a place the two could drift apart.
        Path file = located.get().resolved();
        try (PinnedDirectory pinned =
                PinnedDirectory.toFind(root, rootIs, located.get().relative(root))) {
            if (!standsAsADocument(pinned, file)) {
                // Exactly what exists() asks, so the two can never disagree. Everything
                // that is not a document answers false: a name that is not there, a folder,
                // a link that resolves to a folder or to nothing at all, and a name
                // unreachable because an ancestor holds a document rather than being a
                // folder.
                //
                // Answering true for any of those reported a memory forgotten that was
                // never held — the one row of #83 that was a defect whichever way the
                // totality question went. An earlier attempt asked NOFOLLOW_LINKS, which
                // made every symbolic link "not a directory" and so deletable: a link to a
                // folder, a dangling link and a self-referencing loop all answered true
                // while exists() answered false.
                return false;
            }
            if (!pinned.isPinned()) {
                return Files.deleteIfExists(file);
            }
            // unlinkat(dirfd, name, 0) — the name inside the directory the descent opened,
            // not a path resolved again from the root. This is the syscall #168 measured
            // destroying a file outside the root 5, 31 and 9 times in 20,000 attempts
            // while it was spelled Files.deleteIfExists(file); through here, 0.
            //
            // unlinkat removes the name and never the thing a link at that name points at,
            // which is the same promise NOFOLLOW_LINKS makes at an open and the reason a
            // link arriving in the window between the fstatat above and this line costs the
            // link and nothing else.
            pinned.parent().deleteFile(pinned.name());
            return true;
        } catch (NoSuchFileException wentAwayFirst) {
            // Somebody else's delete won. deleteIfExists answered false for that and so
            // does this: nothing was removed *by this call*, which is what the caller is
            // being told.
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete memory " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        Optional<Located> located = locateForReading(path);
        if (located.isEmpty()) {
            return false;
        }
        try (PinnedDirectory pinned =
                PinnedDirectory.toFind(root, rootIs, located.get().relative(root))) {
            return standsAsADocument(pinned, located.get().resolved());
        } catch (IOException couldNotLook) {
            // Total, as it was when this was Files.isRegularFile — which reports every
            // reason it could not look as false rather than raising. A key whose folder
            // cannot even be opened holds no memory this store could hand back, and
            // exists() answering "I do not know" by throwing would be a new failure mode
            // for the one operation whose whole job is to be safe to ask.
            //
            // A rotated root (#165) arrives here too, and is the one cause where this is
            // quieter than its four neighbours: they raise, this logs and answers false.
            // Kept quiet deliberately. exists() is the operation a caller wraps around
            // every other one, so it is the worst place to introduce a throw, and "no, this
            // store holds no memory at that key" is true of a store whose root has been
            // taken away. Whichever operation the caller goes on to run says so loudly.
            LOG.debug("Could not look for {} under {}: {}", Quoted.of(path), root,
                    Quoted.of(couldNotLook.toString()));
            return false;
        }
    }

    @Override
    public List<String> list(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        Listing listing = new Listing(scopeOf(prefix));
        // Opened through the root's recorded inode rather than by re-resolving its name
        // (#165). This line used to be liveRoot(), a toRealPath() per call, and its comment
        // called following a rotation the fix for a disagreement: read followed the rotated
        // name through SafePaths and this walk did not. The disagreement was real and the
        // side chosen was the wrong one — measured, list() reported the entries of an
        // attacker's directory as memory keys in 200 attempts out of 200 after a bare
        // `rm -rf mem && ln -s /attacker mem`, with nothing to race. Both sides now refuse a
        // root that is not this store's root, so they still agree, and the rotation #139's
        // javadoc actually invites -- mv mem mem2 && ln -s mem2 mem, which keeps the inode --
        // is still invisible to both.
        try (DirectoryStream<Path> top = PinnedDirectory.rootAsOpened(root, rootIs)) {
            if (top instanceof SecureDirectoryStream<?> secure) {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> pinned = (SecureDirectoryStream<Path>) secure;
                walkPinned(pinned, listing);
            } else {
                walkByName(root, listing);
            }
        } catch (NoSuchFileException | NotDirectoryException notThere) {
            // A root that is gone, or that is not a directory any more, holds no keys — the
            // same empty answer the isDirectory() guard this replaces gave, and the same one
            // an empty directory gives. A root that has been REPOINTED is a different thing
            // and arrives as PinnedDirectory.RootRotated, which is deliberately not a
            // FileSystemException so that it reaches the caller instead of this branch.
            return List.of();
        } catch (DirectoryIteratorException readdirFailed) {
            // What a DirectoryStream raises when readdir(3) fails part way through, which
            // walkFileTree reported through postVisitDirectory and SimpleFileVisitor
            // rethrew into the same UncheckedIOException. Unwrapped so the cause the caller
            // sees is the I/O error rather than the wrapper.
            throw new UncheckedIOException("Failed to list memory", readdirFailed.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list memory", e);
        }
        listing.report(root);
        return listing.keys();
    }

    /**
     * Walks the root through open directories rather than by name (#168).
     *
     * <p>{@code list()} was the last operation still re-walking a path per directory, and it
     * leaked. {@code walkFileTree} does not follow symbolic links, but it decides a name is a
     * directory with one {@code lstat} and then <em>opens that name</em> — so a component
     * flipped to a link in between is opened, and the entries of a folder outside the root
     * are read as candidate keys. Measured against a thread flipping one intermediate
     * component, 20,000 listings per run and every listing counted: names from outside the
     * root reached the returned list 0, 1 and 1 times, first at listings 12,728 and 10,557.
     * Rarer than the other four windows and narrower — a name, never content, and a key so
     * listed answers absent on the read that follows — but a name from outside the root is
     * still something the model was told and should not have been. Through here: 0, 0, 0.
     *
     * <p><strong>No test races this one, and that is a measurement rather than an
     * opinion.</strong> The other four windows are pinned by {@code MemoryRaceTest}, which
     * fails on all four with this change stashed. This one wins about once in twenty
     * thousand listings, so a test asserting it would pass against unfixed code roughly a
     * third of the time — a regression pin that fails to fail is worse than a figure written
     * down. Widening the harness to eight contested components at once raises it to 5, 4 and
     * 3 wins in 20,000 listings, which would kill that mutant about ninety-nine times in a
     * hundred, and costs about a minute of runner time and eight attacker threads for the
     * narrowest of the five windows. The trade was taken the other way, so the mutant that
     * puts {@code list()} back on {@link #walkByName} <strong>survives the suite</strong>:
     * that is recorded here rather than left for somebody to rediscover.
     *
     * <p>Every entry is stat'd with {@code fstatat} against the descriptor of the folder it
     * is in, and only a directory is descended into, so the folder whose entries are read is
     * by construction the folder the walk opened. The type question is asked first for the
     * reason {@link PinnedDirectory} sets out at length and not as tidying: the
     * {@code openat} behind {@link SecureDirectoryStream#newDirectoryStream} carries no
     * {@code O_DIRECTORY}, so opening a FIFO there does not fail, it blocks forever.
     *
     * <p><strong>Iterative, and that is not a style choice.</strong> The obvious recursion is
     * what {@link PinnedDirectory#clear()} uses, where a depth bound is correct because
     * nothing legitimate is nested that deep. Here it would not be: {@code MemoryKeys} admits
     * 512-component keys, so a bound low enough to protect the stack would silently drop
     * real memories from the listing, and one high enough to keep them would not protect it —
     * anything with write access under the root can nest a directory a hundred thousand deep
     * for the cost of a hundred thousand {@code mkdir}s, and a {@code StackOverflowError}
     * inside {@code list()} is a denial of service {@code walkFileTree} did not have.
     * Descriptors are closed as the walk leaves each folder for the same reason: holding one
     * per level to the end runs a wide tree out of file descriptors.
     */
    private static void walkPinned(SecureDirectoryStream<Path> top, Listing into)
            throws IOException {
        Deque<Level> stack = new ArrayDeque<>();
        stack.push(new Level(top, top.iterator(), null));
        try {
            while (!stack.isEmpty()) {
                Level level = stack.peek();
                if (!level.entries().hasNext()) {
                    stack.pop();
                    if (level.dir() != top) {
                        level.dir().close();
                    }
                    continue;
                }
                Path name = level.entries().next().getFileName();
                Path under = level.spelling(name);
                BasicFileAttributes attrs;
                try {
                    attrs = level.dir().getFileAttributeView(name, BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes();
                } catch (IOException cannotLook) {
                    // visitFileFailed's case, and the same answer: unreadable is not empty.
                    into.unreadable(under);
                    continue;
                }
                if (attrs.isDirectory()) {
                    SecureDirectoryStream<Path> inside = null;
                    try {
                        // Through PinnedDirectory rather than straight at the descriptor, and
                        // that is #236's other half rather than tidying. #235 wrapped every
                        // directory open in PinnedDirectory so a FIFO could not wedge it, and
                        // this one is not in PinnedDirectory, so it was left bare: a FIFO
                        // raced into a folder under the root hung list() inside openat with
                        // nothing to reach it. The type question above narrows that window
                        // and does not close it, which is the finding PinnedDirectory.step
                        // records at length about the identical pair of lines.
                        //
                        // Found by a mutation pass rather than by reading, and pinned by
                        // MemoryRaceTest.aFifoRacedUnderTheRootDoesNotStrandAListing --
                        // which is a real pin and not a hopeful one: with this line bare it
                        // strands 37, 38 and 33 listings of 2,000, and with it 0, 0 and 0.
                        // The paragraph two methods up says no test races THIS walk and that
                        // the mutant putting list() back on walkByName therefore survives;
                        // that is still true of walkByName, and no longer true of this line.
                        inside = PinnedDirectory.openFolder(level.dir(), name);
                    } catch (IOException cannotOpen) {
                        // A folder we may not open holds no key we could report, so it is
                        // skipped and named to the operator instead. This is also where a
                        // component that turned into a link between the fstatat above and
                        // this line arrives: NOFOLLOW_LINKS makes the open refuse it rather
                        // than descend into somebody else's directory.
                        into.unreadable(under);
                    }
                    if (inside != null) {
                        stack.push(new Level(inside, inside.iterator(), under));
                    }
                    continue;
                }
                into.entry(under, attrs);
            }
        } finally {
            // A walk that stopped early still has to give back what it took.
            while (!stack.isEmpty()) {
                Level level = stack.pop();
                if (level.dir() != top) {
                    try {
                        level.dir().close();
                    } catch (IOException | RuntimeException alreadyGone) {
                        // Nothing a caller could do with this, and the listing that failed
                        // is the failure worth reporting.
                    }
                }
            }
        }
    }

    /** One open directory on the way down, and where under the root it sits. */
    private record Level(SecureDirectoryStream<Path> dir, Iterator<Path> entries, Path under) {

        /** One of this directory's entries, spelled relative to the root as a key is. */
        Path spelling(Path name) {
            return under == null ? name : under.resolve(name);
        }
    }

    /**
     * {@link #list} 's walk where the platform has no {@code openat}.
     *
     * <p>The code that was there before #168, reachable only on a platform whose
     * {@code Files.newDirectoryStream} is not a {@link SecureDirectoryStream}, and kept for
     * the reason the other two fallbacks in this class are: deleting it would leave that
     * platform unable to list at all. It feeds the same {@link Listing}, so the two walks
     * cannot drift on what a name means.
     *
     * <p>{@code walkFileTree} rather than {@code Files.walk}. The stream raises
     * {@code UncheckedIOException} from its terminal operation for any directory it cannot
     * open — which the caller's catch could never see, and which killed <em>every</em>
     * listing over a root holding one unreadable folder, {@code list("notes/")} included,
     * since the scope filter runs after the walk. The message also carried the host path,
     * which {@code SafePaths} keeps out of anything model-facing.
     */
    private static void walkByName(Path here, Listing into) throws IOException {
        Files.walkFileTree(here, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                into.entry(here.relativize(file), attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failed) {
                into.unreadable(here.relativize(file));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * What a listing walk found, and what each name it found means.
     *
     * <p>One object rather than a visitor per walk, because there are two walks now — the
     * pinned one and the one the platforms without {@code openat} keep — and "which names
     * become keys" is the half of {@code list()} that a model sees. Two copies of it would
     * be two answers to the same question, and only one of them is exercised in CI.
     */
    private static final class Listing {

        private final String scope;
        private final List<String> keys = new ArrayList<>();
        // Counted in full, kept only as far as the warning will print. These three are
        // populated from directory entries, and a directory entry is something anything
        // with write access to the root can make by the hundred thousand — cheapest of all
        // as a symbolic link, which needs no data blocks. Retaining every name cost two
        // copies of the whole tree's names and took list() to OutOfMemoryError on a 160 MB
        // heap from a seven-megabyte payload, with logging switched off. The cap on
        // Quoted.each bounded the line and not the list behind it.
        private final Sample skipped = new Sample();
        private final Sample unreadable = new Sample();
        private final Sample links = new Sample();

        Listing(String scope) {
            this.scope = scope;
        }

        /** One entry that is not a directory, judged by attributes read without following. */
        void entry(Path under, BasicFileAttributes attrs) {
            // isRegularFile from the attributes, read without following: a symlink out of
            // the root would otherwise be listed as a key that read() then refuses, and
            // would tell the model where the link points.
            if (attrs.isSymbolicLink()) {
                // Named, not just skipped. This is the one operation an operator runs when
                // they wonder where a memory went, and after #99 an alias they created on
                // purpose answers absent everywhere. With nothing said here, a root full of
                // newly-inert aliases lists clean and the migration is invisible.
                // Scoped where it is collected. list("real/") reporting links nowhere near
                // "real/" is a warning about somebody else's problem, on every call an
                // agent makes.
                String link = ResourcePaths.display(under);
                if (link.startsWith(scope)) {
                    links.add(link);
                }
                return;
            }
            if (!attrs.isRegularFile()) {
                return;
            }
            String name = ResourcePaths.display(under);
            // Two questions, and a name has to pass both: is it a name this store wrote,
            // and is the key it holds one a later read would resolve. Reported, not
            // silently dropped: the root is a directory and anything may put a file in it,
            // but listing a name no read() could resolve would hand the model a key
            // guaranteed to miss. An operator who put it there deserves to hear why it is
            // gone.
            Optional<String> held = MemoryFilenames.keyOf(name);
            if (held.isEmpty() || !MemoryKeys.isNormalized(held.get())) {
                skipped.add(name);
            } else if (held.get().startsWith(scope)) {
                keys.add(held.get());
            }
        }

        /** One name the walk could not look at, or a folder it could not open. */
        void unreadable(Path under) {
            unreadable.add(ResourcePaths.display(under));
        }

        /** Tells the operator what was left out, which is the half a model never sees. */
        void report(Path here) {
            if (!unreadable.isEmpty()) {
                LOG.warn("Skipped {} path(s) under {} that could not be read: {}",
                        unreadable.total(), here, Quoted.each(unreadable.names()));
            }
            if (!links.isEmpty()) {
                LOG.warn("Ignoring {} symbolic link(s) under {}; a link is not a memory key,"
                        + " so neither it nor anything under it is listed, read, or"
                        + " written: {}", links.total(), here, Quoted.each(links.names()));
            }
            if (!skipped.isEmpty()) {
                // Quoted, not raw. These names come from whatever put a file in the root —
                // an unpacked archive, a shared volume, a restored backup — which is exactly
                // the population this warning exists for, and a name carrying a line
                // terminator wrote entries of its own into an operator's log (#98).
                LOG.warn("Ignoring {} file(s) under {} whose names are not memory keys: {}",
                        skipped.total(), here, Quoted.each(skipped.names()));
            }
        }

        List<String> keys() {
            keys.sort(String::compareTo);
            return keys;
        }
    }
}
