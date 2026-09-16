package dev.agentkit.core.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;

/**
 * A file read through the directory it lives in, held open, so nothing between the
 * containment check and the read re-walks a path the kernel resolves again (#167).
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>{@link SafePaths#resolveWithin} hands back a resolved, symlink-free path and says of
 * itself that it is "a check, not a lock". Every syscall after it resolves that path again
 * from the root, so <em>symlink-free when it was checked</em> is not <em>symlink-free when
 * it is read</em>. Skill loading finished with {@code Files.readString} on the checked path,
 * which is the second resolution, and the gap is winnable from attacker position D in
 * {@code docs/PENTEST.md} — a third-party bundle, whose own files an attacker with runtime
 * write access to the tree can rotate.
 *
 * <p>Measured on this machine before this class existed, by stashing it and running
 * {@code SkillRaceTest}: one thread rotating one name, 20,000 attempts per run, three runs,
 * and every attempt counted.
 *
 * <pre>
 * what was rotated                            attemptsMade   escapes
 * a resource's last component                    20000       482 /  25 /  79
 * a directory component                          20000       131 /   7 /   2
 * SKILL.md, during the load                      20000       677 / 396 / 541
 * the bundle directory itself, no race at all      200       200 / 200 / 200
 * </pre>
 *
 * <p>An escape is bytes that exist only outside the bundle arriving in what the loader
 * handed back — which for {@code read_skill_resource} means inside a {@code Spotlight} fence
 * in the model's context, and for {@code SKILL.md} means inside a <strong>procedure</strong>
 * fence, since a skill's instructions are what {@code read_skill} serves. The spread is
 * contention on a shared machine rather than a narrow window: the same three races run as a
 * standalone harness on an idle one gave 474/639/230, 105/87/71 and 308/538/514, with first
 * wins at attempts 19, 3 and 7. The last row is not a race at all —
 * {@code rm -rf bundle && ln -s /attacker bundle} after the library is built redirected
 * every read, because the bundle was a name and a name is re-resolved on every call.
 *
 * <p>With this class: 0 on all four, every run.
 *
 * <h2>Why this is a fix and not another check</h2>
 *
 * <p>Each directory component is opened with {@code openat(dirfd, name, O_RDONLY|O_NOFOLLOW)}
 * — {@link SecureDirectoryStream#newDirectoryStream} with
 * {@link LinkOption#NOFOLLOW_LINKS} — and the file itself with the same flags through
 * {@link SecureDirectoryStream#newByteChannel}. A descriptor refers to an <em>inode</em>,
 * not to a name, so renaming or replacing the name afterwards cannot move it. There is no
 * interval between a check and a use because there is no check: every open either lands in
 * the directory that was opened or fails.
 *
 * <p>The base is pinned the same way and for the same reason {@code PinnedDirectory} pins a
 * memory root (#165): {@link #identity()} is {@code fstat} of the descriptor this descent
 * opened, compared with the one recorded when the bundle was loaded. That is a question
 * about the one property of a file that cannot change — which inode it is — so its answer is
 * still true when it is used rather than merely true when it was asked.
 *
 * <h2>Its relationship to {@code PinnedDirectory}, stated rather than left to be noticed</h2>
 *
 * <p>{@code dev.agentkit.core.memory.PinnedDirectory} descends the same way for the same
 * reason, and this repository's recurring defect is one rule with several runners (#131,
 * #179, #163). The two are not merged today because {@code PinnedDirectory} cannot be shared
 * without being changed: it is package-private in {@code memory}, its {@code freshName}
 * reaches into {@code FileMemoryStore.TEMP_PREFIX}, and better than half of it — staging a
 * folder, {@code renameat}ing it into place, clearing a tree — is write-path machinery a
 * read-only consumer must not inherit. Folding {@code PinnedDirectory}'s reading half onto
 * this class is a deletion rather than a rewrite, and it is the follow-up this class exists
 * to make cheap. What differs and would still differ afterwards is the <em>caller's</em>
 * policy, not the descent: a memory store creates the folders a key implies and this refuses
 * a component that is not already there, because a bundle is somebody else's tree and
 * nothing here ever writes to it.
 *
 * <h2>What it does not reach</h2>
 *
 * <p><strong>A FIFO raced into a directory component — or standing at the base — still
 * hangs the thread</strong>, which is #218 and is not this class's to close. {@link SecureDirectoryStream#newDirectoryStream}
 * carries no {@code O_DIRECTORY} — the JDK opens the descriptor and hands it to
 * {@code fdopendir} — so {@code open(2)} on a FIFO blocks until somebody opens the other
 * end. {@link #step} asks the component's type with {@code fstatat} first, which turns the
 * <em>sitting</em> FIFO from a permanent hang into a refusal and narrows the raced one to
 * the gap between two syscalls. {@code PinnedDirectory.step} carries the measurement (85 in
 * 6,000 down to 2 in 6,000) and the alternatives that were tried; the same wall is here for
 * the same reason. The base's own open in {@link #of} has no such guard at all, because
 * there is nothing above it to ask the question through — a caller that hands this a base
 * an attacker can replace with a FIFO has already lost, which is the same position
 * {@code PinnedDirectory.rootAsOpened} is in.
 *
 * <p><strong>Where the platform has no {@code openat}</strong> — {@code newDirectoryStream}
 * answering something that is not a {@link SecureDirectoryStream} — {@link #isPinned()} answers
 * false and the read falls back to the path, with {@code NOFOLLOW_LINKS} at the last component
 * and the window this class closes still open above it. Named rather than silent, because a
 * fallback that quietly reinstates the defect is how this kind of fix rots.
 *
 * <p><strong>This used to say that meant Windows, and that it "cannot be exercised on a Unix
 * runner". Both were wrong (#396).</strong> Only Linux ships
 * {@code sun.nio.fs.UnixSecureDirectoryStream}; macOS returns a plain
 * {@code sun.nio.fs.UnixDirectoryStream}, so on every Mac this reads through paths and
 * {@code SkillRaceTest} measures the consequence: 287 reads served from outside the bundle.
 * The claim was made from a Linux runner and generalised to "Unix".
 *
 * <p><strong>A hard link inside the bundle to a file outside it</strong> is not a traversal,
 * so there is nothing for {@code O_NOFOLLOW} to refuse and nothing here sees it.
 * {@link SafePaths} carries that disclosure already and it is unchanged.
 *
 * <p><strong>The catalog is still built by walking paths.</strong> {@code SkillLoader}
 * lists a bundle's resources with {@code Files.walk}, and a component rotated during that
 * walk can put a name from outside the bundle into the catalog. What it cannot do is get
 * that name <em>served</em>: the read is this class, and a name that is not reachable
 * through the pinned descent answers "no such resource file". So the residue is a catalog
 * entry that always fails to read, which is a disclosure of a filename rather than of a
 * file's bytes.
 */
public final class PinnedRead implements Closeable {

    /**
     * How many directory components the descent will open before answering absent.
     *
     * <p>Every descriptor the descent takes is held until the read is done, and the length
     * of the path is chosen by two parties that are not this framework: the model writes the
     * {@code path} argument and the bundle author ships the tree it names. "Nothing we build
     * is that deep" is a statement about what we build, not about what we accept.
     *
     * <p><strong>What it costs, measured rather than assumed.</strong> On this JDK a
     * {@code UnixSecureDirectoryStream} holds two descriptors per open directory — the one
     * {@code openat} returned and a {@code dup} for iterating — so the descent's high-water
     * mark is twice the component count: a 60-component path held 122, a 200-component path
     * 402, a 1,200-component path 2,402. With {@code ulimit -n} at 256 the 1,200-component
     * read reached {@code EMFILE} and answered absent.
     *
     * <p><strong>And what it does not cost, which is the honest half.</strong> A second
     * thread opening a file in a loop through that same window saw no failure at all —
     * 130,195 opens, none refused — because the descent unwinds the moment it hits
     * {@code EMFILE}. So this is a bound taken on a resource cost the framework never chose,
     * not a repair for an outage that was observed. It is stated that way because the
     * alternative reading — "this closes a denial of service" — is one nobody here measured.
     *
     * <p>128 rather than tighter because refusing costs a real bundle its resource, and
     * rather than looser because the point is to stay far below any plausible descriptor
     * limit: 128 components is 258 descriptors, which fits inside the smallest default
     * {@code ulimit -n} anyone runs. The deepest tree in this repository's own test bundles
     * is three.
     *
     * <p>{@code SkillLoader} asks the same question of the catalog through
     * {@link #canDescend}, so a resource this refuses to read is a resource the model was
     * never offered. A listing that advertises what the reader will not serve is the shape
     * that made {@code servedAs} necessary in the first place.
     */
    private static final int MAX_DEPTH = 128;

    /**
     * Whether {@code relative} is short enough for {@link #of} to descend it.
     *
     * <p>Public so that a caller building a listing can drop the names a read would refuse
     * instead of advertising them. One rule with two runners is this repository's recurring
     * defect (#131, #179, #163); this is the rule, and both runners ask it here.
     */
    public static boolean canDescend(Path relative) {
        Objects.requireNonNull(relative, "relative");
        return relative.getNameCount() > 0 && relative.getNameCount() <= MAX_DEPTH
                && relative.getFileName() != null;
    }

    /** Every descriptor the descent took, innermost first. */
    private final Deque<DirectoryStream<Path>> open;

    /** The pinned parent, or {@code null} where this platform has no {@code openat}. */
    private final SecureDirectoryStream<Path> parent;

    /** The last component — the name the file is spelled as inside {@link #parent}. */
    private final Path name;

    /** The path to fall back to where there is no {@code openat}. */
    private final Path lexical;

    /** Which inode the base was, as this descent's own open reported it. */
    private final Object identity;

    private PinnedRead(Deque<DirectoryStream<Path>> open, SecureDirectoryStream<Path> parent,
            Path name, Path lexical, Object identity) {
        this.open = open;
        this.parent = parent;
        this.name = name;
        this.lexical = lexical;
        this.identity = identity;
    }

    /**
     * Opens the directory {@code relative} lives in, refusing a link at every component.
     *
     * <p>{@code null} rather than an exception when nothing reachable stands there, because
     * both callers are asking a total question: a resource whose folder is absent, is a
     * document, or is reached through a symbolic link is a resource the bundle does not
     * have, and "no such resource file" is the answer either way. The one thing that is
     * <em>not</em> folded into absent is the base having been rotated — see
     * {@link BaseRotated}, and {@code PinnedDirectory.RootRotated} for the same distinction
     * made for the same reason.
     *
     * @param base     the bundle directory, already resolved
     * @param baseIs   the inode {@code base} named when the bundle was loaded, from an
     *     earlier {@link #identity()}; {@code null} to record rather than compare
     * @param relative the file's name relative to {@code base}
     * @throws BaseRotated if {@code base} no longer names the inode {@code baseIs}
     */
    public static PinnedRead of(Path base, Object baseIs, Path relative) throws IOException {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(relative, "relative");
        if (!canDescend(relative)) {
            // Nothing to descend, or deeper than this will hold descriptors for. Refused
            // before a single one is taken: see MAX_DEPTH.
            return null;
        }
        Deque<DirectoryStream<Path>> open = new ArrayDeque<>();
        try {
            DirectoryStream<Path> here = Files.newDirectoryStream(base);
            open.push(here);
            Object nowIs = identityOf(here, base);
            if (baseIs != null && !Objects.equals(baseIs, nowIs)) {
                throw new BaseRotated(base.toString(),
                        "the bundle directory now names a different directory than the one"
                                + " this skill was loaded from; refusing rather than"
                                + " following it");
            }
            if (!(here instanceof SecureDirectoryStream)) {
                // No openat on this platform. Answered rather than thrown so the caller
                // keeps the behaviour it had, and says so.
                return new PinnedRead(open, null, relative.getFileName(),
                        base.resolve(relative), nowIs);
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> at = (SecureDirectoryStream<Path>) here;
            // The last component is the file itself and is never opened as a directory:
            // reading it is readString's, relative to the descriptor this loop ends on.
            for (int component = 0; component < relative.getNameCount() - 1; component++) {
                at = step(at, relative.getName(component));
                open.push(at);
            }
            return new PinnedRead(open, at, relative.getFileName(),
                    base.resolve(relative), nowIs);
        } catch (BaseRotated rotated) {
            // Named before FileSystemException on purpose, and not redundant with the
            // IOException clause below: it is the guard that keeps a rotated base from
            // being folded into "nothing reachable here" if somebody later gives
            // BaseRotated a FileSystemException parent. Absent is what a bundle says about
            // a resource; this is about the bundle.
            closeAll(open);
            throw rotated;
        } catch (FileSystemException notReachable) {
            closeAll(open);
            return null;
        } catch (IOException | RuntimeException failed) {
            closeAll(open);
            throw failed;
        }
    }

    /**
     * One component down, refusing a symbolic link as part of the open.
     *
     * <p>The type is asked first with {@code fstatat}, and it has to be:
     * {@link SecureDirectoryStream#newDirectoryStream} carries no {@code O_DIRECTORY}, so
     * an {@code openat} of a FIFO blocks inside the syscall where no error, no result and
     * no timeout reaches the caller. Asking the type is itself a check before an open and
     * therefore narrows rather than closes — that residue is #218, and
     * {@code PinnedDirectory.step} carries the measurement of how far it narrows.
     *
     * <p>A type question is not the kind of check #143 is about. An inode cannot change
     * type, so "this is a directory" is still true when the open lands, whatever the name
     * has come to point at — and if the name has come to point at something else, the
     * {@code O_NOFOLLOW} open is what refuses it.
     */
    private static SecureDirectoryStream<Path> step(SecureDirectoryStream<Path> at,
            Path component) throws IOException {
        BasicFileAttributes standing = factsAbout(at, component);
        if (standing == null) {
            throw new NoSuchFileException(component.toString());
        }
        if (!standing.isDirectory()) {
            // A symbolic link, a document, a FIFO, a socket, a device node. All of them
            // used to arrive as the open refusing -- except the FIFO, which arrived as the
            // thread never coming back.
            throw new NotDirectoryException(component.toString());
        }
        return at.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Which inode {@code base} is, recorded so a later descent can be compared with it.
     *
     * <p>Asked by opening the directory and {@code fstat}ing the descriptor, which is the
     * long way round for a question {@code Files.readAttributes} answers in one call — and
     * it is the point. This is the value every later comparison is made against, so it has
     * to come from the same code path those comparisons use, or two spellings of "which
     * inode is this" could disagree about the answer's form and refuse a bundle that never
     * moved.
     *
     * <p>{@code null} where the platform will not say. {@code fileKey()} is documented to
     * answer {@code null} on file systems with nothing unique to offer, and there is no
     * honest comparison to make then; skipped rather than silently passed, for the same
     * reason {@link #isPinned()} answers false out loud.
     */
    public static Object identityOf(Path base) throws IOException {
        Objects.requireNonNull(base, "base");
        try (DirectoryStream<Path> here = Files.newDirectoryStream(base)) {
            return identityOf(here, base);
        }
    }

    private static Object identityOf(DirectoryStream<Path> here, Path base) throws IOException {
        if (here instanceof SecureDirectoryStream<?> secure) {
            return secure.getFileAttributeView(BasicFileAttributeView.class)
                    .readAttributes().fileKey();
        }
        return Files.readAttributes(base, BasicFileAttributes.class).fileKey();
    }

    /**
     * The base's name no longer leads to the base.
     *
     * <p>Deliberately <strong>not</strong> a {@link FileSystemException}: {@link #of} folds
     * those into "nothing reachable here", and a rotated base is the one thing that must not
     * answer absent. Absent is what a bundle says about a resource; this is about the bundle.
     */
    public static final class BaseRotated extends IOException {

        private static final long serialVersionUID = 1L;

        BaseRotated(String file, String reason) {
            super(file + ": " + reason);
        }
    }

    /**
     * Which inode the base was when this descent opened it.
     *
     * <p>Pass it back to {@link #of} on a later read to pin the bundle by inode rather than
     * by name. Taken from the same {@code openat} the read is made through, so a caller that
     * records it at load time has no window at all between establishing the identity and
     * using it — which is why {@link #identityOf(Path)} is the second-best way to get one
     * and this is the first.
     */
    public Object identity() {
        return identity;
    }

    /** Whether this platform gave us {@code openat}; false means the read is path-based. */
    public boolean isPinned() {
        return parent != null;
    }

    /**
     * What stands at the file, or {@code null} if nothing does.
     *
     * <p>{@code fstatat(dirfd, name, AT_SYMLINK_NOFOLLOW)}. A symbolic link answers as
     * itself rather than as its target, which is what the callers want: a link at the last
     * component after {@link SafePaths#resolveWithin} has already collapsed the aliases is a
     * link that arrived since the check.
     */
    public BasicFileAttributes standing() throws IOException {
        if (!isPinned()) {
            try {
                return Files.readAttributes(lexical, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException nothingThere) {
                return null;
            }
        }
        return factsAbout(parent, name);
    }

    private static BasicFileAttributes factsAbout(SecureDirectoryStream<Path> at, Path name)
            throws IOException {
        try {
            return at.getFileAttributeView(name, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
        } catch (NoSuchFileException nothingThere) {
            return null;
        }
    }

    /**
     * The file's content, read relative to the directory this descent opened.
     *
     * <p>{@code openat(dirfd, name, O_RDONLY|O_NOFOLLOW)}: no component of the path is named
     * again, and the last one is refused if it is a link.
     *
     * <p><strong>Decoding reports rather than replaces</strong>, which
     * {@link Files#readString} also does and which {@code new String(bytes, charset)} does
     * not — the constructor substitutes U+FFFD for malformed input, so a corrupt resource
     * would reach the model as mojibake with nothing anywhere saying so. Both callers here
     * used {@code Files.readString}, so reporting is the behaviour they had and the
     * behaviour kept. Measured on the bytes {@code C3 28 78}: this throws
     * {@link CharacterCodingException}, the constructor answers
     * {@code U+FFFD U+0028 U+0078}.
     *
     * @param charset how the bytes are decoded; the caller's, because a bundle's charset is
     *     the caller's policy and not this class's
     */
    public String readString(Charset charset) throws IOException {
        Objects.requireNonNull(charset, "charset");
        return decodeStrictly(readAllBytes(), charset);
    }

    private byte[] readAllBytes() throws IOException {
        if (!isPinned()) {
            try (InputStream in = Files.newInputStream(lexical, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                return in.readAllBytes();
            }
        }
        try (InputStream in = Channels.newInputStream(parent.newByteChannel(name,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            return in.readAllBytes();
        }
    }

    private static String decodeStrictly(byte[] bytes, Charset charset) throws IOException {
        CharsetDecoder strict = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return strict.decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * Gives back every descriptor the descent took.
     *
     * <p>Does not throw. Failing to release a descriptor cannot make a read that already
     * completed untrue, and turning a finished read into an exception would be the worse
     * answer.
     */
    @Override
    public void close() {
        closeAll(open);
    }

    private static void closeAll(Deque<DirectoryStream<Path>> open) {
        while (!open.isEmpty()) {
            try {
                open.pop().close();
            } catch (IOException | RuntimeException alreadyGone) {
                // See close(): there is nothing a caller could do with this.
            }
        }
    }
}
