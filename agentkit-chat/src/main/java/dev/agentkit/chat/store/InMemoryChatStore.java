package dev.agentkit.chat.store;

import dev.agentkit.chat.Attachment;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.View;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The whole store in memory, which is what every test wants and what a demo with nothing to
 * remember can use.
 *
 * <p>Thread-safe by holding one lock per conversation rather than one for the store: a turn
 * is appended to from a worker while an HTTP thread reads the transcript, and those are the
 * two threads that actually contend. A single lock would serialise every conversation in the
 * process against every other, which is the wrong shape for a console that may have several
 * runs in flight.
 *
 * <p>{@link FileChatStore} is built on this rather than beside it, so the two cannot come to
 * disagree about what {@code begin} or {@code end} mean.
 */
public class InMemoryChatStore implements ChatStore {

    private final Clock clock;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, List<Turn>> turns = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Attachment> attachments = new ConcurrentHashMap<>();
    private final Map<String, byte[]> content = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public InMemoryChatStore() {
        this(Clock.systemUTC());
    }

    /** With a clock a test can hold still, so an ordering assertion is not a race. */
    public InMemoryChatStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    protected Instant now() {
        return clock.instant();
    }

    /**
     * Called after anything is written, so a subclass can persist it.
     *
     * <p>One hook rather than an override per method: {@link FileChatStore} needs to write the
     * same three things whatever changed, and a per-method hook is a list a later method
     * forgets to join.
     */
    protected void changed(Conversation conversation) {
        // Nothing to do; a store that only remembers has nowhere to write.
    }

    /** Called after an attachment's bytes are taken. */
    protected void changed(Attachment attachment, byte[] bytes) {
        // As above.
    }

    /** Called after a conversation is forgotten. */
    protected void forgotten(String conversationId) {
        // As above.
    }

    // --- conversations --------------------------------------------------------------

    @Override
    public Conversation create(String tenantId, String title) {
        Objects.requireNonNull(tenantId, "tenantId");
        Instant now = now();
        // Ids.observe, called for everything loaded from disk, is what keeps this from
        // overwriting a conversation that already exists — see its javadoc for what that
        // silently cost before it existed.
        //
        // A putIfAbsent-and-retry loop was written here first as a second defence. It came
        // out because no test could kill it: with observe correct the collision is
        // unreachable, so the loop was a line that could be deleted with the suite still
        // green, and this repository's rule is that such a line is worse than the argument it
        // saves. One mechanism, pinned by
        // ARestartDoesNotLoseTheConversationTest#aRestartDoesNotMintAnIdThatAlreadyExists.
        Conversation conversation = new Conversation(Ids.next("conv"), tenantId, title, now, now);
        conversations.put(conversation.id(), conversation);
        turns.put(conversation.id(), new ArrayList<>());
        changed(conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> conversation(String tenantId, String id) {
        return Optional.ofNullable(id == null ? null : conversations.get(id))
                .filter(found -> found.tenantId().equals(tenantId));
    }

    @Override
    public List<Conversation> conversations(String tenantId) {
        return conversations.values().stream()
                .filter(found -> found.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(Conversation::updatedAt).reversed()
                        // A tie on the clock is possible and a list that reorders under the
                        // reader is worse than one in an arbitrary but stable order.
                        .thenComparing(Conversation::id))
                .toList();
    }

    @Override
    public Optional<Conversation> rename(String tenantId, String id, String title) {
        if (conversation(tenantId, id).isEmpty()) {
            return Optional.empty();
        }
        // Under the conversation's lock, like every other write to it. Without this, a rename
        // racing a step append is a lost update in whichever direction loses: `replace` reads
        // the conversation, appends, and writes back a `touched` copy, so a title set between
        // those two points is reverted.
        synchronized (lockFor(id)) {
            Conversation current = conversations.get(id);
            if (current == null || !current.tenantId().equals(tenantId)) {
                return Optional.empty();
            }
            Conversation renamed = current.titled(title, now());
            conversations.put(renamed.id(), renamed);
            changed(renamed);
            return Optional.of(renamed);
        }
    }

    @Override
    public boolean delete(String tenantId, String id) {
        if (conversation(tenantId, id).isEmpty()) {
            return false;
        }
        conversations.remove(id);
        turns.remove(id);
        // The files go with the conversation. Leaving them would make "delete this thread"
        // a promise the store does not keep about the one part of it that was a file.
        attachments.values().removeIf(attachment -> {
            if (attachment.conversationId().equals(id)) {
                content.remove(attachment.id());
                return true;
            }
            return false;
        });
        forgotten(id);
        // The lock goes too. One Object per conversation is small and a console that runs for
        // months creating and deleting threads keeps every one of them otherwise.
        locks.remove(id);
        return true;
    }

    // --- turns ----------------------------------------------------------------------

    private Object lockFor(String conversationId) {
        return locks.computeIfAbsent(conversationId, key -> new Object());
    }

    @Override
    public Turn begin(String tenantId, String conversationId, String userText,
            List<String> attachmentIds) {
        if (conversation(tenantId, conversationId).isEmpty()) {
            throw new IllegalArgumentException(
                    "No such conversation: " + dev.agentkit.core.util.Quoted.of(
                            dev.agentkit.core.util.Cut.to(String.valueOf(conversationId), 120)));
        }
        synchronized (lockFor(conversationId)) {
            List<Turn> existing = turns.computeIfAbsent(conversationId, key -> new ArrayList<>());
            Turn turn = Turn.beginning(Ids.next("turn"), conversationId, existing.size() + 1L,
                    userText, attachmentIds, now());
            existing.add(turn);
            touch(conversationId);
            return turn;
        }
    }

    /**
     * Marks a conversation changed, from inside its own lock.
     *
     * <p>Re-read here rather than passed in, which is the fix for a lost update: the callers
     * used to read the conversation before taking the lock and write a {@code touched} copy of
     * it afterwards, so a rename landing between those two points was silently reverted.
     */
    private void touch(String conversationId) {
        Conversation current = conversations.get(conversationId);
        if (current == null) {
            return;
        }
        Conversation touched = current.touched(now());
        conversations.put(touched.id(), touched);
        changed(touched);
    }

    @Override
    public Optional<Turn> turn(String tenantId, String conversationId, String turnId) {
        if (conversation(tenantId, conversationId).isEmpty()) {
            return Optional.empty();
        }
        synchronized (lockFor(conversationId)) {
            return turns.getOrDefault(conversationId, List.of()).stream()
                    .filter(found -> found.id().equals(turnId))
                    .findFirst();
        }
    }

    @Override
    public List<Turn> turns(String tenantId, String conversationId) {
        if (conversation(tenantId, conversationId).isEmpty()) {
            return List.of();
        }
        synchronized (lockFor(conversationId)) {
            return List.copyOf(turns.getOrDefault(conversationId, List.of()));
        }
    }

    @Override
    public Optional<Turn> addStep(String tenantId, String conversationId, String turnId,
            Step.Kind kind, String name, Map<String, Object> detail, long millis,
            boolean failed) {
        return replace(tenantId, conversationId, turnId, turn -> turn.with(
                new Step(sequence.incrementAndGet(), kind, name,
                        detail == null ? Map.of() : new LinkedHashMap<>(detail),
                        now(), millis, failed)));
    }

    @Override
    public Optional<Turn> show(String tenantId, String conversationId, String turnId, View view) {
        return replace(tenantId, conversationId, turnId, turn -> turn.showing(view));
    }

    @Override
    public Optional<Turn> markRunning(String tenantId, String conversationId, String turnId) {
        return replace(tenantId, conversationId, turnId, Turn::running);
    }

    @Override
    public Optional<Turn> end(String tenantId, String conversationId, String turnId,
            Turn.State state, String answer, String detail, TokenUsage usage) {
        return replace(tenantId, conversationId, turnId,
                turn -> turn.ended(state, answer, detail, usage, now()));
    }

    /** The one place a turn is swapped for a newer version of itself, under its lock. */
    private Optional<Turn> replace(String tenantId, String conversationId, String turnId,
            java.util.function.UnaryOperator<Turn> change) {
        if (conversation(tenantId, conversationId).isEmpty()) {
            return Optional.empty();
        }
        synchronized (lockFor(conversationId)) {
            List<Turn> existing = turns.computeIfAbsent(conversationId, key -> new ArrayList<>());
            for (int i = 0; i < existing.size(); i++) {
                if (existing.get(i).id().equals(turnId)) {
                    Turn updated = change.apply(existing.get(i));
                    existing.set(i, updated);
                    touch(conversationId);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }
    }

    // --- attachments ----------------------------------------------------------------

    @Override
    public Attachment attach(String tenantId, String conversationId, String name,
            String mediaType, byte[] content) {
        conversation(tenantId, conversationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such conversation: " + dev.agentkit.core.util.Quoted.of(
                                dev.agentkit.core.util.Cut.to(String.valueOf(conversationId), 120))));
        byte[] bytes = content == null ? new byte[0] : content.clone();
        Attachment attachment = new Attachment(Ids.next("att"), tenantId, conversationId, name,
                mediaType, bytes.length, now());
        attachments.put(attachment.id(), attachment);
        this.content.put(attachment.id(), bytes);
        changed(attachment, bytes);
        return attachment;
    }

    @Override
    public Optional<Attachment> attachment(String tenantId, String id) {
        return Optional.ofNullable(id == null ? null : attachments.get(id))
                .filter(found -> found.tenantId().equals(tenantId));
    }

    @Override
    public List<Attachment> attachments(String tenantId, String conversationId) {
        return attachments.values().stream()
                .filter(found -> found.tenantId().equals(tenantId))
                .filter(found -> found.conversationId().equals(conversationId))
                .sorted(Comparator.comparing(Attachment::uploadedAt)
                        .thenComparing(Attachment::id))
                .toList();
    }

    @Override
    public Optional<byte[]> content(String tenantId, String id) {
        // Through attachment(), so the tenant check is the same one and cannot drift: the
        // bytes are the thing worth stealing, and a second spelling of the check here is how
        // the two come to disagree.
        return attachment(tenantId, id)
                .map(found -> content.get(found.id()))
                .map(byte[]::clone);
    }

    /**
     * Whether any tenant's attachment still stands at {@code id}.
     *
     * <p>The one unscoped question in the store, and it exists because
     * {@link FileChatStore#forgotten} has to sweep files whose owner has just been deleted —
     * at which point asking "does tenant X own this?" cannot be answered, and answering it
     * "no" would delete every other conversation's uploads too. Not on {@link ChatStore},
     * because every caller that is not the file sweep wants the scoped question.
     */
    protected boolean knowsAttachment(String id) {
        return id != null && attachments.containsKey(id);
    }

    /** For {@link FileChatStore} to fill on load, without going through the public writes. */
    protected void load(Conversation conversation, List<Turn> conversationTurns) {
        conversations.put(conversation.id(), conversation);
        turns.put(conversation.id(), new ArrayList<>(conversationTurns));
        // Everything counted in this process starts at zero, and everything loaded here was
        // counted in a previous one. Without these three lines a restart mints ids that
        // already exist and step sequences that repeat inside a turn already on disk — see
        // Ids.observe for what that cost.
        Ids.observe(conversation.id());
        for (Turn turn : conversationTurns) {
            Ids.observe(turn.id());
            for (Step step : turn.steps()) {
                sequence.accumulateAndGet(step.sequence(), Math::max);
            }
        }
    }

    /** As {@link #load(Conversation, List)}, for a file that came back off disk. */
    protected void load(Attachment attachment, byte[] bytes) {
        attachments.put(attachment.id(), attachment);
        content.put(attachment.id(), bytes);
        Ids.observe(attachment.id());
    }
}
