package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Reviews a proposed tool invocation and returns an {@link ApprovalDecision} —
 * the human-in-the-loop seam for approving, rejecting, or editing hard-to-reverse
 * actions before they run. Wire one in with {@link ToolGates#requireApproval}.
 *
 * <p>An interactive harness implements this by prompting a person (CLI, chat, web);
 * unattended runs use {@link #DENY_ALL} to fail safe or a policy that approves only
 * known-safe shapes. The review runs on the agent thread, so a blocking prompt
 * blocks the loop — for asynchronous or durable approval, resolve the decision
 * elsewhere and hand this a ready answer.
 */
@FunctionalInterface
public interface Approver {

    /** Approves every invocation unchanged. */
    Approver APPROVE_ALL = new Approver() {
        @Override
        public boolean waitsForAHuman() {
            return false;
        }

        @Override
        public ApprovalDecision review(Tool tool, ToolInvocation invocation) {
            return ApprovalDecision.approve();
        }
    };

    /** Denies every invocation — the safe default for unattended runs. */
    Approver DENY_ALL = new Approver() {
        @Override
        public boolean waitsForAHuman() {
            return false;
        }

        @Override
        public ApprovalDecision review(Tool tool, ToolInvocation invocation) {
            return ApprovalDecision.deny(
                    "This action requires human approval, which was not granted.");
        }
    };

    /**
     * An approver that reaches its answer without waiting for anybody — the shape a lambda
     * otherwise has no way to declare (#290).
     *
     * <p>{@link #waitsForAHuman()} presumes {@code true}, which is right for a fail-closed
     * control and is not weakened here. What was missing is the opposite: a caller whose
     * policy decides from the arguments could only say so by writing a class purely to
     * override one method and return {@code false}. Since #283 that is not a matter of
     * taste — {@code ToolActivitiesImpl} asks every registered tool what gate it holds, and
     * {@code CodeExecutionTool} forwards it from the gate the tool was built with, so a
     * {@code requireApproval} over a plain lambda is refused at Temporal registration
     * however quickly it answers.
     *
     * <pre>{@code
     * CodeExecutionTool.builder(sandbox, bridged)
     *     .toolGate(ToolGates.requireApproval(
     *             invocation -> invocation.name().equals("send_wire"),
     *             Approver.withoutWaiting((tool, invocation) ->
     *                     ((Number) invocation.argument("amount")).longValue() <= 1_000
     *                             ? ApprovalDecision.approve()
     *                             : ApprovalDecision.deny("Over the unattended limit."))))
     *     .build();
     * }</pre>
     *
     * <p><strong>The parameter is a review function and not an {@code Approver}, which is
     * the whole of the safety argument.</strong> Taking an {@code Approver} would read
     * better at one call site and would let {@code withoutWaiting(pagesSomebody)} relabel
     * an implementation that had explicitly declared {@code true} — the laundering
     * {@code ToolActivitiesImpl}'s own comments name as the residual its checks cannot see,
     * offered here as a public method. A {@link BiFunction} carries no declaration to
     * contradict: the assertion that nobody is waited on is made by the caller, at the
     * point where it is written, about a function that has never claimed otherwise. An
     * existing approver can still be re-declared, and has to say so out loud —
     * {@code Approver.withoutWaiting(other::review)}.
     *
     * <p>Named for the property rather than the timing. {@code immediate(...)} was the first
     * name and reads, at a glance, as "immediately approve" — a dangerous thing for a
     * factory whose argument may well deny.
     *
     * <p>This asserts nothing it can check. An implementation that pages somebody inside
     * {@code decidedHere} is exactly as blocking as before and now says it is not, in the
     * same best-effort sense as every other declaration on this seam.
     *
     * @param decidedHere the review, which must return without waiting on anyone outside
     *     the process
     */
    static Approver withoutWaiting(BiFunction<Tool, ToolInvocation, ApprovalDecision> decidedHere) {
        Objects.requireNonNull(decidedHere, "decidedHere");
        return new Approver() {
            @Override
            public boolean waitsForAHuman() {
                return false;
            }

            @Override
            public ApprovalDecision review(Tool tool, ToolInvocation invocation) {
                return Objects.requireNonNull(decidedHere.apply(tool, invocation),
                        "decidedHere returned no decision");
            }
        };
    }

    /**
     * Whether reaching a decision may wait on someone outside the process.
     *
     * <p>Presumed {@code true}, which is the opposite polarity to most defaults here and
     * deliberate: this interface is named for the person, and an implementation that does
     * reach one is the case that hurts if it is assumed not to. Say {@code false} when the
     * answer is computed — {@link #DENY_ALL}, {@link #APPROVE_ALL}, a policy deciding from
     * the arguments, or a decision fetched before the review is called. From a lambda, say
     * it with {@link #withoutWaiting(BiFunction)}; overriding this method is otherwise the
     * only way, and writing a class to assert a negative is enough friction that the
     * honest answer went unsaid (#290).
     *
     * <p>The distinction has a caller. A durable runner cannot host a decision that waits:
     * it would burn the activity's timeout, fail, and be retried, asking the person again
     * each time. Declaring it here rather than on the gate is what keeps {@code DENY_ALL} —
     * the framework's own recommendation for unattended runs — usable durably, instead of
     * banning every approval-shaped policy because one shape of it blocks.
     */
    default boolean waitsForAHuman() {
        return true;
    }

    /**
     * Reviews {@code invocation} against the {@code tool} that would run it.
     *
     * <p>The tool is here because a person deciding whether to permit an action wants its
     * description and its declared {@link Tool#sideEffects()} — most of what there is to go
     * on besides the arguments. It is never null: a caller with no tool has nothing to
     * gate, so there is no case to write a branch for.
     */
    ApprovalDecision review(Tool tool, ToolInvocation invocation);
}
