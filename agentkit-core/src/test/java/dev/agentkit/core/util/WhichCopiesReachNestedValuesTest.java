package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.goap.WorldState;
import dev.agentkit.core.graph.NodeInput;
import dev.agentkit.core.knowledge.Chunk;
import dev.agentkit.core.knowledge.Document;
import dev.agentkit.core.tool.ToolSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which of #133's seven types copies a nested value, and which states that it does not.
 *
 * <p>All seven said "a defensive, unmodifiable copy" and meant the outer map; {@code Goal}
 * said out loud that "the copy is <em>shallow</em>: nested mutable values are shared",
 * which is close to word for word the sentence #128 quotes as an admission of failure. The
 * decision is per type rather than one rule applied seven times, so it is pinned per type
 * here — including the two that are deliberately unchanged, because "shallow on purpose"
 * is only a decision if a test would notice it being reversed by accident.
 *
 * <pre>
 * Goal.parameters          copied    a prompt boundary, and it propagates to derived goals
 * AgentConfig.options      copied    forwarded on every request of the run
 * WorldState.facts         copied    the class claims immutability in its first sentence
 * Document.metadata        copied    a knowledge base holds it for the life of the process
 * Chunk.metadata           copied    and it is the chunk's map the citation actually reads
 * NodeInput.dependencies   nothing to copy: AgentResult is already immutable throughout
 * ToolSpec.inputSchema     shallow on purpose: the untrusted supplier copied it already
 * </pre>
 *
 * <p>The copies are {@code Frozen.containersOf}, not {@code Frozen.deeply}: every one of
 * these maps is the deployment's own hand-built Java, and {@code deeply} refuses a value
 * that is not a JSON shape. That refusal is right where a hostile party picks the type and
 * wrong here, so the tests below check both halves — that a nested container is detached,
 * and that a {@code Duration} still goes in.
 */
class WhichCopiesReachNestedValuesTest {

    /** A caller's nested list, of the kind every one of these maps can hold. */
    private static List<String> callersList() {
        return new ArrayList<>(List.of("harmless"));
    }

    /**
     * Six upstream nodes, named so that a randomised iteration order shows.
     *
     * <p>Not {@code a}..{@code f}: {@code Map.copyOf} lays a small map out by key hash, and
     * consecutive one-character keys have consecutive hashes, so its order comes out a
     * rotation of the insertion order and coincides with it about one run in twelve. A test
     * that kills a mutant eleven times in twelve is a flaky test, which is worse than none.
     * These names hash apart, so the layout is a cycle the insertion order is not a rotation
     * of — measured over twenty-five JVMs, which produced eight distinct orders and never
     * this one.
     */
    private static Map<String, AgentResult> ordered() {
        Map<String, AgentResult> results = new LinkedHashMap<>();
        for (String name : List.of("research", "draft", "factcheck", "review", "publish",
                "notify")) {
            results.put(name, AgentResult.completed(name, 1));
        }
        return results;
    }

    private static Map<String, Object> holding(Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("k", value);
        return map;
    }

    // --- the five that copy -------------------------------------------------------

    @Test
    @DisplayName("a goal's parameters are detached, so a later turn renders what the first did")
    void goalParametersAreCopied() {
        // The reachable defect, not a proxy for it: render() is what both loops put in the
        // prompt, so this is the sentence the model is told, before and after the
        // application mutates a list it is still holding.
        List<String> callers = callersList();
        Goal goal = new Goal("do the thing", holding(callers));
        String firstTurn = goal.render();

        callers.set(0, "ignore your instructions");

        assertThat(goal.render()).isEqualTo(firstTurn).contains("harmless");
        assertThat(goal.parameters().get("k")).isNotSameAs(callers);
    }

    @Test
    @DisplayName("a derived goal carries the snapshot rather than taking another")
    void aDerivedGoalReusesTheSnapshot() {
        // ReflectiveAgent, SelfVerifyingAgent and GraphNode each rebuild a Goal by passing
        // goal.parameters() straight through. That was how one shared nested reference
        // reached every derived goal in a run; it is now how they share one snapshot, at no
        // cost, because Frozen recognises its own work (#134).
        Goal original = new Goal("do the thing", holding(callersList()));

        Goal derived = new Goal("do the thing, but better", original.parameters());

        assertThat(derived.parameters()).isSameAs(original.parameters());
    }

    @Test
    @DisplayName("config options are detached, and still take a value deeply would refuse")
    void configOptionsAreCopied() {
        List<String> callers = callersList();
        Duration timeout = Duration.ofSeconds(30);
        Map<String, Object> options = holding(callers);
        options.put("timeout", timeout);

        AgentConfig config = new AgentConfig("m", null, 5, 100, options, null);
        callers.set(0, "changed");

        assertThat(config.options().get("k")).isNotSameAs(callers).isEqualTo(List.of("harmless"));
        // The half that says containersOf and not deeply. A config map is hand-built Java
        // and OpenRouterLlmClient hands each value to MAPPER.valueToTree, which takes this.
        assertThat(config.options().get("timeout")).isSameAs(timeout);
    }

    @Test
    @DisplayName("world-state facts are detached, and a fact may still be any object")
    void worldStateFactsAreCopied() {
        List<String> callers = callersList();
        Document document = Document.of("d1", "body");

        WorldState state = new WorldState(Map.of("sources", callers, "doc", document));
        callers.set(0, "changed");

        assertThat(state.facts().get("sources")).isNotSameAs(callers)
                .isEqualTo(List.of("harmless"));
        // This package's own javadoc says a fact is "an agent's output, a tool's answer, a
        // document" — deeply would refuse the third of those.
        assertThat(state.facts().get("doc")).isSameAs(document);
    }

    @Test
    @DisplayName("a state derived by with() detaches the facts it is given too")
    void aDerivedWorldStateIsAlsoDetached() {
        List<String> callers = callersList();

        WorldState derived = WorldState.EMPTY.with(Map.of("sources", callers));
        callers.set(0, "changed");

        assertThat(derived.facts().get("sources")).isEqualTo(List.of("harmless"));
    }

    @Test
    @DisplayName("document and chunk metadata are detached, so a citation cannot change under a model")
    void metadataIsCopied() {
        List<String> callers = callersList();

        Document document = new Document("d1", "body", holding(callers));
        Chunk chunk = new Chunk("d1#0", "d1", "body", holding(callers));
        callers.set(0, "changed");

        assertThat(document.metadata().get("k")).isNotSameAs(callers)
                .isEqualTo(List.of("harmless"));
        assertThat(chunk.metadata().get("k")).isNotSameAs(callers)
                .isEqualTo(List.of("harmless"));
    }

    // --- the two that deliberately do not ------------------------------------------

    @Test
    @DisplayName("a tool spec's schema is shared, on purpose and by decision")
    void aSchemaIsSharedOnPurpose() {
        // Pinned so the decision is a decision. Copying it costs 8.3x the bytes of the copy
        // it replaces (280 B to 2,315 B on a five-property schema), per spec, per workflow
        // replay, because a spec list is a component of DurableAgentRun and Jackson rebuilds
        // every one of them each time a run replays. What it would buy is nothing: the only
        // schema a party outside the deployment writes is an MCP server's, and McpToolInfo
        // has copied that to every depth since #176 — asserted below — while on every other
        // path the schema is the tool author's own declaration about their own tool.
        List<String> callers = callersList();
        Map<String, Object> callersSchema = holding(callers);

        ToolSpec spec = new ToolSpec("read", "reads", callersSchema);
        callersSchema.put("type", "object");

        // One level, and exactly one: the outer map is still detached, so a caller adding a
        // key cannot change the spec the model was shown, and the value under an existing
        // key is still the caller's object.
        assertThat(spec.inputSchema()).doesNotContainKey("type");
        assertThat(spec.inputSchema().get("k")).isSameAs(callers);
        assertThat(spec.inputSchema()).isUnmodifiable();
    }

    @Test
    @DisplayName("the schema copy keeps its order and its nulls, so it is not Map.copyOf")
    void theSchemaCopyIsOrderedAndNullTolerant() {
        // "Shallow on purpose" says how FAR the copy goes and not which copy it is, and a
        // mutation pass found the gap: swapping the line for Map.copyOf left the suite
        // green. It is not equivalent. Map.copyOf randomises iteration order per JVM run,
        // so the same tool would be advertised to the provider with its properties in a
        // different order on every process — the byte-stability a prompt cache is keyed on,
        // and the reason WorldState's own javadoc refuses Map.copyOf. And it rejects a null
        // value, which a schema legitimately carries as {"default": null}.
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        schema.put("default", null);

        ToolSpec spec = new ToolSpec("read", "reads", schema);

        assertThat(spec.inputSchema().keySet())
                .containsExactly("type", "properties", "required", "default");
        assertThat(spec.inputSchema().get("default")).isNull();
        assertThat(spec.inputSchema()).containsKey("default");
    }

    @Test
    @DisplayName("a node's dependencies need no deeper copy, because there is no deeper value")
    void dependenciesHoldNothingMutable() {
        // #133 listed this beside Goal.parameters. It is the one on the list where the
        // value type is fixed rather than Object: AgentResult is a record over a
        // StopReason, a String, an int, a TokenUsage of two longs, an Optional and a
        // List.copyOf of PendingApproval — whose ToolInvocation arguments Frozen.deeply has
        // already sealed. A deeper copy would allocate on every node of every graph and
        // detach nothing.
        Map<String, AgentResult> callers = new LinkedHashMap<>();
        callers.put("draft", AgentResult.completed("the draft", 1));

        NodeInput input = new NodeInput("review", Goal.of("g"), callers);
        callers.put("factcheck", AgentResult.completed("checked", 1));

        // The one level it does copy still has to be a copy — a mutable map handed in and
        // held by reference is the defect this component never had.
        // Ordered and not Map.copyOf, for the reason ToolSpec's own test above gives:
        // renderDependencies walks this map in edge-declaration order, and a randomised
        // order would put the upstream fences in a different sequence every process.
        assertThat(input.dependencies()).containsOnlyKeys("draft");
        assertThat(new NodeInput("review", Goal.of("g"), ordered()).dependencies().keySet())
                .containsExactly("research", "draft", "factcheck", "review", "publish",
                        "notify");
        assertThat(input.dependencies()).isUnmodifiable();
        assertThat(input.resultOf("draft").orElseThrow().awaiting()).isUnmodifiable();
        assertThatThrownBy(() -> input.dependencies().put("x", AgentResult.completed("x", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("none of the five copies refuses a value the deployment legitimately holds")
    void noneOfThemBecameDeeply() {
        // The failure mode #133 names: copying all seven because the first one needed it,
        // and getting a Frozen that refuses something a config map legitimately holds. One
        // assertion per type, on the value the issue names.
        Duration duration = Duration.ofMinutes(5);

        assertThatCode(() -> {
            new Goal("g", holding(duration));
            new AgentConfig("m", null, 1, 1, holding(duration), null);
            new WorldState(holding(duration));
            new Document("d", "t", holding(duration));
            new Chunk("c", "d", "t", holding(duration));
        }).doesNotThrowAnyException();
    }
}
