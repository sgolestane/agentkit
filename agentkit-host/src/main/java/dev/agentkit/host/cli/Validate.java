package dev.agentkit.host.cli;

import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.host.AgentHost;
import dev.agentkit.host.HostedAgent;
import dev.agentkit.host.Principal;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.GitVersion;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RepoLoader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Checks an organization's repository of agents the way the host will load it — for a pull request's check, before
 * anything is merged.
 *
 * <pre>
 * ./mvnw -q -pl agentkit-host exec:exec -Dexec.mainClass=dev.agentkit.host.cli.Validate -Dexec.appArgs=$PWD/orgs/acme
 * </pre>
 *
 * <p>By default it reads the files: every field, name, effect, reference and prompt file. With
 * {@code AGENTKIT_VALIDATE_CONNECT=true} it also connects to the connectors, with secrets from
 * {@code AGENTKIT_SECRET_<ORG>_<NAME>}, and assembles every agent: tools that exist, confirmations and bindings that
 * fit, no grant without a person — and says what each agent can do, which is what a reviewer of the pull request
 * needs to see. Every problem is printed with its file and field; under GitHub Actions each is also an annotation on
 * the pull request. Exits 1 if there is any.
 */
public final class Validate {

    private Validate() {
    }

    public static void main(String[] args) {
        System.exit(run(List.of(args), System.getenv(), System.out));
    }

    /** Validates; returns the exit status. */
    static int run(List<String> args, Map<String, String> env, PrintStream out) {
        List<String> given = args.stream().filter(a -> a != null && !a.isBlank()).toList();
        String dir = !given.isEmpty() ? given.get(0) : env.get("AGENTKIT_VALIDATE_REPO");
        if (dir == null || dir.isBlank()) {
            out.println("Name the repository to validate: an argument, or AGENTKIT_VALIDATE_REPO.");
            return 2;
        }
        Path repoDir = Path.of(dir.strip()).toAbsolutePath();
        boolean connect = "true".equalsIgnoreCase(env.get("AGENTKIT_VALIDATE_CONNECT"));
        boolean github = "true".equalsIgnoreCase(env.get("GITHUB_ACTIONS"));
        String prefix = env.getOrDefault("AGENTKIT_VALIDATE_PATH_PREFIX", "");
        String version = GitVersion.of(repoDir);

        OrgRepo repo;
        try {
            repo = RepoLoader.load(repoDir, version);
        } catch (DefinitionException e) {
            return report(e.problems(), out, github, prefix);
        }
        if (!connect) {
            out.println(repo.org() + " @ " + version + ": " + repo.agents().size() + " agent(s), "
                    + repo.connectors().size() + " connector(s); the files are valid.");
            repo.agents().values().forEach(a -> out.println("  " + a.id() + " — " + a.name() + selectors(a)
                    + (a.evals().isEmpty() ? "; no eval cases" : "; " + a.evals().size() + " eval case(s)")));
            out.println(routing(repo));
            out.println("Connectors were not reached; set AGENTKIT_VALIDATE_CONNECT=true to check the agents against them.");
            return 0;
        }

        String secretPrefix = "AGENTKIT_SECRET_" + repo.org().toUpperCase(Locale.ROOT).replace('-', '_') + "_";
        Secrets secrets = name -> java.util.Optional.ofNullable(env.get(secretPrefix + name)).filter(v -> !v.isBlank());
        boolean allowLocal = "true".equalsIgnoreCase(env.get("AGENTKIT_HOST_ALLOW_LOCAL_CONNECTORS"));
        try (AgentHost host = AgentHost.open(repoDir, version, new AgentHost.Options(secrets, Map.of(), allowLocal))) {
            List<DefinitionException.Problem> problems = new ArrayList<>();
            for (HostedAgent agent : host.agents().values()) {
                agent.unavailable().ifPresent(why -> problems.add(new DefinitionException.Problem(
                        "agents/" + agent.definition().id() + "/agent.yaml", "", "could not be checked: " + why)));
            }
            if (!problems.isEmpty()) {
                return report(problems, out, github, prefix);
            }
            out.println(repo.org() + " @ " + version + ": " + host.agents().size() + " agent(s) checked against their "
                    + "connectors; valid.");
            host.agents().values().forEach(agent -> describe(agent, out));
            out.println("\n" + routing(repo));
            return 0;
        } catch (DefinitionException e) {
            return report(e.problems(), out, github, prefix);
        }
    }

    /** What an agent can do, by effect: the part of a change a reviewer must not miss. */
/** What the repository says about routing messages nobody sent to a particular agent. */
    static String routing(dev.agentkit.host.repo.OrgRepo repo) {
        if (!repo.router().enabled()) {
            return "Routing: off; a person chooses an agent to start a conversation.";
        }
        return "Routing: on" + (repo.router().model() == null ? "" : ", with " + repo.router().model())
                + (repo.router().prompt().isBlank() ? "" : ", with the organization's own instructions") + "; "
                + (repo.routing().isEmpty() ? "no routing cases (routing.yaml)"
                        : repo.routing().size() + " routing case(s)") + ".";
    }

        private static void describe(HostedAgent agent, PrintStream out) {
        AgentDefinition definition = agent.definition();
        out.println("\n  " + definition.id() + " — " + definition.name() + " ("
                + definition.pattern().name().toLowerCase(Locale.ROOT).replace('_', '-') + "; audience: "
                + String.join(", ", definition.audience()) + ")");
        Principal nobody = new Principal("validate", "validate", "validate@example.invalid", Set.of(), Map.of());
        DeclaredTools tools = agent.tools(nobody);
        Map<ToolEffect, List<String>> byEffect = new TreeMap<>();
        tools.entries().forEach(e -> byEffect.computeIfAbsent(e.declaration().effect(), k -> new ArrayList<>())
                .add(e.tool().name() + (agent.confirmed().contains(e.tool().name()) ? " [confirmed]" : "")));
        byEffect.forEach((effect, names) -> out.println("    " + effect.wire() + ": " + String.join(", ", names)));
        definition.bind().forEach((ref, arguments) -> out.println("    bound: " + ref + " " + arguments));
        if (definition.deferred() != null) {
            out.println("    deferred work as " + definition.deferred().actor() + ", about: "
                    + String.join(", ", definition.deferred().subjects().keySet()));
        }
        if (definition.input() != null) {
            out.println("    started from a form: " + definition.input().fields().stream()
                    .map(f -> f.name() + (f.required() ? "*" : "")).collect(Collectors.joining(", ")));
        }
        if (!definition.mcpDirect().isEmpty()) {
            out.println("    offered directly over MCP: " + definition.mcpDirect().stream().map(Object::toString)
                    .collect(Collectors.joining(", ")));
        }
        out.println("    eval cases: " + (definition.evals().isEmpty() ? "none — a pull request cannot show what a change "
                + "to it does" : definition.evals().stream().map(c -> c.name()).collect(Collectors.joining(", "))));
        Map<String, String> refused = agent.changing(List.of());
        if (!refused.isEmpty()) {
            out.println("    refused in a rehearsal: " + String.join(", ", refused.keySet()));
        }
    }

    private static String selectors(AgentDefinition agent) {
        return " (tools from " + agent.tools().stream().map(AgentDefinition.ToolSelector::connector).distinct()
                .collect(Collectors.joining(", ")) + ")";
    }

    static int report(List<DefinitionException.Problem> problems, PrintStream out, boolean github, String prefix) {
        out.println(problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + ":");
        for (DefinitionException.Problem p : problems) {
            out.println("  " + p);
            if (github) {
                out.println("::error file=" + property(prefix + p.file()) + ",title="
                        + property(p.where().isEmpty() ? p.file() : p.where()) + "::" + message(p.message()));
            }
        }
        return 1;
    }

    /** GitHub's workflow-command escaping for a message. */
    static String message(String text) {
        return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    /** GitHub's workflow-command escaping for a property, which also may not hold a colon or a comma. */
    static String property(String text) {
        return message(text).replace(":", "%3A").replace(",", "%2C");
    }
}
