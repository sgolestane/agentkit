package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.ModelPricing;
import dev.agentkit.core.reliability.TokenBudget;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.common.metadata.POJOActivityMethodMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the serializable boundary records (no Temporal environment). */
class DurableTypesTest {

    @Test
    void durableAgentOptionsRejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> new DurableAgentOptions(0, 1, 60, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DurableAgentOptions(60, 1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durableAgentOptionsRejectsMaxAttemptsBelowOne() {
        assertThatThrownBy(() -> new DurableAgentOptions(60, 0, 60, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DurableAgentOptions(60, 1, 60, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durableAgentOptionsDefaultsAreSensible() {
        DurableAgentOptions defaults = DurableAgentOptions.defaults();
        assertThat(defaults.llmMaxAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(defaults.llmStartToCloseSeconds()).isPositive();
    }

    @Test
    void agentRunResultFromCarriesErrorMessage() {
        AgentResult failed = AgentResult.failed(new IllegalStateException("boom"), 2,
                new TokenUsage(1, 1));
        AgentRunResult durable = AgentRunResult.from(failed);

        assertThat(durable.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(durable.errorMessage()).isEqualTo("boom");
        assertThat(durable.steps()).isEqualTo(2);
        assertThat(durable.isSuccess()).isFalse();
    }

    @Test
    void agentRunResultFromSuccessHasEmptyErrorMessage() {
        AgentRunResult durable = AgentRunResult.from(
                AgentResult.completed("done", 1, TokenUsage.ZERO));
        assertThat(durable.isSuccess()).isTrue();
        assertThat(durable.errorMessage()).isEmpty();
    }

    // --- durable budget (a core TokenBudget carried on the input) -----------

    @Test
    void aTokenBudgetRoundTripsAcrossTheWorkflowBoundary() throws Exception {
        // The cap crosses the wire as the very type the loop enforces, so there is no
        // mirrored copy that could drift from TokenBudget's caps.
        TokenBudget original = TokenBudget.builder()
                .maxTotalTokens(1_000)
                .maxCostUsd(5.00, ModelPricing.of(5.00, 25.00))
                .build();
        var mapper = DurableJson.objectMapper();

        TokenBudget restored = mapper.readValue(mapper.writeValueAsString(original), TokenBudget.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.isExhausted(new TokenUsage(600, 600))).isTrue();   // token cap
        assertThat(restored.isExhausted(new TokenUsage(999_999, 0))).isTrue(); // cost cap
        assertThat(restored.isExhausted(new TokenUsage(1, 1))).isFalse();
    }

    @Test
    void aTokenBudgetWithoutPricingRoundTrips() throws Exception {
        var mapper = DurableJson.objectMapper();
        TokenBudget original = TokenBudget.ofTotalTokens(100);

        TokenBudget restored = mapper.readValue(mapper.writeValueAsString(original), TokenBudget.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.pricing()).isNull();
    }

    // --- activity type names (a persisted wire format, #163) ---------------

    /**
     * The activity type names {@link ToolActivities} declares, pinned against a rename.
     *
     * <p><strong>These names are a wire format, and nothing said so.</strong> An
     * {@code @ActivityMethod} name is the activity <em>type</em> Temporal writes into
     * history, and a workflow replaying an in-flight run schedules the type its history
     * records. Rename one and every run that is mid-flight at deploy time schedules a type
     * no worker has registered; the call burns {@code toolMaxAttempts} and comes back an
     * error. {@code theBudgetWireShapeIsPinnedAgainstARenameInCore} makes exactly this
     * argument about a record's component names — this is the same argument about a
     * method's name, which had no test at all.
     *
     * <p>Derived from the interface through the SDK's own metadata rather than from a
     * literal list of method names, so it pins what Temporal will actually put on the wire:
     * the default type name is the method name with its first letter capitalised, and an
     * explicit {@code @ActivityMethod(name = …)} would override it silently.
     *
     * <p>The <em>count</em> is asserted too, because #163 is about it. Four is 2 entry
     * points × 1 run-scoped bit; a second run-scoped bit spelled the way the first one was
     * makes it eight. See {@code ToolActivities}' javadoc for why that is still the right
     * trade and what would have to be true to change it — this assertion is the thing that
     * makes the growth visible in a diff rather than arriving unremarked.
     */
    @Test
    void theActivityTypeNamesAreAWireFormatAndAreNotRenamedLightly() {
        assertThat(activityTypeNamesOf(ToolActivities.class))
                .containsExactlyInAnyOrder(
                        "ExecuteTool",
                        "ExecuteToolUnderLoweredTrust",
                        "ResumeTool",
                        "ResumeToolUnderLoweredTrust");
        assertThat(activityTypeNamesOf(LlmActivities.class)).containsExactly("Generate");
    }

    private static List<String> activityTypeNamesOf(Class<?> activityInterface) {
        return POJOActivityInterfaceMetadata.newInstance(activityInterface)
                .getMethodsMetadata().stream()
                .map(POJOActivityMethodMetadata::getActivityTypeName)
                .toList();
    }
}
