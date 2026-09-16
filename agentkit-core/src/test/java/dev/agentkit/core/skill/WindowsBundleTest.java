package dev.agentkit.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.PinnedRead;
import dev.agentkit.core.util.ResourcePaths;
import dev.agentkit.core.util.WindowsFilesystem;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The skill bundle round trip — listing, {@code procedures:} declaration, read — on a
 * filesystem that is not the default one and does not spell paths the way it does (#81).
 *
 * <p>Three sites have twice been found disagreeing about the same round trip (#78, and the
 * symlink alias caught reviewing #79) and every test that watched them ran on POSIX. What
 * this adds is not "another platform for completeness": a bundle need not live on the
 * default filesystem at all, and until this existed none of the three had ever been asked
 * a question whose answer differs.
 *
 * <p><strong>Which branch of {@link PinnedRead} each test exercises is stated, not
 * assumed.</strong> {@link WindowsFilesystem#withoutOpenat()} gives no
 * {@link java.nio.file.SecureDirectoryStream}, which is what a real Windows JDK gives too,
 * so the read there is the lexical fallback — worth having, and not the pinned descent.
 * {@link WindowsFilesystem#withOpenat()} is the one that walks components.
 */
class WindowsBundleTest {

    private static Path bundle(FileSystem fs, String frontmatter) throws IOException {
        Path base = fs.getPath("C:\\skills\\reporter");
        Files.createDirectories(base.resolve("refs"));
        Files.writeString(base.resolve("SKILL.md"), "---\nname: reporter\n"
                + "description: Writes reports\n" + frontmatter + "---\nFollow refs/steps.md.");
        Files.writeString(base.resolve("refs\\steps.md"), "1. Gather. 2. Draft.");
        Files.writeString(base.resolve("template.md"), "# Report template");
        return base;
    }

    private static String read(SkillLibrary library, String path) {
        Tool tool = SkillTools.readSkillResourceTool(library);
        ToolResult result = tool.execute(new ToolInvocation("1", "read_skill_resource",
                Map.of("skill", "reporter", "path", path)));
        return result.content();
    }

    @Test
    @DisplayName("a bundle that is not on the default filesystem loads at all")
    void loadsABundleFromANonDefaultFilesystem() throws IOException {
        // The defect this closes, and it was not a spelling near-miss: SkillLoader read
        // SKILL.md through PinnedRead with a relative path built by Path.of, which is
        // always the default provider. base.resolve(foreign) throws
        // ProviderMismatchException, so every load from a zip, an in-memory provider or
        // anything else a caller had opened failed outright. Measured before the fix:
        // java.nio.file.ProviderMismatchException: SKILL.md, at PinnedRead.of.
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Path base = bundle(windows, "");

            Skill skill = SkillLoader.loadSkill(base);

            assertThat(skill.name()).isEqualTo("reporter");
            assertThat(skill.instructions()).isEqualTo("Follow refs/steps.md.");
            // Listed under the wire spelling, not the bundle's: this is what a model is
            // shown and what it will type back.
            assertThat(skill.resourceFiles())
                    .containsExactly("refs/steps.md", "template.md");
        }
    }

    @Test
    @DisplayName("a procedure declared with the bundle's own separator is the file it names")
    void aBackslashDeclarationNamesTheSameFileAsASlashOne() throws IOException {
        // An author on Windows writes 'procedures: refs\steps.md' because that is what
        // their shell and their editor show them. Canonicalised through Path.of on a POSIX
        // host that is one filename with a backslash in it, matches nothing in the listing,
        // and Skill's constructor rejects the whole bundle — the skill vanishes with a
        // warning telling the operator the author declared a file that is not bundled, when
        // it plainly is. Asked of the bundle's own filesystem it is two components and the
        // file it names.
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Path base = bundle(windows, "procedures: refs\\steps.md\n");

            Skill skill = SkillLoader.loadSkill(base);

            assertThat(skill.procedureResources()).containsExactly("refs/steps.md");
            assertThat(skill.isProcedureResource("refs/steps.md"))
                    .as("the wire spelling of the declared file is a procedure").isTrue();
            assertThat(skill.isProcedureResource("refs\\steps.md"))
                    .as("the bundle's own spelling names the same file, so it is the same"
                            + " procedure; folded through Path.of it would be one filename"
                            + " with a backslash in it and would match nothing")
                    .isTrue();
            assertThat(skill.isProcedureResource("template.md"))
                    .as("an undeclared file is not promoted by the fold").isFalse();
        }
    }

    @Test
    @DisplayName("listing, declaration and read agree on one name")
    void theRoundTripAgreesOnOneName() throws IOException {
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Path base = bundle(windows, "procedures: refs/steps.md\n");
            SkillLibrary library = new SkillLibrary(List.of(SkillLoader.loadSkill(base)));

            // Every spelling the model can reach the declared file by has to arrive fenced
            // as a procedure, including the bundle's own separator — which on this host is
            // a filename character and on that filesystem is not.
            for (String spelling : List.of("refs/steps.md", "refs\\steps.md",
                    "./refs/steps.md", ".\\refs\\steps.md", "refs\\x\\..\\steps.md",
                    "refs//steps.md")) {
                assertThat(read(library, spelling)).as("read as '%s'", spelling)
                        .contains("1. Gather.").contains("kind=\"procedure\"");
            }
            // And the undeclared one is still evidence, so the fence is deciding rather
            // than defaulting to whatever the last assertion wanted.
            assertThat(read(library, "template.md"))
                    .contains("# Report template").contains("kind=\"evidence\"");
        }
    }

    @Test
    @DisplayName("the read is the lexical fallback where the filesystem has no openat")
    void withoutOpenatTheReadIsTheFallbackAndSaysSo() throws IOException {
        // Named rather than left to be inferred. Jimfs gates SecureDirectoryStream behind a
        // feature its Windows configuration does not enable, which matches what a real
        // Windows JDK gives, so the two tests above are exercising PinnedRead's fallback
        // branch and not its pinned descent. A Windows-filesystem test that quietly only
        // ran the fallback would still be worth having; one that claimed otherwise would
        // not be.
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Path base = bundle(windows, "");

            try (PinnedRead read = PinnedRead.of(base, null,
                    windows.getPath("refs\\steps.md"))) {
                assertThat(read).as("the resource is reachable at all").isNotNull();
                assertThat(read.isPinned())
                        .as("this filesystem has no SecureDirectoryStream, so the read is"
                                + " PinnedRead's lexical fallback and this test must not be"
                                + " described as covering the pinned descent")
                        .isFalse();
                assertThat(read.readString(java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo("1. Gather. 2. Draft.");
            }
        }
    }

    @Test
    @DisplayName("the pinned descent walks backslash-separated components")
    void withOpenatTheDescentWalksTheComponents() throws IOException {
        // The other branch, on the same spelling. Not a platform anyone ships — no real
        // Windows JDK offers openat here — but the only way to ask whether the descent
        // splits a Windows-syntax path into the components it walks. Every containment
        // property of the descent is tested on the real filesystem by PinnedReadTest; what
        // is new is the separator.
        try (FileSystem windows = WindowsFilesystem.withOpenat()) {
            Path base = bundle(windows, "procedures: refs\\steps.md\n");

            try (PinnedRead read = PinnedRead.of(base, null,
                    windows.getPath("refs\\steps.md"))) {
                assertThat(read).as("the descent found the file").isNotNull();
                assertThat(read.isPinned())
                        .as("openat is enabled here, so this is the pinned descent and not"
                                + " the fallback the previous test exercises")
                        .isTrue();
                assertThat(read.standing().isRegularFile())
                        .as("fstatat through the pinned parent, on a backslash-spelled name")
                        .isTrue();
                assertThat(read.readString(java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo("1. Gather. 2. Draft.");
            }
            SkillLibrary library = new SkillLibrary(List.of(SkillLoader.loadSkill(base)));
            assertThat(read(library, "refs\\steps.md"))
                    .contains("1. Gather.").contains("kind=\"procedure\"");
        }
    }

    @Test
    @DisplayName("a directory component reached through a link is refused, spelled either way")
    void aLinkedDirectoryComponentIsRefused() throws IOException {
        try (FileSystem windows = WindowsFilesystem.withOpenat()) {
            Path base = bundle(windows, "");
            Files.createDirectories(windows.getPath("C:\\outside"));
            Files.writeString(windows.getPath("C:\\outside\\secret.md"), "secret");
            Files.createSymbolicLink(base.resolve("linked"), windows.getPath("C:\\outside"));

            for (String spelling : List.of("linked\\secret.md", "linked/secret.md")) {
                try (PinnedRead read = PinnedRead.of(base, null,
                        windows.getPath(spelling))) {
                    assertThat(read).as("spelled '%s': a directory component that is a"
                            + " symbolic link is refused, so the descent answers absent"
                            + " rather than reading C:\\outside\\secret.md", spelling)
                            .isNull();
                }
            }
        }
    }

    @Test
    @DisplayName("without openat a linked directory component is followed, as disclosed")
    void theFallbackFollowsALinkedDirectoryComponent() throws IOException {
        // The measurement that stops the two tests above from being read as more than they
        // are. PinnedRead discloses that without openat the read falls back to the path
        // "with NOFOLLOW_LINKS at the last component and the window this class closes still
        // open above it", and this is that sentence run: the same descent that answers null
        // with openat reads the outside file without it. Containment above the last
        // component is SafePaths' on such a platform, and SafePaths is what read_skill_
        // resource asks first -- PinnedRead.of is called here directly, which is the only
        // way to see the descent on its own.
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Path base = bundle(windows, "");
            Files.createDirectories(windows.getPath("C:\\outside"));
            Files.writeString(windows.getPath("C:\\outside\\secret.md"), "OUTSIDE-THE-BUNDLE");
            Files.createSymbolicLink(base.resolve("linked"), windows.getPath("C:\\outside"));

            try (PinnedRead read = PinnedRead.of(base, null,
                    windows.getPath("linked\\secret.md"))) {
                assertThat(read).as("the fallback resolves rather than refusing").isNotNull();
                assertThat(read.isPinned()).isFalse();
                assertThat(read.readString(java.nio.charset.StandardCharsets.UTF_8))
                        .as("the disclosed residue, measured: a linked directory component"
                                + " is followed where there is no openat to refuse it")
                        .isEqualTo("OUTSIDE-THE-BUNDLE");
            }
            // And the tool is still contained there, which is the half that matters to a
            // model: resolveWithin is asked first and refuses this before any descent runs.
            SkillLibrary library = new SkillLibrary(List.of(SkillLoader.loadSkill(base)));
            assertThat(read(library, "linked/secret.md"))
                    .as("the resource tool refuses what the fallback descent would follow")
                    .doesNotContain("OUTSIDE-THE-BUNDLE")
                    .contains("resolves outside the permitted directory");
        }
    }

    @Test
    @DisplayName("a symlink alias is not listed and is served under its target's name")
    void anAliasIsNotAdvertisedAndIsFencedByWhatItPointsAt() throws IOException {
        // The disagreement caught reviewing #79, asked in the spelling it was never asked
        // in. A second name for a listed file must not reach the catalog — an author could
        // then declare 'procedures: alias.md' against a name no read ever produces, which
        // loads clean and serves the declared file as evidence forever.
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Path base = bundle(windows, "procedures: refs/steps.md\n");
            Files.createSymbolicLink(base.resolve("alias.md"),
                    windows.getPath("refs\\steps.md"));

            Skill skill = SkillLoader.loadSkill(base);
            SkillLibrary library = new SkillLibrary(List.of(skill));

            assertThat(skill.resourceFiles())
                    .containsExactly("refs/steps.md", "template.md");
            assertThat(read(library, "alias.md"))
                    .contains("1. Gather.").contains("kind=\"procedure\"");
        }
    }

    @Test
    @DisplayName("loadDirectory finds a bundle on a non-default filesystem")
    void loadDirectoryWorksThere() throws IOException {
        // loadDirectory swallows a malformed skill with a logged warning, which is the
        // behaviour an unsupervised deployment needs and also the behaviour that made the
        // ProviderMismatchException invisible: before the fix this answered an empty list
        // rather than throwing.
        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            bundle(windows, "");

            List<Skill> skills = SkillLoader.loadDirectory(windows.getPath("C:\\skills"));

            assertThat(skills).extracting(Skill::name).containsExactly("reporter");
        }
    }

    @Test
    @DisplayName("with no bundle the host filesystem is the only thing a name could mean")
    void aSkillWithNoBundleFallsBackToTheHost() throws IOException {
        // There is no bundle, so there is no filesystem to ask. Asserted against a skill
        // that does have a host directory rather than against a hardcoded string, so the
        // claim is "the empty case behaves as the host does" on whichever host runs this —
        // and against one on a Windows filesystem, so the comparison is not vacuous.
        //
        // Built through the six-argument constructor, which exists so that callers written
        // before directoryIs still compile. Its shape is unchanged by #81 and this is what
        // would notice if that stopped being true.
        List<String> declared = List.of("refs\\steps.md");
        Skill noBundle = new Skill("plain", "d", "body", Optional.empty(),
                declared, declared);
        Skill onTheHost = new Skill("plain", "d", "body",
                Optional.of(Path.of(".")), declared, declared);

        assertThat(noBundle.directory()).isEmpty();
        assertThat(noBundle.procedureResources())
                .as("folded by the default filesystem's rules, which on a POSIX host makes"
                        + " this one filename with a backslash in it and on a Windows host"
                        + " makes it two components")
                .containsExactly(ResourcePaths.canonical("refs\\steps.md"));
        assertThat(noBundle.procedureResources())
                .as("no directory means the default filesystem, which is what a skill"
                        + " holding a host directory folds against too")
                .isEqualTo(onTheHost.procedureResources());
        assertThatCode(() -> noBundle.isProcedureResource("refs\\steps.md"))
                .as("asking a skill with no filesystem must not throw")
                .doesNotThrowAnyException();
        assertThat(noBundle.isProcedureResource("refs\\steps.md"))
                .isEqualTo(onTheHost.isProcedureResource("refs\\steps.md"));

        try (FileSystem windows = WindowsFilesystem.withoutOpenat()) {
            Skill inABundle = new Skill("plain", "d", "body",
                    Optional.of(windows.getPath("C:\\b")), declared, declared);

            assertThat(inABundle.procedureResources())
                    .as("two components there, so the canonical form is the wire spelling,"
                            + " whatever the host would have made of the same string")
                    .containsExactly("refs/steps.md");
        }
    }
}
