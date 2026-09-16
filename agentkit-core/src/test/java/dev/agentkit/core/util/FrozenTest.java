package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What "a defensive copy" has to mean for a value that crosses a gate (#120). */
class FrozenTest {

    /** The documented depth cap, kept here so the boundary cases below are not magic numbers. */
    private static final int MAX_DEPTH = 100;

    /** {@code {"n":{"n":...:"leaf"}}}, whose deepest value sits at {@code levels}. */
    private static Map<String, Object> nested(int levels) {
        Map<String, Object> map = new LinkedHashMap<>(Map.of("n", "leaf"));
        for (int i = 1; i < levels; i++) {
            map = new LinkedHashMap<>(Map.of("n", map));
        }
        return map;
    }

    /** {@code {"n":[[[..."leaf"]]]}}, whose deepest value also sits at {@code levels}. */
    private static Map<String, Object> nestedLists(int levels) {
        List<Object> list = new ArrayList<>(List.of("leaf"));
        for (int i = 2; i < levels; i++) {
            list = new ArrayList<>(List.of(list));
        }
        return new LinkedHashMap<>(Map.of("n", list));
    }

    /**
     * {@code body} run on a thread of exactly {@code stackBytes}, returning what escaped it.
     *
     * <p>The stack size is the point of every caller below, so it is requested explicitly
     * rather than inherited: the bug was that the depth counter and the thread's stack were
     * racing, and a test that runs on whatever stack surefire happens to fork with cannot
     * tell which one won.
     */
    /**
     * The stack sizes this looks for an overflow at, smallest first.
     *
     * <p>A search rather than a constant. The original asked for 64 kB and relied on the JVM
     * clamping that to a floor carrying 49 frames of the walk — half of {@code MAX_DEPTH}, so
     * a legal structure overflowed anyway. That was measured on one JVM on one operating
     * system. The floor is larger on macOS: all 98 levels fit, nothing overflows, and the
     * assertion fails on every Mac. It also failed on a Linux CI runner about one build in
     * three, which is the same fact arriving as a flake — #382's PR caught one.
     *
     * <p>Depth cannot be measured and reused either: recursing until it fails and then
     * recursing to just short of that gives a number from an interpreted run and spends it on
     * a compiled one, whose frames are smaller. Tried, and it does not overflow.
     *
     * <p>So: ask for each of these in turn and use the first that produces the condition.
     */
    private static final int[] CANDIDATE_STACKS =
            {16 * 1024, 32 * 1024, 64 * 1024, 96 * 1024, 128 * 1024};

    private static Throwable escapingFrom(int stackBytes, Runnable body) {
        Throwable[] escaped = new Throwable[1];
        Thread thread = new Thread(null, () -> {
            try {
                body.run();
            } catch (Throwable t) {
                escaped[0] = t;
            }
        }, "frozen-depth-probe", stackBytes);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return escaped[0];
    }

    @Test
    @DisplayName("a nested value cannot be changed after it has been read")
    void nestedValuesAreSnapshots() {
        // The outer map was copied and the values were not, so a caller keeping a
        // reference could show one reader a value and the next another. Both types that
        // used this admitted it in their own javadoc and asked callers to be careful,
        // which is what a type says when it has not made the thing true.
        List<String> mine = new ArrayList<>(List.of("/tmp/harmless.txt"));
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("inner", mine));

        Map<String, Object> frozen = Frozen.deeply(Map.of("paths", mine, "deep", nested));

        mine.set(0, "/etc/shadow");
        assertThat(frozen.get("paths")).isEqualTo(List.of("/tmp/harmless.txt"));
        assertThat(((Map<?, ?>) frozen.get("deep")).get("inner"))
                .as("a map nested inside a map was still shared")
                .isEqualTo(List.of("/tmp/harmless.txt"));
    }

    @Test
    @DisplayName("and nothing handed the snapshot can change it either")
    void theSnapshotIsSealedAllTheWayDown() {
        Map<String, Object> frozen = Frozen.deeply(Map.of(
                "paths", new ArrayList<>(List.of("a")),
                "deep", new LinkedHashMap<>(Map.of("inner", new ArrayList<>(List.of("b"))))));

        assertThatThrownBy(() -> ((List<Object>) frozen.get("paths")).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<Object, Object>) frozen.get("deep")).put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) ((Map<?, ?>) frozen.get("deep")).get("inner"))
                .add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a map inside a list is a snapshot too")
    void listElementsAreWalked() {
        // The mutant that survived the first round of tests: copy.add(item) instead of
        // copy.add(value(item)), so a list was copied and its elements were not. Nothing
        // here caught it, because every element in the other cases was already immutable.
        // {"edits": [{"file": ...}]} is the commonest nested argument shape there is.
        Map<String, Object> edit = new LinkedHashMap<>(Map.of("file", "/tmp/harmless.txt"));
        List<Object> edits = new ArrayList<>(List.of(edit));
        List<Object> listInList = new ArrayList<>(List.of(new ArrayList<>(List.of("a"))));

        Map<String, Object> frozen = Frozen.deeply(Map.of("edits", edits, "outer", listInList));

        edit.put("file", "/etc/shadow");
        ((List<Object>) listInList.get(0)).set(0, "z");
        assertThat(frozen.get("edits")).isEqualTo(List.of(Map.of("file", "/tmp/harmless.txt")));
        assertThat(frozen.get("outer")).isEqualTo(List.of(List.of("a")));
    }

    @Test
    @DisplayName("a value that cannot be snapshotted is refused, not shared")
    void aNonJsonValueIsRefused() {
        // The first version stored anything that was not a Map or Collection by reference
        // and said so in its javadoc — "the JSON shapes are a snapshot; anything else is
        // the caller's to keep immutable". Two red-team passes read that and typed the
        // argument String[]: the policy gate read /tmp/harmless.txt and the tool opened
        // /etc/shadow, on all four runners. An attacker who picks the type picks whether
        // the control applies, so the guarantee had been written for the honest caller
        // while the threat model named the dishonest one.
        assertThatThrownBy(() -> Frozen.deeply(Map.of("paths", new String[] {"/tmp/ok"})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("String[]");
        assertThatThrownBy(() -> Frozen.deeply(Map.of("when", new java.util.Date())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Frozen.deeply(Map.of("buf", new StringBuilder("x"))))
                .isInstanceOf(IllegalArgumentException.class);
        // Nested, not only at the top level.
        assertThatThrownBy(() -> Frozen.deeply(Map.of("deep",
                List.of(Map.of("paths", new Object[] {"/tmp/ok"})))))
                .isInstanceOf(IllegalArgumentException.class);
        // And the shapes JSON does produce still pass.
        assertThat(Frozen.deeply(Map.of("n", 1, "b", true, "s", "x"))).hasSize(3);
    }

    @Test
    @DisplayName("a cycle is refused rather than overflowing the stack")
    void aCycleIsRefused() {
        // The first version argued a StackOverflowError was the better failure. It is not:
        // Agent.runTool catches RuntimeException, so the Error escaped Agent.run with no
        // result and no onFinish — killing the run, which is what the comment beside that
        // catch says must never happen. An IllegalArgumentException is a RuntimeException,
        // so every runner already turns it into an error result.
        List<Object> loop = new ArrayList<>();
        loop.add(loop);
        Map<String, Object> selfMap = new LinkedHashMap<>();
        selfMap.put("self", selfMap);

        assertThatThrownBy(() -> Frozen.deeply(Map.of("loop", loop)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Frozen.deeply(selfMap))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the depth counter refuses the cycle before the stack runs out")
    void theDepthGuardWinsTheRaceAgainstTheStack() {
        // What aCycleIsRefused could not tell you (#193). It asserts IllegalArgumentException
        // and the walk has two ways to stop a cycle: the counter, which throws one, and the
        // JVM stack, which throws StackOverflowError — so the test passed whenever EITHER
        // won, and on ubuntu-latest they took turns. Two runs of one commit disagreed.
        //
        // The race is decided by how much stack a level of the walk costs. Measured for #193
        // in a fresh JVM per probe, so the first deeply() call is the interpreted one the
        // JIT has not shrunk yet: ~790 bytes a level, giving 49 levels on the smallest stack
        // the JVM will hand a thread, 650 on 512 kB, and 1,312-1,501 on the 1 MB default.
        // The old limit of 1,000 therefore needed ~790 kB of that 1 MB — 1.3x, against a
        // walk whose frames shrink ninefold once C2 has seen it. It was never going to hold.
        //
        // 512 kB is the fixture: measured to carry 650 levels of the walk, 6x the 101 this
        // needs and comfortably under the ~790 kB the old limit wanted. The assertion is on
        // the counter's own message, because "some IllegalArgumentException" is exactly the
        // assertion that let the bug through in the first place.
        //
        // Both fixtures are maps, and that is not incidental. A self-referencing LIST is one
        // value() frame a level and rides a 512 kB stack past 1,000 easily — measured, the
        // old code refuses it here. A self-referencing MAP costs value() plus copyMap() plus
        // the capturing lambda copyMap hands to forEach, which the interpreter builds
        // through DirectMethodHandle: about three times the stack a level, and the exact
        // frames in the CI trace on #193 (allocateInstance -> copyMap -> value). So of the
        // two cycles aCycleIsRefused already drove, only the map one could ever have lost —
        // and it is the one the reported failure points at, FrozenTest.java:112.
        Map<String, Object> selfMap = new LinkedHashMap<>();
        selfMap.put("self", selfMap);

        assertThat(escapingFrom(512 * 1024, () -> Frozen.deeply(selfMap)))
                .as("a cycle must be stopped by the counter, not by running out of stack")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nest deeper than " + MAX_DEPTH + " levels");
        assertThat(escapingFrom(512 * 1024, () -> Frozen.deeply(nested(MAX_DEPTH + 2))))
                .as("and so must a structure that is merely deep")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nest deeper than " + MAX_DEPTH + " levels");
    }

    @Test
    @DisplayName("and the cap is the documented one, not one level either side of it")
    void theDepthCapIsExactlyWhereItSaysItIs() {
        // Lowering the cap is only a fix if the cap is where the javadoc and the {@value} in
        // deeply's @throws say it is. A test that only drives a cycle cannot see the
        // boundary at all: a cycle is refused by any cap.
        assertThat(Frozen.deeply(nested(MAX_DEPTH))).containsOnlyKeys("n");
        assertThatThrownBy(() -> Frozen.deeply(nested(MAX_DEPTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nest deeper than " + MAX_DEPTH + " levels");

        // And a level of list counts as a level, which the mutant that dropped the depth + 1
        // from the collection branch showed nothing was checking. It is the same defect as
        // #193 in miniature: a nesting the counter does not count is a nesting only the
        // stack can stop, and the stack answers with the wrong throwable at an unpredictable
        // depth. Lists are cheaper per level than maps, so this one would have run a long
        // way before anything noticed.
        assertThat(Frozen.deeply(nestedLists(MAX_DEPTH))).containsOnlyKeys("n");
        assertThatThrownBy(() -> Frozen.deeply(nestedLists(MAX_DEPTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nest deeper than " + MAX_DEPTH + " levels");
    }

    @Test
    @DisplayName("and if the stack loses anyway, an Error is still not what escapes")
    void anOverflowIsConvertedRatherThanThrown() {
        // Defence in depth, because MAX_DEPTH is an estimate over frame layouts this class
        // does not control. Measured: the JVM clamps a thread stack request below ~128 kB to
        // one floor, and that floor carries 49 levels of the walk — half of MAX_DEPTH — so a
        // structure of exactly MAX_DEPTH is legal, cannot trip the counter, and exhausts the
        // stack regardless. The margin is 2x, and it is in the direction of overflowing.
        //
        // What must not happen then is an Error reaching a caller that was promised an
        // exception. ToolInvocation calls deeply from a constructor on the durable path —
        // it was ToolInvocationRecord as well until #170 gave that record the whole
        // invocation; #130 widened ToolActivitiesImpl's catch to Throwable after an
        // escaped Error made Temporal retry one model-proposed call more than once, and #166
        // did the same on the observer path. The bound is worth nothing if the caller has to
        // catch Throwable to use it.
        Throwable converted = null;
        for (int stack : CANDIDATE_STACKS) {
            Throwable escaped = escapingFrom(stack, () -> Frozen.deeply(nested(MAX_DEPTH)));
            if (escaped == null) {
                // That much stack was enough. A bigger one will be too — stop looking down
                // and try the next size up, which is the same answer for a different reason.
                continue;
            }
            // The regression this exists to catch: an Error reaching a caller that was
            // promised an exception. Asserted the moment ANY size produces something, so a
            // raw StackOverflowError cannot be skipped past by the search.
            assertThat(escaped)
                    .as("the walk ran out of stack at %d bytes; an Error must not be what "
                            + "comes out", stack)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exhausted the stack")
                    .hasCauseInstanceOf(StackOverflowError.class);
            converted = escaped;
            break;
        }

        // Nothing overflowed at any size this asks for, which means the JVM would not give a
        // stack small enough to construct the condition. Said out loud rather than passed
        // quietly: a green tick here would claim the conversion was checked when it was not.
        assumeTrue(converted != null,
                "this JVM's smallest thread stack still carries " + MAX_DEPTH
                        + " levels of the walk, so an overflow cannot be constructed here");
    }

    @Test
    @DisplayName("a shared subtree is bounded, not expanded until the heap runs out")
    void aSharedSubtreeIsRefused() {
        // Measured on the first version: twenty-three ArrayList objects arranged as a DAG
        // expanded to four million nodes and exhausted a 512MB heap in seven seconds,
        // because a snapshot walks a shared reference once per reference. Deduplicating
        // instead would be wrong — one mutation would then show through both references,
        // which is the defect this class exists to remove — so the answer is a budget.
        List<Object> level = new ArrayList<>(List.of("leaf"));
        for (int i = 0; i < 30; i++) {
            level = new ArrayList<>(List.of(level, level));
        }
        List<Object> dag = level;

        assertThatThrownBy(() -> Frozen.deeply(Map.of("dag", dag)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expanded");
    }

    @Test
    @DisplayName("a collection that lies about its size does not become an exception")
    void aHostileSizeIsNotUsedAsACapacity() {
        // new ArrayList<>(items.size()) passed a caller-supplied size straight into a
        // capacity argument, so a Collection answering -1 turned a snapshot into
        // IllegalArgumentException: Illegal Capacity. The old shallow copy went through
        // putMapEntries, which guards s > 0. Nothing needs the size, so nothing asks.
        Collection<Object> liar = new java.util.AbstractCollection<>() {
            @Override
            public java.util.Iterator<Object> iterator() {
                return List.of((Object) "a").iterator();
            }

            @Override
            public int size() {
                return -1;
            }
        };

        assertThat(Frozen.deeply(Map.of("xs", liar)).get("xs")).isEqualTo(List.of("a"));
    }

    @Test
    @DisplayName("order survives, and so do the values that are not containers")
    void ordinaryContentIsUnchanged() {
        // The copy is for sharing, not for sanitising: a snapshot that reordered or
        // rewrote arguments would be a different defect wearing the same name.
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("z", 1);
        source.put("a", "two");
        source.put("nil", null);
        source.put("nested", List.of(1, "two", List.of(3)));

        Map<String, Object> frozen = Frozen.deeply(source);

        assertThat(frozen).containsExactlyEntriesOf(source);
        assertThat(frozen.keySet()).containsExactly("z", "a", "nil", "nested");
        assertThat(frozen.get("nested")).isEqualTo(List.of(1, "two", List.of(3)));
        assertThat(Frozen.deeply(Map.of())).isEmpty();
    }
}
