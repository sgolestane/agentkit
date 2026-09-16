package dev.agentkit.core.util;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The one name a file inside a base directory is known by, for the places that hand
 * relative paths to a model and take them back.
 *
 * <p>Two consumers, and both need every site to agree: a skill bundle, where what the
 * loader lists, what the author declared in {@code procedures:} and what a read resolves
 * to must be the same string or a declared procedure is served as evidence; and the
 * file-backed memory store, where a key that {@code list} reports must be one {@code read}
 * can find.
 *
 * <h2>Why a backslash is not a separator here</h2>
 *
 * <p>Names cross the wire in the platform's own spelling, with separators rendered as
 * {@code /} — that is what a model writes and what a {@code SKILL.md} is written in.
 * Getting there by rewriting every {@code \} looks platform-neutral and is not: on POSIX a
 * backslash is an ordinary filename character, so the rewrite renames real files. A bundle
 * holding both {@code a\b.md} and {@code a/b.md} listed one name twice, leaving the first
 * with no name a model could ask for, and a {@code procedures:} entry naming either one
 * promoted <em>both</em> to a procedure — an author declaring one file and the framework
 * fencing two, in the direction the evidence-by-default rule exists to prevent.
 *
 * <p>So the rewrite is asked of the filesystem rather than assumed. Going in, nothing is
 * rewritten at all: {@link Path#of} already accepts {@code /} on Windows, and on POSIX
 * rewriting would be the bug above. Coming out, the separator is rewritten only where it
 * is {@code \}.
 *
 * <h2>What this does not settle</h2>
 *
 * <p>One spelling per file, for the lexical variations a caller can invent. Not for a
 * filesystem that folds case or Unicode normalisation: there {@code Refs/Steps.md} and
 * {@code refs/steps.md} are one file and two names here. {@link SafePaths} carries the
 * same caveat, for the same reason — no test on Linux can exercise it.
 */
public final class ResourcePaths {

    private ResourcePaths() {
    }

    /**
     * {@code relative} as a name to hand a model: {@code /}-separated on every platform,
     * and unchanged where {@code /} is already the only separator.
     *
     * <p>The question goes to the path's own filesystem rather than to
     * {@code File.separatorChar}, so a path from a zip or an in-memory provider is
     * rendered by the rules it was built under rather than the host's.
     *
     * <p><strong>The rewrite used to sit behind a package-private
     * {@code slashSeparated(String, boolean)}</strong>, so that a test could pass the
     * answer its own platform never gives. That was the right call while it was true, and
     * it is no longer true: a bundle built on an in-memory Windows filesystem gives this
     * method a path whose separator really is {@code \}, so both branches are reached
     * through the composition that carried the bug — {@code getPath} then {@code normalize}
     * then the rewrite — rather than through the rewrite alone (#81). The seam tested the
     * one line least likely to be wrong and, once the real path was covered, was a second
     * runner of a rule that already had one.
     */
    public static String display(Path relative) {
        Objects.requireNonNull(relative, "relative");
        String name = relative.toString();
        return "\\".equals(relative.getFileSystem().getSeparator())
                ? name.replace('\\', '/')
                : name;
    }

    /**
     * {@code name} read back as a path on the filesystem it was rendered for — the inverse
     * of {@link #display}, and the reason this is a method rather than a {@link Path#of}
     * call at each site.
     *
     * <p>{@link Path#of} is always the <em>default</em> filesystem. A bundle need not be
     * there: it can be a zip, an in-memory provider, or anything else a caller opened, and
     * a path built by the wrong provider is not merely spelled wrong. It is rejected —
     * {@code base.resolve(foreign)} and {@code SecureDirectoryStream.newDirectoryStream}
     * both throw {@link java.nio.file.ProviderMismatchException}, so the read fails rather
     * than misreads. Asking the base's own filesystem is what keeps the pair symmetrical:
     * the name went out through that filesystem's separator and comes back through its
     * {@code getPath}.
     *
     * <p>Nothing is rewritten going in. {@link #display} emits {@code /}, and {@code /} is
     * a separator to both syntaxes this framework meets — see the class note for why
     * rewriting the other direction is the bug rather than the fix.
     *
     * @throws InvalidPathException if {@code filesystem} cannot parse {@code name}
     */
    public static Path pathIn(FileSystem filesystem, String name) {
        Objects.requireNonNull(filesystem, "filesystem");
        Objects.requireNonNull(name, "name");
        return filesystem.getPath(name);
    }

    /**
     * One spelling per file: {@code .} and {@code ..} segments folded, separators as
     * {@link #display} renders them.
     *
     * <p>Folded and rendered by {@code filesystem}'s rules rather than the host's, which is
     * the whole of why this overload exists. {@code refs\steps.md} is one component on
     * POSIX and two on Windows, so a declaration in a {@code SKILL.md} canonicalises to a
     * different string depending on which machine reads the bundle — and the canonical form
     * is what a {@code procedures:} entry is compared against. Pass the filesystem the
     * bundle is on; {@link #canonical(String)} is the answer when there is no bundle.
     *
     * <p>Unparseable input is returned unchanged rather than rejected. It matches nothing,
     * so a caller comparing against a known name gets a miss, and refusing here would turn
     * a bad argument into a load failure or an exception out of a predicate.
     */
    public static String canonical(FileSystem filesystem, String path) {
        Objects.requireNonNull(filesystem, "filesystem");
        Objects.requireNonNull(path, "path");
        try {
            return display(pathIn(filesystem, path).normalize());
        } catch (InvalidPathException e) {
            return path;
        }
    }

    /**
     * {@link #canonical(FileSystem, String)} against the default filesystem.
     *
     * <p>Not a convenience for callers that have a filesystem and cannot be bothered to
     * pass it: the default filesystem is the honest answer only when there is no bundle to
     * ask. An in-memory skill has no directory, so the platform running the JVM is the only
     * spelling anything could mean. A caller holding a base directory has a better answer
     * and should pass it.
     */
    public static String canonical(String path) {
        return canonical(FileSystems.getDefault(), path);
    }

    /**
     * The name {@code resolved} is known by inside {@code baseDir}, or {@code null} if it
     * is not inside it at all.
     *
     * <p>Pass a path from {@link SafePaths#resolveWithin}, which is symlink-free: relativising
     * that against the base's own real location is what collapses a file's aliases — spelling
     * and links alike — onto the single name the base's listing would give it. Both sides go
     * through {@code toRealPath} so they compare like with like; if the base has since gone,
     * the lexical form stands, since nothing can be planted under a directory that is not there.
     */
    public static String relativeTo(Path baseDir, Path resolved) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(resolved, "resolved");
        try {
            Path realBase = baseDir.toRealPath();
            if (resolved.startsWith(realBase)) {
                return display(realBase.relativize(resolved));
            }
        } catch (IOException baseGone) {
            // Fall through to the lexical base.
        }
        Path base = baseDir.toAbsolutePath().normalize();
        return resolved.startsWith(base) ? display(base.relativize(resolved)) : null;
    }
}
