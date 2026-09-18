package dev.agentkit.host.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.agentkit.core.tool.ToolEffect;
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
 * org.yaml                     org, model, directory
 * connectors/&lt;name&gt;.yaml      url or command, headers, trustAnnotations, authoritative, timeoutSeconds, tools
 * agents/&lt;id&gt;/agent.yaml       name, description, pattern, model, audience, prompt, tools, confirm, bind, limits
 * agents/&lt;id&gt;/…               the prompt files agent.yaml names
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

    private static final Set<String> ORG_KEYS = Set.of("org", "model", "directory");
    private static final Set<String> DIRECTORY_KEYS = Set.of("connector", "tool", "argument");
    private static final Set<String> CONNECTOR_KEYS = Set.of("url", "command", "headers", "trustAnnotations",
            "authoritative", "timeoutSeconds", "tools");
    private static final Set<String> AGENT_KEYS = Set.of("name", "description", "pattern", "model", "audience",
            "prompt", "tools", "confirm", "bind", "limits");
    private static final Set<String> PROMPT_KEYS = Set.of("system", "policy");
    private static final Set<String> SELECTOR_KEYS = Set.of("connector", "effects", "tools");
    private static final Set<String> LIMIT_KEYS = Set.of("maxSteps", "maxTokens");

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
        Optional<JsonNode> orgFile = yaml("org.yaml", true);
        if (orgFile.isPresent()) {
            JsonNode node = orgFile.get();
            unknownKeys("org.yaml", "", node, ORG_KEYS);
            org = name("org.yaml", "org", node, true);
            model = text("org.yaml", "model", node.get("model"), true);
            if (node.has("directory")) {
                directory = directory(node.get("directory"));
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
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        return new OrgRepo(org, version, model, directory, connectors, agents);
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
                problem(file, "pattern", "unknown pattern \"" + patternText + "\"; the patterns are: chat");
            }
        }

        List<String> audience = strings(file, "audience", node.get("audience"));
        if (!node.has("audience")) {
            audience = List.of(AgentDefinition.EVERYONE);
        } else if (audience.isEmpty()) {
            problem(file, "audience", "admits nobody; say everyone, or name groups");
        }

        String system = null;
        String policy = "";
        JsonNode prompt = node.get("prompt");
        if (prompt == null || !prompt.isObject()) {
            problem(file, "prompt", "is required: a mapping with system, and optionally policy");
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

        if (problems.size() > before) {
            return Optional.empty();
        }
        return Optional.of(new AgentDefinition(id, name, description, pattern, model, audience, system, policy, tools,
                confirm, bind, maxSteps, maxTokens));
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
