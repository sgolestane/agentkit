package dev.agentkit.core.collab;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many {@code send_message} calls a {@link PeerGroup} may make before
 * {@link MessagingTools#SEND_MESSAGE} starts refusing.
 *
 * <p>A reply can send further messages — every collaborating agent can hold
 * {@code send_message}, so a peer answering one question may ask two — and without a
 * counter that every level shares there is nothing to stop the exchange. This is that
 * counter, and it lives on the {@link PeerGroup} rather than being passed in beside it.
 *
 * <h2>Why a type, and why the group owns it (#299)</h2>
 *
 * <p>{@code MessagingTools} used to take a bare {@link AtomicInteger} and carry the rule in
 * prose: <em>"Build every peer's tool from the <strong>same</strong> budget so the cap is
 * global."</em> Nothing in the signature said so. A caller who wrote
 * {@code MessagingTools.budget(6)} at each call site got one counter per tool and every test
 * stayed green.
 *
 * <p>That was not merely untidy. A tool is normally registered where the agent holding it is
 * built, and {@link Peer} builds its agent from a {@link java.util.function.Supplier} it runs
 * once <em>per interaction</em> — {@code CollaborationExample} registers its writer's
 * {@code send_message} inside such a supplier and keeps the counter outside it, and every
 * word of the guarantee depended on that second half. Build both inside, which is what
 * "one budget per tool" looks like when the tool is built there, and a peer gets a fresh
 * allowance for every message it answers: A messages B, B's fresh agent has a full
 * allowance, B messages A, and the exchange does not end. Measured on this branch by
 * planting exactly that: 875 nested peer runs before the JVM's stack ran out. The
 * termination guarantee survived only the version of the mistake in which the tool
 * instances outlive the run, and that is not the shape {@code Peer} encourages.
 *
 * <p>So the counter is no longer something a caller supplies per tool. There is exactly one
 * {@code MessageBudget} per {@code PeerGroup}, {@link MessagingTools#sendMessageTool(PeerGroup)}
 * reads it from the group, and the group is the object a peer must be registered in to be
 * addressable at all — so a second counter now means a second group, which is a visible act
 * rather than an oversight at a call site. "Build every peer's tool from the same budget" is
 * true by construction rather than by instruction.
 *
 * <h2>The scope this has, which is not a run</h2>
 *
 * <p>The tally is instance state that spans every call, so one budget bounds <em>one
 * run</em>: build a fresh {@code PeerGroup} per run, or call {@link #reset()} between runs,
 * otherwise a reused group carries its spend forward and a later run can start already
 * exhausted. That is {@code BudgetLlmClient}'s and {@code UsageMeter}'s sentence, deliberately
 * word for word, because it is the same hazard.
 *
 * <p>It is a caution rather than a guarantee, and the refusal text says so out loud. A tool
 * handler is handed an invocation, not a run — there is no run identity anywhere in this
 * package for a counter to key on — so the object genuinely cannot tell one run from the
 * next. What it used to do was claim otherwise: {@code "Message budget exhausted; cannot send
 * more messages this run."} A model told the run it is in has spent something the run never
 * spent has no reason to suspect the wiring, and reports a capability failure instead. The
 * refusal now names the group and says the allowance does not refill.
 *
 * <p>Thread-safe: {@link #tryReserve()} is a single atomic step and never leaves the count
 * negative, even transiently, so a concurrent {@link #remaining()} always reads a number the
 * caller can believe.
 */
public final class MessageBudget {

    private final int maxMessages;
    private final AtomicInteger remaining;

    private MessageBudget(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0, was " + maxMessages);
        }
        this.maxMessages = maxMessages;
        this.remaining = new AtomicInteger(maxMessages);
    }

    /** A fresh budget of {@code maxMessages} messages, shared by whichever group holds it. */
    public static MessageBudget of(int maxMessages) {
        return new MessageBudget(maxMessages);
    }

    /** The allowance this budget was built with. */
    public int maxMessages() {
        return maxMessages;
    }

    /** How many messages are still allowed. Never negative. */
    public int remaining() {
        return remaining.get();
    }

    /**
     * Takes one message from the allowance, or answers {@code false} when there is none.
     *
     * <p>Reserved <em>before</em> the peer runs, so a reply that sends further messages can
     * never push the total past the cap — which is what makes the exchange terminate.
     *
     * <p>One {@code getAndUpdate} rather than the decrement-then-undo pair this replaced.
     * That pair never over-sent, but it left the count at {@code -1} between the two steps,
     * so a concurrent {@link #remaining()} could read a negative number that no allowance
     * corresponds to. Nothing in the framework read it concurrently; the point of publishing
     * {@code remaining()} is that a caller now can.
     */
    public boolean tryReserve() {
        return remaining.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0;
    }

    /** Restores the full allowance so this group can carry a fresh run. */
    public void reset() {
        remaining.set(maxMessages);
    }

    @Override
    public String toString() {
        return "MessageBudget[" + remaining.get() + "/" + maxMessages + "]";
    }
}
