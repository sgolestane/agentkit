package dev.agentkit.core.routine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What each kind of work has looked like lately, and which kinds have settled into a routine.
 *
 * <h2>When a recording becomes a routine</h2>
 *
 * <p>When the last {@link #timesToEstablish} successful runs of a kind did <em>exactly</em> the same thing: the same
 * tools, in the same order, with the same arguments once the task's values are taken out. A streak rather than a
 * tally, because a tally cannot tell "this settled three runs ago" from "this was the usual way last year": one run
 * that does something else breaks the streak, and the kind goes back to being worked out by the model until it
 * settles again. That is the behaviour to want from a component that will later run without a model watching.
 *
 * <p>Only successful runs are offered here ({@link RoutineRecorder}), and only calls that actually ran. A run that
 * failed, was refused or stopped early says nothing about how the work is done.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a cache and not a plan. It holds no results — two runs of the same routine talk to the same systems and get
 * whatever those systems say — and nothing in it was written by anybody. It is a record of what happened, kept so
 * that {@link Routines#replay} can do it again without paying a model to rediscover it.
 *
 * <p>In memory, for one process. A routine that has to survive a restart is a straightforward addition and is not
 * here yet; until it is, a fresh process starts by watching again.
 */
public final class RoutineBook {

    /** Runs in a row that must agree before a kind counts as settled. */
    public static final int DEFAULT_TIMES_TO_ESTABLISH = 3;

    /** Kinds of work kept at once; the least recently seen is dropped. */
    public static final int DEFAULT_MAX_KINDS = 500;

    /** The longest sequence recorded: past this, a "routine" is a workflow and wants writing rather than recording. */
    public static final int DEFAULT_MAX_STEPS = 40;

    private final int timesToEstablish;
    private final int maxKinds;
    private final int maxSteps;

    /** Per kind, the most recent runs' step sequences, oldest first. Access-ordered, so eviction drops the stalest. */
    private final Map<String, Deque<List<RoutineStep>>> recent;

    public RoutineBook() {
        this(DEFAULT_TIMES_TO_ESTABLISH, DEFAULT_MAX_KINDS, DEFAULT_MAX_STEPS);
    }

    /**
     * @param timesToEstablish runs in a row that must agree; at least 2, because one run is an anecdote
     * @param maxKinds         kinds of work kept at once
     * @param maxSteps         the longest sequence recorded
     */
    public RoutineBook(int timesToEstablish, int maxKinds, int maxSteps) {
        if (timesToEstablish < 2) {
            throw new IllegalArgumentException("a routine takes at least two runs that agree, not " + timesToEstablish);
        }
        if (maxKinds < 1 || maxSteps < 1) {
            throw new IllegalArgumentException("maxKinds and maxSteps are at least 1");
        }
        this.timesToEstablish = timesToEstablish;
        this.maxKinds = maxKinds;
        this.maxSteps = maxSteps;
        this.recent = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<List<RoutineStep>>> eldest) {
                return size() > RoutineBook.this.maxKinds;
            }
        };
    }

    /**
     * Records what one successful run of {@code task} did.
     *
     * <p>A run with no steps, or more than {@link #DEFAULT_MAX_STEPS}, is not recorded and breaks any streak: it is
     * not evidence that the usual way still holds.
     */
    public synchronized void observe(Task task, List<RoutineStep> steps) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(steps, "steps");
        Deque<List<RoutineStep>> history = recent.computeIfAbsent(task.kind(), kind -> new ArrayDeque<>());
        if (steps.isEmpty() || steps.size() > maxSteps) {
            history.clear();
            return;
        }
        history.addLast(List.copyOf(steps));
        while (history.size() > timesToEstablish) {
            history.removeFirst();
        }
    }

    /** The routine for this task, if its kind has settled and the task fills every parameter the steps expect. */
    public synchronized Optional<Routine> established(Task task) {
        Objects.requireNonNull(task, "task");
        Deque<List<RoutineStep>> history = recent.get(task.kind());
        if (history == null || history.size() < timesToEstablish) {
            return Optional.empty();
        }
        List<List<RoutineStep>> runs = new ArrayList<>(history);
        List<RoutineStep> first = runs.get(0);
        if (!runs.stream().allMatch(first::equals)) {
            return Optional.empty();
        }
        Routine routine = new Routine(task.kind(), first, runs.size());
        return routine.fits(task) ? Optional.of(routine) : Optional.empty();
    }

    /** Every kind that has settled, with its routine, for a report or a log. */
    public synchronized List<Routine> settled() {
        List<Routine> routines = new ArrayList<>();
        recent.forEach((kind, history) -> {
            if (history.size() >= timesToEstablish) {
                List<List<RoutineStep>> runs = new ArrayList<>(history);
                List<RoutineStep> first = runs.get(0);
                if (runs.stream().allMatch(first::equals)) {
                    routines.add(new Routine(kind, first, runs.size()));
                }
            }
        });
        return List.copyOf(routines);
    }

    /** Forgets what a kind has been doing, so it is worked out afresh. */
    public synchronized void forget(String taskKind) {
        recent.remove(taskKind);
    }

    /** How many runs in a row must agree here. */
    public int timesToEstablish() {
        return timesToEstablish;
    }
}
