package dev.agentkit.otel;

import io.opentelemetry.api.common.AttributeKey;

/**
 * The GenAI semantic-convention names this module emits.
 *
 * <p>Spelled out here rather than taken from {@code opentelemetry-semconv-incubating}
 * deliberately. That artifact is alpha and renames things between releases; a library
 * that depended on it would force its churn on every application that uses AgentKit,
 * and these are ultimately just strings. The cost is that they can drift from the spec
 * — hence the version stamp below, so a reader knows what to diff against.
 *
 * <p>Tracks the GenAI conventions as of semconv 1.43.0. Note the conventions themselves
 * are still <em>experimental</em>: names have changed before ({@code gen_ai.system} →
 * {@code gen_ai.provider.name}) and may change again, so treat dashboards built on them
 * as needing maintenance.
 */
final class GenAi {

    private GenAi() {
    }

    // --- operations (span names are "{operation} {target}") ---

    static final String OPERATION_CHAT = "chat";
    static final String OPERATION_INVOKE_AGENT = "invoke_agent";
    static final String OPERATION_EXECUTE_TOOL = "execute_tool";

    /**
     * Not a convention value — the GenAI conventions have no operation for an approval
     * gate. Hence the {@code agentkit.} prefix, and hence {@code gen_ai.operation.name} is
     * left unset on that span rather than filled with something invented.
     */
    static final String OPERATION_GATE_TOOL = "agentkit.gate_tool";

    // --- attributes ---

    static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> PROVIDER_NAME = AttributeKey.stringKey("gen_ai.provider.name");
    static final AttributeKey<String> REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    static final AttributeKey<Long> REQUEST_MAX_TOKENS = AttributeKey.longKey("gen_ai.request.max_tokens");
    static final AttributeKey<Boolean> REQUEST_STREAM = AttributeKey.booleanKey("gen_ai.request.stream");
    static final AttributeKey<java.util.List<String>> RESPONSE_FINISH_REASONS =
            AttributeKey.stringArrayKey("gen_ai.response.finish_reasons");
    static final AttributeKey<Long> USAGE_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    static final AttributeKey<Long> USAGE_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    static final AttributeKey<String> TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");
    static final AttributeKey<String> AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
    static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id");

    static final String TOKEN_TYPE_INPUT = "input";
    static final String TOKEN_TYPE_OUTPUT = "output";

    /** Stable across all conventions, not GenAI-specific. */
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    // --- metrics ---

    static final String METRIC_TOKEN_USAGE = "gen_ai.client.token.usage";
    static final String METRIC_TOKEN_USAGE_UNIT = "{token}";
    static final String METRIC_OPERATION_DURATION = "gen_ai.client.operation.duration";
    static final String METRIC_OPERATION_DURATION_UNIT = "s";

    /**
     * The bucket boundaries the conventions advise for these instruments. The SDK's
     * generic default (first non-zero boundary 5, top 10000) is unusable for a duration
     * in seconds and for token counts alike — see the note at the call site.
     */
    static final java.util.List<Double> OPERATION_DURATION_BUCKETS = java.util.List.of(
            0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92);

    static final java.util.List<Long> TOKEN_USAGE_BUCKETS = java.util.List.of(
            1L, 4L, 16L, 64L, 256L, 1024L, 4096L, 16384L, 65536L, 262144L,
            1048576L, 4194304L, 16777216L, 67108864L);

    // --- AgentKit's own, outside the conventions ---

    /**
     * Which part of the framework made the call — {@code agent}, {@code compaction},
     * {@code verification}, and so on. There is no GenAI attribute for this, but without
     * it every span is indistinguishable, and the framework's own model calls can
     * dominate a run's cost (see {@code UsageMeter}).
     */
    static final AttributeKey<String> AGENTKIT_ROLE = AttributeKey.stringKey("agentkit.llm.role");

    /** How the run ended, from {@code AgentResult.stopReason()}. */
    static final AttributeKey<String> AGENTKIT_STOP_REASON = AttributeKey.stringKey("agentkit.agent.stop_reason");

    /** How many loop steps the run took. */
    static final AttributeKey<Long> AGENTKIT_STEPS = AttributeKey.longKey("agentkit.agent.steps");

    /**
     * The loop's own token totals, rolled up onto the run span. Under {@code agentkit.*}
     * rather than {@code gen_ai.usage.*} because the child chat spans already carry that
     * attribute — summing it across spans would count the same tokens twice.
     */
    static final AttributeKey<Long> AGENTKIT_LOOP_INPUT_TOKENS =
            AttributeKey.longKey("agentkit.agent.loop.input_tokens");
    static final AttributeKey<Long> AGENTKIT_LOOP_OUTPUT_TOKENS =
            AttributeKey.longKey("agentkit.agent.loop.output_tokens");

    /** Whether the tool reported a failure via {@code ToolResult.isError()}. */
    static final AttributeKey<Boolean> AGENTKIT_TOOL_ERROR = AttributeKey.booleanKey("agentkit.tool.error");

    /**
     * Who wrote what the tool returned — the declaration, never the content.
     *
     * <p>A trust class and not a body, so it does not reopen the question this module
     * settles by recording no prompts: three enum constants say nothing about the user's
     * data. It is the first reader of {@code Provenance} in the framework, and the one #60
     * asked for by name when it said observers "cannot say where content originated".
     */
    static final AttributeKey<String> AGENTKIT_TOOL_PROVENANCE =
            AttributeKey.stringKey("agentkit.tool.provenance");

    /**
     * The gate's <em>answer</em>: whether it permitted the invocation.
     *
     * <p>Not a record of what ran, and it was read as one (#121). A gate can answer allow
     * and the call still not proceed, because a replacement that renames the tool or
     * renumbers the call is refused afterwards by {@code GateResult.effectiveFor}. Read
     * {@link #AGENTKIT_GATE_OUTCOME} for what the runner will actually do; this one stays
     * what its name says.
     */
    static final AttributeKey<Boolean> AGENTKIT_GATE_ALLOWED = AttributeKey.booleanKey("agentkit.gate.allowed");

    /** Whether the gate approved with edited arguments rather than the ones proposed. */
    static final AttributeKey<Boolean> AGENTKIT_GATE_REPLACED = AttributeKey.booleanKey("agentkit.gate.replaced");

    /**
     * Why this gate stopped the call: {@code denied}, {@code parked}, {@code refused}, or
     * {@code failed}.
     *
     * <p><strong>Present only when this gate stopped it.</strong> Absence means this gate
     * did not — not that the call ran, which this gate cannot know. That distinction is the
     * whole design of the attribute and it was got wrong first time: an earlier version also
     * emitted {@code allowed}, and a traced gate that is a <em>member</em> of a
     * {@code ToolGates.allOf} then asserted {@code allowed} for a call a later member
     * refused, with no {@code execute_tool} span beside it. That is the #121 shape exactly,
     * reintroduced one composition step out and now stated affirmatively. The values that
     * remain are terminal wherever the gate sits: a denial, a park, a refused substitution
     * and a throw each stop the call on their own, whatever the rest of the chain says.
     *
     * <p>Terminal is not the same as final, and {@code parked} is the one that shows the
     * difference. It stops this call as surely as a denial does — {@code allowed} is
     * {@code false} and nothing runs — while a decision may still arrive and a later call
     * may carry the same action through. A trace records what this gate did, and what it
     * did was stop the call and ask.
     *
     * <p>{@code refused} is the gate answering allow with a replacement the runner will not
     * honour — the case that showed {@code gate.allowed=true}, {@code gate.replaced=true}
     * and then no {@code execute_tool} span at all, "as though the request evaporated",
     * which is the sentence {@code TracingToolGate}'s own class javadoc opens with as the
     * thing it exists to prevent. {@code failed} is a gate that threw for its own reasons
     * and so reached no decision either.
     *
     * <p>{@link #AGENTKIT_GATE_ALLOWED} still carries this gate's own answer, which is a
     * different question and always available.
     */
    static final AttributeKey<String> AGENTKIT_GATE_OUTCOME = AttributeKey.stringKey("agentkit.gate.outcome");

    /** The gate refused the call; the model gets the reason. */
    static final String GATE_OUTCOME_DENIED = "denied";

    /**
     * The gate stopped the call pending a person's decision.
     *
     * <p>Its own value rather than folding into {@link #GATE_OUTCOME_DENIED}, because the
     * two are different facts about what happens next and an operator counting them wants
     * them apart. A denial is finished — the model was told no and adapted. A park is a
     * question outstanding, and a dashboard where those are the same number cannot show
     * that a queue is growing or that a run has been sitting on somebody's desk since
     * Friday. The distinction is also the only place a trace records that a run <em>can</em>
     * come back, which is the difference between a stalled run and a finished one.
     */
    static final String GATE_OUTCOME_PARKED = "parked";

    /** The gate allowed with a replacement the runner will not honour. */
    static final String GATE_OUTCOME_REFUSED = "refused";

    /** The gate threw, so it reached no decision and the call will not proceed. */
    static final String GATE_OUTCOME_FAILED = "failed";
}
