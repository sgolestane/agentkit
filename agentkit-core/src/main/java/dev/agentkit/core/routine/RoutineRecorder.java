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
 * Watches a run and, if it succeeded, tells the {@link RoutineBook} what it did.
 *
 * <p>Attach it as an observer, the same way an eval harness attaches its own:
 *
 * <pre>{@code
 * RoutineRecorder recorder = new RoutineRecorder(shape, book);
 * AgentResult result = Agent.builder(llm, tools, config).observer(recorder).build().run(goal);
 * }</pre>
 *
 * <p><strong>What is recorded.</strong> The <em>effective</em> call — what actually reached the tool — and only when
 * it {@link Disposition#RAN} and returned no error. A gate that narrowed the arguments narrowed what happened, and
 * what happened is what a replay has to repeat. (An eval records the proposed call instead, because an eval scores
 * the model; this records the work.)
 *
 * <p><strong>What is not.</strong> A run that failed, stopped early or was refused tells us nothing about how the
 * work is done, and is not offered to the book at all — so it cannot establish a routine, and cannot break an
 * existing streak either.
 *
 * <p>One recorder serves one run.
 */
public final class RoutineRecorder implements AgentObserver {

    private final TaskShape shape;
    private final RoutineBook book;
    private final List<RoutineStep> steps = new ArrayList<>();
    private volatile Task task;

    public RoutineRecorder(TaskShape shape, RoutineBook book) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.book = Objects.requireNonNull(book, "book");
    }

    /** The task this run was recognised as, once it has started. */
    public Optional<Task> task() {
        return Optional.ofNullable(task);
    }

    /** What has been recorded so far, in order. */
    public synchronized List<RoutineStep> steps() {
        return List.copyOf(steps);
    }

    @Override
    public void onStart(AgentRun run, Goal goal) {
        task = shape.of(goal).orElse(null);
    }

    @Override
    public synchronized void onToolResult(AgentRun run, int step, ToolInvocation proposed, ToolInvocation effective,
                                          ToolResult result, Disposition disposition) {
        Task recognised = task;
        if (recognised == null || disposition != Disposition.RAN || result.isError()) {
            return;
        }
        steps.add(RoutineStep.recorded(effective.name(), effective.arguments(), recognised));
    }

    @Override
    public void onFinish(AgentRun run, AgentResult result) {
        Task recognised = task;
        if (recognised == null || !result.isSuccess()) {
            return;
        }
        book.observe(recognised, steps());
    }
}
