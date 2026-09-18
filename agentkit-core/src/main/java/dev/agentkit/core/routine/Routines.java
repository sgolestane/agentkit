package dev.agentkit.core.routine;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Cut;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Running a {@link Routine} again, with no model in the loop.
 *
 * <p>This is where the saving is: a job whose shape has settled costs the tool calls and nothing else, instead of a
 * model call per step to work out what it already worked out last time.
 *
 * <h2>A replay is not a bypass</h2>
 *
 * <p>Every call goes through the registry and the policy the deployment supplies, the way a model's call would:
 * the tool is bound to the run, the {@link TrustFloor}'s policy in force is asked first, and the floor lowers when a
 * step returns somebody else's words, exactly as it would in the agent loop. A gate that refuses
 * {@code aws_grant_access} refuses it whether a model asked or a recording did, and a replay that meets a refusal
 * stops there rather than working around it. Every call is reported to the observer with its
 * {@link Disposition}, so a replayed grant is in the same trail as a deliberated one.
 *
 * <p>It stops at the first sign that this run is not last run: a tool that is no longer registered, a policy that
 * refuses, wants a person or fails, an error result, an exception, or a placeholder the task does not fill. What has
 * already happened is in the {@link Replay}, so the caller can hand the rest to a model that knows what it is
 * picking up — which is what {@link RoutineAgent} does.
 */
public final class Routines {

    /** How much of a tool's output is kept per step, so a long replay does not become a context bomb. */
    public static final int MAX_OUTPUT_CHARS = 500;

    private static final Logger log = LoggerFactory.getLogger(Routines.class);

    private Routines() {
    }

    /** Runs {@code routine} for {@code task} under a single gate, with no floor and nobody watching. */
    public static Replay replay(Routine routine, Task task, ToolRegistry tools, ToolGate gate) {
        return replay(routine, task, tools, TrustFloor.none(gate), AgentRun.of("routine"), AgentObserver.NONE);
    }

    /**
     * Runs {@code routine} for {@code task}.
     *
     * @param floor    this run's policy, as the agent loop would apply it
     * @param run      the run the calls belong to: tools are bound to it and observers are told it
     * @param observer told of every call and how it ended
     */
    public static Replay replay(Routine routine, Task task, ToolRegistry tools, TrustFloor floor, AgentRun run,
                                AgentObserver observer) {
        Objects.requireNonNull(routine, "routine");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(observer, "observer");
        if (!routine.fits(task)) {
            return new Replay(routine, List.of(), 0, "the task does not fill " + routine.parameters(), false);
        }
        List<Replay.Step> done = new ArrayList<>();
        boolean lowered = false;
        List<RoutineStep> steps = routine.steps();
        for (int i = 0; i < steps.size(); i++) {
            RoutineStep step = steps.get(i);
            int number = i + 1;
            Map<String, Object> arguments = step.filledFor(task);
            ToolInvocation call = new ToolInvocation("routine-" + UUID.randomUUID(), step.tool(), arguments);
            observer.onToolProposed(run, number, call);

            Tool registered = tools.find(step.tool()).orElse(null);
            if (registered == null) {
                String reason = "no tool named " + step.tool() + " is registered";
                return stopped(routine, done, i, reason, lowered, run, observer, call, call, Disposition.UNKNOWN_TOOL);
            }
            Tool tool = registered.boundTo(run);
            GateResult verdict;
            try {
                verdict = floor.inForce(lowered).evaluate(tool, call);
            } catch (RuntimeException e) {
                // A gate that throws decides nothing, and a replay never treats "nothing was decided" as a yes.
                return stopped(routine, done, i, "the gate failed: " + e.getMessage(), lowered, run, observer, call,
                        call, Disposition.GATE_FAILED);
            }
            if (!verdict.allowed()) {
                String reason = verdict.reason().isBlank() ? "the gate did not allow it" : verdict.reason();
                Disposition disposition = verdict.awaiting().isPresent() ? Disposition.PARKED : Disposition.REFUSED;
                return stopped(routine, done, i, reason, lowered, run, observer, call, call, disposition);
            }
            ToolInvocation effective;
            try {
                effective = verdict.effectiveFor(call);
            } catch (IllegalArgumentException e) {
                return stopped(routine, done, i, "the gate's replacement was refused: " + e.getMessage(), lowered, run,
                        observer, call, call, Disposition.GATE_FAILED);
            }
            ToolResult result;
            try {
                // Attributed as the agent loop attributes it, so the floor lowers on the tool's declaration too.
                result = tool.execute(effective).attributedTo(tool);
            } catch (RuntimeException e) {
                // The message is passed on to whoever continues, and a tool's exception is where remote text turns
                // up, so it counts as the tool's words.
                return stopped(routine, done, i, step.tool() + " threw: " + e.getMessage(),
                        lowered || floor.lowersOn(tool.provenance()), run, observer, call, effective,
                        Disposition.THREW);
            }
            observer.onToolResult(run, number, call, effective, result, Disposition.RAN);
            // Before the error check: an error's text is passed on to whoever continues, so it is read either way.
            lowered = lowered || floor.lowersOn(result.provenance());
            if (result.isError()) {
                log.info("Routine for {} stopped at step {}: {} returned an error", routine.taskKind(), number,
                        step.tool());
                return new Replay(routine, done, i, Cut.to(result.content(), MAX_OUTPUT_CHARS), lowered);
            }
            done.add(new Replay.Step(step.tool(), Cut.to(result.content(), MAX_OUTPUT_CHARS), result.provenance()));
        }
        log.debug("Replayed {} step(s) of the routine for {}", done.size(), routine.taskKind());
        return new Replay(routine, done, -1, "", lowered);
    }

    private static Replay stopped(Routine routine, List<Replay.Step> done, int index, String reason, boolean lowered,
                                  AgentRun run, AgentObserver observer, ToolInvocation proposed,
                                  ToolInvocation effective, Disposition disposition) {
        observer.onToolResult(run, index + 1, proposed, effective, ToolResult.error(reason), disposition);
        log.info("Routine for {} stopped at step {}: {}", routine.taskKind(), index + 1, reason);
        return new Replay(routine, done, index, reason, lowered);
    }
}
