package dev.agentkit.core.memory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The directory a key's name lives in, held open, so the operation that follows names one
 * component in one directory rather than re-walking a path (#168).
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>{@code SafePaths} hands back a resolved, symlink-free path, and every syscall after
 * that resolves it again from the beginning. Symlink-free <em>when it was checked</em> is
 * not symlink-free <em>when it is used</em>, and the gap is winnable. Measured on this
 * machine against a thread flipping one intermediate component between a real directory and
 * a symbolic link pointing outside the memory root, 20,000 attempts per run and every
 * attempt counted:
 *
 * <pre>
 * operation  attemptsMade   escaped         first win at        with this class
 * write         20000       12 / 15 / 12     235 /   24 /    4    0 / 0 / 0
 * append        20000       14 / 17 / 21     202 /   19 /  187    0 / 0 / 0
 * delete        20000        5 / 31 /  9      24 /   53 /    1    0 / 0 / 0
 * read          20000       18 / 34 / 28      49 / 2716 / 2016    0 / 0 / 0
 * list          20000        0 /  1 /  1       - / 12728 / 10557  0 / 0 / 0
 * </pre>
 *
 * <p>{@code write} left model-chosen bytes in a file outside the root, {@code append} added
 * to one, {@code delete} unlinked one, {@code read} handed its content back to the model,
 * and {@code list} reported names of entries in a directory outside the root as memory keys.
 * The canary is restored after every win, so the counts are independent wins rather than
 * runs that escaped at least once, and the denominator is attempts actually made rather than
 * a loop bound. {@link java.nio.file.LinkOption#NOFOLLOW_LINKS} does not help: it guards the
 * <em>final</em> component, and the component being swapped is not the final one.
 *
 * <h2>Why this is a fix and not another check</h2>
 *
 * <p>Each component is opened with {@code openat(dirfd, name, O_NOFOLLOW|O_DIRECTORY)} —
 * {@link SecureDirectoryStream#newDirectoryStream}, with
 * {@link java.nio.file.LinkOption#NOFOLLOW_LINKS}. The kernel refuses to traverse a symbolic
 * link in the same syscall that opens the directory, and the descriptor that comes back
 * refers to an <em>inode</em>, not to a name. Renaming or replacing the name afterwards
 * cannot move the descriptor: everything done relative to it lands in the directory that was
 * opened, which is by construction the one reached from the root one refused-link at a time.
 * There is no interval between a check and a use because there is no check.
 *
 * <p>#168 says this "is not expressible in {@code java.nio.file}; needs JNI or a custom
 * {@code FileSystemProvider}". That is wrong, and it is worth saying plainly because the
 * belief is what deferred the fix. {@link SecureDirectoryStream} has been in
 * {@code java.nio.file} since Java 7 and is exactly the {@code openat} family: on this
 * platform {@code Files.newDirectoryStream} returns {@code sun.nio.fs.UnixSecureDirectoryStream},
 * whose {@code newByteChannel}, {@code newDirectoryStream}, {@code deleteFile},
 * {@code deleteDirectory}, {@code move} and {@code getFileAttributeView} are
 * {@code openat}, {@code unlinkat}, {@code renameat} and {@code fstatat}.
 *
 * <h2>What it does not reach</h2>
 *
 * <p><strong>{@code mkdir} has no at-relative form here.</strong> {@link SecureDirectoryStream}
 * offers no {@code createDirectory}, so a folder cannot be made straight into a pinned
 * directory. It is made under the root — one component, under a directory this store
 * resolved at construction and which nothing writing <em>under</em> the root can rename —
 * and then {@code renameat}d into place through the pinned descriptor. The alternative
 * measured and rejected was {@code Files.createDirectories} on the lexical path followed by
 * this descent: it cannot put content outside the root, because the descent is still the
 * authority on where the write lands, but a won race leaves an <strong>empty directory
 * created outside the root</strong>, and "we only create stray directories out there" is not
 * a containment claim worth making when one {@code renameat} removes the case entirely.
 *
 * <p><strong>Where the platform has no {@code openat}</strong> — {@code Files.newDirectoryStream}
 * returns something that is not a {@link SecureDirectoryStream} — {@link #isPinned()} answers
 * false and the caller keeps its previous path-based behaviour, with the window this class
 * closes still open. Named rather than silent, because a fallback that quietly reinstates the
 * defect is how this kind of fix rots.
 *
 * <p><strong>This paragraph used to say that meant Windows, and that it "cannot be exercised on
 * a Unix runner". Both were wrong (#396).</strong> Only Linux ships
 * {@code sun.nio.fs.UnixSecureDirectoryStream}; macOS returns a plain
 * {@code sun.nio.fs.UnixDirectoryStream}, so the pinned descent is <em>inactive on every
 * Mac</em> and the fallback is what runs. The pentest suite has been measuring the consequence
 * all along — 6,713 escaping writes in 20,000 on macOS against 0 on Linux — and nobody read it,
 * because those tests were also red for an unrelated reason and had been written off as "they
 * fail on Macs".
 *
 * <p>The claim was made from a Linux runner and generalised to "Unix". That is the mistake worth
 * remembering: a platform disclosure has to name the platforms it was measured on.
 *
 * <p><strong>A FIFO raced into a component used to hang the thread and no longer does
 * (#218).</strong> The {@code openat} behind {@link SecureDirectoryStream#newDirectoryStream}
 * carries no {@code O_DIRECTORY}, so it blocks on a FIFO rather than refusing one — 27 hangs
 * in 6,000 attempts against a thread flipping a component, and one hang per call with a FIFO
 * standing at the root, no race needed. Every such open now runs under
 * {@link #withoutHangingOnAFifo}, which frees it by becoming the writer the syscall is
 * waiting for. What is left is one name away: an attacker who wins the race <em>and</em>
 * takes the FIFO's name away within two milliseconds, which that method measures.
 *
 * <p><strong>The root is pinned by inode rather than by name (#165).</strong> The descent
 * used to begin at {@code Files.newDirectoryStream(root)} — a <em>path</em>, resolved once at
 * construction and re-resolved by the kernel on every call — so replacing that name redirected
 * the whole store and no component below it mattered. {@link #rootAsOpened} opens the name and
 * then asks the <em>descriptor</em> which inode it got, comparing it with the one the store
 * recorded when it was built; a mismatch is {@link RootRotated} and nothing else happens.
 * {@link #identityOf} says why that is not another racing check.
 */
final class PinnedDirectory implements Closeable {

    /**
     * How many times a missing folder is made before the descent gives up.
     *
     * <p>Bounded because the loop is racing somebody: a thread removing the folder as fast
     * as it is made would otherwise spin here forever, which is a hang, and this file's
     * neighbours record what a hang costs. Eight is far past what contention between honest
     * writers needs — they collide once, at most, on the same missing folder.
     */
    private static final int ATTEMPTS = 8;

    /**
     * How long a directory open is left alone before something goes to release it.
     *
     * <p>Two milliseconds. An {@code openat} of a directory that is a directory takes
     * microseconds, so the release thread ordinarily never runs at all: it is waiting on a
     * latch the open's own return counts down. When the open <em>is</em> wedged, this is
     * the delay before the first attempt to free it, and the interval between attempts
     * after that.
     */
    private static final int RENDEZVOUS_MILLIS = 2;

    /**
     * How many times a wedged open is offered a way out before the attempt is abandoned.
     *
     * <p>500 at two milliseconds is one second of trying. Bounded because the thread doing
     * the trying is not free either, and because an attacker who has taken the FIFO's name
     * away cannot be reached by any number of further attempts — see
     * {@link #withoutHangingOnAFifo} for what that residue is and how often it is reached.
     */
    private static final int RENDEZVOUS_ATTEMPTS = 500;

    /**
     * {@code O_RDWR|O_NOFOLLOW}, which is the one open in {@code java.nio.file} that a FIFO
     * cannot block.
     */
    private static final Set<OpenOption> READ_WRITE = Set.<OpenOption>of(
            StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

    /** An open that may never come back. */
    private interface Opening<T> {
        T open() throws IOException;
    }

    /** The same name, opened the way that cannot block, to release one that is blocked. */
    private interface Rendezvous {
        SeekableByteChannel open() throws IOException;
    }

    /**
     * Runs a directory open, and frees it if a FIFO has wedged it (#218).
     *
     * <h4>The defect, which is a hang and not an escape</h4>
     *
     * <p>{@link SecureDirectoryStream#newDirectoryStream} is {@code openat(dfd, name,
     * O_RDONLY|O_NOFOLLOW)} and carries <strong>no {@code O_DIRECTORY}</strong>: the JDK
     * opens a descriptor and hands it to {@code fdopendir}. {@code open(2)} on a FIFO
     * without {@code O_NONBLOCK} waits for somebody to open the other end, and waits
     * forever. Nothing reaches the thread in there — no error, no result, no timeout — so
     * every occurrence costs a thread permanently and a patient attacker exhausts a pool.
     * Measured against a thread flipping an intermediate component between a directory and
     * a hard link to a pre-made FIFO, each attempt bounded on a daemon thread and every
     * attempt counted:
     *
     * <pre>
     * attemptsMade=2000  flips=2085816  declined= 928  acted=1052  BLOCKED=20
     * attemptsMade=2000  flips= 354334  declined= 798  acted=1199  BLOCKED= 3
     * attemptsMade=2000  flips= 466287  declined= 780  acted=1216  BLOCKED= 4
     * </pre>
     *
     * <h4>Why this frees it, when nothing in the API can cancel an open</h4>
     *
     * <p>A blocked FIFO open is not waiting on a timer or on a descriptor this process can
     * reach — the descriptor does not exist yet, and the one the JDK is about to produce is
     * a private field of {@code sun.nio.fs.UnixSecureDirectoryStream} in a package
     * {@code java.base} does not open. It is waiting on <em>a writer</em>. So a second
     * thread opens the same name through the same pinned descriptor with
     * {@code O_RDWR|O_NOFOLLOW}, which on this platform never blocks on a FIFO, and the
     * rendezvous happens: the wedged open returns, {@code fdopendir} refuses the descriptor
     * with {@code ENOTDIR}, and the caller gets the {@code NotDirectoryException} a FIFO
     * sitting still would have earned it. Measured directly, before any of this was wired
     * into the descent: a {@code newDirectoryStream} still blocked after 700 ms came back
     * in under a millisecond of the read-write open, as {@code NotDirectoryException}.
     *
     * <p>This is the watchdog #218 asked for and could not find — "one that closes the
     * descriptor rather than one that abandons the thread" — reached by supplying what the
     * syscall is waiting for instead of by reaching the descriptor. Nothing is abandoned:
     * the open stays on the calling thread, so there is no descriptor to hand across and
     * nothing to leak per attempt, which is what sank the version #217 measured.
     *
     * <h4>What it costs when nothing is wrong</h4>
     *
     * <p>One virtual thread, created and immediately parked on a latch that the open's own
     * return counts down. No syscall: the release attempt is behind a two-millisecond wait
     * that an ordinary open beats by three orders of magnitude. Virtual rather than
     * platform because a descent makes one of these per intermediate component, and a key
     * with no {@code /} in it makes none at all.
     *
     * <h4>The residue, which is smaller than it looks and is not zero</h4>
     *
     * <p>The rendezvous goes through the <em>name</em>, so it frees the caller only while
     * the FIFO can still be reached by it. An attacker who wins the {@code fstatat}-to-open
     * race and then takes the FIFO's name away for good — before the first attempt two
     * milliseconds later — leaves the thread where it was. That is two conditions rather
     * than one, and the second has to be met blind, since nothing tells the attacker when a
     * thread went into the syscall. Measured: the deliberate version, planting a FIFO,
     * waiting 300 ms and then restoring the directory, never reaches the window at all
     * ({@code attemptsMade=20 declined=20 acted=0 BLOCKED=0}) because a FIFO standing still
     * is refused by the {@code fstatat} in {@link #step} before anything opens it.
     *
     * <p><strong>Alternatives measured and rejected.</strong> {@code O_NONBLOCK} is the
     * textbook answer and is not reachable: {@code sun.nio.fs.UnixChannelFactory$Flags}
     * carries exactly {@code read, write, append, truncateExisting, noFollowLinks, create,
     * createNew, deleteOnClose, sync, dsync, direct}, {@code com.sun.nio.file
     * .ExtendedOpenOption} adds only {@code NOSHARE_*} and {@code DIRECT}, and an unknown
     * {@code OpenOption} is rejected rather than ignored. Reaching the flag anyway means
     * either reflection into {@code sun.nio.fs}, which the module refuses, or a downcall
     * through {@code java.lang.foreign}, which is a preview API on this release and would
     * put {@code --enable-preview} on every process that ever loads this class — and would
     * still need the descriptor out of the same closed package to be at-relative. Doing the
     * open on a borrowed thread and abandoning it on a timeout bounds the caller and leaks a
     * thread and a descriptor for every attempt, which is the trade #217 measured and
     * refused; it is also strictly worse than this, which leaks neither.
     */
    private static <T> T withoutHangingOnAFifo(Rendezvous writer, Opening<T> open)
            throws IOException {
        CountDownLatch returned = new CountDownLatch(1);
        Thread.ofVirtual().name("memory-fifo-rendezvous").start(() -> {
            try {
                for (int attempt = 0; attempt < RENDEZVOUS_ATTEMPTS; attempt++) {
                    if (returned.await(RENDEZVOUS_MILLIS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                    try (SeekableByteChannel released = writer.open()) {
                        // Opened and dropped again. Nothing is read, nothing is written and
                        // nothing is created -- there is no O_CREAT here, so a name that
                        // holds nothing answers ENOENT. If this was the FIFO the caller is
                        // wedged on, the caller has just come back.
                    } catch (IOException | RuntimeException nothingToRelease) {
                        // EISDIR is the ordinary answer, and it is the answer that says
                        // there was never anything to release: the name is the directory
                        // the caller opened without trouble, and the latch is about to say
                        // so.
                    }
                }
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            return open.open();
        } finally {
            returned.countDown();
        }
    }

    /**
     * One folder inside another, opened the way that a FIFO cannot wedge.
     *
     * <p>The one spelling of this pairing in the package. {@link #withoutHangingOnAFifo}
     * takes the release open and the open it guards as two lambdas, and a caller that gets
     * the pairing wrong — a release that is not the same name, or through a different
     * descriptor — still compiles and still hangs. Five call sites: {@link #step},
     * {@link #clear}, {@link #survey} twice, and {@code FileMemoryStore}'s listing walk.
     * {@link #rootAsOpened} is the sixth directory open in this package and is not one of
     * them, because the root is opened by <em>path</em> and there is no descriptor to be
     * at-relative to; it calls {@link #withoutHangingOnAFifo} directly. The survey is the
     * whole of #236's repair, and the listing walk was found to be bare while making this
     * method — #235 wrapped every directory open in this class, and that one is not in this
     * class.
     *
     * <p>{@link LinkOption#NOFOLLOW_LINKS} on the directory open and nothing else: the
     * caller has already asked the type, and this is not the place that decides policy.
     */
    static SecureDirectoryStream<Path> openFolder(SecureDirectoryStream<Path> at, Path name)
            throws IOException {
        return withoutHangingOnAFifo(
                () -> at.newByteChannel(name, READ_WRITE),
                () -> at.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * The streams to close, innermost first.
     *
     * <p>Every descriptor opened on the way down is held, because closing an intermediate
     * one early would not invalidate the deeper ones but would leak the guarantee the next
     * descent wants — and because a descent that fails half way still has to give back what
     * it took.
     */
    private final Deque<DirectoryStream<Path>> open;

    /** The pinned parent, or {@code null} where this platform has no {@code openat}. */
    private final SecureDirectoryStream<Path> parent;

    /** The final component — the name the key is actually spelled as in {@link #parent}. */
    private final Path name;

    private PinnedDirectory(Deque<DirectoryStream<Path>> open,
            SecureDirectoryStream<Path> parent, Path name) {
        this.open = open;
        this.parent = parent;
        this.name = name;
    }

    /**
     * The directory a key about to be written lives in, making the folders that are missing.
     *
     * <p>Throws rather than answering absent, because the two writing operations are the two
     * that cannot treat absence as an answer. A component that is a symbolic link, or that
     * is not a directory, arrives as a {@link FileSystemException} and reaches the caller as
     * the {@code UncheckedIOException} {@code MemoryStore}'s contract promises — the
     * refusals a model can act on are made before this, by {@code MemoryNamespace} and
     * {@code requireTheKeyIsFree}, where the key's own vocabulary is still in hand.
     *
     * @param root     the memory root, already resolved
     * @param rootIs   the inode that root named when the store was built ({@link #identityOf})
     * @param relative the key's on-disk name relative to {@code root}
     */
    static PinnedDirectory toWrite(Path root, Object rootIs, Path relative) throws IOException {
        return descend(root, rootIs, relative, true, false);
    }

    /**
     * The directory a key lives in, or {@code null} when no such directory is reachable.
     *
     * <p>{@code null} rather than an exception, because the four asking operations are total:
     * a key whose folder is absent, is a document, or is reached through a symbolic link
     * holds no memory, and "no memory here" is the answer {@code read}, {@code exists},
     * {@code delete} and {@code list} all give for it. That is the same set of causes
     * {@code Files.isRegularFile} used to fold into {@code false} at these call sites, so
     * nothing about which keys answer absent changes — only where the answer comes from.
     *
     * @param root     the memory root, already resolved
     * @param rootIs   the inode that root named when the store was built ({@link #identityOf})
     * @param relative the key's on-disk name relative to {@code root}
     */
    static PinnedDirectory toFind(Path root, Object rootIs, Path relative) throws IOException {
        return descend(root, rootIs, relative, false, true);
    }

    /**
     * The directory a key lives in, making nothing and folding nothing away.
     *
     * <p>The third descent, and it exists because the two above answer the two questions a
     * caller can act on while {@link FileMemoryStore#whatStandsAt} has a third: <em>why</em>
     * the key could not be reached. {@link #toWrite} makes the missing folders, which a
     * question must not; {@link #toFind} folds every {@link FileSystemException} into
     * {@code null}, which is right for the four total operations — a key whose folder is
     * absent holds no memory, and so does one under a document — and wrong here, because
     * "the folder is not there" and "a component of the path is a FIFO" are the two answers
     * #188 exists to tell apart. A missing component arrives as {@link NoSuchFileException}
     * and one that is not a folder as {@link ComponentIsNotAFolder}, which names it.
     *
     * @param root     the memory root, already resolved
     * @param rootIs   the inode that root named when the store was built ({@link #identityOf})
     * @param relative the key's on-disk name relative to {@code root}
     */
    static PinnedDirectory toExamine(Path root, Object rootIs, Path relative)
            throws IOException {
        return descend(root, rootIs, relative, false, false);
    }

    private static PinnedDirectory descend(Path root, Object rootIs, Path relative,
            boolean creating, boolean absentIsAnAnswer) throws IOException {
        Deque<DirectoryStream<Path>> open = new ArrayDeque<>();
        try {
            DirectoryStream<Path> here = rootAsOpened(root, rootIs);
            open.push(here);
            if (!(here instanceof SecureDirectoryStream)) {
                // No openat on this platform. Answered rather than thrown so the caller can
                // keep doing what it did before, and say so.
                return new PinnedDirectory(open, null, relative.getFileName());
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> rootStream = (SecureDirectoryStream<Path>) here;
            SecureDirectoryStream<Path> at = rootStream;
            // The last component is the key's own name and is never opened here: what is
            // done with it -- created, read, unlinked, renamed over -- is the caller's, and
            // all of it is at-relative to the descriptor this loop ends on.
            for (int component = 0; component < relative.getNameCount() - 1; component++) {
                try {
                    at = step(root, rootStream, at, relative.getName(component), creating);
                } catch (NotDirectoryException notAFolder) {
                    // Which component, counted by the loop that refused it rather than
                    // recovered from the message (#188). The descent is holding the
                    // descriptor of this component's parent when it asks, so the index is
                    // not a second walk and has no window to lose: what it names is the
                    // component the fstatat just refused.
                    throw new ComponentIsNotAFolder(component,
                            relative.getName(component).toString(), notAFolder);
                }
                open.push(at);
            }
            return new PinnedDirectory(open, at, relative.getFileName());
        } catch (FileSystemException notReachable) {
            closeAll(open);
            if (!absentIsAnAnswer) {
                throw notReachable;
            }
            return null;
        } catch (IOException | RuntimeException failed) {
            closeAll(open);
            throw failed;
        }
    }

    /**
     * A component of the key's path is not a folder, and which one.
     *
     * <p>A {@link NotDirectoryException} still, so nothing that catches one stops catching
     * it: {@link #toWrite}'s caller turns it into the {@code UncheckedIOException}
     * {@code MemoryStore}'s contract promises, and {@link #toFind}'s folds it into "no
     * memory here". What it adds is the index, which is the whole of #188's "complete" fix
     * and costs nothing — the alternative that issue weighed was a second walk stat-ing
     * every ancestor, with its own check-to-open race, on the hot path of every write.
     */
    static final class ComponentIsNotAFolder extends NotDirectoryException {

        private static final long serialVersionUID = 1L;

        private final int component;

        ComponentIsNotAFolder(int component, String name, IOException cause) {
            super(name);
            this.component = component;
            initCause(cause);
        }

        /** How many components of the key's path stand above the one that is not a folder. */
        int component() {
            return component;
        }
    }

    /**
     * The memory root, opened, and refused if the name no longer leads to the store's inode.
     *
     * <p><strong>The defect this exists for, measured without any race at all.</strong>
     * Every descent used to start at {@code Files.newDirectoryStream(root)}, and a path is
     * re-resolved by the kernel on every call. Replace the name after construction —
     * {@code rm -rf mem && ln -s /attacker mem}, one command, no timing — and the whole
     * store follows it, however carefully the components below are opened. Measured on this
     * machine, each attempt building a fresh root, rotating it, then running one operation,
     * and every attempt counted:
     *
     * <pre>
     * operation  attemptsMade  reached the attacker's directory  after this change
     * write          200                    200                        0
     * append         200                    200                        0
     * read           200                    200                        0
     * delete         200                    200                        0
     * list           200                    200                        0
     * </pre>
     *
     * <p>{@code write} and {@code append} left model-chosen bytes in the attacker's file,
     * {@code read} handed its content back, {@code delete} unlinked it, and {@code list}
     * reported its entries as memory keys. Not one in twenty thousand: <em>every</em>
     * attempt, because there is nothing here for an attacker to lose.
     *
     * <p><strong>An inode comparison is not another racing check.</strong> #143's whole
     * lesson is that a check only narrows a window when the property it asks about can
     * change between the asking and the use. This asks {@code fstat} of a descriptor that
     * is already open, about the one property of a file that cannot change at all: which
     * inode it is. {@code rename(2)} moves names, never inodes; the descriptor returned
     * here refers to what was opened and will still refer to it after any number of
     * rotations. So the answer is true when it is used, not merely true when it was asked —
     * the same reason {@code step} may safely ask a component's <em>type</em>.
     *
     * <p><strong>The benign rotation this does not kill.</strong> #139's javadoc invites
     * {@code mv mem mem2 && ln -s mem2 mem} and calls it invisible. It still is: the
     * directory keeps its inode across a rename, so the name now leads through a symbolic
     * link to the same inode, the comparison passes, and the store never notices. What is
     * refused is <em>repointing</em> — the name coming to lead somewhere that was never
     * this store's root — which is the case nothing inside the store can tell apart from
     * the attack, because it is the attack. An operator who means to swap a different
     * directory in builds a store on it; that is one line, and it is the only reading of
     * "swap the root" that this cannot honour.
     *
     * <p><strong>Alternatives measured and rejected.</strong> {@code toRealPath()} on every
     * call and comparing the string is what {@code list()} did through {@code liveRoot()},
     * and it is a check on a mutable property in the #143 sense: the answer can change
     * before the open that follows it, and a root that is a symbolic link to a directory
     * whose <em>own</em> name is rotated defeats it outright. Holding one descriptor open
     * for the store's lifetime is the textbook answer and is stronger — it keeps working on
     * the original inode rather than refusing — but {@code MemoryStore} has no close, so it
     * would mean a file descriptor per store held forever and a lifecycle change reaching
     * every caller, to buy the difference between "refuses" and "carries on" for a
     * directory somebody has already taken away. Refusing to operate on a root that is not
     * this store's root is the answer this repository's contract already has a channel for.
     *
     * <p><strong>What it does not reach.</strong> {@link #freshFolderUnder} still names
     * {@code root} to make a staging directory, because {@code java.nio.file} has no
     * {@code mkdirat}. A rotation landing between this open and that {@code mkdir} creates
     * an <em>empty directory</em> in the attacker's directory; the {@code renameat} that
     * would move it to the key is relative to the descriptor opened here, so it fails, the
     * descent retries, and nothing is written, read, removed or named outside the root.
     * That is the same residue this class's own javadoc weighed and rejected for
     * {@code Files.createDirectories} — narrowed there from the key's whole path to one
     * name directly under the root, and not removable without a syscall the API does not
     * expose.
     *
     * <p><strong>{@code append}'s second name was the sharper one and is gone (#227).</strong>
     * {@code FileMemoryStore.aSecondNameUnderTheRoot} used to take a hard link at a name
     * under the root so that the read leg could ask a question and open an answer about one
     * inode; {@code link(2)} takes two paths, there is no {@code linkat} in
     * {@code java.nio.file}, and a rotation landing on that call left a readable hard link to
     * a memory document in the rotating process's own directory — 16, 7 and 23 per 20,000
     * appends against a thread renaming the root away and back. The link is not taken any
     * more: {@code FileMemoryStore.whatTheKeyHolds} opens the key through the pinned folder
     * with {@code O_RDWR}, which cannot block, and proves the descriptor is a document by
     * seeking it. No path is spelled, so nothing is left anywhere to rotate into. That
     * leaves {@code mkdir} alone at this wall.
     *
     * <p><strong>A FIFO at the root's own name hung every call, with no race at all.</strong>
     * The {@code Files.newDirectoryStream} below is the same {@code open(2)} without
     * {@code O_DIRECTORY} that {@link #step} describes, so {@code mv mem aside; mkfifo mem}
     * made {@code list} block forever — measured, one command and one call. It runs under
     * {@link #withoutHangingOnAFifo} now, like every other directory open in this class.
     *
     * @param root   the memory root as the store resolved it at construction
     * @param rootIs what {@link #identityOf} answered for it then, or {@code null} where the
     *     platform would not say
     * @throws RootRotated if the name now leads to a different inode
     */
    static DirectoryStream<Path> rootAsOpened(Path root, Object rootIs) throws IOException {
        DirectoryStream<Path> here = withoutHangingOnAFifo(
                () -> Files.newByteChannel(root, READ_WRITE),
                () -> Files.newDirectoryStream(root));
        try {
            Object nowIs = identityOf(here, root);
            if (rootIs != null && !Objects.equals(rootIs, nowIs)) {
                throw new RootRotated(root.toString(), null,
                        "the memory root now names a different directory than the one this"
                                + " store opened; refusing rather than following it");
            }
            return here;
        } catch (IOException | RuntimeException notOurs) {
            try {
                here.close();
            } catch (IOException | RuntimeException alreadyGone) {
                notOurs.addSuppressed(alreadyGone);
            }
            throw notOurs;
        }
    }

    /**
     * Which inode {@code root} is, recorded once so a later open can be compared with it.
     *
     * <p>{@code null} where the platform will not say. {@code fileKey()} is documented to
     * answer {@code null} on file systems that have nothing unique to offer — Windows
     * volumes without a file index are the case — and there is no honest comparison to make
     * then. Answered rather than thrown, and skipped rather than silently passed, for the
     * same reason {@link #isPinned()} answers false out loud: a fallback that quietly
     * reinstates the defect is how this kind of fix rots. On this platform it is a
     * {@code UnixFileKey} carrying {@code st_dev} and {@code st_ino}, which is exactly the
     * pair that identifies a file to the kernel.
     *
     * <p>Asked by opening the directory and {@code fstat}ing the descriptor, which is the
     * long way round for a question {@code Files.readAttributes} would answer in one call —
     * and it is the point. This is the value every later comparison is made against, so it
     * has to be produced by the same code path those comparisons use; two spellings of "which
     * inode is this" that disagreed about the answer's <em>form</em> would make every
     * operation refuse a root that had never moved.
     */
    static Object identityOf(Path root) throws IOException {
        try (DirectoryStream<Path> here = Files.newDirectoryStream(root)) {
            return identityOf(here, root);
        }
    }

    /**
     * The inode behind an open directory stream, asked of the descriptor where it can be.
     *
     * <p>{@link SecureDirectoryStream#getFileAttributeView(Class)} with no name is
     * {@code fstat} of the directory's own descriptor, so the answer is about the thing
     * already held rather than about whatever the name leads to now. The path fallback is
     * for the platforms {@link #isPinned()} covers: there the question has a window, which
     * is the same window everything else has there.
     */
    private static Object identityOf(DirectoryStream<Path> here, Path root) throws IOException {
        if (here instanceof SecureDirectoryStream<?> secure) {
            return secure.getFileAttributeView(BasicFileAttributeView.class)
                    .readAttributes().fileKey();
        }
        return Files.readAttributes(root, BasicFileAttributes.class).fileKey();
    }

    /**
     * The memory root's name no longer leads to the memory root.
     *
     * <p>Deliberately <strong>not</strong> a {@link FileSystemException}: {@link #descend}
     * folds those into "no memory here" for the four asking operations, and a rotated root
     * is the one thing that must not answer absent. Absent is what a store says about a key;
     * this is about the store.
     */
    static final class RootRotated extends IOException {

        private static final long serialVersionUID = 1L;

        RootRotated(String file, String other, String reason) {
            super(file + (other == null ? "" : " -> " + other) + ": " + reason);
        }
    }

    /**
     * One component down, refusing a symbolic link as part of the open.
     *
     * <p>{@link java.nio.file.LinkOption#NOFOLLOW_LINKS} here is doing the work
     * {@code O_NOFOLLOW} does at a final component elsewhere in this package, one level at a
     * time and all the way down. A link is refused, a document is refused, and neither is
     * retried — only absence is, and only when the caller is writing.
     *
     * <h4>The type is asked first, and it has to be</h4>
     *
     * <p>Not defensive tidying. {@link SecureDirectoryStream#newDirectoryStream} is
     * {@code openat(dfd, name, O_RDONLY|O_NOFOLLOW)} and <strong>does not pass
     * {@code O_DIRECTORY}</strong> — the JDK opens the descriptor and then hands it to
     * {@code fdopendir}. {@code open(2)} on a FIFO without {@code O_NONBLOCK} blocks until
     * somebody opens the other end, so a FIFO standing at an intermediate component hung
     * the thread inside the syscall, where no error, no result and no timeout reaches it.
     * That is #189's disease in a new place, and it was found by
     * {@code NonDocumentAtAKeyTest.aNonDocumentAtAnAncestorIsAMediumErrorAndNotThisRefusal},
     * which plants exactly that FIFO and stood still forever rather than failing. Nothing
     * about it was subtle once seen; it is written down here because the same trap is set
     * for anybody who reaches for {@code SecureDirectoryStream} expecting {@code O_DIRECTORY}
     * semantics from the name.
     *
     * <p><strong>It is a check before an open, so it narrows and does not close</strong> —
     * an attacker who replaces the directory with a FIFO inside the window between the
     * {@code fstatat} and the {@code openat} used to block the thread anyway. Measured
     * against a thread doing nothing but that flip — {@code rmdir} then a hard link to a
     * pre-made FIFO, and back — with each {@code write} bounded on a daemon thread and every
     * attempt counted:
     *
     * <pre>
     * without the type question   attemptsMade=2000  BLOCKED = 14 / 49 / 22
     * with it                     attemptsMade=2000  BLOCKED = 20 /  3 /  4
     * </pre>
     *
     * <p>What closes the rest is not another check but {@link #withoutHangingOnAFifo}, which
     * wraps the open below and frees it when it wedges. So this question no longer carries
     * the safety property, and the reason it stays is smaller and worth stating: a FIFO
     * standing still is refused here in one {@code fstatat} instead of after a two
     * millisecond wedge, and a symbolic link and a document are refused with the exception
     * each of them already earned rather than with whatever {@code openat} reports.
     *
     * <p><strong>Dropping it is a mutant that survives, and it survives on purpose.</strong>
     * Measured with the type question deleted and the rendezvous left in place: the sitting
     * FIFO that {@code NonDocumentAtAKeyTest} plants still refuses, with the same
     * {@code NotDirectoryException} naming the same component, two milliseconds later —
     * {@code fdopendir} answers {@code ENOTDIR} for the descriptor the rendezvous frees, and
     * that is the same exception this line throws by hand. Equivalent in outcome, not merely
     * unkilled. The raced counts above are what the line is worth, and they are a narrowing
     * of a window that is now closed by other means.
     */
    private static SecureDirectoryStream<Path> step(Path root,
            SecureDirectoryStream<Path> rootStream, SecureDirectoryStream<Path> at,
            Path component, boolean creating) throws IOException {
        IOException lastRefusal = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            BasicFileAttributes standing = factsAbout(at, component);
            if (standing == null) {
                if (!creating) {
                    throw new NoSuchFileException(component.toString());
                }
                lastRefusal = make(root, rootStream, at, component);
                continue;
            }
            if (!standing.isDirectory()) {
                // A symbolic link, a document, a FIFO, a socket, a device node. All of them
                // used to arrive as the open refusing -- except the FIFO, which arrived as
                // the thread never coming back.
                throw new NotDirectoryException(component.toString());
            }
            try {
                return openFolder(at, component);
            } catch (NoSuchFileException notYet) {
                if (!creating) {
                    throw notYet;
                }
                lastRefusal = make(root, rootStream, at, component);
            }
        }
        FileSystemException gaveUp = new FileSystemException(component.toString(), null,
                "the folder could not be made; something is removing it as fast as it is made");
        if (lastRefusal != null) {
            gaveUp.initCause(lastRefusal);
        }
        throw gaveUp;
    }

    /**
     * Makes {@code component} inside {@code at}, or answers with why it could not.
     *
     * <p>Staged under the root and renamed in, for the reason this class's javadoc gives:
     * there is no {@code mkdirat} in {@code java.nio.file}, and a lexical {@code mkdir} on
     * the key's own path is the syscall the attacker is waiting for. A name directly under
     * the root has no intermediate component to swap.
     *
     * <p>Losing the rename is ordinary rather than exceptional. Another writer creating the
     * same folder first shows up as {@code FileAlreadyExistsException} or
     * {@code DirectoryNotEmptyException}; the staged folder is dropped and the caller's next
     * turn round the loop opens whatever is now there. It is returned rather than thrown so
     * that a genuine refusal — a read-only medium — reaches the caller as the cause of the
     * give-up above instead of vanishing.
     */
    private static IOException make(Path root, SecureDirectoryStream<Path> rootStream,
            SecureDirectoryStream<Path> at, Path component) throws IOException {
        Path staged = freshFolderUnder(root);
        try {
            if (factsAbout(at, component) != null) {
                // Somebody else made it while this one was staging. Asked because
                // renameat REPLACES an empty directory rather than refusing it, and
                // java.nio.file cannot reach renameat2(RENAME_NOREPLACE): the writer whose
                // folder is replaced has already opened it, so its document lands in a
                // directory with no name and is lost when the descriptor closes. Measured
                // over 16 threads x 100 keys under one folder that does not exist yet,
                // three runs: 1,600 of 1,600 keys readable afterwards with this line, and
                // 1,600 of 1,600 without it -- so the window it shuts is narrower than that
                // harness can reach, and one fstatat is a cheap price for not leaving a way
                // to lose a memory that nothing measures.
                Files.deleteIfExists(staged);
                return null;
            }
            rootStream.move(staged.getFileName(), at, component);
            return null;
        } catch (IOException somebodyElseFirst) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException couldNotTidy) {
                somebodyElseFirst.addSuppressed(couldNotTidy);
            }
            return somebodyElseFirst;
        }
    }

    /**
     * An empty folder under the root at a name nothing else holds.
     *
     * <p>{@code Files.createDirectory} rather than {@code createTempDirectory}, and the
     * difference is the mode: {@code createTempDirectory} makes an owner-only folder, so
     * every folder a key implies would have quietly become {@code rwx------} where
     * {@code Files.createDirectories} left it at the process umask. A permission change is
     * not a thing to ship inside a containment fix.
     */
    private static Path freshFolderUnder(Path root) throws IOException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return Files.createDirectory(root.resolve(freshName()));
            } catch (FileAlreadyExistsException taken) {
                // A 64-bit name collided, or somebody guessed it back and claimed it.
                // createDirectory is exclusive either way, so the answer is another name.
            }
        }
        throw new IOException("no free staging name under the memory root");
    }

    /**
     * A name this store will not mistake for a memory.
     *
     * <p>Same deliberately malformed percent-escape as the write temporaries, for the same
     * reason: {@code MemoryFilenames.keyOf} cannot decode it, so {@code list()} skips it and
     * reports it to the operator rather than offering it to the model as a key.
     */
    static String freshName() {
        return FileMemoryStore.TEMP_PREFIX
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 16) + ".tmp";
    }

    /** Whether this platform gave us {@code openat}; false means the caller is on its own. */
    boolean isPinned() {
        return parent != null;
    }

    /** The pinned parent directory. Only meaningful when {@link #isPinned()}. */
    SecureDirectoryStream<Path> parent() {
        return parent;
    }

    /** The key's final component, to be used relative to {@link #parent()}. */
    Path name() {
        return name;
    }

    /**
     * What stands at the key, or {@code null} if nothing does.
     *
     * <p>{@code fstatat(dirfd, name, AT_SYMLINK_NOFOLLOW)}. A symbolic link at the key
     * answers as itself rather than as its target, which is what every caller here wants:
     * this store stopped treating a link as a memory in #99.
     */
    BasicFileAttributes standing() throws IOException {
        return factsAbout(parent, name);
    }

    /**
     * What {@code name} inside {@code at} is, or {@code null} if it is nothing.
     *
     * <p>{@code fstatat(dirfd, name, AT_SYMLINK_NOFOLLOW)}. A symbolic link answers as
     * itself rather than as its target, which is what every caller here wants: this store
     * stopped treating a link as a memory in #99.
     */
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
     * What a survey of the key's own subtree found, and whether it got to the end of it.
     *
     * <p>Deliberately not {@code FileMemoryStore.Standing}, which is the answer to a wider
     * question: what stands <em>at</em> the key, including the node itself. This enum is only
     * about what is <em>under</em> it, which is the part that costs a walk.
     */
    enum Below {

        /** Folders and nothing else, all the way down — what {@code clearTheWay} may remove. */
        NOTHING_BUT_FOLDERS,

        /** At least one document, which settles the collision and stops the walk. */
        A_DOCUMENT,

        /**
         * Something that is neither a document nor a folder, or a folder that would not be
         * read. Unreadable is not empty, which is the answer both callers need.
         */
        SOMETHING_ELSE,

        /** More entries than {@link #survey} will look at (#146). */
        TOO_MANY_ENTRIES,

        /** Nested deeper than {@link #survey} will descend (#146). */
        TOO_DEEP
    }

    /**
     * Whether any document lives under the key, asked at-relative and asked with a budget.
     *
     * <h4>The two defects this replaces, which are one method (#146, #236)</h4>
     *
     * <p>{@code FileMemoryStore.whatStandsAt} asked this with {@code Files.walkFileTree} on
     * the key's path, and that was the last walk by name left in the write path.
     *
     * <p><strong>It resolved by name (#236).</strong> {@code walkFileTree} opens every
     * directory it descends into through {@code Files.newDirectoryStream}, which is the same
     * {@code open(2)} without {@code O_DIRECTORY} that {@link #step} describes — so a FIFO
     * raced under the key wedged the thread inside the JDK's own walk, where nothing in this
     * package can reach it: {@link #withoutHangingOnAFifo} can only wrap an open this code
     * makes. Measured with everything in #235 applied, a thread flipping one folder under the
     * key between a directory and a hard link to a FIFO, every attempt counted and bounded on
     * a daemon thread:
     *
     * <pre>
     * attemptsMade=2000  declined=1216  acted=781  BLOCKED=3
     * attemptsMade=2000  declined=1790  acted=204  BLOCKED=6
     * attemptsMade=2000  declined=1609  acted=385  BLOCKED=6
     * </pre>
     *
     * <p>Every one of them inside {@code Files.walkFileTree}. Through here, on the same
     * harness: {@code BLOCKED=0} three times, with {@code declined} and {@code acted} both
     * still positive so the store was meeting the flipped side.
     *
     * <p><strong>It was unbounded, and the model chooses the bound (#146).</strong> Nothing
     * limits how much stands under a key. {@code MemoryKeys} bounds a key's depth and not the
     * width of what somebody planted below it, and the planting needs no privilege at all:
     * {@code write("a/dN/x.md")} then {@code delete("a/dN/x.md")} leaves the folder behind —
     * {@code clearTheWay}'s javadoc records why pruning it in {@code delete} was moved out —
     * so N pairs of ordinary tool calls leave N directories that {@code list("")} reports as
     * <strong>zero keys</strong> and that every later write to that key pays for. Measured on
     * this machine, through {@code MemoryTools}' own operations:
     *
     * <pre>
     * pairs      entries under "a"   list("")   write("a")
     *  1,000            1,000        0 keys        78 ms
     *  5,000            5,000        0 keys       324 ms
     * 20,000           20,000        0 keys     1,422 ms
     * </pre>
     *
     * <h4>Why a bound does not refuse a real key, which is the thing to get right</h4>
     *
     * <p>Because the walk that runs to the end is exactly the walk that finds nothing. A
     * document settles the question and stops it, so a key holding memories is answered by
     * the first one the walk meets, not by all of them. Measured, keys written under
     * {@code notes} and then {@code write("notes")} asked:
     *
     * <pre>
     * keys under "notes"    100    1,000    5,000
     * write("notes")       0 ms     0 ms     0 ms   and refused with the hierarchy message
     * </pre>
     *
     * <p>Flat at zero, past the bound, because the walk never gets past the first document.
     * The subtree that is walked in full is one with
     * <strong>no document anywhere in it</strong> — which is a subtree holding no memory at
     * all, so what the bound refuses to enumerate is by construction empty of keys.
     *
     * <p>What that leaves as the honest cost is a store whose model wrote and deleted under
     * more than {@code entries} distinct sub-prefixes of one key and then wrote to that key
     * itself. It is refused with a reason naming the key, and a sibling key still works,
     * which is what makes the refusal actionable rather than a dead end.
     *
     * <h4>Iterative, and bounded twice</h4>
     *
     * <p>Recursion is what {@link #clear} uses and it would be wrong here for the reason
     * {@code FileMemoryStore.walkPinned} gives: a tree deep enough to exhaust the stack costs
     * one {@code mkdir} per level to build, so a {@code StackOverflowError} in here would be a
     * denial of service {@code walkFileTree} did not have. Descriptors are held one per level
     * and given back as the walk leaves each folder, so the width costs nothing and only the
     * depth does — which is the second bound, and why there is one.
     *
     * <h4>What a mutation pass over this method does not kill, and why</h4>
     *
     * <p>Fifteen mutants, nine killed. Three of the survivors are here and each is stated
     * rather than left to be found again.
     *
     * <ul>
     *   <li><strong>The {@code catch} around {@link #factsAbout} answering "skip it" instead
     *       of {@code SOMETHING_ELSE}.</strong> Not equivalent — it would let a write land on
     *       top of an entry nobody could look at — and no construction for it was found. The
     *       walk holds the entry's folder <em>open</em>, so the search permission
     *       {@code fstatat} wants has already been granted, and the name is one component so
     *       it cannot be too long. It is the direct port of {@code visitFileFailed}, which
     *       had the same status in the path version.</li>
     *   <li><strong>The {@code catch} around the folder open answering "skip it".</strong>
     *       Reachable, and killed — on any uid but root. Measured as uid 65534 with a
     *       mode-000 folder holding a document under the key: the refusal naming the key
     *       becomes {@code UncheckedIOException} → "Memory operation failed." Its pin is
     *       {@code MemoryStoreTest.anUnreadableSubtreeRefusesWithAReason}, which skips as
     *       root because {@code chmod 000} is not enforced against uid 0 at all — so this
     *       survives a root-only run and dies on a CI runner that is not root.</li>
     *   <li><strong>An entry that vanished between {@code readdir} and {@code fstatat} being
     *       counted as {@code SOMETHING_ELSE} rather than skipped.</strong> That is what the
     *       path version did — {@code walkFileTree} reports a vanished entry through
     *       {@code visitFileFailed} — so the mutant restores the old behaviour and skipping
     *       is the deliberate improvement: nothing stands there, so nothing is in the way.
     *       Killing it needs an entry removed inside the window, which no deterministic test
     *       can arrange.</li>
     * </ul>
     *
     * @param entries how many directory entries this will look at before refusing
     * @param deepest how many folders below the key this will descend before refusing
     */
    Below survey(int entries, int deepest) throws IOException {
        Deque<Level> stack = new ArrayDeque<>();
        int seen = 0;
        Below found = Below.NOTHING_BUT_FOLDERS;
        try {
            SecureDirectoryStream<Path> top = openFolder(parent, name);
            stack.push(new Level(top, top.iterator()));
            while (!stack.isEmpty()) {
                Level level = stack.peek();
                if (!level.entries().hasNext()) {
                    stack.pop().dir().close();
                    continue;
                }
                Path entry = level.entries().next().getFileName();
                if (++seen > entries) {
                    return Below.TOO_MANY_ENTRIES;
                }
                BasicFileAttributes standing;
                try {
                    standing = factsAbout(level.dir(), entry);
                } catch (IOException cannotLook) {
                    // visitFileFailed's case, and the same answer it gave: unreadable is not
                    // empty. It keeps a write from landing on top of documents nobody could
                    // enumerate, and from clearing a tree it could not see.
                    return Below.SOMETHING_ELSE;
                }
                if (standing == null) {
                    // Gone between readdir and fstatat. Nothing stands there, so there is
                    // nothing to count against the key.
                    continue;
                }
                if (standing.isRegularFile()) {
                    // A document settles it outright, and this is the return that keeps the
                    // bound above from refusing keys that hold memories.
                    return Below.A_DOCUMENT;
                }
                if (!standing.isDirectory()) {
                    // A symbolic link most often, since #99, and equally a FIFO, a socket or
                    // a device node. The folder cannot be removed and is not a collision
                    // either -- and the walk carries on, because a document deeper in still
                    // outranks this.
                    found = Below.SOMETHING_ELSE;
                    continue;
                }
                if (stack.size() > deepest) {
                    // stack.size() counts the key's own folder as well, so this is "deepest
                    // folders below the key are already open and this would be one more" --
                    // which is what the refusal says, exactly. With >= it was one short of
                    // its own message.
                    return Below.TOO_DEEP;
                }
                SecureDirectoryStream<Path> inside;
                try {
                    inside = openFolder(level.dir(), entry);
                } catch (IOException cannotOpen) {
                    // A folder that will not open holds documents this cannot rule out, and
                    // this is also where a directory that turned into a link between the
                    // fstatat above and this line arrives: NOFOLLOW_LINKS makes the open
                    // refuse it rather than descend into somebody else's directory.
                    return Below.SOMETHING_ELSE;
                }
                stack.push(new Level(inside, inside.iterator()));
            }
        } catch (DirectoryIteratorException readdirFailed) {
            // What a DirectoryStream raises when readdir(3) fails part way through, which is
            // a RuntimeException and so would otherwise leave this method by a door its
            // caller does not watch. walkFileTree reported the same failure through
            // postVisitDirectory, and the default SimpleFileVisitor rethrew it as the
            // IOException the old catch turned into this answer.
            return Below.SOMETHING_ELSE;
        } finally {
            // A walk that stopped early still has to give back what it took.
            while (!stack.isEmpty()) {
                try {
                    stack.pop().dir().close();
                } catch (IOException | RuntimeException alreadyGone) {
                    // Nothing a caller could do with this, and the answer already stands.
                }
            }
        }
        return found;
    }

    /** One open folder on the way down, and where the walk had got to in it. */
    private record Level(SecureDirectoryStream<Path> dir, Iterator<Path> entries) {
    }

    /**
     * Removes a folder tree at the key that holds nothing but folders.
     *
     * <p>The at-relative twin of what {@code clearTheWay} used to do with
     * {@code Files.walkFileTree} on the key's path — which was the last destructive syscall
     * in the write path still spelled as a path, and so the last one a swapped intermediate
     * component could redirect. Measured on the same harness as this class's opening table,
     * the reach was narrower than {@code write}'s or {@code delete}'s (it only ever removed
     * <em>empty</em> directories, so nothing outside could be lost) which is why it is
     * mentioned here rather than in the table: a directory outside the root being removed is
     * still a write outside the root.
     *
     * <p>Recursive, and bounded, which the iterative {@code walkFileTree} it replaces did
     * not need to be: a tree deep enough to exhaust the stack is one directory entry per
     * level to build, so an unbounded recursion here would be a denial of service that the
     * old code did not have. {@code MemoryKeys} admits 512 key components; this is about
     * what somebody else planted under the key, which no rule bounds, so the depth is
     * refused rather than descended and the caller reports the folder as one it could not
     * remove.
     *
     * @throws IOException if anything that is not an empty folder is in the way, which is
     *     the same answer {@code deleteIfExists} gave for a folder that is not empty
     */
    void clear() throws IOException {
        clear(parent, name, CLEARING_DEPTH);
    }

    /** How deep {@link #clear()} will go before refusing. */
    private static final int CLEARING_DEPTH = 64;

    private static void clear(SecureDirectoryStream<Path> at, Path folder, int depth)
            throws IOException {
        if (depth <= 0) {
            throw new FileSystemException(folder.toString(), null,
                    "the folder is nested deeper than this store will follow");
        }
        BasicFileAttributes standing = factsAbout(at, folder);
        if (standing == null) {
            // Somebody else removed it. The way is clear, which is what the caller asked.
            return;
        }
        if (!standing.isDirectory()) {
            // Anything that is not a folder abandons the whole clearing, which is
            // deliberate and is what the caller's refusal is for: this store removes folders
            // it can prove hold nothing, and refuses the key otherwise. Asked rather than
            // discovered by opening, for the reason step() gives at length: the open does
            // not carry O_DIRECTORY, so discovering a FIFO this way means never discovering
            // it.
            throw new NotDirectoryException(folder.toString());
        }
        try (SecureDirectoryStream<Path> inside = openFolder(at, folder)) {
            for (Path entry : inside) {
                clear(inside, entry.getFileName(), depth - 1);
            }
        }
        at.deleteDirectory(folder);
    }

    /**
     * Gives back every descriptor the descent took.
     *
     * <p>Does not throw. A directory stream's close releases a file descriptor, and a
     * failure to release one cannot make the operation that already completed untrue —
     * turning a finished read into an exception would be the worse answer.
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
