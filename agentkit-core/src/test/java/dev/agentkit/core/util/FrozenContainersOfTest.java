package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The second entry point, and the asymmetry that keeps it from weakening the first (#133).
 *
 * <p>{@code containersOf} exists because {@code deeply} refuses a value that is not a JSON
 * shape, which is right for a tool argument whose type a hostile party picked and wrong for
 * an {@code AgentConfig}'s options, which are hand-built Java and may hold a
 * {@code Duration} or a client object. Two methods with different rules is the shape #169
 * was reverted for, so what is asserted here is not only that each does its own job but
 * that the lenient one cannot be used to get past the strict one.
 */
@SuppressWarnings("unchecked")
class FrozenContainersOfTest {

    /** A value no JSON parser can produce and no copier can snapshot. */
    private static Object mutableNonJson() {
        return new StringBuilder("harmless");
    }

    @Test
    @DisplayName("nested containers are copied, so a later mutation cannot reach the snapshot")
    void containersAreCopied() {
        List<String> sharedList = new ArrayList<>(List.of("harmless"));
        Map<String, Object> sharedMap = new LinkedHashMap<>(Map.of("k", "harmless"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("xs", sharedList);
        source.put("m", sharedMap);

        Map<String, Object> snapshot = Frozen.containersOf(source);
        sharedList.set(0, "changed");
        sharedMap.put("k", "changed");

        assertThat(snapshot.get("xs")).isNotSameAs(sharedList).isEqualTo(List.of("harmless"));
        assertThat(snapshot.get("m")).isNotSameAs(sharedMap).isEqualTo(Map.of("k", "harmless"));
    }

    @Test
    @DisplayName("a value that is not a container is kept, where deeply would refuse it")
    void whatCannotBeCopiedIsKeptRatherThanRefused() {
        // The whole reason this method exists. AgentConfig.options reaches
        // OpenRouterLlmClient's MAPPER.valueToTree, which serialises all three of these
        // happily; deeply would have turned a working deployment into an exception.
        Duration timeout = Duration.ofSeconds(30);
        Object client = mutableNonJson();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("timeout", timeout);
        source.put("client", client);

        Map<String, Object> snapshot = Frozen.containersOf(source);

        assertThat(snapshot.get("timeout")).isSameAs(timeout);
        assertThat(snapshot.get("client")).isSameAs(client);
        assertThatThrownBy(() -> Frozen.deeply(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may only hold JSON shapes");
    }

    @Test
    @DisplayName("a set stays a set here, and still becomes a list under deeply")
    void aSetKeepsItsTypeUnderTheLenientWalk() {
        // The one difference between the two walks that is not about a threat. deeply
        // produces what a JSON parser could have produced, and JSON has no set; containersOf
        // produces the shape the caller gave, and .option("stop", Set.of("\n")) is ordinary
        // Java. Handing back a List would fail the caller's cast and compare unequal to the
        // value it was taken of, because Set.equals refuses a List.
        Set<String> callers = new LinkedHashSet<>(List.of("b", "a"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("stop", callers);

        Object lenient = Frozen.containersOf(source).get("stop");
        callers.add("c");

        assertThat(lenient).isInstanceOf(Set.class).isEqualTo(new LinkedHashSet<>(List.of("b", "a")));
        assertThat((Set<Object>) lenient).containsExactly("b", "a");
        assertThatThrownBy(() -> ((Set<Object>) lenient).add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
        // The same input under the strict walk, which normalises it away: JSON has no set.
        assertThat(Frozen.deeply(Map.of("stop", new LinkedHashSet<>(List.of("b", "a"))))
                .get("stop")).isEqualTo(List.of("b", "a"));
    }

    @Test
    @DisplayName("a collection that is neither list nor set becomes a list in both walks")
    void anyOtherCollectionBecomesAList() {
        // List and Set are the only collection interfaces the JDK specifies equals for.
        // ArrayDeque inherits identity equality, which a defensive copy has already broken
        // by copying at all, so an element-wise comparable copy is the more useful one.
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("q", new ArrayDeque<>(List.of("first", "second")));

        assertThat(Frozen.containersOf(source).get("q")).isEqualTo(List.of("first", "second"));
        assertThat(Frozen.deeply(source).get("q")).isEqualTo(List.of("first", "second"));
    }

    @Test
    @DisplayName("a containersOf snapshot cannot launder a non-JSON value past deeply")
    void theLenientMethodIsNotAWayPastTheStrictOne() {
        // The failure this pair could have shipped: copy a hostile map through the method
        // that shares what it cannot snapshot, hand the result to ToolInvocation, and have
        // it accepted as "already frozen" — restoring exactly the hole #128 closed, where a
        // gate reads [/tmp/harmless.txt] and the tool opens [/etc/shadow].
        String[] typedByTheAttacker = {"/tmp/harmless.txt"};
        Map<String, Object> lenient = Frozen.containersOf(Map.of("paths", typedByTheAttacker));

        assertThat(lenient.get("paths")).isSameAs(typedByTheAttacker);
        assertThatThrownBy(() -> Frozen.deeply(lenient))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.String[]");
    }

    @Test
    @DisplayName("deeply hands back its own snapshot rather than walking it again")
    void theStrictMethodRecognisesItsOwnWork() {
        // #134, at the level the fix is actually made. Both Agent and AgentWorkflowImpl
        // build a ToolInvocation from a ToolUseBlock whose constructor already froze the
        // same map; identity is what tells the second walk it has nothing to do.
        Map<String, Object> once = Frozen.deeply(
                Map.of("path", "/tmp/x", "opts", Map.of("deep", List.of(1, 2, 3))));

        assertThat(Frozen.deeply(once)).isSameAs(once);
        assertThat(Frozen.containersOf(once)).isSameAs(once);
    }

    @Test
    @DisplayName("containersOf recognises its own snapshot too, and deeply does not")
    void theLenientMethodRecognisesItsOwnWorkAndTheStrictOneDoesNot() {
        Map<String, Object> once = Frozen.containersOf(Map.of("k", List.of("v")));

        assertThat(Frozen.containersOf(once)).isSameAs(once);
        // Re-walked, not handed back: deeply has still to apply a refusal this snapshot
        // never applied. It succeeds here because these values happen to be JSON shapes —
        // what matters is that it did the work rather than trusting the marker.
        assertThat(Frozen.deeply(once)).isNotSameAs(once).isEqualTo(once);
    }

    @Test
    @DisplayName("a cycle is refused by the depth cap, not by exhausting the stack")
    void aCycleIsRefused() {
        // Kept from deeply deliberately. Goal and AgentConfig are rebuilt by Jackson inside
        // a workflow replay, where a StackOverflowError does not arrive where an
        // IllegalArgumentException would — and a cyclic map could not have been serialised
        // to get there in the first place, so refusing it at construction reports a defect
        // the deployment already had, earlier.
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("self", loop);

        assertThatThrownBy(() -> Frozen.containersOf(loop))
                .isInstanceOf(IllegalArgumentException.class)
                // The whole sentence, because this method's message names a different
                // subject from deeply's and a caller reading it is not holding tool
                // arguments.
                .hasMessage("the values in this map nest deeper than 100 levels;"
                        + " a cyclic structure is the usual cause");
    }

    @Test
    @DisplayName("the node budget is deeply's alone, because a false refusal stalls a replay")
    void theNodeBudgetIsNotTaken() {
        // 100,001 values: one past the budget deeply enforces. Wide rather than deep or
        // shared, so it is a single fast pass and nothing here is the payload shape that
        // makes a build crawl.
        //
        // deeply refuses it and should: a model-proposed argument map that large is a cost
        // nobody meant to pay. containersOf must not, because its callers are parsed on the
        // durable path — Goal and AgentConfig come back through their canonical
        // constructors on every workflow replay, and a throw there fails the workflow task,
        // which Temporal retries forever. That is #132 and #172: the run stalls rather than
        // failing, which is worse than either.
        List<Object> wide = new ArrayList<>(100_000);
        for (int i = 0; i < 100_000; i++) {
            wide.add(i);
        }
        Map<String, Object> source = Map.of("xs", wide);

        assertThatThrownBy(() -> Frozen.deeply(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 100000 values once expanded");
        assertThat(Frozen.containersOf(source)).containsKey("xs");
    }

    @Test
    @DisplayName("the snapshot refuses every way of writing to it")
    void theSnapshotIsUnmodifiable() {
        // It is a new type since #133, so the guarantee Collections.unmodifiableMap gave
        // for free has to be shown to survive delegation — including the Map default
        // methods, which route through put and remove rather than being overridden.
        Map<String, Object> snapshot = Frozen.containersOf(Map.of("k", "v"));

        assertThatThrownBy(() -> snapshot.put("k2", "v2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.remove("k"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(snapshot::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.putAll(Map.of("k3", "v3")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.merge("k", "v2", (a, b) -> b))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.computeIfAbsent("k2", k -> "v2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.replaceAll((k, v) -> "v2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.entrySet().iterator().next().setValue("v2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the snapshot reads, orders and compares like the map it was taken of")
    void theSnapshotBehavesLikeAMap() {
        // Records compare by their components, so a snapshot that was not equal to an
        // equivalent LinkedHashMap would make a Goal or an AgentConfig unequal to itself
        // across a serialization round trip.
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("z", 1);
        source.put("a", "two");
        source.put("nil", null);

        Map<String, Object> snapshot = Frozen.containersOf(source);

        assertThat(snapshot).isEqualTo(source).hasSameHashCodeAs(source);
        assertThat(source).isEqualTo(snapshot);
        assertThat(snapshot.keySet()).containsExactly("z", "a", "nil");
        assertThat(snapshot.get("a")).isEqualTo("two");
        assertThat(snapshot.get("nil")).isNull();
        assertThat(snapshot.containsKey("nil")).isTrue();
        assertThat(snapshot.getOrDefault("absent", "fallback")).isEqualTo("fallback");
        assertThat(snapshot.containsValue("two")).isTrue();
        assertThat(snapshot).hasSize(3);
        assertThat(snapshot.isEmpty()).isFalse();
        assertThat(snapshot.values()).containsExactly(1, "two", null);
        assertThat(snapshot.toString()).isEqualTo(source.toString());
        List<String> visited = new ArrayList<>();
        snapshot.forEach((k, v) -> visited.add(k));
        assertThat(visited).containsExactly("z", "a", "nil");
        assertThat(Frozen.containersOf(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("a null map is refused rather than becoming an empty snapshot")
    void aNullMapIsRefused() {
        assertThatThrownBy(() -> Frozen.containersOf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("map");
    }
}
