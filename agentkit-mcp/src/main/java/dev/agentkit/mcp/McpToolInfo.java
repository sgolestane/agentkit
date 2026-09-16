package dev.agentkit.mcp;

import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A tool advertised by an MCP server, as returned by {@code tools/list}.
 *
 * <p><strong>What a server says about itself is held here, not at the adapter (#176).</strong>
 * Being <em>listed</em> is enough: {@code McpTools.load} turns this record into a
 * {@code ToolSpec} and that goes into the provider's native {@code tools} array, in every
 * request for the whole run, in the position that describes what the model's own
 * instruments do. The model never has to take the bait — it does not have to call the tool,
 * or even consider calling it, for the text to be present. Measured before this:
 *
 * <pre>
 * payload present in description?  true
 * payload present in schema?       true
 * description survives un-normalised?  true   &lt;- a fullwidth canary comes through intact,
 *                                                so no pass touched it at all
 * description can spell a well-formed fence?  true
 * </pre>
 *
 * <p>That last line is the sharp one. A description can spell a <em>complete, well-formed
 * opening marker with a correct id</em>, because the nonce is a public hash of a body the
 * server writes — so {@code Spotlight.outsideFences} reports the forged block as fenced and
 * the audit oracle goes blind. That is the failure {@code Synthesizers.buildPrompt}'s
 * javadoc already records against itself, and it was reachable through a field nothing
 * normalised.
 *
 * <h2>Which of the three fields is held, and why they differ</h2>
 *
 * <p><strong>{@code description} and {@code inputSchema}: held.</strong> Both are
 * {@linkplain Spotlight#sizedAsFenced neutralised} — NFKC, format characters removed,
 * unpaired surrogates replaced, any marker taken apart — {@linkplain OneLine flattened} to
 * one line, and {@linkplain Cut cut}. Neither has a second reader: an advertisement's only
 * consumer is the model, so there is no wire meaning to preserve, which is exactly what
 * makes them cheap to hold and is why holding them belongs on this record rather than at
 * {@link McpTool}. A future adapter reading an {@code McpToolInfo} cannot get the raw text
 * into a prompt because it is not here to get.
 *
 * <p><strong>{@code name}: not held, deliberately.</strong> The name is also the registry
 * key and the identifier {@link McpTool#execute} puts back on the wire, MCP's own spec does
 * not promise this repository's name rule, and refusing a name here would let one oddly
 * named tool disable a whole server. The split is the one #154's PR settled: hold the name
 * wherever it is <em>printed</em> and leave the wire identifier alone — see
 * {@code McpTool.printed()}, which reduces it with {@code Spotlight.name} for the fence's
 * {@code source} attribute and for the framework's own sentence in a failure frame. A
 * newline in a name cannot forge a line in the {@code tools} array, because JSON escapes
 * it; where the name is rendered as a line of prose, {@code LlmPlanner} and
 * {@code DisclosingToolRegistry} already flatten it themselves.
 *
 * <h2>What is not done, and is not a slip</h2>
 *
 * <p><strong>No fence marker.</strong> The four places this repo fences a catalog —
 * {@code LlmPlanner}, {@code DisclosingToolRegistry}, {@code SkillLibrary},
 * {@code MemoryTools} — all render into prompt <em>text</em>, where a marker is more text.
 * A {@code description} is parsed by the provider's tool-calling machinery instead, and no
 * fence in this repository has been measured in that position. Shipping one here on the
 * strength of the other four working would be a fence nobody had measured, which is the
 * failure mode this repo calls decoration. That question is open in #194; the passes above
 * do not depend on it, since {@code sizedAsFenced} is {@code neutralise} with no markers at
 * all — it is what {@code Synthesizers.buildPrompt} applies to the operator's own goal slot
 * for precisely the "a fence would say the wrong thing here" reason, and it asks nothing of
 * the model's interpretation.
 *
 * <p><strong>Structural values stay the server's.</strong> Inside the schema only the
 * natural-language annotations are held ({@code description}, {@code title},
 * {@code $comment}). A property's <em>name</em>, a {@code type}, an {@code enum} member and
 * a {@code const} are what the model must emit verbatim for the server to accept the call,
 * so rewriting them would break the tool rather than protect the run — the same reasoning
 * as the tool name, one level down. A hostile server can still put a sentence in a property
 * name, and that reaches the model; it reaches it as a JSON key, cut to nothing that can
 * end a line, and buying that last inch costs the ability to call the tool.
 *
 * @param name        the tool's unique name, exactly as the server sent it — the registry
 *                    key and the wire identifier, held only where it is printed
 * @param description a natural-language description for model selection, neutralised,
 *                    flattened and cut (may be empty)
 * @param inputSchema the JSON-Schema description of the tool's arguments, structurally
 *                    intact with its prose annotations neutralised, flattened and cut.
 *                    Stored as an unmodifiable copy that reaches <em>every</em> nested map
 *                    and list, bounded at {@code MAX_SCHEMA_DEPTH}, and it has since #176 —
 *                    #133 listed this component as another one-level copy and it had
 *                    already stopped being one. Nothing changed here for #133 except this
 *                    sentence and a test that pins it, because a server's schema reaching
 *                    a {@code ToolSpec} un-copied is what makes that record's own
 *                    deliberately shallow copy safe
 */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema,
                          Map<String, Object> meta) {

    /**
     * The three-arg shape, for every caller that predates MCP Apps.
     *
     * <p>Additive, and the record's canonical constructor coerces a missing {@code meta} to an
     * empty map rather than refusing it — the rule this repository follows wherever a shape
     * crosses a version boundary, because a field a newer build added must not stop an older
     * one from reading what a server sent.
     */
    public McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, Map.of());
    }

    /**
     * Where a tool says it has a user interface: {@code _meta.ui.resourceUri}.
     *
     * <p>MCP Apps (SEP-1865, final 2026-01-26) is the first official MCP extension. A server
     * predeclares a {@code ui://} resource holding HTML and a tool points at it from its
     * metadata; a host that understands the extension fetches the resource and renders it,
     * and a host that does not sees an ordinary tool. That fallback is the whole reason this
     * is metadata rather than a new field, and it is why reading it is optional here too.
     *
     * <p>The deprecated flat spelling {@code _meta["ui/resourceUri"]} is accepted as well.
     * It is being phased out and servers in the wild still send it; refusing it would make
     * this host stricter than the spec asks and would show a person nothing where something
     * exists.
     *
     * @return the {@code ui://} uri, or empty if this tool declares none
     */
    public java.util.Optional<String> uiResourceUri() {
        Object nested = meta.get("ui");
        if (nested instanceof Map<?, ?> ui) {
            Object uri = ui.get("resourceUri");
            if (uri instanceof String text && !text.isBlank()) {
                return java.util.Optional.of(text);
            }
        }
        Object flat = meta.get("ui/resourceUri");
        return flat instanceof String text && !text.isBlank()
                ? java.util.Optional.of(text) : java.util.Optional.empty();
    }

    /**
     * How much of a description reaches the model.
     *
     * <p>Tighter than {@code ToolResult}'s 32,000 for a third-party <em>result</em>, and the
     * asymmetry is the point: a result is carried once, when the model chose to call the
     * tool; an advertisement is carried on every turn of the run whether it is used or not.
     * It matches {@code ToolResult.MAX_FAILURE_CHARS} instead, which is the other bound this
     * repo puts on text a server writes into a shape the framework owns.
     *
     * <p>A bound is <em>required</em> here rather than tidy, because the pass above it
     * expands and the far side chooses by how much: NFKC turns {@code U+FDFA} into eighteen
     * characters and marker removal replaces ten with twenty-two. Neutralising an unbounded
     * description would have made this change strictly worse than the raw field it replaced
     * — 200,000 characters in, 3,600,098 out, on every request. That is the amplifier
     * #154's review found on the result path, arriving on the advertisement path by way of
     * the fix for it.
     */
    private static final int MAX_DESCRIPTION_CHARS = 4_000;

    /**
     * How much prose the whole schema may spend, across every annotation at every depth.
     *
     * <p>A per-annotation cap alone bounds nothing: a schema with ten thousand properties
     * spends ten thousand times it. The budget is shared and drawn down in document order,
     * so a server can choose which of its annotations survive but not how many tokens the
     * deployment pays for them.
     */
    private static final int MAX_SCHEMA_PROSE_CHARS = 8_000;

    /**
     * How deep the walk goes before it stops copying and starts dropping.
     *
     * <p>Recursion over a structure the far side supplies; a schema nested past this is not
     * one a model was going to fill in correctly anyway. It fails closed — the subtree is
     * replaced by an empty container rather than passed through unheld, because passing it
     * through is precisely the hole being closed.
     */
    private static final int MAX_SCHEMA_DEPTH = 64;

    /**
     * The JSON-Schema keywords whose values are natural language rather than wire values.
     *
     * <p>An allowlist, and it is short on purpose. Every other keyword either names
     * something the model must reproduce exactly ({@code enum}, {@code const}, a property
     * name) or is a keyword the provider's own validator reads ({@code type},
     * {@code required}). Holding those would break calls; holding these costs a server
     * nothing it was entitled to.
     */
    private static final Set<String> PROSE_KEYWORDS = Set.of("description", "title", "$comment");

    public McpToolInfo {
        Objects.requireNonNull(name, "name");
        description = held(description == null ? "" : description, MAX_DESCRIPTION_CHARS);
        // A defensive, unmodifiable copy that tolerates null values — a schema from an
        // untrusted server may legitimately carry them (e.g. "default": null), and
        // Map.copyOf would reject those. Deep since #176: the annotations being held sit
        // at every depth, so the copy has to reach them, and a shallow copy would have
        // left the nested maps aliased to the caller's anyway.
        inputSchema = inputSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(
                        heldSchema(inputSchema, new int[] {MAX_SCHEMA_PROSE_CHARS}));
        // NOT run through heldSchema. That pass neutralises and bounds prose because a
        // schema's descriptions reach the MODEL on every turn; metadata does not reach the
        // model at all — it tells this host what to fetch. Neutralising a URI would break it,
        // and the defence a uri needs is the one uiResource applies when it fetches: refuse
        // anything that is not ui://.
        meta = meta == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    /**
     * {@code text} as it will reach the model: neutralised, on one line, and within
     * {@code maxChars}.
     *
     * <p>Cut, neutralise, cut — the idiom {@code Spotlight.fenceBounded} documents. The
     * first cut bounds the <em>work</em> an attacker can ask for, the second bounds what
     * arrives, and they are different numbers exactly because the pass between them
     * expands. {@code OneLine} sits inside the pair rather than outside it because it only
     * ever collapses, so the second cut still says what the result costs.
     *
     * <p>Flattened, not merely neutralised. Neutralising stops the text forging a marker;
     * it leaves every line terminator in place, and a listing built as {@code "- " + item}
     * is where those write entries of their own — a tool that does not exist, offered to
     * the model to choose. {@code LlmPlanner} and {@code DisclosingToolRegistry} flatten
     * for that reason where they render this text; doing it here means the next renderer
     * does not have to remember.
     */
    private static String held(String text, int maxChars) {
        return Cut.to(OneLine.of(Spotlight.sizedAsFenced(Cut.to(text, maxChars))), maxChars);
    }

    private static Map<String, Object> heldSchema(Map<String, Object> schema, int[] budget) {
        Map<String, Object> copy = new LinkedHashMap<>();
        schema.forEach((key, value) ->
                copy.put(key, heldValue(value, PROSE_KEYWORDS.contains(key), budget, 1)));
        return copy;
    }

    /**
     * One schema value, held if it is prose and copied if it is not.
     *
     * <p>{@code prose} follows the key down through arrays, because nothing stops a server
     * writing {@code "description": ["...", "..."]}; a schema that is not valid still
     * reaches the provider, and an invalid schema is the one a hostile server writes.
     */
    private static Object heldValue(Object value, boolean prose, int[] budget, int depth) {
        if (value instanceof Map<?, ?> nested) {
            if (depth > MAX_SCHEMA_DEPTH) {
                return Map.of();
            }
            Map<Object, Object> copy = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> copy.put(key,
                    heldValue(nestedValue, PROSE_KEYWORDS.contains(key), budget, depth + 1)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> items) {
            if (depth > MAX_SCHEMA_DEPTH) {
                return List.of();
            }
            List<Object> copy = new ArrayList<>(items.size());
            for (Object item : items) {
                copy.add(heldValue(item, prose, budget, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        if (prose && value instanceof String text) {
            return draw(text, budget);
        }
        return value;
    }

    /** One annotation's share of the schema's prose budget, and the budget after it. */
    private static String draw(String text, int[] budget) {
        if (budget[0] <= 0) {
            // Spent. Empty rather than the raw text: running out of budget is not a reason
            // to start emitting the thing the budget exists to bound.
            return "";
        }
        String held = held(text, budget[0]);
        budget[0] -= held.length();
        return held;
    }
}
