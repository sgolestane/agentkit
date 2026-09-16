package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Why {@code ToolInvocation}'s own bounds cannot fire on a payload read back off Temporal
 * history — measured, rather than argued (#172).
 *
 * <h2>The hazard, which is real and is in the type</h2>
 *
 * <p>{@code ToolInvocation}'s constructor calls {@code Frozen.deeply}, which throws
 * {@code IllegalArgumentException} beyond 100,000 expanded values or 100 levels of nesting.
 * A {@code ToolInvocation} rides back from the tool activity on {@link ToolOutcome#effective}
 * and inside {@link PendingApproval}, and an activity result is deserialized <em>on the
 * workflow thread</em> — where a throw fails the workflow task, which Temporal retries
 * forever. It would be a stall on replay too: the payload is already in history, so every
 * replay throws again and nothing drains it. {@code Frozen.MAX_NODES}' own javadoc calls
 * that the worst failure mode in this codebase and names this issue.
 *
 * <h2>Why it is nonetheless latent, which is what these tests pin</h2>
 *
 * <p>The same bound guards the writing side. Every {@code ToolInvocation} that reaches
 * history was constructed in process first — by a gate's {@code effectiveFor}, by the park a
 * gate raised, or by the loop out of a {@code ToolUseBlock} — and the constructor that
 * refuses it on the way out is the constructor that would refuse it on the way in. So the
 * reader can only ever be handed something the writer accepted, <em>provided the round trip
 * does not change the quantity the two bounds are taken of</em>. That proviso is the whole
 * of the argument and it is the thing these tests measure: node count and nesting depth are
 * preserved exactly across {@link DurableJson}, over every shape {@code Frozen.deeply}
 * permits.
 *
 * <p>Measured at the cliff: 99,999 arguments is 1,477,952 bytes of JSON, is accepted by the
 * writer, and comes back through the workflow thread's deserializer in about 200 ms. 100,100
 * arguments — the size {@code ApprovalVerdict} and {@code Frozen.MAX_NODES} both quote from
 * #132 — is refused by the writer before anything can be recorded, and a hand-written
 * payload of that size is refused by the reader. Nothing in this repository can produce one.
 *
 * <h2>What changed since #172 was filed, and what did not</h2>
 *
 * <p>Two things narrowed it. #133 gave {@code Frozen} a second entry point without a node
 * budget and moved {@code Goal} and {@code AgentConfig} — the other parsed payloads a
 * workflow thread reconstructs — onto it. #134 made {@code deeply} hand back a map it
 * already produced, so the loop building a {@code ToolInvocation} out of an already-frozen
 * {@code ToolUseBlock} no longer walks the arguments a second time; on this path that second
 * walk was paid per replay of every step.
 *
 * <p>What did not change is the decision #172 asks for, and it is taken here as "no":
 * {@code ToolInvocation} does <strong>not</strong> get a second constructor, factory or
 * {@code Frozen} method distinguishing "built in process" from "read back off a wire". There
 * is no defect for the split to fix — that is what the first two tests below measure — and
 * #169 already paid for the shape it would take: a second path named for its caller's
 * provenance, whose correctness lives in prose where a green build cannot catch getting it
 * wrong. #170 proposing that several more records hold a {@code ToolInvocation} argues the
 * same way: one constructor with one contract, more load-bearing, not two with a rule
 * between them. #172's third item — that {@code Frozen}'s javadoc say its node budget is
 * meaningless on a parsed payload — was done by #133 and cites this issue by number.
 */
class DurableToolInvocationBoundsTest {

    /**
     * One under {@code Frozen.MAX_NODES}: the largest flat argument map a worker can build.
     *
     * <p>Not read off {@code Frozen}, which keeps it private, and deliberately not made
     * smaller: the claim is about the cliff, and a test that stood 90,000 values away from it
     * would pass while the two sides disagreed by 10,000.
     */
    private static final int BIGGEST_A_WRITER_ACCEPTS = 99_999;

    /** The size #132 measured a stalled run at, and the first one both sides refuse. */
    private static final int FIRST_SIZE_REFUSED = 100_100;

    private static Map<String, Object> flat(int values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values; i++) {
            map.put("k" + i, i);
        }
        return map;
    }

    @Test
    void theBiggestInvocationAWorkerCanWriteIsOneTheWorkflowThreadCanReadBack() throws Exception {
        ObjectMapper mapper = DurableJson.objectMapper();
        ToolInvocation biggest = new ToolInvocation("t1", "publish", flat(BIGGEST_A_WRITER_ACCEPTS));

        // The completed-call shape: a ToolInvocation on the activity result the workflow
        // re-reads on every replay.
        ToolOutcome completed = ToolOutcome.of(biggest, ToolResult.ok("done"), false,
                Disposition.RAN, false);
        ToolOutcome readBack = mapper.readValue(mapper.writeValueAsString(completed),
                ToolOutcome.class);

        // The park shape: the same type one record deeper, and the one a run sits on for as
        // long as a person takes to answer.
        ToolOutcome parked = ToolOutcome.parked(new PendingApproval(biggest,
                ApprovalNeeded.because("a person must look"), "tk1"));
        ToolOutcome parkedBack = mapper.readValue(mapper.writeValueAsString(parked),
                ToolOutcome.class);

        assertThat(readBack.effective().arguments())
                .as("the writer accepted an invocation the workflow thread cannot read, which"
                        + " is a run that stalls on every replay rather than failing")
                .hasSize(BIGGEST_A_WRITER_ACCEPTS);
        assertThat(parkedBack.awaiting().invocation().arguments())
                .hasSize(BIGGEST_A_WRITER_ACCEPTS);
    }

    @Test
    void aboveTheBoundBothSidesRefuseAndTheWriterGetsToSayItFirst() throws Exception {
        ObjectMapper mapper = DurableJson.objectMapper();

        assertThatThrownBy(() -> new ToolInvocation("t1", "publish", flat(FIRST_SIZE_REFUSED)))
                .as("the writing side stopped refusing, so a payload no reader can accept can"
                        + " now be recorded in history")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 100000 values once expanded");
        assertThatThrownBy(() -> new ToolUseBlock("t1", "publish", flat(FIRST_SIZE_REFUSED)))
                .as("the block the model's own proposal arrives in stopped refusing")
                .isInstanceOf(IllegalArgumentException.class);

        // Hand-written, because nothing in this repository can produce it: this is what the
        // workflow thread would do with such a payload if one ever reached history, and it
        // is why the paragraph above is the load-bearing one rather than this.
        String json = handWritten(FIRST_SIZE_REFUSED);
        assertThatThrownBy(() -> mapper.readValue(json, ToolOutcome.class))
                .as("a payload above the bound now deserializes, which would only mean the"
                        + " reader's bound had moved without the writer's")
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRoundTripPreservesTheQuantityBothBoundsAreTakenOf() throws Exception {
        // The proviso the whole latency argument rests on. Frozen counts expanded values and
        // nesting levels; if a JSON round trip could add either, a payload the writer's
        // budget accepted could exceed the reader's and stall a run. Every shape
        // Frozen.deeply permits is here, including the two it rewrites — a Set becomes a
        // List, and every other Collection does too — because a rewrite is exactly where a
        // count could change without anybody noticing.
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("string", "text");
        shapes.put("int", 7);
        shapes.put("double", 1.5);
        shapes.put("boolean", true);
        shapes.put("null", null);
        shapes.put("list", List.of(1, 2, List.of(3, 4)));
        shapes.put("set", Set.of("only"));
        shapes.put("deque", new java.util.ArrayDeque<>(List.of("a", "b")));
        shapes.put("map", Map.of("deep", List.of(Map.of("deeper", 1))));
        shapes.put("empty", Map.of());
        ToolInvocation written = new ToolInvocation("t1", "publish", shapes);

        ObjectMapper mapper = DurableJson.objectMapper();
        ToolInvocation read = mapper.readValue(mapper.writeValueAsString(written),
                ToolInvocation.class);

        assertThat(nodes(read.arguments()))
                .as("the round trip changed how many values Frozen would count, so the two"
                        + " sides' budgets are no longer taken of the same quantity")
                .isEqualTo(nodes(written.arguments()));
        assertThat(depth(read.arguments()))
                .as("the round trip changed how deep Frozen would walk")
                .isEqualTo(depth(written.arguments()));
        assertThat(read.arguments())
                .as("the round trip is not even value-preserving, which is a larger problem"
                        + " than the one this test was written for")
                .isEqualTo(written.arguments());
    }

    @Test
    void theWorkflowThreadWalksAProposalsArgumentsOnceAndNotTwice() {
        // #134, on the path that pays for it. A ToolUseBlock is rebuilt by Jackson on every
        // replay of every step, and AgentWorkflowImpl builds a ToolInvocation out of it
        // immediately; before #134 that walked the whole argument tree a second time, per
        // replay. The map's identity is the test rather than a call counter, for the reason
        // Frozen's own javadoc gives: the class is static and an instrumented input map is
        // visited exactly once either way.
        //
        // It is also the reason ToolInvocation's bound is not even evaluated on that path
        // any more — the block's constructor took it, on the writing side, once.
        ToolUseBlock block = new ToolUseBlock("t1", "publish", flat(1_000));

        ToolInvocation invocation =
                new ToolInvocation(block.id(), block.name(), block.input());

        assertThat(invocation.arguments())
                .as("the durable loop walks a proposal's arguments a second time, on every"
                        + " replay of every step")
                .isSameAs(block.input());
    }

    /** A {@code ToolOutcome} payload no worker in this repository can write. */
    private static String handWritten(int values) {
        StringBuilder json = new StringBuilder(
                "{\"content\":\"\",\"isError\":false,\"provenance\":\"UNKNOWN\","
                        + "\"effective\":{\"id\":\"t1\",\"name\":\"publish\",\"arguments\":{");
        for (int i = 0; i < values; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"k").append(i).append("\":").append(i);
        }
        return json.append("}}}").toString();
    }

    /** Expanded values, counted the way {@code Frozen}'s node budget counts them. */
    private static int nodes(Object value) {
        int total = 0;
        if (value instanceof Map<?, ?> map) {
            for (Object member : map.values()) {
                total += 1 + nodes(member);
            }
        } else if (value instanceof Collection<?> items) {
            for (Object item : items) {
                total += 1 + nodes(item);
            }
        }
        return total;
    }

    /** Nesting levels, counted the way {@code Frozen}'s depth cap counts them. */
    private static int depth(Object value) {
        int deepest = 0;
        if (value instanceof Map<?, ?> map) {
            for (Object member : map.values()) {
                deepest = Math.max(deepest, depth(member));
            }
        } else if (value instanceof Collection<?> items) {
            for (Object item : items) {
                deepest = Math.max(deepest, depth(item));
            }
        } else {
            return 0;
        }
        return deepest + 1;
    }
}
