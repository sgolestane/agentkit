package dev.agentkit.host.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.eval.CheckOutcome;
import dev.agentkit.host.AgentHost;
import dev.agentkit.host.HostedAgent;
import dev.agentkit.host.Rehearsal;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.GitVersion;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Rehearses an organization's agents for a pull request: each changed agent's eval cases ({@code evals.yaml}), as real
 * conversations with the connectors, the model and the people the cases name — with every tool that could change
 * something refused and recorded ({@link Rehearsal}). The report says, per case, whether its checks held, and what the
 * agent would have done, which is what a reviewer of a change to a prompt or a policy needs to see.
 *
 * <pre>
 * OPENROUTER_API_KEY=sk-or-... AGENTKIT_SECRET_ACME_...=... AGENTKIT_REHEARSE_SINCE=origin/main \
 *   ./mvnw -q -pl agentkit-host exec:exec -Dexec.mainClass=dev.agentkit.host.cli.Rehearse -Dexec.appArgs=$PWD/orgs/acme
 * </pre>
 *
 * <p>Configuration: {@code AGENTKIT_REHEARSE_SINCE}, a Git ref: only agents whose files changed since it are
 * rehearsed, and every agent when {@code org.yaml} or a connector changed; {@code AGENTKIT_REHEARSE_AGENTS}, agent ids
 * to rehearse instead; {@code AGENTKIT_REHEARSE_REPORT}, a file for the results as JSON;
 * {@code AGENTKIT_REHEARSE_CASE_SECONDS} (default 300), how long one case may take. Secrets and local connectors as for
 * the host. Under GitHub Actions a failed check is an annotation on {@code evals.yaml}, and the report is written to
 * the job's summary.
 *
 * <p>Exits 0 when every case held, 1 when one did not, and 2 when nothing could be rehearsed.
 */
public final class Rehearse {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    private Rehearse() {
    }

    public static void main(String[] args) {
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        Optional<LlmClient> llm = key == null || key.isBlank() ? Optional.empty()
                : Optional.of(OpenRouterLlmClient.builder(key).title("agentkit rehearsal").build());
        System.exit(run(List.of(args), System.getenv(), System.out, llm));
    }

    /** Rehearses; returns the exit status. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, Optional<LlmClient> llm) {
        List<String> given = args.stream().filter(a -> a != null && !a.isBlank()).toList();
        String dir = !given.isEmpty() ? given.get(0) : env.get("AGENTKIT_VALIDATE_REPO");
        if (dir == null || dir.isBlank()) {
            out.println("Name the repository to rehearse: an argument, or AGENTKIT_VALIDATE_REPO.");
            return 2;
        }
        if (llm.isEmpty()) {
            out.println("A rehearsal talks to the agents' model: set " + OpenRouterLlmClient.API_KEY_ENV + ".");
            return 2;
        }
        Path repoDir = Path.of(dir.strip()).toAbsolutePath();
        boolean github = "true".equalsIgnoreCase(env.get("GITHUB_ACTIONS"));
        String prefix = env.getOrDefault("AGENTKIT_VALIDATE_PATH_PREFIX", "");
        String version = GitVersion.of(repoDir);
        String org = orgOf(repoDir);
        String secretPrefix = "AGENTKIT_SECRET_" + org.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
        Secrets secrets = name -> Optional.ofNullable(env.get(secretPrefix + name)).filter(v -> !v.isBlank());
        boolean allowLocal = "true".equalsIgnoreCase(env.get("AGENTKIT_HOST_ALLOW_LOCAL_CONNECTORS"));
        Duration perCase = Duration.ofSeconds(Long.parseLong(env.getOrDefault("AGENTKIT_REHEARSE_CASE_SECONDS", "300")
                .strip()));

        try (AgentHost host = AgentHost.open(repoDir, version, new AgentHost.Options(secrets, Map.of(), allowLocal))) {
            Set<String> chosen = chosen(host, repoDir, env, out);
            if (chosen.isEmpty()) {
                out.println(host.repo().org() + " @ " + version + ": no agent changed; nothing to rehearse.");
                summary(env, "## Rehearsal of " + host.repo().org() + "\n\nNo agent changed; nothing to rehearse.\n");
                return 0;
            }
            Rehearsal rehearsal = new Rehearsal(host, llm.get(), Instant::now, perCase);
            List<Rehearsal.Result> results = new ArrayList<>();
            List<String> untested = new ArrayList<>();
            for (String id : chosen) {
                HostedAgent agent = host.agent(id).orElseThrow();
                if (agent.definition().evals().isEmpty()) {
                    untested.add(id);
                    out.println("\n" + id + ": no eval cases (agents/" + id + "/evals.yaml); nothing rehearsed.");
                    if (github) {
                        out.println("::warning file=" + Validate.property(prefix + "agents/" + id + "/agent.yaml")
                                + ",title=No eval cases::" + Validate.message(id + " changed and has no evals.yaml, "
                                + "so this pull request cannot show what the change does."));
                    }
                    continue;
                }
                out.println("\n" + id + " — " + agent.definition().name() + ": " + agent.definition().evals().size()
                        + " case(s)");
                out.flush();
                for (Rehearsal.Result result : rehearsal.rehearse(agent)) {
                    print(result, out);
                    if (github) {
                        annotate(result, prefix, out);
                    }
                    results.add(result);
                    out.flush();
                }
            }
            long failed = results.stream().filter(r -> !r.passed()).count();
            out.println("\n" + host.repo().org() + " @ " + version + ": " + (results.size() - failed) + " of "
                    + results.size() + " case(s) held" + (untested.isEmpty() ? "" : "; no cases for " + untested) + ".");
            summary(env, markdown(host.repo().org(), version, results, untested));
            report(env, host.repo().org(), version, results, untested, out);
            return failed == 0 ? 0 : 1;
        } catch (DefinitionException e) {
            return Validate.report(e.problems(), out, github, prefix) == 0 ? 0 : 2;
        }
    }

    /** The agents to rehearse: named, or changed since a ref, or all. */
    private static Set<String> chosen(AgentHost host, Path repoDir, Map<String, String> env, PrintStream out) {
        Set<String> all = host.agents().keySet();
        String named = env.get("AGENTKIT_REHEARSE_AGENTS");
        if (named != null && !named.isBlank()) {
            Set<String> wanted = new LinkedHashSet<>(Arrays.stream(named.split("[\\s,]+")).filter(s -> !s.isBlank())
                    .toList());
            wanted.stream().filter(id -> !all.contains(id))
                    .forEach(id -> out.println("There is no agent " + id + "; it is left out."));
            wanted.retainAll(all);
            return wanted;
        }
        String since = env.get("AGENTKIT_REHEARSE_SINCE");
        if (since == null || since.isBlank()) {
            return new LinkedHashSet<>(all);
        }
        Optional<List<String>> changed = changedFiles(repoDir, since.strip());
        if (changed.isEmpty()) {
            out.println("Could not tell what changed since " + since.strip() + "; rehearsing every agent.");
            return new LinkedHashSet<>(all);
        }
        Set<String> agents = new LinkedHashSet<>();
        for (String file : changed.get()) {
            String[] parts = file.split("/");
            if (parts.length >= 2 && parts[0].equals("agents")) {
                agents.add(parts[1]);
            } else {
                out.println(file + " changed, which every agent depends on; rehearsing them all.");
                return new LinkedHashSet<>(all);
            }
        }
        agents.retainAll(all);
        return agents;
    }

    /**
     * The files that differ from {@code since}, and those Git does not track yet, relative to the repository's
     * directory; empty if Git cannot say.
     */
    static Optional<List<String>> changedFiles(Path repoDir, String since) {
        Optional<List<String>> changed = git(repoDir, "diff", "--name-only", "--relative", since, "--", ".");
        Optional<List<String>> added = git(repoDir, "ls-files", "--others", "--exclude-standard", "--", ".");
        if (changed.isEmpty() || added.isEmpty()) {
            return Optional.empty();
        }
        List<String> all = new ArrayList<>(changed.get());
        added.get().stream().filter(f -> !all.contains(f)).forEach(all::add);
        return Optional.of(all);
    }

    private static Optional<List<String>> git(Path repoDir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repoDir.toString()));
        command.addAll(List.of(args));
        try {
            Process git = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!git.waitFor(60, TimeUnit.SECONDS) || git.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static void print(Rehearsal.Result result, PrintStream out) {
        out.println("  " + (result.passed() ? "PASS" : "FAIL") + "  " + result.evalCase().name() + " (as "
                + result.evalCase().as() + ", " + result.state().toLowerCase(Locale.ROOT) + ", " + result.millis() / 1000
                + "s)");
        for (CheckOutcome outcome : result.outcomes()) {
            out.println("        " + (outcome.passed() ? "✓ " : "✗ ") + outcome.name()
                    + (outcome.passed() ? "" : " — " + outcome.detail()));
        }
        for (Rehearsal.Call call : result.wouldHave()) {
            out.println("        would " + call.would() + ": " + call.tool() + " " + call.arguments());
        }
    }

    private static void annotate(Rehearsal.Result result, String prefix, PrintStream out) {
        if (result.passed()) {
            return;
        }
        String failures = result.outcomes().stream().filter(o -> !o.passed())
                .map(o -> o.name() + ": " + o.detail()).collect(Collectors.joining("\n"));
        out.println("::error file=" + Validate.property(prefix + "agents/" + result.agent() + "/evals.yaml")
                + ",title=" + Validate.property(result.agent() + " / " + result.evalCase().name()) + "::"
                + Validate.message(failures));
    }

    /** The report as Markdown, for a pull request's summary. */
    static String markdown(String org, String version, List<Rehearsal.Result> results, List<String> untested) {
        StringBuilder md = new StringBuilder("## Rehearsal of " + org + "\n\n");
        long held = results.stream().filter(Rehearsal.Result::passed).count();
        md.append("At `").append(version).append("`: **").append(held).append(" of ").append(results.size())
                .append("** case(s) held. Every tool that could change something was refused and recorded, so ")
                .append("\"would\" below is what the agent set out to do.\n\n");
        md.append("| Agent | Case | As | Result |\n|---|---|---|---|\n");
        for (Rehearsal.Result r : results) {
            md.append("| ").append(r.agent()).append(" | ").append(r.evalCase().name()).append(" | ")
                    .append(r.evalCase().as()).append(" | ").append(r.passed() ? "✅ held" : "❌ failed").append(" |\n");
        }
        if (!untested.isEmpty()) {
            md.append("\n⚠️ Changed with no eval cases: ").append(String.join(", ", untested)).append("\n");
        }
        for (Rehearsal.Result r : results) {
            md.append("\n<details").append(r.passed() ? "" : " open").append("><summary><b>").append(r.agent())
                    .append(" / ").append(r.evalCase().name()).append("</b> — ")
                    .append(r.passed() ? "held" : "failed").append("</summary>\n\n");
            md.append("**Said:** ").append(r.evalCase().say() != null ? oneLine(r.evalCase().say())
                    : "the form, with " + r.evalCase().input()).append("\n\n");
            if (!r.plan().isEmpty()) {
                md.append("**Plan:**\n");
                for (int i = 0; i < r.plan().size(); i++) {
                    md.append(i + 1).append(". ").append(oneLine(r.plan().get(i))).append('\n');
                }
                md.append('\n');
            }
            if (!r.wouldHave().isEmpty()) {
                md.append("**Would have:**\n");
                r.wouldHave().forEach(c -> md.append("- ").append(c.would()).append(": `").append(c.tool())
                        .append("` ").append(oneLine(String.valueOf(c.arguments()))).append('\n'));
                md.append('\n');
            }
            if (!r.questions().isEmpty()) {
                md.append("**Asked:** ").append(r.questions().stream().map(Rehearse::oneLine)
                        .collect(Collectors.joining(" / "))).append("\n\n");
            }
            md.append("**Checks:**\n");
            r.outcomes().forEach(o -> md.append("- ").append(o.passed() ? "✅ " : "❌ ").append(o.name())
                    .append(o.passed() ? "" : " — " + oneLine(o.detail())).append('\n'));
            md.append("\n**Answer:**\n\n> ").append(r.answer().isBlank() ? "(none)" : r.answer().strip()
                    .replace("\n", "\n> ")).append("\n\n</details>\n");
        }
        return md.toString();
    }

    private static void summary(Map<String, String> env, String markdown) {
        String file = env.get("GITHUB_STEP_SUMMARY");
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(file), markdown + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write the job summary: " + e.getMessage());
        }
    }

    private static void report(Map<String, String> env, String org, String version, List<Rehearsal.Result> results,
                               List<String> untested, PrintStream out) {
        String file = env.get("AGENTKIT_REHEARSE_REPORT");
        if (file == null || file.isBlank()) {
            return;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("org", org);
        report.put("version", version);
        report.put("at", Instant.now().toString());
        report.put("held", results.stream().filter(Rehearsal.Result::passed).count());
        report.put("cases", results.size());
        report.put("untested", untested);
        report.put("results", results.stream().map(Rehearse::json).toList());
        try {
            Files.writeString(Path.of(file), JSON.writeValueAsString(report), StandardCharsets.UTF_8);
            out.println("The report is in " + file + ".");
        } catch (IOException e) {
            out.println("Could not write the report to " + file + ": " + e.getMessage());
        }
    }

    private static Map<String, Object> json(Rehearsal.Result r) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("agent", r.agent());
        json.put("case", r.evalCase().name());
        json.put("as", r.evalCase().as());
        json.put("passed", r.passed());
        json.put("state", r.state());
        json.put("millis", r.millis());
        json.put("plan", r.plan());
        json.put("questions", r.questions());
        json.put("calls", r.calls());
        json.put("checks", r.outcomes());
        json.put("answer", r.answer());
        return json;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /** The organization's name from its {@code org.yaml}, for its secrets' prefix, before anything is loaded. */
    private static String orgOf(Path repoDir) {
        try {
            for (String line : Files.readAllLines(repoDir.resolve("org.yaml"), StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.startsWith("org:")) {
                    return stripped.substring("org:".length()).strip().replaceAll("^[\"']|[\"']$", "");
                }
            }
        } catch (IOException ignored) {
            // The load that follows reports a missing or unreadable org.yaml properly.
        }
        return "";
    }
}
