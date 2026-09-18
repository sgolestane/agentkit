package dev.agentkit.host.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Which version of a repository a directory holds: its commit, or — when the working tree has changes the commit does
 * not — the commit marked {@code -dirty-} and a hash of those changes, so a conversation pinned to it never claims a
 * commit it did not run, and the same uncommitted tree is recognised as the same version.
 */
public final class GitVersion {

    /** The version of a directory that is not a Git checkout. */
    public static final String UNVERSIONED = "unversioned";

    private GitVersion() {
    }

    /** The commit checked out in {@code dir}, marked when anything differs; {@link #UNVERSIONED} outside Git. */
    public static String of(Path dir) {
        String head = git(dir, "rev-parse", "HEAD");
        if (head == null || !head.matches("[0-9a-f]{40,64}")) {
            return UNVERSIONED;
        }
        String status = git(dir, "status", "--porcelain", "--untracked-files=all", "--", ".");
        if (status != null && status.isEmpty()) {
            return head;
        }
        // Which changes, not only that there are some: the same uncommitted tree is the same version, and an edit to
        // it is a new one, so a checkout being worked on reloads when it changes and not on every look.
        String diff = git(dir, "diff", "HEAD", "--", ".");
        StringBuilder changes = new StringBuilder(String.valueOf(status)).append('\n').append(String.valueOf(diff));
        for (String line : String.valueOf(status).lines().toList()) {
            if (line.startsWith("?? ")) {
                try {
                    changes.append('\n').append(line).append('\n')
                            .append(java.nio.file.Files.readString(dir.resolve(line.substring(3).strip())));
                } catch (IOException | RuntimeException unreadable) {
                    changes.append("\n(unreadable)");
                }
            }
        }
        return head + "-dirty-" + sha256(changes.toString()).substring(0, 12);
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String git(Path dir, String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            return new String(out, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
