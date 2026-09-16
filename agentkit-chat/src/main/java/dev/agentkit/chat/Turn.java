package dev.agentkit.chat;

import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.View;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One exchange: what a person said, everything the agent did about it, and how it ended.
 *
 * <p>The unit the UI renders and the unit a test asserts on. Both of those matter more than
 * they sound. A console that models a conversation as a flat list of messages has nowhere to
 * hang the eleven tool calls behind one answer, so it either drops them or interleaves them
 * with the prose; and a test that can only see messages cannot say "this turn parked" without
 * pattern-matching on text.
 *
 * <h2>Why a turn is not two messages</h2>
 *
 * <p>It renders as two bubbles and it is one thing. The prompt and the answer share a
 * lifetime, a trace, a token bill and an outcome, and every question worth asking of a
 * transcript — what did this cost, why did it stop, what did it read — is a question about
 * the pair. Splitting them would mean a join on every one of those.
 *
 * <h2>{@code state} is how it ended, and absent is not a state</h2>
 *
 * <p>A turn is {@link State#RUNNING} from the moment it is begun. Everything else is
 * terminal, and {@link #detail} carries the sentence for the ones that need one — the
 * refusal, the question a person has to answer, the fact that somebody pressed stop. A
 * console shows {@code detail} verbatim, so it is written for a person rather than for a log.
 *
 * @param id             minted by the store
 * @param conversationId which conversation this belongs to
 * @param ordinal        where it falls in that conversation, from 1
 * @param userText       what the person said; may be a sentence the runtime wrote on their
 *                       behalf, as an upload's "(attached tickets.csv)" is
 * @param attachmentIds  files handed over with this message
 * @param answer         what the agent said, once it has said it
 * @param views          what the tools produced for a person to look at, in order
 * @param steps          what happened inside, in order
 * @param state          how it ended, or {@link State#RUNNING}
 * @param detail         the sentence a person reads about a state that needs one
 * @param usage          what the turn cost
 * @param startedAt      when the person sent it
 * @param endedAt        when it reached a terminal state, or null while running
 */
public record Turn(String id, String conversationId, long ordinal, String userText,
                   List<String> attachmentIds, String answer, List<View> views,
                   List<Step> steps, State state, String detail, TokenUsage usage,
                   Instant startedAt, Instant endedAt) {

    /**
     * How a turn ended.
     *
     * <p>{@link #WAITING_FOR_HUMAN} is the one that makes this an agent's console rather than
     * a chat window: a run that stopped to ask is not failed, is not finished, and must not
     * be rendered as either. The framework already models it that way —
     * {@code AgentStopReason} and the Workbench example's {@code Run.Status} both have it — and a console that
     * collapsed it into an error would undo the whole human-in-the-loop story.
     */
    public enum State {
        /**
         * Accepted, and waiting for the conversation's worker.
         *
         * <p>A transcript is ordered, so two turns of one conversation cannot run at once — and
         * until #348 that meant the second message was REFUSED. Which is wrong: a person who
         * thinks of something while the agent is working should be able to say it, and a
         * console that answers "wait" is one they have to babysit. Queued is the honest state
         * for a turn that exists, is theirs, and has not started.
         */
        QUEUED,
        RUNNING, COMPLETED, FAILED, WAITING_FOR_HUMAN, CANCELLED;

        public boolean isTerminal() {
            return this != RUNNING && this != QUEUED;
        }
    }

    public Turn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        userText = userText == null ? "" : userText;
        // Every list coerced from null and copied, for the reason ToolResult's own constructor
        // gives: these are read back out of JSON written by an older version, which supplies
        // null for anything it did not know to write. Copied rather than wrapped so a caller
        // that keeps its list cannot edit a turn already recorded.
        attachmentIds = copy(attachmentIds);
        answer = answer == null ? "" : answer;
        views = copy(views);
        steps = copy(steps);
        state = state == null ? State.QUEUED : state;
        detail = detail == null ? "" : detail;
        usage = usage == null ? TokenUsage.ZERO : usage;
        Objects.requireNonNull(startedAt, "startedAt");
    }

    private static <T> List<T> copy(List<T> source) {
        if (source == null) {
            return List.of();
        }
        // Nulls dropped rather than refused — List.copyOf throws on one, and this constructor
        // is on the path that reads a file somebody may have edited. A missing step is a worse
        // trace; a throw here is an unreadable conversation.
        List<T> copied = new ArrayList<>(source.size());
        for (T item : source) {
            if (item != null) {
                copied.add(item);
            }
        }
        return java.util.Collections.unmodifiableList(copied);
    }

    /** A turn just accepted: the person has spoken and no worker has picked it up yet. */
    public static Turn beginning(String id, String conversationId, long ordinal,
            String userText, List<String> attachmentIds, Instant now) {
        return new Turn(id, conversationId, ordinal, userText, attachmentIds, "", List.of(),
                List.of(), State.QUEUED, "", TokenUsage.ZERO, now, null);
    }

    /** This turn, now being worked on. */
    public Turn running() {
        return state != State.QUEUED ? this
                : new Turn(id, conversationId, ordinal, userText, attachmentIds, answer, views,
                        steps, State.RUNNING, detail, usage, startedAt, endedAt);
    }

    /** This turn with one more step on the record. */
    public Turn with(Step step) {
        Objects.requireNonNull(step, "step");
        List<Step> more = new ArrayList<>(steps);
        more.add(step);
        return new Turn(id, conversationId, ordinal, userText, attachmentIds, answer, views,
                more, state, detail, usage, startedAt, endedAt);
    }

    /** This turn with one more thing for a person to look at. */
    public Turn showing(View view) {
        Objects.requireNonNull(view, "view");
        List<View> more = new ArrayList<>(views);
        more.add(view);
        return new Turn(id, conversationId, ordinal, userText, attachmentIds, answer, more,
                steps, state, detail, usage, startedAt, endedAt);
    }

    /**
     * This turn, ended.
     *
     * <p>Refuses to end a turn that has already ended, rather than overwriting it. A second
     * terminal state is always a bug in the runtime — a cancel racing a completion, an
     * observer firing twice — and the first answer is the true one: it is what the person was
     * shown. Silently taking the last would make a cancelled turn read as completed depending
     * on thread scheduling.
     */
    public Turn ended(State finalState, String answerText, String detailText, TokenUsage cost,
            Instant now) {
        Objects.requireNonNull(finalState, "finalState");
        if (!finalState.isTerminal()) {
            throw new IllegalArgumentException("A turn ends in a terminal state, not "
                    + finalState + ".");
        }
        if (state.isTerminal()) {
            throw new IllegalStateException("Turn " + id + " already ended as " + state
                    + " and cannot end again as " + finalState + ".");
        }
        return new Turn(id, conversationId, ordinal, userText, attachmentIds, answerText, views,
                steps, finalState, detailText, cost, startedAt, now);
    }

    /** The steps of one kind, for a console that shows tool calls and model calls apart. */
    public List<Step> stepsOf(Step.Kind kind) {
        return steps.stream().filter(step -> step.kind() == kind).toList();
    }
}
