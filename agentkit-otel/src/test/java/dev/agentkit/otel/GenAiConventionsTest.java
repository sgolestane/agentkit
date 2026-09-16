package dev.agentkit.otel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the semantic-convention strings to literals.
 *
 * <p>{@link GenAi} exists so the spec names are hardcoded rather than pulled from the
 * alpha semconv artifact, which accepts that they can drift. The rest of the suite
 * asserts using those same constants, so it would pass happily with every key
 * misspelled — a one-character typo would ship silently and the traces would simply not
 * mean what the conventions say they mean. These are the assertions that would fail.
 *
 * <p>Verified against {@code opentelemetry-semconv-incubating} 1.43.0-alpha.
 */
class GenAiConventionsTest {

    @Test
    void attributeKeysMatchTheConventions() {
        assertThat(GenAi.OPERATION_NAME.getKey()).isEqualTo("gen_ai.operation.name");
        // Not gen_ai.system, which the conventions renamed away from.
        assertThat(GenAi.PROVIDER_NAME.getKey()).isEqualTo("gen_ai.provider.name");
        assertThat(GenAi.REQUEST_MODEL.getKey()).isEqualTo("gen_ai.request.model");
        assertThat(GenAi.REQUEST_MAX_TOKENS.getKey()).isEqualTo("gen_ai.request.max_tokens");
        assertThat(GenAi.REQUEST_STREAM.getKey()).isEqualTo("gen_ai.request.stream");
        assertThat(GenAi.RESPONSE_FINISH_REASONS.getKey()).isEqualTo("gen_ai.response.finish_reasons");
        // Not the deprecated prompt_tokens / completion_tokens spellings.
        assertThat(GenAi.USAGE_INPUT_TOKENS.getKey()).isEqualTo("gen_ai.usage.input_tokens");
        assertThat(GenAi.USAGE_OUTPUT_TOKENS.getKey()).isEqualTo("gen_ai.usage.output_tokens");
        assertThat(GenAi.TOKEN_TYPE.getKey()).isEqualTo("gen_ai.token.type");
        assertThat(GenAi.AGENT_NAME.getKey()).isEqualTo("gen_ai.agent.name");
        assertThat(GenAi.TOOL_NAME.getKey()).isEqualTo("gen_ai.tool.name");
        assertThat(GenAi.TOOL_CALL_ID.getKey()).isEqualTo("gen_ai.tool.call.id");
        // Stable across all conventions, and not exception.type — that is the span-event
        // attribute, not the one the metrics and spans want here.
        assertThat(GenAi.ERROR_TYPE.getKey()).isEqualTo("error.type");
    }

    @Test
    void ourOwnAttributesStayOutOfTheGenAiNamespace() {
        // These are AgentKit's, not the conventions'; putting them under gen_ai.* would
        // claim a meaning the spec does not give them.
        assertThat(GenAi.AGENTKIT_ROLE.getKey()).isEqualTo("agentkit.llm.role");
        assertThat(GenAi.AGENTKIT_STOP_REASON.getKey()).isEqualTo("agentkit.agent.stop_reason");
        assertThat(GenAi.AGENTKIT_STEPS.getKey()).isEqualTo("agentkit.agent.steps");
        assertThat(GenAi.AGENTKIT_TOOL_ERROR.getKey()).isEqualTo("agentkit.tool.error");
        assertThat(GenAi.AGENTKIT_LOOP_INPUT_TOKENS.getKey())
                .isEqualTo("agentkit.agent.loop.input_tokens");
        assertThat(GenAi.AGENTKIT_LOOP_OUTPUT_TOKENS.getKey())
                .isEqualTo("agentkit.agent.loop.output_tokens");
        assertThat(GenAi.AGENTKIT_GATE_ALLOWED.getKey()).isEqualTo("agentkit.gate.allowed");
        assertThat(GenAi.AGENTKIT_GATE_REPLACED.getKey()).isEqualTo("agentkit.gate.replaced");
        assertThat(GenAi.AGENTKIT_GATE_OUTCOME.getKey()).isEqualTo("agentkit.gate.outcome");
    }

    @Test
    void theGateOutcomeValuesAreAClosedSet() {
        // Pinned here for the reason this class exists: the rest of the suite asserts using
        // these same constants, so it would pass happily with every one of them misspelled.
        // A mutant moving the outcome key to "gen_ai.gate.outcom" — a typo *and* a squat in
        // the reserved namespace — survived the whole suite before this. There is no
        // "allowed" value on purpose: a gate cannot know whether the call proceeded.
        assertThat(GenAi.GATE_OUTCOME_DENIED).isEqualTo("denied");
        assertThat(GenAi.GATE_OUTCOME_REFUSED).isEqualTo("refused");
        assertThat(GenAi.GATE_OUTCOME_FAILED).isEqualTo("failed");
    }

    @Test
    void theGateOperationIsMarkedAsOursRatherThanBorrowed() {
        // The conventions have no operation for an approval gate, so this carries our
        // prefix — and the gate span leaves gen_ai.operation.name unset rather than
        // filling a convention field with something invented.
        assertThat(GenAi.OPERATION_GATE_TOOL).isEqualTo("agentkit.gate_tool");
    }

    @Test
    void operationAndTokenTypeValuesMatchTheConventions() {
        assertThat(GenAi.OPERATION_CHAT).isEqualTo("chat");
        assertThat(GenAi.OPERATION_INVOKE_AGENT).isEqualTo("invoke_agent");
        assertThat(GenAi.OPERATION_EXECUTE_TOOL).isEqualTo("execute_tool");
        assertThat(GenAi.TOKEN_TYPE_INPUT).isEqualTo("input");
        // "output", not the deprecated "completion" alias.
        assertThat(GenAi.TOKEN_TYPE_OUTPUT).isEqualTo("output");
    }

    @Test
    void metricNamesAndUnitsMatchTheConventions() {
        assertThat(GenAi.METRIC_TOKEN_USAGE).isEqualTo("gen_ai.client.token.usage");
        assertThat(GenAi.METRIC_TOKEN_USAGE_UNIT).isEqualTo("{token}");
        assertThat(GenAi.METRIC_OPERATION_DURATION).isEqualTo("gen_ai.client.operation.duration");
        assertThat(GenAi.METRIC_OPERATION_DURATION_UNIT).isEqualTo("s");
    }

    @Test
    void theHistogramBucketsSuitTheirUnits() {
        // The SDK's generic default starts at 5 and tops out at 10000. For a duration in
        // seconds that puts nearly every chat call in the first bucket; for token counts
        // it puts any modern context window in the overflow bucket. Either way the
        // percentiles are gone, which is the whole point of a histogram.
        assertThat(GenAi.OPERATION_DURATION_BUCKETS)
                .isSorted()
                .startsWith(0.01)
                .allMatch(boundary -> boundary > 0 && boundary <= 100);
        assertThat(GenAi.TOKEN_USAGE_BUCKETS)
                .isSorted()
                .startsWith(1L)
                .last().satisfies(top -> assertThat(top).isGreaterThan(1_000_000L));
    }
}
