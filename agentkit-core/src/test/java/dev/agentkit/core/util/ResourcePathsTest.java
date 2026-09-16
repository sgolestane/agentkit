package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The name a bundled resource is known by, on both platforms.
 *
 * <p>The suite only ever runs on one of them. It used to answer that by passing the
 * platform question in as a boolean to a package-private seam, which reached the branch
 * this machine never takes but reached it around the rewrite alone — the one line least
 * likely to be wrong. The seam is gone (#81): the branch is now reached by giving
 * {@link ResourcePaths} a path from a filesystem whose separator really is a backslash, so
 * what is under test is {@code getPath} then {@code normalize} then the rewrite, which is
 * the composition that carried #78.
 */
class ResourcePathsTest {

    @Test
    @DisplayName("where a backslash separates, it is rewritten")
    void windowsSeparatorsBecomeSlashes() throws IOException {
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            assertThat(ResourcePaths.display(windows.getPath("refs\\steps.md")))
                    .isEqualTo("refs/steps.md");
            // Windows takes '/' on input too, so both spellings are one path and both
            // render the same. This is the assertion the boolean seam could only assert
            // about a string.
            assertThat(ResourcePaths.display(windows.getPath("refs/steps.md")))
                    .isEqualTo("refs/steps.md");
        }
    }

    @Test
    @DisplayName("where a backslash is a filename character, it is left alone")
    void posixFilenamesKeepTheirBackslashes() {
        // The bug: this rewrite ran everywhere, so 'a\b.md' was advertised as 'a/b.md' —
        // a name no read could resolve, and one that collided with a real nested file.
        // Asserted on the default filesystem because that is where the suite runs; guarded
        // so it does not become a false claim if it is ever run on Windows.
        assumeTrue(File.separatorChar == '/', "a backslash is a separator here");
        assertThat(ResourcePaths.display(FileSystems.getDefault().getPath("a\\b.md")))
                .isEqualTo("a\\b.md");
        assertThat(ResourcePaths.display(FileSystems.getDefault().getPath("refs/steps.md")))
                .isEqualTo("refs/steps.md");
    }

    @Test
    @DisplayName("canonical folds against the bundle's filesystem, not the host's")
    void canonicalOnWindowsFoldsBackslashSeparators() throws IOException {
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            // The two lines issue #81 says cannot be written on this platform. They cannot
            // be written against canonical(String) — Path.of is the host — and they can be
            // written against the overload that is handed the bundle's filesystem.
            assertThat(ResourcePaths.canonical(windows, "refs\\steps.md"))
                    .isEqualTo("refs/steps.md");
            assertThat(ResourcePaths.canonical(windows, "refs/steps.md"))
                    .isEqualTo("refs/steps.md");
            // And the fold that needs the separator to be understood as a separator: on
            // this host 'refs\x\..\steps.md' is a single filename and normalize() has
            // nothing to fold, which is precisely the difference no boolean can simulate.
            assertThat(ResourcePaths.canonical(windows, "refs\\x\\..\\steps.md"))
                    .isEqualTo("refs/steps.md");
            assertThat(ResourcePaths.canonical(windows, ".\\refs\\steps.md"))
                    .isEqualTo("refs/steps.md");
        }
    }

    @Test
    @DisplayName("canonical on the default filesystem keeps a backslash in a filename")
    void canonicalOnPosixKeepsABackslashInAName() {
        assumeTrue(File.separatorChar == '/', "a backslash is a separator here");
        // The other half of the same rule, and the direction #78 broke: on POSIX this is
        // one file named 'refs\x\..\steps.md' and folding it would name a different one.
        assertThat(ResourcePaths.canonical("refs\\x\\..\\steps.md"))
                .isEqualTo("refs\\x\\..\\steps.md");
        assertThat(ResourcePaths.canonical(FileSystems.getDefault(), "refs\\steps.md"))
                .isEqualTo("refs\\steps.md");
    }

    @Test
    @DisplayName("pathIn reads a rendered name back on the filesystem it was rendered for")
    void pathInIsTheInverseOfDisplay() throws IOException {
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Path original = windows.getPath("refs\\steps.md");
            String rendered = ResourcePaths.display(original);

            Path readBack = ResourcePaths.pathIn(windows, rendered);

            assertThat(readBack).isEqualTo(original);
            assertThat(readBack.getNameCount()).isEqualTo(2);
            // The point of the method: Path.of would answer with this host's syntax about
            // a name rendered for another filesystem. Same string, different provider, and
            // the difference is not cosmetic — a foreign path is refused, not misread.
            assertThat(readBack.getFileSystem()).isSameAs(windows);
        }
    }

    @Test
    void canonicalFoldsRedundantSegments() {
        assertThat(ResourcePaths.canonical("./refs/steps.md")).isEqualTo("refs/steps.md");
        assertThat(ResourcePaths.canonical("refs/./steps.md")).isEqualTo("refs/steps.md");
        assertThat(ResourcePaths.canonical("refs/x/../steps.md")).isEqualTo("refs/steps.md");
        assertThat(ResourcePaths.canonical("refs//steps.md")).isEqualTo("refs/steps.md");
    }

    @Test
    void canonicalKeepsAnUnparseableNameRatherThanThrowing() {
        // NUL is the whole of it: on POSIX every other byte is legal in a filename, so it
        // is the only input that reaches the InvalidPathException branch. Written as an
        // escape rather than embedded — a literal NUL makes this file a binary blob to git,
        // unreviewable in a diff, and the assertion still passes after a tool strips it.
        assertThat(ResourcePaths.canonical("a\0b.md")).isEqualTo("a\0b.md");
    }

    @Test
    void relativeToNamesAFileByWhereItIsRatherThanHowItWasAsked(@TempDir Path base)
            throws IOException {
        Files.createDirectories(base.resolve("refs"));
        Files.writeString(base.resolve("refs/steps.md"), "x");

        assertThat(ResourcePaths.relativeTo(base, base.resolve("refs/steps.md")))
                .isEqualTo("refs/steps.md");
        assertThat(ResourcePaths.relativeTo(base, base.getParent())).isNull();
    }

    @Test
    void relativeToFallsBackToTheLexicalBaseWhenTheDirectoryIsGone() {
        // The branch a real bundle never takes, and therefore the one no filesystem test
        // reaches: toRealPath throws and the lexical form has to stand. It was unreached by
        // the whole suite, so reinstating the unconditional rewrite *here* stayed green.
        Path gone = Path.of("/nonexistent-bundle-9f3a").toAbsolutePath();

        assertThat(ResourcePaths.relativeTo(gone, gone.resolve("a\\b.md"))).isEqualTo("a\\b.md");
        assertThat(ResourcePaths.relativeTo(gone, gone.resolve("refs/steps.md")))
                .isEqualTo("refs/steps.md");
        assertThat(ResourcePaths.relativeTo(gone, Path.of("/elsewhere/x.md"))).isNull();
    }

    @Test
    void displayRendersARelativePathForThisPlatform() {
        String rendered = ResourcePaths.display(Path.of("refs", "steps.md"));
        assertThat(rendered).isEqualTo("refs/steps.md");
        // And round-trips: the name handed to a model is one Path.of can read back.
        assertThat(Path.of(rendered)).isEqualTo(Path.of("refs", "steps.md"));
        if (File.separatorChar == '/') {
            assertThat(ResourcePaths.display(Path.of("a\\b.md"))).isEqualTo("a\\b.md");
        }
    }
}
