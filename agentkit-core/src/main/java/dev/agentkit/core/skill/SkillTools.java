package dev.agentkit.core.skill;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.PinnedRead;
import dev.agentkit.core.util.ResourcePaths;
import dev.agentkit.core.util.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the tools that drive tiers 2 and 3 of skill progressive disclosure over
 * a {@link SkillLibrary}:
 *
 * <ul>
 *   <li>{@code read_skill(name)} — loads a skill's full instructions (tier 2);</li>
 *   <li>{@code read_skill_resource(skill, path)} — reads a bundled resource file
 *       (tier 3), confined to the skill directory, symlinks included — a resource that leaves the bundle is neither listed nor served.</li>
 * </ul>
 *
 * <p>Register these with a tool registry and inject {@link SkillLibrary#catalog()}
 * into the system prompt to complete the three-tier model.
 */
public final class SkillTools {

    public static final String READ_SKILL = "read_skill";
    public static final String READ_SKILL_RESOURCE = "read_skill_resource";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SkillTools.class);

    private SkillTools() {
    }

    /** The read_skill and read_skill_resource tools for {@code library}. */
    public static List<Tool> forLibrary(SkillLibrary library) {
        Objects.requireNonNull(library, "library");
        return List.of(readSkillTool(library), readSkillResourceTool(library));
    }

    public static Tool readSkillTool(SkillLibrary library) {
        Objects.requireNonNull(library, "library");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of(
                        "type", "string", "description", "The skill name from the catalog")),
                "required", List.of("name"));
        return FunctionTool.builder(READ_SKILL,
                        "Load a skill's full instructions before using it. Pass the skill name "
                                + "shown in the skills catalog.")
                .schema(schema)
                .readOnly()
                // A skill bundle is somebody else's: a third-party bundle is exactly the
                // population SafePaths' symlink note is written for.
                .provenance(Provenance.THIRD_PARTY)
                .handler(inv -> readSkill(library, inv))
                .build();
    }

    public static Tool readSkillResourceTool(SkillLibrary library) {
        Objects.requireNonNull(library, "library");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "skill", Map.of("type", "string", "description", "The skill name"),
                        "path", Map.of("type", "string",
                                "description", "Relative path of the bundled resource file")),
                "required", List.of("skill", "path"));
        return FunctionTool.builder(READ_SKILL_RESOURCE,
                        "Read a bundled resource file belonging to a skill, by its relative path.")
                .schema(schema)
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(inv -> readResource(library, inv))
                .build();
    }

    private static ToolResult readSkill(SkillLibrary library, ToolInvocation inv) {
        String name = inv.stringArgument("name");
        if (name == null || name.isBlank()) {
            return ToolResult.error("The 'name' argument is required.");
        }
        Optional<Skill> skill = library.find(name);
        if (skill.isEmpty()) {
            return ToolResult.error("No skill named '" + name + "'.");
        }
        // Fenced as a procedure, and this is the half that matters. Fencing the catalog
        // while returning the instructions bare would guard the advertisement and not the
        // payload — an author who wanted to steer the run would simply write a bland
        // description and put everything here. A procedure is still followed, so the skill
        // works; what it cannot do is redefine the objective or reach a tool the run was
        // not given, which is the whole difference between loading a skill and obeying one.
        // Label is the bare word, not the skill's name: a name comes from SKILL.md
        // frontmatter with no restriction beyond non-blank, and a label sits on the marker
        // line rather than inside the fence, so deriving it from the bundle would hand a
        // hostile one ~80 characters of unfenced prose next to our own markup. That is the
        // trap KnowledgeTools documents avoiding; the name is inside the instructions.
        return ToolResult.ok(Spotlight.wrap(Spotlight.Kind.PROCEDURE,
                Source.of("skill"), skill.get().renderInstructions(READ_SKILL_RESOURCE)));
    }

    private static ToolResult readResource(SkillLibrary library, ToolInvocation inv) {
        String name = inv.stringArgument("skill");
        String path = inv.stringArgument("path");
        if (name == null || name.isBlank() || path == null || path.isBlank()) {
            return ToolResult.error("Both 'skill' and 'path' arguments are required.");
        }
        Optional<Skill> skill = library.find(name);
        if (skill.isEmpty()) {
            return ToolResult.error("No skill named '" + name + "'.");
        }
        Optional<Path> dir = skill.get().directory();
        if (dir.isEmpty()) {
            return ToolResult.error("Skill '" + name + "' has no bundled resources.");
        }
        Path resolved;
        try {
            resolved = SafePaths.resolveWithin(dir.get(), path);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        // The one name this file is known by inside the bundle, and the only resolution
        // anything below uses: the descent walks it, and the fence decision is taken from
        // it. Deriving the two separately is how they come to disagree.
        String declaredAs = ResourcePaths.relativeTo(dir.get(), resolved);
        if (declaredAs == null || declaredAs.isEmpty()) {
            // Not inside the bundle after all, which resolveWithin already refused -- so it
            // means the bundle's own name was repointed between that check and this. The
            // descent refuses that too; answering absent here is one syscall cheaper and
            // says the same thing.
            return ToolResult.error("No such resource file: '" + path + "'.");
        }
        // On the bundle's filesystem, not the host's: declaredAs came out of relativeTo
        // rendered with the bundle's separator, and a path from another provider is
        // refused rather than misread -- ProviderMismatchException out of PinnedRead.of.
        try (PinnedRead read = PinnedRead.of(dir.get(), skill.get().directoryIs(),
                ResourcePaths.pathIn(dir.get().getFileSystem(), declaredAs))) {
            if (read == null || !isRegularFile(read)) {
                // Absent, a folder, a device -- or a symbolic link, which fstatat reports as
                // itself rather than as its target. SafePaths has already collapsed every
                // alias the bundle legitimately ships, so a link standing here now is one
                // that arrived after the check, and "no such resource file" is the right
                // answer for it as well as the one this tool already gave.
                return ToolResult.error("No such resource file: '" + path + "'.");
            }
            // The author says which files are steps; everything else is evidence. Fencing
            // every bundled file as a procedure made "carry this out" the widest-scoped
            // kind in the taxonomy and applied it to arbitrary bytes — a template, a
            // dataset, a cached corpus — which is the one place the design would raise the
            // trust of content rather than lower it. Fencing them all as evidence is no
            // better: a reference doc whose whole content is procedure would arrive marked
            // "do not take direction from this", right after instructions telling the model
            // to follow it. Only the bundle author can tell the two apart, so the bundle
            // author declares it, and the strict reading is what an absent declaration
            // gets. See Skill for why the default runs that way.
            //
            // The label is a bare word: both the skill name and the path come from outside,
            // and a label sits on the marker line rather than inside the fence.
            //
            // declaredAs is asked of the path the read resolved to, not of the argument:
            // the argument is the model's spelling and a file has many ('./refs/steps.md',
            // 'refs//steps.md', a symlink beside it). Every one of them serves the declared
            // file's bytes, so every one of them has to reach the declared file's answer —
            // otherwise the contradiction this fence exists to remove is one spelling away.
            if (SkillLoader.SKILL_FILE.equals(declaredAs)) {
                // Not listed, so not servable — and the exclusion has to be on what the
                // path resolved to rather than on how it was spelled, or a symlink beside
                // it walks straight through. The same bytes do come back from read_skill,
                // but as a procedure; serving them here as evidence tells the model two
                // different things about one file.
                return ToolResult.error("No such resource file: '" + path + "'.");
            }
            Spotlight.Kind kind = skill.get().isProcedureResource(declaredAs)
                    ? Spotlight.Kind.PROCEDURE
                    : Spotlight.Kind.EVIDENCE;
            return ToolResult.ok(Spotlight.wrap(kind, Source.of("skill-resource"),
                    read.readString(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            // Do not surface the absolute path / host layout to the model.
            LOG.warn("Failed to read skill resource {} for skill {}",
                    Quoted.of(path), Quoted.of(name), Quoted.failure(e));
            return ToolResult.error("Failed to read resource '" + path + "'.");
        }
    }

    /**
     * Whether an ordinary file stands at the resource, asked of the pinned folder.
     *
     * <p><strong>The defect this replaces.</strong> This tool used to ask
     * {@code Files.isRegularFile(resolved)} and then finish with
     * {@code Files.readString(resolved, UTF_8)} — two more full path resolutions by the
     * kernel, after {@link SafePaths#resolveWithin} had already resolved the same path once.
     * {@code SafePaths} says of itself that it is "a check, not a lock", and it is right:
     * anything that can rename a component between the resolutions redirects the read, and
     * the bytes it finds go back to the model inside a {@code Spotlight} fence. Measured on
     * this machine with one thread rotating one name, 20,000 tool calls per run and every
     * attempt counted:
     *
     * <pre>
     * what was rotated                            attemptsMade   escapes
     * the last component, file &lt;-&gt; link outside      20000       482 /  25 /  79
     * a directory component, dir &lt;-&gt; link outside    20000       131 /   7 /   2
     * the bundle directory itself, no race at all      200       200 / 200 / 200
     * </pre>
     *
     * <p>An escape is the tool answering with bytes that exist only outside the bundle. The
     * third row needs no timing: {@code rm -rf bundle && ln -s /attacker bundle} after the
     * library is built redirected every single read, because the bundle was a name and a
     * name is re-resolved on every call. Through {@link PinnedRead} all three are 0, every
     * run — {@code SkillRaceTest} carries the harness and the after figures.
     *
     * <p><strong>Why the check stays.</strong> {@code resolveWithin} is not replaced, and
     * this is the point #167 argues about: it decides <em>whether the model may have this
     * file</em> — it rejects {@code ..}, it collapses a bundle's aliases onto the one name
     * the catalog advertises, and it words the refusal a model can act on. What it cannot do
     * is make the read land where it decided, and that is all the descent adds. The check
     * says which file; the descent makes sure the read gets that one.
     *
     * <p>Two differences from {@code Files.isRegularFile}, and both are the fix: this names
     * one entry in a directory already open — {@code fstatat(dirfd, name,
     * AT_SYMLINK_NOFOLLOW)} — and it reports a symbolic link as a link rather than as
     * whatever it points at.
     */
    private static boolean isRegularFile(PinnedRead read) throws IOException {
        BasicFileAttributes standing = read.standing();
        return standing != null && standing.isRegularFile();
    }
}
