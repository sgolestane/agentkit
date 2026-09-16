package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.runtime.Observers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A forwarder that cannot silently stop forwarding.
 *
 * <p>{@code Observers} exists because one execution now has two witnesses — the audit trail
 * and the record policy reads back — and {@code Agent.Builder.observer} holds one. Every
 * method on {@link AgentObserver} is a {@code default}, so a seventh callback added tomorrow
 * would compile here, reach neither member, and leave a run whose observer looked wired while
 * a row nobody asked for went missing. Same trap as {@code Tool}'s declarations before #296,
 * and not fixable from this side of the interface — what is fixable is noticing.
 *
 * <p>Two tests, because the reflection one alone would pass over a class that overrode every
 * callback and forwarded none of them.
 */
class ObserversForwardEveryCallbackTest {

    @Test
    void everyCallbackOnTheInterfaceIsOverriddenHere() {
        List<String> missing = new ArrayList<>();
        for (Method method : AgentObserver.class.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                Observers.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException absent) {
                missing.add(method.getName());
            }
        }

        assertThat(AgentObserver.class.getDeclaredMethods())
                .as("reflection found no callbacks at all, so this test measured nothing")
                .isNotEmpty();
        assertThat(missing)
                .as("a callback Observers does not override reaches neither member, and the"
                        + " default makes that compile")
                .isEmpty();
    }

    @Test
    void everyCallbackReachesEveryMember() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        AgentObserver both = Observers.of(first, second);
        AgentRun run = AgentRun.of("executor");
        ToolInvocation call = new ToolInvocation("call-1", "ticketing.get_ticket",
                Map.of("ticket_id", "INC0012345"));

        both.onStart(run, Goal.of("Work INC0012345."));
        both.onTextDelta(run, 1, "thinking");
        both.onModelResponse(run, 1, LlmResponse.of(
                Message.of(Role.ASSISTANT, TextBlock.of("hello")),
                LlmStopReason.END_TURN, TokenUsage.ZERO));
        both.onToolProposed(run, 1, call);
        both.onToolResult(run, 1, call, call, ToolResult.ok("read"), Disposition.RAN);
        both.onFinish(run, AgentResult.completed("done", 1));

        List<String> expected = List.of("onStart", "onTextDelta", "onModelResponse",
                "onToolProposed", "onToolResult", "onFinish");
        assertThat(first.seen).isEqualTo(expected);
        assertThat(second.seen)
                .as("a forwarder that stops at the first member is the failure this class"
                        + " exists to prevent")
                .isEqualTo(expected);
    }

    private static final class Recorder implements AgentObserver {

        private final List<String> seen = new ArrayList<>();

        @Override
        public void onStart(AgentRun run, Goal goal) {
            seen.add("onStart");
        }

        @Override
        public void onTextDelta(AgentRun run, int step, String delta) {
            seen.add("onTextDelta");
        }

        @Override
        public void onModelResponse(AgentRun run, int step, LlmResponse response) {
            seen.add("onModelResponse");
        }

        @Override
        public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
            seen.add("onToolProposed");
        }

        @Override
        public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                                 ToolInvocation effective, ToolResult result,
                                 Disposition disposition) {
            seen.add("onToolResult");
        }

        @Override
        public void onFinish(AgentRun run, AgentResult result) {
            seen.add("onFinish");
        }
    }
}
