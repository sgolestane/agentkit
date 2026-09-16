package dev.agentkit.itops.workflow;

import java.util.List;
import java.util.Map;

/**
 * The workflows the demo ships with.
 *
 * <p>Written by hand here; the same structures are what a document-extraction step would
 * produce, which is why {@link Workflow} carries {@code active} — a generated definition is
 * stored inactive and a person turns it on. Nothing in the representation assumes an author.
 */
public final class Workflows {

    private Workflows() {
    }

    public static List<Workflow> seeded() {
        return List.of(offboarding(), accessReview());
    }

    /**
     * Employee offboarding, with the branch that makes it worth drawing as a graph.
     *
     * <p>A privileged account and an ordinary one take different routes: the privileged one
     * goes past a human before anything irreversible, the ordinary one is suspended and
     * documented. Written as a sequence, that difference disappears into a step body where
     * nobody reviewing the process can see it.
     *
     * <p>Note that the approval node is not the only protection. Every tool call still passes
     * the supervisor, so a step whose arguments turn out to name a privileged group parks
     * even on the branch that did not expect to.
     */
    private static Workflow offboarding() {
        return new Workflow("employee-offboarding", 1, "Employee offboarding",
                "Suspend or remove a departing employee's access, with a human checkpoint "
                        + "before anything irreversible.",
                List.of(
                        Workflow.Node.start(),
                        Workflow.Node.tool("find-user", "Look up the account",
                                "identity.find_user", Map.of("email", "$email")),
                        Workflow.Node.tool("check-privileged", "Check privileged group membership",
                                "identity.get_group_members",
                                Map.of("group", "Production-Administrators")),
                        Workflow.Node.condition("is-privileged",
                                "Did they hold production administrator access?",
                                "check-privileged contains $email"),
                        Workflow.Node.approval("approve-removal",
                                "A person confirms before privileged access is torn down"),
                        Workflow.Node.tool("remove-privileged", "Remove production admin access",
                                "identity.remove_user_from_group",
                                Map.of("user", "$email", "group", "Production-Administrators")),
                        Workflow.Node.tool("suspend", "Suspend the account",
                                "identity.suspend_user", Map.of("email", "$email")),
                        Workflow.Node.end("done", "Offboarding complete")),
                List.of(
                        Workflow.Edge.of("start", "find-user"),
                        Workflow.Edge.of("find-user", "check-privileged"),
                        Workflow.Edge.of("check-privileged", "is-privileged"),
                        new Workflow.Edge("is-privileged", "approve-removal", "true"),
                        new Workflow.Edge("is-privileged", "suspend", "false"),
                        Workflow.Edge.of("approve-removal", "remove-privileged"),
                        Workflow.Edge.of("remove-privileged", "suspend"),
                        Workflow.Edge.of("suspend", "done")),
                true);
    }

    /** A read-only workflow, to show that not every process changes something. */
    private static Workflow accessReview() {
        return new Workflow("privileged-access-review", 1, "Privileged access review",
                "List who currently holds production administrator access.",
                List.of(
                        Workflow.Node.start(),
                        Workflow.Node.tool("members", "List production administrators",
                                "identity.get_group_members",
                                Map.of("group", "Production-Administrators")),
                        Workflow.Node.end("done", "Review complete")),
                List.of(
                        Workflow.Edge.of("start", "members"),
                        Workflow.Edge.of("members", "done")),
                true);
    }
}
