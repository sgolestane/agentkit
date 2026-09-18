package dev.agentkit.host.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Which version of a repository a directory holds: its commit, marked {@code -dirty} when the working tree has
 * changes the commit does not, so a conversation pinned to it never claims a commit it did not run.
 */
public final class GitVersion {

    /** The version of a directory that is not a Git checkout. */
    public static final String UNVERSIONED = "unversioned";

    private GitVersion() {
    }

    /** The commit checked out in {@code dir}, with {@code -dirty} if anything differs; {@link #UNVERSIONED} otherwise. */
    public static String of(Path dir) {
        String head = git(dir, "rev-parse", "HEAD");
        if (head == null || !head.matches("[0-9a-f]{40,64}")) {
            return UNVERSIONED;
        }
        String status = git(dir, "status", "--porcelain", "--untracked-files=normal", "--", ".");
        return status == null || !status.isEmpty() ? head + "-dirty" : head;
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
