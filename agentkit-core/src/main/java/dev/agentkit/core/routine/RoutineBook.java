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
 * <p>When the last {@link #timesToEstablish} clean runs of a kind did <em>exactly</em> the same thing: the same
 * tools, in the same order, with the same arguments once the task's values are taken out. A streak rather than a
 * tally, because a tally cannot tell "this settled three runs ago" from "this was the usual way last year": one run
 * that does something else breaks the streak, and so does any run that did not go cleanly
 * ({@link RoutineRecorder}), and the kind goes back to being worked out by the model until it settles again. That is
 * the behaviour to want from a component that will later run without a model watching.
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

    /** One kind of work: its most recent clean runs, oldest first, and how many runs of it have been started. */
    private static final class Kind {
        final Deque<List<RoutineStep>> recent = new ArrayDeque<>();
        long started;
    }

    private final int timesToEstablish;
    private final int maxKinds;
    private final int maxSteps;

    /** Access-ordered, so eviction drops the kind seen least recently. */
    private final Map<String, Kind> kinds;

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
        this.kinds = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Kind> eldest) {
                return size() > RoutineBook.this.maxKinds;
            }
        };
    }

    /**
     * Records what one clean run of {@code task} did.
     *
     * <p>A run with no steps, or more than the most this book records, is not a routine and breaks any streak.
     */
    public synchronized void observe(Task task, List<RoutineStep> steps) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(steps, "steps");
        Kind kind = kinds.computeIfAbsent(task.kind(), k -> new Kind());
        if (steps.isEmpty() || steps.size() > maxSteps) {
            kind.recent.clear();
            return;
        }
        kind.recent.addLast(List.copyOf(steps));
        while (kind.recent.size() > timesToEstablish) {
            kind.recent.removeFirst();
        }
    }

    /** The routine for this task, if its kind has settled and the task fills every parameter the steps expect. */
    public synchronized Optional<Routine> established(Task task) {
        Objects.requireNonNull(task, "task");
        Kind kind = kinds.get(task.kind());
        return kind == null ? Optional.empty() : settled(task.kind(), kind).filter(routine -> routine.fits(task));
    }

    /** Every kind that has settled, with its routine, for a report or a log. */
    public synchronized List<Routine> settled() {
        List<Routine> routines = new ArrayList<>();
        kinds.forEach((name, kind) -> settled(name, kind).ifPresent(routines::add));
        return List.copyOf(routines);
    }

    /**
     * Counts one more run of this kind being started and returns the count, from 1. Kept here rather than by the
     * caller so it is bounded with everything else the book keeps.
     */
    public synchronized long started(String taskKind) {
        Objects.requireNonNull(taskKind, "taskKind");
        return ++kinds.computeIfAbsent(taskKind, k -> new Kind()).started;
    }

    /** Forgets what a kind has been doing, so it is worked out afresh; its count of runs started is kept. */
    public synchronized void forget(String taskKind) {
        Kind kind = kinds.get(taskKind);
        if (kind != null) {
            kind.recent.clear();
        }
    }

    /** How many runs in a row must agree here. */
    public int timesToEstablish() {
        return timesToEstablish;
    }

    private Optional<Routine> settled(String name, Kind kind) {
        if (kind.recent.size() < timesToEstablish) {
            return Optional.empty();
        }
        List<RoutineStep> first = kind.recent.getFirst();
        for (List<RoutineStep> run : kind.recent) {
            if (!run.equals(first)) {
                return Optional.empty();
            }
        }
        return Optional.of(new Routine(name, first, kind.recent.size()));
    }
}
