package dev.agentkit.host;

import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A tool with some of its arguments filled by the host, from the person asking, and hidden from the model.
 *
 * <p>The model's view of the tool has no such argument — not in the schema, not required — so it cannot be asked
 * for, argued over, or talked into a different value. Whatever the model sends under that name anyway is replaced.
 * A binding with no value (the person's directory record has no such field) refuses the call rather than send it
 * without: a connector told nothing about who is asking must not be left to guess.
 */
final class BoundTool extends ForwardingTool {

    private final Tool delegate;
    private final Map<String, String> paths;
    private final Principal principal;
    private final Map<String, Object> schema;

    /** @param paths each bound argument and the principal field it comes from, such as {@code principal.email} */
    BoundTool(Tool delegate, Map<String, String> paths, Principal principal) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.paths = Map.copyOf(paths);
        this.principal = Objects.requireNonNull(principal, "principal");
        this.schema = withoutArguments(delegate.inputSchema(), this.paths.keySet());
    }

    @Override
    protected Tool delegate() {
        return delegate;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return schema;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(name(), description(), schema, inputExamples());
    }

    @Override
    public List<Map<String, Object>> inputExamples() {
        return delegate.inputExamples().stream().map(example -> {
            Map<String, Object> shown = new LinkedHashMap<>(example);
            shown.keySet().removeAll(paths.keySet());
            return (Map<String, Object>) shown;
        }).toList();
    }

    @Override
    protected Tool rebuiltAround(Tool bound) {
        return new BoundTool(bound, paths, principal);
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Map<String, Object> arguments = new HashMap<>(invocation.arguments());
        for (Map.Entry<String, String> binding : paths.entrySet()) {
            var value = principal.value(binding.getValue());
            if (value.isEmpty()) {
                return ToolResult.error(name() + " needs " + binding.getValue() + " for the person asking, and their "
                        + "record has none, so it was not called.");
            }
            arguments.put(binding.getKey(), value.get());
        }
        return delegate.execute(new ToolInvocation(invocation.id(), invocation.name(), arguments));
    }

    /** The schema with {@code hidden} removed from its properties and its required list. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutArguments(Map<String, Object> schema, java.util.Set<String> hidden) {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        if (copy.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> kept = new LinkedHashMap<>((Map<String, Object>) properties);
            kept.keySet().removeAll(hidden);
            copy.put("properties", kept);
        }
        if (copy.get("required") instanceof List<?> required) {
            List<Object> kept = new ArrayList<>(required);
            kept.removeAll(hidden);
            copy.put("required", kept);
        }
        return copy;
    }

    /** Whether {@code schema} has a property named {@code argument}. */
    static boolean hasArgument(Map<String, Object> schema, String argument) {
        return schema.get("properties") instanceof Map<?, ?> properties && properties.containsKey(argument);
    }
}
