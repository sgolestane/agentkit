package dev.agentkit.host;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** A copy of the example repository in {@code src/test/resources/repos/acme}, for a test to change. */
final class RepoFixture {

    private final Path root;

    private RepoFixture(Path root) {
        this.root = root;
    }

    static RepoFixture copyInto(Path dir) {
        try {
            Path source = Path.of(RepoFixture.class.getClassLoader().getResource("repos/acme").toURI());
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.toList()) {
                    Path target = dir.resolve(source.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(file, target);
                    }
                }
            }
            return new RepoFixture(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    Path root() {
        return root;
    }

    RepoFixture write(String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String read(String relative) {
        try {
            return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Commits everything in the checkout, making it a Git repository first if it is not one; returns the commit. */
    String commit(String message) {
        if (!java.nio.file.Files.isDirectory(root.resolve(".git"))) {
            git("init", "-q");
        }
        git("add", "-A", ".");
        git("-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "--allow-empty", "-m", message);
        return git("rev-parse", "HEAD").strip();
    }

    private String git(String... args) {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git", "-C", root.toString()));
        command.addAll(java.util.List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Replaces the first occurrence of {@code from} in a file. */
    RepoFixture edit(String relative, String from, String to) {
        String content = read(relative);
        if (!content.contains(from)) {
            throw new IllegalArgumentException(relative + " does not contain " + from);
        }
        return write(relative, content.replaceFirst(java.util.regex.Pattern.quote(from),
                java.util.regex.Matcher.quoteReplacement(to)));
    }
}
