package dev.agentkit.core.routine;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Watches a run and tells the {@link RoutineBook} what it did — or, if the run did not go cleanly, that the usual way
 * is in doubt.
 *
 * <p>Attach it as an observer, the same way an eval harness attaches its own:
 *
 * <pre>{@code
 * RoutineRecorder recorder = new RoutineRecorder(shape, book);
 * AgentResult result = Agent.builder(llm, tools, config).observer(recorder).build().run(goal);
 * }</pre>
 *
 * <p><strong>What is recorded.</strong> The <em>effective</em> call — what actually reached the tool — for a run that
 * completed and in which every call {@link Disposition#RAN} and returned no error. A gate that narrowed the arguments
 * narrowed what happened, and what happened is what a replay has to repeat. (An eval records the proposed call
 * instead, because an eval scores the model; this records the work.)
 *
 * <p><strong>What resets.</strong> Anything else: a run that failed or stopped early, and a run that completed after
 * a call was refused, parked, threw or returned an error. Recording such a run minus the call that went wrong would
 * teach the book a sequence nobody chose — a welcome message that was refused three times running would drop out of
 * the routine for good. So the book forgets the kind instead ({@link RoutineBook#forget}) and it is worked out
 * afresh. That errs towards paying a model more often, which is the direction to err in.
 *
 * <p><strong>One run.</strong> The recorder attaches itself to the first run that starts and ignores callbacks from
 * any other, so an observer that reaches a subagent's run does not splice its calls into the parent's.
 */
public final class RoutineRecorder implements AgentObserver {

    private final TaskShape shape;
    private final RoutineBook book;
    private final List<RoutineStep> steps = new ArrayList<>();
    private AgentRun watching;
    private Task task;
    private boolean tainted;

    public RoutineRecorder(TaskShape shape, RoutineBook book) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.book = Objects.requireNonNull(book, "book");
    }

    /** The task this run was recognised as, once it has started. */
    public synchronized Optional<Task> task() {
        return Optional.ofNullable(task);
    }

    /** What has been recorded so far, in order. */
    public synchronized List<RoutineStep> steps() {
        return List.copyOf(steps);
    }

    @Override
    public synchronized void onStart(AgentRun run, Goal goal) {
        if (watching != null) {
            return;
        }
        watching = run;
        task = shape.of(goal).orElse(null);
    }

    @Override
    public synchronized void onToolResult(AgentRun run, int step, ToolInvocation proposed, ToolInvocation effective,
                                          ToolResult result, Disposition disposition) {
        if (!run.equals(watching) || task == null || tainted) {
            return;
        }
        if (disposition != Disposition.RAN || result.isError()) {
            tainted = true;
            return;
        }
        try {
            steps.add(RoutineStep.recorded(effective.name(), effective.arguments(), task));
        } catch (RuntimeException e) {
            // A call that cannot be recorded is not left out of the recording: the run is not evidence.
            tainted = true;
        }
    }

    @Override
    public void onFinish(AgentRun run, AgentResult result) {
        Task recognised;
        List<RoutineStep> recorded;
        boolean clean;
        synchronized (this) {
            if (!run.equals(watching) || task == null) {
                return;
            }
            recognised = task;
            recorded = List.copyOf(steps);
            clean = !tainted && result.isSuccess();
        }
        if (clean) {
            book.observe(recognised, recorded);
        } else {
            book.forget(recognised.kind());
        }
    }
}
