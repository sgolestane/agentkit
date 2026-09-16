package dev.agentkit.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * A {@code @TempDir} whose path is the one the kernel would give you.
 *
 * <h2>Fifteen tests were red on macOS and nobody saw it</h2>
 *
 * <p>They passed in CI, which is {@code ubuntu-latest}, so nothing caught it. macOS's
 * {@code /var} is a symbolic link to {@code /private/var}, and JUnit's default
 * {@code @TempDir} hands back a path under {@code /var/folders/…}. The classes under test —
 * {@code SafePaths}, {@code PinnedRead}, the memory store — <em>resolve symlinks, which is
 * their entire job</em>, so they return the real path and the assertions compared it against
 * the unresolved one they had been given:
 *
 * <pre>
 * expected: /var/folders/8s/…/junit-…/base/forms/w2.txt
 *  but was: /private/var/folders/8s/…/junit-…/base/forms/w2.txt
 * </pre>
 *
 * <p>Two identical files. The test was wrong, not the code.
 *
 * <h2>Why here rather than in fifteen fixtures</h2>
 *
 * <p>The alternative was {@code toRealPath()} in every {@code @BeforeEach} and every
 * {@code @TempDir} parameter — thirty-odd call sites, each of which a later test can forget.
 * Canonicalising at the source makes it impossible to forget, and it is the right default on
 * its own terms: a test that is handed a directory and then compares paths against what a
 * path-resolving API returns wants the canonical name of that directory, on every platform.
 *
 * <p>Registered as the default for the whole module in {@code junit-platform.properties}, so
 * no test opts in.
 *
 * <p><strong>It does not fix everything that was red.</strong> Two of the fifteen fail for
 * reasons that have nothing to do with symlinks — a path-length ceiling and a stack depth —
 * and are fixed where they are.
 */
public final class CanonicalTempDir implements TempDirFactory {

    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context)
            throws IOException {
        Path made = Files.createTempDirectory("junit-");
        // toRealPath rather than toAbsolutePath: only the first follows symbolic links, and
        // the link is the whole problem. It cannot fail — the directory was just created.
        return made.toRealPath();
    }

    /**
     * Nothing to release. JUnit deletes the directory itself — a factory only chooses where it
     * lives, and one that deleted it here would be racing the framework for the same tree.
     */
    @Override
    public void close() {
    }
}
