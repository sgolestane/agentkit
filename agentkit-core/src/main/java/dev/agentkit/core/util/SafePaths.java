package dev.agentkit.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Helpers for confining model-supplied relative paths to a trusted base directory.
 *
 * <p>Rejects the traversal a model can write ({@code ..}, absolute paths) and the
 * containment failure it cannot — a symbolic link that leaves the base.
 *
 * <p>Used wherever the model controls a path — reading skill resources and the
 * file-backed memory store.
 *
 * <p>Exception messages quote only the caller-supplied {@code requested} string, so
 * they are safe to hand back to a model. Host paths appear in the {@code cause}, never
 * in {@code getMessage()}; keep it that way if you add a message here.
 */
public final class SafePaths {

    /**
     * How many dangling links {@link #realOfDanglingLink} will follow before refusing.
     *
     * <p>Smaller than the kernel's forty on purpose: a chain that long inside a bundle is
     * not a resource somebody meant to ship, and refusing is the safe answer either way.
     */
    private static final int MAX_LINK_HOPS = 8;

    private SafePaths() {
    }

    /**
     * Resolves {@code requested} against {@code baseDir} and verifies the result
     * stays within {@code baseDir}, following symlinks.
     *
     * <p>Two checks, and both are needed. The lexical one rejects {@code ..} and
     * absolute paths — the escapes the model can write itself. The second resolves
     * the deepest part of the path that exists and confirms it is <em>really</em>
     * inside the base, catching a path that is well-formed and still leaves: a
     * symlink planted in the tree by something else. A third-party skill bundle that
     * ships one is exactly that case.
     *
     * <p>Both sides are resolved, so a base that is itself a symlink — a memory
     * root pointing into a mounted volume — is fine. What is rejected is the
     * resolved target landing outside the resolved base.
     *
     * <p>The returned path is symlink-free, which is the point of returning one at
     * all: hand back the requested path and the caller re-walks every component this
     * just validated, giving anything that can rename a directory a second attempt at
     * each. Callers should open what they are given rather than re-deriving it.
     *
     * <p>The path need not exist: for a write, the deepest existing ancestor is
     * resolved and the missing tail appended, which is sound because components that
     * do not exist cannot be links. A dangling link is judged by where it aimed, so a
     * bundle symlinking a sibling left out of an archive is broken rather than hostile.
     * Nor need the base exist — nothing can be planted under a directory
     * that is not there, so the lexical result stands and the caller's own open
     * fails as it always would.
     *
     * <p><strong>Symbolic links specifically.</strong> A <em>hard</em> link inside
     * the base to a file outside it is not detected and cannot be: a hard link has
     * no separate real path to compare. An archive can carry one, so a bundle from
     * somewhere you do not trust is not fully contained by this.
     *
     * <p>A <strong>bind mount</strong> is the same blind spot and is likelier to arise by
     * accident. It has no separate real path either — {@code realpath} of a mountpoint is
     * the mountpoint — so nothing here can see it, and read, write and delete all reach the
     * mounted directory. Mounting needs privilege, so the realistic route is an operator
     * rather than an attacker: {@code docker run -v /host/secrets:/data/mem/shared} is
     * exactly this shape, and it hands whatever holds the base read and write access to
     * {@code /host/secrets}. Choose what you mount under a base you hand to a model.
     *
     * <p>Even so this is a check, not a lock. Returning a resolved path closes the
     * window on every component it names, but the final open is still a separate
     * syscall — and anyone with write access to the tree at runtime can rewrite the
     * file's contents anyway. It bounds what a bundle <em>ships</em>, not what a live
     * process does.
     *
     * <p><strong>So every caller closes that window itself, and both of them now do</strong>
     * (#143, #167). {@code FileMemoryStore} descends to the key's folder with
     * {@code PinnedDirectory} and opens the key relative to that descriptor; skill loading
     * and {@code read_skill_resource} do the same with
     * {@link dev.agentkit.core.util.PinnedRead}. Neither re-walks the path this method
     * returns, so a component rotated after the check redirects nothing. The divergence
     * #167 tracked — one caller opening with {@code NOFOLLOW_LINKS} and the other with a
     * plain {@code Files.readString} — is gone; what remains true is the sentence above, that
     * this is a check and the open is somebody else's syscall. The two consumers now agree
     * about whose.
     *
     * <p>That leaves this method doing the half it is good at: deciding <em>which file</em>
     * the caller may have. It rejects {@code ..}, it collapses a file's aliases onto the one
     * name a listing advertises, and it words a refusal that is safe to hand a model. A
     * descent cannot do any of that, and this cannot make a read land where it decided —
     * which is why both are needed and neither was replaced by the other.
     *
     * <p>Untested on case-insensitive or Unicode-normalising filesystems. Both sides
     * go through {@code toRealPath}, so containment should compare like with like, but
     * no test here can exercise that on Linux.
     *
     * @param baseDir   the directory to confine to
     * @return the normalised, absolute path inside {@code baseDir}
     * @throws IllegalArgumentException if {@code requested} escapes {@code baseDir}
     */
    public static Path resolveWithin(Path baseDir, String requested) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(requested, "requested");
        if (requested.isBlank()) {
            throw new IllegalArgumentException("requested path must not be blank");
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(requested).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            // Reject escapes above the base AND the base directory itself: a
            // model-supplied key must denote a file strictly inside the base.
            throw new IllegalArgumentException(
                    "Path '" + requested + "' does not resolve to a location inside the permitted directory");
        }
        return realWithin(base, resolved, requested);
    }

    /**
     * Resolves {@code resolved} to a symlink-free path and confirms it is inside
     * {@code base}.
     *
     * <p>Returning the resolved path rather than the requested one is what makes the
     * check worth having. Hand back the lexical path and the caller re-walks every
     * component this just validated, so anything that can rename a directory in
     * between gets a second attempt at each one — a race that needs no timing skill,
     * only patience. The path returned here contains no links to re-follow.
     *
     * <p>The deepest existing component is resolved and the missing tail appended: the
     * components that do not exist cannot be links. A dangling link is resolved through
     * its own target, so one pointing at a sibling that was left out of an archive is
     * refused only if the target would have been outside.
     */
    private static Path realWithin(Path base, Path resolved, String requested) {
        Path realBase;
        try {
            realBase = base.toRealPath();
        } catch (IOException e) {
            // A base that is not there cannot contain a planted link, so there is nothing
            // to follow and the lexical result stands. Throwing here would buy no safety
            // and would cost a lot: the two model-facing tools turn an
            // IllegalArgumentException into a tool error, so a missing directory — an
            // operator's mistake — would be reported to the model as a bad path argument,
            // and MemoryStore's read/exists/delete would stop being total.
            return resolved;
        }
        try {
            // The common case, and one syscall rather than a walk: the whole path exists.
            return requireInside(resolved.toRealPath(), realBase, requested, true);
        } catch (IOException fullPathMissing) {
            return realWithinPartial(realBase, resolved, requested);
        }
    }

    /** As {@link #realWithin}, for a path whose tail does not exist yet. */
    private static Path realWithinPartial(Path realBase, Path resolved, String requested) {
        Path existing = resolved;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalStateException("Walked above the base while confining '" + requested + "'");
        }
        Path realExisting;
        try {
            realExisting = existing.toRealPath();
        } catch (IOException dangling) {
            realExisting = realOfDanglingLink(existing, requested);
        }
        // Not strict: when the tail does not exist the base itself is the deepest
        // existing component, and it is legitimately its own ancestor. The appended
        // tail is what puts the result strictly inside.
        requireInside(realExisting, realBase, requested, false);
        // The tail cannot contain links — it does not exist — so appending it to a
        // resolved prefix yields a path with none anywhere.
        return existing.equals(resolved) ? realExisting : realExisting.resolve(existing.relativize(resolved));
    }

    /**
     * Where a dangling symlink would have pointed.
     *
     * <p>Refusing every dangling link would refuse a bundle that symlinks a sibling
     * left out of the archive — a broken resource, not an escape. Resolve the target
     * against the link's real parent so containment is decided by where it aimed.
     *
     * <p><strong>Where it aimed is the kernel's answer, not a lexical one.</strong> This
     * used to finish with {@code .normalize()}, which folds {@code X/..} by deleting both
     * components — correct only when {@code X} is a real directory. The kernel resolves
     * {@code X} first, so for a target of {@code sub/../name} where {@code sub} is a link,
     * the two answers are different directories, and this class handed back the one that
     * passed containment while the open went to the other. A link named to fold back onto
     * itself made {@code resolveWithin} return the caller's own lexical path, so every
     * check downstream saw "no link here" and a write created a file wherever the planted
     * link pointed. Measured on {@code FileMemoryStore}: {@code write} accepted, and the
     * content landed outside the root.
     *
     * <p><strong>It is a write hole, not a read one</strong>, and the reason is worth
     * knowing: this method runs only when the target does not exist, so there is nothing
     * there to read — but a write to a name that <em>is</em> a dangling link creates the
     * file at the link's target. That is why the memory store's write was the exploitable
     * consumer and skill-resource reading was not, and why a fix that only stopped reads
     * would have missed it entirely.
     *
     * <p>So the aimed path goes back through {@link #realWithinPartial}'s walk rather than
     * through {@code normalize}: the deepest component that exists is resolved by
     * {@code toRealPath}, which is the kernel, and only the tail that does not exist —
     * which therefore cannot be a link — is appended. Folding {@code ..} against a
     * <em>real</em> prefix is sound, because a real path's parent is its true parent.
     *
     * <p>{@code hops} bounds the recursion, because a link may aim at another dangling
     * link and a pair of them may aim at each other. The kernel's own limit is forty; this
     * is smaller because a chain that long inside a bundle is not a resource, and the
     * refusal is the safe answer either way.
     */
    private static Path realOfDanglingLink(Path link, String requested) {
        return realOfDanglingLink(link, requested, MAX_LINK_HOPS);
    }

    private static Path realOfDanglingLink(Path link, String requested, int hops) {
        try {
            Path parent = link.getParent();
            if (parent == null || hops <= 0 || !Files.isSymbolicLink(link)) {
                throw new IOException("not a symbolic link");
            }
            Path aimed = parent.toRealPath().resolve(Files.readSymbolicLink(link));
            return realOfAimedPath(aimed, requested, hops - 1);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Path '" + requested + "' cannot be resolved to a real location", e);
        }
    }

    /**
     * The real location {@code aimed} names, resolving as much of it as exists.
     *
     * <p>The same walk {@link #realWithinPartial} does, without the containment check —
     * this is asked about a link's target, and whether the target is inside the base is the
     * caller's question rather than this method's.
     */
    private static Path realOfAimedPath(Path aimed, String requested, int hops)
            throws IOException {
        try {
            // The whole path exists, so the kernel has already resolved every component.
            return aimed.toRealPath();
        } catch (IOException tailMissing) {
            Path existing = aimed;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new IOException("nothing along '" + aimed + "' exists");
            }
            Path realExisting;
            try {
                realExisting = existing.toRealPath();
            } catch (IOException dangling) {
                realExisting = realOfDanglingLink(existing, requested, hops);
            }
            // Normalised only now, against a prefix with no links left in it.
            return existing.equals(aimed)
                    ? realExisting
                    : realExisting.resolve(existing.relativize(aimed)).normalize();
        }
    }

    /**
     * Requires {@code real} to be inside {@code realBase}, and returns it.
     *
     * <p>{@code strict} additionally rejects the base itself, which is right for a
     * fully-resolved target — a link pointing back at the base would otherwise give
     * every file unbounded aliases ({@code self/self/…/x}) — and wrong for a prefix,
     * where the base is the legitimate ancestor of a path not yet created.
     */
    private static Path requireInside(Path real, Path realBase, String requested, boolean strict) {
        // Component-wise, not string-prefix: a sibling directory named like the base
        // with more characters after it must not count as inside.
        if (!real.startsWith(realBase) || (strict && real.equals(realBase))) {
            throw new IllegalArgumentException("Path '" + requested
                    + "' resolves outside the permitted directory through a symbolic link");
        }
        return real;
    }
}
