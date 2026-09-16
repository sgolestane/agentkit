package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class BlackboardTest {

    @Test
    void assignsMonotonicIdsAndPreservesOrder() {
        Blackboard board = new Blackboard();
        Blackboard.Entry a = board.post("alice", "plan", "first");
        Blackboard.Entry b = board.post("bob", "plan", "second");

        assertThat(a.id()).isEqualTo(1);
        assertThat(b.id()).isEqualTo(2);
        assertThat(board.entries()).containsExactly(a, b);
        assertThat(board.size()).isEqualTo(2);
    }

    @Test
    void filtersByTopicCaseInsensitively() {
        Blackboard board = new Blackboard();
        board.post("alice", "Research", "r1");
        board.post("bob", "writing", "w1");
        board.post("carol", "research", "r2");

        assertThat(board.byTopic("research")).extracting(Blackboard.Entry::content)
                .containsExactly("r1", "r2");
        assertThat(board.byTopic("WRITING")).hasSize(1);
        assertThat(board.byTopic("absent")).isEmpty();
    }

    @Test
    void sinceReturnsOnlyEntriesAfterTheGivenId() {
        Blackboard board = new Blackboard();
        board.post("a", "t", "1");
        Blackboard.Entry second = board.post("a", "t", "2");
        board.post("a", "t", "3");

        assertThat(board.since(second.id())).extracting(Blackboard.Entry::content)
                .containsExactly("3");
        assertThat(board.since(0)).hasSize(3);
    }

    @Test
    void aPageCarriesTheFrontOfTheTailAndTheSizeOfTheWholeTail() {
        // #93's shape: a reader gets what it will render and a count of what it will not,
        // so the header can say how much is left without the tail having been copied to be
        // measured. Both halves matter — a page without the count is a silent truncation.
        Blackboard board = new Blackboard();
        for (int i = 1; i <= 10; i++) {
            board.post("alice", "t", "note " + i);
        }

        Blackboard.Page page = board.since(3, 4);
        assertThat(page.entries()).extracting(Blackboard.Entry::content)
                .containsExactly("note 4", "note 5", "note 6", "note 7");
        assertThat(page.total()).isEqualTo(7);
        assertThat(page.truncated()).isTrue();

        // A limit wider than the tail is not an error and does not pad: total and the page
        // agree, and nothing is truncated.
        Blackboard.Page rest = board.since(7, 50);
        assertThat(rest.entries()).hasSize(3);
        assertThat(rest.total()).isEqualTo(3);
        assertThat(rest.truncated()).isFalse();

        // Past the end of the board is an empty page, not a negative total.
        assertThat(board.since(10, 4).entries()).isEmpty();
        assertThat(board.since(10, 4).total()).isZero();
        assertThat(board.since(999, 4).total()).isZero();
    }

    @Test
    void aPagedTopicIsTheSameSelectionTheUnpagedOneMakes() {
        Blackboard board = new Blackboard();
        for (int i = 1; i <= 10; i++) {
            board.post("alice", i % 2 == 0 ? "Even" : "odd", "note " + i);
        }

        // Case-insensitive, in post order, and cut at the cursor rather than at the index.
        Blackboard.Page page = board.byTopic("EVEN", 4, 2);
        assertThat(page.entries()).extracting(Blackboard.Entry::content)
                .containsExactly("note 6", "note 8");
        assertThat(page.total()).isEqualTo(3);

        // The bucket is the same answer the whole-topic form gives, which is the property
        // that lets read_board switch to the paged one.
        assertThat(board.byTopic("even")).extracting(Blackboard.Entry::content)
                .containsExactly("note 2", "note 4", "note 6", "note 8", "note 10");
        assertThat(board.byTopic("absent", 0, 5).entries()).isEmpty();
        assertThat(board.byTopic("absent", 0, 5).total()).isZero();
    }

    @Test
    void theTopicIndexAgreesWithEqualsIgnoreCaseAndNotWithToLowerCase() {
        // byTopic used to scan and compare with equalsIgnoreCase; it now looks a bucket up
        // by a folded key, and the two must be the same relation or a topic that used to
        // match silently stops matching. toLowerCase is not that relation in either
        // direction: it is context-sensitive and can change length, and it disagrees on
        // characters nothing here excludes. Character.toUpperCase('ı') is 'I', so
        // "ı".equalsIgnoreCase("i") is true — while "ı".toLowerCase() is "ı", which would
        // have filed the two under different keys.
        Blackboard board = new Blackboard();
        board.post("alice", "\u0131", "dotless");

        assertThat("\u0131".equalsIgnoreCase("i")).isTrue();
        assertThat("\u0131".toLowerCase(java.util.Locale.ROOT)).isNotEqualTo("i");
        assertThat(board.byTopic("i")).extracting(Blackboard.Entry::content)
                .containsExactly("dotless");
        assertThat(board.byTopic("I", 0, 5).entries()).hasSize(1);
    }

    @Test
    void aPageOfNothingIsRefusedRatherThanReturned() {
        // A limit of zero is a caller that computed its page size and got it wrong, and a
        // page of nothing over a non-empty board is indistinguishable from the end of the
        // board — the one failure a cursor cannot recover from. Clamping it to one would
        // hide the arithmetic error instead.
        Blackboard board = new Blackboard();
        board.post("alice", "t", "note");

        assertThatThrownBy(() -> board.since(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> board.since(0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> board.byTopic("t", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Blackboard.Page(board.entries(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pagingCostsTheSameWhereverTheCursorSits() {
        // The point of the binary search: the work of a page does not depend on how far
        // into the board the cursor has walked. Asserted as a shape rather than a
        // stopwatch — every page of a fixed size carries the same number of entries, and
        // the totals fall by exactly the number consumed, which a scan-and-copy fetch
        // could satisfy only by not scanning.
        Blackboard board = new Blackboard();
        for (int i = 1; i <= 200; i++) {
            board.post("alice", "t", "note " + i);
        }

        long cursor = 0;
        int pages = 0;
        for (Blackboard.Page page = board.since(cursor, 8); !page.entries().isEmpty();
                page = board.since(cursor, 8)) {
            pages++;
            assertThat(page.entries()).hasSize(8);
            assertThat(page.total()).isEqualTo(200 - (pages - 1) * 8);
            assertThat(page.entries().get(0).id()).isEqualTo(cursor + 1);
            cursor = page.entries().get(7).id();
        }
        assertThat(pages).isEqualTo(25);
        assertThat(cursor).isEqualTo(200);
    }

    @Test
    void concurrentPostsAllLandWithUniqueIds() throws InterruptedException {
        Blackboard board = new Blackboard();
        int threads = 8;
        int perThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String author = "w" + t;
            Thread worker = new Thread(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    ids.add(board.post(author, "topic", "n").id());
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(board.size()).isEqualTo(threads * perThread);
        assertThat(ids).hasSize(threads * perThread); // no duplicate ids handed out
        // The topic index is appended under the same lock and in the same order, so it is
        // the same set and the same ordering — a bucket that dropped a post under
        // contention would be a note that can never be read back under its own topic.
        assertThat(board.byTopic("topic")).containsExactlyElementsOf(board.entries());

        // The list order never diverges from id order, even under concurrent posts:
        // id assignment and insertion are atomic, so entries() is a contiguous,
        // id-sorted prefix. (Guards the reserve-id-then-append race.)
        List<Long> ordered = board.entries().stream().map(Blackboard.Entry::id).toList();
        assertThat(ordered).isSorted();
        assertThat(ordered).containsExactlyElementsOf(
                LongStream.rangeClosed(1, threads * perThread).boxed().toList());

        // A reader paging forward by max-seen id recovers every entry, no gaps. Guarded by
        // a page count rather than left to run until the batch is empty: 'since' is
        // exclusive, and a 'since' that were inclusive by one would hand the cursor's own
        // entry back forever — which as a bare while-loop is not a failing test but an
        // OutOfMemoryError that takes the whole surefire fork with it, two minutes later
        // and naming nothing.
        List<Long> paged = new ArrayList<>();
        long cursor = 0;
        for (int page = 0; page <= threads * perThread; page++) {
            List<Blackboard.Entry> batch = board.since(cursor);
            if (batch.isEmpty()) {
                break;
            }
            batch.forEach(e -> paged.add(e.id()));
            long advanced = batch.get(batch.size() - 1).id();
            assertThat(advanced).as("a page that does not advance is an infinite loop")
                    .isGreaterThan(cursor);
            cursor = advanced;
        }
        assertThat(paged).containsExactlyElementsOf(
                LongStream.rangeClosed(1, threads * perThread).boxed().toList());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
