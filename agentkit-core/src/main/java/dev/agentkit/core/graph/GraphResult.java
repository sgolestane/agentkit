package dev.agentkit.core.graph;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.PendingApproval;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of running an {@link AgentGraph}: what every node did, and the totals.
 *
 * <p>There is deliberately no synthesis step. A {@code Supervisor} needs a
 * {@code Synthesizer} because its subagents run in parallel and nothing in the structure
 * says how to combine them; a graph can express that combination as a node, with the
 * inputs it actually wants. If you find yourself synthesising a {@code GraphResult} by
 * hand, that is a join node you have not drawn yet.
 *
 * @param outcomes every node's outcome, keyed by name, in declaration order; never
 *                 {@code null}. Contains an entry for every declared node — a node that
 *                 never ran is present with {@link NodeState#SKIPPED} or
 *                 {@link NodeState#NOT_RUN}, not missing
 * @param steps    the total loop steps across every node that ran
 * @param usage    the total token usage across every node that ran; never {@code null}
 * @param leaves   the names of nodes with no outgoing edge, in declaration order — the
 *                 ones {@link #leafOutputs()} reads; never {@code null}
 * @param stop     why the run ended; never {@code null}
 */
public record GraphResult(Map<String, NodeOutcome> outcomes, List<String> leaves, int steps,
                          TokenUsage usage, GraphStop stop) {

    public GraphResult {
        Objects.requireNonNull(outcomes, "outcomes");
        Objects.requireNonNull(leaves, "leaves");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(stop, "stop");
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0, was " + steps);
        }
        outcomes = Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
        leaves = List.copyOf(leaves);
    }

    /** The outcome of the named node. */
    public Optional<NodeOutcome> outcome(String nodeName) {
        return Optional.ofNullable(outcomes.get(nodeName));
    }

    /** The output of the named node, if it ran and succeeded. */
    public Optional<String> output(String nodeName) {
        return outcome(nodeName).flatMap(NodeOutcome::output);
    }

    /**
     * The outputs of the graph's leaf nodes that completed, keyed by node name.
     *
     * <p>Where a graph's answers come out. A branch that ends in one of two leaves puts
     * whichever ran in here, so a caller reads the result without hardcoding which arm
     * was taken. Empty when every leaf was skipped or failed — which is the case
     * {@link #isSuccess()} alone will not tell you about.
     */
    public Map<String, String> leafOutputs() {
        Map<String, String> outputs = new LinkedHashMap<>();
        for (String leaf : leaves) {
            outcome(leaf).flatMap(NodeOutcome::output).ifPresent(output -> outputs.put(leaf, output));
        }
        return Collections.unmodifiableMap(outputs);
    }

    /**
     * Every node that ran and failed.
     *
     * <p>A node that stopped for a person's decision is <em>not</em> here: it is a question,
     * not a failure, and a caller that treats the two alike is the defect #159 measured.
     * {@link #awaiting()} is where a park shows up, and {@link #isSuccess()} is false for
     * either.
     */
    public List<NodeOutcome> failures() {
        return outcomes.values().stream().filter(o -> o.state() == NodeState.FAILED).toList();
    }

    /**
     * The tool calls a gate stopped pending somebody's decision, across every node that ran.
     *
     * <p>Empty unless {@link #stop()} is {@link GraphStop#AWAITING_APPROVAL}. Derived from
     * the outcomes rather than stored, like {@link #steps} and {@link #usage} before it, so
     * there is one copy of the fact and no way for a future constructor call to forget it.
     *
     * <p>More than one node can appear here even though the graph stops scheduling on the
     * first park: nodes already in flight are left to finish, and one of them may park too.
     * All of them are questions somebody owes an answer on, so all of them are listed.
     */
    public List<PendingApproval> awaiting() {
        return outcomes.values().stream().map(NodeOutcome::result).flatMap(Optional::stream)
                .filter(AgentResult::isAwaitingApproval)
                .flatMap(result -> result.awaiting().stream())
                .toList();
    }

    /**
     * Whether the run finished with nothing failed and nothing left unrun.
     *
     * <p>Note this can be {@code true} for a graph that produced no answer at all: a
     * branch whose every arm was legitimately skipped ran exactly as written. Check
     * {@link #leafOutputs()} when what you need is an answer rather than a clean run.
     *
     * <p>A parked graph is not successful, and is caught by all three clauses at once —
     * the stop is {@link GraphStop#AWAITING_APPROVAL}, and everything the park kept from
     * starting is {@link NodeState#NOT_RUN}. That belt and braces is deliberate: this is
     * the predicate a caller reaches for first, and it must not answer {@code true} while
     * somebody is still owed a decision.
     */
    public boolean isSuccess() {
        return stop == GraphStop.COMPLETED && failures().isEmpty()
                && outcomes.values().stream().noneMatch(o -> o.state() == NodeState.NOT_RUN);
    }
}
