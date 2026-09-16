package dev.agentkit.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A snapshot of a map, so a value cannot change after it has been read.
 *
 * <p>Written for a tool's arguments, which is what the sections below argue about; #133
 * gave it a second entry point for the maps a deployment hands the framework about itself,
 * and "Two entry points" is the rule for which is which.
 *
 * <p>{@code ToolUseBlock} and {@code ToolInvocation} both said they stored "a defensive,
 * order-preserving, unmodifiable copy", and both meant the outer map only. The values were
 * shared by reference, and both types also said so a line later — "the copy is
 * <em>shallow</em>, so callers should treat argument values as read-only". That is a
 * warning, and a warning is what a type resorts to when it has not made the thing true.
 *
 * <h2>What was wrong with a warning here</h2>
 *
 * <p>{@code ToolGates.allOf} promises that each later gate judges the invocation the
 * earlier one edited. With shared values, an earlier member can hand the real policy gate a
 * list, let it read {@code [/tmp/harmless.txt]}, and mutate it to {@code [/etc/shadow]}
 * before the tool opens it. The name matched, the id matched, and
 * {@code GateResult.effectiveFor} — which pins <em>which call this is</em> — waved it
 * through, because it cannot pin what the arguments will say when the tool reads them.
 *
 * <p>It needs one buggy or untrustworthy member in the chain, which is precisely the
 * scenario {@code allOf} exists for: composing a policy you wrote with one you did not.
 *
 * <h2>Refused, not copied where it cannot be copied</h2>
 *
 * <p>The first version of this class copied {@link Map} and {@link Collection} through and
 * stored anything else <em>by reference</em>, with a javadoc paragraph explaining that the
 * JSON shapes were a snapshot and the rest was the caller's to keep immutable. The
 * red-team review took the paragraph at its word and typed the argument {@code String[]}:
 *
 * <pre>
 * in-process    policy read [/tmp/harmless.txt]   tool received [/etc/shadow]
 * ToolBridges   policy read [/tmp/harmless.txt]   tool received [/etc/shadow]
 * Approver      reviewer saw [/tmp/harmless.txt]  tool received [/etc/shadow]
 * durable       policy read [/tmp/harmless.txt]   tool received [/etc/shadow]
 * </pre>
 *
 * <p>Same for {@code Object[]}, {@code Date}, {@code StringBuilder}, {@code ByteBuffer},
 * {@code AtomicReference} and {@code Map.Entry}. The guarantee had been written for an
 * honest caller while the threat model named a dishonest one, and an attacker who picks the
 * type picks whether the control applies. A boundary whose scope the attacker selects is
 * not a boundary.
 *
 * <p>So a value that is not a JSON shape is now <strong>refused</strong>. That is not a
 * new contract: {@code ToolInvocation} and {@code ToolUseBlock} have always documented
 * their arguments as "a JSON-like map", and a JSON parser cannot produce any of the types
 * above — every one of them arrives from code, which means from a gate replacement, an
 * approver's edit, or a sandbox bridging a script's own array type. Those are the callers
 * who can act on being told, and being told is better than being quietly exempted.
 *
 * <h2>Two entry points, because two different things are being defended against</h2>
 *
 * <p>{@link #deeply} is the one written above: a value a <em>hostile party chose the type
 * of</em>, refused if it is not a JSON shape. {@link #containersOf} is for the other half
 * of the repository — {@code Goal.parameters}, {@code AgentConfig.options},
 * {@code WorldState.facts}, {@code Document.metadata} — where the map is the deployment's
 * own hand-built Java and the only thing that can go wrong is the deployment mutating it
 * later. It copies every {@link Map} and {@link Collection} and stores anything else
 * <em>by reference</em>.
 *
 * <p>That is the paragraph the red-team review demolished four sections up, so it needs
 * saying why it is not the same mistake. There, the guarantee had been written for an
 * honest caller while the threat model named a dishonest one, and the dishonest one picked
 * the type. Here there is no second party at all: nothing in an {@code AgentConfig}'s
 * options came off a wire, no gate reads it and then a tool acts on it, and the value that
 * would be shared is one the same code that built the map is holding anyway.
 * {@code deeply} would <em>refuse</em> it — a config map may legitimately hold a
 * {@link java.time.Duration}, a retry policy, a client object, and
 * {@code OpenRouterLlmClient} hands options straight to {@code MAPPER.valueToTree}, which
 * takes all three. Refusing them to close a hole that is not there would break working
 * deployments for nothing.
 *
 * <p><strong>The rule, and it is one line.</strong> If the map's values came from outside
 * the deployment — a model, an MCP server, an approver's edit, a script in a sandbox — it
 * goes through {@code deeply}. If the deployment wrote them, {@code containersOf}. The
 * asymmetry is enforced rather than described: {@code containersOf} accepts a
 * {@code deeply} snapshot as already done, and {@code deeply} does <em>not</em> accept a
 * {@code containersOf} one, so routing a hostile map through the lenient method first
 * cannot smuggle a {@code String[]} past the strict one.
 *
 * <h2>Already a snapshot, so not snapshotted again</h2>
 *
 * <p>Both entry points return a private type, and both hand back an argument that is
 * already one. Before that, {@code Agent} and {@code AgentWorkflowImpl} each built a
 * {@code ToolInvocation} out of a {@code ToolUseBlock} whose constructor had already frozen
 * the same map, so every tool call walked its whole argument tree twice — and on the
 * durable path {@code ToolUseBlock} is rebuilt by Jackson on every workflow replay of every
 * step, so the second walk was paid per replay. Measured as bytes allocated per call (#134,
 * by the method #170 used; wall clock on a shared machine is noise and is not reported):
 *
 * <pre>
 * expanded nodes   ToolUseBlock's freeze   ToolInvocation's freeze   redundant
 *             16                 1,080 B                   1,128 B         51%
 *          1,554               108,680 B                 114,008 B         51%
 *         69,904             4,200,456 B               4,299,144 B         51%
 * </pre>
 *
 * <p>Half, necessarily, because the second walk visits the node set the first one produced.
 * The second column is 24 B at every one of those sizes now — the {@code ToolInvocation}
 * record itself, with the freeze allocating nothing — and the identity of the map is the
 * reason. Which is also why the test is that identity and not a call counter: this class is
 * static and used to return a plain {@code LinkedHashMap}, so an instrumented input map is
 * visited exactly once either way and nothing distinguished a map it had built from one a
 * caller had. #134 lists a package-private factory that lets the two call sites skip
 * the copy as the cheaper option; it is rejected here for the reason the issue gives
 * against it — it puts a trust assumption in two places a fourth runner will not know
 * about, and this class has been given a fourth runner twice.
 *
 * <h2>Bounded, because the walk is over somebody else's structure</h2>
 *
 * <p>Recursion depth and total node count are capped, and exceeding either is an
 * {@link IllegalArgumentException} — a {@code RuntimeException}, which every runner already
 * turns into an error result.
 *
 * <p>Both bounds replace a worse failure the review measured. A cyclic argument map — which
 * the earlier javadoc argued was <em>fine</em> because it produced a
 * {@code StackOverflowError} rather than a half-copy — killed a whole in-process run:
 * {@code Agent.runTool} catches {@code RuntimeException}, so the {@code Error} escaped
 * {@code Agent.run} with no result and no {@code onFinish}, which is exactly what the
 * comment beside that catch says must not happen. And because a shared subtree is walked
 * once per reference rather than once, twenty-three {@code ArrayList} objects arranged as a
 * DAG expanded to four million nodes and exhausted a 512&nbsp;MB heap in seven seconds. The
 * old shallow copy never recursed, so both were risks this class introduced.
 *
 * <p>The depth cap only counts, though, while the recursion it is counting still has stack
 * to run on. It was set to 1,000 without measuring which of the two would arrive first, and
 * on a 1&nbsp;MB stack the answer turned out to be "whichever the JIT felt like" — see
 * {@code MAX_DEPTH} for the numbers, and {@link #deeply} for what now happens if the
 * estimate is ever wrong again. A bound whose failure mode is an {@code Error} is not the
 * bound this class advertised (#193).
 *
 * <p>The node budget is what makes the DAG case terminate; the depth cap is what makes the
 * cyclic case terminate. Neither is a deduplicating copy: two references to one list still
 * become two lists, because a snapshot that preserved sharing would let a mutation through
 * one reference show up under the other, which is the whole defect.
 */
public final class Frozen {

    /**
     * How deep a nested argument may be.
     *
     * <p>It was 1,000, chosen because Jackson's own default rejects JSON beyond 1,000
     * levels, and documented as "far below the measured stack limit of roughly 2,300". That
     * 2,300 was measured on a <em>warm</em> JVM, and the walk does not run warm the first
     * time. Measured again for #193, on a 1&nbsp;MB stack — the JVM default and what
     * {@code ubuntu-latest} gives a surefire fork — with each probe in a fresh JVM so that
     * the first {@code deeply} call is the interpreted one:
     *
     * <pre>
     * thread stack   levels the walk survives (cold/interpreted)
     *      128 kB     49          the smallest stack the JVM will hand a thread
     *      256 kB    241
     *      512 kB    650
     *        1 MB    1,312 - 1,501   over eight independent bisections
     * </pre>
     *
     * <p>So a level costs roughly 790 bytes of stack interpreted, and a limit of 1,000 needed
     * about 790&nbsp;kB of a 1&nbsp;MB stack: 1.3x headroom, against a walk whose frame cost
     * drops nearly ninefold once C2 has seen it (the same bisection on a warmed JVM reached
     * 11,794). Whether the counter reached 1,000 first therefore depended on JIT state, on
     * how deep the caller already was, and on the runner — which is why two CI runs of one
     * commit disagreed, one refusing the cycle and one throwing {@code StackOverflowError}
     * out of a constructor on the durable path.
     *
     * <p>100 levels cost about 79&nbsp;kB: 13x headroom on the default stack, and still 2.4x
     * on the 256&nbsp;kB one. It is also far more nesting than a tool call has any use for —
     * a model-produced argument map is single digits deep — so the room given up is room
     * nothing was standing in. {@link #deeply} converts an overflow anyway, for the runtime
     * where even this estimate is wrong.
     *
     * <p><strong>Public since #246, because a caller has to be able to say the number this
     * refused on.</strong> {@code ToolUseBlock} turns this refusal into one that names the
     * tool call it refused, and a refusal that says "deeper than the limit" without saying
     * what the limit is tells a reader — or a model — nothing they can act on. It is a
     * constant of the framework's contract with a tool argument, not a tuning knob: nothing
     * reads it to decide policy, and it is not settable.
     */
    public static final int MAX_DEPTH = 100;

    /**
     * How many values one argument map may contain, counted after expansion.
     *
     * <p>Expanded rather than input size, because the input size is not the cost: a DAG of
     * twenty-three lists expands to four million nodes. A model-produced argument map is
     * bounded long before this by the context window.
     *
     * <p><strong>It means nothing on a parsed payload, and there its failure mode is the
     * worst one this codebase has (#132, #172).</strong> A parser cannot produce a cycle or
     * a shared subtree — measured, Jackson does not even share identical subtrees, so two
     * occurrences of {@code {"x":[1,2,3]}} become two lists — so on a deserialization path
     * this bound can only ever be a false positive. And a throw while deserializing a
     * Temporal signal or an activity result fails the workflow task, which Temporal retries
     * forever: the run stalls rather than rejecting the input.
     *
     * <p>Measured: an {@code ApprovalVerdict} signal carrying 100,100 arguments is 1,290,407
     * bytes of JSON, inside Temporal's default blob limit, and this budget refused it.
     * {@code ApprovalVerdict}'s constructor therefore does not call {@link #deeply} at all.
     *
     * <p>The lesson for the next caller, learned the expensive way in #169: do not solve
     * that by adding a second method here named for its caller's provenance. That was tried
     * and reverted — it left "which of two copies is correct" stated only in prose, where a
     * green build cannot catch getting it wrong. Either the bound is right for the call site
     * or the call site should not be bounding here.
     *
     * <p>{@link #containersOf} is the second method #133 added, and it is worth saying why
     * it is not the one that was reverted. It is not named for who calls it and it does not
     * differ from {@link #deeply} in its bounds alone: it answers a different question about
     * a value that is not a JSON shape — share it, rather than refuse it — which is a
     * decision no call site can make by choosing a bound. Getting the choice between them
     * wrong is caught by a green build in the direction that matters: {@code deeply} refuses
     * a {@code containersOf} snapshot, so a hostile map cannot be laundered through the
     * lenient one, and the strict one is the default a security path already reaches for.
     * This budget is not taken by {@code containersOf}, and that method's javadoc gives the
     * measurement rather than an assertion.
     */
    private static final int MAX_NODES = 100_000;

    private Frozen() {
    }

    /**
     * {@code map} and every {@link Map} or {@link Collection} inside it, copied and sealed.
     *
     * @throws NullPointerException if {@code map} is null
     * @throws IllegalArgumentException if a value is not a JSON shape, or the structure
     *     exceeds {@value #MAX_DEPTH} levels or {@value #MAX_NODES} expanded values
     */
    public static <K> Map<K, Object> deeply(Map<K, ?> map) {
        Objects.requireNonNull(map, "map");
        if (map instanceof Snapshot<?> snapshot && snapshot.nonJsonRefused) {
            // Already this method's own output, so already walked, already bounded and
            // already refused everything this method refuses. Handing it back is the whole
            // of #134: see the class javadoc for the 51% it deletes and for why the test is
            // the map's identity rather than a counter.
            //
            // `nonJsonRefused` and not merely `instanceof Snapshot`: containersOf returns
            // the same type and shares a non-JSON value by reference, so accepting one here
            // would let a caller launder a String[] past the refusal by copying it through
            // the lenient entry point first. The strict method accepts only the strict
            // method's own work.
            @SuppressWarnings("unchecked")
            Map<K, Object> already = (Map<K, Object>) map;
            return already;
        }
        return walk(map, "tool arguments", new int[] {MAX_NODES}, true);
    }

    /**
     * {@code map} and every {@link Map} or {@link Collection} inside it, copied and sealed;
     * every other value stored <em>by reference</em>.
     *
     * <p>For a map the deployment's own code built and may still be holding —
     * {@code Goal.parameters}, {@code AgentConfig.options}, {@code WorldState.facts},
     * {@code Document.metadata}. See the class javadoc for why this is not the exemption
     * the red-team review took apart, and for the one-line rule that decides between this
     * and {@link #deeply}.
     *
     * <p><strong>The principle both walks follow from.</strong> {@link #deeply} produces a
     * value a JSON parser could have produced — which is why it refuses a {@code String[]}
     * and why it turns a {@code Set} into a {@code List}. This method produces a value of
     * the shape the caller gave: a non-JSON value is kept, and a {@code Set} stays a
     * {@code Set}, because {@code AgentConfig.builder("m").option("stop", Set.of("\n"))} is
     * an ordinary thing to write and a copy that silently handed back a {@code List} would
     * fail the caller's cast and compare unequal to what it copied.
     *
     * <p><strong>What is still shared, said once here rather than seven times.</strong> A
     * value that is neither a map nor a collection is the caller's object and stays the
     * caller's object: an {@code Instant} or a {@code Duration} cannot change, and a
     * {@code StringBuilder} can, and this method cannot tell them apart. What it does
     * guarantee is that no <em>container</em> is shared, which is the case that propagates
     * — one nested list reaching three derived {@code Goal}s and every turn of a
     * transcript.
     *
     * <p><strong>No node budget, deliberately, and {@link #MAX_NODES} is the one this
     * method does not take.</strong> That budget exists to make a DAG terminate, and a DAG
     * arrives only from code — Jackson does not even share two identical subtrees, so on a
     * parsed payload the budget can only ever be a false positive. Both of the durable
     * callers are parsed payloads: {@code Goal} and {@code AgentConfig} are reconstructed
     * by Jackson through their canonical constructors on every workflow replay, and a throw
     * there fails the workflow task, which Temporal retries forever — the run stalls rather
     * than rejecting anything, which is #132 and #172 and is the worst failure mode in this
     * codebase. It could not even be relied on to refuse consistently: this method shares a
     * non-JSON value by reference, so one node before serialization can be a thousand after
     * it, and a bound that passed at submission could refuse the same run on replay.
     *
     * <p>The depth cap is kept, and the difference is not inconsistency. A cycle terminates
     * nothing without it; a cyclic map cannot be serialized either, so refusing it in this
     * constructor reports a defect the deployment already had, earlier, and as the
     * {@link RuntimeException} every runner turns into an error result. A DAG the
     * deployment built out of its own objects is the deployment's own structure, and no
     * party this class defends against can choose it.
     *
     * @throws NullPointerException if {@code map} is null
     * @throws IllegalArgumentException if the structure exceeds {@value #MAX_DEPTH} levels
     */
    public static <K> Map<K, Object> containersOf(Map<K, ?> map) {
        Objects.requireNonNull(map, "map");
        if (map instanceof Snapshot<?>) {
            // Either entry point's output will do here: deeply's is strictly stronger than
            // what this method promises, and this method's own is exactly it.
            @SuppressWarnings("unchecked")
            Map<K, Object> already = (Map<K, Object>) map;
            return already;
        }
        return walk(map, "the values in this map", null, false);
    }

    private static <K> Map<K, Object> walk(Map<K, ?> map, String subject, int[] budget,
                                           boolean refuseNonJson) {
        try {
            return new Snapshot<>(copyMap(map, subject, budget, refuseNonJson, 0),
                    refuseNonJson);
        } catch (StackOverflowError overflow) {
            // Defence in depth for #193, not the fix: MAX_DEPTH is sized so the counter wins
            // by 13x on the default stack. But the sizing is an estimate over frame layouts
            // this class does not control — a smaller stack, a caller already deep, a
            // hostile key whose own hashCode recurses — and the estimate being wrong must
            // not change WHICH throwable comes out. That is the whole point of the bound:
            // ToolInvocation calls this from a constructor on the durable path, where an
            // Error does not arrive where a RuntimeException would. It was two callers
            // until #170 — ToolInvocationRecord froze the same map a second time — and one
            // is the point: the rule is stated once, by the value both records now carry.
            // #130 widened ToolActivitiesImpl's catch to Throwable because an escaped Error
            // made Temporal retry one model-proposed call more than once, and #166 did the
            // same on the observer path. Catching here is safe in a way catching an Error
            // usually is not: by the time this line runs the whole walk has unwound, every
            // frame it held is gone, and the only state it touched was the local copy it was
            // building — there is no half-mutated object left behind to reason about.
            //
            // Shared by both entry points since #133, and containersOf needs it at least as
            // much: Goal and AgentConfig are rebuilt by Jackson inside a workflow replay,
            // which is precisely where an Error does not arrive where a RuntimeException
            // would.
            throw new IllegalArgumentException(
                    subject + " exhausted the stack before the " + MAX_DEPTH
                            + "-level limit could refuse them; a cyclic or deliberately"
                            + " hostile structure is the usual cause", overflow);
        }
    }

    private static <K> Map<K, Object> copyMap(Map<K, ?> map, String subject, int[] budget,
                                              boolean refuseNonJson, int depth) {
        // Not sized from map.size(). A hostile Map can answer -1, and LinkedHashMap takes
        // that as an initial capacity and throws IllegalArgumentException — turning a
        // snapshot into an exception on a path where the old copy silently produced an
        // empty map. The default capacity costs a resize and takes the argument away.
        Map<K, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) ->
                copy.put(key, value(value, subject, budget, refuseNonJson, depth + 1)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object value(Object value, String subject, int[] budget,
                                boolean refuseNonJson, int depth) {
        if (budget != null && --budget[0] < 0) {
            throw new IllegalArgumentException(
                    subject + " hold more than " + MAX_NODES + " values once expanded;"
                            + " a shared or cyclic structure is the usual cause");
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    subject + " nest deeper than " + MAX_DEPTH + " levels;"
                            + " a cyclic structure is the usual cause");
        }
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> nested) {
            return Collections.unmodifiableMap(copyMap((Map<Object, ?>) nested, subject,
                    budget, refuseNonJson, depth));
        }
        if (value instanceof Set<?> members && !refuseNonJson) {
            // containersOf only, and it is the one difference between the two walks that is
            // not about a threat. deeply produces a value a JSON parser could have produced,
            // so a Set becomes a List there: JSON has no set, and a Set only reaches a tool
            // argument from code. containersOf produces a value of the shape the caller
            // gave, and the caller here is the deployment — AgentConfig.builder("m")
            // .option("stop", Set.of("\n")) is ordinary, and a copy that handed back a List
            // would have changed the option's type under it, failed a cast, and compared
            // unequal to the Set it was taken of, because Set.equals refuses a List.
            // Sized like the map above, and for the same reason.
            Set<Object> copy = new LinkedHashSet<>();
            for (Object member : members) {
                copy.add(value(member, subject, budget, refuseNonJson, depth + 1));
            }
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Collection<?> items) {
            // Sized like the map above, and for the same reason. Every remaining Collection
            // becomes a List, in both walks. List and Set are the only two collection
            // interfaces the JDK specifies equals for; everything else — ArrayDeque is the
            // one that bites — inherits identity equality, which a defensive copy has
            // already broken by copying at all. A copy that is element-wise comparable is
            // more use than one that is equal to nothing, including itself before the copy.
            List<Object> copy = new ArrayList<>();
            for (Object item : items) {
                copy.add(value(item, subject, budget, refuseNonJson, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        if (!refuseNonJson) {
            // containersOf. Shared, and the class javadoc says so rather than this line
            // saying it seven times over at the callers.
            return value;
        }
        throw new IllegalArgumentException(
                "a tool argument may only hold JSON shapes — a string, number, boolean,"
                        + " null, map or collection — and this one holds a "
                        // getTypeName, not getName: the array case is the one a gate author
                        // actually hits, and "[Ljava.lang.String;" is not how anyone spells
                        // it in the code they need to change.
                        + value.getClass().getTypeName() + ", which cannot be snapshotted and"
                        + " would therefore stay changeable after a gate had read it");
    }

    /**
     * The map both entry points return, and the only thing that tells one of this class's
     * own snapshots from a map a caller built.
     *
     * <p>It exists to be recognised. {@code Collections.unmodifiableMap} was what this
     * class returned before, and two of them are indistinguishable — which is why
     * {@code ToolInvocation} re-walked a tree {@code ToolUseBlock} had already walked, once
     * per tool call and once per workflow replay on top of that (#134). A wrapper class is
     * what that costs, and #134 named it as the price of the option it recommends.
     *
     * <p>Every method delegates rather than being inherited from {@code AbstractMap}:
     * {@code AbstractMap.get} is a scan of the entry set, and the map this wraps is read on
     * every gate check and every render. The delegate is already an unmodifiable view, so
     * the mutators — including the {@code Map} default methods that route through
     * {@code put} and {@code remove} — refuse exactly as they did before this type existed.
     *
     * @param <K> the key type, which is the caller's; this type never inspects a key
     */
    private static final class Snapshot<K> implements Map<K, Object> {

        private final Map<K, Object> values;

        /**
         * Whether {@link #deeply} built this, i.e. whether a value that is not a JSON shape
         * was refused rather than stored by reference. {@code deeply} accepts only a
         * snapshot with this set; see its fast path for the laundering it stops.
         */
        private final boolean nonJsonRefused;

        Snapshot(Map<K, Object> values, boolean nonJsonRefused) {
            this.values = Collections.unmodifiableMap(values);
            this.nonJsonRefused = nonJsonRefused;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return values.containsValue(value);
        }

        @Override
        public Object get(Object key) {
            return values.get(key);
        }

        @Override
        public Object getOrDefault(Object key, Object fallback) {
            return values.getOrDefault(key, fallback);
        }

        @Override
        public Object put(K key, Object value) {
            return values.put(key, value);
        }

        @Override
        public Object remove(Object key) {
            return values.remove(key);
        }

        @Override
        public void putAll(Map<? extends K, ?> from) {
            values.putAll(from);
        }

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public Set<K> keySet() {
            return values.keySet();
        }

        @Override
        public Collection<Object> values() {
            return values.values();
        }

        @Override
        public Set<Entry<K, Object>> entrySet() {
            return values.entrySet();
        }

        @Override
        public void forEach(BiConsumer<? super K, ? super Object> action) {
            values.forEach(action);
        }

        // equals and hashCode delegate rather than compare identity: a snapshot has to go
        // on being equal to the map it was taken of, because ToolInvocation, Goal and
        // AgentConfig are records whose equality is their components', and a run's config
        // comparing unequal to itself after a round trip is not a small bug.
        @Override
        public boolean equals(Object other) {
            return other == this || values.equals(other);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }

        @Override
        public String toString() {
            return values.toString();
        }
    }
}
