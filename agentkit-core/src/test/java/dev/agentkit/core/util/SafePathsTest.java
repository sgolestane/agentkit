package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathsTest {

    @TempDir
    Path tmp;

    private Path base;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        // A real tree, since a lexical-only check cannot exercise any of this.
        base = Files.createDirectories(tmp.resolve("base"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secrets.txt"), "top secret");
    }

    /** Symlink support is filesystem- and privilege-dependent; skip rather than fail. */
    private Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlinks unavailable here: " + e);
            throw new AssertionError("unreachable");
        }
    }

    @Test
    void resolvesRelativePathWithinBase() {
        assertThat(SafePaths.resolveWithin(base, "forms/w2.txt"))
                .isEqualTo(base.toAbsolutePath().normalize().resolve("forms/w2.txt"));
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "../secrets.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSneakyTraversal() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "forms/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsoluteEscape() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a symlink to a file outside the base is refused")
    void rejectsSymlinkedFile() {
        symlink(base.resolve("leak.txt"), outside.resolve("secrets.txt"));

        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "leak.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    @DisplayName("a path through a symlinked directory is refused")
    void rejectsPathThroughSymlinkedDirectory() {
        symlink(base.resolve("docs"), outside);

        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "docs/secrets.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
        // Also when the file beyond the link does not exist: the link itself is the
        // deepest existing component, and it already leaves the base.
        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "docs/absent.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a dangling symlink is judged by where it aimed, not by being broken")
    void judgesADanglingSymlinkByItsTarget() {
        symlink(base.resolve("out.txt"), outside.resolve("never-existed.txt"));
        symlink(base.resolve("in.txt"), base.resolve("also-never-existed.txt"));

        // Aimed outside: refused, and for that reason rather than for being broken.
        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "out.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
        // Aimed inside: a bundle that symlinks a sibling left out of the archive is a
        // broken resource, not an escape, and refusing it would be a false positive.
        assertThat(SafePaths.resolveWithin(base, "in.txt"))
                .isEqualTo(base.resolve("also-never-existed.txt"));
    }

    @Test
    @DisplayName("a sibling whose name merely starts with the base name is outside")
    void rejectsASiblingThatStringPrefixesTheBase() throws IOException {
        // Containment must be component-wise. A string-prefix comparison passes every
        // other test in this class, because no other case has a sibling like this.
        Path lookalike = Files.createDirectories(tmp.resolve("baseEVIL"));
        Files.writeString(lookalike.resolve("secrets.txt"), "top secret");
        symlink(base.resolve("near.txt"), lookalike.resolve("secrets.txt"));

        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "near.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    @DisplayName("a symlink pointing back at the base is refused")
    void rejectsASymlinkToTheBaseItself() {
        // The lexical rule already rejects the base as a key. Without the same rule on
        // the resolved path, "self/self/.../x" would alias every file arbitrarily many
        // ways, breaking any dedup or listing keyed on the path.
        symlink(base.resolve("self"), base);

        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "self"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a symlink that stays inside the base is followed, not merely permitted")
    void allowsSymlinkWithinBase() throws IOException {
        Path inner = Files.createDirectories(base.resolve("real"));
        Files.writeString(inner.resolve("note.txt"), "fine");
        symlink(base.resolve("alias"), inner);

        Path resolved = SafePaths.resolveWithin(base, "alias/note.txt");

        // The returned path is the link-free one. Asserting the lexical path instead
        // would pass against an implementation that does no symlink handling at all.
        assertThat(resolved).isEqualTo(inner.resolve("note.txt"));
        assertThat(Files.readString(resolved)).isEqualTo("fine");
    }

    @Test
    @DisplayName("a base that is itself a symlink is resolved on both sides")
    void allowsASymlinkedBase() throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real-root"));
        Files.writeString(real.resolve("note.txt"), "fine");
        Path linkedBase = symlink(tmp.resolve("linked-root"), real);

        // A memory root pointing into a mounted volume is the ordinary case, and
        // comparing a real target against a lexical base would reject all of them.
        Path resolved = SafePaths.resolveWithin(linkedBase, "note.txt");

        assertThat(resolved).isEqualTo(real.resolve("note.txt"));
        assertThat(Files.readString(resolved)).isEqualTo("fine");
    }

    @Test
    @DisplayName("a path that does not exist yet is allowed, so writes still work")
    void allowsAPathThatDoesNotExistYet() {
        assertThat(SafePaths.resolveWithin(base, "not/created/yet.txt"))
                .isEqualTo(base.resolve("not/created/yet.txt"));
    }

    @Test
    @DisplayName("a missing base is allowed, since nothing can be planted under it")
    void allowsAMissingBase() {
        // Refusing here would buy nothing — there is no tree to hide a link in — and
        // would cost two things: MemoryStore.read/exists/delete would stop being total
        // when a root is removed after construction, and SkillTools and MemoryTools
        // both turn IllegalArgumentException into a tool error, so an operator's
        // misconfiguration would reach the model as a complaint about its own argument.
        Path missing = tmp.resolve("no-such-dir");

        assertThat(SafePaths.resolveWithin(missing, "a.txt")).isEqualTo(missing.resolve("a.txt"));
    }

    @Test
    @DisplayName("the escape messages carry no host paths, so they are safe to hand a model")
    void messagesDoNotLeakHostLayout() {
        symlink(base.resolve("leak.txt"), outside.resolve("secrets.txt"));
        String baseName = base.toString();

        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "leak.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(baseName));
        assertThatThrownBy(() -> SafePaths.resolveWithin(base, "../secrets.txt"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(baseName));
    }

    @Test
    @DisplayName("a hard link out of the base is NOT detected — the documented residual")
    void doesNotDetectHardLinks() throws IOException {
        // Pinned so the limitation is a decision rather than a surprise: a hard link has
        // no separate real path, so toRealPath() cannot tell it from a normal file. If
        // this ever starts failing, the docs and the README sharp edge need updating.
        try {
            Files.createLink(base.resolve("hard.txt"), outside.resolve("secrets.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("hard links unavailable here: " + e);
        }

        assertThat(SafePaths.resolveWithin(base, "hard.txt")).isEqualTo(base.resolve("hard.txt"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a link that folds back onto its own name aims where the kernel says")
    void aLexicalFoldCannotFakeContainment(@TempDir Path tmp) throws IOException {
        // The fold is the whole defect. This used to finish with normalize(), which deletes
        // 'X/..' as a pair — sound only when X is a real directory. The kernel resolves X
        // first, so for a target of 'sub/../name' where sub is a link, the two answers are
        // different directories, and this class returned the one that passed containment
        // while the open went to the other.
        //
        // Aimed outward, the returned path was the caller's own lexical path, so every
        // check downstream saw "no link here": FileMemoryStore accepted the write and the
        // content landed outside the root.
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("sub"), outside);
        Files.createSymbolicLink(root.resolve("pwned.md"), Path.of("sub/../pwned.md"));

        assertThatThrownBy(() -> SafePaths.resolveWithin(root, "pwned.md"))
                .isInstanceOf(IllegalArgumentException.class);
        // Asserted on the filesystem as well as on the return value: a path that merely
        // *looks* contained is what this was.
        assertThat(Files.exists(outside.resolve("pwned.md"))).isFalse();
        assertThat(Files.exists(tmp.resolve("pwned.md"))).isFalse();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("and aimed inward, it names the key it really reaches")
    void aLexicalFoldPointedInwardIsResolvedNotHidden(@TempDir Path tmp) throws IOException {
        // The same trick aimed at another key inside the base needs no escape: it decides
        // which name a caller's write lands under. Containment is not the property that
        // catches this — the answer being the kernel's is.
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("y/inner"));
        Files.createDirectories(root.resolve("x"));
        Files.createSymbolicLink(root.resolve("x/sub"), root.resolve("y/inner"));
        Files.createSymbolicLink(root.resolve("x/note.md"), Path.of("../x/sub/../note.md"));

        Path resolved = SafePaths.resolveWithin(root, "x/note.md");

        assertThat(root.relativize(resolved)).hasToString("y/note.md");
        assertThat(resolved)
                .as("the fold hid which key the write would land under")
                .isNotEqualTo(root.resolve("x/note.md"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a chain of dangling links is followed, and a cycle in one is refused")
    void aChainOfDanglingLinksTerminates(@TempDir Path tmp) throws IOException {
        // Resolving the aimed path through the same walk means a dangling link may aim at
        // another dangling link, so the recursion needs a bound — and two links aiming at
        // each other need it to be a bound rather than a cycle check that happens to work.
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createSymbolicLink(root.resolve("gone.md"), root.resolve("missing.md"));
        Files.createSymbolicLink(root.resolve("chain.md"), root.resolve("gone.md"));
        Files.createSymbolicLink(root.resolve("self.md"), Path.of("self.md"));
        Files.createSymbolicLink(root.resolve("a.md"), Path.of("b.md"));
        Files.createSymbolicLink(root.resolve("b.md"), Path.of("a.md"));

        // A broken resource is still a resource: it resolves to where it aimed, and the
        // caller's own open is what fails.
        assertThat(root.relativize(SafePaths.resolveWithin(root, "gone.md")))
                .hasToString("missing.md");
        assertThat(root.relativize(SafePaths.resolveWithin(root, "chain.md")))
                .hasToString("missing.md");
        // A cycle has nowhere to aim, so it is refused rather than walked.
        assertThatThrownBy(() -> SafePaths.resolveWithin(root, "self.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.resolveWithin(root, "a.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
