package dev.agentkit.core.routine;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.ToolRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An agent that watches how its work is done, and stops paying a model to work it out again.
 *
 * <p>The first runs of a kind of job go to the model and are recorded ({@link RoutineRecorder}). Once the last few
 * runs have agreed on exactly the same sequence ({@link RoutineBook}), the next one is replayed instead: the same
 * tools, in the same order, with this task's values, through the same registry and gate, and no model call at all.
 *
 * <pre>{@code
 * RoutineAgent agent = new RoutineAgent(
 *         observer -> Agent.builder(llm, tools, config).observer(observer).build(),
 *         book, TaskShape.ofGoalParameters(), () -> tools, gate);
 *
 * agent.run(goal);   // learns for the first three; replays afterwards
 * }</pre>
 *
 * <p><strong>The factory must attach the observer it is given</strong>, the way an eval harness's does — otherwise
 * nothing is recorded and the agent never learns anything, silently.
 *
 * <h2>When the world has moved on</h2>
 *
 * <p>A replay stops at the first step that does not go as recorded (see {@link Routines}). What happens next is the
 * part worth understanding: the work is handed to the model, with what already ran quoted to it as evidence, so it
 * continues rather than starting the job again. A routine that grants access and then reports it must not grant it
 * twice because the report failed.
 *
 * <p>That is also why the steps a half-replay ran are not recorded as a routine: the shape changed, the streak
 * breaks, and the kind goes back to being worked out until it settles again.
 *
 * <h2>Why a settled job still goes to the model sometimes</h2>
 *
 * <p>A routine that always replays can never be found wrong. Nothing in a replay notices that the group a new hire
 * should join changed last month, as long as the old call still succeeds — and the model, which would have noticed,
 * is never asked again. So one run in {@link #DEFAULT_RECHECK_EVERY} is worked out by the model even though the
 * routine is settled: if it does the same thing, the streak continues and the recheck cost a run; if it does
 * something else, the streak breaks and the kind goes back to being worked out. That is the price of the saving,
 * and it is set by the deployment, not fixed here.
 */
public final class RoutineAgent {

    /** How a finished replay is reported, since no model wrote an answer. */
    @FunctionalInterface
    public interface Answer {
        String from(Task task, Replay replay);
    }

    /** One run in this many is worked out by the model even where a routine is settled. */
    public static final int DEFAULT_RECHECK_EVERY = 10;

    private static final Logger log = LoggerFactory.getLogger(RoutineAgent.class);

    private final Function<AgentObserver, Agent> agents;
    private final RoutineBook book;
    private final TaskShape shape;
    private final Supplier<ToolRegistry> tools;
    private final ToolGate gate;
    private final Answer answer;
    private final int recheckEvery;
    private final Map<String, AtomicLong> runsPerKind = new ConcurrentHashMap<>();

    /** Reports a replay as the steps it ran. */
    public static final Answer STEPS_TAKEN = (task, replay) ->
            "Done, as this task has been done the last " + replay.routine().timesSeen()
                    + " times, without a model:\n" + replay.describe();

    public RoutineAgent(Function<AgentObserver, Agent> agents, RoutineBook book, TaskShape shape,
                        Supplier<ToolRegistry> tools, ToolGate gate) {
        this(agents, book, shape, tools, gate, STEPS_TAKEN);
    }

    public RoutineAgent(Function<AgentObserver, Agent> agents, RoutineBook book, TaskShape shape,
                        Supplier<ToolRegistry> tools, ToolGate gate, Answer answer) {
        this(agents, book, shape, tools, gate, answer, DEFAULT_RECHECK_EVERY);
    }

    /**
     * @param recheckEvery one run in this many is worked out by the model even where a routine is settled; 1 means
     *                     never replay (record only), which is how to watch what a deployment would have saved
     */
    public RoutineAgent(Function<AgentObserver, Agent> agents, RoutineBook book, TaskShape shape,
                        Supplier<ToolRegistry> tools, ToolGate gate, Answer answer, int recheckEvery) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.book = Objects.requireNonNull(book, "book");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.answer = Objects.requireNonNull(answer, "answer");
        if (recheckEvery < 1) {
            throw new IllegalArgumentException("recheckEvery is at least 1, not " + recheckEvery);
        }
        this.recheckEvery = recheckEvery;
    }

    /** The routine this goal would be replayed with, if any: for a dashboard, a log, or a decision to trust it. */
    public Optional<Routine> routineFor(Goal goal) {
        return shape.of(goal).flatMap(book::established);
    }

    /** Runs the goal: replayed if its kind has settled, worked out by the model otherwise. */
    public AgentResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        Task task = shape.of(goal).orElse(null);
        if (task == null) {
            // Not a job this deployment recognises: nothing to replay, and nothing worth recording.
            return agents.apply(AgentObserver.NONE).run(goal);
        }
        long run = runsPerKind.computeIfAbsent(task.kind(), kind -> new AtomicLong()).incrementAndGet();
        Optional<Routine> established = book.established(task);
        if (established.isEmpty()) {
            return watched(goal);
        }
        if (run % recheckEvery == 0) {
            log.debug("Rechecking the routine for {}: this run goes to the model", task.kind());
            return watched(goal);
        }
        Replay replay = Routines.replay(established.get(), task, tools.get(), gate);
        if (replay.finished()) {
            log.debug("Replayed the routine for {} in {} step(s), no model call", task.kind(), replay.done().size());
            return AgentResult.completed(answer.from(task, replay), 0);
        }
        return watched(continuing(goal, replay));
    }

    private AgentResult watched(Goal goal) {
        return agents.apply(new RoutineRecorder(shape, book)).run(goal);
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
}
