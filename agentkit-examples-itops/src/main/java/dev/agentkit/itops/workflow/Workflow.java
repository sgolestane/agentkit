package dev.agentkit.itops.workflow;

import dev.agentkit.core.util.Frozen;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A reusable operational process, as a graph.
 *
 * <p>A graph rather than a list, because the processes worth automating are not linear:
 * offboarding branches on whether the person held privileged access, onboarding forks per
 * application, and almost everything has a step that only happens sometimes. A
 * representation that assumes a sequence forces those branches into the step bodies, where
 * they stop being inspectable.
 *
 * <p><strong>The definition never records runtime state.</strong> No {@code currentNode},
 * no {@code status}, nothing that differs between two people running the same workflow at
 * the same time. That lives in the execution. Mixing them is the mistake that makes a
 * workflow un-versionable and un-shareable, and it is easier to avoid at the start than to
 * unpick later.
 *
 * <p>Versioned, and generated definitions are expected: the eventual document-to-workflow
 * feature produces one of these and stores it {@code active=false} for a human to look at
 * before it can run. Nothing about this record assumes a person typed it.
 */
public record Workflow(String id, int version, String name, String description,
                       List<Node> nodes, List<Edge> edges, boolean active) {

    /**
     * One step.
     *
     * @param id     unique within the workflow; edges refer to it
     * @param type   what kind of step
     * @param label  what a human sees
     * @param tool   the tool to call, for {@link Type#TOOL} nodes
     * @param arguments arguments for that tool; values beginning {@code $} read from the
     *                  execution's variables rather than being literals. Must be JSON
     *                  shapes — a string, number, boolean, null, map or collection — since
     *                  they become a tool's arguments, which may hold nothing else
     * @param expression the test, for {@link Type#CONDITION} nodes
     * @param workflowId the sub-workflow, for {@link Type#WORKFLOW} nodes
     */
    public record Node(String id, Type type, String label, String tool,
                       Map<String, Object> arguments, String expression, String workflowId) {

        public enum Type { START, END, TOOL, AGENT, CONDITION, APPROVAL, WORKFLOW }

        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            // Frozen.deeply rather than Map.copyOf, which rejects a null value (#132).
            // These arguments are resolved and handed to a ToolInvocation (WorkflowRunner
            // line 167), where null is legal, so only this line disagreed.
            //
            // Two things an earlier version of this comment got wrong. It said the
            // arguments "come from a workflow definition read as JSON" — there is no JSON
            // loader for a workflow in this module, and every Node in the repository is
            // built with map literals in Workflows, so no null can reach here today. This
            // is defensive, which is a good enough reason without inventing a code path.
            //
            // And it did not mention that Frozen.deeply NARROWS what a Node may hold: a
            // Duration, an enum or an array was accepted by Map.copyOf and is refused here,
            // at definition time. That is deliberate — ToolInvocation refuses them too, so
            // the alternative is the same failure later and further from the author — but
            // it is a contract change on a public record in an example module people copy
            // from, and it belongs in the record's documentation rather than in a diff.
            arguments = arguments == null ? Map.of() : Frozen.deeply(arguments);
        }

        public static Node start() {
            return new Node("start", Type.START, "Start", null, Map.of(), null, null);
        }

        public static Node end(String id, String label) {
            return new Node(id, Type.END, label, null, Map.of(), null, null);
        }

        public static Node tool(String id, String label, String tool, Map<String, Object> arguments) {
            return new Node(id, Type.TOOL, label, tool, arguments, null, null);
        }

        public static Node condition(String id, String label, String expression) {
            return new Node(id, Type.CONDITION, label, null, Map.of(), expression, null);
        }

        public static Node approval(String id, String label) {
            return new Node(id, Type.APPROVAL, label, null, Map.of(), null, null);
        }
    }

    /**
     * A transition.
     *
     * @param when the branch label this edge is taken on — {@code "true"} or {@code "false"}
     *             out of a condition, {@code null} for an unconditional step
     */
    public record Edge(String from, String to, String when) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        public static Edge of(String from, String to) {
            return new Edge(from, to, null);
        }
    }

    public Workflow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    public Optional<Node> node(String nodeId) {
        return nodes.stream().filter(node -> node.id().equals(nodeId)).findFirst();
    }

    /** The node reached from {@code nodeId} along the {@code branch} edge, if any. */
    public Optional<Node> next(String nodeId, String branch) {
        return edges.stream()
                .filter(edge -> edge.from().equals(nodeId))
                .filter(edge -> edge.when() == null || edge.when().equals(branch))
                .findFirst()
                .flatMap(edge -> node(edge.to()));
    }
}
