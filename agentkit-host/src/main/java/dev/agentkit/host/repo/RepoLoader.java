package dev.agentkit.host.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.host.models.Budget;
import dev.agentkit.host.models.HostLimits;
import dev.agentkit.host.models.ModelAccounts;
import dev.agentkit.host.repo.AgentDefinition.ToolRef;
import dev.agentkit.host.repo.AgentDefinition.ToolSelector;
import dev.agentkit.host.repo.DefinitionException.Problem;
import dev.agentkit.host.repo.OrgRepo.ConnectorSpec;
import dev.agentkit.host.repo.OrgRepo.DirectorySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads an organization's repository of agents from a directory — normally a checkout of its Git repository.
 *
 * <pre>
 * org.yaml                     org, model, provider, budget, directory, admins, repository, signIn
 * connectors/&lt;name&gt;.yaml      url or command, headers, trustAnnotations, authoritative, timeoutSeconds, tools
 * agents/&lt;id&gt;/agent.yaml       name, description, pattern, model, audience, prompt, tools, confirm, bind, limits
 * agents/&lt;id&gt;/…               the prompt files agent.yaml names
 * agents/&lt;id&gt;/evals.yaml       optional: the cases a pull request rehearses ({@link EvalCase})
 * </pre>
 *
 * <p>Everything a file says is checked here that can be checked without connecting to anything: required fields,
 * unknown fields (a misspelt {@code confrim} must not silently confirm nothing), names, effects, references between
 * files, and prompt files that stay inside the repository. Checks that need the connectors' tools — does a
 * confirmed tool exist, does a bound argument — happen when an agent is assembled. Every problem is collected
 * before anything is thrown.
 */
public final class RepoLoader {

    /** Agent ids and connector names: lowercase, digits and dashes, as a URL or a tool prefix can carry them. */
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private static final Pattern PRINCIPAL_PATH = Pattern.compile("principal\\.[A-Za-z_][A-Za-z0-9_]*");

    private static final Set<String> ORG_KEYS = Set.of("org", "model", "provider", "budget", "directory", "admins",
            "repository", "signIn", "router");
    private static final Set<String> ROUTER_KEYS = Set.of("enabled", "model", "prompt");
    private static final Set<String> ROUTING_KEYS = Set.of("cases");
    private static final Set<String> ROUTING_CASE_KEYS = Set.of("name", "as", "before", "say", "expect");
    private static final Set<String> ROUTING_BEFORE_KEYS = Set.of("say", "agent", "answer");
    private static final Set<String> ROUTING_EXPECT_KEYS = Set.of("agent", "answers", "asks", "form");
    private static final Set<String> SIGN_IN_KEYS = Set.of("issuer", "clientId", "emailClaim", "mcpAudience");
    private static final Set<String> REPOSITORY_KEYS = Set.of("github", "path", "base", "api");
    private static final Pattern GITHUB_REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Set<String> DIRECTORY_KEYS = Set.of("connector", "tool", "argument");
    private static final Set<String> CONNECTOR_KEYS = Set.of("url", "command", "headers", "trustAnnotations",
            "authoritative", "timeoutSeconds", "tools");
    private static final Set<String> AGENT_KEYS = Set.of("name", "description", "pattern", "model", "audience",
            "prompt", "tools", "confirm", "bind", "limits", "deferred", "mcp", "input", "plans", "before");
    private static final Set<String> CHECK_KEYS = Set.of("tool", "with");
    private static final Set<String> PLANS_KEYS = Set.of("reuse");
    private static final Set<String> REUSE_KEYS = Set.of("after", "recheckEvery", "sameWhen");
    private static final Set<String> INPUT_KEYS = Set.of("schema", "goal");
    private static final Set<String> MCP_KEYS = Set.of("direct");
    private static final Set<String> DEFERRED_KEYS = Set.of("prompt", "actor", "subjects");
    private static final Set<String> SUBJECT_KEYS = Set.of("tool", "argument");
    private static final Set<String> PROMPT_KEYS = Set.of("system", "policy");
    private static final Set<String> PLAN_EXECUTE_PROMPT_KEYS = Set.of("planner", "executor", "policy");
    private static final Set<String> SELECTOR_KEYS = Set.of("connector", "effects", "tools");
    private static final Set<String> LIMIT_KEYS = Set.of("maxSteps", "maxTokens");
    private static final Set<String> EVALS_KEYS = Set.of("cases");
    private static final Set<String> CASE_KEYS = Set.of("name", "as", "say", "input", "answers", "expect");
    private static final Set<String> EXPECT_KINDS = Set.of("calls", "never", "asks", "answer_contains", "judge");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static final int DEFAULT_MAX_STEPS = 20;
    static final int DEFAULT_MAX_TOKENS = 2048;

    private static final YAMLMapper YAML = new YAMLMapper();

    private final Path root;
    private final List<Problem> problems = new ArrayList<>();

    private RepoLoader(Path root) {
        this.root = root;
    }

    /**
     * Loads the repository in {@code dir}.
     *
     * @param version what is being loaded, from {@link GitVersion#of} or the commit a webhook named
     * @throws DefinitionException listing every problem found
     */
    public static OrgRepo load(Path dir, String version) {
        Path root;
        try {
            root = dir.toRealPath();
        } catch (IOException e) {
            throw new DefinitionException(List.of(new Problem(String.valueOf(dir), "", "is not a readable directory")));
        }
        return new RepoLoader(root).load(version);
    }

    private OrgRepo load(String version) {
        String org = null;
        String model = null;
        Optional<DirectorySpec> directory = Optional.empty();
        List<String> admins = List.of();
        Optional<OrgRepo.RepositorySpec> repository = Optional.empty();
        Optional<OrgRepo.SignInSpec> signIn = Optional.empty();
        Optional<String> provider = Optional.empty();
        Budget budget = Budget.NONE;
        OrgRepo.RouterSpec router = OrgRepo.RouterSpec.DEFAULT;
        Optional<JsonNode> orgFile = yaml("org.yaml", true);
        if (orgFile.isPresent()) {
            JsonNode node = orgFile.get();
            unknownKeys("org.yaml", "", node, ORG_KEYS);
            org = name("org.yaml", "org", node, true);
            model = text("org.yaml", "model", node.get("model"), true);
            if (node.has("directory")) {
                directory = directory(node.get("directory"));
            }
            admins = strings("org.yaml", "admins", node.get("admins"));
            if (node.has("repository")) {
                repository = repository(node.get("repository"));
            }
            if (node.has("signIn")) {
                signIn = signIn(node.get("signIn"));
            }
            if (node.has("router")) {
                router = router(node.get("router"));
            }
            provider = Optional.ofNullable(text("org.yaml", "provider", node.get("provider"), false));
            if (provider.isPresent() && !ModelAccounts.PROVIDERS.contains(provider.get())) {
                problem("org.yaml", "provider", "is one of " + String.join(", ", ModelAccounts.PROVIDERS.stream()
                        .sorted().toList()) + ", with the organization's " + ModelAccounts.KEY_SECRET
                        + " secret as its key; leave it out to run on the host's account");
                provider = Optional.empty();
            }
            if (node.has("budget")) {
                budget = HostLimits.budget(node.get("budget"), "budget", (where, what) -> problem("org.yaml", where, what));
            }
        }

        Map<String, ConnectorSpec> connectors = new LinkedHashMap<>();
        for (Path file : list(root.resolve("connectors"), false)) {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".yaml")) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - ".yaml".length());
            String relative = "connectors/" + fileName;
            if (!NAME.matcher(name).matches()) {
                problem(relative, "", "a connector's name is lowercase letters, digits and dashes");
                continue;
            }
            yaml(relative, true).flatMap(node -> connector(relative, name, node)).ifPresent(c -> connectors.put(name, c));
        }

        directory.ifPresent(d -> {
            if (!connectors.containsKey(d.connector())) {
                problem("org.yaml", "directory.connector", "no connector named " + d.connector());
            }
        });

        Map<String, AgentDefinition> agents = new LinkedHashMap<>();
        for (Path agentDir : list(root.resolve("agents"), true)) {
            String id = agentDir.getFileName().toString();
            String relative = "agents/" + id + "/agent.yaml";
            if (!NAME.matcher(id).matches()) {
                problem("agents/" + id, "", "an agent's id is lowercase letters, digits and dashes");
                continue;
            }
            yaml(relative, true).flatMap(node -> agent(relative, id, agentDir, node, connectors.keySet()))
                    .ifPresent(a -> agents.put(id, a));
        }
        if (agents.isEmpty() && problems.isEmpty()) {
            problem("agents", "", "defines no agents");
        }
        // Checked against every agent directory, so a routing case is not blamed for an agent that has problems of its own.
        Set<String> agentIds = new java.util.HashSet<>();
        list(root.resolve("agents"), true).forEach(dir -> agentIds.add(dir.getFileName().toString()));
        List<RoutingCase> routing = routing(agentIds);
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        return new OrgRepo(org, version, model, directory, connectors, agents, admins, repository, signIn, provider,
                budget, router, routing);
    }

    /** {@code router: {enabled, model, prompt}} in org.yaml; the prompt is a file in the repository. */
    private OrgRepo.RouterSpec router(JsonNode node) {
        if (node.isBoolean()) {
            return new OrgRepo.RouterSpec(node.asBoolean(), null, "");
        }
        if (!node.isObject()) {
            problem("org.yaml", "router", "is true, false, or a mapping of enabled, model and prompt");
            return OrgRepo.RouterSpec.DEFAULT;
        }
        unknownKeys("org.yaml", "router.", node, ROUTER_KEYS);
        boolean enabled = true;
        if (node.has("enabled")) {
            if (!node.get("enabled").isBoolean()) {
                problem("org.yaml", "router.enabled", "must be true or false");
            } else {
                enabled = node.get("enabled").asBoolean();
            }
        }
        String model = text("org.yaml", "router.model", node.get("model"), false);
        String prompt = node.has("prompt") ? promptFile("org.yaml", "router.prompt", root, node.get("prompt"), true) : "";
        return new OrgRepo.RouterSpec(enabled, model, prompt);
    }

    /** The routing cases in {@code routing.yaml}, if there is one; each names only agents the repository has. */
    private List<RoutingCase> routing(Set<String> agents) {
        String file = "routing.yaml";
        Optional<JsonNode> read = yaml(file, false);
        if (read.isEmpty()) {
            return List.of();
        }
        JsonNode node = read.get();
        if (!node.isObject() || !node.path("cases").isArray() || node.path("cases").isEmpty()) {
            problem(file, "cases", "is required: a list of {name, as, before, say, expect}");
            return List.of();
        }
        unknownKeys(file, "", node, ROUTING_KEYS);
        List<RoutingCase> cases = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        JsonNode list = node.get("cases");
        for (int i = 0; i < list.size(); i++) {
            String where = "cases[" + i + "]";
            JsonNode c = list.get(i);
            if (!c.isObject()) {
                problem(file, where, "must be a mapping of name, as, before, say and expect");
                continue;
            }
            int before = problems.size();
            unknownKeys(file, where + ".", c, ROUTING_CASE_KEYS);
            String name = text(file, where + ".name", c.get("name"), true);
            if (name != null && !names.add(name)) {
                problem(file, where + ".name", "another case is named " + name);
            }
            String as = text(file, where + ".as", c.get("as"), true);
            String say = text(file, where + ".say", c.get("say"), true);
            List<RoutingCase.Earlier> earlier = new ArrayList<>();
            JsonNode beforeNode = c.path("before");
            if (c.has("before") && !beforeNode.isArray()) {
                problem(file, where + ".before", "must be a list of {say, agent, answer}");
            }
            for (int j = 0; beforeNode.isArray() && j < beforeNode.size(); j++) {
                String at = where + ".before[" + j + "]";
                JsonNode turn = beforeNode.get(j);
                if (!turn.isObject()) {
                    problem(file, at, "must be a mapping of say, agent and answer");
                    continue;
                }
                unknownKeys(file, at + ".", turn, ROUTING_BEFORE_KEYS);
                String agent = text(file, at + ".agent", turn.get("agent"), false);
                if (agent != null && !agents.contains(agent)) {
                    problem(file, at + ".agent", "there is no agent " + agent);
                }
                earlier.add(new RoutingCase.Earlier(text(file, at + ".say", turn.get("say"), true), agent,
                        text(file, at + ".answer", turn.get("answer"), true)));
            }
            JsonNode expect = c.get("expect");
            RoutingCase.Expect expected = null;
            if (expect == null || !expect.isObject()) {
                problem(file, where + ".expect", "is required: one of {agent: <id>}, {answers: true}, {asks: true} or "
                        + "{form: <id>}");
            } else {
                unknownKeys(file, where + ".expect.", expect, ROUTING_EXPECT_KEYS);
                String agent = text(file, where + ".expect.agent", expect.get("agent"), false);
                boolean answers = expect.path("answers").asBoolean(false);
                boolean asks = expect.path("asks").asBoolean(false);
                String form = text(file, where + ".expect.form", expect.get("form"), false);
                if ((agent != null ? 1 : 0) + (answers ? 1 : 0) + (asks ? 1 : 0) + (form != null ? 1 : 0) != 1) {
                    problem(file, where + ".expect", "is exactly one of {agent: <id>}, {answers: true}, {asks: true} "
                            + "or {form: <id>}");
                } else if (agent != null && !agents.contains(agent)) {
                    problem(file, where + ".expect.agent", "there is no agent " + agent);
                } else if (form != null && !agents.contains(form)) {
                    problem(file, where + ".expect.form", "there is no agent " + form);
                } else {
                    expected = new RoutingCase.Expect(agent, answers, asks, form);
                }
            }
            if (problems.size() == before && name != null && as != null && say != null && expected != null) {
                cases.add(new RoutingCase(name, as, earlier, say, expected));
            }
        }
        return cases;
    }

    // ---------------------------------------------------------------- org and connectors

    private Optional<DirectorySpec> directory(JsonNode node) {
        if (!node.isObject()) {
            problem("org.yaml", "directory", "must be a mapping of connector, tool and argument");
            return Optional.empty();
        }
        unknownKeys("org.yaml", "directory.", node, DIRECTORY_KEYS);
        String connector = text("org.yaml", "directory.connector", node.get("connector"), true);
        String tool = text("org.yaml", "directory.tool", node.get("tool"), true);
        String argument = text("org.yaml", "directory.argument", node.get("argument"), true);
        return connector == null || tool == null || argument == null ? Optional.empty()
                : Optional.of(new DirectorySpec(connector, tool, argument));
    }

    private Optional<OrgRepo.RepositorySpec> repository(JsonNode node) {
        if (!node.isObject()) {
            problem("org.yaml", "repository", "must be a mapping of github, and optionally path, base and api");
            return Optional.empty();
        }
        unknownKeys("org.yaml", "repository.", node, REPOSITORY_KEYS);
        String github = text("org.yaml", "repository.github", node.get("github"), true);
        if (github != null && !GITHUB_REPOSITORY.matcher(github).matches()) {
            problem("org.yaml", "repository.github", "is owner/name, such as acme/agents");
            return Optional.empty();
        }
        String path = text("org.yaml", "repository.path", node.get("path"), false);
        if (path != null && java.util.Arrays.asList(path.split("/")).contains("..")) {
            problem("org.yaml", "repository.path", "is a directory inside the repository, such as orgs/acme");
            return Optional.empty();
        }
        String base = text("org.yaml", "repository.base", node.get("base"), false);
        String api = text("org.yaml", "repository.api", node.get("api"), false);
        if (api != null && !api.startsWith("https://") && !api.startsWith("http://")) {
            problem("org.yaml", "repository.api", "is the API's URL, such as https://api.github.com");
            return Optional.empty();
        }
        return github == null ? Optional.empty() : Optional.of(new OrgRepo.RepositorySpec(github, path, base, api));
    }

    private Optional<OrgRepo.SignInSpec> signIn(JsonNode node) {
        if (!node.isObject()) {
            problem("org.yaml", "signIn", "must be a mapping of issuer and clientId, and optionally emailClaim and mcpAudience");
            return Optional.empty();
        }
        unknownKeys("org.yaml", "signIn.", node, SIGN_IN_KEYS);
        String issuer = text("org.yaml", "signIn.issuer", node.get("issuer"), true);
        if (issuer != null && !issuer.startsWith("https://") && !issuer.startsWith("http://localhost")
                && !issuer.startsWith("http://127.0.0.1")) {
            problem("org.yaml", "signIn.issuer", "is an https URL: an identity provider is only trusted over TLS");
            return Optional.empty();
        }
        String clientId = text("org.yaml", "signIn.clientId", node.get("clientId"), true);
        String emailClaim = text("org.yaml", "signIn.emailClaim", node.get("emailClaim"), false);
        String audience = text("org.yaml", "signIn.mcpAudience", node.get("mcpAudience"), false);
        return issuer == null || clientId == null ? Optional.empty()
                : Optional.of(new OrgRepo.SignInSpec(issuer, clientId, emailClaim, audience));
    }

    private Optional<ConnectorSpec> connector(String file, String name, JsonNode node) {
        if (!node.isObject()) {
            problem(file, "", "must be a mapping");
            return Optional.empty();
        }
        int before = problems.size();
        unknownKeys(file, "", node, CONNECTOR_KEYS);
        if (node.has("url") == node.has("command")) {
            problem(file, "", "needs exactly one of url or command");
        }
        if (node.has("url") && !node.get("url").isTextual()) {
            problem(file, "url", "must be text");
        }
        if (node.has("command") && (!node.get("command").isArray() || node.get("command").isEmpty())) {
            problem(file, "command", "must be a list of words");
        }
        if (node.has("headers") && !node.get("headers").isObject()) {
            problem(file, "headers", "must be a mapping of header to value");
        }
        for (String flag : List.of("trustAnnotations", "authoritative")) {
            if (node.has(flag) && !node.get(flag).isBoolean()) {
                problem(file, flag, "must be true or false");
            }
        }
        if (node.has("timeoutSeconds") && (!node.get("timeoutSeconds").canConvertToInt()
                || node.get("timeoutSeconds").asInt() <= 0)) {
            problem(file, "timeoutSeconds", "must be a positive whole number");
        }
        JsonNode tools = node.path("tools");
        if (node.has("tools") && !tools.isObject()) {
            problem(file, "tools", "must be a mapping of tool name to its declaration");
        } else {
            tools.fields().forEachRemaining(entry -> {
                String where = "tools." + entry.getKey();
                JsonNode declared = entry.getValue();
                unknownKeys(file, where + ".", declared, Set.of("effect", "system", "subject"));
                String effect = text(file, where + ".effect", declared.get("effect"), false);
                if (effect != null && ToolEffect.parse(effect).isEmpty()) {
                    problem(file, where + ".effect", "unknown effect \"" + effect + "\"; " + effects());
                }
            });
        }
        if (problems.size() > before) {
            return Optional.empty();
        }
        ObjectNode server = ((ObjectNode) node).deepCopy();
        boolean authoritative = server.path("authoritative").asBoolean(false);
        server.remove("authoritative");
        server.put("name", name);
        return Optional.of(new ConnectorSpec(name, server, authoritative));
    }

    // ---------------------------------------------------------------- agents

    private Optional<AgentDefinition> agent(String file, String id, Path dir, JsonNode node, Set<String> connectors) {
        if (!node.isObject()) {
            problem(file, "", "must be a mapping");
            return Optional.empty();
        }
        int before = problems.size();
        unknownKeys(file, "", node, AGENT_KEYS);
        String name = text(file, "name", node.get("name"), true);
        String description = text(file, "description", node.get("description"), false);
        String model = text(file, "model", node.get("model"), false);

        AgentDefinition.Pattern pattern = AgentDefinition.Pattern.CHAT;
        String patternText = text(file, "pattern", node.get("pattern"), false);
        if (patternText != null) {
            try {
                pattern = AgentDefinition.Pattern.valueOf(patternText.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                problem(file, "pattern", "unknown pattern \"" + patternText + "\"; the patterns are: chat, plan-execute");
            }
        }

        List<String> audience = strings(file, "audience", node.get("audience"));
        if (!node.has("audience")) {
            audience = List.of(AgentDefinition.EVERYONE);
        } else if (audience.isEmpty()) {
            problem(file, "audience", "admits nobody; say everyone, or name groups");
        }

        String system = null;
        String planner = null;
        String policy = "";
        boolean planned = pattern == AgentDefinition.Pattern.PLAN_EXECUTE;
        JsonNode prompt = node.get("prompt");
        if (prompt == null || !prompt.isObject()) {
            problem(file, "prompt", planned ? "is required: a mapping with planner and executor, and optionally policy"
                    : "is required: a mapping with system, and optionally policy");
        } else if (planned) {
            unknownKeys(file, "prompt.", prompt, PLAN_EXECUTE_PROMPT_KEYS);
            planner = promptFile(file, "prompt.planner", dir, prompt.get("planner"), true);
            system = promptFile(file, "prompt.executor", dir, prompt.get("executor"), true);
            String policyText = promptFile(file, "prompt.policy", dir, prompt.get("policy"), false);
            policy = policyText == null ? "" : policyText;
        } else {
            unknownKeys(file, "prompt.", prompt, PROMPT_KEYS);
            system = promptFile(file, "prompt.system", dir, prompt.get("system"), true);
            String policyText = promptFile(file, "prompt.policy", dir, prompt.get("policy"), false);
            policy = policyText == null ? "" : policyText;
        }

        List<ToolSelector> tools = new ArrayList<>();
        JsonNode toolsNode = node.get("tools");
        if (toolsNode == null || !toolsNode.isArray() || toolsNode.isEmpty()) {
            problem(file, "tools", "is required: a list of {connector, effects, tools}");
        } else {
            for (int i = 0; i < toolsNode.size(); i++) {
                selector(file, "tools[" + i + "]", toolsNode.get(i), connectors).ifPresent(tools::add);
            }
        }

        List<ToolRef> confirm = new ArrayList<>();
        List<String> confirmed = strings(file, "confirm", node.get("confirm"));
        for (int i = 0; i < confirmed.size(); i++) {
            toolRef(file, "confirm[" + i + "]", confirmed.get(i), connectors, false).ifPresent(confirm::add);
        }

        Map<ToolRef, Map<String, String>> bind = new LinkedHashMap<>();
        JsonNode bindNode = node.get("bind");
        if (bindNode != null && !bindNode.isObject()) {
            problem(file, "bind", "must be a mapping of connector/tool to {argument: principal.field}");
        } else if (bindNode != null) {
            bindNode.fields().forEachRemaining(entry -> {
                String where = "bind." + entry.getKey();
                Optional<ToolRef> ref = toolRef(file, where, entry.getKey(), connectors, true);
                if (!entry.getValue().isObject() || entry.getValue().isEmpty()) {
                    problem(file, where, "must be a mapping of argument to principal.field");
                    return;
                }
                Map<String, String> arguments = new LinkedHashMap<>();
                entry.getValue().fields().forEachRemaining(argument -> {
                    String path = argument.getValue().isTextual() ? argument.getValue().asText().strip() : "";
                    if (!PRINCIPAL_PATH.matcher(path).matches()) {
                        problem(file, where + "." + argument.getKey(),
                                "must name a field of the person asking, such as principal.email");
                    } else {
                        arguments.put(argument.getKey(), path);
                    }
                });
                ref.ifPresent(r -> bind.put(r, arguments));
            });
        }

        int maxSteps = DEFAULT_MAX_STEPS;
        int maxTokens = DEFAULT_MAX_TOKENS;
        JsonNode limits = node.get("limits");
        if (limits != null) {
            unknownKeys(file, "limits.", limits, LIMIT_KEYS);
            maxSteps = positive(file, "limits.maxSteps", limits.get("maxSteps"), maxSteps);
            maxTokens = positive(file, "limits.maxTokens", limits.get("maxTokens"), maxTokens);
        }

        AgentDefinition.Deferred deferred = null;
        JsonNode deferredNode = node.get("deferred");
        if (deferredNode != null) {
            deferred = deferred(file, dir, deferredNode, connectors);
        }

        List<ToolRef> direct = new ArrayList<>();
        JsonNode mcp = node.get("mcp");
        if (mcp != null) {
            if (!mcp.isObject()) {
                problem(file, "mcp", "must be a mapping with direct: a list of connector/tool");
            } else {
                unknownKeys(file, "mcp.", mcp, MCP_KEYS);
                List<String> named = strings(file, "mcp.direct", mcp.get("direct"));
                for (int i = 0; i < named.size(); i++) {
                    toolRef(file, "mcp.direct[" + i + "]", named.get(i), connectors, false).ifPresent(direct::add);
                }
            }
        }

        TaskInput input = null;
        JsonNode inputNode = node.get("input");
        if (inputNode != null) {
            input = input(file, dir, inputNode);
        }

        List<EvalCase> evals = evals("agents/" + id + "/evals.yaml", input);

        AgentDefinition.PlanReuse planReuse = null;
        JsonNode plans = node.get("plans");
        if (plans != null) {
            planReuse = planReuse(file, plans, pattern, input);
        }

        List<AgentDefinition.Check> checks = new ArrayList<>();
        JsonNode beforeNode = node.get("before");
        if (beforeNode != null) {
            checks = checks(file, beforeNode, pattern, input, connectors);
        }

        if (problems.size() > before) {
            return Optional.empty();
        }
        return Optional.of(new AgentDefinition(id, name, description, pattern, model, audience, system, policy, tools,
                confirm, bind, maxSteps, maxTokens, deferred, direct, planner, input, evals, planReuse, checks));
    }

    /**
     * {@code before: [{tool: connector/tool, with: {argument: input.<field>}}]}: what a plan-execute agent with a form
     * checks before it plans. That the tool is one of the agent's, and reads, is checked when the agent is assembled.
     */
    private List<AgentDefinition.Check> checks(String file, JsonNode node, AgentDefinition.Pattern pattern,
                                               TaskInput input, Set<String> connectors) {
        List<AgentDefinition.Check> checks = new ArrayList<>();
        if (!node.isArray()) {
            problem(file, "before", "must be a list of {tool: connector/tool, with: {argument: input.<field>}}");
            return checks;
        }
        if (pattern != AgentDefinition.Pattern.PLAN_EXECUTE) {
            problem(file, "before", "is for a plan-execute agent: it is checked before the plan is made");
        }
        if (input == null) {
            problem(file, "before", "needs input: a check is made with the fields of the task");
        }
        Set<String> fields = input == null ? Set.of()
                : new java.util.HashSet<>(input.fields().stream().map(TaskInput.Field::name).toList());
        for (int i = 0; i < node.size(); i++) {
            String where = "before[" + i + "]";
            JsonNode check = node.get(i);
            if (!check.isObject()) {
                problem(file, where, "must be {tool: connector/tool, with: {argument: input.<field>}}");
                continue;
            }
            unknownKeys(file, where + ".", check, CHECK_KEYS);
            String tool = text(file, where + ".tool", check.get("tool"), true);
            Optional<ToolRef> ref = tool == null ? Optional.empty() : toolRef(file, where + ".tool", tool, connectors, false);
            Map<String, String> with = new LinkedHashMap<>();
            JsonNode withNode = check.get("with");
            if (withNode == null || !withNode.isObject() || withNode.isEmpty()) {
                problem(file, where + ".with", "is required: each argument of the tool and input.<field> it is from");
            } else {
                withNode.fields().forEachRemaining(argument -> {
                    String path = argument.getValue().asText("");
                    if (!path.startsWith("input.") || !fields.contains(path.substring("input.".length()))) {
                        problem(file, where + ".with." + argument.getKey(), "\"" + path
                                + "\" is not input.<field> of a field of the agent's input");
                    } else {
                        with.put(argument.getKey(), path);
                    }
                });
            }
            ref.ifPresent(r -> checks.add(new AgentDefinition.Check(r, with)));
        }
        return checks;
    }

    /** {@code plans: {reuse: {after, recheckEvery, sameWhen}}}: for a plan-execute agent started from its form. */
    private AgentDefinition.PlanReuse planReuse(String file, JsonNode plans, AgentDefinition.Pattern pattern,
                                                TaskInput input) {
        if (!plans.isObject() || !plans.path("reuse").isObject()) {
            problem(file, "plans", "must be a mapping with reuse: {after, recheckEvery, sameWhen}");
            return null;
        }
        unknownKeys(file, "plans.", plans, PLANS_KEYS);
        JsonNode reuse = plans.get("reuse");
        unknownKeys(file, "plans.reuse.", reuse, REUSE_KEYS);
        if (pattern != AgentDefinition.Pattern.PLAN_EXECUTE) {
            problem(file, "plans.reuse", "is for a plan-execute agent: only it makes a plan to reuse");
        }
        if (input == null) {
            problem(file, "plans.reuse", "needs input: a plan is reused for a task started from the agent's form, "
                    + "whose fields say which tasks are alike");
        }
        int after = positive(file, "plans.reuse.after", reuse.get("after"), 3);
        if (after < 2 || after > 10) {
            problem(file, "plans.reuse.after", "is from 2 to 10: one plan is an anecdote");
        }
        int recheckEvery = positive(file, "plans.reuse.recheckEvery", reuse.get("recheckEvery"), 10);
        List<String> sameWhen = strings(file, "plans.reuse.sameWhen", reuse.get("sameWhen"));
        if (input != null) {
            Set<String> fields = new java.util.HashSet<>(input.fields().stream().map(TaskInput.Field::name).toList());
            for (String field : sameWhen) {
                if (!fields.contains(field)) {
                    problem(file, "plans.reuse.sameWhen", field + " is not a field of the agent's input");
                }
            }
        }
        return new AgentDefinition.PlanReuse(after, recheckEvery, sameWhen);
    }

    /**
     * The agent's eval cases, if it has an {@code evals.yaml}. What needs the connectors — that a tool an expectation
     * names is one of the agent's — is checked when the agent is assembled.
     */
    private List<EvalCase> evals(String file, TaskInput input) {
        Optional<JsonNode> read = yaml(file, false);
        if (read.isEmpty()) {
            return List.of();
        }
        JsonNode node = read.get();
        if (!node.isObject()) {
            problem(file, "", "must be a mapping with cases: a list of eval cases");
            return List.of();
        }
        unknownKeys(file, "", node, EVALS_KEYS);
        JsonNode casesNode = node.get("cases");
        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            problem(file, "cases", "is required: a list of {name, as, say or input, answers, expect}");
            return List.of();
        }
        List<EvalCase> cases = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < casesNode.size(); i++) {
            String where = "cases[" + i + "]";
            JsonNode c = casesNode.get(i);
            if (!c.isObject()) {
                problem(file, where, "must be a mapping of name, as, say or input, answers and expect");
                continue;
            }
            int before = problems.size();
            unknownKeys(file, where + ".", c, CASE_KEYS);
            String name = text(file, where + ".name", c.get("name"), true);
            if (name != null && !names.add(name)) {
                problem(file, where + ".name", "another case is named " + name);
            }
            String as = text(file, where + ".as", c.get("as"), true);
            if (as != null && !as.contains("@")) {
                problem(file, where + ".as", "is the email of someone in the directory");
            }
            String say = text(file, where + ".say", c.get("say"), false);
            Map<String, Object> filled = null;
            JsonNode inputNode = c.get("input");
            if ((say == null) == (inputNode == null)) {
                problem(file, where, "starts from say or from input: exactly one of them");
            } else if (inputNode != null) {
                if (input == null) {
                    problem(file, where + ".input", "this agent takes no form; use say");
                } else if (!inputNode.isObject()) {
                    problem(file, where + ".input", "must be a mapping of the form's fields");
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> given = JSON.convertValue(inputNode, Map.class);
                    input.problems(given).forEach(p -> problem(file, where + ".input", p));
                    filled = given;
                }
            }
            List<String> answers = strings(file, where + ".answers", c.get("answers"));
            List<EvalCase.Expectation> expect = new ArrayList<>();
            JsonNode expectNode = c.get("expect");
            if (expectNode == null || !expectNode.isArray() || expectNode.isEmpty()) {
                problem(file, where + ".expect", "is required: a list of what must be true");
            } else {
                for (int j = 0; j < expectNode.size(); j++) {
                    expectation(file, where + ".expect[" + j + "]", expectNode.get(j)).ifPresent(expect::add);
                }
            }
            if (problems.size() == before) {
                cases.add(new EvalCase(name, as.toLowerCase(Locale.ROOT), say, filled, answers, expect));
            }
        }
        return cases;
    }

    private Optional<EvalCase.Expectation> expectation(String file, String where, JsonNode node) {
        if (!node.isObject()) {
            problem(file, where, "must be a mapping: one of " + new java.util.TreeSet<>(EXPECT_KINDS));
            return Optional.empty();
        }
        List<String> kinds = new ArrayList<>();
        node.fieldNames().forEachRemaining(key -> {
            if (EXPECT_KINDS.contains(key)) {
                kinds.add(key);
            } else if (!key.equals("with")) {
                problem(file, where + "." + key, "is not an expectation; they are " + new java.util.TreeSet<>(EXPECT_KINDS));
            }
        });
        if (kinds.size() != 1) {
            problem(file, where, "must say exactly one of " + new java.util.TreeSet<>(EXPECT_KINDS));
            return Optional.empty();
        }
        String kind = kinds.get(0);
        JsonNode with = node.get("with");
        if (with != null && !kind.equals("calls") && !kind.equals("never")) {
            problem(file, where + ".with", "goes with calls or never");
            return Optional.empty();
        }
        Map<String, Object> arguments = Map.of();
        if (with != null) {
            if (!with.isObject()) {
                problem(file, where + ".with", "must be a mapping of argument to value");
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = JSON.convertValue(with, Map.class);
            arguments = converted;
        }
        JsonNode value = node.get(kind);
        Map<String, Object> withArguments = arguments;
        return switch (kind) {
            case "calls", "never" -> Optional.ofNullable(text(file, where + "." + kind, value, true))
                    .map(tool -> new EvalCase.Expectation(kind.equals("calls") ? EvalCase.Expectation.Kind.CALLS
                            : EvalCase.Expectation.Kind.NEVER, tool, withArguments, false, null));
            case "asks" -> {
                if (value == null || !value.isBoolean()) {
                    problem(file, where + ".asks", "is true or false");
                    yield Optional.empty();
                }
                yield Optional.of(new EvalCase.Expectation(EvalCase.Expectation.Kind.ASKS, null, null, value.asBoolean(),
                        null));
            }
            default -> Optional.ofNullable(text(file, where + "." + kind, value, true))
                    .map(text -> new EvalCase.Expectation(kind.equals("judge") ? EvalCase.Expectation.Kind.JUDGE
                            : EvalCase.Expectation.Kind.ANSWER_CONTAINS, null, null, false, text));
        };
    }

    private TaskInput input(String file, Path dir, JsonNode node) {
        if (!node.isObject()) {
            problem(file, "input", "must be a mapping of schema, and optionally goal");
            return null;
        }
        unknownKeys(file, "input.", node, INPUT_KEYS);
        String schemaText = promptFile(file, "input.schema", dir, node.get("schema"), true);
        String goal = promptFile(file, "input.goal", dir, node.get("goal"), false);
        if (schemaText == null) {
            return null;
        }
        JsonNode schema;
        try {
            schema = YAML.readTree(schemaText);
        } catch (IOException e) {
            problem(file, "input.schema", "is not valid JSON or YAML: " + firstLine(e.getMessage()));
            return null;
        }
        String schemaFile = node.get("schema").asText();
        return TaskInput.parse(schema, goal, (where, message) -> problem(file,
                where.equals("goal") ? "input.goal" : "input.schema" + (where.isEmpty() ? "" : " (" + schemaFile + " "
                        + where + ")"), message));
    }

    private AgentDefinition.Deferred deferred(String file, Path dir, JsonNode node, Set<String> connectors) {
        if (!node.isObject()) {
            problem(file, "deferred", "must be a mapping of prompt, actor and subjects");
            return null;
        }
        unknownKeys(file, "deferred.", node, DEFERRED_KEYS);
        String prompt = promptFile(file, "deferred.prompt", dir, node.get("prompt"), true);
        String actor = text(file, "deferred.actor", node.get("actor"), true);
        if (actor != null && actor.contains("@")) {
            problem(file, "deferred.actor", "is the agent's own identity, not a person's email");
        }
        Map<String, AgentDefinition.Subject> subjects = new LinkedHashMap<>();
        JsonNode subjectsNode = node.get("subjects");
        if (subjectsNode == null || !subjectsNode.isObject() || subjectsNode.isEmpty()) {
            problem(file, "deferred.subjects", "is required: each kind of subject and {tool, argument} to look it up");
        } else {
            subjectsNode.fields().forEachRemaining(entry -> {
                String where = "deferred.subjects." + entry.getKey();
                if (!NAME.matcher(entry.getKey()).matches()) {
                    problem(file, where, "a subject kind is lowercase letters, digits and dashes");
                    return;
                }
                if (!entry.getValue().isObject()) {
                    problem(file, where, "must be {tool: connector/tool, argument: name}");
                    return;
                }
                unknownKeys(file, where + ".", entry.getValue(), SUBJECT_KEYS);
                String tool = text(file, where + ".tool", entry.getValue().get("tool"), true);
                String argument = text(file, where + ".argument", entry.getValue().get("argument"), true);
                Optional<ToolRef> ref = tool == null ? Optional.empty() : toolRef(file, where + ".tool", tool, connectors, false);
                if (ref.isPresent() && argument != null) {
                    subjects.put(entry.getKey(), new AgentDefinition.Subject(ref.get(), argument));
                }
            });
        }
        return prompt == null || actor == null ? null : new AgentDefinition.Deferred(prompt, actor, subjects);
    }

    private Optional<ToolSelector> selector(String file, String where, JsonNode node, Set<String> connectors) {
        if (!node.isObject()) {
            problem(file, where, "must be a mapping of connector, and optionally effects and tools");
            return Optional.empty();
        }
        unknownKeys(file, where + ".", node, SELECTOR_KEYS);
        String connector = text(file, where + ".connector", node.get("connector"), true);
        if (connector != null && !connectors.contains(connector)) {
            problem(file, where + ".connector", "no connector named " + connector);
            connector = null;
        }
        Set<ToolEffect> effects = new LinkedHashSet<>();
        for (String effect : strings(file, where + ".effects", node.get("effects"))) {
            ToolEffect.parse(effect).ifPresentOrElse(effects::add,
                    () -> problem(file, where + ".effects", "unknown effect \"" + effect + "\"; " + effects()));
        }
        Set<String> tools = new LinkedHashSet<>(strings(file, where + ".tools", node.get("tools")));
        return connector == null ? Optional.empty() : Optional.of(new ToolSelector(connector, effects, tools));
    }

    private Optional<ToolRef> toolRef(String file, String where, String text, Set<String> connectors, boolean wildcard) {
        Optional<ToolRef> ref = ToolRef.parse(text);
        if (ref.isEmpty()) {
            problem(file, where, "\"" + text + "\" is not connector/tool");
            return Optional.empty();
        }
        if (!connectors.contains(ref.get().connector())) {
            problem(file, where, "no connector named " + ref.get().connector());
            return Optional.empty();
        }
        if (ref.get().isWildcard() && !wildcard) {
            problem(file, where, "name the tool; " + ref.get() + " would cover tools nobody has read");
            return Optional.empty();
        }
        return ref;
    }

    /** The text of a prompt file named relative to the agent's directory, which must stay inside the repository. */
    private String promptFile(String file, String where, Path dir, JsonNode node, boolean required) {
        String name = text(file, where, node, required);
        if (name == null) {
            return null;
        }
        Path target = dir.resolve(name).normalize();
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            problem(file, where, name + " does not exist");
            return null;
        }
        try {
            Path real = target.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                problem(file, where, name + " is not a file inside the repository");
                return null;
            }
            String text = Files.readString(real, StandardCharsets.UTF_8).strip();
            if (required && text.isEmpty()) {
                problem(file, where, name + " is empty");
            }
            return text;
        } catch (IOException e) {
            problem(file, where, name + " cannot be read");
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private Optional<JsonNode> yaml(String relative, boolean required) {
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file)) {
            if (required) {
                problem(relative, "", "is missing");
            }
            return Optional.empty();
        }
        try {
            JsonNode node = YAML.readTree(file.toFile());
            if (node == null || node.isMissingNode() || node.isNull()) {
                problem(relative, "", "is empty");
                return Optional.empty();
            }
            return Optional.of(node);
        } catch (IOException e) {
            problem(relative, "", "is not valid YAML: " + firstLine(e.getMessage()));
            return Optional.empty();
        }
    }

    private List<Path> list(Path dir, boolean directories) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> directories ? Files.isDirectory(p) : Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            problem(root.relativize(dir).toString(), "", "cannot be listed");
            return List.of();
        }
    }

    private void unknownKeys(String file, String prefix, JsonNode node, Set<String> known) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String key = names.next();
            if (!known.contains(key)) {
                problem(file, prefix + key, "is not a field here; the fields are " + new java.util.TreeSet<>(known));
            }
        }
    }

    private String name(String file, String where, JsonNode parent, boolean required) {
        String value = text(file, where, parent.get(where), required);
        if (value != null && !NAME.matcher(value).matches()) {
            problem(file, where, "is lowercase letters, digits and dashes");
            return null;
        }
        return value;
    }

    private String text(String file, String where, JsonNode node, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                problem(file, where, "is required");
            }
            return null;
        }
        if (!node.isValueNode() || node.asText().isBlank()) {
            problem(file, where, "must be text");
            return null;
        }
        return node.asText().strip();
    }

    private List<String> strings(String file, String where, JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            problem(file, where, "must be a list");
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            String value = text(file, where + "[" + i + "]", node.get(i), true);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private int positive(String file, String where, JsonNode node, int fallback) {
        if (node == null) {
            return fallback;
        }
        if (!node.canConvertToInt() || !node.isIntegralNumber() || node.asInt() <= 0) {
            problem(file, where, "must be a positive whole number");
            return fallback;
        }
        return node.asInt();
    }

    private void problem(String file, String where, String message) {
        problems.add(new Problem(file, where, message));
    }

    private static String effects() {
        return "the effects are " + Stream.of(ToolEffect.values()).map(ToolEffect::wire).toList();
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }
}
