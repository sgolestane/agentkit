package dev.agentkit.host.change;

import dev.agentkit.host.AgentHost;
import dev.agentkit.host.HostedAgent;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Principal;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.GitVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A change to an organization's agents proposed from the admin view, opened for review: never applied to what the host
 * runs. Git stays the one source of truth; the change is served once it is merged, like any other.
 *
 * <p>Before anything is opened, the change is checked the way the host would load it: applied to a copy of the version
 * the host runs, and that copy loaded against the organization's connectors — every file, every reference, every
 * tool, and no grant without a person. A change the host would refuse is refused here, with every problem, and nothing
 * is opened. What is opened says what the change does to each agent it touches, for its reviewer.
 *
 * <p>Only an agent's files may be proposed from here — {@code agents/<id>/…} — not {@code org.yaml} or a connector,
 * whose changes are an operator's, made in the repository.
 */
public final class Proposals {

    public static final int MAX_FILES = 20;
    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_TITLE = 120;

    private static final Pattern AGENT_FILE = Pattern.compile("agents/[a-z0-9][a-z0-9-]{0,62}(/[A-Za-z0-9_][A-Za-z0-9_.-]*)+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Function<OrgHost, Optional<ChangeProposer>> proposers;
    private final Function<String, AgentHost.Options> options;

    /**
     * @param proposers where an organization's proposals go; empty when it has nowhere
     * @param options   how an organization's repository is loaded, to check a proposal against its connectors
     */
    public Proposals(Function<OrgHost, Optional<ChangeProposer>> proposers, Function<String, AgentHost.Options> options) {
        this.proposers = Objects.requireNonNull(proposers, "proposers");
        this.options = Objects.requireNonNull(options, "options");
    }

    /** What happened to a proposal: opened, with where, or refused, with every reason. */
    public record Outcome(boolean opened, String branch, String url, String where, List<String> problems, String summary) {
        static Outcome refused(List<String> problems) {
            return new Outcome(false, null, null, null, List.copyOf(problems), "");
        }
    }

    /** Where the organization's proposals go, or empty with why none can be made now. */
    public Availability availability(OrgHost org) {
        Optional<ChangeProposer> proposer = proposers.apply(org);
        if (proposer.isEmpty()) {
            return new Availability(false, null, "This organization's org.yaml names no repository to open changes on, "
                    + "or the host has no GITHUB_TOKEN for it.");
        }
        String version = org.current().repo().version();
        if (!GitVersion.isCommit(version)) {
            return new Availability(false, proposer.get().where(), "The host is running " + version
                    + ", which is not a commit, so a change has nothing to be built on. Commit or discard what is "
                    + "uncommitted in its checkout.");
        }
        if (!version.equals(GitVersion.of(org.checkout()))) {
            return new Availability(false, proposer.get().where(), "The checkout has moved on from " + version
                    + ", the version the host runs; try again once the host has loaded it.");
        }
        return new Availability(true, proposer.get().where(), null);
    }

    /** Whether proposals can be made for an organization now, where they go, and if not, why. */
    public record Availability(boolean enabled, String where, String why) {
    }

    /**
     * Checks {@code files} against the version the host runs and, if the host would load the result, opens it.
     *
     * @param files paths relative to the organization's repository, under {@code agents/}, and their new content
     */
    public Outcome propose(OrgHost org, Principal by, String title, String description, Map<String, String> files) {
        Availability availability = availability(org);
        if (!availability.enabled()) {
            return Outcome.refused(List.of(availability.why()));
        }
        List<String> problems = new ArrayList<>();
        String cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty() || cleanTitle.length() > MAX_TITLE || cleanTitle.contains("\n")) {
            problems.add("A proposal has a title of one line, up to " + MAX_TITLE + " characters.");
        }
        if (files == null || files.isEmpty()) {
            problems.add("A proposal changes at least one file.");
        } else if (files.size() > MAX_FILES) {
            problems.add("A proposal changes at most " + MAX_FILES + " files.");
        } else {
            files.forEach((path, content) -> check(path, content).ifPresent(problems::add));
        }
        if (!problems.isEmpty()) {
            return Outcome.refused(problems);
        }

        AgentHost running = org.current();
        Path copy = null;
        try {
            copy = copyOf(org.checkout());
            Set<String> changed = new TreeSet<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = copy.resolve(file.getKey());
                String before = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : null;
                if (!file.getValue().equals(before)) {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
                    changed.add(file.getKey());
                }
            }
            if (changed.isEmpty()) {
                return Outcome.refused(List.of("Nothing changes: every file is as the running version has it."));
            }
            String summary;
            try (AgentHost proposed = AgentHost.open(copy, "proposed", options.apply(org.org()))) {
                summary = summary(running, proposed, changed);
            } catch (DefinitionException e) {
                return Outcome.refused(e.problems().stream().map(Object::toString).toList());
            }
            Map<String, String> changedFiles = new LinkedHashMap<>();
            changed.forEach(path -> changedFiles.put(path, files.get(path)));
            String body = "Proposed in the agent host's admin view by " + by.email() + ".\n\n"
                    + (description == null || description.isBlank() ? "" : description.strip() + "\n\n")
                    + "### What changes\n\n" + summary + "\n\nThe host loaded this change against " + org.org()
                    + "'s connectors before opening it. The pull request's checks rehearse the agents it touches.";
            ChangeProposer proposer = proposers.apply(org).orElseThrow();
            ChangeProposer.Opened opened = proposer.open(running.repo().version(), branch(cleanTitle), cleanTitle, body,
                    changedFiles);
            return new Outcome(true, opened.branch(), opened.url(), proposer.where(), List.of(), summary);
        } catch (ChangeProposer.ProposalException e) {
            return Outcome.refused(List.of(e.getMessage()));
        } catch (IOException e) {
            return Outcome.refused(List.of("The change could not be checked: " + e.getMessage()));
        } finally {
            if (copy != null) {
                delete(copy);
            }
        }
    }

    /** Why a file may not be proposed, if it may not. */
    static Optional<String> check(String path, String content) {
        if (path == null || !AGENT_FILE.matcher(path).matches() || path.contains("/.")) {
            return Optional.of(path + ": only an agent's own files, agents/<id>/…, may be proposed here");
        }
        if (content == null) {
            return Optional.of(path + ": has no content");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            return Optional.of(path + ": is larger than " + MAX_FILE_BYTES / 1024 + " KB");
        }
        if (content.indexOf('\0') >= 0) {
            return Optional.of(path + ": is not text");
        }
        return Optional.empty();
    }

    /** What the change does to each agent it touches, for its reviewer. */
    static String summary(AgentHost before, AgentHost after, Set<String> changed) {
        Map<String, List<String>> byAgent = new LinkedHashMap<>();
        changed.forEach(path -> byAgent.computeIfAbsent(path.split("/")[1], id -> new ArrayList<>())
                .add(path.substring(("agents/" + path.split("/")[1] + "/").length())));
        StringBuilder md = new StringBuilder();
        byAgent.forEach((id, paths) -> {
            Optional<HostedAgent> was = before.agent(id);
            Optional<HostedAgent> now = after.agent(id);
            md.append("**").append(id).append("**");
            if (now.isEmpty()) {
                md.append(": not an agent after this change (its agent.yaml is missing)\n");
                return;
            }
            md.append(was.isEmpty() ? " (a new agent)" : "").append(" — files: ").append(String.join(", ", paths))
                    .append('\n');
            Set<String> abilitiesBefore = was.map(Proposals::abilities).orElse(Set.of());
            Set<String> abilitiesAfter = abilities(now.get());
            difference(abilitiesAfter, abilitiesBefore).forEach(a -> md.append("- can now: ").append(a).append('\n'));
            difference(abilitiesBefore, abilitiesAfter).forEach(a -> md.append("- no longer: ").append(a).append('\n'));
            if (was.isPresent() && !was.get().definition().audience().equals(now.get().definition().audience())) {
                md.append("- audience: ").append(was.get().definition().audience()).append(" → ")
                        .append(now.get().definition().audience()).append('\n');
            }
            int casesBefore = was.map(a -> a.definition().evals().size()).orElse(0);
            int casesAfter = now.get().definition().evals().size();
            if (casesBefore != casesAfter) {
                md.append("- eval cases: ").append(casesBefore).append(" → ").append(casesAfter).append('\n');
            }
            if (abilitiesBefore.equals(abilitiesAfter) && was.isPresent()) {
                md.append("- what it can do is unchanged\n");
            }
        });
        return md.toString().stripTrailing();
    }

    /** An agent's abilities, one line each: a tool with its effect, and whether it is confirmed or bound. */
    private static Set<String> abilities(HostedAgent agent) {
        Set<String> abilities = new LinkedHashSet<>();
        agent.toolInfo().forEach(tool -> abilities.add(tool.effect() + " " + tool.connector() + "/" + tool.name()
                + (tool.confirmed() ? ", confirmed by the person" : "")
                + (tool.bound().isEmpty() ? "" : ", with " + tool.bound().entrySet().stream()
                        .map(e -> e.getKey() + " ← " + e.getValue()).collect(Collectors.joining(", ")))));
        if (agent.definition().deferred() != null) {
            abilities.add("schedule work for later, as " + agent.definition().deferred().actor());
        }
        return abilities;
    }

    private static List<String> difference(Set<String> a, Set<String> b) {
        return a.stream().filter(x -> !b.contains(x)).toList();
    }

    private static String branch(String title) {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return "agentkit/" + (slug.isEmpty() ? "change" : slug) + "-" + HexFormat.of().formatHex(bytes);
    }

    private static Path copyOf(Path checkout) throws IOException {
        Path copy = Files.createTempDirectory("agentkit-proposed-");
        try (Stream<Path> files = Files.walk(checkout)) {
            for (Path file : files.toList()) {
                Path relative = checkout.relativize(file);
                if (relative.toString().isEmpty() || relative.startsWith(".git")) {
                    continue;
                }
                Path target = copy.resolve(relative.toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        return copy;
    }

    private static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
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
