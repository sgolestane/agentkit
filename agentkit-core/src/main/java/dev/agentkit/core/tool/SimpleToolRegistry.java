package dev.agentkit.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A straightforward {@link ToolRegistry} that advertises every registered tool.
 *
 * <p>Not thread-safe for concurrent registration; register all tools during
 * setup, then share the registry read-only across a run.
 */
public final class SimpleToolRegistry implements ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public SimpleToolRegistry() {
    }

    public SimpleToolRegistry(Collection<? extends Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        tools.forEach(this::register);
    }

    /**
     * Registers a tool.
     *
     * @throws IllegalArgumentException if a tool with the same name is already registered
     */
    public SimpleToolRegistry register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = tool.name();
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("A tool named '" + name + "' is already registered");
        }
        tools.put(name, tool);
        return this;
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    @Override
    public List<ToolSpec> advertisedSpecs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools.values()) {
            specs.add(tool.spec());
        }
        return List.copyOf(specs);
    }
}
