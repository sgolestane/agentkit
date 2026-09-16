package dev.agentkit.core.agent;

import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.util.Observations;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.context.ContextStrategies;
import dev.agentkit.core.context.ContextStrategy;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Conversation;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.BudgetExceededException;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core agentic loop: goal in, {@link AgentResult} out.
 *
 * <p>Each step calls the model with the running conversation and the registry's
 * advertised tool specs. If the model requests tools, they are executed and their
 * results are fed back; otherwise the run finishes. The loop is bounded by
 * {@link AgentConfig#maxSteps()} and never lets a single tool failure abort the
 * run — a thrown tool {@code RuntimeException} becomes an error {@link ToolResult}
 * the model can react to.
 *
 * <p>An {@link Error} out of a tool body that was <em>entered</em> is the one throw that
 * does not: the worker's own invariant has broken rather than the call having failed, so
 * the run ends with {@link StopReason#ERROR}, keeping its steps, its usage, the model's
 * last text and the throwable, and calling {@code onFinish} on the way out (#241). It ends
 * by reporting, not by throwing. A gate that throws an {@code Error} is untouched and still
 * propagates (#103).
 *
 * <p>Each turn's message list is built by a {@link ContextStrategy}, which defaults to
 * {@link ContextStrategies#DEFAULT} rather than to identity (#151).
 *
 * <p>This class is provider-agnostic: it depends only on {@link LlmClient} and
 * {@link ToolRegistry}. The same loop runs in-process here and, in a later phase,
 * inside a Temporal workflow.
 *
 * <h2>The loop does no more work on an interrupted thread</h2>
 *
 * <p>{@link #run} executes on the <em>caller's</em> thread, and it calls third-party code
 * — a tool, a gate, an observer — that can leave the interrupt flag set. Measured before
 * #183, one tool that increments a counter, sets the flag and throws:
 *
 * <pre>
 * ran=1 flagAtLlmCall=[false, true] flagAtEnd=true stopReason=COMPLETED steps=2
 * </pre>
 *
 * <p>The tool ran once — nothing re-delivers in process, so #136's duplicate side effect
 * does not happen here — but the flag reached the <em>next model call</em> and then the
 * caller. With {@code FakeLlmClient} that is invisible, which is why the run above reports
 * COMPLETED. With a real client it is not: measured against a loopback server that answers
 * immediately, {@code HttpClient.send} on a thread carrying the flag threw
 * {@code InterruptedException} three times out of three, before any I/O. So the turn after
 * a badly-behaved tool fails for a reason that has nothing to do with the model or the
 * request, and {@code JdkHttpTransport} restores the flag on its way out, so it escapes
 * anyway.
 *
 * <p>So {@link StopReason#CANCELLED} rather than a repeat of #136's one-liner. That fix
 * <em>clears</em> the flag before an activity returns, which is unambiguously right there:
 * the activity thread belongs to Temporal's pool, the tool has no business touching it, and
 * carrying the flag out costs a duplicate execution. Here the thread belongs to the caller,
 * where an interrupt may be the caller saying stop — so clearing it would swallow a
 * cancellation, which is the risk #136 raised, did not have, and this loop does.
 *
 * <p>Two other candidates were weighed. <strong>Snapshotting around the tool dispatch</strong>
 * — flag clear before {@code execute}, set after, therefore the tool's — decides by timing,
 * and gets a caller interrupt that lands <em>during</em> the call wrong in the direction that
 * discards a cancellation. The window is not narrow either: a tool call is where a run spends
 * most of its wall time. <strong>Documenting it as the tool's bug</strong> leaves a loop that
 * knowingly makes a network call on a thread it has been told to stop using.
 *
 * <p>Checking needs no attribution, because "the run stopped because the thread was
 * interrupted" is true whoever set the flag — and it is the answer both readings want. A
 * caller who cancelled wanted the run to stop; a tool's litter becomes a clean early stop
 * instead of a broken HTTP call.
 *
 * <p><strong>A tool that litters ends this run and not a durable one.</strong> Decided
 * rather than discovered — the question was raised reviewing #136. It is not two answers to
 * one question. {@code AgentWorkflowImpl} reaches a tool through
 * {@code Workflow.newActivityStub}, so the tool runs on an activity-executor thread and the
 * workflow loop never shares it; there is no flag there to consult, and cancellation does
 * not arrive as one either — #136 measured that across both cancellation types, a
 * start-to-close timeout and a graceful shutdown. The flag is scoped to a thread, and the
 * two runners do not have the same one.
 */
public final class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentConfig config;
    private final AgentObserver observer;
    /**
     * What this agent is called in a trace, and the only part of {@link AgentRun} a
     * deployment sets. Never null: an agent nobody named runs as {@link AgentRun#ANONYMOUS}.
     */
    private final String name;
    private final java.util.function.BiConsumer<String, Throwable> onObservationFailure;
    private final ContextStrategy contextStrategy;
    /**
     * The policy, as two policies and the moment the run moves between them.
     *
     * <p>A plain {@code toolGate(...)} becomes a floor nothing lowers, so there is one
     * shape here rather than a gate and an optional floor and a branch at every use. The
     * flag that chooses between them is run state and lives in {@link #run}, not here — a
     * field would be shared by every run this instance serves.
     */
    private final TrustFloor trustFloor;
    private final boolean streaming;
    private final boolean contextAware;

    /** An agent with no observer and the default context strategy. */
    public Agent(LlmClient llm, ToolRegistry tools, AgentConfig config) {
        this(builder(llm, tools, config));
    }

    /** An agent with an observer and the default context strategy. */
    public Agent(LlmClient llm, ToolRegistry tools, AgentConfig config, AgentObserver observer) {
        this(builder(llm, tools, config).observer(observer));
    }

    public Agent(LlmClient llm, ToolRegistry tools, AgentConfig config, AgentObserver observer,
                 ContextStrategy contextStrategy) {
        this(builder(llm, tools, config).observer(observer).contextStrategy(contextStrategy));
    }

    private Agent(Builder b) {
        this.llm = Objects.requireNonNull(b.llm, "llm");
        this.tools = Objects.requireNonNull(b.tools, "tools");
        this.config = Objects.requireNonNull(b.config, "config");
        this.observer = Objects.requireNonNull(b.observer, "observer");
        this.name = Objects.requireNonNull(b.name, "name");
        this.onObservationFailure = Objects.requireNonNull(
                b.onObservationFailure, "onObservationFailure");
        this.contextStrategy = Objects.requireNonNull(b.contextStrategy, "contextStrategy");
        this.trustFloor = b.trustFloor != null
                ? b.trustFloor
                // A plain gate becomes a floor that does not exist, so the runner consults
                // one thing rather than a gate, a floor and a branch at every use — and a
                // caller who wired no floor is never told one engaged.
                : TrustFloor.none(Objects.requireNonNull(b.toolGate, "toolGate"));
        this.streaming = b.streaming;
        this.contextAware = b.contextAware;
    }

    public static Builder builder(LlmClient llm, ToolRegistry tools, AgentConfig config) {
        return new Builder(llm, tools, config);
    }

    /**
     * Fluent construction with optional observer, context strategy, and tool gate.
     *
     * <p>The context strategy is not merely optional but defaulted: see
     * {@link #contextStrategy(ContextStrategy)}.
     */
    public static final class Builder {
        private final LlmClient llm;
        private final ToolRegistry tools;
        private final AgentConfig config;
        private AgentObserver observer = AgentObserver.NONE;
        private String name = AgentRun.ANONYMOUS;
        private java.util.function.BiConsumer<String, Throwable> onObservationFailure =
                Observations.LOGGING;
        private ContextStrategy contextStrategy = ContextStrategies.DEFAULT;
        private ToolGate toolGate = ToolGate.ALLOW_ALL;
        private boolean gateSet;
        private TrustFloor trustFloor;
        private boolean streaming;
        private boolean contextAware;

        private Builder(LlmClient llm, ToolRegistry tools, AgentConfig config) {
            this.llm = llm;
            this.tools = tools;
            this.config = config;
        }

        public Builder observer(AgentObserver observer) {
            this.observer = observer;
            return this;
        }

        /**
         * What this agent is called in the {@link AgentRun} every observer callback carries
         * (#311).
         *
         * <p>For the top-level agent, which nothing else can name. A subagent is named by
         * {@link dev.agentkit.core.supervisor.Subagent}, which runs its child under the
         * roster name it was declared with — so the case #311 is about does not depend on
         * anybody remembering this.
         *
         * <p>Leaving it unset is not an unattributed trace: the run still has its own
         * {@link AgentRun#id()} and its rows still separate. What is lost is only the part a
         * reviewer reads, which is why the default is the legible {@link AgentRun#ANONYMOUS}
         * rather than a blank.
         */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * What to do when an observer callback throws (#166).
         *
         * <p>Nothing an observer throws stops a run — that was the defect, and it is closed
         * whether or not this is set. What this decides is who finds out. The default logs,
         * which is the only honest thing a framework can do on its own: it cannot know that
         * a missing {@code onTextDelta} does not matter and a missing {@code onToolResult}
         * does.
         *
         * <p>A deployment whose audit trail is a control should set this, because it is the
         * one party that can repair the gap. The handler is given the callback name and the
         * throwable, so an {@code AuditObserver} can write the row it failed to write,
         * naming the call, rather than leaving an operator to correlate a log line against a
         * run that reported success.
         *
         * <p>An earlier draft of this fix put a failure <em>count</em> on
         * {@code AgentResult} instead. Two measurements retired it. {@code onFinish} — where
         * an audit observer writes its closing record — fails after the result exists, so
         * the count could not answer the question it was added for. And a streaming run with
         * 800 deltas and a throwing observer reported 800, next to which one missing audit
         * row also counted 1. The framework cannot weigh those; the deployment can.
         *
         * <p>This handler is itself guarded: if it throws, that is logged and absorbed. A
         * handler able to abort a run would put the defect back one level up.
         */
        public Builder onObservationFailure(
                java.util.function.BiConsumer<String, Throwable> handler) {
            this.onObservationFailure = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * How each turn's message list is built from the running history.
         *
         * <p>Defaults to {@link ContextStrategies#DEFAULT}, which bounds how much of a
         * transcript is failure text somebody else wrote and does nothing at all below that
         * budget — see that constant for the measurement and for why a control this quiet
         * is defaulted on. Pass {@link ContextStrategies#identity()} for the history through
         * exactly as it stands.
         *
         * <p>Setting this replaces the default rather than composing with it. A caller who
         * wants their own editing <em>and</em> the failure-text bound composes the two —
         * {@code ContextEditor.andThen} for two editors, {@code ContextStrategies.of} for an
         * editor and a compactor — because which one runs first is a decision worth making
         * rather than one this method should make on a caller's behalf.
         */
        public Builder contextStrategy(ContextStrategy contextStrategy) {
            this.contextStrategy = contextStrategy;
            return this;
        }

        public Builder toolGate(ToolGate toolGate) {
            this.toolGate = Objects.requireNonNull(toolGate, "toolGate");
            this.gateSet = true;
            return this;
        }

        /**
         * Tightens the policy for the rest of the run once it has read somebody else's
         * words (#122).
         *
         * <p>An alternative to {@link #toolGate}, not a companion: a floor holds both
         * policies. Setting both is a contradiction, and {@link #build()} refuses it rather
         * than picking one — silently dropping an authorization policy is fail-open by
         * omission, and the first version of this did it in both directions.
         *
         * <p>An earlier version of this sentence said {@code CodeExecutionTool}'s builder
         * "already refuses" the same thing. It did not, and the review that found so had a
         * working exploit: {@code .toolFloor(...).toolGate(ALLOW_ALL)} built without
         * complaint and a script read the web and then published, where the reverse order
         * refused. Both builders refuse it now.
         *
         * <p>There is no default floor — see {@link TrustFloor} for why an on-by-default
         * one would engage on nearly every run today.
         */
        public Builder trustFloor(TrustFloor trustFloor) {
            this.trustFloor = Objects.requireNonNull(trustFloor, "trustFloor");
            return this;
        }

        /**
         * When enabled, each model turn is streamed and its text deltas are delivered
         * to {@link AgentObserver#onTextDelta}. Off by default; requires a streaming
         * observer to be useful, and a client that supports streaming to be
         * incremental (others degrade to a single delta per turn).
         */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /**
         * When enabled, each turn's system prompt carries a short note telling the
         * model which step it is on and how many remain, so it can pace itself and
         * produce a final answer before hitting {@link AgentConfig#maxSteps()} rather
         * than being cut off mid-task. Off by default.
         *
         * <p>This applies to the in-process loop only; the Temporal durable loop
         * builds its request separately and does not add the note.
         */
        public Builder contextAwareness(boolean contextAware) {
            this.contextAware = contextAware;
            return this;
        }

        /**
         * @throws IllegalStateException if both {@link #toolGate} and {@link #trustFloor}
         *     were set. They are alternatives: a floor holds both policies, so honouring
         *     one means dropping the other, and dropping an authorization policy quietly is
         *     the shape {@code CodeExecutionTool}'s builder refuses by name. The first
         *     version of this resolved it by last-writer-wins in both directions, so
         *     {@code .trustFloor(f).toolGate(g)} silently demoted a two-policy control to
         *     one gate.
         */
        public Agent build() {
            if (gateSet && trustFloor != null) {
                throw new IllegalStateException(
                        "toolGate(...) and trustFloor(...) are alternatives, not a pair: a"
                                + " floor already holds the policy a gate would be. Set one.");
            }
            return new Agent(this);
        }
    }

    /**
     * The specifications of <em>all</em> tools registered with this agent — the full
     * capability catalog, not just the currently-advertised (revealed) subset. Useful
     * as planning input, where knowing everything the executor can do matters more
     * than what progressive disclosure has revealed so far.
     */
    public List<ToolSpec> toolSpecs() {
        return tools.tools().stream().map(Tool::spec).toList();
    }

    /** Runs the agent to pursue {@code goal}, under a fresh identity of its own. */
    public AgentResult run(Goal goal) {
        return run(goal, List.of(), AgentRun.of(name));
    }

    /**
     * The same, with content the first message carries besides the goal's text.
     *
     * <p>Added for images (#374): an operator's screenshot has to reach the model as an
     * image, and until this there was nowhere to put one — {@link Goal} is text, and the
     * first message was built from it and nothing else.
     *
     * <p>Additive rather than a change to {@link Goal}. A goal is what the run is FOR, and it
     * is rendered into prompts, logged, compared in evals and handed to
     * {@code AgentObserver.onStart}; making it carry half a megabyte of base64 would put an
     * image in every one of those places. What travels here travels once, in the message.
     *
     * @param alsoInTheFirstMessage appended after the goal's text, in order; may be empty
     */
    public AgentResult run(Goal goal, List<ContentBlock> alsoInTheFirstMessage) {
        return run(goal, alsoInTheFirstMessage, AgentRun.of(name));
    }

    /**
     * Runs the agent to pursue {@code goal} under an identity the caller mints (#311).
     *
     * <p>For a caller that owns this agent's place in somebody else's trace and knows it
     * before the agent does. {@link dev.agentkit.core.supervisor.Subagent} is the one in this
     * repository: a subagent's agent is built inside a {@code Supplier} that has never heard
     * of the roster, so the name a reviewer needs — the same string {@code delegate} puts in
     * its arguments — is held by the {@code Subagent} and not by the {@code Agent}. Passing
     * it here is what makes a subagent named by construction rather than by a deployment
     * remembering to call {@link Builder#name(String)} twice.
     *
     * <p>It is also the seam a sound parent link would arrive through, should one be built:
     * a caller that knows both ends is the only party that does. See {@link AgentRun} for
     * why this change deliberately ships no parent (#317).
     *
     * <p><strong>The caller owns uniqueness here.</strong> {@link AgentRun#of(String)} mints
     * a fresh identity per call and that is what to hand over; reusing one across two runs
     * merges them in every trace that reads it, which is the defect this parameter exists to
     * close. {@link #run(Goal)} cannot get that wrong and is what to call otherwise.
     */
    public AgentResult run(Goal goal, AgentRun run) {
        return run(goal, List.of(), run);
    }

    /** The full form: a goal, anything else the first message carries, and the run. */
    public AgentResult run(Goal goal, List<ContentBlock> alsoInTheFirstMessage, AgentRun run) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(alsoInTheFirstMessage, "alsoInTheFirstMessage");
        Objects.requireNonNull(run, "run");
        observed("onStart", () -> observer.onStart(run, goal));

        Conversation conversation = new Conversation();
        // The goal's text first, then whatever else came with it. That order because the
        // text is the instruction and the rest is what the instruction is about — a model
        // handed three screenshots and then asked what to do with them has read them as
        // context for a question it had not been asked yet.
        List<ContentBlock> opening = new java.util.ArrayList<>();
        opening.add(TextBlock.of(goal.render()));
        opening.addAll(alsoInTheFirstMessage);
        conversation.append(Message.of(Role.USER, opening));

        TokenUsage totalUsage = TokenUsage.ZERO;
        int steps = 0;
        // Run state, deliberately a local. A field would be shared by every run this Agent
        // serves, which is the leak ToolActivitiesImpl documents for a stateful gate.
        boolean trustLowered = false;
        String lastText = "";

        while (steps < config.maxSteps()) {
            // Before anything this iteration would do, because everything it would do is
            // work on a thread that has been told to stop, and the first of them is a model
            // call that a real client fails outright (#183; see the class comment).
            //
            // Thread.interrupted() rather than isInterrupted(): it reports AND clears, and
            // the clearing is the point — what follows is finish(), which dispatches to
            // somebody else's onFinish, and an observer doing blocking work there would hit
            // the same broken I/O one frame later. The flag goes back on at the very end of
            // stopInterrupted, so nothing between here and the return sees it and the caller
            // still does. The mutant swapping these two looks identical and leaves the flag
            // set through onFinish; there is a test for it.
            if (Thread.interrupted()) {
                return stopInterrupted(run, lastText, steps, totalUsage);
            }
            // Engineer the context (edit/compact/...) and persist it, so the
            // transformation is applied once rather than recomputed each turn.
            List<Message> prepared = contextStrategy.prepare(conversation.messages());
            if (prepared.isEmpty()) {
                var ex = new IllegalStateException("Context strategy produced an empty message list");
                return finish(run, AgentResult.failed(ex, steps, totalUsage));
            }
            conversation.replaceAll(prepared);

            LlmResponse response;
            try {
                int turn = steps + 1;
                LlmRequest request = buildRequest(conversation, turn);
                response = streaming
                        ? llm.generate(request, delta -> observed("onTextDelta",
                                () -> observer.onTextDelta(run, turn, delta)))
                        : llm.generate(request);
            } catch (BudgetExceededException e) {
                // A deliberate stop from a BudgetLlmClient, not a failure.
                log.info("Stopping before step {}: {}", steps + 1, Quoted.of(e.getMessage()));
                return finish(run, AgentResult.stopped(StopReason.BUDGET_EXHAUSTED, lastText, steps, totalUsage));
            } catch (RuntimeException e) {
                log.warn("Model call failed on step {}", steps + 1, Quoted.failure(e));
                return finish(run, AgentResult.failed(e, steps, totalUsage));
            }

            steps++;
            totalUsage = totalUsage.plus(response.usage());
            conversation.append(response.message());
            lastText = response.message().text();
            int completed = steps;
            observed("onModelResponse",
                    () -> observer.onModelResponse(run, completed, response));
            log.debug("Step {} stopReason={} usage={}", steps, response.stopReason(), totalUsage);

            switch (response.stopReason()) {
                case REFUSAL -> {
                    return finish(run, AgentResult.stopped(StopReason.REFUSED, lastText, steps, totalUsage));
                }
                case PAUSE -> {
                    // A resumable pause (e.g. a long-running server-side tool). This
                    // in-process loop does not resume; a durable runner (Temporal) can.
                    return finish(run, AgentResult.stopped(StopReason.PAUSED, lastText, steps, totalUsage));
                }
                case MAX_TOKENS -> {
                    // This turn's output was truncated at the per-call maxTokens limit.
                    // Any tool call in this turn may be incomplete, so stop rather than
                    // execute a partial call. This is distinct from a run-wide budget stop.
                    return finish(run, AgentResult.stopped(StopReason.OUTPUT_TRUNCATED, lastText, steps, totalUsage));
                }
                default -> {
                    // END_TURN / OTHER: finish if no tools, else run them and continue.
                }
            }

            List<ProposedCall> toolUses = proposedCalls(response.message());
            if (toolUses.isEmpty()) {
                return finish(run, AgentResult.completed(lastText, steps, totalUsage));
            }
            Optional<String> malformed = ToolUseBlock.refusalForRepeatedIds(toolUses);
            if (malformed.isPresent()) {
                // Stops, rather than running none of it and carrying on. Carrying on is not
                // available: the assistant turn belongs to the provider and is echoed back
                // verbatim, duplicate ids and all, so the next request is invalid however
                // carefully the results are written — and a provider that rejects it gets
                // retried to exhaustion, so the run would die reporting something else
                // entirely. Loud and here beats obscure and three layers down (#119).
                //
                // The observer still hears about every call, as it does for an unknown
                // tool, a gate denial and a thrown tool: a trajectory showing no tool calls
                // for a turn that requested ten is the "wiring looked right and did
                // nothing" shape this framework has shipped before.
                log.warn("Model turn on step {} reused a tool-call id; no tool was run", steps);
                for (ProposedCall use : toolUses) {
                    ToolInvocation refused = new ToolInvocation(use.id(), use.name(),
                            // Empty for a call whose arguments were themselves refused: the
                            // block does not hold them, because nothing froze them (#246).
                            // The same reasoning as the unusable branch in executeTools,
                            // which says at length why an audit row must not invent any.
                            use instanceof ToolUseBlock u ? u.input() : java.util.Map.of());
                    int at = steps;
                    observed("onToolProposed",
                            () -> observer.onToolProposed(run, at, refused));
                    // Both the same: no gate ran, so there is no effective call to differ.
                    // NOT_ATTEMPTED for the same reason: nothing was decided about this
                    // call, it was simply never offered to anything (#181). REFUSED would
                    // report a policy decision that no gate was asked to make.
                    observed("onToolResult",
                            () -> observer.onToolResult(run, at, refused, refused,
                                    ToolResult.refused(malformed.get()),
                                    Disposition.NOT_ATTEMPTED));
                }
                return finish(run, AgentResult.failed(
                        new IllegalStateException(malformed.get()), steps, totalUsage));
            }

            Turn executed = executeTools(run, steps, toolUses, trustLowered);
            // Monotonic: once this run has read somebody else's words it has read them, and
            // nothing here can see what the model did with them. A flag rather than a scan
            // of the transcript, so compaction rewriting the transcript cannot raise it
            // again — see TrustFloor.
            trustLowered = executed.lowered();
            conversation.append(executed.message());
            if (executed.brokeAnInvariant().isPresent()) {
                // Reported rather than rethrown (#241). See stopBrokenInvariant.
                return stopBrokenInvariant(run,
                        executed.brokeAnInvariant().get(), lastText, steps, totalUsage);
            }
            if (!executed.awaiting().isEmpty()) {
                // A gate said a person has to decide and this loop has nobody to ask, so
                // the run ends rather than a thread waiting on an answer that arrives
                // through some other door.
                //
                // The turn is appended before the return, keeping this loop's own rule that
                // a turn's results land before it leaves the turn. That is all it buys
                // today: `conversation` is a local, never returned and never handed to the
                // observer, so no caller can resume from it. An earlier version of this
                // comment claimed a caller could, which was not true of any caller that
                // exists — resuming in process is #157's problem, and it will need the
                // transcript to be reachable before this line means anything to anybody.
                log.info("Run stopping on step {}: {} tool call(s) need a person's decision",
                        steps, executed.awaiting().size());
                return finish(run, AgentResult.awaitingApproval(
                        lastText, steps, totalUsage, executed.awaiting()));
            }
        }

        return finish(run, AgentResult.stopped(StopReason.MAX_STEPS, lastText, steps, totalUsage));
    }

    private LlmRequest buildRequest(Conversation conversation, int turn) {
        LlmRequest.Builder builder = LlmRequest.builder(config.model())
                .messages(conversation.messages())
                .tools(tools.advertisedSpecs())
                .maxTokens(config.maxTokens());
        systemPrompt(turn).ifPresent(builder::system);
        config.options().forEach(builder::option);
        return builder.build();
    }

    /**
     * The system prompt for {@code turn}, with a step-budget note appended when
     * context-awareness is enabled so the model knows how much room it has left.
     * The note lives in the (per-turn, non-persisted) system prompt, so it stays
     * current and never accumulates in the conversation history.
     */
    private Optional<String> systemPrompt(int turn) {
        // The spotlighting clause goes in unless the caller opted out. It has to come from
        // here: a knowledge passage arrives as a tool result, and a recalled lesson or a
        // verifier's feedback arrives inside the goal — in every one of those cases the
        // agent is built by a caller whose prompt the fencing collaborator never sees. A
        // fence with nothing to explain it is decoration, and shipping that was how the
        // first attempt at this failed. The cost is 1,619 characters of cached prefix,
        // measured and held there by a test rather than estimated here (#74) — as a comment
        // the figure drifted upward three times, 110 tokens to 320 to 370 to "400+, treat
        // it as a floor". Characters and not tokens on purpose: this repository ships three
        // provider adapters whose tokenisers differ, so a token count is a fact about a
        // provider rather than about the string, and measuring one needs live access this
        // build does not have. Either way the failure direction is right: a clause with
        // nothing fenced is only noise.
        Optional<String> base = config.explainsFencedContent()
                ? Optional.of(Spotlight.withInstruction(config.systemPrompt()))
                : config.systemPromptValue();
        if (!contextAware) {
            return base;
        }
        String note = budgetNote(turn, config.maxSteps());
        return base.filter(s -> !s.isBlank())
                .map(s -> s + "\n\n" + note)
                .or(() -> Optional.of(note));
    }

    private static String budgetNote(int turn, int maxSteps) {
        int remaining = Math.max(0, maxSteps - turn);
        if (remaining == 0) {
            return "[Budget] This is your last step (step " + turn + " of " + maxSteps
                    + "). Give your best final answer now rather than calling another tool.";
        }
        return "[Budget] You are on step " + turn + " of " + maxSteps + " (" + remaining
                + " step(s) remaining). If you are close to the limit, stop calling tools and"
                + " give your best final answer before you run out.";
    }

    /**
     * What one turn's tool calls produced: the message to append, and anything a gate
     * stopped pending a person's decision.
     *
     * <p>Two values rather than a signalling exception, because a park is an ordinary
     * outcome of a policy doing its job and every call in the turn still needs a result
     * block. An exception thrown out of the middle of the loop would leave the ones already
     * run without one.
     */
    private record Turn(Message message, List<PendingApproval> awaiting, boolean lowered,
                        Optional<Throwable> brokeAnInvariant) {}

    private Turn executeTools(AgentRun run, int step, List<ProposedCall> toolUses,
                              boolean lowered) {
        List<ContentBlock> results = new ArrayList<>(toolUses.size());
        List<PendingApproval> awaiting = new ArrayList<>();
        boolean loweredHere = lowered;
        // Set at most once: the first entered tool body to throw an Error ends the turn and
        // the run (#241).
        //
        // Mutually exclusive with `awaiting`, and provably so, which is why run() tests them
        // as two independent ifs rather than reconciling them. No single Attempt carries
        // both: the three that exist are `of` (neither), the gate branch (awaiting only) and
        // `brokeAnInvariant` (this only). Both locals are written only inside the
        // `awaiting.isEmpty()` branch below, so once a call has parked, every later call in
        // the turn takes the else branch and leaves this empty; and once this is set the
        // loop breaks, so no later call can park. A mutant that swaps the two ifs in run()
        // therefore survives the suite and is equivalent rather than untested — the tests
        // that hold the two premises up are the ones for the break and for the park.
        Optional<Throwable> brokeAnInvariant = Optional.empty();
        for (ProposedCall proposed : toolUses) {
            if (proposed instanceof UnusableToolUseBlock unusable) {
                // Before the `awaiting` check below, and deliberately. A sibling having
                // parked makes no difference to this call: it was refused before anything
                // in this turn was decided, so telling the model that it "stopped for a
                // human decision" would name a cause that is not its cause. Both branches
                // report NOT_ATTEMPTED either way, so an auditor sees the same state and a
                // model gets the true reason.
                results.add(refused(run, step, unusable));
                continue;
            }
            ToolUseBlock use = (ToolUseBlock) proposed;
            ToolInvocation invocation = new ToolInvocation(use.id(), use.name(), use.input());
            observed("onToolProposed",
                    () -> observer.onToolProposed(run, step, invocation));
            ToolResult result;
            // What the tool received, which is the proposal unless a gate replaced it. Held
            // separately so onToolResult can report it — see AgentObserver (#131).
            ToolInvocation effective = invocation;
            // How far this call got, held for the same reason and reported by the same
            // callback (#181). Both are facts only this loop and runTool know, and both
            // were being re-derived — badly — by whoever read the result afterwards.
            Disposition disposition;
            if (awaiting.isEmpty()) {
                Attempt attempt = runTool(run, invocation, loweredHere);
                result = attempt.result();
                effective = attempt.effective();
                disposition = attempt.disposition();
                attempt.awaiting().ifPresent(awaiting::add);
                brokeAnInvariant = attempt.brokeAnInvariant();
                // Within the turn, not after it. A turn that reads a page and then writes
                // has the page in hand before the write is gated, so the write is gated
                // under the lowered floor — waiting until the turn ends would let every
                // multi-tool turn spend its first read as a free pass.
                if (!loweredHere && trustFloor.lowersOn(result.provenance())) {
                    log.info("Trust floor lowered on step {} by '{}' ({}): the rest of this"
                            + " run is gated by the tightened policy",
                            step, Quoted.of(invocation.name()), result.provenance());
                    loweredHere = true;
                }
            } else {
                // A later call in the same turn, after one has parked. It is not run, and
                // not because it is unsafe — it may be a plain read — but because the run
                // is ending and starting side effects on the way out is the surprising
                // direction. The model is told plainly rather than being handed a result
                // that looks like the tool answered.
                result = ToolResult.refused(
                        "This run stopped for a human decision before this call was made,"
                                + " so it did not run.");
                // Never offered to a gate, so nothing decided anything about it. The result
                // above says as much to the model in prose; this says it to an auditor in a
                // value they can query (#181). It is the state the itops audit trail used
                // to record as "ERROR", indistinguishable from a tool that failed mid-write.
                disposition = Disposition.NOT_ATTEMPTED;
            }
            ToolResult produced = result;
            ToolInvocation ran = effective;
            Disposition howFar = disposition;
            observed("onToolResult",
                    () -> observer.onToolResult(run, step, invocation, ran, produced,
                            howFar));
            results.add(new ToolResultBlock(use.id(), result.content(), result.isError(),
                    result.provenance()));
            if (brokeAnInvariant.isPresent()) {
                // After the observer heard about this call and after its result block
                // exists, and both matter. THREW is the one row an incident review cannot
                // do without — the tool was entered and may have landed half a side effect
                // (#181) — and dropping the block would leave a tool_use with no
                // tool_result, which the wire format forbids. Then the turn ends: nothing
                // later in it is started, which is what the durable path does and what the
                // escaping throwable used to do by accident.
                break;
            }
        }
        return new Turn(Message.of(Role.USER, results), List.copyOf(awaiting), loweredHere,
                brokeAnInvariant);
    }

    /**
     * The result block a call earns when this framework will not carry its arguments (#246).
     *
     * <p>No gate is consulted and no tool is entered, and neither is a shortcut: by the time
     * control reaches here {@code Frozen} has already decided, at the only place that could
     * see the structure, that these arguments cannot be held. There is nothing left for a
     * policy to judge — a gate would have to be handed either an emptied call or an unfrozen
     * tree, and {@link ProposedCall} argues at length why both are fail-open. What was
     * missing was never the judgement; it was the delivery, which is this method.
     *
     * <p><strong>The run carries on.</strong> That is the whole of the change: the model is
     * handed a sentence naming what it did and what to do instead, exactly as it is for a
     * gate's denial, and it can reissue the call flattened. Measured against a gate that
     * denies everything with a reason, before and after:
     *
     * <pre>
     *  99 levels  before and after -&gt;  gate consulted, denied, run finished normally
     * 101 levels  before           -&gt;  no gate, no tool, run ended ERROR, model told nothing
     * 101 levels  after            -&gt;  no gate, no tool, run finished normally, model told why
     * </pre>
     *
     * <p><strong>The observer hears about it with empty arguments, and that reads as "not
     * carried" rather than "the model sent none".</strong> Reporting it at all is the
     * duplicate-id branch's rule — a trajectory showing no calls for a turn that requested
     * one is the "wiring looked right and did nothing" shape this framework has shipped
     * before. Reporting empty arguments is forced: the block does not hold the model's tree,
     * because holding it is the thing that was refused, so there is nothing truthful to put
     * there. It is the closest this can get to #131's rule that an audit row must not name
     * arguments the call never had, and it is stated here because it is a contract rather
     * than an accident — {@link Disposition#NOT_ATTEMPTED} and the refusal in the content
     * are the two fields that carry the fact.
     *
     * <p>{@code NOT_ATTEMPTED} for the reason its javadoc gives: no policy saw this call and
     * no decision exists to point at. {@code REFUSED} would file a governance event that
     * nobody authored.
     */
    private ToolResultBlock refused(AgentRun run, int step, UnusableToolUseBlock unusable) {
        // The operator's line, because this is a fact about the model's own output that no
        // AgentResult carries any more: after this change the run completes, so a deployment
        // whose model keeps proposing structures past the cap sees nothing at run level.
        // info and not warn: nothing is broken here, and the model is being told so and can
        // fix it next turn — which is what separates it from the two lines in runTool.
        log.info("Model turn on step {} proposed call '{}' on tool '{}' with arguments this"
                        + " framework will not carry; no gate was asked and nothing ran: {}",
                step, Quoted.of(unusable.id()), Quoted.of(unusable.name()),
                Quoted.of(unusable.refusal()));
        ToolInvocation proposal =
                new ToolInvocation(unusable.id(), unusable.name(), java.util.Map.of());
        ToolResult result = ToolResult.refused(unusable.refusal());
        observed("onToolProposed", () -> observer.onToolProposed(run, step, proposal));
        // Both invocations the same: nothing gated it, so there is no second call to name.
        observed("onToolResult", () -> observer.onToolResult(run, step, proposal, proposal,
                result, Disposition.NOT_ATTEMPTED));
        return new ToolResultBlock(unusable.id(), result.content(), result.isError(),
                result.provenance());
    }

    /**
     * What one gated tool call produced: the result the model sees, and — when the gate
     * stopped the call pending somebody's decision — what is outstanding.
     *
     * <p>Both come out of one gate evaluation. Asking the gate a second time to find out
     * why it said no would be evaluating an authorization boundary twice for a single call,
     * which is worth avoiding on its own and doubly so for a gate that pages somebody or
     * writes an audit row. {@code runTool} is private with one caller, so widening what it
     * returns costs nothing.
     */
    private record Attempt(ToolResult result, java.util.Optional<PendingApproval> awaiting,
                           ToolInvocation effective, Disposition disposition,
                           java.util.Optional<Throwable> brokeAnInvariant) {

        /**
         * An attempt whose {@code effective} call is supplied rather than recomputed.
         *
         * <p>Carried, because recomputing it means calling {@code evaluate} a second time
         * and a gate is not required to be pure. The repository states that requirement in
         * exactly one place and it is a <em>different</em> contract:
         * {@code ToolActivitiesImpl}'s class javadoc — "A durable gate must be a pure
         * function of the tool and the invocation" — which holds because the durable runner
         * re-evaluates across a replay. An earlier version of this comment attributed that
         * sentence to {@code ToolGates.parkForApproval}, which says nothing about purity;
         * the in-process contract makes no such promise, which is the reason to carry.
         */
        static Attempt of(ToolResult result, ToolInvocation effective,
                          Disposition disposition) {
            return new Attempt(result, java.util.Optional.empty(), effective, disposition,
                    java.util.Optional.empty());
        }

        /**
         * A tool body that was entered and threw an {@link Error}, so the run ends (#241).
         *
         * <p>The result and the disposition are the same ones a {@code RuntimeException}
         * from the same body produces — {@link Disposition#THREW}, and the framework's
         * frame with the thrower's message fenced inside it — because from the model's and
         * an auditor's side nothing else is different: the tool was entered and it may have
         * landed half a side effect. What differs is the last component, which the loop
         * reads to stop rather than to feed the model another turn.
         *
         * <p>{@code awaiting} is empty and cannot be otherwise: a gate that parked returned
         * two branches above, before {@code execute} was ever called.
         */
        static Attempt brokeAnInvariant(ToolResult result, ToolInvocation effective,
                                        Throwable error) {
            return new Attempt(result, java.util.Optional.empty(), effective,
                    Disposition.THREW, java.util.Optional.of(error));
        }
    }

    /**
     * {@code tool} told which run is about to execute it, or {@code tool} itself when it
     * cannot answer.
     *
     * <p>{@link Tool#boundTo} is tool-authored code called on the dispatch path, and this
     * catch is what keeps a fault in it costing what every other {@code boundTo} fault
     * costs: the parent link, and nothing else. Without it the throw landed in
     * {@code runTool}'s own catch, one statement before {@code entered} is set — so a tool
     * that would have run returned an error result to the model instead, reported
     * {@link Disposition#GATE_FAILED}, and logged that this deployment's policy code is
     * failing. Three things wrong at once, and the loudest of them sends an operator to
     * audit a gate that did nothing.
     *
     * <p>Not {@link Disposition#THREW} either, which is why this does not simply move
     * {@code entered}: that constant means a tool body was entered and may have landed half
     * a side effect (#241), and nothing was entered here. The honest report is the one a
     * missing {@code boundTo} already gets — the call ran unbound — so that is what this
     * produces, with the fault logged where a tool author will see it.
     *
     * <p>{@code null} is folded into the same answer rather than allowed to reach
     * {@code execute}. An implementation returning it means the same thing a throw does.
     */
    private Tool boundOrItself(Tool tool, AgentRun run) {
        Tool bound;
        try {
            bound = tool.boundTo(run);
        } catch (RuntimeException e) {
            // Quoted for the reason every tool name on this path is: the name is ours, the
            // failure is somebody else's and its message can carry anything.
            log.warn("Tool '{}' failed to bind to run '{}', so it ran unbound and its"
                            + " delegations will name no parent; the call itself is unaffected",
                    Quoted.of(tool.name()), Quoted.of(run.id()), Quoted.failure(e));
            return tool;
        }
        if (bound == null) {
            log.warn("Tool '{}' returned no bound form for run '{}', so it ran unbound;"
                            + " boundTo must return a tool, and returning `this` is the default",
                    Quoted.of(tool.name()), Quoted.of(run.id()));
            return tool;
        }
        return bound;
    }

    private Attempt runTool(AgentRun run, ToolInvocation invocation, boolean lowered) {
        // Gate runs after lookup: an unknown tool never executes, so there is
        // nothing to guard and the clearer "Unknown tool" message is preferable.
        Optional<Tool> tool = tools.find(invocation.name());
        if (tool.isEmpty()) {
            // The framework's own words, said so. These three used to be UNKNOWN, which
            // the design treats as somebody else's — so a run whose only failure was the
            // loop refusing a name looked, to anything reading the transcript, like a run
            // that had read a stranger's text.
            //
            // Through ToolResult.unknownTool now rather than concatenating the model's own
            // string here (#278). It was unbounded and unquoted, and two other runners
            // spelled the same sentence the same wrong way; the factory's javadoc carries
            // the bound, the escaping and the argument for the FIRST_PARTY label.
            return Attempt.of(ToolResult.unknownTool(invocation.name()),
                    invocation, Disposition.UNKNOWN_TOOL);
        }
        // Hoisted above the try so the catch below can report what the tool was actually
        // handed. It is the proposal until the gate has settled the call, which is the
        // honest answer when a gate throws; from the moment execute() is entered it is what
        // the tool received, which is the honest answer when a tool throws. Measured before
        // it was hoisted, with a narrowing gate and a tool that throws after recording its
        // argument:
        //
        //   tool received = [/tmp/harmless.txt]
        //   observer told = [/etc/shadow]
        //
        // That is #131's own defect surviving inside the change that closes it, on the path
        // where it matters most: a tool that threw part-way may already have landed a side
        // effect, and the audit row named arguments it never received. The comment that
        // stood here justified the proposal on the grounds that "a gate that threw produced
        // no effective call" — true, and only half of what this catch catches, as the
        // comment three lines below it has always said.
        ToolInvocation effective = invocation;
        // The other thing the catch below cannot work out for itself: it covers a gate that
        // threw and a tool that threw, and those are two different audit facts — one is a
        // deployment's policy code being broken, the other may have landed half a side
        // effect (#181). Set on the statement before control enters the tool, so "the tool
        // was entered" is a fact about where this assignment sits rather than an inference
        // from anything the catch can see. Deriving it instead from `effective != invocation`
        // would be wrong for every gate that allows without narrowing, which is most of them.
        boolean entered = false;
        try {
            GateResult gate = trustFloor.inForce(lowered).evaluate(tool.get(), invocation);
            if (!gate.allowed()) {
                log.info("Tool '{}' blocked by gate: {}",
                        Quoted.of(invocation.name()), Quoted.of(gate.reason()));
                // A gate is the deployment's own policy speaking.
                ToolResult refusal = ToolResult.refused(gate.reason());
                // A park is a refusal too — the call did not run — so the model gets the
                // same shape of answer either way. What differs is that somebody still owes
                // an answer, and that travels back to the loop, which ends the run rather
                // than holding a thread until one arrives.
                // effectiveFor, not the proposal: a composite may narrow the arguments and
                // then park, and what a reviewer is shown has to be the call that would
                // actually run. Measured before this: allOf(narrowTo100k, park) showed the
                // reviewer 5,000,000.
                //
                // Asked once and carried, like the allowed branch below. It was asked
                // twice, three lines above the comment saying it is asked once.
                ToolInvocation settled = gate.effectiveFor(invocation);
                // Asked of the same gate result the branch above already read, so the two
                // cannot disagree: a park is the not-allowed result that leaves something
                // outstanding, and every other not-allowed result is terminal. Reported
                // apart because "come back when a person has decided" and "no, and that is
                // the end of it" are different rows in a compliance trail (#181).
                return new Attempt(refusal,
                        gate.awaiting().map(why -> new PendingApproval(settled, why)), settled,
                        gate.awaiting().isPresent()
                                ? Disposition.PARKED : Disposition.REFUSED,
                        // Nothing was entered, so there is no invariant of the worker's to
                        // have broken. A gate that throws an Error does not come through
                        // here at all — it leaves runTool by propagation (#103, #241).
                        java.util.Optional.empty());
            }
            // A gate may approve with edited arguments (e.g. a human narrowing scope);
            // run the substitute if present, otherwise the invocation as proposed. Asked of
            // GateResult rather than unwrapped here, so this runner and the durable one
            // cannot come to differ about an authorization boundary — a replacement naming
            // a *different* tool used to run the originally resolved one with the
            // replacement's arguments, silently and in the permissive direction (#104).
            // Attributed here rather than by each tool, so a tool that declares once
            // gets it on every result and an undeclared one is visibly undeclared.
            // Asked once and carried, so the call reported and the call executed are the
            // same object rather than two evaluations that a stateful gate could differ on.
            effective = gate.effectiveFor(invocation);
            // The run reaches the tool here, after the gate has decided and before the body
            // is entered (#317). Almost every tool answers with itself; the one that does
            // not is `delegate`, which runs a whole child agent inside execute() and had no
            // way to tell that child whose behalf it was working on. See Tool.boundTo.
            //
            // Deliberately NOT the tool the gate was evaluated against, and not the tool the
            // result is attributed to. Both of those stay the registered one, so a boundTo
            // that returned something with different declarations would be *ignored* rather
            // than obeyed — a wrong answer here cannot widen what policy allowed or relabel
            // whose words a result carries. It is the same rule #104 states for a gate's
            // replacement, applied to the other direction: the authorization decision and
            // the thing it decided about must not come from two objects.
            Tool bound = boundOrItself(tool.get(), run);
            entered = true;
            return Attempt.of(bound.execute(effective).attributedTo(tool.get()), effective,
                    Disposition.RAN);
        } catch (RuntimeException e) {
            // A thrown gate/confirmation handler or tool must not abort the run. Still true
            // of a RuntimeException and deliberately not extended to an Error one catch
            // below: this branch feeds the model an error result and carries on, which is
            // the right answer when a call failed and the wrong one when the worker's own
            // invariant broke. See the Error branch and #241.
            // Two lines and two levels, not one line for two facts (#260).
            //
            // They are different facts and only one of them is an operator's problem. A
            // gate that threw is the deployment's own policy code broken at an
            // authorization boundary: every call it should have judged is going through an
            // error result instead of a decision, and nothing else says so. A tool that
            // threw is an ordinary failure the model routes around, which is the case this
            // whole branch was built to survive.
            //
            // The durable path has separated them structurally since #181 — Disposition
            // tells GATE_FAILED from THREW, and an auditor reading Temporal history can see
            // which happened. This line is the channel with no such observer and it was the
            // one folding them together, which inverts #225's rule: the line worth pinning
            // is the one that is the ONLY place a fact reaches somebody who can act on it.
            // In process it is worse than durably, because this path is designed to be
            // survivable — the model routes around the error result, the run reports
            // COMPLETED, AgentResult carries no trace, and AgentBuilder's default observer
            // is AgentObserver.NONE. A deployment whose gate throws on every call therefore
            // completes runs and looks healthy.
            //
            // #268 asked whether that fact should also be carried structurally in process,
            // and the answer is no, on two measurements rather than on taste.
            //
            // A FIELD ON AgentResult reaches two of the four runners. There is no
            // AgentResult for a call made from inside a code-execution script, and none for
            // an itops workflow step. Its durable mirror would have to ride on
            // AgentRunResult, which crosses DurableJson -- and DurableJson disables
            // FAIL_ON_UNKNOWN_PROPERTIES, so during a rolling deploy an old worker drops
            // the field silently and reports a run with no gate failures. That is a
            // positive claim of health rather than an absence of one, which is worse than
            // what it replaces; ContentBlockMixin states the hazard and #246 rejected a
            // design for exactly it.
            //
            // A NON-NONE DEFAULT OBSERVER reaches no channel this line does not already
            // reach. It would log GATE_FAILED beside this statement -- two lines for one
            // event -- while changing behaviour for every caller who never asked for an
            // observer, most sharply on the streaming path, where onTextDelta fires once
            // per chunk (Observations' javadoc has the run with 800 of them). The default
            // stays NONE.
            //
            // What #268 did change is the runner that had NO channel: ToolBridges logged
            // nothing at all here, on the runner whose own comments call the gate
            // load-bearing. It now says the same two sentences at the same two levels.
            // Four runners, one rule.
            //
            // Two statements rather than one with a distinguishing field, because the point
            // is that an operator can alert on one and not the other, and a field inside a
            // parameterised message has to be parsed back out to do that. The levels differ
            // for the same reason Synthesizers and MessagingTools split theirs: a gate that
            // is broken needs somebody to go and look, and a warn nobody can afford to read
            // is not a warn. `entered` is the same fact the Disposition below is taken from,
            // so the line and the audit field cannot come to disagree.
            if (entered) {
                log.warn("Tool '{}' was entered and threw; the model is told the call failed"
                                + " and the run continues", Quoted.of(invocation.name()),
                        Quoted.failure(e));
            } else {
                log.error("Gate for tool '{}' threw, so nothing was decided and nothing ran;"
                                + " this deployment's policy code is failing and every call"
                                + " it should judge is getting an error result instead",
                        Quoted.of(invocation.name()), Quoted.failure(e));
            }
            // Effective is whatever the call had been settled to when the throw happened:
            // the proposal if the gate threw, and what the tool was handed if the tool did.
            // See the comment on the declaration above for the measurement; see threw() for
            // why the result is fenced, attributed and not FIRST_PARTY.
            return Attempt.of(threw(invocation, tool.get(), e), effective,
                    entered ? Disposition.THREW : Disposition.GATE_FAILED);
        } catch (Error e) {
            if (!entered) {
                // A GATE that throws an Error still leaves this loop the way it always has
                // (#103). #238 scoped the durable change to a tool body that was entered for
                // exactly this reason, and #129 warns against reversing one issue's decision
                // under another's number — so the boundary is held here rather than widened
                // in passing. `entered` is set on the statement before control enters the
                // tool, so this is a fact about where that assignment sits and not an
                // inference from anything this catch can see.
                throw e;
            }
            log.warn("Run stopping: tool '{}' was entered and threw outside RuntimeException,"
                    + " so the worker's own invariant broke rather than the call failing",
                    Quoted.of(invocation.name()), Quoted.failure(e));
            return Attempt.brokeAnInvariant(threw(invocation, tool.get(), e), effective, e);
        }
    }

    /**
     * The result a thrown gate or tool hands to the model and the observer.
     *
     * <p>One spelling for all three throwing paths — a gate that threw, a tool that threw a
     * {@code RuntimeException}, a tool that threw an {@code Error} — because they differ in
     * what the <em>run</em> does next and in nothing a reader of the transcript can see.
     * What tells them apart is the {@link Disposition} each caller pairs this with:
     * {@code GATE_FAILED} for a policy that is broken, {@code THREW} for a call that may
     * have landed half a side effect (#181). Two spellings of the result is how the
     * {@code RuntimeException} branch came to be the only one that remembered
     * {@code attributedTo}, and #241's complaint is that the two ways a tool can throw were
     * reported differently for no reason anybody could state.
     *
     * <p>Fenced, and not declared {@code FIRST_PARTY} (#113). The frame is the framework's
     * and the detail is whoever threw — a tool, or a gate the deployment wrote — so the
     * result mixes, and {@code ToolResult.provenance}'s own rule is that a result which
     * mixes declares the weaker answer. The sibling branches in {@code runTool} keep
     * {@code FIRST_PARTY} and should.
     *
     * <p><strong>Corrected rather than deleted (#278).</strong> The sentence above used to
     * finish "…and should: &quot;Unknown tool&quot; and a gate's denial reason are the
     * framework's and the deployment's own words <em>with nothing else in them</em>". The
     * last clause was false of the unknown-tool branch on the day it was written: that
     * sentence has the model's own tool name in it, which is precisely what #278 is about.
     * The conclusion survives the correction and the reasoning for it is now where it
     * belongs — see {@code ToolResult.unknownTool}, which bounds and escapes the echo and
     * then argues the label on the bounded version. A gate's denial reason is untouched by
     * any of this: it is the deployment's own text end to end.
     *
     * <p>{@code attributedTo}, like the success path. {@code ToolResult.attributedTo}'s own
     * javadoc says an error must inherit — "an error message is exactly where remote text
     * turns up, and {@code OpenRouterLlmClient} putting an HTTP body into an exception is
     * the case in this repository" — and every caller has the tool in hand. {@code UNKNOWN}
     * is not the weaker answer; a {@code THIRD_PARTY} tool's is.
     *
     * @param invocation the call as proposed, whose name goes in the frame and the fence's
     *     source — the tool's registered name either way, since a gate cannot rename it
     * @param tool the resolved tool, for the declaration the result inherits
     * @param e whoever threw
     */
    private static ToolResult threw(ToolInvocation invocation, Tool tool, Throwable e) {
        return ToolResult.failed("Tool '" + invocation.name() + "' failed.",
                dev.agentkit.core.prompt.Source.of("tool", invocation.name()), e)
                .attributedTo(tool);
    }

    /**
     * Hands the result to the observer and returns it.
     *
     * <p>Guarded like every other dispatch, and this one for a reason of its own: a throw
     * here used to lose an {@code AgentResult} that had already been computed, so the caller
     * got an exception describing the observer rather than the run.
     */
    private AgentResult finish(AgentRun run, AgentResult result) {
        observed("onFinish", () -> observer.onFinish(run, result));
        return result;
    }

    /**
     * Ends the run because a tool body was entered and threw an {@link Error} (#241).
     *
     * <p><strong>The measured defect.</strong> One tool that records a side effect and then
     * throws an {@code AssertionError}, driven through each of this repository's three
     * runners:
     *
     * <pre>
     * runner              tool executions   outcome
     * in-process Agent          1           AssertionError escaped run(): no AgentResult,
     *                                       no partial steps, no onFinish
     * in-process GoapRunner     1           run ends, GoapStop.ACTION_THREW, trace kept,
     *                                       onFinish called
     * durable (since #129)      1           run ends, StopReason.ERROR, steps/usage/last
     *                                       text kept
     * </pre>
     *
     * <p>All three agree on the <em>rule</em> — an {@code Error} from a tool ends the run —
     * and this one disagreed on how. The tool had already run and may have landed a side
     * effect; the caller got no result naming what happened, no partial steps, and no
     * {@code onFinish}, so an observer holding per-run state was left with a run it would
     * never see the end of. That is the loss {@code Observations} records for #166, that
     * {@link #stopInterrupted} closes for #196, and that {@code GoapRunner}'s
     * {@code catch (RuntimeException | Error)} closes for #173, in the same words each time.
     *
     * <p><strong>{@link StopReason#ERROR}, with the throwable on the result.</strong> The
     * durable path chose {@code ERROR} and there is no reason in process it cannot: nothing
     * about the run is ordinary, an {@code AgentResult} must carry an error to use that
     * reason at all, and a caller that wants to rethrow still has the original in
     * {@code AgentResult.error()}. This is not a swallow — it is the same end by another
     * door, and the door reaches {@code onFinish}.
     *
     * <p><strong>The last text is kept</strong>, which the {@code failed(Throwable, int,
     * TokenUsage)} factory does not do, and is why
     * {@link AgentResult#failed(Throwable, String, int, TokenUsage)} exists. It is the
     * model's text from the very turn whose tool blew up — usually the sentence saying what
     * it was about to do — so it is the most useful thing on the result for anybody working
     * out what half-landed. The durable path keeps it for the same reason. The other
     * {@code ERROR} exits from {@link #run} are left alone: a model call that failed
     * produced no text this turn, and an empty context strategy failed before the call.
     *
     * <p><strong>A gate that throws an {@code Error} is untouched</strong> and still leaves
     * this method's caller by propagation — see the {@code catch (Error)} in
     * {@link #runTool}. #103 tested that decision separately and #238 scoped the durable
     * change to an entered tool body to preserve it.
     *
     * <p><strong>The rest of the turn is not started.</strong> The tool that threw gets its
     * observer callback and its result block first, so the audit trail records a
     * {@link Disposition#THREW} for the call that may have acted; the calls after it in the
     * same turn are never begun, which is what the escaping throwable did by accident and
     * what the durable path does deliberately.
     */
    private AgentResult stopBrokenInvariant(AgentRun run, Throwable error, String lastText,
                                            int steps, TokenUsage usage) {
        return finish(run, AgentResult.failed(error, lastText, steps, usage));
    }

    /**
     * Ends the run because the thread carrying it is interrupted, and hands the flag back.
     *
     * <p><strong>An ordinary stop, not a failure.</strong> Nothing failed: the steps already
     * taken are real and {@code lastText} is what the model had established, kept for the
     * same reason every other non-error stop keeps it. So this returns a result through
     * {@link #finish} rather than throwing an {@code InterruptedException} out of
     * {@link #run} — which {@code run} does not declare, and which would be a way out of the
     * loop that never reaches {@code onFinish}. An observer holding per-run state being left
     * with a run it never sees the end of is #166's defect, and adding a new escape route
     * for it would be putting that back.
     *
     * <p><strong>The flag goes back on.</strong> {@code Observations.restoreInterruptIfLost}
     * is the precedent and its javadoc has the principle: the flag belongs to the thread's
     * owner, and here the owner is whoever called {@code run}. If they cancelled, they must
     * still find out — from their own check, their next blocking call, or a {@code Future}
     * that has to report cancelled. If instead a tool littered, this hands back litter, which
     * is what the loop cannot tell apart and is strictly better than the alternative reading
     * of the same ambiguity: clearing would be silent, permanent, and wrong in the one
     * direction that loses a cancellation. It is also what already happened before #183, with
     * a broken model call on the way.
     *
     * <p><strong>Restored last, after {@code finish}</strong>, so {@code onFinish} runs on a
     * clean thread. Setting it first would break exactly the observer this method exists to
     * let run.
     *
     * <p>The other returns from {@link #run} do not consult the flag, and that is consistent
     * rather than missed: each of them is already ending the run, so there is no further work
     * to withhold, and letting the flag reach the caller untouched is what this method does
     * deliberately. What none of them can do is relabel themselves — a caller who interrupts
     * during a model call still gets whatever the client threw, typically an
     * {@code LlmException} wrapping the {@code InterruptedException}, reported as
     * {@code ERROR}. Reading it as a cancellation would mean walking somebody else's cause
     * chain and would turn an ordinary provider error on a dirty thread into a phantom
     * cancel. Left alone, and said rather than implied.
     */
    private AgentResult stopInterrupted(AgentRun run, String lastText, int steps,
                                        TokenUsage usage) {
        log.info("Run stopping after step {}: the thread is interrupted. Either the caller"
                + " cancelled, or a tool, gate or observer left the flag set; this loop"
                + " cannot tell, and both mean it should stop rather than call the model"
                + " on a thread that has been told to stop", steps);
        AgentResult result =
                finish(run, AgentResult.stopped(StopReason.CANCELLED, lastText, steps, usage));
        Thread.currentThread().interrupt();
        return result;
    }

    /**
     * One observer callback, absorbed if it throws and reported to whoever asked to know.
     *
     * <p>Every dispatch in this class goes through here. There were eight of them and none
     * was guarded, which is #166 — and two of the eight, on the reused-tool-call-id path,
     * are the ones a reader is most likely to miss when adding a guard by hand. A third,
     * {@code onTextDelta}, is inside a lambda handed to the streaming client: the one a
     * reader is most likely to guard and then not test.
     */
    private void observed(String callback, Runnable dispatch) {
        Observations.ran(callback, dispatch, onObservationFailure);
    }

    /**
     * Every tool call the turn proposed, in the order the provider sent them — including the
     * ones whose arguments this framework refused (#246).
     *
     * <p>Both shapes, and in one list, because both need exactly one result block carrying
     * their id and the wire format cares about the pairing rather than about which of the
     * two a call turned out to be. Scanning only {@link ToolUseBlock} was what let a refused
     * call vanish between the parse and the turn.
     */
    private static List<ProposedCall> proposedCalls(Message message) {
        List<ProposedCall> uses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof ProposedCall use) {
                uses.add(use);
            }
        }
        return uses;
    }
}
