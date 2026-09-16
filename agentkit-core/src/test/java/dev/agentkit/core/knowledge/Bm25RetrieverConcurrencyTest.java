package dev.agentkit.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.retrieval.Bm25Index;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * #286: {@link Bm25Retriever} rebuilds its index on the first search, and the fields that
 * carry the result were neither volatile nor guarded. Two subagents racing the first
 * query of a run — the shape {@code Supervisor.fanOut} and {@code AgentGraph} produce
 * over one shared knowledge base — could observe {@code dirty == false} without observing
 * the index that cleared it, and dereference null.
 *
 * <p><strong>These are structural pins, not a race reproduction, and the distinction is
 * load-bearing.</strong> The defect is a Java-memory-model violation whose visible
 * symptom depends on the hardware's store ordering. Attempting it on this project's
 * x86-64 CI produced <em>zero</em> failures in 24,000 concurrent first searches across
 * 3,000 freshly-built retrievers: x86 is total-store-ordered, so the reordering the JMM
 * permits does not occur there. A test asserting "no NPE under concurrency" would
 * therefore have passed against the broken code and proved nothing. The pins below
 * assert the properties that make the code correct on any hardware, and each of them
 * fails against the pre-fix version.
 *
 * <p>The stale-or-short-index arm the issue also describes was not reproduced, and
 * analysis says it is not reachable under the documented wiring: with ingestion finished
 * before the instance is shared, concurrent rebuilds each iterate an unmodified chunk map
 * and each therefore produce a <em>complete</em> index, so the losing writer's index is
 * equivalent, not short. Producing a short index requires {@code add} to run concurrently
 * with {@code search}, which is separately unsupported.
 */
class Bm25RetrieverConcurrencyTest {

    private static final int THREADS = 8;
    private static final int CORPUS = 200;

    @Test
    void theIndexFieldIsVolatileSoAReaderCannotMissIt() throws Exception {
        Field index = Bm25Retriever.class.getDeclaredField("index");

        assertThat(Modifier.isVolatile(index.getModifiers()))
                .as("Bm25Retriever.index must be volatile. Without it a thread that reads "
                        + "dirty == false is under no happens-before edge to the write of "
                        + "index that cleared it, so search can dereference null (#286). "
                        + "This is a structural pin because the reordering it forbids is "
                        + "not observable on x86 — 0 failures in 24,000 concurrent first "
                        + "searches — so only the declaration can be asserted.")
                .isTrue();
    }

    @Test
    void theDirtyFieldIsVolatileSoTheRebuiltFlagIsPublished() throws Exception {
        Field dirty = Bm25Retriever.class.getDeclaredField("dirty");

        assertThat(Modifier.isVolatile(dirty.getModifiers()))
                .as("Bm25Retriever.dirty must be volatile. It is written last in rebuild() "
                        + "precisely so that seeing it false implies seeing the index; a "
                        + "plain field gives that write no ordering a reader can rely on.")
                .isTrue();
    }

    @Test
    void theRebuildIsGuardedSoConcurrentFirstSearchesBuildOneIndex() throws Exception {
        Method rebuild = Bm25Retriever.class.getDeclaredMethod("rebuild");

        assertThat(Modifier.isSynchronized(rebuild.getModifiers()))
                .as("Bm25Retriever.rebuild() must be synchronized. Unguarded, N threads "
                        + "racing the first search of a run each rebuild the whole corpus "
                        + "and each publish a different index; guarded, the first one "
                        + "builds and the rest return it (#286).")
                .isTrue();
        assertThat(rebuild.getReturnType())
                .as("rebuild() must hand the index back rather than only assign the field, "
                        + "so a caller that lost the race searches the index it was given "
                        + "instead of re-reading a field that may have moved on")
                .isEqualTo(Bm25Index.class);
    }

    /**
     * A liveness and correctness check on the new lock, <em>not</em> a race reproduction:
     * it passes against the pre-fix version too. It is here to kill the mutants a guarded
     * rebuild invites — a rebuild that deadlocks, that returns null when it finds the
     * index already built, or that publishes an index built from a partial corpus.
     */
    @Test
    void everyThreadRacingTheFirstSearchSeesTheWholeIndex() throws Exception {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.add(corpus());

        CyclicBarrier startTogether = new CyclicBarrier(THREADS);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    startTogether.await();
                    return retriever.search("alpha", 5).size();
                }));
            }
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get())
                        .as("Thread %d raced the first search and must still see a complete "
                                + "index: every chunk contains 'alpha', so a full result "
                                + "set is 5 hits. Fewer means a thread searched an index "
                                + "built from a partial corpus.", i)
                        .isEqualTo(5);
            }
        }
    }

    @Test
    void aSearchAfterMoreIngestionSeesTheNewChunks() {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.add(corpus());
        assertThat(retriever.search("alpha", 5)).hasSize(5);

        retriever.add(List.of(new Chunk("late", "late-doc", "alpha zeta later arrival", Map.of())));

        assertThat(retriever.search("later", 5))
                .as("The guarded rebuild must still notice that chunks changed; a rebuild "
                        + "that returns the cached index whenever it is non-null would "
                        + "freeze the corpus at the first search")
                .isNotEmpty();
        assertThat(retriever.search("later", 5).get(0).chunk().id()).isEqualTo("late");
    }

    private static List<Chunk> corpus() {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < CORPUS; i++) {
            chunks.add(new Chunk("c" + i, "doc" + i,
                    "alpha beta gamma term" + i + " delta epsilon zeta", Map.of()));
        }
        return chunks;
    }
}
