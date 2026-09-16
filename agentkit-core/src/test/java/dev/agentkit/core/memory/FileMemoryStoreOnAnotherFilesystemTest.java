package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link FileMemoryStore} against a root that is not on the default filesystem (#254).
 *
 * <p>The constructor takes a {@link Path}, and a {@link Path} knows which provider it came
 * from, so a root on a zip filesystem or an in-memory provider is something the type
 * already promises to accept. It did not: every {@code write} built its temporary name with
 * {@code Path.of}, which is always the <em>default</em> filesystem, and
 * {@link SecureDirectoryStream#newByteChannel} rejects a path from another provider rather
 * than misreading it. On a Jimfs {@code Configuration.unix()} root, before the repair:
 *
 * <pre>
 * java.nio.file.ProviderMismatchException:
 *     path %.write-da4792fb7d6c6a14.tmp is not associated with a Jimfs file system
 * </pre>
 *
 * <p>Same defect and same repair as #81's four sites in the skill loader, and the same
 * promise {@code ResourcePaths.display} already keeps in the other direction: ask the
 * path's own {@code FileSystem}, so a path from a zip or an in-memory provider is handled
 * by the rules it was built under rather than by the host's.
 *
 * <h2>Which branch this runs, said rather than inferred</h2>
 *
 * <p>{@link PinnedDirectory} has two: the {@code openat} descent, taken when
 * {@code Files.newDirectoryStream} hands back a {@link SecureDirectoryStream}, and a
 * path-based fallback for platforms where it does not. They are different code, and a test
 * that silently exercised only the fallback would say nothing about the line #254 names,
 * which is in the pinned branch.
 *
 * <p>#254 expected the fallback, on the strength of a note from #253 that Jimfs "gates
 * {@code SecureDirectoryStream} behind a feature its shipped configurations do not enable".
 * <strong>That is not what jimfs 1.3.0 does, and the correction is the reason this file can
 * exist at all.</strong> Measured by opening a directory on each shipped configuration and
 * asking what came back:
 *
 * <pre>
 * Configuration.unix()     JimfsSecureDirectoryStream    SecureDirectoryStream: yes
 * Configuration.osX()      DowngradedDirectoryStream     SecureDirectoryStream: no
 * Configuration.windows()  DowngradedDirectoryStream     SecureDirectoryStream: no
 * </pre>
 *
 * <p>So {@code unix()} is the configuration that reaches the defect, and
 * {@link #theDescentOverThisRootIsThePinnedOneAndNotTheFallback} asserts it rather than
 * assuming it — asserted, because a Jimfs release that quietly stopped being secure would
 * otherwise leave every clause here passing over the branch it was not written for. Not an
 * {@code assumeTrue} either: assuming on the property under test is how a test comes to
 * skip under the very mutant it exists to catch (#225).
 *
 * <h2>What this does not make true, which is the residual</h2>
 *
 * <p>A key can be written <em>once</em> here and no more.
 * {@link #aSecondWriteToAKeyNeedsAMediumWhoseMoveReplaces} pins it. On the pinned branch —
 * the one this class runs, and the one a Linux deployment runs — both writing operations
 * finish with {@link SecureDirectoryStream#move} of a staged temporary onto the key, and
 * that method's contract makes replacing an existing target an <em>optional</em> behaviour
 * — "{@code FileAlreadyExistsException} if a file already exists in the target directory
 * and cannot be replaced (optional specific exception)". The platform's {@code renameat}
 * replaces, which is why nothing on the default filesystem ever noticed; jimfs 1.3.0
 * refuses outright. That is a dependency on the medium rather than a use of the wrong
 * filesystem, so it is not #254 and is not repaired here — replacing the {@code move} with
 * an unlink-then-rename costs far more than a second provider is worth.
 *
 * <p><strong>The clause naming that cost used to be wrong, and the correction is worth
 * more than the sentence was</strong> (#273). It read: "replacing the {@code move} with an
 * unlink-then-rename would reopen the check-to-open window #145 and #168 closed". It would
 * not. Neither step opens the key — {@code deleteFile} and {@code move} both leave the
 * target unopened, which is the whole reason a staged rename was chosen in #145 — and both
 * are already relative to the descriptor {@link PinnedDirectory} holds, so #168's
 * path-re-walk is not reopened either. Those two windows stay shut.
 *
 * <p>What a two-step replace opens is a different thing of the same family: an interval in
 * which the key holds nothing at all. A concurrent {@code read} answers empty and
 * {@code list} stays silent for a memory nobody deleted, and this store's own
 * {@code append} is a read-modify-write, so it can read that emptiness and write back only
 * the text being appended. That is the cost, it is enough on its own, and it is why the
 * requirement is stated in {@link MemoryStore}'s contract rather than repaired. (A racer
 * planting a name in the gap is a third thing, and it is left out of the argument
 * deliberately: anyone who can create names under the root is already outside what this
 * store defends against, as {@link FileMemoryStore}'s class javadoc says at length.)
 *
 * <p>Measured rather than assumed, by running {@code MediumBackedMemoryStoreContract}
 * against this root: 24 of its 26 clauses passed, and the two that did not —
 * {@code appendConcatenates} and {@code aDocumentTheStoreDidNotWriteIsStillAMemory} — are
 * exactly the two that write a key twice, both arriving as
 * {@code FileAlreadyExistsException} out of {@code JimfsSecureDirectoryStream.move}. That
 * suite is not committed, because a contract runner carrying two exemptions teaches an
 * implementor the wrong contract; the measurement it produced is here instead, and it is
 * the answer to #254's open question about whether the rest of the class shared the
 * assumption. It did not.
 *
 * <p><strong>Where the requirement is stated now.</strong> This paragraph was the only
 * place it was written down, and a test class is not where an implementor looks for what a
 * store demands of a medium (#273). {@link MemoryStore}'s SPI section carries it as a
 * consequence — the medium must be able to put a document at a key that already holds one
 * in a single step — and its list of refusal reasons names the shape this takes when the
 * medium will not. {@link MediumBackedMemoryStoreContract} carries the decision to stay
 * strict rather than exempt anything, together with the re-measurement of the numbers
 * above; {@link FileMemoryStore} carries why it is not detected when the store is built.
 * What stays here is what this class is for: the residual, running, over the one non-default
 * provider available.
 */
class FileMemoryStoreOnAnotherFilesystemTest {

    private FileSystem elsewhere;
    private Path root;

    @BeforeEach
    void openAnInMemoryFilesystem() throws IOException {
        elsewhere = Jimfs.newFileSystem(Configuration.unix());
        root = Files.createDirectories(elsewhere.getPath("/mem"));
    }

    @AfterEach
    void closeIt() throws IOException {
        elsewhere.close();
    }

    @Test
    void theDescentOverThisRootIsThePinnedOneAndNotTheFallback() throws IOException {
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(root)) {
            assertThat(opened)
                    .as("this configuration no longer offers openat, so every other clause "
                            + "in this class is exercising PinnedDirectory's path-based "
                            + "fallback and says nothing about the pinned branch #254 is in")
                    .isInstanceOf(SecureDirectoryStream.class);
        }
    }

    @Test
    void aStoreWritesAndReadsBackOnARootFromAnotherProvider() {
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.write("notes/a.md", "hello");

        assertThat(store.read("notes/a.md"))
                .as("the temporary a write stages through was named by the default "
                        + "filesystem rather than by the root's, so the open refused it")
                .contains("hello");
    }

    @Test
    void theDocumentLandsAtTheNameTheStoreWouldListItUnder() {
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.write("notes/a.md", "hello");

        // Separate from the round trip above, which read() alone could satisfy while the
        // bytes sat under a temporary name: a memory nothing can list is one no agent finds
        // again.
        assertThat(store.list(""))
                .as("the staged temporary was never renamed onto the key")
                .containsExactly("notes/a.md");
        assertThat(store.exists("notes/a.md")).isTrue();
        assertThat(store.delete("notes/a.md"))
                .as("delete reaches the key through the same descent")
                .isTrue();
        assertThat(store.list("")).isEmpty();
    }

    @Test
    void appendReachesAKeyThatHoldsNothingYet() {
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.append("log.txt", "one\n");

        assertThat(store.read("log.txt"))
                .as("append stages through the same temporary as write, so it was refused "
                        + "the same way")
                .contains("one\n");
    }

    /**
     * The owner-only mode is asked of the medium the document lands on (#254).
     *
     * <h4>Why this one is asked through a seam when nothing else here is</h4>
     *
     * <p>Because no behavioural probe can reach it. The old code decided the mode once from
     * {@code FileSystems.getDefault()}, so on a POSIX host the {@code rw-------} attribute
     * went to a {@code newByteChannel} on whatever filesystem the root was on — and setting
     * an attribute a provider does not have is {@link UnsupportedOperationException}, which
     * is neither of the two channels {@code MemoryStore} names. But jimfs 1.3.0's
     * {@code JimfsSecureDirectoryStream.newByteChannel} <em>discards</em> its
     * {@code FileAttribute} argument rather than honouring or refusing it, so on the one
     * non-default provider available here the old code and the new one behave identically.
     * Measured, on the same filesystem, so that "discards" is not a guess:
     *
     * <pre>
     * jimfs Configuration.unix() supportedFileAttributeViews = [basic]
     * secure newByteChannel with posix:permissions           = accepted (dropped)
     * Files.createFile     with posix:permissions            = UnsupportedOperationException
     * </pre>
     *
     * <p>{@code ResourcePaths.display}'s javadoc argues against exactly this kind of seam,
     * and the argument it makes is the one that decides this case rather than one that
     * forbids it: a seam is wrong <em>"once the real path was covered"</em>, because it is
     * then a second runner of a rule that already has one. The real path is not covered
     * here and cannot be, so the choice is a seam or no runner at all — and a rule with no
     * runner is how a line comes to be reverted by a later change with every test green.
     * The clause below is the one it is worth having: both directions, so that "ask the
     * medium" is not satisfied by a method which never asks anyone.
     */
    @Test
    void theModeIsDecidedByTheMediumTheDocumentLandsOnAndNotByTheHost() {
        assertThat(elsewhere.supportedFileAttributeViews())
                .as("this configuration now has POSIX modes, so the clause below no longer "
                        + "exercises a medium that has none")
                .doesNotContain("posix");

        assertThat(FileMemoryStore.ownerOnlyOn(elsewhere))
                .as("a medium with no POSIX view is still being handed a POSIX mode, which "
                        + "a provider that does not silently drop it refuses outright")
                .isEmpty();

        // The other direction needs a medium that HAS modes, and the host is the only one
        // to hand. Assumed rather than asserted, and about the platform rather than about
        // the line under test -- no mutant in ownerOnlyOn can make this skip, which is the
        // distinction #225 was about.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                        .contains("posix"),
                "this host has no POSIX modes, so there is no medium here that wants one");
        assertThat(FileMemoryStore.ownerOnlyOn(java.nio.file.FileSystems.getDefault()))
                .as("the host filesystem has POSIX modes and is no longer given one, so "
                        + "every memory document is created at 0666 & ~umask")
                .isNotEmpty();
    }

    @Test
    void aMediumWithNoModesIsWrittenToAllTheSame() {
        // The behavioural half of the clause above, kept because it is the one that would
        // notice a provider which refuses the attribute rather than dropping it.
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.write("a.md", "hello");

        assertThat(store.read("a.md")).contains("hello");
    }

    @Test
    void aSecondWriteToAKeyNeedsAMediumWhoseMoveReplaces() {
        // The residual the class javadoc names, pinned so that it is a known limit rather
        // than a surprise -- and so that a provider or a Jimfs release which starts
        // replacing makes this fail loudly instead of leaving a stale paragraph behind.
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("a.md", "first");

        assertThatThrownBy(() -> store.write("a.md", "second"))
                .as("this medium's SecureDirectoryStream.move now replaces an existing "
                        + "target, so the residual in this class's javadoc is stale")
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(store.read("a.md"))
                .as("a refused overwrite must leave what was there, whatever the medium")
                .contains("first");
    }

    /**
     * The other branch of the same provider replaces, because it can ask (#273).
     *
     * <h4>Why this is worth a clause of its own</h4>
     *
     * <p>It is the asymmetry {@link MediumBackedMemoryStoreContract}'s refusal to grow an
     * exemption rests on: the requirement is not a property of the medium alone, it is a
     * property of the medium and the branch it steers the store into. {@code osX()} hands
     * back a plain directory stream, so {@code PinnedDirectory} takes the path-based
     * writer, whose {@code Files.move} names {@code REPLACE_EXISTING} outright — and the
     * same jimfs that refuses the clause above takes this one. Measured the same way: the
     * whole of {@code MediumBackedMemoryStoreContract} passes here, 26 of 26, against
     * {@code unix()}'s 24.
     *
     * <p>It is also the only runner that branch has, and that is measured rather than
     * assumed. Every other test in this repository runs on a medium that offers
     * {@code openat}, so {@code REPLACE_EXISTING} could be deleted from
     * {@link FileMemoryStore}'s path-based writer with the whole suite still green.
     * Verified by deleting it: 2,199 tests across every module, one failure, and it is
     * this clause.
     */
    @Test
    void theFallbackBranchReplacesBecauseItCanAskTo() throws IOException {
        try (FileSystem downgraded = Jimfs.newFileSystem(Configuration.osX())) {
            Path other = Files.createDirectories(downgraded.getPath("/mem"));

            // Asserted rather than assumed, for the reason the clause about the pinned
            // branch gives: a jimfs release that made this configuration secure would
            // otherwise leave this passing over the branch it was not written for -- and
            // it would be passing for the opposite reason to the one claimed.
            try (DirectoryStream<Path> opened = Files.newDirectoryStream(other)) {
                assertThat(opened)
                        .as("this configuration now offers openat, so this clause is "
                                + "exercising the pinned branch and the asymmetry it "
                                + "exists to pin is not being tested at all")
                        .isNotInstanceOf(SecureDirectoryStream.class);
            }

            MemoryStore store = new FileMemoryStore(other, Containment.BEST_EFFORT);
            store.write("a.md", "first");
            store.write("a.md", "second");

            assertThat(store.read("a.md"))
                    .as("the path-based writer asks for REPLACE_EXISTING by name, so a "
                            + "medium that honours it replaces -- the same provider that "
                            + "refuses the second write through SecureDirectoryStream.move")
                    .contains("second");
        }
    }
}
