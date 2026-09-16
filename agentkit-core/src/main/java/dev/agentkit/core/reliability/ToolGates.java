package dev.agentkit.core.reliability;

import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Factories and combinators for common {@link ToolGate}s.
 */
public final class ToolGates {

    private ToolGates() {
    }

    public static ToolGate allowAll() {
        return ToolGate.ALLOW_ALL;
    }

    /** Denies invocations matching {@code predicate} with {@code reason}. */
    public static ToolGate denyIf(Predicate<ToolInvocation> predicate, String reason) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(reason, "reason");
        return (tool, invocation) ->
                predicate.test(invocation) ? GateResult.deny(reason) : GateResult.allow();
    }

    /**
     * Permits only tools that declare {@link SideEffects#NONE} — a rehearsal.
     *
     * <p>Lets a run be exercised end to end without changing anything outside the process:
     * the model still reasons, still chooses tools, still reads whatever the read-only ones
     * return, and every attempt to change the world is refused with a reason it can react
     * to. Useful for a dry run before a destructive job, for replaying a failed run to see
     * what it would do differently, and for exercising an agent against production data
     * without production consequences. Reads still happen — {@code read_skill_resource}
     * opens a real file and {@code knowledge_search} may cross the network — because a
     * rehearsal in which nothing can be looked up would not rehearse anything.
     *
     * <p>Only {@link SideEffects#NONE} passes — {@link SideEffects#IDEMPOTENT} is still a
     * write, and a rehearsal that leaves rows behind is not a rehearsal.
     *
     * <p>Fail-closed: a tool that has not declared its effects is {@link SideEffects#UNKNOWN}
     * and is refused. That is the whole point — every tool written before {@code sideEffects}
     * existed would otherwise be silently eligible, and a rehearsal that sends real email is
     * worse than no rehearsal. It also means an agent whose tools come from somewhere that
     * cannot declare them, such as an MCP server, rehearses as deny-everything until you
     * wrap them with {@link dev.agentkit.core.tool.Tools#withSideEffects}.
     *
     * <p><strong>A denied call is not visible in {@code AgentResult}.</strong> The model gets
     * an error result and usually carries on to say what it would have done, so the run
     * completes normally — a rehearsal wired into CI goes green whether or not anything was
     * ever refused. Count denials from {@code AgentObserver.onToolResult} if that matters —
     * its {@code Disposition} is {@code REFUSED} for exactly this, which is new (#181). This
     * sentence stood while the callback could not tell a denial from a tool that failed, so
     * anything counting from it counted both.
     *
     * <p><strong>Side effects do not compose.</strong> A tool's declaration covers what that
     * tool does, not what it can be asked to do on someone else's behalf: a tool that can
     * reach a writer is not {@code NONE}, whatever its own body does. {@code CodeExecutionTool}
     * is the case in the framework — a model-written script reaches its tools through the
     * bridge's own gate rather than this one. That composes correctly if you give the bridge
     * {@code readOnly()} as well, and {@code CodeExecutionTool} will then let you declare
     * {@code run_code} as {@code NONE} — its builder asks the bridge gate this question and
     * refuses the declaration if the answer is no. {@code run_code} is {@code UNKNOWN}
     * unless you declare otherwise, because the builder cannot know what you intended.
     *
     * <p>A durable run is rehearsed by this too (#58), but only if the gate is handed to
     * {@code TemporalAgent.register(worker, llm, tools, gate)} — a gate cannot ride in the
     * workflow input, so a run registered without one is ungated. Note the observability
     * caveat above is worse there: a durable run has no {@code AgentObserver}, so a denial
     * is visible only in the worker log and in the activity result recorded in history.
     */
    public static ToolGate readOnly() {
        return new ToolGate() {
            @Override
            public boolean guaranteesReadOnly() {
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                // Enforced rather than asserted: the interface says the tool is never null,
                // and a user calling this directly with one would otherwise get allow or
                // NPE depending on which gate they picked. On a fail-closed policy that
                // difference should not be discoverable by accident.
                Objects.requireNonNull(tool, "tool");
                return tool.sideEffects() == SideEffects.NONE
                        ? GateResult.allow()
                        : deny(invocation.name(), tool.sideEffects());
            }
        };
    }

    private static GateResult deny(String name, SideEffects effects) {
        return GateResult.deny("This run is read-only, and '" + name
                + "' is not declared free of external side effects"
                + (effects == SideEffects.UNKNOWN ? " (it declares none either way)" : "")
                + ". Use a tool that only reads, or report what you would have done.");
    }

    /** Denies any invocation of a tool whose name is in {@code toolNames}. */
    public static ToolGate denyTools(Set<String> toolNames) {
        Objects.requireNonNull(toolNames, "toolNames");
        Set<String> names = Set.copyOf(toolNames);
        return denyIf(inv -> names.contains(inv.name()),
                "This tool is blocked by policy and cannot be used.");
    }

    /**
     * Permits the tool called {@code toolName} once in a run and refuses it afterwards.
     *
     * <p>The missing piece #310 found while wiring a {@code declare_plan} tool: a tool that
     * is only meaningful the first time it is called, with nothing in the framework to say
     * so. It <em>was</em> expressible — {@link #denyIf} over an {@code AtomicBoolean} the
     * lambda closes over — which is why this is a factory rather than a new seam. What the
     * hand-rolled version cannot do is the part that is not about convenience.
     *
     * <p><strong>It is bound to one run, and it says so.</strong> A counter belongs to the
     * run that is spending it, so this declares {@link ToolGate#boundToOneRun()} and a
     * durable worker refuses it at registration. A {@code denyIf} lambda over the same
     * {@code AtomicBoolean} answers {@code false} to that question, because nothing asks a
     * lambda anything — the exact state {@link ToolGate#boundToOneRun()}'s own javadoc calls
     * out as the reason that property is a declaration and not a detector. Wired on a
     * Temporal worker, which is built once per task queue, the hand-rolled version lets the
     * first run on the worker spend an allowance every later run is then denied.
     *
     * <p><strong>The allowance is spent by a call, not by an evaluation.</strong> The
     * {@link ToolInvocation#id()} that spent it is remembered, so re-evaluating that same
     * call — which is what resuming a parked call does — is not a second call. Without that,
     * composing this after {@link #parkForApproval} in an {@link #allOf} would deny the very
     * call the person just approved: a park does not short-circuit the chain, so this gate
     * charges for the parked call and the resume re-evaluates the composite.
     *
     * <p>What it still cannot know is whether the call it cleared went on to run. A later
     * member of an {@code allOf} may deny it, or park it and never be resumed, and the
     * allowance is spent either way. Put this last in the chain if that matters.
     *
     * <p><strong>Not the right control for every once-only tool.</strong> A refusal costs
     * the second call's <em>arguments</em>, which are sometimes the thing a reviewer most
     * wants. {@code SelfWiringAgent} deliberately does not wire this on its
     * {@code declare_plan} for that reason — its ledger already keeps the first declaration
     * and reports the second, so a model that changed its mind is visible along with what it
     * changed its mind to; refusing the call would leave the trace saying only that it tried.
     *
     * @param toolName the tool to allow once; every other tool is allowed untouched
     * @param reason   what the model is told on the second call, and it should say what to do
     *     instead — a refusal the model cannot act on is a loop
     */
    public static ToolGate callableOnce(String toolName, String reason) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(reason, "reason");
        AtomicReference<String> spentOn = new AtomicReference<>();
        return new ToolGate() {
            @Override
            public boolean boundToOneRun() {
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Objects.requireNonNull(tool, "tool");
                if (!toolName.equals(invocation.name())) {
                    return GateResult.allow();
                }
                String id = invocation.id();
                return spentOn.compareAndSet(null, id) || id.equals(spentOn.get())
                        ? GateResult.allow()
                        : GateResult.deny(reason);
            }
        };
    }

    /**
     * Requires a yes/no confirmation for invocations matching {@code gatedWhen}.
     * Non-matching invocations are allowed; matching ones are allowed only if
     * {@code handler} approves, otherwise denied. A convenience over
     * {@link #requireApproval} for the common boolean case.
     */
    public static ToolGate requireConfirmation(Predicate<ToolInvocation> gatedWhen,
                                               ConfirmationHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return requireApproval(gatedWhen, (tool, invocation) -> handler.confirm(tool, invocation)
                ? ApprovalDecision.approve()
                : ApprovalDecision.deny("This action requires confirmation and was not approved."));
    }

    /**
     * Routes invocations matching {@code gatedWhen} through {@code approver} for a
     * human-in-the-loop decision. Non-matching invocations are allowed unchanged;
     * matching ones are approved, denied with the approver's reason, or approved
     * with the approver's edited arguments (see {@link ApprovalDecision}).
     *
     * <p>An approver's edit is visible to an {@code AgentObserver}:
     * {@code onToolResult} is given the proposal and the effective call, so a record can
     * show that policy changed something rather than just what policy ran (#131). This
     * paragraph used to say the opposite — that the observer sees only the proposal, and
     * that the approver is therefore the only place to log an edit — which was true, and
     * was the gap #131 names this method for. The approver is still a reasonable place to
     * log, since it holds the reason for the edit and the observer does not.
     *
     * <p>The approver receives the {@link Tool} as well as the invocation — a person
     * deciding wants its description and its declared {@link Tool#sideEffects()}, and a
     * policy like "approve anything not declared {@code NONE}" needs it, and it is never
     * null — so an approver can read it without a branch for the case that no longer
     * exists.
     */
    public static ToolGate requireApproval(Predicate<ToolInvocation> gatedWhen, Approver approver) {
        Objects.requireNonNull(gatedWhen, "gatedWhen");
        Objects.requireNonNull(approver, "approver");
        return new ToolGate() {
            @Override
            public boolean waitsForAHuman() {
                // Asked of the approver rather than assumed of the gate. An approver may be
                // a prompt on a terminal, a pager, a queue somebody reads on Monday — and it
                // may equally be DENY_ALL, which this framework recommends for unattended
                // runs and which waits for nobody. Declaring it here made every
                // approval-shaped policy unusable on a runner that cannot host a wait,
                // including the one shape built for exactly that runner.
                return approver.waitsForAHuman();
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
            Objects.requireNonNull(tool, "tool");
            if (!gatedWhen.test(invocation)) {
                return GateResult.allow();
            }
            // The tool goes through: a person deciding whether to permit an action wants
            // its description and its declared side effects, which is most of what there
            // is to go on. allOf always passed the tool to every member; it was this
            // lambda that discarded what it was handed.
            return approver.review(tool, invocation).toGateResult(invocation);
            }
        };
    }

    /**
     * Stops invocations matching {@code gatedWhen} and says a person must decide, for the
     * reason {@code why} gives. Non-matching invocations are allowed unchanged.
     *
     * <p>This is the gate that asks nobody. It reaches a decision immediately — "not
     * without someone" — and leaves the arranging to whatever is running it: a durable
     * workflow blocks on a signal, an in-process loop stops the run and reports what is
     * pending, a sandboxed script refuses. Contrast {@link #requireApproval}, where the
     * {@link Approver} <em>is</em> the waiting, on the thread the run is using.
     *
     * <p>So {@link ToolGate#waitsForAHuman()} is {@code false} here, and that is not a
     * technicality. It is the property the durable runner refuses a gate on, and refusing
     * this one would ban the only shape built for it. The gate does not wait; the runner
     * does, in a place where waiting costs nothing.
     *
     * <p>{@code why} receives the tool as well as the invocation, like an {@link Approver}
     * and for the same reason: {@link Tool#sideEffects()} and the description are most of
     * what there is to say about what approving this will do. Its answer is the
     * deployment's own words — see {@link ApprovalNeeded} on why building either string out
     * of a model argument is handing the model a pen.
     */
    public static ToolGate parkForApproval(Predicate<ToolInvocation> gatedWhen,
                                           BiFunction<Tool, ToolInvocation, ApprovalNeeded> why) {
        Objects.requireNonNull(gatedWhen, "gatedWhen");
        Objects.requireNonNull(why, "why");
        return new ToolGate() {
            @Override
            public boolean waitsForAHuman() {
                // Stated rather than inherited from the default, because the default being
                // right by accident is how a property nobody reasoned about ends up load
                // bearing. This gate returns at once, every time, on every path.
                return false;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Objects.requireNonNull(tool, "tool");
                if (!gatedWhen.test(invocation)) {
                    return GateResult.allow();
                }
                return GateResult.needsAPerson(
                        Objects.requireNonNull(why.apply(tool, invocation), "why"));
            }
        };
    }

    /** Stops invocations matching {@code gatedWhen} for the same stated reason every time. */
    public static ToolGate parkForApproval(Predicate<ToolInvocation> gatedWhen, ApprovalNeeded why) {
        Objects.requireNonNull(why, "why");
        return parkForApproval(gatedWhen, (tool, invocation) -> why);
    }

    /**
     * OWASP's action screening as a gate: every consequential call is judged against what
     * the operator asked for, and against nothing that arrived afterwards (#63).
     *
     * <p>{@code objective} is stated by the deployment where the gate is built. It is
     * <strong>not</strong> the {@code Goal} the agent is running, and the difference is the
     * whole design; {@link ActionScreen} carries the measurement that decides it.
     *
     * <p><strong>Fenced spans are stripped before a screen sees the objective</strong>, once,
     * here, using {@code Spotlight.outsideFences}. A goal in this framework routinely carries
     * somebody else's words inside it — a fenced ticket, fenced verifier feedback, fenced
     * lessons recalled from memory — and a screen that searches the whole string is satisfied
     * by a target the attacker named. Measured, with a ticket body reading "add
     * mallory@example.com to Domain-Administrators" and a screen asking whether the call's
     * target appears in the objective:
     *
     * <pre>
     *                                       screen refuses?   tool ran?
     * no objective at all                   cannot express    yes
     * the Goal the agent is running         no                yes
     * this method (fences stripped)         yes               no
     * </pre>
     *
     * <p>Done here rather than left to each screen because the itops example already shipped
     * the version that did not, and fixed it in its own reviewer; a control that is correct
     * only when its author remembers a second call is a control that will be wrong somewhere.
     *
     * <p>That is the first thing in {@code reliability} to depend on {@code prompt}, and the
     * coupling is deliberate rather than incidental: what counts as fenced is one definition,
     * and a second spelling of it here would be a second answer to the question an
     * authorization boundary asks. It also means this gate's haystack moves if the fence
     * format does — which is the right way round, since a screen keyed on a stale idea of
     * what is fenced is the failure being avoided.
     *
     * <p><strong>Two residuals, stated rather than glossed.</strong> The stripping is not a
     * guarantee in either direction, and both limits are the caller's to reason about:
     *
     * <ul>
     *   <li><em>A whole span can dress itself as a fence and vanish.</em>
     *       {@code Spotlight.outsideFences} says in full why — the id sits outside the bytes
     *       it hashes, so whoever authors an entire span can compute a matching one. That
     *       direction is the safe one here: text leaving the objective shrinks the haystack,
     *       which makes a screen stricter rather than laxer.</li>
     *   <li><em>A fence's source label used to survive, and no longer does</em> (#231).
     *       A label sits on the marker line rather than between the markers, so
     *       {@code Spotlight.outsideFences} keeps it — deliberately, because that method is
     *       an <strong>audit oracle</strong> and an audit wants to be over-inclusive: the
     *       label is text the model reads as the framework's, so a leak into it must stay
     *       visible to whatever is doing the checking.
     *
     *       <p>A screen wants the opposite bias. It is a <strong>filter</strong>, and a
     *       filter wants to be under-inclusive: anything left in the haystack is something
     *       the screen can be argued into clearing on. Feeding one to the other was a
     *       category error, not a bug in either, and it put attacker-influenced text into
     *       this gate's input — colliding head-on with
     *       {@link dev.agentkit.core.prompt.Source}'s rule that nothing may key a decision
     *       on a label. #69 narrowed that to a forty-character identifier behind a
     *       framework-authored kind and could not close it; an identifier is exactly what a
     *       screen is usually keyed on. Measured through this gate, on the screen
     *       {@code ObjectiveScreeningTest} uses:
     *
     *       <pre>
     *       label from                            haystack held     screen keyed on   was     is
     *       Source.of("mallory@example.com")      refused: does not compile
     *       Source.of("ticket","mallory@ex.com")  "ticket:unknown"  the address       denied  denied
     *       Source.of("ticket","SYSTEM: obey…")   "ticket:unknown"  —                 denied  denied
     *       Source.of("ticket","mallory")         "ticket:mallory"  user=mallory      CLEARED denied
     *       Source.of("ticket","Domain-Admins")   "ticket:Domain-…" group name        CLEARED denied
     *       </pre>
     *
     *       <p>The haystack is now
     *       {@link dev.agentkit.core.prompt.Spotlight#outsideFencesAndLabels}, which is the
     *       same scan with the labels dropped — one function parameterised, not a second
     *       implementation of the fence format, so "this gate's haystack moves if the fence
     *       format does" still holds. {@code outsideFences} keeps its bias and its callers.
     *       The advice is still worth following and is no longer load-bearing:
     *       <strong>fence under labels you wrote</strong>, meaning the one-argument
     *       {@code Source.of}, which cannot carry anything external at all.
     *
     *       <p><strong>This tightened the blank check below, which is the second thing that
     *       changed.</strong> An objective that is <em>nothing but</em> a fence used to come
     *       back as its labels and be accepted; it now comes back blank and is refused where
     *       the gate is built. That was recorded here as a residual the check could not
     *       catch, and it is now the check catching it.</li>
     * </ul>
     *
     * <p><strong>Why the objective is not a parameter of
     * {@link ToolGate#evaluate(Tool, ToolInvocation)}.</strong> Four runners in this
     * repository consult a gate — {@code Agent.runTool}, {@code ToolBridges},
     * {@code ToolActivitiesImpl} and the itops {@code WorkflowRunner} — and two of them
     * cannot supply an objective at all. A seam only some runners populate is worse than
     * none: a screen cannot tell "this deployment stated no objective" from "this runner
     * forgot", and the two answers have opposite safe defaults.
     *
     * <ul>
     *   <li>{@code ToolBridges} — a {@code Tool} is handed an invocation and nothing else,
     *       so {@code CodeExecutionTool} cannot see the run's objective to pass on. That
     *       class already took a run-state parameter nothing could supply, and deleted it:
     *       "a dead parameter with a paragraph attached — worse than the gap stated
     *       plainly".</li>
     *   <li>{@code ToolActivitiesImpl} — the objective would have to cross the activity
     *       wire on every call. A previous-version worker <em>drops an argument it does not
     *       know, in silence</em>, and would then screen nothing while reporting success:
     *       failing open at an authorization boundary, which is the hazard
     *       {@code ToolActivities} documents at length. Measured cost of carrying it
     *       anyway, through the durable data converter: 1,876 ns to serialise a 60-character
     *       goal and 4,103 ns a 1.2 KB one, paid per step and again on every replay, plus a
     *       1,260-byte copy per step in history for a value the run already carries twice
     *       there.</li>
     * </ul>
     *
     * <p>A {@code default} three-argument overload was the shape the issue proposed and is
     * refused for the reason #57 made {@code evaluate} abstract: two callers in this
     * repository took a default that dropped what they were holding, both turned a
     * read-only policy into deny-everything, and both failed closed so they looked like the
     * policy working. Nothing here can be dropped, because the objective is not passed.
     *
     * <p><strong>The gate this returns is bound to one run</strong> and says so through
     * {@link ToolGate#boundToOneRun()}, so a durable worker refuses it at registration
     * rather than screening every run on the worker against one run's objective. That is
     * measured on {@code ToolActivitiesImpl}, whose javadoc carries the figures. Build it
     * inside the {@code Supplier<Agent>} the wrapping seams take — {@code SelfVerifyingAgent},
     * {@code ReflectiveAgent} and {@code Subagent} all mint a fresh agent per attempt, so a
     * gate built there is per-run by construction, and it screens against the operator's
     * objective rather than the rewritten goal those wrappers hand the agent.
     *
     * <p><strong>In process there is no check, and that is not an oversight.</strong> An
     * {@code Agent} reused across two different goals with one of these gates screens the
     * second goal against the first's objective — the same leak the durable runner is
     * refused for. It is not detectable there: "one run" is not a thing an {@code Agent} can
     * observe, because a legitimate wrapper reruns the <em>same</em> run with a rewritten
     * goal. {@code SelfVerifyingAgent} does exactly that between attempts, and its
     * single-{@code Agent} constructor reuses one gate across them correctly. So a check on
     * "ran twice" would refuse a correct wiring and a check on "the goal changed" would
     * refuse the same one. The durable case is enforceable only because <em>one worker
     * serves many runs</em> is structural rather than a matter of how the caller uses it.
     * Build the gate where the run is, which is what the {@code Supplier<Agent>} seams
     * already make natural.
     *
     * <p>Denies rather than parks, because a screen is a deterministic control and its
     * answer is an answer. Compose with {@link #parkForApproval} if the deployment wants a
     * person to see what the screen refused.
     *
     * @param objective what the operator asked for. Fenced spans are removed; what is left
     *                  must not be blank
     * @param screen    the judgement, which sees the objective, the tool and the call, and
     *                  nothing that came back from one
     * @throws IllegalArgumentException if nothing survives stripping the fences from
     *     {@code objective} — in practice an objective that is empty or only whitespace.
     *     Refused at the keyboard rather than coerced: a screen with nothing to judge
     *     against clears every call and reports a control, and an empty objective is what a
     *     missing configuration key looks like. The same division {@code ApprovalNeeded}
     *     draws between its factory and its constructor — this is a gate author building a
     *     policy, not a deserializer rebuilding history, so failing is free. Since #231 the
     *     labels go too, so an objective that is nothing but a fence reaches this check as
     *     blank and is refused — where it used to arrive as its labels and be accepted.
     */
    public static ToolGate screeningAgainst(String objective, ActionScreen screen) {
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(screen, "screen");
        // Stripped once, here, rather than on every call: outsideFences hashes each
        // candidate body, and this value does not change for the life of the gate.
        // outsideFencesAndLabels, not outsideFences: the label is not fenced, it may be
        // derived from untrusted data, and a screen keyed on an identifier is satisfied by
        // one (#231). The audit oracle wants the label and a filter must not have it -- see
        // the second residual above for the measurement, and Spotlight's own javadoc for
        // why the two cannot be the same function.
        String stated = Spotlight.outsideFencesAndLabels(objective).strip();
        if (stated.isEmpty()) {
            // isEmpty and not isBlank, which strip() has already made the same question --
            // said here because they are not the same question one line earlier, and a
            // reader checking whether the whitespace case is covered should not have to
            // work out which call covers it.
            throw new IllegalArgumentException(
                    "this objective states nothing the operator asked for, so screening"
                            + " against it would clear every proposal while reporting a"
                            + " control; say what the run is for");
        }
        return new ToolGate() {
            @Override
            public boolean boundToOneRun() {
                // Stated rather than inherited from the default. This gate holds one run's
                // objective, and a runner that serves more than one run must refuse it.
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Objects.requireNonNull(tool, "tool");
                Optional<String> objection = screen.objection(stated, tool, invocation);
                // A screen that returns null is a gate-author mistake and must not read as
                // "allowed". Optional.map would do that: null.map is an NPE, but the whole
                // expression is one a reader can mistake for a fall-through, so the throw is
                // named. The runners turn it into a tool error, as they do for any gate that
                // throws -- loud and closed.
                return Objects.requireNonNull(objection, "objection")
                        .map(GateResult::deny)
                        .orElseGet(GateResult::allow);
            }
        };
    }

    /**
     * Combines gates: the invocation is allowed only if <em>every</em> gate allows
     * it. With no gates the result allows everything (fail-open) — an empty policy
     * imposes no restriction, matching the identity of "allow unless denied".
     *
     * <p>Gates run in order, and a gate that approves with edited arguments (see
     * {@link #requireApproval}) is honored: each later gate evaluates the edited
     * invocation, and the combined result carries the final edit. The first denial
     * short-circuits and is returned as-is.
     *
     * <p>The same {@code tool} goes to every member, which is sound only because a
     * replacement may not rename — see {@link GateResult#effectiveFor}. A member that tries
     * to is refused here rather than at the runner, so the error names the gate that did it
     * while the chain is still in view.
     *
     * <p><strong>A denial short-circuits; a park does not.</strong> That asymmetry is the
     * whole of the composition rule and it was got wrong first. A denial is an answer, so
     * the chain stops. A park is a <em>question</em>, and a later member that would refuse
     * the call outright has already answered it — so the chain runs on, and the categorical
     * denial wins over the question.
     *
     * <p>The first version returned the park immediately, on the reasoning that no member
     * should run that would not have run before this outcome existed. That reasoning does
     * not hold: a park is a new outcome, so there is no earlier behaviour for it to
     * preserve, and a denial still short-circuits exactly as it always did. What the
     * reasoning bought was a hole. Measured with {@code allOf(parkForApproval,
     * denyWiresEntirely)} on a proposal of 5,000,000: the call parked, the approval was
     * signalled, and the tool <strong>ran</strong> — because the resume re-evaluated the
     * composite, short-circuited at the same parking member, and the denying member was
     * never reached. The paragraph that used to be here asserted the opposite in as many
     * words.
     *
     * <p>An edit made before a park travels with the question, via
     * {@link GateResult#needsAPerson(ApprovalNeeded, ToolInvocation)}. Otherwise the
     * reviewer is shown one call and another runs: measured with
     * {@code allOf(narrowAmountTo100k, parkForApproval)}, the reviewer saw 5,000,000 and
     * the tool ran with 5,000,000, losing the narrowing at both ends.
     *
     * <p><strong>An edit made <em>by</em> the parking member travels too</strong>, which
     * is the same sentence and was a different code path. The repair above kept a flag
     * beside the accumulated call and set it only where a member allowed, so a member that
     * narrowed and parked in one result — which is exactly what the two-argument
     * {@code needsAPerson} is for — had its substitution applied to what the later members
     * were judged against and dropped from what came back. Measured on a proposal of
     * 5,000,000 with a gate that narrows to 100,000 and then parks:
     *
     * <pre>
     *   the gate alone settled on      : {amount=100000}
     *   the same gate inside allOf     : {amount=5000000}
     *   the member after it was judged : {amount=100000}
     * </pre>
     *
     * <p>There is no flag now. The members run against one accumulated call and whether
     * anything was substituted is read off that call, so every arm answers the question the
     * same way or not at all. One consequence is worth stating: a member that returns
     * {@code allowWith} on the very call it was handed reports no edit, where it used to
     * report one. Nothing downstream can tell — every reader asks
     * {@link GateResult#effectiveFor}, which yields that same call either way — except
     * {@code TracingToolGate}'s {@code gate.replaced} span attribute, which now says
     * something true.
     *
     * <p>A composite asks <strong>one</strong> question at a time — the first raised. A
     * second parking member's reason is not collected, because the first one's answer may
     * change what the second decides, and asking somebody two questions about a call that
     * may not survive the first is worse than asking twice.
     */
    public static ToolGate allOf(ToolGate... gates) {
        List<ToolGate> all = List.of(gates);
        return new ToolGate() {
            @Override
            public boolean waitsForAHuman() {
                // A gate that can block does not stop blocking by being composed with
                // gates that cannot, and the composite runs every member that does not
                // short-circuit first — so the composite can block if any member can.
                return all.stream().anyMatch(ToolGate::waitsForAHuman);
            }

            @Override
            public boolean boundToOneRun() {
                // OR, like waitsForAHuman above and unlike guaranteesReadOnly below: this
                // is a hazard rather than a promise, and a member built for one run does
                // not stop being built for one run by being composed with members that
                // were not. Composing a screening gate with readOnly() must not launder it
                // onto a worker that serves every run.
                return all.stream().anyMatch(ToolGate::boundToOneRun);
            }

            @Override
            public boolean guaranteesReadOnly() {
                // Any single denial short-circuits the whole composite, so one member
                // that refuses non-NONE tools is enough to make the composite refuse them.
                // That reasoning is specific to this property: a member may return an
                // edited invocation, which widens what runs relative to what was proposed,
                // so "more gates is always stricter" does not hold in general.
                return all.stream().anyMatch(ToolGate::guaranteesReadOnly);
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                // Forwarded, not dropped: a member that decides from the tool — readOnly()
                // is the one in this class — would otherwise be handed nothing to decide
                // from the moment it was composed with anything.
                return combine(tool, invocation);
            }

            private GateResult combine(Tool tool, ToolInvocation invocation) {
                ToolInvocation settled = invocation;
                ApprovalNeeded parked = null;
                for (ToolGate gate : all) {
                    GateResult result = gate.evaluate(tool, settled);
                    switch (result) {
                        case GateResult.Denied denied -> {
                            // The one short-circuit. A denial is an answer, so the chain
                            // stops and the member's own result is what the runner sees,
                            // reason and all.
                            return denied;
                        }
                        case GateResult.NeedsAPerson needs -> {
                            // Remembered, not returned. A park is the one stop the chain
                            // runs past, because a park is not an answer — it is a
                            // question, and a later member that answers "no" outright has
                            // settled it. Returning here let an approval carry a call
                            // through a gate that was never reached: measured with
                            // allOf(park, denyWires), a proposal of 5,000,000 parked, was
                            // approved, and ran.
                            //
                            // The first version's defence of returning was that no member
                            // should run that would not have run before. That reasoning
                            // does not apply: a park is a new outcome, so there is no
                            // "before" for it to preserve. A denial still short-circuits
                            // exactly as it did.
                            //
                            // The first question only. A composite asks one at a time, and
                            // the javadoc above says why.
                            parked = parked == null ? needs.why() : parked;
                        }
                        case GateResult.Allowed allowed -> {
                            // Nothing to remember. Whether this member substituted a call
                            // is not asked here — it is asked once, below, of every arm.
                        }
                    }
                    // Asked of every arm and in one place, which is the whole repair. It
                    // used to be asked inside the two arms, and the park arm applied the
                    // substitution without recording that it had: the later members were
                    // judged against the narrowed call, and the result handed back carried
                    // the proposal. Measured, with a gate that narrows to 100,000 and then
                    // parks:
                    //
                    //   the gate alone settled on      : {amount=100000}
                    //   the same gate inside allOf     : {amount=5000000}
                    //   the member after it was judged : {amount=100000}
                    //
                    // Asked here as well as at the runner, so a member that renames is
                    // named where it did it rather than at the end of the chain. It also
                    // keeps `tool` and `settled` describing the same thing: this forwards
                    // the original tool to every member, which is only correct because a
                    // replacement cannot rename (#104). Before that rule, later members
                    // gated a tool that was not the one named in the invocation they were
                    // judging.
                    settled = result.effectiveFor(settled);
                }
                // Reference inequality, and effectiveFor's javadoc is where that is
                // promised: with no replacement it returns the invocation it was given,
                // so `settled != invocation` is exactly "some member substituted a call".
                // A boolean maintained alongside is what went wrong twice — once by being
                // returned instead of the accumulated call, once by not being set on the
                // park arm — and the value now answers for itself.
                //
                // A member returning allowWith(theCallItWasGiven) therefore reports no
                // edit where it used to report one. Nothing can tell: every reader asks
                // effectiveFor, which yields that same call either way.
                boolean edited = settled != invocation;
                if (parked != null) {
                    // The edit travels with the question. Without it the reviewer is shown
                    // the call as proposed while the narrowed one runs — or, as measured,
                    // the un-narrowed one runs and the narrowing is lost entirely.
                    return edited
                            ? GateResult.needsAPerson(parked, settled)
                            : GateResult.needsAPerson(parked);
                }
                return edited ? GateResult.allowWith(settled) : GateResult.allow();
            }
        };
    }
}
