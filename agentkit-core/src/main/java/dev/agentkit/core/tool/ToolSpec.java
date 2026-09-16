package dev.agentkit.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The public declaration of a {@link Tool}: the metadata sent to the model so it
 * can decide when and how to call the tool.
 *
 * <h2>Why the schema copy stops at one level</h2>
 *
 * <p>#133 listed {@code inputSchema} with six other shallow copies and asked whether
 * documenting it as shallow on purpose would be a better outcome than copying it. It is,
 * and this paragraph is that documentation rather than a warning to be careful: the two
 * things a deeper copy would buy are already bought elsewhere or are not worth buying.
 *
 * <p><strong>The untrusted supplier already copies, one layer up.</strong> The only schema
 * in this repository that a party outside the deployment writes is an MCP server's, and
 * {@code McpToolInfo} has walked it to every depth since #176 — holding the prose
 * annotations, copying every container, bounding the depth — before {@code McpTools.load}
 * ever builds a {@code ToolSpec} out of it. The map arriving here from that path is already
 * a deep, unmodifiable copy of something the server no longer has a reference to. Copying
 * it a second time is #134's defect in a different record.
 *
 * <p><strong>On every other path the schema is the tool author's own declaration about
 * their own tool.</strong> A mutation is the deployment editing a schema it wrote, for a
 * tool it wrote, and there is no gate reading the value and no tool acting on it
 * afterwards — the schema is read and rendered into the provider's {@code tools} array, and
 * that is all it is for. #133 says this exactly: "a mutation is a caller bug with no
 * security consequence".
 *
 * <p><strong>And it is paid on every workflow replay.</strong> A spec list is a component
 * of {@code DurableAgentRun}, the durable workflow input, so Jackson rebuilds every spec
 * through this constructor each time a run replays. Measured as bytes allocated by the
 * copy line itself, on a five-property schema of twenty expanded values: 280 B as it
 * stands, 2,315 B for {@code Frozen.containersOf} — 8.3x, per spec, per replay, over a map
 * Jackson has just parsed and shared with nobody. That is the worst ratio of the seven
 * types #133 lists, because a schema is nearly all containers: every property is its own
 * nested map. A twelve-tool catalogue would pay it twelve times a replay to defend against
 * nothing.
 *
 * <p>{@code examples} is copied one level further only because its component type is
 * {@code List<Map<...>>}: sealing the list without sealing the maps in it would be an
 * unmodifiable list of modifiable maps, which is not a shape worth shipping. It stops at
 * the same place for the same reasons.
 *
 * @param name        unique tool name; never {@code null} or blank
 * @param description natural-language description used by the model for tool
 *                    selection; never {@code null}
 * @param inputSchema JSON-Schema-style description of the tool's arguments
 *                    (typically {@code {"type":"object","properties":{...},
 *                    "required":[...]}}); never {@code null}. Stored as a
 *                    defensive, unmodifiable copy that is <strong>one level deep, on
 *                    purpose</strong> — a nested map or list inside the schema is shared
 *                    with the caller. See the class javadoc
 * @param examples    optional few-shot example argument sets, each a map matching
 *                    {@link #inputSchema}. Schemas alone underspecify correct usage;
 *                    a couple of exemplar calls measurably improve how the model
 *                    invokes a tool. May be empty; never {@code null}. Stored as a
 *                    defensive, unmodifiable copy.
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema,
                       List<Map<String, Object>> examples) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        // `examples` is a component added after the initial release. Tolerate an
        // absent/null value (older serialized payloads have no `examples` field, and
        // Jackson deserializes records through this canonical constructor) by
        // defaulting to empty rather than rejecting it.
        if (examples == null) {
            examples = List.of();
        } else {
            List<Map<String, Object>> copiedExamples = new ArrayList<>(examples.size());
            for (Map<String, Object> example : examples) {
                Objects.requireNonNull(example, "example");
                copiedExamples.add(Collections.unmodifiableMap(new LinkedHashMap<>(example)));
            }
            examples = Collections.unmodifiableList(copiedExamples);
        }
    }

    /** A spec with no examples. */
    public ToolSpec(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, List.of());
    }

    /** A schema for a tool that takes no arguments. */
    public static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
}
