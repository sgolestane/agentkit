package dev.agentkit.core.routine;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent that watches how its work is done, and stops paying a model to work it out again.
 *
 * <p>The first runs of a kind of job go to the model and are recorded ({@link RoutineRecorder}). Once the last few
 * runs have agreed on exactly the same sequence ({@link RoutineBook}), the next one is replayed instead: the same
 * tools, in the same order, with this task's values, under the same policy, and no model call at all.
 *
 * <pre>{@code
 * RoutineAgent agent = RoutineAgent.builder(
 *                 (observer, floor) -> Agent.builder(llm, tools, config)
 *                         .observer(observer).trustFloor(floor).build(),
 *                 book, TaskShape.ofGoalParameters(), () -> tools, () -> TrustFloor.none(gate))
 *         .observer(tracing)
 *         .build();
 *
 * agent.run(goal);   // learns for the first three; replays afterwards
 * }</pre>
 *
 * <h2>One policy, built per run, for both paths</h2>
 *
 * <p>The factory is handed the {@link TrustFloor} to install, and a replay is held to the same one, so the two
 * paths cannot drift apart. A fresh floor is asked for on every run, because some policies are bound to one run —
 * {@code callableOnce}, a screening against the run's objective — and one shared across runs would spend its
 * allowance on the first. A supplier that hands out the same run-bound floor twice is refused.
 *
 * <p><strong>The factory must attach the observer and the floor it is given.</strong> Otherwise nothing is recorded,
 * or the model runs under a different policy from the replays, and neither shows up anywhere.
 *
 * <h2>When the world has moved on</h2>
 *
 * <p>A replay stops at the first step that does not go as recorded (see {@link Routines}). The kind is then forgotten
 * — the usual way just failed, so it is no longer the usual way — and the work is handed to the model with what
 * already ran quoted to it as evidence, so it continues rather than starting over: a routine that grants access and
 * then reports it must not grant it twice because the report failed. If a replayed step returned somebody else's
 * words, the model continues under the floor's tightened policy, as it would have if it had read them itself.
 *
 * <p>That continuation is not recorded. It did part of the job, and a routine learned from parts of jobs would, in
 * time, replay only the part.
 *
 * <h2>Why a settled job still goes to the model sometimes</h2>
 *
 * <p>A routine that always replays can never be found wrong. Nothing in a replay notices that the group a new hire
 * should join changed last month, as long as the old call still succeeds — and the model, which would have noticed,
 * is never asked again. So one run in {@link #DEFAULT_RECHECK_EVERY} is worked out by the model even though the
 * routine is settled: if it does the same thing, the streak continues and the recheck cost a run; if it does
 * something else, the streak breaks and the kind goes back to being worked out. That is the price of the saving,
 * and it is set by the deployment, not fixed here.
 *
 * <h2>What a replay reports</h2>
 *
 * <p>A replay is a run of its own, named {@code routine}: the observer sees it start, sees every call with its
 * {@link Disposition}, and sees it finish, so it is in the same trace and audit trail as a deliberated run. Its
 * result reports no model steps and no token usage, because it had none.
 */
public final class RoutineAgent {

    /** Builds the agent for one run of the job, with {@code observer} attached and {@code floor} installed. */
    @FunctionalInterface
    public interface Agents {
        Agent build(AgentObserver observer, TrustFloor floor);
    }

    /** How a finished replay is reported, since no model wrote an answer. */
    @FunctionalInterface
    public interface Answer {
        String from(Task task, Replay replay);
    }

    /** One run in this many is worked out by the model even where a routine is settled. */
    public static final int DEFAULT_RECHECK_EVERY = 10;

    /** What a replay's run is called in the trace. */
    public static final String RUN_NAME = "routine";

    /** Reports a replay as the steps it ran. */
    public static final Answer STEPS_TAKEN = (task, replay) ->
            "Done, as this task has been done the last " + replay.routine().timesSeen()
                    + " times, without a model:\n" + replay.describe();

    private static final Logger log = LoggerFactory.getLogger(RoutineAgent.class);

    private final Agents agents;
    private final RoutineBook book;
    private final TaskShape shape;
    private final Supplier<ToolRegistry> tools;
    private final Supplier<TrustFloor> floors;
    private final Answer answer;
    private final int recheckEvery;
    private final AgentObserver observer;
    private final AtomicReference<TrustFloor> lastFloor = new AtomicReference<>();

    private RoutineAgent(Builder b) {
        this.agents = b.agents;
        this.book = b.book;
        this.shape = b.shape;
        this.tools = b.tools;
        this.floors = b.floors;
        this.answer = b.answer;
        this.recheckEvery = b.recheckEvery;
        this.observer = b.observer;
    }

    /**
     * @param agents builds the agent for one run; must attach the observer and install the floor it is given
     * @param tools  the registry a replay calls, which should be the one the agent is built with
     * @param floors this deployment's tool policy, fresh for each run; {@code () -> TrustFloor.none(gate)} for a
     *               plain gate
     */
    public static Builder builder(Agents agents, RoutineBook book, TaskShape shape, Supplier<ToolRegistry> tools,
                                  Supplier<TrustFloor> floors) {
        return new Builder(agents, book, shape, tools, floors);
    }

    /** The routine this goal would be replayed with, if any: for a dashboard, a log, or a decision to trust it. */
    public Optional<Routine> routineFor(Goal goal) {
        return shape.of(goal).flatMap(book::established);
    }

    /** Runs the goal: replayed if its kind has settled, worked out by the model otherwise. */
    public AgentResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        TrustFloor floor = nextFloor();
        Task task = shape.of(goal).orElse(null);
        if (task == null) {
            // Not a job this deployment recognises: nothing to replay, and nothing worth recording.
            return agents.build(observer, floor).run(goal);
        }
        long started = book.started(task.kind());
        Optional<Routine> established = book.established(task);
        if (established.isEmpty()) {
            return watched(goal, floor);
        }
        if (started % recheckEvery == 0) {
            log.debug("Rechecking the routine for {}: this run goes to the model", task.kind());
            return watched(goal, floor);
        }

        AgentRun run = AgentRun.of(RUN_NAME);
        observer.onStart(run, goal);
        Replay replay = Routines.replay(established.get(), task, tools.get(), floor, run, observer);
        if (replay.finished()) {
            AgentResult result = AgentResult.completed(answer.from(task, replay), 0);
            observer.onFinish(run, result);
            log.debug("Replayed the routine for {} in {} step(s), no model call", task.kind(), replay.done().size());
            return result;
        }
        observer.onFinish(run, AgentResult.failed(
                new IllegalStateException("the routine stopped and the model took over: " + replay.reason()), 0));
        book.forget(task.kind());
        TrustFloor continuingUnder = replay.lowered() ? TrustFloor.none(floor.onceLowered()) : floor;
        return agents.build(observer, continuingUnder).run(continuing(goal, replay));
    }

    private AgentResult watched(Goal goal, TrustFloor floor) {
        return agents.build(both(new RoutineRecorder(shape, book), observer), floor).run(goal);
    }

    private TrustFloor nextFloor() {
        TrustFloor floor = Objects.requireNonNull(floors.get(), "the floor supplier returned null");
        TrustFloor previous = lastFloor.getAndSet(floor);
        if (floor == previous && floor.boundToOneRun()) {
            throw new IllegalStateException("The floor supplier returned the same policy twice, and that policy is "
                    + "bound to one run; supply a fresh one for each run");
        }
        return floor;
    }

    /**
     * The goal, plus what the abandoned replay already did.
     *
     * <p>Fenced as evidence: it is a report of what happened, not an instruction, and it is assembled from tool
     * output, which is somebody else's words.
     */
    private static Goal continuing(Goal goal, Replay replay) {
        if (!replay.changedAnything()) {
            return goal;
        }
        return new Goal(goal.description()
                + "\n\nThis task has a usual sequence, and it was started for you before it stopped. These steps have "
                + "already been done — carry on from there, and do not do them again:\n"
                + Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("routine"), replay.describe()),
                goal.parameters());
    }

    /** Both observers, in order; the second is skipped when it is the no-op one. */
    private static AgentObserver both(AgentObserver first, AgentObserver second) {
        if (second == AgentObserver.NONE) {
            return first;
        }
        return new AgentObserver() {
            @Override
            public void onStart(AgentRun run, Goal goal) {
                first.onStart(run, goal);
                second.onStart(run, goal);
            }

            @Override
            public void onTextDelta(AgentRun run, int step, String delta) {
                first.onTextDelta(run, step, delta);
                second.onTextDelta(run, step, delta);
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
        };
    }

    public static final class Builder {

        private final Agents agents;
        private final RoutineBook book;
        private final TaskShape shape;
        private final Supplier<ToolRegistry> tools;
        private final Supplier<TrustFloor> floors;
        private Answer answer = STEPS_TAKEN;
        private int recheckEvery = DEFAULT_RECHECK_EVERY;
        private AgentObserver observer = AgentObserver.NONE;

        private Builder(Agents agents, RoutineBook book, TaskShape shape, Supplier<ToolRegistry> tools,
                        Supplier<TrustFloor> floors) {
            this.agents = Objects.requireNonNull(agents, "agents");
            this.book = Objects.requireNonNull(book, "book");
            this.shape = Objects.requireNonNull(shape, "shape");
            this.tools = Objects.requireNonNull(tools, "tools");
            this.floors = Objects.requireNonNull(floors, "floors");
        }

        /** How a finished replay is reported; {@link #STEPS_TAKEN} by default. */
        public Builder answer(Answer answer) {
            this.answer = Objects.requireNonNull(answer, "answer");
            return this;
        }

        /**
         * One run in this many is worked out by the model even where a routine is settled; 1 means never replay
         * (record only), which is how to watch what a deployment would have saved before letting it.
         */
        public Builder recheckEvery(int recheckEvery) {
            if (recheckEvery < 1) {
                throw new IllegalArgumentException("recheckEvery is at least 1, not " + recheckEvery);
            }
            this.recheckEvery = recheckEvery;
            return this;
        }

        /** Told of every run, deliberated or replayed. */
        public Builder observer(AgentObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer");
            return this;
        }

        public RoutineAgent build() {
            return new RoutineAgent(this);
        }
    }
}
