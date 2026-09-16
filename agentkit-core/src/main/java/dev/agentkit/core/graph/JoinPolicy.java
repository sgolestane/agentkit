package dev.agentkit.core.graph;

/**
 * How a node with several incoming edges decides whether to run.
 *
 * <p>The choice matters most when it is wrong. Under {@link #ALL} a missing input stops
 * the node, so you get no output and notice; under {@link #ANY} the node runs on what
 * survived and produces a complete-looking answer built from partial data, with nothing
 * in the text to say so. That asymmetry is why {@link #ALL} is the default.
 */
public enum JoinPolicy {

    /**
     * Run only when <em>every</em> incoming edge is taken. The default, and what a
     * pipeline wants: a step that needs both a draft and a review should not run holding
     * only one of them.
     */
    ALL,

    /**
     * Run when <em>at least one</em> incoming edge is taken, on whatever arrived. What an
     * aggregator wants — a summariser over several researchers is more useful with three
     * of four inputs than not at all — and a deliberate choice to make, because a node
     * under this policy cannot tell a partial answer from a whole one.
     */
    ANY
}
