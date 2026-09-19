package dev.agentkit.chat.store;

import dev.agentkit.chat.Attachment;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import java.util.List;
import java.util.Optional;

/**
 * Everything a chat console remembers, behind one tenant-scoped facade.
 *
 * <p>Two implementations, and the difference between them is the whole point of the seam:
 * {@link InMemoryChatStore} for tests, which need no disk and no cleanup, and
 * {@link FileChatStore} for a demo, where a conversation that vanishes on restart makes the
 * knowledge base and the lesson book pointless — work is supposed to accumulate.
 *
 * <h2>Tenant-scoped reads, including the ones that look like they cannot leak</h2>
 *
 * <p>Every method that returns something takes a {@code tenantId} and returns empty rather
 * than another tenant's row, <em>including</em> lookups by an id that was minted here and
 * could not be guessed. Unguessable is not the same as unauthorised, and a store where some
 * reads check and others do not is one where the next reader has to work out which is which.
 *
 * <h2>Appending is not "save the whole thing back"</h2>
 *
 * <p>{@link #addStep} and {@link #show} exist rather than a general {@code save(Turn)},
 * because a turn is written to from a worker thread while it runs and read from an HTTP
 * thread at the same time. Read-modify-write from the caller would lose steps under exactly
 * the load a live console puts on it; the append happens inside the store, where it can be
 * atomic.
 */
public interface ChatStore {

    // --- conversations --------------------------------------------------------------

    /** A new, empty conversation. */
    Conversation create(String tenantId, String title);

    /**
     * A conversation with {@code agent}, pinned for its whole life. A store that cannot keep a pin refuses one rather
     * than drop it: a conversation that forgot its agent would be answered by whichever agent is asked.
     */
    default Conversation create(String tenantId, String title, Conversation.Pin agent) {
        if (agent == null) {
            return create(tenantId, title);
        }
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot pin a conversation to an agent");
    }

    /** One conversation, if it exists and belongs to {@code tenantId}. */
    Optional<Conversation> conversation(String tenantId, String id);

    /** Every conversation of {@code tenantId}, most recently touched first. */
    List<Conversation> conversations(String tenantId);

    /** Renames a conversation, returning it as it now is. */
    Optional<Conversation> rename(String tenantId, String id, String title);

    /**
     * Forgets a conversation, its turns and its attachments.
     *
     * @return whether there was one to forget
     */
    boolean delete(String tenantId, String id);

    // --- turns ----------------------------------------------------------------------

    /**
     * Starts a turn: the person has spoken and nothing has happened yet.
     *
     * <p>The ordinal is assigned here rather than by the caller, so two messages sent at once
     * cannot land on the same number.
     */
    Turn begin(String tenantId, String conversationId, String userText,
            List<String> attachmentIds);

    /**
     * Starts a turn the person sent to {@code agent} — in a conversation not pinned to one agent, where each turn goes
     * to its own. Null leaves it to be routed.
     */
    default Turn begin(String tenantId, String conversationId, String userText, List<String> attachmentIds,
                       Conversation.Pin agent) {
        Turn begun = begin(tenantId, conversationId, userText, attachmentIds);
        return agent == null ? begun : route(tenantId, conversationId, begun.id(), agent).orElse(begun);
    }

    /**
     * Records the agent, at its version, a turn goes to, in a conversation not pinned to one agent. A store that cannot
     * keep it refuses, rather than forget which agent answered.
     */
    default Optional<Turn> route(String tenantId, String conversationId, String turnId, Conversation.Pin agent) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot record a turn's agent");
    }

    /** One turn, if it exists in a conversation belonging to {@code tenantId}. */
    Optional<Turn> turn(String tenantId, String conversationId, String turnId);

    /** Every turn of a conversation, oldest first. */
    List<Turn> turns(String tenantId, String conversationId);

    /**
     * Records something that happened inside a running turn.
     *
     * <p>{@code sequence} on the returned step is assigned here, so the trace reads in the
     * order the run happened even when two threads write at once.
     *
     * @return the turn as it now is, or empty if there is no such turn
     */
    Optional<Turn> addStep(String tenantId, String conversationId, String turnId,
            Step.Kind kind, String name, java.util.Map<String, Object> detail, long millis,
            boolean failed);

    /** Records something for a person to look at, produced inside a running turn. */
    Optional<Turn> show(String tenantId, String conversationId, String turnId,
            dev.agentkit.core.tool.View view);

    /** Marks a queued turn as being worked on. */
    Optional<Turn> markRunning(String tenantId, String conversationId, String turnId);

    /**
     * Ends a turn.
     *
     * <p>Ending an already-ended turn throws, because the first answer is the one the person
     * was shown — see {@link Turn#ended}.
     */
    Optional<Turn> end(String tenantId, String conversationId, String turnId, Turn.State state,
            String answer, String detail, dev.agentkit.core.llm.TokenUsage usage);

    // --- attachments ----------------------------------------------------------------

    /** Takes a file, holds it server-side, and hands back the handle. */
    Attachment attach(String tenantId, String conversationId, String name, String mediaType,
            byte[] content);

    /** One attachment's handle, if it exists and belongs to {@code tenantId}. */
    Optional<Attachment> attachment(String tenantId, String id);

    /** Every attachment of a conversation, oldest first. */
    List<Attachment> attachments(String tenantId, String conversationId);

    /**
     * The bytes.
     *
     * <p>Reached only from a tool or from whatever serves a download — never assembled into a
     * model's context, which is what {@link Attachment}'s javadoc means by "the bytes are not
     * here".
     */
    Optional<byte[]> content(String tenantId, String id);
}
