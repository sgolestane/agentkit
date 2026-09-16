package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The durable half of #260: a {@code ToolGate} that threw and a tool that threw are two
 * facts, and the operator's line says which.
 *
 * <p>{@code ToolActivitiesImpl} wrote {@code "(gate or execution threw)"} for both, exactly
 * as {@code Agent.runTool} did. This runner has the better excuse of the two — it does
 * separate them structurally, in the {@link dev.agentkit.core.tool.Disposition} that rides
 * out on the {@code ToolOutcome} and into Temporal history — but an operator's alert cannot
 * be built on history it has to open a run to read, and it certainly cannot be built on a
 * sentence that says "or".
 *
 * <p>Both ways in are driven and the level is asserted as well as the text, because half of
 * what #260 decided is that the two sit at different levels: a rename that left both at
 * {@code warn} would satisfy every assertion about wording and none of the point.
 */
class DurableBrokenGateIsNotAFlakyToolTest {

    private static final String TASK_QUEUE = "agentkit-broken-gate-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private static SimpleToolRegistry publishing(AtomicInteger sideEffects,
                                                 java.util.function.Supplier<ToolResult> body) {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes text somewhere public")
                        .schema(Map.of("type", "object",
                                "properties", Map.of("text", Map.of("type", "string"))))
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            sideEffects.incrementAndGet();
                            return body.get();
                        })
                        .build());
    }

    private AgentRunResult runDurably(SimpleToolRegistry tools, ToolGate gate) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        TemporalAgent.register(worker, new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "the payload")))
                .then(ScriptedLlm.text("done")), tools, gate);
        env.start();
        return TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(), tools.advertisedSpecs()));
    }

    @Test
    void aGateThatThrewAndAToolThatThrewAreTwoLinesAtTwoLevels() {
        AtomicInteger toolRuns = new AtomicInteger();
        String whenTheGateThrew = errWhile(() -> runDurably(
                publishing(toolRuns, () -> ToolResult.ok("published")),
                (tool, invocation) -> {
                    throw new IllegalStateException("the policy service is down");
                }));
        tearDown();
        env = null;

        assertThat(toolRuns.get())
                .as("the precondition: a gate that threw means nothing ran")
                .isZero();
        assertThat(whenTheGateThrew)
                .contains("Gate for tool 'publish' threw on the durable path")
                .contains("nothing was decided and nothing ran")
                .contains("the policy service is down");
        assertThat(whenTheGateThrew)
                .as("a broken policy was reported in the words used for a tool that ran and"
                        + " failed, so an operator alerting on one alerts on both")
                .doesNotContain("was entered and threw");
        assertThat(levelOfLineContaining(whenTheGateThrew, "Gate for tool"))
                .as("the channel an operator watches was left at the level used for an"
                        + " ordinary tool failure")
                .isEqualTo("ERROR");

        AtomicInteger entered = new AtomicInteger();
        String whenTheToolThrew = errWhile(() -> runDurably(
                publishing(entered, () -> {
                    throw new IllegalStateException("the file was locked");
                }), ToolGate.ALLOW_ALL));

        assertThat(entered.get())
                .as("the precondition: this is the branch where the tool body was entered")
                .isEqualTo(1);
        assertThat(whenTheToolThrew)
                .contains("Tool 'publish' was entered and threw on the durable path")
                .contains("the file was locked");
        assertThat(whenTheToolThrew)
                .as("an ordinary tool failure was reported as broken policy code")
                .doesNotContain("Gate for tool");
        assertThat(levelOfLineContaining(whenTheToolThrew, "was entered and threw"))
                .as("a failure the model routes around was raised to the level reserved for"
                        + " one nobody routes around")
                .isEqualTo("WARN");
    }

    @Test
    void aCallThatSucceededIsReportedAsNeither() {
        // The other side of the branch, because a line that fires either way reports nothing
        // and an unguarded warn passes every assertion above.
        AtomicInteger ran = new AtomicInteger();

        String captured = errWhile(() -> runDurably(
                publishing(ran, () -> ToolResult.ok("published")), ToolGate.ALLOW_ALL));

        assertThat(ran.get()).isEqualTo(1);
        assertThat(captured)
                .doesNotContain("was entered and threw")
                .doesNotContain("Gate for tool");
    }

    /**
     * Everything {@code body} writes to {@code System.err}, with the stream put back.
     *
     * <p>{@code slf4j-simple} resolves {@code System.err} on each write — {@code
     * cacheOutputStream} is off by default — so a logger initialised long before this call
     * still lands in the buffer, and a line written on a Temporal worker thread lands there
     * too, since the stream is process-wide. {@code DurableSettledCallTest} carries the same
     * helper for the same reason.
     */
    private static String errWhile(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * The slf4j level of the first captured line containing {@code needle}.
     *
     * <p>Fails loudly when the line is absent rather than answering something falsy: a helper
     * that returned "" for a missing line would turn every assertion built on it into one
     * that passes when nothing was logged.
     */
    private static String levelOfLineContaining(String captured, String needle) {
        // The level token after the bracketed thread name, matched rather than counted:
        // a Temporal worker's thread name has spaces in it, so "the second whitespace-
        // separated token" is the level on a main-thread line and part of a thread name on
        // a worker one. Found by regex against the levels that exist, so a line whose shape
        // this helper cannot read fails rather than answering something plausible.
        java.util.regex.Pattern level =
                java.util.regex.Pattern.compile("]\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s");
        for (String line : captured.split("\\R")) {
            if (line.contains(needle)) {
                java.util.regex.Matcher matcher = level.matcher(line);
                if (!matcher.find()) {
                    throw new AssertionError("unparseable log line: " + line);
                }
                return matcher.group(1);
            }
        }
        throw new AssertionError("no captured line contained: " + needle);
    }
}
