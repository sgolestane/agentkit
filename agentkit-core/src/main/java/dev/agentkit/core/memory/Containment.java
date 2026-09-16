package dev.agentkit.core.memory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

/**
 * Whether a store may run without the containment it documents.
 *
 * <h2>Why this is an argument and not a default</h2>
 *
 * <p>{@link FileMemoryStore} closes a family of TOCTOU races by descending with {@code openat}
 * — one component per syscall, each answered against a descriptor rather than a name, so there
 * is no interval between judging a path and using it. That is what makes the containment claim
 * in the README true, and the pentest suite measures it: with the pinned descent, an attacker
 * racing a symbolic link into an intermediate component escapes the root <strong>zero</strong>
 * times in 20,000 attempts, on write, append, delete, read and list alike.
 *
 * <p>The descent is {@link SecureDirectoryStream}, and <strong>only Linux's JDK returns
 * one</strong>. On macOS {@code Files.newDirectoryStream} hands back a plain
 * {@code sun.nio.fs.UnixDirectoryStream}; the store falls back to resolving paths by name, and
 * every one of those counts comes back — one run measured 6,713 escaping writes, 7,787 reads,
 * 6,833 deletes.
 *
 * <h2>The failure this exists to prevent is not the escape</h2>
 *
 * <p>It is <em>not knowing</em>. Both fallbacks were disclosed in javadoc, and both described
 * the platform as Windows — a claim made from a Linux runner and generalised to "Unix". The
 * tests that contradict it had been red on macOS for an unrelated fixture bug and written off
 * as "those fail on Macs", so a wrong security claim stood in the README for two years while
 * this repository's own suite printed the counter-evidence on every developer's machine.
 *
 * <p>A silent default would have reproduced exactly that. A warning would too — the situation
 * above <em>is</em> what an ignorable disclosure produces. So the constructor takes this and
 * has no overload that omits it: a Mac developer writes {@link #BEST_EFFORT} once and knows
 * what they chose, and a production wiring that writes it is making a statement somebody can
 * review in a diff.
 *
 * <h2>This changes nothing on Linux</h2>
 *
 * <p>Where the descent is available both values behave identically, because both get it. The
 * choice is only ever about what to do where it is not.
 */
public enum Containment {

    /**
     * Refuse to build a store that cannot contain what it holds.
     *
     * <p>For anything holding data whose confinement matters, and the right value for a
     * production wiring. Construction throws where the pinned descent is unavailable, naming
     * the platform, rather than starting and quietly meaning less than it says.
     */
    PINNED_OR_FAIL,

    /**
     * Proceed without it, having said so.
     *
     * <p>For development on a platform whose JDK has no {@code openat}, for tests, and for
     * data whose confinement is not what the deployment is relying on. The store still refuses
     * traversal, absolute paths and keys reached through symbolic links — everything that is a
     * question about a <em>name</em> still holds. What is gone is the guarantee under a
     * concurrent attacker who can create names inside the root, which is a narrower threat
     * than the one most callers have and is not nothing.
     *
     * <p>Logged once at construction, at WARN, naming the platform.
     */
    BEST_EFFORT;

    /**
     * Whether {@code directory} can be descended with {@code openat}.
     *
     * <p>Asked of the directory the store will actually use, not of the platform in the
     * abstract. Whether a {@link SecureDirectoryStream} comes back is a property of the
     * filesystem provider serving that path — a root on a mounted volume, an in-memory
     * filesystem in a test, or a provider a caller supplied can each answer differently on the
     * same JVM, and a platform-wide probe would report the wrong one.
     *
     * @throws IOException if the directory cannot be opened at all
     */
    public static boolean isAvailableFor(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return stream instanceof SecureDirectoryStream;
        }
    }
}
