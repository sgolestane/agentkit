package dev.agentkit.core.tool;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.retrieval.Bm25Index;
import dev.agentkit.core.util.OneLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link ToolRegistry} that implements <em>progressive tool disclosure</em>.
 *
 * <p>Tools are registered as either <em>always-available</em> (advertised to the
 * model from the first turn) or <em>deferred</em> (hidden until discovered). A
 * synthetic {@code search_tools} tool is advertised whenever the library contains
 * any deferred tools; when the model calls it, the best-matching <em>deferred</em>
 * tools are revealed and become available on subsequent turns.
 *
 * <p><strong>Advertised order is a stable, growing prefix:</strong> the search
 * tool (if present) sits first, followed by revealed tools in the order they were
 * revealed. New reveals only ever append, so the serialized tool block grows
 * monotonically and the provider prompt cache is preserved.
 *
 * <p><strong>Disclosure gates advertising, not authorization.</strong> A deferred
 * tool remains resolvable via {@link #find(String)} and therefore executable if
 * the model calls it by name before revealing it — this is deliberate robustness
 * (a model may recall a tool across turns). Use always-available vs deferred to
 * control what the model <em>sees</em>, not what it is <em>permitted</em> to run;
 * for authorization/gating see the verification subsystem.
 *
 * <p><strong>State:</strong> the set of revealed tools is mutated as a run
 * progresses, so a registry instance is <em>per-run</em>. Build a fresh registry
 * for each agent run (construction is cheap; the BM25 index is built once here).
 * It is not thread-safe and must not be shared across concurrent runs.
 */
public final class DisclosingToolRegistry implements ToolRegistry {

    /** Default name of the synthetic search tool. */
    public static final String DEFAULT_SEARCH_TOOL_NAME = "search_tools";

    private final Map<String, Tool> all;
    private final Set<String> deferredNames;
    private final Set<String> revealed;
    private final Bm25Index deferredIndex;
    private final int searchLimit;
    private final Tool searchTool;

    private DisclosingToolRegistry(Builder b) {
        if (b.all.containsKey(b.searchToolName)) {
            throw new IllegalArgumentException(
                    "A registered tool collides with the search tool name '" + b.searchToolName + "'");
        }
        this.all = new LinkedHashMap<>(b.all);
        this.searchLimit = b.searchLimit;

        // revealed starts with the always-available tools, in registration order.
        this.revealed = new LinkedHashSet<>(b.alwaysAvailable);

        // deferred = everything else, in registration order.
        this.deferredNames = new LinkedHashSet<>(all.keySet());
        this.deferredNames.removeAll(b.alwaysAvailable);

        Map<String, String> corpus = new LinkedHashMap<>();
        for (String name : deferredNames) {
            corpus.put(name, name + " " + all.get(name).description());
        }
        this.deferredIndex = Bm25Index.of(corpus);

        this.searchTool = buildSearchTool(b.searchToolName);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<Tool> find(String name) {
        if (searchTool.name().equals(name)) {
            return Optional.of(searchTool);
        }
        return Optional.ofNullable(all.get(name));
    }

    /**
     * All registered user tools, in registration order. Excludes the synthetic
     * search tool (which is resolvable via {@link #find(String)} but is not a
     * user-registered capability).
     */
    @Override
    public List<Tool> tools() {
        return List.copyOf(all.values());
    }

    @Override
    public List<ToolSpec> advertisedSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        if (!deferredNames.isEmpty()) {
            specs.add(searchTool.spec());
        }
        for (String name : revealed) {
            specs.add(all.get(name).spec());
        }
        return List.copyOf(specs);
    }

    /**
     * Reveals a registered tool by name, making it advertised from now on. No-op
     * if the name is unknown or already revealed. Lets later subsystems (skills,
     * knowledge base) force-surface a tool without a search.
     */
    public DisclosingToolRegistry reveal(String name) {
        if (all.containsKey(name)) {
            revealed.add(name);
        }
        return this;
    }

    /** The names currently revealed to the model, in reveal order. */
    public Set<String> revealedNames() {
        return new LinkedHashSet<>(revealed);
    }

    private Tool buildSearchTool(String name) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "Keywords describing the capability you need")),
                "required", List.of("query"));
        return FunctionTool.builder(name,
                        "Search the tool library for tools matching a capability, and make them "
                                + "available to call. Use this when no advertised tool fits the task.")
                .schema(schema)
                .readOnly()
                .handler(this::handleSearch)
                // Tool names and descriptions out of the library, which for an
                // MCP-backed one is a remote server's text — the shortest path a
                // hostile server has to the model. This handler already fences it.
                .provenance(Provenance.THIRD_PARTY)
                .build();
    }

    private ToolResult handleSearch(ToolInvocation invocation) {
        String query = invocation.stringArgument("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("The 'query' argument is required and must be non-blank.");
        }
        if (deferredIndex.size() == 0) {
            return ToolResult.ok("There are no additional tools to discover.");
        }
        // Rank all deferred tools, then take the top-N that are not already revealed.
        List<Bm25Index.Scored> hits = deferredIndex.search(query, deferredIndex.size());
        List<String> newlyRevealed = new ArrayList<>();
        for (Bm25Index.Scored hit : hits) {
            if (revealed.add(hit.id())) {
                newlyRevealed.add(hit.id());
                if (newlyRevealed.size() >= searchLimit) {
                    break;
                }
            }
        }
        if (newlyRevealed.isEmpty()) {
            return ToolResult.ok("No new tools matched \"" + query + "\". Try different keywords.");
        }
        // Names and descriptions are the tool author's, and for an MCP tool that means the
        // server's. This is the same content LlmPlanner and SkillLibrary fence as a
        // catalog; it arrives through a tool result only because progressive disclosure
        // hands it over on demand rather than up front. The header is ours and stays out.
        List<String> entries = new ArrayList<>();
        for (String name : newlyRevealed) {
            entries.add("- " + OneLine.of(name) + ": " + OneLine.of(all.get(name).description()));
        }
        return ToolResult.ok("Revealed " + newlyRevealed.size()
                + " tool(s) — you can now call them:\n"
                + Spotlight.wrap(Spotlight.Kind.CATALOG, Source.of("revealed-tools"),
                        String.join("\n", entries)));
    }

    /** Builder for {@link DisclosingToolRegistry}. */
    public static final class Builder {
        private final Map<String, Tool> all = new LinkedHashMap<>();
        private final Set<String> alwaysAvailable = new LinkedHashSet<>();
        private int searchLimit = 5;
        private String searchToolName = DEFAULT_SEARCH_TOOL_NAME;

        private Builder() {
        }

        /** Registers a tool that is advertised to the model from the first turn. */
        public Builder alwaysAvailable(Tool tool) {
            register(tool);
            alwaysAvailable.add(tool.name());
            return this;
        }

        /** Registers a tool that is hidden until revealed by a search. */
        public Builder deferred(Tool tool) {
            register(tool);
            return this;
        }

        public Builder searchLimit(int searchLimit) {
            if (searchLimit <= 0) {
                throw new IllegalArgumentException("searchLimit must be > 0");
            }
            this.searchLimit = searchLimit;
            return this;
        }

        public Builder searchToolName(String searchToolName) {
            this.searchToolName = Objects.requireNonNull(searchToolName, "searchToolName");
            return this;
        }

        private void register(Tool tool) {
            Objects.requireNonNull(tool, "tool");
            if (all.containsKey(tool.name())) {
                throw new IllegalArgumentException("A tool named '" + tool.name() + "' is already registered");
            }
            all.put(tool.name(), tool);
        }

        public DisclosingToolRegistry build() {
            return new DisclosingToolRegistry(this);
        }
    }
}
