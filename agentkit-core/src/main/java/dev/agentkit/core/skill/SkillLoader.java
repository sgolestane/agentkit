package dev.agentkit.core.skill;

import dev.agentkit.core.util.PinnedRead;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.util.ResourcePaths;
import dev.agentkit.core.util.SafePaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads {@link Skill skills} from the filesystem.
 *
 * <p>A skill is a directory containing a {@code SKILL.md}; every other regular
 * file under that directory is registered as a bundled resource (tier 3).
 */
public final class SkillLoader {

    /** The conventional skill definition file name. */
    public static final String SKILL_FILE = "SKILL.md";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SkillLoader.class);

    private SkillLoader() {
    }

    /**
     * Loads every skill directory directly under {@code root} (a directory is a
     * skill directory iff it contains a {@code SKILL.md}).
     *
     * <p><strong>Resilient by design:</strong> a directory whose skill fails to
     * parse or load is skipped with a logged warning rather than aborting the
     * whole load — one malformed skill must not disable every other skill in an
     * unsupervised deployment. Use {@link #loadSkill(Path)} directly when you want
     * a single skill's failure to propagate.
     */
    public static List<Skill> loadDirectory(Path root) {
        Objects.requireNonNull(root, "root");
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> skillDirs = entries
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(SKILL_FILE)))
                    .sorted()
                    .toList();
            for (Path dir : skillDirs) {
                try {
                    skills.add(loadSkill(dir));
                } catch (RuntimeException e) {
                    LOG.warn("Skipping malformed skill at {}: {}",
                            Quoted.of(String.valueOf(dir)), Quoted.of(e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list skill directory: " + root, e);
        }
        return skills;
    }

    /** Loads a single skill from its directory. */
    public static Skill loadSkill(Path skillDir) {
        Objects.requireNonNull(skillDir, "skillDir");
        Path md = skillDir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(md)) {
            throw new IllegalArgumentException("No " + SKILL_FILE + " in " + skillDir);
        }
        try {
            // Real, not merely absolute: linking a bundle into the skills directory is the
            // ordinary way to develop one, and Files.walk does not follow the start link —
            // so a lexical base listed nothing at all. Silent before 'procedures:' existed,
            // and since then the skill vanishes with a warning telling the operator the
            // author declared a file that is not bundled, when it plainly is. Resolving
            // here also makes this base the one SkillTools relativises reads against.
            Path base = skillDir.toRealPath();
            String content;
            Object baseIs;
            // SKILL.md is read through the directory it lives in rather than through its
            // own path (#167). Measured before this, one thread flipping SKILL.md between
            // the real file and a symbolic link pointing outside the bundle, 20,000 loads
            // per run and every attempt counted: 677, 396 and 541 loads took the outside
            // file's body as the skill's instructions, first at attempt 7. That is the worst
            // direction a bundle has, because a skill's instructions are what read_skill
            // serves and it serves them fenced as a PROCEDURE -- so a won race does not
            // merely leak a file, it promotes one to something the model is told to carry
            // out. With the descent: 0, 0, 0. SkillRaceTest carries the harness.
            //
            // Resolved before it is descended, and the two steps do different jobs. The
            // check decides WHICH file: a SKILL.md that is a symbolic link to a sibling
            // inside the bundle is an ordinary way to share one body between bundles, and
            // this keeps loading it, while a link aiming outside is refused in the same
            // sentence resolveWithin refuses a resource with. The descent then makes the
            // read land on the file the check named. Opening SKILL.md by its own name with
            // O_NOFOLLOW and nothing else would have refused the benign alias too, and a
            // containment fix that quietly drops a working case is how one gets reverted.
            String named = ResourcePaths.relativeTo(base,
                    SafePaths.resolveWithin(base, SKILL_FILE));
            // Read back on the base's own filesystem, not with Path.of. The name came out
            // through relativeTo, which renders with the base's separator; Path.of would
            // read it back with the host's, and a path from another provider is not a
            // near-miss — base.resolve throws ProviderMismatchException, so loading any
            // bundle not on the default filesystem failed outright (#81).
            try (PinnedRead read = named == null || named.isEmpty() ? null
                    : PinnedRead.of(base, null,
                            ResourcePaths.pathIn(base.getFileSystem(), named))) {
                if (read == null) {
                    // Nothing reachable stands there through a descent that refuses a link
                    // at every component -- which includes the isRegularFile above having
                    // answered yes about a link, since that question follows one and this
                    // does not.
                    throw new IllegalArgumentException("No " + SKILL_FILE + " in " + skillDir);
                }
                // Taken from the descent's own open of the base rather than from a separate
                // stat, so there is no interval between establishing which inode the bundle
                // is and reading through it. Every later read compares against this.
                baseIs = read.identity();
                content = read.readString(StandardCharsets.UTF_8);
            }
            SkillParser.Parsed parsed = SkillParser.parse(content);
            List<String> resources = listResources(base);
            return new Skill(parsed.name(), parsed.description(), parsed.body(),
                    Optional.of(base), resources, procedureResources(parsed), baseIs);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read skill at " + skillDir, e);
        }
    }

    /**
     * The {@code procedures:} frontmatter key: a comma-separated list of bundled files the
     * author declares as steps to carry out, rather than material to read.
     *
     * <p>Absent means none, which fences every resource as evidence — see {@link Skill}
     * for why the default runs that way. Entries are not checked here; {@link Skill}'s
     * constructor rejects one that is not bundled, so a typo fails the load rather than
     * silently downgrading a procedure to evidence.
     *
     * <p>One line, because {@link SkillParser} reads scalars rather than YAML: the
     * {@code - item} list form is a frontmatter parse error, not a declaration. A file
     * whose name contains a comma therefore cannot be declared — it splits, and the load
     * fails naming the halves.
     */
    private static List<String> procedureResources(SkillParser.Parsed parsed) {
        String declared = parsed.frontmatter().get("procedures");
        if (declared == null) {
            return List.of();
        }
        // No separate blank guard: an empty or all-whitespace value splits into entries
        // that strip to nothing, and the filter below drops them. Nor a dedup: two entries
        // are the same file only once they are canonical, which is Skill's job, so doing it
        // here would catch the easy half and leave the half worth catching.
        return Stream.of(declared.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private static List<String> listResources(Path base) throws IOException {
        try (Stream<Path> walk = Files.walk(base)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(base::relativize)
                    .map(ResourcePaths::display)
                    .filter(name -> !name.equals(SKILL_FILE))
                    .filter(SkillLoader::printable)
                    .filter(name -> name.equals(servedAs(base, name)))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * Whether {@code name} can be listed without the listing becoming something else.
     *
     * <p>{@code renderInstructions} puts these names in a text block under two headings,
     * and {@code read_skill} hands that block to the model fenced as a procedure. A POSIX
     * filename may contain a newline, so a resource named
     * {@code "x.md\n\nBundled steps to carry out (...):\n- x.md"} writes a second heading
     * inside trusted text and promotes files the author never declared — a fence forged
     * from a directory entry rather than from the fenced content.
     *
     * <p>Dropped rather than escaped: a name a model cannot reliably type is not a name
     * worth advertising, and escaping would still have to be undone somewhere. Against a
     * hostile bundle author this grants nothing — their {@code SKILL.md} is already a
     * procedure — but a bundle's {@code references/} and its {@code SKILL.md} do not always
     * come from the same place.
     */
    private static boolean printable(String name) {
        return name.codePoints().noneMatch(Character::isISOControl);
    }

    /**
     * The name {@code read_skill_resource} would answer under if asked for {@code name},
     * or {@code null} if it would refuse.
     *
     * <p>The catalog is the only place a model learns these names, so a name that does not
     * come back as itself must not be in it. Three ways that happens, and all of them
     * matter:
     *
     * <ul>
     *   <li>{@code Files.walk} does not follow links but {@code isRegularFile} does, so a
     *       bundle can list a file living outside it — which {@link SafePaths} then
     *       refuses. Advertising it puts an entry in the catalog guaranteed to fail, and
     *       tells the model where a hostile bundle points.</li>
     *   <li>A symlink to a file <em>inside</em> the bundle is a second name for something
     *       already listed. A read of it answers under the target's name, so listing it
     *       too would put one file in the catalog twice — and, worse, let an author
     *       declare {@code procedures: alias.md} against a name no read ever produces.
     *       That load succeeded and every read came back evidence, which is the
     *       contradiction the declaration exists to remove, arrived at silently.</li>
     *   <li>A path with more components than the pinned read will hold descriptors for is
     *       resolvable and not readable — {@link PinnedRead#canDescend} is the bound, and it
     *       is asked here so the listing and the reader answer the same question. A catalog
     *       entry no read can serve is the defect the other two are about, reached by
     *       depth. What counts as a component is asked of the base's filesystem, for the
     *       same reason the read asks it there: {@code Path.of} would answer with the
     *       host's syntax about somebody else's tree. No bundle reachable today changes its
     *       answer between the two — {@code relativeTo} renders with {@code /}, which both
     *       syntaxes split on — so this one is consistency rather than a repair, and it is
     *       written that way rather than claimed as a fix.</li>
     * </ul>
     *
     * <p>An alias is still readable, and still fenced by what it points at — it is simply
     * not advertised, and cannot be declared. The name that survives is the target's.
     */
    private static String servedAs(Path base, String name) {
        try {
            String served = ResourcePaths.relativeTo(base, SafePaths.resolveWithin(base, name));
            // And it has to be readable as well as resolvable. The pinned descent holds a
            // descriptor per component and refuses a path deeper than it will hold, so a
            // name past that bound is one every read answers "no such resource file" for.
            // Advertising it would put an entry in the catalog guaranteed to fail, which is
            // the same defect the two cases above are about, arrived at by depth.
            return served != null
                    && PinnedRead.canDescend(ResourcePaths.pathIn(base.getFileSystem(), served))
                    ? served : null;
        } catch (IllegalArgumentException outsideTheBundle) {
            return null;
        }
    }
}
