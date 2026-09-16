package dev.agentkit.core.util;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Feature;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;

/**
 * An in-memory filesystem that spells paths the way Windows does, so the half of the path
 * pipeline this repository's runners never reach has one (#81).
 *
 * <p>Every test in every module runs on POSIX. The bug {@code ResourcePaths} exists for
 * (#78) was reachable only where a backslash separates, and the composition that carried it
 * — {@code getPath} then {@code normalize} then the separator rewrite — cannot be expressed
 * as an assertion on this platform: {@code Path.of("refs\\steps.md")} is one component here
 * no matter what flag is passed afterwards. Jimfs supplies the missing rules.
 *
 * <h2>What this is and is not</h2>
 *
 * <p><strong>It is the path syntax, and the case-folding that comes with it.</strong>
 * Separator {@code \}, drive-letter roots, {@code /} accepted on input, and lookup that
 * folds ASCII case — measured, not assumed: {@code Files.exists(base.resolve("steps.md"))}
 * answers true for a file created as {@code Steps.md}, and {@code toRealPath} gives back
 * {@code Steps.md}. That is the caveat {@code ResourcePaths} and {@link SafePaths} both
 * carry, and these tests do not attempt to settle it — they only stop pretending the
 * separator question is settled.
 *
 * <p><strong>It is not Windows.</strong> No ADS, no reparse points, no share paths, no
 * {@code CreateFile} sharing semantics. A test here says what the JDK's path abstraction
 * does with Windows syntax, not what a Windows kernel does.
 */
public final class WindowsFilesystem {

    private WindowsFilesystem() {
    }

    /**
     * A Windows-spelling filesystem with no {@code openat}, which is what a real Windows
     * JDK gives too.
     *
     * <p>{@code Files.newDirectoryStream} here answers a {@code DowngradedDirectoryStream}
     * rather than a {@link java.nio.file.SecureDirectoryStream} — Jimfs gates that behind
     * {@link Feature#SECURE_DIRECTORY_STREAM}, which {@link Configuration#windows()} does
     * not enable. So {@link PinnedRead#isPinned()} answers false and the read falls back to
     * the lexical path, which is exactly the branch {@code PinnedRead} documents as
     * unexercisable on a Unix runner. A test built on this one is testing the fallback, and
     * must say so rather than claim it covers the pinned descent.
     */
    public static FileSystem withoutOpenat() {
        return Jimfs.newFileSystem(Configuration.windows());
    }

    /**
     * A Windows-spelling filesystem that does have {@code openat}, so the pinned descent
     * runs against backslash-separated components.
     *
     * <p><strong>No real platform is this</strong>, and it is not offered as a simulation of
     * one. It is the only way to ask the one question that matters here — does the descent
     * walk the components a Windows-syntax path names — without also changing the separator.
     * Every containment property the descent has is already tested on the real POSIX
     * filesystem by {@code PinnedReadTest}; what is new here is only the spelling.
     */
    public static FileSystem withOpenat() {
        return Jimfs.newFileSystem(Configuration.windows().toBuilder()
                .setSupportedFeatures(Feature.LINKS, Feature.SYMBOLIC_LINKS,
                        Feature.SECURE_DIRECTORY_STREAM, Feature.FILE_CHANNEL)
                .build());
    }
}
