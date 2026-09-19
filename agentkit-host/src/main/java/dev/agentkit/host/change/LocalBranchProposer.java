package dev.agentkit.host.change;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Opens a change as a branch in the checkout's own Git repository — for development, where there is no GitHub to open
 * a pull request on. The branch is made in a worktree of its own, so the checkout the host runs is not touched: the
 * change is served once it is merged there, like any other.
 */
public final class LocalBranchProposer implements ChangeProposer {

    private final Path checkout;

    public LocalBranchProposer(Path checkout) {
        this.checkout = Objects.requireNonNull(checkout, "checkout");
    }

    @Override
    public String where() {
        return "branches in " + git(checkout, "rev-parse", "--show-toplevel").strip();
    }

    @Override
    public Opened open(String base, String branch, String title, String body, Map<String, String> files) {
        String prefix = git(checkout, "rev-parse", "--show-prefix").strip();
        Path worktree;
        try {
            worktree = Files.createTempDirectory("agentkit-proposal-");
            Files.delete(worktree);
        } catch (IOException e) {
            throw new ProposalException("Could not make a place to build the change: " + e.getMessage());
        }
        git(checkout, "worktree", "add", "--quiet", "-b", branch, worktree.toString(), base);
        try {
            List<String> paths = new ArrayList<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = worktree.resolve(prefix + file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
                paths.add(prefix + file.getKey());
            }
            List<String> add = new ArrayList<>(List.of("add", "--"));
            add.addAll(paths);
            git(worktree, add.toArray(String[]::new));
            git(worktree, "-c", "user.name=AgentKit host", "-c", "user.email=host@agentkit.invalid", "commit",
                    "--quiet", "-m", title + "\n\n" + body);
            return new Opened(branch, null);
        } catch (IOException e) {
            throw new ProposalException("Could not write the change: " + e.getMessage());
        } finally {
            try {
                git(checkout, "worktree", "remove", "--force", worktree.toString());
            } catch (ProposalException ignored) {
                delete(worktree);
            }
        }
    }

    private static String git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new ProposalException("git " + args[0] + " failed: " + output.strip());
            }
            return output;
        } catch (IOException e) {
            throw new ProposalException("git could not be run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProposalException("git was interrupted");
        }
    }

    private static void delete(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Litter in the temporary directory, at worst.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }
}
