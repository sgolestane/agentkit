package dev.agentkit.examples.routine;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.routine.RoutineAgent;
import dev.agentkit.core.routine.RoutineBook;
import dev.agentkit.core.routine.TaskShape;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.Objects;

/**
 * An IT desk that unlocks locked-out accounts — the same five steps for every ticket, which is exactly the work a
 * model should stop being paid to rediscover.
 *
 * <p>Each ticket is a {@link Goal} whose description is the job and whose parameters are this ticket's values
 * ({@code employee_email}, {@code manager_email}), so {@link TaskShape#ofGoalParameters()} recognises every ticket
 * as the same job. The first few are worked out by the model and recorded; once three in a row have done exactly the
 * same thing, the rest are replayed by {@link RoutineAgent} with no model call. A ticket that does not fit — a
 * hardware token that cannot be reset remotely — stops the replay, and the model finishes it knowing what already
 * ran.
 */
public final class UnlockDesk {

    /** The job, word for word the same on every ticket. */
    public static final String JOB = "Unlock a locked-out employee's account";

    /** The procedure the model follows while the job is still being learned. */
    public static final String PROCEDURE = """
            You run the IT desk's account-unlock procedure. For the employee in the ticket, call these tools in \
            this order, one call per step, with exactly these arguments:
            1. directory_lookup(email = the employee's email)
            2. okta_unlock(email = the employee's email)
            3. okta_reset_mfa(email = the employee's email)
            4. notify(to_email = the employee's email, template = "account_unlocked", about_email = the employee's email)
            5. notify(to_email = the manager's email, template = "manager_copy", about_email = the employee's email)
            If okta_reset_mfa cannot be done remotely, call it_create_ticket(for_email = the employee's email, \
            category = "hardware_token_reset") and use template "token_reset_pending" instead of "account_unlocked" \
            in step 4. Never repeat a step that has already been done. When you are finished, reply with one short \
            sentence saying what was done.""";

    /** How one ticket was handled, and what it cost. */
    public record Outcome(String employee, Path path, int modelCalls, long inputTokens, long outputTokens,
                          String answer) {
    }

    /** Which way a ticket went. */
    public enum Path {
        /** Worked out by the model, and recorded. */
        MODEL,
        /** Replayed from the settled routine, with no model call. */
        REPLAYED,
        /** Replay started, hit something it had not seen, and the model finished the job. */
        REPLAY_THEN_MODEL
    }

    private final UnlockDeskSystems systems;
    private final RoutineBook book = new RoutineBook();
    private final RoutineAgent agent;
    private Counter counting = new Counter();

    /**
     * @param watching told of every run as well, deliberated or replayed; {@link AgentObserver#NONE} for nobody
     */
    public UnlockDesk(LlmClient llm, String model, UnlockDeskSystems systems, AgentObserver watching) {
        this.systems = Objects.requireNonNull(systems, "systems");
        AgentConfig config = AgentConfig.builder(model).systemPrompt(PROCEDURE).maxSteps(12).maxTokens(1024).build();
        this.agent = RoutineAgent.builder(
                        (observer, floor) -> Agent.builder(llm, systems.registry(), config)
                                .name("unlock-desk").observer(observer).trustFloor(floor).build(),
                        book, TaskShape.ofGoalParameters(), systems::registry,
                        () -> TrustFloor.none(ToolGate.ALLOW_ALL))
                .observer(new Both(new Tally(), Objects.requireNonNull(watching, "watching")))
                .build();
    }

    /** The ticket for one person. */
    public static Goal ticket(UnlockDeskSystems.Account account) {
        return new Goal(JOB, Map.of("employee_email", account.email(), "manager_email", account.manager()));
    }

    /** Handles one ticket. Tickets are handled one at a time. */
    public synchronized Outcome handle(UnlockDeskSystems.Account account) {
        counting = new Counter();
        AgentResult result = agent.run(ticket(account));
        Path path = !counting.replayed ? Path.MODEL : counting.handedOver ? Path.REPLAY_THEN_MODEL : Path.REPLAYED;
        return new Outcome(account.email(), path, counting.modelCalls, counting.inputTokens, counting.outputTokens,
                result.output());
    }

    /** The routine the desk has settled on, if it has. */
    public String routine() {
        return book.settled().stream().map(r -> r.describe()).findFirst().orElse("(not settled)");
    }

    public UnlockDeskSystems systems() {
        return systems;
    }

    private static final class Counter {
        boolean replayed;
        boolean handedOver;
        int modelCalls;
        long inputTokens;
        long outputTokens;
    }

    /** Counts what each ticket cost, from the same observer stream a tracer would see. */
    private final class Tally implements AgentObserver {
        @Override
        public void onStart(AgentRun run, Goal goal) {
            if (RoutineAgent.RUN_NAME.equals(run.name())) {
                counting.replayed = true;
            }
        }

        @Override
        public void onModelResponse(AgentRun run, int step, LlmResponse response) {
            counting.modelCalls++;
            counting.inputTokens += response.usage().inputTokens();
            counting.outputTokens += response.usage().outputTokens();
        }

        @Override
        public void onFinish(AgentRun run, AgentResult result) {
            if (RoutineAgent.RUN_NAME.equals(run.name()) && !result.isSuccess()) {
                counting.handedOver = true;
            }
        }
    }

    /** Two observers, in order. */
    private record Both(AgentObserver first, AgentObserver second) implements AgentObserver {
        @Override
        public void onStart(AgentRun run, Goal goal) {
            first.onStart(run, goal);
            second.onStart(run, goal);
        }

        @Override
        public void onModelResponse(AgentRun run, int step, LlmResponse response) {
            first.onModelResponse(run, step, response);
            second.onModelResponse(run, step, response);
        }

        @Override
        public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
            first.onToolProposed(run, step, invocation);
            second.onToolProposed(run, step, invocation);
        }

        @Override
        public void onToolResult(AgentRun run, int step, ToolInvocation proposed, ToolInvocation effective,
                                 ToolResult result, Disposition disposition) {
            first.onToolResult(run, step, proposed, effective, result, disposition);
            second.onToolResult(run, step, proposed, effective, result, disposition);
        }

        @Override
        public void onFinish(AgentRun run, AgentResult result) {
            first.onFinish(run, result);
            second.onFinish(run, result);
        }

        @Override
        public void onTextDelta(AgentRun run, int step, String delta) {
            first.onTextDelta(run, step, delta);
            second.onTextDelta(run, step, delta);
        }
    }
}
