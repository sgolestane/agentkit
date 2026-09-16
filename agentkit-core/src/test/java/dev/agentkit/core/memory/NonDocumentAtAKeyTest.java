package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a write does when something that is not a document stands at the key (#145).
 *
 * <h2>The defect</h2>
 *
 * <p>{@code FileMemoryStore} guarded a <em>folder</em> at a key and nothing else, because
 * {@code whatStandsAt} asked only {@code isDirectory} and answered {@code NOTHING_IN_THE_WAY}
 * for everything that was not one. A FIFO or a device node was therefore reported free and
 * handed straight to the open. Measured, before:
 *
 * <pre>
 * fifo          write  = BLOCKED (still waiting after 5s)   read = Optional.empty
 * chardev       write  = returned normally                  read = Optional.empty
 * unix-socket   write  = threw UncheckedIOException         read = Optional.empty
 * </pre>
 *
 * <p>The FIFO is the one that matters. A hang is not a failure a caller can observe — no
 * error, no result, nothing in the transcript, and no timeout to trip — so a live agent
 * simply stops. The device node is the quieter version of the same thing: the store confirms
 * a memory it does not hold, and the next read answers empty after the model was told the
 * write succeeded.
 *
 * <h2>Why the write side is the only side that was wrong</h2>
 *
 * <p>{@code read} has always asked {@code Files.isRegularFile} and answers
 * {@code Optional.empty} for all three shapes — measured above, in the same run. This is the
 * write side finally asking the question the read side already asked.
 *
 * <h2>Reachability</h2>
 *
 * <p>None of these can be created through {@code MemoryTools}: the model writes regular files
 * and creates directories, and nothing else. It needs filesystem access under the root, which
 * is the same threat position as #143's race. The reason to guard anyway is that "the model
 * cannot create it" is not "it cannot be there" — an operator's {@code docker run -v} mount is
 * enough — and a hang is the one failure mode a caller cannot detect and time out.
 *
 * <h2>Every assertion here is time-bounded</h2>
 *
 * <p>Deliberately. A regression does not make these tests fail, it makes them <em>hang</em>,
 * and a hung suite in CI reads as an infrastructure problem rather than as this bug. Each
 * store call runs on its own thread with a hard bound, so the regression comes back as a
 * named assertion failure.
 */
class NonDocumentAtAKeyTest {

    /** Long enough that a slow machine is not mistaken for a hang, short enough to be a test. */
    private static final int BOUND_SECONDS = 10;

    @TempDir
    Path root;

    /** Runs one store call with a hard bound, so a hang is measurable rather than fatal. */
    private static String bounded(Callable<String> call) {
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            // Daemon, because a thread blocked on opening a FIFO is inside a syscall and
            // will not answer an interrupt. shutdownNow cannot reclaim it; the JVM exiting
            // is what reclaims it.
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> running = pool.submit(call);
            return running.get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException hung) {
            return "BLOCKED";
        } catch (Exception failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            return channelOf(cause) + ": " + cause.getMessage();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Which of {@code MemoryStore}'s two channels a failure arrived in, named by the type
     * the contract names rather than by the concrete class.
     *
     * <p>It was {@code getSimpleName()}, which is a different question and stopped agreeing
     * with this one at #257: a refusal now carries a {@link MemoryRefusal}, so its class is
     * a package-private subclass of {@link IllegalArgumentException} and
     * {@code getSimpleName()} answers with that subclass's name. Every clause below is about
     * the channel — "the model was told something it can act on" — and none of them is about
     * which class carried it, which is the same trap {@code MemoryStoreDifferentialTest}'s
     * {@code Outcome} records having fallen into for {@code SafePaths}' own subclass.
     */
    private static String channelOf(Throwable failure) {
        if (failure instanceof IllegalArgumentException) {
            return "IllegalArgumentException";
        }
        if (failure instanceof java.io.UncheckedIOException) {
            return "UncheckedIOException";
        }
        return failure.getClass().getSimpleName();
    }

    private static Path fifoAt(Path path) throws Exception {
        assumeTrue(java.io.File.separatorChar == '/', "no FIFOs on this platform");
        // The start() is inside the try, because it throws IOException when the binary is
        // absent -- BEFORE any assumeTrue could fire. An earlier version had the assumption
        // after it and the PR body claimed these tests "skip where mkfifo is unavailable";
        // measured with mkfifo off the PATH, the suite went RED with
        // "Cannot run program mkfifo: error=2" rather than skipping.
        Process made;
        try {
            made = new ProcessBuilder("mkfifo", path.toString()).start();
        } catch (IOException noSuchBinary) {
            assumeTrue(false, "mkfifo is not on the PATH here");
            throw new AssertionError("unreachable");
        }
        assumeTrue(made.waitFor(30, TimeUnit.SECONDS) && made.exitValue() == 0,
                "mkfifo could not create a FIFO here");
        return path;
    }

    /** A socket, made in pure Java, so this needs no external tool and no privileges. */
    private static Path socketAt(Path path) throws IOException {
        assumeTrue(java.io.File.separatorChar == '/', "no unix sockets on this platform");
        try (ServerSocketChannel channel =
                     ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.bind(UnixDomainSocketAddress.of(path));
        }
        // Closed, and the socket file stays: close() does not unlink it. An earlier version
        // left the channel open and said so deliberately, which confused "the path must
        // remain" with "the descriptor must remain" — only the first is true, and holding a
        // descriptor per test is what makes a suite fail on a low fd limit.
        return path;
    }

    @Test
    void aFifoAtAKeyIsRefusedRatherThanWaitedOnForever() throws Exception {
        Path key = fifoAt(root.resolve("notes.md"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        String outcome = bounded(() -> {
            store.write("notes.md", "hello");
            return "returned normally";
        });

        assertThat(outcome)
                .as("opening a FIFO with no reader blocks forever, and a caller cannot see"
                        + " it happen: no error, no result, and nothing to time out")
                .isNotEqualTo("BLOCKED")
                .startsWith("IllegalArgumentException")
                .contains("not a document")
                .contains("choose another key");
        assertThat(Files.readAttributes(key, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isOther())
                .as("refusing is not deleting: the FIFO was somebody else's to remove")
                .isTrue();
    }

    @Test
    void aSocketAtAKeyIsRefusedWithTheReasonAndNotAMediumError() throws Exception {
        // Before, this one threw UncheckedIOException — which MemoryTools reduces to
        // "Memory operation failed.", a message the model cannot act on. It is the same
        // cause as the FIFO and now gets the same answer, which is the point of asking
        // about the shape rather than letting each shape fail its own way at the open.
        socketAt(root.resolve("notes.md"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        assertThatThrownBy(() -> store.write("notes.md", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a document");
    }

    @Test
    void appendIsRefusedForTheSameReason() throws Exception {
        // append goes through the same requireTheKeyIsFree, and would hang identically.
        // Asserted rather than assumed: the two writers have diverged in this file before.
        Path key = fifoAt(root.resolve("notes.md"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        String outcome = bounded(() -> {
            store.append("notes.md", "hello");
            return "returned normally";
        });

        assertThat(outcome)
                .isNotEqualTo("BLOCKED")
                .startsWith("IllegalArgumentException")
                .contains("not a document");
        assertThat(Files.exists(key, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void aNonDocumentAtAnAncestorNamesTheComponentRatherThanBeingADeadEnd() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dev.agentkit.core.util.Pinning.available(), dev.agentkit.core.util.Pinning.WHY);
        // #188, and this test used to pin the opposite. It was
        // aNonDocumentAtAnAncestorIsAMediumErrorAndNotThisRefusal, and it existed to hold a
        // branch that was undefended -- the mutant turning `catch (IOException) ->
        // NOTHING_IN_THE_WAY` into `-> NOT_A_DOCUMENT` passed all 1,015 core tests -- while
        // saying in its own comment that the behaviour it pinned was wrong:
        //
        //   write a/b.md -> UncheckedIOException -> "Memory operation failed."
        //   write a      -> the refusal, naming the key
        //
        // Same operator, same node, same mount: the leaf got the actionable refusal and the
        // ancestor got the dead end, because readAttributes on a/b.md answers ENOTDIR and
        // ENOTDIR and EACCES arrived as the same IOException. The pinned descent asks one
        // component at a time, so they are different exceptions now, and the one for a
        // component that is not a folder carries which component it was.
        //
        // A socket rather than a FIFO, because socketAt needs no external binary and no
        // privileges: this test must not SKIP, since a skipped test is worse than no test
        // for a branch a mutant can delete. The FIFO case is the same branch -- the descent
        // keys off "not a directory" rather than off the node type -- and it is what
        // MemoryRaceTest races.
        socketAt(root.resolve("a"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        assertThatThrownBy(() -> store.write("a/b.md", "hello"))
                .as("a write under a component that is not a folder still reaches the model"
                        + " as \"Memory operation failed.\", which is the dead end #115 and"
                        + " #145 both exist to remove")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'a/b.md'")
                .hasMessageContaining("'a' stands in that path and is not a folder");

        // The half that makes "choose a key that does not pass through it" true rather than
        // merely encouraging. This is the property that separates this case from the
        // unsearchable-parent case the same catch still folds: there, every key fails alike
        // and the advice would be a lie.
        store.write("b.md", "elsewhere");
        assertThat(store.read("b.md")).contains("elsewhere");
    }

    @Test
    void aDocumentAtAnAncestorKeepsTheHierarchyMessageRatherThanTheGeometryOne()
            throws Exception {
        // The ordering the fix above depends on, pinned. A regular file at an ancestor
        // reaches BOTH refusals: the descent cannot open it as a folder, and
        // MemoryNamespace.requireFree sees a document at an ancestor key. requireFree's
        // message names the rule the model broke; the new one names the geometry. The rule
        // is the better answer, so requireFree has to go first -- and a mutant reordering
        // the two lines passes every other test in this file.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("a", "I am a document");

        assertThatThrownBy(() -> store.write("a/b.md", "hello"))
                .as("a document at an ancestor was reported as geometry rather than as the"
                        + " hierarchy rule, which is the message #83 added and both stores"
                        + " share")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'a' already holds a document");
    }

    @Test
    void aNonDocumentMetFirstDoesNotHideAKeyDeeperInTheSameFolder() throws Exception {
        // The one branch of the subtree survey a mutant can flip while every other test in
        // this package stays green: "a non-document does not settle it, a document does".
        // Turning `found = SOMETHING_ELSE; continue;` into `return SOMETHING_ELSE;` makes the
        // walk stop at the first symbolic link -- and the folder below it still holds a real
        // key, so the model is told "a folder stands there holding entries that are not
        // documents" about a folder full of its own memories, instead of "other keys already
        // live under it". Both refuse; which reason the model gets is the whole of #115, and
        // MemoryStoreTest's own comment says so about the same pair of messages.
        //
        // The walk that this replaced had the same shape -- SimpleFileVisitor.visitFile
        // returning CONTINUE for a non-document and TERMINATE for a document -- so this is an
        // inherited branch rather than a new one, and it was undefended in both.
        //
        // THE FIXTURE DEPENDS ON READDIR ORDER, which is the kernel's and not this code's, so
        // a mutant cannot cause the assumption below to fire. Measured here, 200 trials out
        // of 200 on ext4 report 'link.md' before 'sub'; it is asserted rather than assumed to
        // hold, so a filesystem that orders them the other way says so instead of quietly
        // proving nothing.
        Files.writeString(root.resolve("target.md"), "T");
        try {
            Files.createSymbolicLink(root.resolve("a"), root.resolve("nowhere"));
        } catch (IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "symbolic links unavailable here: " + noLinks);
        }
        Files.delete(root.resolve("a"));
        Path folder = Files.createDirectory(root.resolve("a"));
        Files.createSymbolicLink(folder.resolve("link.md"), root.resolve("target.md"));
        Files.writeString(Files.createDirectory(folder.resolve("sub")).resolve("real.md"), "R");

        java.util.List<String> order = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                order.add(entry.getFileName().toString());
            }
        }
        assumeTrue(order.indexOf("link.md") < order.indexOf("sub"),
                "this filesystem reports the document's folder before the symbolic link ("
                        + order + "), so the walk never meets the non-document first and this"
                        + " test cannot reach the branch it is for");

        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        assertThatThrownBy(() -> store.write("a", "X"))
                .as("a symbolic link met first ended the survey, so a folder holding a real"
                        + " key was reported as holding no documents and the model was given"
                        + " the wrong one of two refusals")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other keys already live under it")
                .hasMessageNotContaining("holding entries that are not documents");

        // Nothing was cleared on the way to refusing, which is the property clearTheWay's
        // javadoc pins for the sibling case.
        assertThat(Files.exists(folder.resolve("link.md"), LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(store.read("a/sub/real.md")).contains("R");
    }

    @Test
    void aFolderAtTheKeyThatCannotBeOpenedRefusesRatherThanReportingItFree() throws Exception {
        // Here rather than beside MemoryStoreTest's anUnreadableSubtreeRefusesWithAReason,
        // which is the same question one level down and which this does not duplicate: that
        // one locks a folder UNDER the key, where the survey's own catch answers, and this
        // one locks the key's folder ITSELF, where the answer used to come from a different
        // catch entirely.
        //
        // The path version asked two questions with two folds. readAttributes of the KEY
        // folded every failure into NOTHING_IN_THE_WAY, because the write that follows fails
        // with the medium error naming the cause; walkFileTree of what is UNDER it folded
        // into SOMETHING_ELSE, because unreadable is not empty. A first draft of the pinned
        // version let the opening of the key's own folder out through the first fold, so a
        // mode-000 folder standing at the key was reported as nothing in the way, clearTheWay
        // agreed, and the write renamed over a directory and reached the model as "Memory
        // operation failed." The two folds are separate again.
        //
        // SKIPPED AS ROOT, and said out loud: chmod 000 is not enforced against uid 0 at all,
        // so the branch cannot be reached there — the same reason and the same wording as its
        // sibling. A mutant folding this back survives a root-only run, which is recorded in
        // the branch's own comment rather than left to be rediscovered.
        Path folder = Files.createDirectory(root.resolve("a"));
        Files.writeString(folder.resolve("real.md"), "R");
        Files.setPosixFilePermissions(folder, java.util.Set.of());
        assumeTrue(!Files.isReadable(folder.resolve("real.md")),
                "this user can read a mode-000 directory (running as root), so the branch"
                        + " this test exists for cannot be reached here");
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        assertThatThrownBy(() -> store.write("a", "X"))
                .as("a folder at the key that could not be opened was reported free, so the"
                        + " write went on to rename over it and the model got \"Memory"
                        + " operation failed.\" instead of a refusal it can act on")
                .isInstanceOf(IllegalArgumentException.class);

        Files.setPosixFilePermissions(folder,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void aDeviceNodeIsRefusedToo() throws Exception {
        // The shape in #145's title, and the only one of the three that failed SILENTLY:
        // a FIFO hangs and a socket throws, but a device node returned success and stored
        // nothing. It had no test, and a mutant exempting character devices passed the
        // whole suite while restoring exactly that.
        //
        // Skipped where mknod is unavailable, which includes CI: creating a device node
        // needs CAP_MKNOD and the runner is not root. That is why the socket case above
        // exists in pure Java — it holds the same branch on every machine, since the branch
        // keys off "neither a regular file nor a directory" rather than off the node type.
        // This test is what pins the specific shape where it can be pinned at all.
        assumeTrue(java.io.File.separatorChar == '/', "no device nodes on this platform");
        Path key = root.resolve("notes.md");
        Process made = new ProcessBuilder("mknod", key.toString(), "c", "1", "3").start();
        assumeTrue(made.waitFor(30, TimeUnit.SECONDS) && made.exitValue() == 0,
                "mknod needs CAP_MKNOD, which CI does not have");
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        assertThatThrownBy(() -> store.write("notes.md", "hello"))
                .as("a character device accepted the write and stored nothing, so the model"
                        + " was told a memory was saved that no read will ever return")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a document");
        assertThat(store.read("notes.md")).isEmpty();
    }

    @Test
    void aWriteRenamesOverTheKeyRatherThanOpeningIt() {
        // The property that closes the race, pinned without racing anything. rename(2) never
        // opens its target, so the file at the key after a second write is a DIFFERENT inode
        // -- which is exactly what "we did not open what was standing there" looks like from
        // the outside. Truncating in place would keep the inode.
        //
        // Measured, with a thread flipping the key between absent and a FIFO, 300 attempts:
        //
        //   before: BLOCKED=88 / 84 / 81   (about 28%, first hit at attempt 1)
        //   after:  BLOCKED=0  / 0  / 0
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("notes.md", "first");
        Object before = fileKeyOf(root.resolve("notes.md"));

        store.write("notes.md", "second");

        assertThat(fileKeyOf(root.resolve("notes.md")))
                .as("the write opened the key in place, so anything standing there when it"
                        + " did -- a FIFO -- would have been opened too")
                .isNotEqualTo(before);
        assertThat(store.read("notes.md")).contains("second");
    }

    @Test
    void aTemporaryFileLeftByACrashIsNotAMemory() {
        // The cost of renaming: a process that dies between the create and the rename
        // leaves a file in the memory root. Measured with an ordinary temp name, list()
        // returned [.write123.tmp, notes.md] and read(".write123.tmp") returned its
        // contents -- a memory the store never wrote.
        //
        // The temp name is a malformed percent-escape, so MemoryFilenames.keyOf cannot
        // decode it and list() -- which already asks keyOf of every entry -- skips it and
        // names it to the operator. No new filtering, and the leftover is still visible to
        // whoever has to clean it up.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("notes.md", "hello");
        Path leftover = temporaryFileLikeTheStoreMakes();

        assertThat(store.list(""))
                .as("a half-finished write was listed as a memory")
                .containsExactly("notes.md");
        assertThat(store.read(leftover.getFileName().toString()))
                .as("a half-finished write was readable as a memory")
                .isEmpty();
    }

    /** The same shape {@code writeWithoutFollowing} creates, without racing a real write. */
    private Path temporaryFileLikeTheStoreMakes() {
        try {
            Path temp = Files.createTempFile(root, "%.write-", ".tmp");
            Files.writeString(temp, "half a document");
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object fileKeyOf(Path path) {
        try {
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class)
                    .fileKey();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void anAppendRenamesOverTheKeyRatherThanOpeningIt() {
        // #189, and the sibling of the test above. append() was the last writer still
        // handing the key to an open, so the hang #145 removed from write survived there:
        //
        //   before: attemptsMade=300 wrote=83 refused=147 BLOCKED=70  rate=23.33%
        //           attemptsMade=300 wrote=96 refused=133 BLOCKED=71  rate=23.67%
        //           attemptsMade=300 wrote=69 refused=199 BLOCKED=32  rate=10.67%
        //   after:  BLOCKED=0 / 0 / 0
        //
        // The property is pinned the same way, without racing anything: a second append
        // leaves a DIFFERENT inode at the key, which is what "we did not open what was
        // standing there" looks like from the outside. Appending in place keeps the inode.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.append("notes.md", "first");
        Object before = fileKeyOf(root.resolve("notes.md"));

        store.append("notes.md", "-second");

        assertThat(fileKeyOf(root.resolve("notes.md")))
                .as("append opened the key in place, so a FIFO standing there when it did"
                        + " would have been opened too")
                .isNotEqualTo(before);
        assertThat(store.read("notes.md")).contains("first-second");
    }

    @Test
    void anAppendReadsWhatIsThereThroughANameNothingElseHolds() throws Exception {
        // The read leg, which is where this fix nearly went wrong. The obvious
        // read-modify-write asks isRegularFile of the key and then opens the key -- a check
        // and an open on a NAME, which is exactly the shape #145 removed from the write leg.
        // Raced with a regular document at the key that the attacker swaps for a FIFO:
        //
        //   lstat the key, then open the key: BLOCKED=28/400, 8/400, 10/400  (46 in 1,200)
        //   through a name of our own:        BLOCKED= 0/400, 0/400,  0/400  ( 0 in 1,200)
        //
        // So it would have moved the hang and reported a smaller number for it. What is
        // pinned here is the observable half: the append leaves no link behind, and the
        // document it read is the one that comes back. A regression to the plain open
        // passes this and fails the race, which is why the numbers are written down.
        //
        // What NOTHING here reaches, and it is named rather than left to be discovered: the
        // branch aSecondNameForWhateverStandsThere takes when the filesystem will not give a
        // hard link. Two mutants of it SURVIVED the whole suite -- returning null instead of
        // the key (append silently forgets the document) and taking the absent answer down
        // the same road (the window reopens). Hard links work on every runner this suite has,
        // so the branch cannot be entered; that is exactly why it was rewritten to carry one
        // line of behaviour instead of its own copy of the read.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("notes.md", "existing");

        String outcome = bounded(() -> {
            store.append("notes.md", "-added");
            return "returned normally";
        });

        assertThat(outcome).isEqualTo("returned normally");
        assertThat(store.read("notes.md")).contains("existing-added");
        try (java.util.stream.Stream<Path> entries = Files.list(root)) {
            assertThat(entries.map(entry -> entry.getFileName().toString()))
                    .as("the hard link the read leg makes is a second name for a live"
                            + " document; leaving one behind puts a %.write- entry in the"
                            + " root for every append")
                    .containsExactly("notes.md");
        }
    }

    @Test
    void anAppendToAnAbsentKeyStillCreatesIt() {
        // The negative control for the read leg's "nothing there" branch. createLink reports
        // NoSuchFileException, which must mean "an empty document" rather than a failure --
        // append creates, and MemoryStore says so.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.append("fresh.md", "hello");

        assertThat(store.read("fresh.md")).contains("hello");
    }

    @Test
    void anOrdinaryDocumentIsStillOverwritten() {
        // The negative control that matters most. The new question is asked on every write,
        // and answering it wrong for a regular file would break the store's main job.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("notes.md", "first");

        store.write("notes.md", "second");

        assertThat(store.read("notes.md")).contains("second");
    }

    @Test
    void anEmptyFolderAtAKeyIsStillClearedRatherThanRefused() throws Exception {
        // The other negative control, and the one with history: an empty folder at a key
        // used to be treated as a collision, which poisoned the key permanently, and a
        // write that fails after createDirectories leaves exactly one behind. The new
        // branch sits directly above that path and must not swallow it.
        //
        // That rationale is #100 ("Settle what a memory operation promises, and make both
        // stores keep it"), not #115 as this comment first said. #115 is the symlink-folder
        // case -- a folder holding only links failing as UncheckedIOException instead of
        // refusing with a reason -- and empty folders appear nowhere in it.
        Files.createDirectories(root.resolve("notes.md"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.write("notes.md", "hello");

        assertThat(store.read("notes.md")).contains("hello");
    }
}
