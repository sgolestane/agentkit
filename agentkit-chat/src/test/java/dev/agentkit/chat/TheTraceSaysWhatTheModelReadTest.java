package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The trace answers the question people actually ask, which is not "what did the tool do".
 *
 * <p>When an answer is wrong the question is almost always "what did the model read" — and
 * until this, the trace recorded the size of the digest and not the digest. A row saying
 * {@code tickets.inbox -> 36 chars [RAN]} tells an operator that something happened and nothing
 * about why the answer was what it was.
 *
 * <p>#343 deferred this deliberately: nothing correlated a view back to the call that produced
 * it, and matching by position breaks the moment a tool returns two views. On the step there is
 * no correlation to invent.
 */
class TheTraceSaysWhatTheModelReadTest {

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();

    private SimpleToolRegistry oneTool(String answer) {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(FunctionTool.builder("tickets.inbox", "Open tickets.")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .readOnly()
                .handler(invocation -> ToolResult.ok(answer))
                .build());
        return registry;
    }

    private Turn run(SimpleToolRegistry registry,
            java.util.function.UnaryOperator<dev.agentkit.core.agent.Agent.Builder> configure) {
        Conversation conversation = store.create("acme", "");
        Turn turn = store.begin("acme", conversation.id(), "how many?", List.of());
        configure.apply(ChatAgents.builder(
                        new ScriptedLlm(ScriptedLlm.toolUse("t1", "tickets.inbox", Map.of()),
                                ScriptedLlm.text("Twelve.")),
                        registry, AgentConfig.builder("m").maxSteps(4).build(), store, events,
                        "acme", conversation.id(), turn.id()))
                .build()
                .run(Goal.of("how many?"));
        return store.turn("acme", conversation.id(), turn.id()).orElseThrow();
    }

    @Test
    void aToolStepCarriesTheDigestTheModelWasHanded() {
        Turn turn = run(oneTool("12 open tickets across 6 categories"), builder -> builder);

        Step tool = turn.stepsOf(Step.Kind.TOOL_CALL).get(0);
        assertThat(String.valueOf(tool.detail().get("digest")))
                .isEqualTo("12 open tickets across 6 categories");
        // And the size stays, because "the digest was 200,000 characters" is its own answer.
        assertThat(tool.detail()).containsKey("resultChars");
    }

    @Test
    void anEnormousDigestIsBoundedRatherThanWrittenWhole() {
        // Every step of every turn of every conversation goes into a file on the demo's path.
        Turn turn = run(oneTool("x".repeat(200_000)), builder -> builder);

        assertThat(String.valueOf(turn.stepsOf(Step.Kind.TOOL_CALL).get(0).detail().get("digest"))
                .length()).isLessThan(10_000);
    }

    @Test
    void aModelStepSaysWhatItActuallySaid() {
        // A turn whose answer is empty and whose model calls all say TOOL_USE is a run that
        // never got to speak, which is a different problem from one that answered badly.
        Turn turn = run(oneTool("12 open"), builder -> builder);

        assertThat(turn.stepsOf(Step.Kind.MODEL_CALL))
                .anySatisfy(step -> assertThat(String.valueOf(step.detail().get("text")))
                        .contains("Twelve."));
        assertThat(turn.stepsOf(Step.Kind.MODEL_CALL))
                .anySatisfy(step -> assertThat(step.detail()).containsKey("stopReason"));
    }

    @Test
    void aGateRefusalReadsAsARefusalRatherThanAsAnError() {
        // isError is true for a refused call, a broken gate and a tool that threw. The
        // disposition is the field that tells them apart, and an audit trail that reported an
        // unknown tool as a denial would invent a decision nobody made.
        Turn turn = run(oneTool("12 open"), builder -> builder.toolGate(
                (tool, invocation) -> GateResult.deny("Not in this deployment.")));

        Step refused = turn.stepsOf(Step.Kind.TOOL_CALL).get(0);
        assertThat(refused.detail()).containsEntry("disposition", "REFUSED");
        assertThat(refused.detail()).containsEntry("ran", false);
        // Both are carried, so a console does not have to choose which question it answers.
        assertThat(refused.detail()).containsEntry("isError", true);
    }

    @Test
    void aNarrowedCallRecordsWhatRanRatherThanWhatWasProposed() {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(FunctionTool.builder("files.read", "Reads a file.")
                .schema(Map.of("type", "object", "properties",
                        Map.of("path", Map.of("type", "string"))))
                .readOnly()
                .handler(invocation -> ToolResult.ok("contents of "
                        + invocation.stringArgument("path")))
                .build());
        Conversation conversation = store.create("acme", "");
        Turn begun = store.begin("acme", conversation.id(), "read it", List.of());
        ChatAgents.builder(
                        new ScriptedLlm(
                                ScriptedLlm.toolUse("t1", "files.read",
                                        Map.of("path", "/etc/shadow")),
                                ScriptedLlm.text("done")),
                        registry, AgentConfig.builder("m").maxSteps(4).build(), store, events,
                        "acme", conversation.id(), begun.id())
                .toolGate((tool, invocation) -> GateResult.allowWith(
                        new dev.agentkit.core.tool.ToolInvocation(invocation.id(),
                                invocation.name(), Map.of("path", "/tmp/harmless.txt"))))
                .build()
                .run(Goal.of("read it"));

        Step ran = store.turn("acme", conversation.id(), begun.id()).orElseThrow()
                .stepsOf(Step.Kind.TOOL_CALL).get(0);
        assertThat(String.valueOf(ran.detail().get("arguments"))).contains("/tmp/harmless.txt");
        // And what the model READ is the narrowed call's answer, not the proposal's.
        assertThat(String.valueOf(ran.detail().get("digest"))).contains("/tmp/harmless.txt");
        assertThat(String.valueOf(ran.detail().get("digest"))).doesNotContain("/etc/shadow");
    }
}
