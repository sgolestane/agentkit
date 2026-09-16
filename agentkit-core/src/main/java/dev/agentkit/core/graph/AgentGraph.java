package dev.agentkit.core.graph;

import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.concurrent.TaskContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A declared, acyclic graph of work: named nodes joined by edges, where an edge both
 * orders two nodes and carries the upstream result to the downstream one.
 *
 * <p>This is the deterministic counterpart to the repo's other orchestrators. A
 * {@code Supervisor} decides at runtime — a model picks which subagents to call, or the
 * caller hands it a flat list — and its subagents never see each other's work. A
 * {@code PlanningAgent} sequences steps and threads prior output forward, but with one
 * worker for every step and every prior output fed into every step. A graph is written
 * down in advance and adds what neither has: <em>a different agent per node</em>,
 * <em>parallel branches</em>, and <em>per-node input selection</em>, so a node receives
 * exactly what its edges deliver rather than the whole transcript so far.
 *
 * <pre>{@code
 * AgentGraph graph = AgentGraph.builder()
 *         .node("research", GraphNode.agent("Gather sources", researcher))
 *         .node("draft", GraphNode.agent("Write the briefing", writer))
 *         .node("factcheck", GraphNode.agent("Check every claim", checker))
 *         .node("revise", GraphNode.agent("Fix the flagged problems", editor))
 *         .edge("research", "draft")
 *         .edge("draft", "factcheck")
 *         // revise needs the draft, and only runs when the check found problems
 *         .edge("draft", "revise")
 *         .edge("factcheck", "revise", r -> r.output().contains("ISSUE"))
 *         .build();
 *
 * GraphResult result = graph.run(Goal.of("Write a briefing on X"));
 * }</pre>
 *
 * <h2>When a node runs</h2>
 *
 * Every edge carries a condition on the upstream result, defaulting to "it succeeded".
 * An edge is <strong>taken</strong> when its source ran and that condition holds. A node
 * becomes eligible once every incoming edge is resolved — each source having reached a
 * terminal state — and then runs according to its {@link JoinPolicy}: under the default
 * {@link JoinPolicy#ALL} every incoming edge must be taken, under {@link JoinPolicy#ANY}
 * one is enough. Otherwise it is {@link NodeState#SKIPPED}. A node with no incoming edges
 * is a root and always runs.
 *
 * <p>That default is what lets the two headline features compose. {@code revise} above
 * draws its data from {@code draft} and its gate from {@code factcheck}; under
 * {@code ANY} either edge alone would fire it, so the editor would be handed a list of
 * complaints without the draft they refer to. Under {@code ALL} it runs only when both
 * arrive.
 *
 * <p>Two more consequences worth knowing:
 *
 * <ul>
 *   <li>A failed node skips everything downstream of it, because the default condition
 *       requires success. To carry on regardless — a cleanup or reporting node — give
 *       that edge an explicit {@link #ALWAYS}.</li>
 *   <li>{@link JoinPolicy#ANY} is the aggregator's policy and is deliberately opt-in: a
 *       node running on partial input produces a whole-looking answer with nothing in it
 *       to say so, whereas a node that does not run is an absence you notice.</li>
 * </ul>
 *
 * <p>Several edges between the same pair are allowed and evaluated independently, which
 * under {@code ANY} means "either condition" and under {@code ALL} means "both".
 *
 * <h2>Execution</h2>
 *
 * Nodes whose dependencies are satisfied run concurrently on an injectable executor (a
 * per-call virtual-thread executor by default), so independent branches overlap.
 * {@link Builder#maxConcurrency(int)} caps how many run at once — across every concurrent
 * run of this graph, since the point is to protect a rate-limited backend — and
 * {@link Builder#timeout(Duration)} bounds a run. A node that throws anything, including
 * an {@code Error}, is recorded as failed rather than taking the graph down.
 *
 * <p><strong>A node that asks for a person stops the schedule.</strong> When a node's run
 * ends in {@code AWAITING_APPROVAL} — a gate stopped a tool call pending somebody's
 * decision — no further node is launched, the nodes already running are left to finish, and
 * the run ends as {@link GraphStop#AWAITING_APPROVAL} with {@link GraphResult#awaiting()}
 * naming what is pending. It is the one outcome that is neither success nor failure, and
 * treating it as failure was actively wrong rather than merely imprecise: the default edge
 * condition is "it succeeded", so a park skipped the downstream work <em>and took every
 * fallback branch</em>. Measured (#159) on a graph with
 * {@code edge("gated", "plan_b", r -> !r.isSuccess())}: the gated node parked, {@code plan_b}
 * ran, and the graph reported {@link GraphStop#COMPLETED}. The question was asked and the
 * effect obtained by the other arm while it went unanswered.
 *
 * <p>Instances are immutable and reusable, and the same graph can serve concurrent goals:
 * all scheduling state is per-{@link #run}.
 *
 * <p><strong>In-process only.</strong> The schedule lives in memory, so a crash mid-graph
 * loses it — unlike a single agent loop, which {@code agentkit-temporal} can make durable.
 * Individual nodes can still be durable: a {@link GraphNode} is a function, so one may
 * perfectly well start a Temporal workflow and wait for it.
 */
public final class AgentGraph {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);

    /** The default edge condition: the upstream node succeeded. */
    public static final Predicate<AgentResult> ON_SUCCESS = AgentResult::isSuccess;

    /** An edge condition that is always taken, however the upstream node ended. */
    public static final Predicate<AgentResult> ALWAYS = result -> true;

    /**
     * How long the driver waits per check when there is no deadline. Bounded rather than
     * blocking forever so that an executor shut down from under the run is noticed
     * instead of hanging: {@code shutdownNow} discards queued tasks without ever
     * completing their futures, so nothing would arrive to wake a blocking take.
     */
    private static final long PROBE_MILLIS = 100;

    /** How long to keep waiting after the executor reports itself shut down. */
    private static final long SHUTDOWN_GRACE_MILLIS = 5_000;

    private final Map<String, Node> nodes;
    private final List<Edge> edges;
    private final List<String> order;   // declaration order: keeps results deterministic
    private final List<String> leaves;  // nodes with no outgoing edge
    private final ExecutorService executor; // nullable: null => a fresh per-call executor
    private final Semaphore gate;       // nullable: null => unbounded. Shared across runs.

    /**
     * The single permit a running node lends to any run of this same graph it starts, set
     * on that node's own thread for the length of its body. Null on a thread holding no
     * permit, which is every thread that starts a top-level run. See
     * {@link Builder#maxConcurrency(int)} for what it buys.
     */
    private final ThreadLocal<Semaphore> lentGate = new ThreadLocal<>();
    private final Duration timeout;     // nullable: null => no deadline
    private final TaskContext taskContext;

    private AgentGraph(Builder b) {
        this.nodes = Map.copyOf(b.nodes);
        this.edges = List.copyOf(b.edges);
        this.order = List.copyOf(b.nodes.keySet());
        this.leaves = b.nodes.keySet().stream()
                .filter(name -> b.edges.stream().noneMatch(edge -> edge.from().equals(name)))
                .toList();
        this.executor = b.executor;
        // One semaphore for the graph, not one per run: the cap exists to protect a
        // rate-limited backend, and a per-run gate would let N concurrent goals multiply
        // it by N — exactly the situation the reuse this class encourages creates.
        this.gate = b.maxConcurrency > 0 ? new Semaphore(b.maxConcurrency) : null;
        this.timeout = b.timeout;
        this.taskContext = b.taskContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The node names, in declaration order. */
    public List<String> nodeNames() {
        return order;
    }

    /** The names of nodes with no outgoing edge — where the graph's answers come out. */
    public List<String> leaves() {
        return leaves;
    }

    /** Runs the graph toward {@code goal}. */
    public GraphResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        if (executor != null) {
            return runOn(executor, goal);
        }
        try (ExecutorService perCall = Executors.newVirtualThreadPerTaskExecutor()) {
            return runOn(perCall, goal);
        }
    }

    private GraphResult runOn(ExecutorService exec, Goal goal) {
        // Read here, on the driver thread, because a nested run's driver is the outer
        // node's own body. Carried into each submission by hand rather than left to
        // inherit: an injected pool's threads were created long before this run and would
        // inherit nothing — the same seam taskContext exists to cross.
        Semaphore lent = lentGate.get();
        Map<String, NodeOutcome> outcomes = new LinkedHashMap<>();
        Set<String> pending = new LinkedHashSet<>(order);
        Set<String> launched = new LinkedHashSet<>();
        boolean bounded = timeout != null;
        long deadlineNanos = bounded ? System.nanoTime() + timeout.toNanos() : 0L;

        CompletionService<NodeOutcome> completions = new ExecutorCompletionService<>(exec);
        List<Future<NodeOutcome>> inFlight = new ArrayList<>();
        int running = 0;
        GraphStop stop = GraphStop.COMPLETED;
        // Latched: once a node has asked for a person, nothing new starts for the rest of
        // the run. See the park branch below for why this is a latch and not a break.
        boolean parked = false;

        try {
            while (!pending.isEmpty() || running > 0) {
                if (!parked) {
                    running += launchReady(completions, inFlight, pending, launched,
                            outcomes, goal, lent);
                }
                if (running == 0) {
                    if (pending.isEmpty() || parked) {
                        // The last pass resolved everything by skipping: nothing left to
                        // launch and nothing to wait for. Or a node asked for a person, so
                        // whatever is still pending is deliberately never going to launch.
                        break;
                    }
                    // Unreachable for a validated DAG: nodes are still pending, nothing is
                    // running, and nothing became ready — so each waits on another of them.
                    throw new IllegalStateException(
                            "Graph stalled with nodes still pending: " + pending);
                }
                Awaited awaited = awaitOne(completions, exec, bounded, deadlineNanos);
                if (awaited.stop() != null) {
                    stop = awaited.stop();
                    break;
                }
                if (awaited.outcome() != null) {
                    running--;
                    outcomes.put(awaited.outcome().name(), awaited.outcome());
                    if (!parked && awaited.outcome().awaitsAPerson()) {
                        // Stop launching, and keep draining (#159). Breaking here instead
                        // would cancel every sibling still in flight, and a park says
                        // nothing about unrelated work that has already been paid for —
                        // TIMED_OUT cancels because the clock is the thing being enforced,
                        // and there is no such thing here. What must not happen is a *new*
                        // node starting: every edge out of the parked node is a route the
                        // author drew, and the fallback ones read `r -> !r.isSuccess()`.
                        // Measured before this: the gated node parked, the fallback ran,
                        // and the graph reported COMPLETED.
                        //
                        // The stop is set now rather than after the loop because the drain
                        // can overwrite it — a timeout or a shutdown during the drain is a
                        // truer account of how the run ended than the park that preceded
                        // it, and awaiting() carries the question either way.
                        parked = true;
                        stop = GraphStop.AWAITING_APPROVAL;
                    }
                }
            }
        } catch (InterruptedException e) {
            // Reported rather than rethrown, so a cancelled run still accounts for what it
            // spent; the flag is restored so the caller's own cancellation still works.
            Thread.currentThread().interrupt();
            stop = GraphStop.INTERRUPTED;
        } finally {
            // Normal exit leaves nothing running; on any early exit this frees nodes still
            // in flight instead of leaking them.
            for (Future<NodeOutcome> future : inFlight) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        return summarise(outcomes, launched, stop);
    }

    /**
     * Launches every node whose dependencies are now resolved, and marks as skipped every
     * node whose dependencies say it should not run. Repeats until nothing more can be
     * decided, because skipping one node immediately resolves the next — and nodes need
     * not be declared in topological order, so a single pass would leave a chain of skips
     * half-resolved and the scheduler would then report a stall.
     *
     * @return how many nodes were submitted
     */
    private int launchReady(CompletionService<NodeOutcome> completions,
            List<Future<NodeOutcome>> inFlight, Set<String> pending, Set<String> launched,
            Map<String, NodeOutcome> outcomes, Goal goal, Semaphore lent) {
        int count = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : List.copyOf(pending)) {
                List<Edge> incoming = incoming(name);
                if (!incoming.stream().allMatch(edge -> outcomes.containsKey(edge.from()))) {
                    continue;
                }
                pending.remove(name);
                changed = true;
                Node node = nodes.get(name);
                if (!shouldRun(node, incoming, outcomes)) {
                    outcomes.put(name, NodeOutcome.skipped(name));
                    continue;
                }
                NodeInput input = inputFor(name, goal, outcomes);
                try {
                    // The context is captured here, on the driver thread, so a node's work
                    // lands inside whatever the caller had current rather than starting
                    // from nothing.
                    inFlight.add(completions.submit(
                            taskContext.wrap(() -> runNode(name, node.node(), input, lent))));
                    launched.add(name);
                    count++;
                } catch (RuntimeException e) {
                    // A saturated or shut-down executor — or a third-party TaskContext that
                    // throws — is this node's failure, not the graph's: recording it lets
                    // the edge conditions decide what happens downstream instead of losing
                    // every other node's work on the way out. runNode's own catch cannot
                    // cover this, since it lives inside the callable being wrapped.
                    log.warn("Graph node '{}' could not be submitted", Quoted.of(name),
                            Quoted.failure(e));
                    outcomes.put(name, NodeOutcome.of(name, AgentResult.failed(e, 0)));
                }
            }
        }
        return count;
    }

    private static boolean shouldRun(Node node, List<Edge> incoming, Map<String, NodeOutcome> outcomes) {
        if (incoming.isEmpty()) {
            return true;
        }
        return node.join() == JoinPolicy.ALL
                ? incoming.stream().allMatch(edge -> taken(edge, outcomes))
                : incoming.stream().anyMatch(edge -> taken(edge, outcomes));
    }

    private static boolean taken(Edge edge, Map<String, NodeOutcome> outcomes) {
        NodeOutcome source = outcomes.get(edge.from());
        return source != null && source.result().filter(edge.condition()).isPresent();
    }

    /**
     * The results arriving over the edges that were actually taken.
     *
     * <p>Not simply "every upstream node that ran": the edge is the data channel, so a
     * node whose edge condition rejected it has sent nothing. A failed upstream is still
     * visible when its edge says so — that is what {@link #ALWAYS} is for.
     */
    private NodeInput inputFor(String name, Goal goal, Map<String, NodeOutcome> outcomes) {
        Map<String, AgentResult> dependencies = new LinkedHashMap<>();
        for (Edge edge : incoming(name)) {
            if (taken(edge, outcomes)) {
                outcomes.get(edge.from()).result()
                        .ifPresent(result -> dependencies.put(edge.from(), result));
            }
        }
        return new NodeInput(name, goal, dependencies);
    }

    private NodeOutcome runNode(String name, GraphNode node, NodeInput input, Semaphore lent) {
        // A node of a nested run queues on the permit the outer node lent it, not on the
        // graph's gate — which that outer node is holding and will not release until this
        // returns. Waiting on the gate there is a deadlock with no result, no error and
        // nothing logged, which is the worst shape a failure in this class can take.
        Semaphore slot = lent != null ? lent : gate;
        if (slot != null) {
            try {
                slot.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return NodeOutcome.of(name, AgentResult.failed(
                        new IllegalStateException("Cancelled while waiting for a concurrency slot"), 0));
            }
            // One permit to lend on, so a nested run's nodes take turns inside this node's
            // slot instead of all running free: the cap stays a cap.
            lentGate.set(new Semaphore(1));
        }
        try {
            return NodeOutcome.of(name, node.run(input));
        } catch (Throwable t) {
            // Throwable, not RuntimeException: an AssertionError out of a node is still
            // that node's failure, and letting it escape into the Future would destroy
            // every other node's results on the way out.
            log.warn("Graph node '{}' threw", Quoted.of(name), Quoted.failure(t));
            return NodeOutcome.of(name, AgentResult.failed(t, 0));
        } finally {
            if (slot != null) {
                // Removed, not left set: an injected pool reuses this thread, and a stale
                // lent permit would let the next unrelated node past the gate.
                lentGate.remove();
                slot.release();
            }
        }
    }

    /** One turn of the driver's wait: an outcome, a reason to stop, or neither (retry). */
    private record Awaited(NodeOutcome outcome, GraphStop stop) {
        static final Awaited RETRY = new Awaited(null, null);

        static Awaited of(NodeOutcome outcome) {
            return new Awaited(outcome, null);
        }

        static Awaited stopping(GraphStop stop) {
            return new Awaited(null, stop);
        }
    }

    private static Awaited awaitOne(CompletionService<NodeOutcome> completions, ExecutorService exec,
            boolean bounded, long deadlineNanos) throws InterruptedException {
        long remaining = bounded ? deadlineNanos - System.nanoTime() : Long.MAX_VALUE;
        Future<NodeOutcome> future;
        if (remaining <= 0) {
            // Drain before giving up: a node that finished inside the deadline is still a
            // result, and discarding it would make "keeps what finished" untrue exactly
            // when the graph is under the pressure that makes the promise matter.
            future = completions.poll();
            if (future == null) {
                return Awaited.stopping(GraphStop.TIMED_OUT);
            }
        } else {
            long waitMillis = Math.min(PROBE_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            future = completions.poll(waitMillis, TimeUnit.MILLISECONDS);
            if (future == null) {
                return exec.isShutdown() ? awaitShutdown(completions) : Awaited.RETRY;
            }
        }
        return Awaited.of(collect(future));
    }

    /**
     * The executor says it is shut down. A graceful shutdown lets running nodes finish, so
     * keep waiting for a grace period; {@code shutdownNow} discards queued tasks whose
     * futures never complete, and that is what the grace period gives up on.
     */
    private static Awaited awaitShutdown(CompletionService<NodeOutcome> completions)
            throws InterruptedException {
        Future<NodeOutcome> future = completions.poll(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        return future == null ? Awaited.stopping(GraphStop.ABANDONED) : Awaited.of(collect(future));
    }

    private static NodeOutcome collect(Future<NodeOutcome> future) throws InterruptedException {
        try {
            return future.get();
        } catch (CancellationException | ExecutionException e) {
            // runNode catches Throwable itself, so neither should be reachable; handled
            // rather than left to escape and lose the whole run's results if it ever is.
            throw new IllegalStateException("A graph node ended without a result", e);
        }
    }

    private GraphResult summarise(Map<String, NodeOutcome> outcomes, Set<String> launched, GraphStop stop) {
        Map<String, NodeOutcome> complete = new LinkedHashMap<>();
        int steps = 0;
        TokenUsage usage = TokenUsage.ZERO;
        for (String name : order) {
            NodeOutcome outcome = outcomes.get(name);
            if (outcome == null) {
                // A node that started and was cut short is a failure, not an absence — it
                // spent real time and tokens. Only one that never started is NOT_RUN.
                outcome = launched.contains(name)
                        ? NodeOutcome.of(name, AgentResult.failed(
                                new TimeoutException("Node '" + name + "' was still running when the graph "
                                        + "stopped (" + stop + ")"), 0))
                        : NodeOutcome.notRun(name);
            }
            complete.put(name, outcome);
            if (outcome.result().isPresent()) {
                steps += outcome.result().orElseThrow().steps();
                usage = usage.plus(outcome.result().orElseThrow().usage());
            }
        }
        return new GraphResult(complete, leaves, steps, usage, stop);
    }

    private List<Edge> incoming(String name) {
        return edges.stream().filter(edge -> edge.to().equals(name)).toList();
    }

    /** A node and how it joins its inputs. */
    private record Node(GraphNode node, JoinPolicy join) {
    }

    /** An edge from one node to another, taken when {@code condition} holds. */
    private record Edge(String from, String to, Predicate<AgentResult> condition) {
    }

    /** Builder for {@link AgentGraph}. */
    public static final class Builder {

        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private ExecutorService executor;
        private TaskContext taskContext = TaskContext.NONE;
        private int maxConcurrency;
        private Duration timeout;

        private Builder() {
        }

        /** Adds a node that runs only when every incoming edge is taken. Names are unique. */
        public Builder node(String name, GraphNode node) {
            return node(name, node, JoinPolicy.ALL);
        }

        /** Adds a node with an explicit {@link JoinPolicy}. */
        public Builder node(String name, GraphNode node, JoinPolicy join) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(join, "join");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Node name must not be blank");
            }
            if (nodes.putIfAbsent(name, new Node(node, join)) != null) {
                throw new IllegalArgumentException("Duplicate node name: " + name);
            }
            return this;
        }

        /** An edge taken when {@code from} succeeds. */
        public Builder edge(String from, String to) {
            return edge(from, to, ON_SUCCESS);
        }

        /**
         * An edge taken when {@code from} has run and {@code condition} holds — the way
         * to branch. Use {@link #ALWAYS} to carry on regardless of how {@code from} ended.
         *
         * <p>The condition is evaluated on the driver thread once the source finishes, so
         * keep it a cheap inspection of the result rather than more work.
         */
        public Builder edge(String from, String to, Predicate<AgentResult> condition) {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(condition, "condition");
            if (from.equals(to)) {
                throw new IllegalArgumentException("A node cannot depend on itself: " + from);
            }
            edges.add(new Edge(from, to, condition));
            return this;
        }

        /**
         * Carries the calling thread's ambient context into each node — with
         * {@code agentkit-otel}'s {@code telemetry.taskContext()}, that is what keeps
         * concurrent branches in one trace. Applies whether or not you also supply an
         * {@link #executor(ExecutorService)}, which matters because the default per-call
         * executor is not yours to wrap.
         *
         * <p>It does not inherit: a node that itself runs a graph or a {@code Supervisor}
         * must set it there too, or that level detaches while the rest looks fine. And it
         * carries the <em>caller's</em> context, not the upstream node's — in an
         * {@code a -> b} chain, {@code b}'s spans sit beside {@code a}'s rather than
         * inside them, because {@code a} has finished before {@code b} is submitted. The
         * trace shows one flat level per run, not the DAG's edges.
         */
        public Builder taskContext(TaskContext taskContext) {
            this.taskContext = Objects.requireNonNull(taskContext, "taskContext");
            return this;
        }

        /**
         * Runs nodes on {@code executor} instead of a fresh per-call virtual-thread
         * executor. The graph does not take ownership and will not shut it down; if it is
         * shut down elsewhere mid-run, the run ends as {@link GraphStop#ABANDONED} rather
         * than hanging.
         *
         * <p>Do not have a node run a nested graph on this same executor if it is
         * bounded — the outer node occupies a thread while waiting for the inner one, and
         * a small fixed pool will deadlock.
         *
         * <p>If you are tracing, prefer {@link #taskContext} to wrapping this: it also
         * covers the default per-call executor, so the setting does not silently stop
         * working the day someone removes the injection.
         */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Caps how many nodes run at once, so a wide graph does not fire unbounded
         * simultaneous LLM calls at a rate-limited backend. Unbounded by default. The cap
         * belongs to the graph, so concurrent runs of it share the budget rather than each
         * getting their own.
         *
         * <p><strong>A node that starts another run of this same graph runs it under the
         * permit it already holds.</strong> Without that, the recursive shape this class
         * invites — a node calling {@link AgentGraph#run} on its own graph for a sub-goal —
         * would queue for a permit its own caller is holding and hang forever with no
         * result, no error and nothing logged. That is the permit version of the deadlock
         * {@link #executor(ExecutorService)} warns about, and strictly worse than it,
         * because a shared executor can be avoided by not injecting one whereas the gate is
         * a field on the graph. So the outer node lends its slot: the nested run's nodes
         * take turns in that one permit, which keeps the cap honest — a node blocked on a
         * nested run is not doing work, so the graph still never has more than
         * {@code maxConcurrency} node bodies on the backend at once — and makes the
         * deadlock unexpressible. The lending travels with the node's own thread, so a node that
         * hands the nested run to some <em>other</em> thread and waits for it is back to
         * the deadlock; set a {@link #timeout(Duration)} if you build that, which turns the
         * hang into a reported {@link GraphStop#TIMED_OUT}.
         *
         * <p>A permit is held for a whole node body, and the budget is the graph's, so a
         * node that ignores cancellation holds its slot for as long as it keeps running —
         * against later runs too, not only its own. That is the price of a cap that spans
         * runs rather than one thrown away with each of them.
         */
        public Builder maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be >= 1, was " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * An overall deadline for a run. Nodes still running when it elapses are cancelled
         * and reported as failed with a {@link TimeoutException}; nodes that never started
         * come back {@link NodeState#NOT_RUN}. Results from nodes that already finished are
         * kept, including any that completed while the deadline was expiring.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive, was " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Validates and builds the graph.
         *
         * @throws IllegalStateException if there are no nodes, an edge names a node that
         *     does not exist, or the edges form a cycle
         */
        public AgentGraph build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("A graph must have at least one node");
            }
            for (Edge edge : edges) {
                if (!nodes.containsKey(edge.from())) {
                    throw new IllegalStateException("Edge from unknown node '" + edge.from()
                            + "'; declared nodes are " + nodes.keySet());
                }
                if (!nodes.containsKey(edge.to())) {
                    throw new IllegalStateException("Edge to unknown node '" + edge.to()
                            + "'; declared nodes are " + nodes.keySet());
                }
            }
            requireAcyclic();
            return new AgentGraph(this);
        }

        /**
         * Rejects cycles at build time rather than deadlocking at run time — the
         * scheduler waits for a node's dependencies, so a cycle would simply never
         * resolve, and a stack trace from that is far from the mistake.
         */
        private void requireAcyclic() {
            Map<String, Integer> remaining = new LinkedHashMap<>();
            nodes.keySet().forEach(name -> remaining.put(name, 0));
            edges.forEach(edge -> remaining.merge(edge.to(), 1, Integer::sum));

            Collection<String> ready = new ArrayList<>(
                    remaining.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList());
            int settled = 0;
            while (!ready.isEmpty()) {
                String name = ready.iterator().next();
                ready.remove(name);
                settled++;
                for (Edge edge : edges) {
                    if (edge.from().equals(name) && remaining.merge(edge.to(), -1, Integer::sum) == 0) {
                        ready.add(edge.to());
                    }
                }
            }
            if (settled != nodes.size()) {
                List<String> inCycle = remaining.entrySet().stream()
                        .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).sorted().toList();
                throw new IllegalStateException(
                        "The graph has a cycle; these nodes can never become ready: " + inCycle);
            }
        }
    }
}
