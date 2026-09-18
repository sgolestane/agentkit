package dev.agentkit.core.routine;

import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
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
 * <p>Every call goes through the registry the deployment supplies and the gate it supplies, exactly as a model's
 * call would. Policy is unchanged by a routine: a gate that refuses {@code aws_grant_access} refuses it whether a
 * model asked or a recording did, and a replay that meets a refusal stops there rather than working around it.
 *
 * <p>It stops at the first sign that this run is not last run: a tool that is no longer registered, a gate that
 * refuses or wants a person, an error result, an exception, or a placeholder the task does not fill. What has
 * already happened is in the {@link Replay}, so the caller can hand the rest to a model that knows what it is
 * picking up — which is what {@link RoutineAgent} does.
 */
public final class Routines {

    /** How much of a tool's output is kept per step, so a long replay does not become a context bomb. */
    public static final int MAX_OUTPUT_CHARS = 500;

    private static final Logger log = LoggerFactory.getLogger(Routines.class);

    private Routines() {
    }

    /**
     * Runs {@code routine} for {@code task}.
     *
     * @param gate the same gate the agent loop would apply; {@link ToolGate#ALLOW_ALL} if the deployment has none
     */
    public static Replay replay(Routine routine, Task task, ToolRegistry tools, ToolGate gate) {
        Objects.requireNonNull(routine, "routine");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(gate, "gate");
        if (!routine.fits(task)) {
            return new Replay(routine, List.of(), 0, "the task does not fill " + routine.parameters());
        }
        List<Replay.Step> done = new ArrayList<>();
        List<RoutineStep> steps = routine.steps();
        for (int i = 0; i < steps.size(); i++) {
            RoutineStep step = steps.get(i);
            Tool tool = tools.find(step.tool()).orElse(null);
            if (tool == null) {
                return stopped(routine, done, i, "no tool named " + step.tool() + " is registered");
            }
            Map<String, Object> arguments = step.filledFor(task);
            ToolInvocation call = new ToolInvocation("routine-" + UUID.randomUUID(), step.tool(), arguments);
            GateResult verdict;
            try {
                verdict = gate.evaluate(tool, call);
            } catch (RuntimeException e) {
                // A gate that throws decides nothing, and a replay never treats "nothing was decided" as a yes.
                return stopped(routine, done, i, "the gate failed: " + e.getMessage());
            }
            if (!verdict.allowed()) {
                return stopped(routine, done, i, verdict.reason().isBlank() ? "the gate did not allow it"
                        : verdict.reason());
            }
            ToolResult result;
            try {
                result = tool.execute(verdict.effectiveFor(call));
            } catch (RuntimeException e) {
                return stopped(routine, done, i, step.tool() + " threw: " + e.getMessage());
            }
            if (result.isError()) {
                return stopped(routine, done, i, Cut.to(result.content(), MAX_OUTPUT_CHARS));
            }
            done.add(new Replay.Step(step.tool(), Cut.to(result.content(), MAX_OUTPUT_CHARS)));
        }
        log.debug("Replayed {} step(s) of the routine for {}", done.size(), routine.taskKind());
        return new Replay(routine, done, -1, "");
    }

    private static Replay stopped(Routine routine, List<Replay.Step> done, int index, String reason) {
        log.info("Routine for {} stopped at step {}: {}", routine.taskKind(), index + 1, reason);
        return new Replay(routine, done, index, reason);
    }
}
