package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * The descent {@link SafePaths}' check hands its answer to (#167).
 *
 * <p>These are the deterministic half. What they pin is that a link is refused wherever it
 * stands, that a base whose name has been repointed is refused rather than followed, and
 * that the depth bound and the decoder behave as their javadoc says. The half that needs a
 * second thread — the check-then-use window this class exists to remove — is
 * {@code SkillRaceTest} in {@code agentkit-pentest}, with the before-and-after rates.
 */
class PinnedReadTest {

    @Test
    void readsAFileThroughTheDirectoryItLivesIn(@TempDir Path base) throws IOException {
        Files.writeString(Files.createDirectories(base.resolve("refs")).resolve("x.md"), "hello");

        try (PinnedRead read = PinnedRead.of(base, null, Path.of("refs/x.md"))) {
            assertThat(read).isNotNull();
            assertThat(read.readString(StandardCharsets.UTF_8)).isEqualTo("hello");
            assertThat(read.standing().isRegularFile()).isTrue();
        }
    }

    @Test
    void aSymbolicLinkAtTheLastComponentIsNotAFile(@TempDir Path base) throws IOException {
        assumeTrue(symlinksWork(base), "this filesystem has no symbolic links");
        Path outside = Files.writeString(base.resolve("outside"), "secret");
        Files.createDirectories(base.resolve("refs"));
        Files.createSymbolicLink(base.resolve("refs").resolve("x.md"), outside);

        try (PinnedRead read = PinnedRead.of(base, null, Path.of("refs/x.md"))) {
            // fstatat with AT_SYMLINK_NOFOLLOW reports the link as itself, so the caller's
            // "is this a regular file" is answered about the link and not about its target.
            assertThat(read.standing().isSymbolicLink()).isTrue();
            assertThat(read.standing().isRegularFile()).isFalse();
            assertThatThrownBy(() -> read.readString(StandardCharsets.UTF_8))
                    .as("the open followed a link at the last component")
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void aSymbolicLinkAtADirectoryComponentIsNotDescended(@TempDir Path base) throws IOException {
        assumeTrue(Pinning.available(), Pinning.WHY);
        assumeTrue(symlinksWork(base), "this filesystem has no symbolic links");
        Path outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("x.md"), "secret");
        Files.createSymbolicLink(base.resolve("refs"), outside);

        // Absent rather than an exception: a component that is a link is a component this
        // will not enter, and "nothing reachable here" is what the callers do with that.
        assertThat(PinnedRead.of(base, null, Path.of("refs/x.md"))).isNull();
    }

    @Test
    void aBaseWhoseNameNowLeadsElsewhereIsRefusedRatherThanFollowed(@TempDir Path tmp)
            throws IOException {
        assumeTrue(symlinksWork(tmp), "this filesystem has no symbolic links");
        Path base = Files.createDirectories(tmp.resolve("base"));
        Files.writeString(base.resolve("x.md"), "ours");
        Object baseIs = PinnedRead.identityOf(base);
        assumeTrue(baseIs != null, "this filesystem will not identify a directory");
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("x.md"), "theirs");

        Files.delete(base.resolve("x.md"));
        Files.delete(base);
        Files.createSymbolicLink(base, elsewhere);

        assertThatThrownBy(() -> PinnedRead.of(base, baseIs, Path.of("x.md")))
                .as("the base's NAME was followed to a directory that was never the base")
                .isInstanceOf(PinnedRead.BaseRotated.class);
    }

    @Test
    void movingABaseAndLinkingItBackIsNotARotation(@TempDir Path tmp) throws IOException {
        assumeTrue(symlinksWork(tmp), "this filesystem has no symbolic links");
        Path base = Files.createDirectories(tmp.resolve("base"));
        Files.writeString(base.resolve("x.md"), "ours");
        Object baseIs = PinnedRead.identityOf(base);
        assumeTrue(baseIs != null, "this filesystem will not identify a directory");

        // mv base base2 && ln -s base2 base. A directory keeps its inode across a rename, so
        // the name still leads to the same thing and nothing here should notice. Refusing
        // this would break the operator's ordinary way of moving a bundle -- the same
        // benign case PinnedDirectory records for a memory root.
        Path moved = Files.move(base, tmp.resolve("base2"));
        Files.createSymbolicLink(base, moved);

        try (PinnedRead read = PinnedRead.of(base, baseIs, Path.of("x.md"))) {
            assertThat(read).isNotNull();
            assertThat(read.readString(StandardCharsets.UTF_8)).isEqualTo("ours");
        }
    }

    @Test
    void aPathDeeperThanTheDescentWillHoldIsRefusedBeforeADescriptorIsTaken(@TempDir Path base)
            throws IOException {
        // The bound is on components, and the refusal happens before any open, so this does
        // not need the tree to exist to be answered. canDescend is the same question
        // SkillLoader asks of the catalog, so a name this refuses is a name never advertised.
        Path shallow = Path.of("a/b/c/x.md");
        assertThat(PinnedRead.canDescend(shallow)).isTrue();
        Path deep = Path.of("d/".repeat(200) + "x.md");
        assertThat(PinnedRead.canDescend(deep)).isFalse();
        assertThat(PinnedRead.of(base, null, deep)).isNull();
    }

    @Test
    void decodingReportsMalformedBytesRatherThanReplacingThem(@TempDir Path base)
            throws IOException {
        // new String(bytes, UTF_8) answers U+FFFD U+0028 U+0078 for these three bytes, so a
        // corrupt resource would reach a model as mojibake with nothing saying so. Both
        // callers used Files.readString, which reports; this keeps that.
        Files.write(base.resolve("x.md"), new byte[] {(byte) 0xC3, 0x28, 0x78});

        try (PinnedRead read = PinnedRead.of(base, null, Path.of("x.md"))) {
            assertThatThrownBy(() -> read.readString(StandardCharsets.UTF_8))
                    .isInstanceOf(CharacterCodingException.class);
        }
    }

    @Test
    void nothingStandingAtTheNameIsAbsentRatherThanAnError(@TempDir Path base) throws IOException {
        assumeTrue(Pinning.available(), Pinning.WHY);
        try (PinnedRead read = PinnedRead.of(base, null, Path.of("x.md"))) {
            assertThat(read).isNotNull();
            assertThat(read.standing()).isNull();
        }
        assertThat(PinnedRead.of(base, null, Path.of("nowhere/x.md"))).isNull();
    }

    @Test
    void theIdentityComesFromTheDescentsOwnOpen(@TempDir Path base) throws IOException {
        Files.writeString(base.resolve("x.md"), "ours");
        Object standalone = PinnedRead.identityOf(base);
        assumeTrue(standalone != null, "this filesystem will not identify a directory");

        try (PinnedRead read = PinnedRead.of(base, null, Path.of("x.md"))) {
            // Both spellings of "which inode is this" have to agree about the answer's form
            // as well as its value, or every later comparison would refuse a base that never
            // moved. Asked here because the two are separate code paths.
            assertThat(read.identity()).isEqualTo(standalone);
        }
    }

    @Test
    void aFifoAtADirectoryComponentIsRefusedRatherThanWaitedOnForever(@TempDir Path base)
            throws Exception {
        assumeTrue(Pinning.available(), Pinning.WHY);
        // Bounded, because a regression here does not make this test fail, it makes it HANG:
        // SecureDirectoryStream.newDirectoryStream carries no O_DIRECTORY -- the JDK opens
        // the descriptor and hands it to fdopendir -- so openat on a FIFO with no writer
        // blocks inside the syscall, where no error, no result and no timeout reaches the
        // caller. `step` asks the component's type with fstatat first for exactly that
        // reason. A hung suite in CI reads as an infrastructure problem rather than as this
        // bug, which is the lesson NonDocumentAtAKeyTest records paying for.
        fifoAt(base.resolve("refs"));

        assertThat(bounded(() -> String.valueOf(PinnedRead.of(base, null, Path.of("refs/x.md")))))
                .as("the descent opened a FIFO standing at a directory component and never"
                        + " came back")
                .isEqualTo("null");
    }

    @Test
    void aFifoAtTheNameIsNotAFileSoItIsNeverOpened(@TempDir Path base) throws Exception {
        // The same trap one level down, and the reason standing() is asked before the read:
        // fstatat answers about a FIFO without opening it, so a caller that requires a
        // regular file never reaches the open that would block. Reading it deliberately is
        // still a hang, and that is the caller's to avoid rather than this class's to guess.
        fifoAt(base.resolve("x.md"));

        assertThat(bounded(() -> {
            try (PinnedRead read = PinnedRead.of(base, null, Path.of("x.md"))) {
                return read.standing().isRegularFile() + "/" + read.standing().isOther();
            }
        }))
                .as("asking what stands at the name opened a FIFO and never came back")
                .isEqualTo("false/true");
    }

    /** Long enough that a slow machine is not mistaken for a hang, short enough to be a test. */
    private static final int BOUND_SECONDS = 10;

    /** Runs one call with a hard bound, so a hang is measurable rather than fatal. */
    private static String bounded(Callable<String> call) {
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            // Daemon, because a thread blocked on opening a FIFO is inside a syscall and
            // will not answer an interrupt. The JVM exiting is what reclaims it.
            Thread thread = new Thread(runnable);
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
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Path fifoAt(Path path) throws Exception {
        assumeTrue(java.io.File.separatorChar == '/', "no FIFOs on this platform");
        // The start() is inside the try because it throws when the binary is absent, which
        // is before any assumption could fire -- the mistake NonDocumentAtAKeyTest records.
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

    private static boolean symlinksWork(Path where) {
        try {
            Path probe = where.resolve("probe-link");
            Files.createSymbolicLink(probe, where);
            Files.delete(probe);
            return true;
        } catch (IOException | UnsupportedOperationException no) {
            return false;
        }
    }
}
