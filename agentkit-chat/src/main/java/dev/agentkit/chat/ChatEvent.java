package dev.agentkit.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Something that happened, on its way to a console that is watching.
 *
 * <p>The consoles this replaces asked instead. An earlier prototype polled
 * {@code /api/chat/history} every second and diffed message counts against what it had
 * already drawn; the Workbench example streamed run events but its chat did not. Polling is
 * why an answer appears a second after it exists, why a tool call that takes eleven seconds looks like a
 * hang, and why the trace could only ever be shown after the fact.
 *
 * <h2>Ordered per conversation, and the order is recorded</h2>
 *
 * <p>{@link #sequence} is assigned when the event is published, monotonically within one
 * conversation. It is what makes reconnecting a <em>resume</em>: a client that dropped at 41
 * asks for everything after 41 and gets it, rather than re-reading the transcript and
 * guessing what it had already drawn. It is also the only ordering a client should trust —
 * {@link #at} is a wall clock, and two events in the same millisecond are common.
 *
 * <h2>Whose run it is</h2>
 *
 * <p>A supervisor and its subagents all publish here, and their steps interleave — the
 * framework's own {@code AgentObserver} javadoc says a child's calls arrive before the
 * {@code delegate} that caused them, because delegation is synchronous. So {@link #runId} and
 * {@link #runName} come from {@code AgentRun} rather than being inferred, and a console can
 * indent a subagent's rows instead of presenting them as the supervisor's.
 *
 * @param sequence       where this falls in the conversation, from 1
 * @param conversationId which conversation is watching
 * @param turnId         which turn this belongs to; empty for an event about no turn
 * @param type           what happened
 * @param runId          the {@code AgentRun} that produced it, or empty
 * @param runName        that run's name, for a console that shows a subagent as one
 * @param data           the payload; JSON values only, as {@link dev.agentkit.core.tool.View}
 *                       takes them
 * @param at             when it happened
 */
public record ChatEvent(long sequence, String conversationId, String turnId, Type type,
                        String runId, String runName, Map<String, Object> data, Instant at) {

    /**
     * What an event is.
     *
     * <p>One per thing a console draws differently. {@link #TEXT_DELTA} is the only
     * high-volume one and it carries a fragment rather than the answer so far, because
     * resending the whole answer on every token is how a streaming UI becomes quadratic.
     */
    public enum Type {
        /**
         * A person said something; the turn exists and is running.
         *
         * <p>This and {@link #TURN_FINISHED} and {@link #ERROR} are published by whatever
         * drives the turn, not by {@link ChatObserver} — an observer is inside one agent run,
         * and a turn may contain no run at all (a refusal, an unconfigured model) or several.
         */
        TURN_STARTED,
        /**
         * A queued turn's worker picked it up.
         *
         * <p>Its own type because the two states look different and mean different things to a
         * person: one is "the console has your message", the other is "it is being worked on".
         * A console that showed a queued turn as running would say the agent was thinking when
         * it had not yet been handed the question.
         */
        TURN_RUNNING,
        /** A fragment of the assistant's answer, to append. */
        TEXT_DELTA,
        /** A model turn came back: its stop reason and what it cost. */
        MODEL_CALL,
        /** The model proposed a tool call. Fires before any gate has judged it. */
        TOOL_STARTED,
        /** A tool call settled — ran, was refused, was narrowed, or parked. */
        TOOL_FINISHED,
        /** A tool result carried something for a person to look at. */
        VIEW,
        /** A gate stopped a call and a person has to decide. */
        APPROVAL_REQUESTED,
        /**
         * Somebody decided, and the run it was holding up can go on.
         *
         * <p>Its own type rather than a second {@code APPROVAL_REQUESTED} carrying a verdict:
         * a console draws these differently — one opens a card and the other closes it — and
         * publishing both as a request meant a decision arriving in another tab looked like a
         * new question.
         */
        APPROVAL_DECIDED,
        /** The turn reached a terminal state. */
        TURN_FINISHED,
        /** Something went wrong, stated as a sentence a person can read. */
        ERROR
    }

    public ChatEvent {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(type, "type");
        turnId = turnId == null ? "" : turnId;
        runId = runId == null ? "" : runId;
        runName = runName == null ? "" : runName;
        data = data == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
        Objects.requireNonNull(at, "at");
    }

    /** Whether this event ends the turn it belongs to, so a client can stop waiting. */
    public boolean endsTurn() {
        return type == Type.TURN_FINISHED;
    }
}
