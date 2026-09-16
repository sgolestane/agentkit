package dev.agentkit.core.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

/**
 * Whether this platform has the {@code openat} family that {@code PinnedRead} and
 * {@code PinnedDirectory} descend with.
 *
 * <h2>What this was written to make visible</h2>
 *
 * <p>Both of those classes disclosed their fallback as: "where the platform has no
 * {@code openat} — {@code Files.newDirectoryStream} returns something that is not a
 * {@link SecureDirectoryStream}, which on this JDK means <strong>Windows</strong> … it cannot
 * be exercised on a Unix runner."
 *
 * <p>That is wrong, and it was wrong in the direction that matters. Measured on macOS:
 *
 * <pre>
 * os                       = Mac OS X
 * stream class             = sun.nio.fs.UnixDirectoryStream
 * is SecureDirectoryStream = false
 * </pre>
 *
 * <p>Only Linux gets {@code UnixSecureDirectoryStream}. On macOS the pinned descent is
 * <em>inactive</em> — the containment races the README reports as closed (0/0/0) are closed on
 * the CI runner and open on every developer's Mac, and the memory store's errors fall back to
 * the "Memory operation failed." dead end that #115 and #145 exist to remove.
 *
 * <p>The tests that would have said so were the ones everybody had learned to ignore, because
 * they were red for a <em>different</em> reason as well (#360's symlink cause, fixed by
 * {@link CanonicalTempDir}). Two unrelated faults in one ignored file is how a wrong security
 * disclosure survives two years.
 *
 * <p>So tests that assert pinned behaviour now <em>skip with this reason</em> rather than fail:
 * a Mac's test run says the descent is inactive, in words, every time. What to do about it —
 * refuse, warn, or accept — is #396 and is not a decision a test fixture should make.
 */
public final class Pinning {

    /** The sentence a skipped test prints, so the reason is in the output rather than lost. */
    public static final String WHY = "this platform has no SecureDirectoryStream, so the "
            + "pinned openat descent is INACTIVE and the path-based fallback is what runs "
            + "— see #396";

    private Pinning() {
    }

    /**
     * Whether a directory here can be descended with {@code openat}.
     *
     * <p>Asked once. It is a property of the JDK and the platform, not of the directory being
     * probed, and every test that skips on it would otherwise create and unlink a temporary
     * directory to learn the same answer.
     */
    public static boolean available() {
        return AVAILABLE;
    }

    private static final boolean AVAILABLE = probe();

    private static boolean probe() {
        try {
            Path probe = Files.createTempDirectory("pinning-probe");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(probe)) {
                return stream instanceof SecureDirectoryStream;
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException cannotAsk) {
            throw new UncheckedIOException("could not probe for a pinned descent", cannotAsk);
        }
    }
}
