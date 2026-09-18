package dev.agentkit.accessdesk.systems;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A stand-in for the company's systems of record — a directory, a catalog of resources people can be
 * given access to, the access itself, and a chat tool for direct messages — exposed as tools.
 *
 * <p>This is what Access Desk connects to over MCP ({@link CompanySystemsServer}). In a real
 * deployment its place is taken by the company's own MCP servers; nothing in the desk depends on this
 * class. The directory and the catalog come from {@code access-desk/company.json}; access and messages
 * are kept in {@code state.json} under a data directory, so they survive a restart.
 */
public final class CompanySystems {

    public static final String SYSTEM = "company";

    /**
     * A person in the directory. {@code manager} is an email, empty for nobody; {@code groups} are what the agent host
     * offers agents by, such as {@code managers}.
     */
    public record Person(String email, String name, String title, String department, String manager,
                         List<String> groups) {
        public Person {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    /** Something a person can be given access to. */
    public record Resource(String id, String name, String system, List<String> levels, String sensitivity,
                           String owner, int max_hours) {
    }

    /** One person's access to one resource at one level. */
    public record Access(String resource_id, String email, String level, Instant granted_at) {
    }

    /** A direct message. */
    public record Message(String to, String text, Instant sent_at) {
    }

    private record Seed(List<Person> people, List<Resource> resources) {
    }

    private record State(List<Access> access, List<Message> messages) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Map<String, Person> people = new LinkedHashMap<>();
    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final Set<Access> access = new LinkedHashSet<>();
    private final List<Message> messages = new ArrayList<>();
    private final Path stateFile;

    private CompanySystems(Path stateDir) {
        this.stateFile = stateDir == null ? null : stateDir.resolve("state.json");
    }

    /**
     * The seeded company, with access and messages loaded from {@code stateDir} if it holds any.
     *
     * @param stateDir where state is kept; null keeps it in memory only
     */
    public static CompanySystems open(Path stateDir) {
        CompanySystems systems = new CompanySystems(stateDir);
        try (InputStream in = CompanySystems.class.getClassLoader().getResourceAsStream("access-desk/company.json")) {
            Seed seed = MAPPER.readValue(Objects.requireNonNull(in, "access-desk/company.json"), Seed.class);
            seed.people().forEach(p -> systems.people.put(p.email(), p));
            seed.resources().forEach(r -> systems.resources.put(r.id(), r));
            if (systems.stateFile != null && Files.exists(systems.stateFile)) {
                State state = MAPPER.readValue(systems.stateFile.toFile(), new TypeReference<>() {
                });
                systems.access.addAll(state.access());
                systems.messages.addAll(state.messages());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the company systems", e);
        }
        return systems;
    }

    /** Every tool, with its declaration. */
    public DeclaredTools catalog() {
        DeclaredTools catalog = new DeclaredTools();
        catalog.add(tool("directory_lookup", "Look a person up in the company directory by work email: "
                        + "name, title, department and manager.",
                Map.of("email", str("Work email")), List.of("email"), SideEffects.NONE,
                inv -> read(people.get(lower(inv.stringArgument("email"))), "No person with that email")),
                new ToolDeclaration("directory", ToolEffect.READ, "email"));
        catalog.add(tool("list_resources", "List the resources people can be given access to, optionally "
                        + "filtered by a search term: id, name, system, access levels, sensitivity (low, high, "
                        + "critical), owner and the most hours access may last.",
                Map.of("query", str("Optional search term, e.g. \"payments\" or \"database\"")), List.of(),
                SideEffects.NONE, inv -> {
                    String query = lower(inv.stringArgument("query"));
                    return json(resources.values().stream()
                            .filter(r -> query.isEmpty() || (r.id() + " " + r.name() + " " + r.system())
                                    .toLowerCase(Locale.ROOT).contains(query))
                            .toList());
                }), new ToolDeclaration("catalog", ToolEffect.READ, null));
        catalog.add(tool("list_access", "List the access a person currently has.",
                Map.of("email", str("Work email")), List.of("email"), SideEffects.NONE,
                inv -> {
                    String email = lower(inv.stringArgument("email"));
                    return json(access.stream().filter(a -> a.email().equals(email)).toList());
                }), new ToolDeclaration("access", ToolEffect.READ, "email"));
        catalog.add(tool("list_messages", "List the direct messages a person has received, newest first.",
                Map.of("email", str("Work email")), List.of("email"), SideEffects.NONE,
                inv -> {
                    String email = lower(inv.stringArgument("email"));
                    List<Message> theirs = new ArrayList<>(messages.stream().filter(m -> m.to().equals(email)).toList());
                    java.util.Collections.reverse(theirs);
                    return json(theirs);
                }), new ToolDeclaration("chat", ToolEffect.READ, "email"));
        catalog.add(tool("grant_access", "Give a person access to a resource at a level.",
                accessArgs(), List.of("resource_id", "email", "level"), SideEffects.IDEMPOTENT,
                inv -> change(inv, true)), new ToolDeclaration("access", ToolEffect.GRANT, "email"));
        catalog.add(tool("revoke_access", "Take a person's access to a resource at a level away.",
                accessArgs(), List.of("resource_id", "email", "level"), SideEffects.IDEMPOTENT,
                inv -> change(inv, false)), new ToolDeclaration("access", ToolEffect.REVOKE, "email"));
        catalog.add(tool("send_message", "Send a person a direct message.",
                Map.of("to_email", str("Recipient's work email"), "text", str("Message text")),
                List.of("to_email", "text"), SideEffects.EXTERNAL, inv -> {
                    String to = lower(inv.stringArgument("to_email"));
                    if (!people.containsKey(to)) {
                        return ToolResult.error("No person with the email " + to);
                    }
                    String text = inv.stringArgument("text");
                    if (text == null || text.isBlank()) {
                        return ToolResult.error("A message needs text");
                    }
                    messages.add(new Message(to, text.strip(), Instant.now()));
                    save();
                    return ToolResult.ok("Message sent to " + to);
                }), new ToolDeclaration("chat", ToolEffect.NOTIFY, "to_email"));
        return catalog;
    }

    // ---------------------------------------------------------------- state, for tests and the desk

    public synchronized Map<String, Person> people() {
        return Map.copyOf(people);
    }

    public synchronized Map<String, Resource> resources() {
        return Map.copyOf(resources);
    }

    public synchronized Set<Access> access() {
        return Set.copyOf(access);
    }

    public synchronized List<Message> messages() {
        return List.copyOf(messages);
    }

    // ---------------------------------------------------------------- helpers

    private synchronized ToolResult change(ToolInvocation inv, boolean grant) {
        String resourceId = inv.stringArgument("resource_id");
        String email = lower(inv.stringArgument("email"));
        String level = lower(inv.stringArgument("level"));
        Resource resource = resources.get(resourceId);
        if (resource == null) {
            return ToolResult.error("No resource with id " + resourceId);
        }
        if (!people.containsKey(email)) {
            return ToolResult.error("No person with the email " + email);
        }
        if (!resource.levels().contains(level)) {
            return ToolResult.error(resource.name() + " has no level " + level + "; its levels are " + resource.levels());
        }
        boolean had = access.stream().anyMatch(a -> a.resource_id().equals(resourceId) && a.email().equals(email)
                && a.level().equals(level));
        if (grant) {
            if (!had) {
                access.add(new Access(resourceId, email, level, Instant.now()));
                save();
            }
            return ToolResult.ok((had ? "Already had " : "Granted ") + level + " on " + resource.name() + " to " + email);
        }
        access.removeIf(a -> a.resource_id().equals(resourceId) && a.email().equals(email) && a.level().equals(level));
        save();
        return ToolResult.ok((had ? "Revoked " : "Did not have ") + level + " on " + resource.name() + " from " + email);
    }

    private void save() {
        if (stateFile == null) {
            return;
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Path temp = stateFile.resolveSibling("state.json.tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),
                    new State(List.copyOf(access), List.copyOf(messages)));
            Files.move(temp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the company systems' state", e);
        }
    }

    private FunctionTool tool(String name, String description, Map<String, Object> properties, List<String> required,
                              SideEffects sideEffects, Function<ToolInvocation, ToolResult> handler) {
        return FunctionTool.builder(name, description)
                .schema(Map.of("type", "object", "properties", properties, "required", required))
                .sideEffects(sideEffects)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> {
                    synchronized (this) {
                        return handler.apply(inv);
                    }
                })
                .build();
    }

    private static Map<String, Object> accessArgs() {
        return Map.of("resource_id", str("Resource id from list_resources"), "email", str("Work email"),
                "level", str("Access level, one of the resource's levels"));
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static ToolResult read(Object value, String missing) {
        return value == null ? ToolResult.error(missing) : json(value);
    }

    private static ToolResult json(Object value) {
        try {
            return ToolResult.ok(MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return ToolResult.error("Could not render the result: " + e.getMessage());
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
