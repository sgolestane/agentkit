package dev.agentkit.chat;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.Ids;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.Approver;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs turns, off the caller's thread, and lets a person stop one or answer one.
 *
 * <h2>A message returns immediately</h2>
 *
 * <p>{@link #say} records the turn, hands it to a worker and returns. It does not wait for the
 * model, because the thing on the other end of it is a browser: a request that blocks for
 * ninety seconds is one a proxy will cut, and the whole reason {@link ChatEvents} exists is
 * that the answer arrives on the stream rather than in a response body. The
 * prototype console this replaces reached the same conclusion and built a
 * single-thread worker for it; this is that, with the parts it did not have.
 *
 * <h2>One worker per conversation</h2>
 *
 * <p>Not one for the process, and not one per turn. Per conversation is what the domain
 * actually is: a transcript is ordered, so two turns of the same conversation must not run at
 * once, and two different conversations have no reason to wait for each other. It also makes
 * cancelling meaningful — there is exactly one thread to interrupt.
 *
 * <h2>A turn sees the conversation so far</h2>
 *
 * <p>A turn is its own run, and a run starts from its goal — so without help, "INC-4211" typed in
 * answer to "which incident?" reaches a model that never asked. Each turn is therefore given the
 * conversation's recent finished turns: what the person said and what the answer was, and nothing
 * else. Not the steps, not the tool results, and never a view (see
 * {@code AViewIsNeverFedBackAsContextTest}). They travel in the turn's first message after the new
 * text, fenced as {@link Spotlight.Kind#ADVISORY} — the run's own earlier work, acted on, unable to
 * redefine what this turn is for — rather than in the {@link Goal}, which is logged, compared in evals
 * and handed to observers. {@link History} says how much; {@link History#NONE} turns it off.
 *
 * <h2>Approval blocks the run rather than replaying it</h2>
 *
 * <p>A gate that needs a person calls an {@link Approver}, and {@code Approver}'s own javadoc
 * says an interactive harness implements it by prompting: "the review runs on the agent
 * thread, so a blocking prompt blocks the loop". Here that is a feature. The alternative —
 * ending the run and replaying it after a decision — is what the durable path does because it
 * has to, and it costs the run's whole context: the resumed run has to be talked back to the
 * same call. Blocking keeps the conversation exactly where it was, and the cost is one parked
 * thread per undecided question, which for a console is not a cost at all.
 *
 * <p>The turn stays {@link Turn.State#RUNNING} while it waits. It has not ended, and saying it
 * had would be a lie a console then has to undo. What tells the person is the
 * {@link ChatEvent.Type#APPROVAL_REQUESTED} event and {@link #pending}.
 */
public final class ChatRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChatRuntime.class);

    /** How long a parked question waits before it is treated as unanswerable. */
    private static final java.time.Duration APPROVAL_PATIENCE = java.time.Duration.ofHours(12);

    /**
     * Builds the agent for one turn.
     *
     * <p>The application's whole contribution. It chooses the model, the tools, the system
     * prompt, the gate, the trust floor — everything this module has no opinion about — and
     * gets back the wiring it cannot supply itself: the conversation this belongs to, and an
     * {@link Approver} that asks the person watching.
     *
     * <p>Throwing {@link ChatUnavailable} from here is how a console with no model configured
     * answers: the sentence reaches the person unchanged.
     */
    @FunctionalInterface
    public interface Agents {
        Agent agentFor(Session session);
    }

    /**
     * What an application needs in order to build a turn's agent.
     *
     * <p>{@link #approver} is the piece it could not make itself. Wire it in with
     * {@code ToolGates.requireApproval(when, session.approver())} and a gate that needs a
     * person will ask the person watching this conversation.
     */
    public record Session(String tenantId, String conversationId, String turnId,
                          String userText, List<String> attachmentIds, ChatStore store,
                          ChatEvents events, Approver approver) {

        /** A builder that already streams and already records into this turn. */
        public Agent.Builder agent(dev.agentkit.core.llm.LlmClient llm,
                dev.agentkit.core.tool.ToolRegistry tools,
                dev.agentkit.core.agent.AgentConfig config) {
            return ChatAgents.builder(llm, tools, config, store, events, tenantId,
                    conversationId, turnId);
        }
    }

    /**
     * Something a person has to settle before a run can go on.
     *
     * <p>Two shapes, and they are answered differently. An {@link Kind#ACTION} is a call the
     * gate stopped: the person approves it, refuses it, or edits its arguments. A
     * {@link Kind#QUESTION} is the agent asking something it cannot work out — the person types
     * a sentence and the run reads it as a tool result.
     *
     * @param capability the family this decision generalises to, if the deployment names one.
     *     It is what "stop asking me about this" is keyed on: a person who has approved
     *     resetting one password is answering about password resets, not about that call. The
     *     framework cannot derive it — {@code ToolCatalog} in the Workbench example maps a tool to
     *     its capability — so it is supplied, and empty means this deployment does not group
     *     tools that way.
     */
    public record PendingDecision(String id, String tenantId, String conversationId,
                                  String turnId, Kind kind, String tool,
                                  Map<String, Object> arguments, String capability,
                                  String reason, String effect, boolean reversible,
                                  String question, Instant askedAt) {

        public enum Kind { ACTION, QUESTION }

        public PendingDecision {
            kind = kind == null ? Kind.ACTION : kind;
            arguments = arguments == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            capability = capability == null ? "" : capability;
            question = question == null ? "" : question;
        }

        Map<String, Object> asEventData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approvalId", id);
            data.put("kind", kind.name());
            data.put("tool", tool);
            data.put("arguments", arguments);
            data.put("capability", capability);
            data.put("reason", reason);
            data.put("effect", effect);
            data.put("reversible", reversible);
            data.put("question", question);
            return data;
        }
    }

    /**
     * Where a standing decision goes, when a deployment keeps them.
     *
     * <p>"Stop asking me about this" and "keep refusing this" are the two halves of an operator
     * teaching a console, and both are keyed on a capability rather than on a call — the next
     * run reaching for a sibling tool in the same family is exactly the repeat they exist to
     * make rarer. What that means in practice is the application's: the Workbench example has a correction book
     * and a trust list, and this module has neither and should not grow one.
     *
     * <p>The default refuses in a sentence rather than silently doing nothing. An operator who
     * ticked "stop asking me" and was never asked again would believe a thing that is not true.
     */
    @FunctionalInterface
    public interface StandingDecisions {

        /**
         * Remembers a decision for a whole capability.
         *
         * @return what to tell the person, or empty if it was remembered without comment
         */
        Optional<String> remember(String capability, boolean approved, String by, String note);

        /** A deployment that keeps none, and says so. */
        StandingDecisions NONE = (capability, approved, by, note) -> Optional.of(
                "This console does not keep standing decisions, so you will be asked again.");
    }

    /**
     * How much of a conversation's earlier turns each turn is given.
     *
     * @param turns              the most recent finished turns to include; 0 for none
     * @param maxCharsPerMessage the most of each message and each answer to include
     */
    public record History(int turns, int maxCharsPerMessage) {

        /** Eight turns, 1,500 characters of each message and answer. */
        public static final History DEFAULT = new History(8, 1_500);

        /** Each turn sees only its own message. */
        public static final History NONE = new History(0, 0);

        public History {
            if (turns < 0 || maxCharsPerMessage < 0) {
                throw new IllegalArgumentException("History bounds must not be negative");
            }
        }
    }

    /** What one running turn is, so cancelling and answering have something to reach. */
    private static final class InFlight {
        private volatile Future<?> work;
        private volatile Thread worker;
        private volatile String turnId;
        private final String conversationId;
        private volatile boolean cancelled;

        InFlight(String conversationId, String turnId) {
            this.conversationId = conversationId;
            this.turnId = turnId;
        }

        /**
         * Whether this claim has not yet been given its work.
         *
         * <p>A claim is put into the map before {@code submit} returns, so for a moment it has
         * no {@code Future}. Treating that as "done" would let a second message slip in during
         * exactly the window the claim exists to close.
         */
        boolean unstarted() {
            return work == null && turnId == null;
        }
    }

    private final ChatStore store;
    private final ChatEvents events;
    private final Agents agents;
    private final java.util.function.Function<Tool, String> capabilityOf;
    private final StandingDecisions standing;
    private final History history;
    private final Map<String, ExecutorService> workers = new ConcurrentHashMap<>();
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();
    private final Map<String, PendingDecision> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ApprovalDecision>> answers =
            new ConcurrentHashMap<>();

    public ChatRuntime(ChatStore store, ChatEvents events, Agents agents) {
        this(store, events, agents, tool -> "", StandingDecisions.NONE, History.DEFAULT);
    }

    /**
     * With a deployment's own answers to the two questions this module cannot answer: what
     * family a tool belongs to, and where a standing decision is kept.
     */
    public ChatRuntime(ChatStore store, ChatEvents events, Agents agents,
            java.util.function.Function<Tool, String> capabilityOf, StandingDecisions standing) {
        this(store, events, agents, capabilityOf, standing, History.DEFAULT);
    }

    /** As above, choosing how much of the conversation so far each turn is given. */
    public ChatRuntime(ChatStore store, ChatEvents events, Agents agents,
            java.util.function.Function<Tool, String> capabilityOf, StandingDecisions standing,
            History history) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.capabilityOf = Objects.requireNonNull(capabilityOf, "capabilityOf");
        this.standing = Objects.requireNonNull(standing, "standing");
        this.history = Objects.requireNonNull(history, "history");
    }

    public ChatStore store() {
        return store;
    }

    public ChatEvents events() {
        return events;
    }

    // --- saying something -----------------------------------------------------------

    /**
     * Records what the person said and starts working on it.
     *
     * <p>Returns as soon as the turn exists. Everything after that arrives on the event stream.
     *
     * <h4>Queued, not refused</h4>
     *
     * <p>A transcript is ordered, so two turns of one conversation cannot run at once — and
     * until #348 that meant the second message was <em>refused</em> with a 409. Which is the
     * wrong answer to the right constraint: a person who thinks of something while the agent
     * is working should be able to say it, and a console that tells them to wait is one they
     * have to babysit. The turn is accepted as {@link Turn.State#QUEUED} and the conversation's
     * single-threaded worker keeps the order.
     */
    public Turn say(String tenantId, String conversationId, String userText,
            List<String> attachmentIds) {
        Turn turn = store.begin(tenantId, conversationId, userText, attachmentIds);
        nameItAfterTheFirstThing(tenantId, conversationId, turn, userText);
        events.publish(conversationId, turn.id(), ChatEvent.Type.TURN_STARTED, "", "",
                Map.of("userText", userText == null ? "" : userText,
                        "ordinal", turn.ordinal(),
                        "state", turn.state().name(),
                        "attachments", attachmentIds == null ? List.of() : attachmentIds));
        // Tracked per TURN rather than per conversation: a conversation can have one turn
        // running and several waiting behind it, and cancelling has to be able to name which.
        InFlight flight = new InFlight(conversationId, turn.id());
        inFlight.put(turn.id(), flight);
        flight.work = workerFor(conversationId).submit(
                () -> run(tenantId, conversationId, turn, attachmentIds));
        return turn;
    }

    /**
     * Titles a conversation from the first thing said in it.
     *
     * <p>Done here rather than in a client, because a list of ten "New conversation" rows is a
     * list nobody can search, switch between, or share a link to — and a console with several
     * clients would otherwise have some threads named and some not.
     *
     * <p>Only ever the first turn, and only when nothing has named it: a person who renames a
     * thread has said what it is about, and a later message must not overrule them.
     */
    private void nameItAfterTheFirstThing(String tenantId, String conversationId, Turn turn,
            String userText) {
        if (turn.ordinal() != 1 || userText == null || userText.isBlank()) {
            return;
        }
        store.conversation(tenantId, conversationId)
                .filter(conversation -> conversation.title().isBlank())
                .ifPresent(conversation -> store.rename(tenantId, conversationId,
                        Cut.to(userText.strip().lines().findFirst().orElse("").strip(), 60)));
    }

    private void run(String tenantId, String conversationId, Turn turn,
            List<String> attachmentIds) {
        InFlight flight = inFlight.get(turn.id());
        if (flight != null) {
            flight.worker = Thread.currentThread();
        }
        if (flight != null && flight.cancelled) {
            // Stopped while it was still waiting its turn. Nothing has been spent on it and
            // nothing should be.
            //
            // Narrower than it looks, and no test can force it: `cancelTurn` cancels the future
            // of anything not yet started, which is what normally stops a queued turn. This
            // covers the window where the executor had already taken the task off the queue —
            // so `Future.cancel` returned false — but `run` had not yet reached its first line.
            // Kept because what it prevents is a model call nobody will read the answer to.
            endTurn(tenantId, conversationId, turn.id(), Turn.State.CANCELLED, "",
                    "You stopped this before it started.", TokenUsage.ZERO);
            return;
        }
        store.markRunning(tenantId, conversationId, turn.id());
        events.publish(conversationId, turn.id(), ChatEvent.Type.TURN_RUNNING, "", "",
                Map.of("state", Turn.State.RUNNING.name()));
        Turn.State state = Turn.State.FAILED;
        String answer = "";
        String detail = "";
        TokenUsage usage = TokenUsage.ZERO;
        try {
            Session session = new Session(tenantId, conversationId, turn.id(), turn.userText(),
                    attachmentIds == null ? List.of() : attachmentIds, store, events,
                    approverFor(tenantId, conversationId, turn.id()));
            Agent agent = agents.agentFor(session);
            List<dev.agentkit.core.message.ContentBlock> alsoSent = new java.util.ArrayList<>();
            earlierTurns(tenantId, conversationId, turn).ifPresent(alsoSent::add);
            alsoSent.addAll(seeable(tenantId, attachmentIds == null ? List.of() : attachmentIds));
            AgentResult result = agent.run(Goal.of(turn.userText()), alsoSent);
            usage = result.usage();
            answer = result.output();
            if (result.isSuccess()) {
                state = Turn.State.COMPLETED;
            } else if (result.isAwaitingApproval()) {
                // A gate that ENDS the run to ask, rather than blocking — the durable shape.
                // Both are supported and they are different states: this turn is over and a
                // person owes it an answer, which is exactly what WAITING_FOR_HUMAN means.
                state = Turn.State.WAITING_FOR_HUMAN;
                detail = result.awaiting().stream()
                        .map(awaiting -> awaiting.why().reason())
                        .findFirst().orElse("A decision is needed before this can go on.");
            } else {
                state = Turn.State.FAILED;
                detail = "The run stopped: " + result.stopReason().name().toLowerCase(
                        java.util.Locale.ROOT).replace('_', ' ') + ".";
            }
        } catch (ChatUnavailable stated) {
            // The console saying something true about its configuration. Reaches the person
            // as the sentence its author wrote, not as "something went wrong".
            state = Turn.State.FAILED;
            detail = stated.getMessage();
        } catch (RuntimeException failure) {
            state = Turn.State.FAILED;
            detail = "That failed: " + Cut.to(String.valueOf(failure.getMessage()), 500);
            LOG.warn("A turn of {} failed", Quoted.of(conversationId),
                    Quoted.failure(failure));
        } finally {
            inFlight.remove(turn.id());
            if (flight != null && flight.cancelled && state != Turn.State.COMPLETED) {
                // A person pressed stop. Recorded as theirs rather than as a failure, because
                // it is a decision and a transcript that calls it an error is wrong about who
                // did what.
                //
                // Unless the run had already finished. A stop arriving as the answer lands is
                // a race with no right answer in the abstract, and there is one in practice:
                // the answer exists and the person is about to be shown it, so throwing it
                // away and reporting a cancellation would be the console lying about work it
                // actually did.
                state = Turn.State.CANCELLED;
                detail = "You stopped this.";
                answer = "";
            }
            // The interrupt flag is handed back by the loop when a run ends interrupted; clear
            // it here so this worker thread — which is pooled and will run the next turn — does
            // not start that one already interrupted.
            Thread.interrupted();
            forgetPendingOf(turn.id());
            endTurn(tenantId, conversationId, turn.id(), state, answer, detail, usage);
            if (flight != null) {
                flight.worker = null;
            }
        }
    }

    /**
     * How many images one turn puts in front of the model.
     *
     * <p>Four, because a turn with twenty screenshots is a context bomb and the person who
     * dragged them in did not mean it as one. What is left out is said in the turn's text
     * rather than dropped, for the reason every bound here says what it dropped: a console
     * that silently showed the model half of what a person handed it is a console whose
     * answers cannot be reasoned about.
     */
    private static final int MAX_IMAGES = 4;

    /**
     * The uploads a model can actually be shown, as content blocks.
     *
     * <p>Before this an image reached the model as {@code "there is a file called shot.png"}
     * and nothing else — the file tools decode as text and refuse anything that is not — so
     * the agent answered about a filename while a person believed it was answering about the
     * picture (#374). That gap is worse than no attachments at all, because nothing about
     * the answer says which of the two happened.
     *
     * <p>Everything that is not an image is left alone. It is still an attachment, still
     * downloadable, and still reachable through the file tools; this is only about the ones
     * a vision model can look at.
     */
    /**
     * The conversation's recent finished turns before {@code turn}, fenced, or empty for a first turn or
     * when {@link #history} is {@link History#NONE}. Message and answer only: a turn's steps, tool
     * results and views are never replayed.
     */
    private Optional<dev.agentkit.core.message.TextBlock> earlierTurns(String tenantId,
            String conversationId, Turn turn) {
        if (history.turns() == 0 || history.maxCharsPerMessage() == 0) {
            return Optional.empty();
        }
        List<Turn> finished = store.turns(tenantId, conversationId).stream()
                .filter(earlier -> earlier.ordinal() < turn.ordinal() && earlier.state().isTerminal())
                .toList();
        if (finished.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (Turn earlier : finished.subList(Math.max(0, finished.size() - history.turns()),
                finished.size())) {
            String answer = earlier.answer() == null || earlier.answer().isBlank()
                    ? "(no answer: " + earlier.state().name().toLowerCase(java.util.Locale.ROOT) + ")"
                    : Cut.to(earlier.answer(), history.maxCharsPerMessage());
            text.append("Person: ").append(Cut.to(earlier.userText(), history.maxCharsPerMessage()))
                    .append("\nAssistant: ").append(answer).append("\n\n");
        }
        return Optional.of(dev.agentkit.core.message.TextBlock.of(
                "Earlier in this conversation, for context. The message above is the one to answer now.\n"
                        + Spotlight.wrap(Spotlight.Kind.ADVISORY, Source.of("conversation"),
                                text.toString().strip())));
    }

    private List<dev.agentkit.core.message.ContentBlock> seeable(String tenantId,
            List<String> attachmentIds) {
        List<dev.agentkit.core.message.ContentBlock> blocks = new java.util.ArrayList<>();
        int skipped = 0;
        for (String id : attachmentIds) {
            java.util.Optional<Attachment> attachment = store.attachment(tenantId, id);
            if (attachment.isEmpty()
                    || !dev.agentkit.core.message.ImageBlock.canBeSeen(
                            attachment.get().mediaType())) {
                continue;
            }
            if (blocks.size() >= MAX_IMAGES) {
                skipped++;
                continue;
            }
            java.util.Optional<byte[]> bytes = store.content(tenantId, id);
            if (bytes.isEmpty()) {
                continue;
            }
            try {
                blocks.add(dev.agentkit.core.message.ImageBlock.of(
                        attachment.get().mediaType(), bytes.get()));
            } catch (IllegalArgumentException tooBigOrWrongType) {
                // A refusal ImageBlock wrote a sentence for. It reaches the model as text on
                // the turn rather than as nothing, so an answer about a picture nobody could
                // see is not what happens.
                skipped++;
                LOG.info("An upload was not shown to the model: {}",
                        Quoted.of(String.valueOf(tooBigOrWrongType.getMessage())));
            }
        }
        if (skipped > 0) {
            blocks.add(dev.agentkit.core.message.TextBlock.of(skipped
                    + " further image(s) were uploaded and are NOT shown here — at most "
                    + MAX_IMAGES + " fit in one turn, and one may have been too large. Say so"
                    + " if your answer depends on them."));
        }
        return blocks;
    }

    private void endTurn(String tenantId, String conversationId, String turnId,
            Turn.State state, String answer, String detail, TokenUsage usage) {
        try {
            store.end(tenantId, conversationId, turnId, state, answer, detail, usage);
        } catch (IllegalStateException alreadyEnded) {
            // Only reachable if something else ended it first, which is a bug worth a line
            // rather than an exception on a worker whose caller is gone.
            LOG.warn("Turn {} had already ended", Quoted.of(turnId),
                    Quoted.failure(alreadyEnded));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", state.name());
        data.put("answer", answer);
        data.put("detail", detail);
        data.put("inputTokens", usage.inputTokens());
        data.put("outputTokens", usage.outputTokens());
        events.publish(conversationId, turnId, ChatEvent.Type.TURN_FINISHED, "", "", data);
        if (state == Turn.State.FAILED && !detail.isEmpty()) {
            events.publish(conversationId, turnId, ChatEvent.Type.ERROR, "", "",
                    Map.of("message", detail));
        }
    }

    // --- stopping -------------------------------------------------------------------

    /**
     * Stops whatever this conversation is doing.
     *
     * <p>By interrupting the worker, which the agent loop honours: it checks
     * {@code Thread.interrupted()} between steps and ends the run rather than starting
     * another. So a tool already running finishes — there is no safe way to stop a call
     * halfway — and nothing after it begins.
     *
     * <p>A turn parked on an approval is stopped the same way, plus the question is
     * abandoned, or the run would sit on a future nobody will complete.
     *
     * @return whether there was anything to stop
     */
    public boolean cancel(String tenantId, String conversationId) {
        // Everything outstanding for the conversation: the one running plus anything behind
        // it. A Stop that left three queued turns to start one after another would be a button
        // that did not do what it says.
        //
        // TWO PASSES, and the order is the whole of #382. Marking and stopping in one pass
        // raced: stopping the running turn interrupts its worker, the worker's finally ends
        // that turn, and the per-conversation executor picks the NEXT one up immediately —
        // while this loop is still on its way to it. By the time the walk arrived, that turn
        // had a worker thread, so the branch taken was `worker.interrupt()`; an interrupt does
        // not unwind a thread that is not in an interruptible wait, and the first thing a turn
        // does is call the model. It was cancelled correctly and it had already spent a model
        // call, about one time in three.
        //
        // Marking first closes it without any new state: a turn that starts mid-walk finds its
        // flag already set at `run`'s first line, which is the check that was always there and
        // that the old ordering arrived too late to satisfy.
        List<Turn> outstanding = store.turns(tenantId, conversationId).stream()
                .filter(turn -> !turn.state().isTerminal())
                .toList();
        for (Turn turn : outstanding) {
            InFlight flight = inFlight.get(turn.id());
            if (flight != null) {
                flight.cancelled = true;
            }
        }
        boolean stopped = false;
        for (Turn turn : outstanding) {
            stopped |= cancelTurn(tenantId, conversationId, turn.id());
        }
        return stopped;
    }

    /** Stops one turn, running or waiting. */
    public boolean cancelTurn(String tenantId, String conversationId, String turnId) {
        InFlight flight = inFlight.get(turnId);
        if (flight == null || isDone(flight)) {
            return false;
        }
        flight.cancelled = true;
        Thread worker = flight.worker;
        if (worker != null) {
            worker.interrupt();
            // The question the run may be blocked on is abandoned by the interrupt itself:
            // CompletableFuture.get is interruptible, the approver denies, and the run's own
            // finally clears the rest.
            return true;
        }
        // A turn still waiting its place has no thread to interrupt. Cancelling the future
        // stops it from ever starting; if it has already been picked up, `run` sees the flag
        // at its first line and ends it without spending anything.
        Future<?> queued = flight.work;
        if (queued != null && queued.cancel(false)) {
            endTurn(tenantId, conversationId, turnId, Turn.State.CANCELLED, "",
                    "You stopped this before it started.", TokenUsage.ZERO);
        }
        return true;
    }

    /** Whether any turn of this conversation is running or waiting. */
    public boolean isWorking(String conversationId) {
        return inFlight.values().stream()
                .anyMatch(flight -> conversationId.equals(flight.conversationId)
                        && !isDone(flight));
    }

    private static boolean isDone(InFlight flight) {
        if (flight.unstarted()) {
            return false;
        }
        Future<?> work = flight.work;
        return work != null && work.isDone();
    }

    // --- deciding -------------------------------------------------------------------

    /** Every question this tenant currently owes an answer to. */
    public List<PendingDecision> pending(String tenantId) {
        return pending.values().stream()
                .filter(decision -> decision.tenantId().equals(tenantId))
                .sorted(java.util.Comparator.comparing(PendingDecision::askedAt)
                        .thenComparing(PendingDecision::id))
                .toList();
    }

    /** One question, if it is still outstanding and belongs to {@code tenantId}. */
    public Optional<PendingDecision> pendingDecision(String tenantId, String approvalId) {
        return Optional.ofNullable(approvalId == null ? null : pending.get(approvalId))
                .filter(decision -> decision.tenantId().equals(tenantId));
    }

    /**
     * Answers a question, releasing the run that is waiting on it.
     *
     * @return whether there was a question to answer
     */
    public boolean decide(String tenantId, String approvalId, ApprovalDecision decision,
            String by) {
        return decide(tenantId, approvalId, decision, by, "", false);
    }

    /**
     * Answers a question, releasing the run that is waiting on it.
     *
     * @param standing whether this decision should apply to the whole capability rather than
     *     only to this call. A choice, not a consequence: most refusals mean "not this one",
     *     and treating every one as policy would let the first routine refusal disable a
     *     capability until somebody noticed.
     * @return whether there was a question to answer
     */
    public boolean decide(String tenantId, String approvalId, ApprovalDecision decision,
            String by, String note, boolean standing) {
        Objects.requireNonNull(decision, "decision");
        Optional<PendingDecision> found = pendingDecision(tenantId, approvalId);
        if (found.isEmpty()) {
            return false;
        }
        PendingDecision asked = found.get();
        CompletableFuture<ApprovalDecision> answer = answers.remove(approvalId);
        pending.remove(approvalId);
        boolean approved = decision.kind() != ApprovalDecision.Kind.DENY;

        Map<String, Object> data = new LinkedHashMap<>(asked.asEventData());
        data.put("decision", decision.kind().name());
        data.put("by", by == null ? "operator" : by);
        data.put("note", note == null ? "" : note);
        data.put("standing", standing);
        if (standing) {
            // Recorded BEFORE the run is released, so the very run this decision frees already
            // proceeds without stopping again on a sibling call in the same capability.
            if (asked.capability().isEmpty()) {
                data.put("standingRefused", "This decision was not filed against a capability, "
                        + "so it applies only to this call.");
            } else {
                this.standing.remember(asked.capability(), approved,
                                by == null ? "operator" : by, note)
                        .ifPresent(said -> data.put("standingRefused", said));
            }
        }
        store.addStep(tenantId, asked.conversationId(), asked.turnId(),
                Step.Kind.APPROVAL_REQUESTED, asked.tool(), data, 0, !approved);
        events.publish(asked.conversationId(), asked.turnId(),
                ChatEvent.Type.APPROVAL_DECIDED, "", "", data);
        // complete() returns false if somebody already answered, which is what makes a parked
        // run resume exactly once however many tabs are open on it.
        return answer != null && answer.complete(decision);
    }

    /**
     * An {@link Approver} that asks whoever is watching this conversation.
     *
     * <p>Blocks the agent thread, which is what {@code Approver} documents an interactive
     * harness doing. {@link #APPROVAL_PATIENCE} bounds it: a question nobody answers must
     * eventually free the worker, or one forgotten decision costs the conversation forever.
     * The timeout denies rather than approves, which is the only safe direction — this gate
     * exists because the action is hard to reverse.
     */
    private Approver approverFor(String tenantId, String conversationId, String turnId) {
        return new Approver() {
            @Override
            public ApprovalDecision review(Tool tool, ToolInvocation invocation) {
                PendingDecision asked = new PendingDecision(Ids.next("ask"), tenantId,
                        conversationId, turnId, PendingDecision.Kind.ACTION, invocation.name(),
                        invocation.arguments(), capabilityOf.apply(tool), reasonFor(tool),
                        effectFor(tool), reversibleOf(tool), "", Instant.now());
                CompletableFuture<ApprovalDecision> answer = new CompletableFuture<>();
                pending.put(asked.id(), asked);
                answers.put(asked.id(), answer);
                events.publish(conversationId, turnId, ChatEvent.Type.APPROVAL_REQUESTED, "",
                        "", asked.asEventData());
                try {
                    return answer.get(APPROVAL_PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException nobodyAnswered) {
                    return ApprovalDecision.deny("Nobody answered this in "
                            + APPROVAL_PATIENCE.toHours() + " hours, so it was not done.");
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return ApprovalDecision.deny("This was stopped before anybody decided.");
                } catch (java.util.concurrent.ExecutionException impossible) {
                    return ApprovalDecision.deny("The decision could not be read.");
                } finally {
                    pending.remove(asked.id());
                    answers.remove(asked.id());
                }
            }
        };
    }

    /**
     * Asks the person watching a question, and waits for their answer.
     *
     * <p>The other half of {@link PendingDecision.Kind}. A gate stopping a call is the console
     * asking whether something may happen; this is the agent asking something it cannot work
     * out — which user, which of two tickets, whether a name is the same person. The Workbench example's
     * workbench has exactly this and calls the answers durable customer knowledge.
     *
     * <p>Built on the same pending-and-block machinery, deliberately. A second mechanism for
     * "a person owes this run something" would be a second thing to cancel, a second thing to
     * time out, and a second thing for a console to draw.
     *
     * @return the person's answer, or empty if nobody answered
     */
    public Optional<String> ask(String tenantId, String conversationId, String turnId,
            String question, java.time.Duration patience) {
        PendingDecision asked = new PendingDecision(Ids.next("ask"), tenantId, conversationId,
                turnId, PendingDecision.Kind.QUESTION, "", Map.of(), "", "", "", true,
                question == null ? "" : question, Instant.now());
        CompletableFuture<ApprovalDecision> answer = new CompletableFuture<>();
        pending.put(asked.id(), asked);
        answers.put(asked.id(), answer);
        events.publish(conversationId, turnId, ChatEvent.Type.APPROVAL_REQUESTED, "", "",
                asked.asEventData());
        try {
            ApprovalDecision decided = answer.get(patience.toMillis(), TimeUnit.MILLISECONDS);
            // The answer rides in the decision's ARGUMENTS, under "answer".
            //
            // It rode in `reason` first, as `deny(text)`, and that was a real confusion rather
            // than a shortcut: DENY already means "the person said no", so a written answer and
            // a refusal to answer were the same value with different contents, and this method
            // read every answer as a decline. Approving with arguments says what happened —
            // somebody supplied something — and leaves DENY meaning the one thing it means.
            if (decided.kind() == ApprovalDecision.Kind.DENY) {
                return Optional.empty();
            }
            Object written = decided.arguments().get("answer");
            String text = written == null ? "" : String.valueOf(written).strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (java.util.concurrent.TimeoutException | InterruptedException
                | java.util.concurrent.ExecutionException nobodyAnswered) {
            if (nobodyAnswered instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        } finally {
            pending.remove(asked.id());
            answers.remove(asked.id());
        }
    }

    private static String reasonFor(Tool tool) {
        return "This call needs a person's decision because " + tool.name()
                + " is gated in this deployment.";
    }

    private static String effectFor(Tool tool) {
        return Cut.to(tool.description(), 500);
    }

    private static boolean reversibleOf(Tool tool) {
        return tool.sideEffects() == dev.agentkit.core.tool.SideEffects.NONE
                || tool.sideEffects() == dev.agentkit.core.tool.SideEffects.IDEMPOTENT;
    }

    private void forgetPendingOf(String turnId) {
        pending.values().removeIf(decision -> {
            if (decision.turnId().equals(turnId)) {
                CompletableFuture<ApprovalDecision> answer = answers.remove(decision.id());
                if (answer != null) {
                    answer.complete(ApprovalDecision.deny(
                            "The turn this belonged to ended before anybody decided."));
                }
                return true;
            }
            return false;
        });
    }

    // --- plumbing -------------------------------------------------------------------

    private ExecutorService workerFor(String conversationId) {
        return workers.computeIfAbsent(conversationId, key -> Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "chat-" + key);
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    @Override
    public void close() {
        workers.values().forEach(ExecutorService::shutdownNow);
        workers.clear();
        answers.values().forEach(answer -> answer.complete(
                ApprovalDecision.deny("The console is shutting down.")));
        answers.clear();
        pending.clear();
    }
}
