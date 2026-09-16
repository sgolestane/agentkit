package dev.agentkit.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.graph.AgentGraph;
import dev.agentkit.core.graph.GraphResult;
import dev.agentkit.core.graph.NodeState;
import dev.agentkit.core.supervisor.DelegatedTask;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.supervisor.SupervisionResult;
import dev.agentkit.core.supervisor.Supervisor;
import dev.agentkit.core.supervisor.Synthesizers;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The seam itself, without a tracing library: a thread-local set on the calling thread
 * must be visible inside work that runs on a pool thread.
 */
class TaskContextTest {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** What agentkit-otel's implementation does, with a String instead of a Span. */
    private static final TaskContext PROPAGATING = new TaskContext() {
        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
            String captured = CURRENT.get();   // read on the submitting thread
            return () -> {
                String previous = CURRENT.get();
                CURRENT.set(captured);
                try {
                    return task.call();
                } finally {
                    CURRENT.set(previous);
                }
            };
        }
    };

    @Test
    void aSupervisorCarriesTheCallersContextIntoEverySubagent() {
        List<String> seen = new CopyOnWriteArrayList<>();
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        observing("alpha", seen), observing("beta", seen)))
                .taskContext(PROPAGATING)
                .synthesizer(Synthesizers.concatenating())
                .build();

        CURRENT.set("caller");
        try {
            // No executor injected, so this exercises the per-call one a caller cannot
            // reach — the configuration wrapping the executor cannot fix.
            supervisor.fanOut(Goal.of("split"), List.of(
                    new DelegatedTask("alpha", Goal.of("one")),
                    new DelegatedTask("beta", Goal.of("two"))));
        } finally {
            CURRENT.remove();
        }

        assertThat(seen).containsExactlyInAnyOrder("caller", "caller");
    }

    @Test
    void withoutOneTheSubagentsSeeNothing() {
        List<String> seen = new CopyOnWriteArrayList<>();
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(observing("alpha", seen)))
                .synthesizer(Synthesizers.concatenating())
                .build();

        CURRENT.set("caller");
        try {
            supervisor.fanOut(Goal.of("split"), List.of(new DelegatedTask("alpha", Goal.of("one"))));
        } finally {
            CURRENT.remove();
        }

        assertThat(seen).containsExactly("(none)");
    }

    @Test
    void aGraphCarriesItIntoEveryNode() {
        List<String> seen = new CopyOnWriteArrayList<>();
        AgentGraph graph = AgentGraph.builder()
                .node("left", input -> record(seen))
                .node("right", input -> record(seen))
                .taskContext(PROPAGATING)
                .build();

        CURRENT.set("caller");
        try {
            assertThat(graph.run(Goal.of("go")).isSuccess()).isTrue();
        } finally {
            CURRENT.remove();
        }

        assertThat(seen).containsExactlyInAnyOrder("caller", "caller");
    }

    @Test
    void theCapturedValueIsRestoredNotLeaked() {
        // The worker's own context must come back afterwards, or a pooled thread carries
        // one caller's context into the next caller's work.
        Callable<String> wrapped = PROPAGATING.wrap(CURRENT::get);
        CURRENT.set("worker");
        try {
            assertThat(wrapped.call()).isNull();       // captured before anything was set
            assertThat(CURRENT.get()).isEqualTo("worker");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            CURRENT.remove();
        }
    }

    @Test
    void aPooledThreadDoesNotKeepThePreviousTasksContext() {
        // The inline restore test runs on the main thread, which is not where the leak
        // would happen: a pool thread is reused, so a context left behind arrives in
        // whatever runs next on it.
        List<String> seen = new CopyOnWriteArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            Supervisor supervisor = Supervisor.builder(SubagentRoster.of(observing("alpha", seen)))
                    .taskContext(PROPAGATING)
                    .executor(pool)
                    .synthesizer(Synthesizers.concatenating())
                    .build();

            CURRENT.set("caller");
            try {
                supervisor.fanOut(Goal.of("split"), List.of(new DelegatedTask("alpha", Goal.of("one"))));
            } finally {
                CURRENT.remove();
            }
            assertThat(seen).containsExactly("caller");

            // Unrelated work on that same thread must not inherit it.
            assertThat(pool.submit(CURRENT::get).get()).isNull();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aSecondWaveOfGraphNodesIsCapturedToo() {
        // "b" is submitted after "a" completes, from a later pass of the driver loop —
        // a different moment, and one a fix that only covered the first wave would miss.
        List<String> seen = new CopyOnWriteArrayList<>();
        AgentGraph graph = AgentGraph.builder()
                .node("a", input -> record(seen))
                .node("b", input -> record(seen))
                .edge("a", "b")
                .taskContext(PROPAGATING)
                .build();

        CURRENT.set("caller");
        try {
            assertThat(graph.run(Goal.of("go")).isSuccess()).isTrue();
        } finally {
            CURRENT.remove();
        }

        assertThat(seen).containsExactly("caller", "caller");
    }

    @Test
    void nestingCarriesThroughOnlyWhereEachLevelSetsIt() {
        // The setting is per-primitive. A nested graph that sets one keeps the caller's
        // context; one that does not detaches, while the level above still looks fine —
        // which is the failure users will hit and the reason the javadoc says so.
        List<String> withSetting = new CopyOnWriteArrayList<>();
        List<String> without = new CopyOnWriteArrayList<>();

        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        Subagent.handling("wired", "nested graph that sets one", goal ->
                                AgentGraph.builder()
                                        .node("leaf", input -> record(withSetting))
                                        .taskContext(PROPAGATING)
                                        .build().run(goal).outcome("leaf").orElseThrow()
                                        .result().orElseThrow()),
                        Subagent.handling("bare", "nested graph that does not", goal ->
                                AgentGraph.builder()
                                        .node("leaf", input -> record(without))
                                        .build().run(goal).outcome("leaf").orElseThrow()
                                        .result().orElseThrow())))
                .taskContext(PROPAGATING)
                .synthesizer(Synthesizers.concatenating())
                .build();

        CURRENT.set("caller");
        try {
            supervisor.fanOut(Goal.of("split"), List.of(
                    new DelegatedTask("wired", Goal.of("one")),
                    new DelegatedTask("bare", Goal.of("two"))));
        } finally {
            CURRENT.remove();
        }

        assertThat(withSetting).containsExactly("caller");
        assertThat(without).containsExactly("(none)");
    }

    @Test
    void aTaskContextThatThrowsFailsThatUnitRatherThanTheWholeRun() {
        // The interface invites third-party implementations, so this is reachable. Both
        // primitives promise one unit's failure does not abort the others.
        TaskContext broken = new TaskContext() {
            @Override
            public <T> Callable<T> wrap(Callable<T> task) {
                throw new IllegalStateException("context backend down");
            }
        };

        GraphResult graph = AgentGraph.builder()
                .node("a", input -> AgentResult.completed("A", 1))
                .taskContext(broken)
                .build()
                .run(Goal.of("go"));
        assertThat(graph.outcome("a").orElseThrow().state()).isEqualTo(NodeState.FAILED);
        assertThat(graph.outcome("a").orElseThrow().result().orElseThrow().error())
                .get().isInstanceOf(IllegalStateException.class);

        SupervisionResult fanOut = Supervisor.builder(
                        SubagentRoster.of(observing("alpha", new CopyOnWriteArrayList<>())))
                .taskContext(broken)
                .synthesizer(Synthesizers.concatenating())
                .build()
                .fanOut(Goal.of("split"), List.of(new DelegatedTask("alpha", Goal.of("one"))));
        assertThat(fanOut.outcomes()).singleElement().satisfies(outcome ->
                assertThat(outcome.result().error()).get().isInstanceOf(IllegalStateException.class));
    }

    @Test
    void theDefaultPropagatesNothingAndReturnsTheTaskUntouched() {
        Callable<String> task = () -> "x";
        assertThat(TaskContext.NONE.wrap(task)).isSameAs(task);
        assertThat(TaskContext.NONE).hasToString("TaskContext.NONE");
    }

    private static Subagent observing(String name, List<String> seen) {
        return Subagent.handling(name, "observes", goal -> record(seen));
    }

    private static AgentResult record(List<String> seen) {
        String value = CURRENT.get();
        seen.add(value == null ? "(none)" : value);
        return AgentResult.completed("ok", 1);
    }
}
