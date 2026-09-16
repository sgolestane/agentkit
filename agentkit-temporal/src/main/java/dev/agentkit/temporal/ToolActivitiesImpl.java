package dev.agentkit.temporal;

import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Worker-side {@link ToolActivities} implementation that resolves and runs tools
 * from a core {@link ToolRegistry}.
 *
 * <p>Matching the in-process agent loop, a tool failure is turned into an error
 * {@link ToolResult} the model reacts to (the activity <em>succeeds</em> with an
 * error result) rather than a thrown exception — so a deterministic tool error
 * does not trigger pointless Temporal retries. To make a specific tool's
 * transient faults retryable instead, have that tool throw and register a
 * separate activity for it.
 *
 * <p>A failure of the activity itself — a start-to-close timeout, a lost worker, or
 * a retryable throw that exhausts its attempts — bypasses this class entirely.
 * {@code AgentWorkflowImpl} catches that too and turns it into the same kind of error
 * result, so a failing tool does not end a run either way; see its javadoc for the
 * two cases that deliberately do stop a run.
 *
 * <h2>The gate runs here (#58)</h2>
 *
 * <p>A durable run used to consult no {@link ToolGate} at all: a developer who wired
 * {@code ToolGates.readOnly()} and then moved the run to Temporal lost every gate, and
 * nothing failed and nothing logged. The policy simply stopped existing.
 *
 * <p>It runs in the activity rather than the workflow, and the decisive reason is the
 * plainest one: <strong>the workflow has no tool to gate.</strong> Its input carries
 * {@code List<ToolSpec>}, not {@code Tool}, so a gate reading {@link Tool#sideEffects()} —
 * {@code readOnly()} is the shipped one — has nothing to read there. The activity is the
 * first place in the durable path holding a resolved tool.
 *
 * <p>Two things follow rather than justify:
 *
 * <ul>
 *   <li><strong>Nothing has to be serialised.</strong> A gate is usually a lambda closing
 *       over local state; it could not cross the wire in a workflow input any more than
 *       the tools themselves could. Here it lives beside the tools it guards, and travels
 *       the way they already do — with the worker.</li>
 *   <li><strong>Workflow code could not host it anyway.</strong> Workflow code must be
 *       deterministic and free of side effects, and an arbitrary gate — one calling a
 *       policy service, a clock, a random source — is simply not legal there.</li>
 * </ul>
 *
 * <h2>What the placement costs, and what a durable gate must therefore be</h2>
 *
 * <p><strong>A durable gate must be a pure function of the tool and the invocation.</strong>
 * Two properties of the activity boundary make that a requirement rather than advice, and
 * neither has an in-process counterpart:
 *
 * <ul>
 *   <li><strong>It is re-evaluated on activity retry.</strong> The whole activity body
 *       re-runs, gate included, so one logical tool call can be gated more than once — and
 *       a retry happens precisely when the first attempt's outcome is unknown. A gate
 *       holding "this approval has already been used" then denies the second attempt, and
 *       the model is told the action was <em>refused</em> when the side effect may well
 *       have landed. The workflow's own message for a lost tool activity says the honest
 *       thing — that whether it ran is unknown — and a stateful denial replaces it with a
 *       confident falsehood. (Workflow <em>replay</em> is a different matter and is safe:
 *       the activity result is memoized, so replay reuses the recorded decision.)</li>
 *   <li><strong>One instance serves every run on the worker.</strong>
 *       {@code TemporalAgent.register} builds this once per task queue, where the
 *       in-process idiom is one gate per {@code Agent}, meaning one per run. A gate
 *       carrying per-run state therefore leaks it between unrelated runs and tenants: a
 *       gate that parks after raising an approval parks every run on that worker, and a
 *       single approval is consumable by a different run whose arguments happen to match.
 *       This is the same hazard {@code TemporalAgent} already documents for the model
 *       client, which it rejects a {@code BudgetLlmClient} for.</li>
 * </ul>
 *
 * <p><strong>And it cannot be one run's gate (#63).</strong> The second bullet is usually
 * read as being about mutable state, and it is not: the hazard is that the gate belongs to a
 * single run, whether what it holds mutates or not. A gate carrying the run's objective —
 * which is what OWASP's action screening is — is immutable and still wrong here, because the
 * objective it holds is not this call's. {@link ToolGate#boundToOneRun()} is how such a gate
 * says so, and the constructor refuses one; the measurement is in that method's javadoc.
 *
 * <p>What it would take to host action screening durably, said rather than implied: the
 * objective would have to reach this class per call, which means the activity wire. An extra
 * argument is <em>dropped in silence</em> by a previous-version worker, which would then
 * screen nothing and report success — the fail-open at an authorization boundary
 * {@code ToolActivities} spends a section on. Measured cost of carrying it even where both
 * ends agree, through {@code DurableJson}'s converter: 1,876 ns to serialise a 60-character
 * goal, 4,103 ns a 1.2 KB one, 31,105 ns an 8 KB one — per step, and again on every replay
 * — plus a copy per step written into history, for a value history already holds twice (the
 * workflow input, and the first message of every {@code LlmCallSpec}). At the itops example's
 * 24-step ceiling that is 30 KB of duplicated objective for a 1.2 KB goal and 197 KB for an
 * 8 KB one, against the 512 KB Temporal warns at. The shape that would earn it is the one
 * {@code ToolActivities} sketches: a facts record that captures unknown properties and
 * refuses the call rather than running under a policy it could not fully read.
 *
 * <h2>And a gate a tool holds is checked here too (#283)</h2>
 *
 * <p>The two checks above read the gate this class is handed. A <em>tool</em> can hold a
 * gate of its own — {@code CodeExecutionTool} requires one, for the tools a script calls —
 * and it is consulted inside {@link Tool#execute}, below this class entirely. So both
 * checks had the same blind spot, and a {@code CodeExecutionTool} built with
 * {@code toolGate(ToolGates.requireApproval(pred, humanApprover))} or
 * {@code toolGate(ToolGates.screeningAgainst(objective, screen))} reached a worker unrefused
 * and produced exactly the two outcomes the checks exist to prevent: an approver paged once
 * per activity retry, and every script on the worker screened against one run's objective.
 *
 * <p>The constructor is handed the {@link ToolRegistry}, so the fix was to ask it. A tool
 * declares the two gate hazards the way a gate does — {@link Tool#holdsGateWaitingForAHuman()}
 * and {@link Tool#holdsGateBoundToOneRun()} — and the constructor refuses at registration
 * like everything else here.
 *
 * <p>A third question joined them in #328, and it is not about a gate at all:
 * {@link Tool#boundToOneRun()} says the tool holds state built for one run. {@code AgentScope}
 * is the case — a tool over one holds that run's outstanding work and its {@code AgentRun},
 * holds no gate, and so answered {@code false} to both of the above while being exactly as
 * unshareable as either.
 *
 * <p><strong>Best-effort to exactly the same degree as the two checks above, and not
 * further.</strong> What it catches is the tool that says what it holds. What it does not:
 * a tool that reaches a person by some means it never declares; a hand-written {@code Tool}
 * decorator that wraps a declaring tool and takes the interface default; and a tool
 * registered into a mutable registry after this constructor read it, {@code tools()} being
 * a snapshot taken at registration just as the floor is.
 *
 * <p>That middle one shrank in #296 without closing. Every decorator shipped here now
 * extends {@code ForwardingTool}, which forwards both declarations, so the decorator you
 * are handed forwards them and the decorator you write forwards them if you extend it.
 * {@code implements Tool} still compiles with four methods and still answers {@code false}
 * to both, and this check still cannot tell that from a tool that genuinely holds nothing.
 * The parenthetical here used to read "(every decorator shipped here forwards it)", which
 * was true and read as reassurance about decorators in general; it was only ever a
 * statement about the three in this repository.
 *
 * <p>Also worth knowing: gate latency is charged against {@code toolStartToCloseSeconds},
 * gate faults against {@code toolMaxAttempts}, and a rolling deploy can have two workers
 * applying two policy versions within one run. Tools and the model client have always had
 * that last property; a gate is the authorization boundary, so it is worth saying out loud.</p>
 *
 * <h2>A tool's answer is bounded here, and deliberately on this runner only (#137)</h2>
 *
 * <p>An activity result is written by the worker and read back by the <em>workflow</em>, on
 * the first write and on every replay after it. Nothing bounded {@code ToolResult.content}
 * anywhere on that path, and above Jackson's 20,000,000-character ceiling the read failed —
 * failing the workflow task, which Temporal retries forever. The run stalled: no result, no
 * error, no {@code onFinish}. {@link #MAX_CONTENT_CHARS} carries the measurement and the
 * arithmetic behind the figure.
 *
 * <p><strong>{@code Agent.runTool} is left unbounded, and that is a decision rather than an
 * omission.</strong> This class's comments argue three separate times that the two runners
 * diverging is worse than either answer, so the exception needs a reason and it is a narrow
 * one: <em>the cap is a property of a wire, and the in-process runner has no wire.</em> It
 * hands the string to the model client, and an oversized result there is refused by the
 * provider — loudly, once, with an error the run reports. Nothing there stalls. Capping in
 * {@code ToolResult} to make the two agree would put a Temporal payload limit into a core
 * type that four runners share, which is the issue's own argument for the runner over the
 * type.
 *
 * <p>What the divergence costs is that the same tool hands the model less durably than in
 * process, and #190's lesson is that a bound one reader inherits from another is how a
 * decision-maker comes to approve what it was shown two thirds of. That is why the cut is
 * announced twice — {@code Cut.MARKER} to the model, a {@code warn} line to the operator —
 * rather than being left for a reader to notice. The one reader that does <em>not</em> see
 * a shorter string is a gate: gates read the invocation, not a previous result, and the
 * trust floor reads {@code provenance}, which {@link #bounded} carries across unchanged.
 *
 * <p>Not bounded, and worth naming rather than implying: a <em>parked</em> outcome carries
 * the gate's own reason and the invocation it parked, neither of which passes through
 * {@link #bounded}. A gate reason is written by the deployment rather than by a tool, and
 * the arguments already travel unbounded in the assistant turn that proposed them — so it
 * is the same hazard through a door #137 did not open, and closing half of it (the
 * {@code content} copy, but not the {@code awaiting} record beside it) would remove no
 * stall at all.
 *
 * <p>A denial is an error {@link ToolResult} and the activity <em>succeeds</em>, matching
 * both the in-process loop and what this class already does with a tool failure. A denial
 * is deterministic, so surfacing it as an activity failure would have Temporal retry it —
 * asking the same question until the attempts ran out and then failing the call, rather
 * than handing the model a refusal it can react to.
 */
public final class ToolActivitiesImpl implements ToolActivities {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ToolActivitiesImpl.class);

    private final ToolRegistry tools;

    /**
     * Both policies and what moves a run between them (#122).
     *
     * <p>A plain gate is stored as a floor whose two policies are the same one, so there is
     * one field rather than a gate and an optional floor. The bit of state that chooses —
     * has this run read somebody else's words — is <em>not</em> here: one instance of this
     * class serves every run on the worker, so a field would leak between unrelated runs
     * and tenants, which is the hazard this class already documents for a stateful gate.
     * The workflow holds it and picks the entry point.
     */
    private final TrustFloor floor;

    /**
     * Runs tools from {@code tools} with no gate — every invocation permitted.
     *
     * <p>The registry is still checked: a tool holding its own blocking or per-run gate is
     * refused here too, because "this worker was given no gate" says nothing about the gate
     * a tool holds below it.
     *
     * @throws IllegalArgumentException if a tool in {@code tools} declares that it holds a
     *     gate that may block waiting for a person or was built for one run, or that it
     *     holds state built for one run ({@link Tool#boundToOneRun()})
     */
    public ToolActivitiesImpl(ToolRegistry tools) {
        this(tools, ToolGate.ALLOW_ALL);
    }

    /**
     * Runs tools from {@code tools}, consulting {@code gate} before each one.
     *
     * @throws IllegalArgumentException if {@code gate} may block waiting for a person
     *     ({@link ToolGate#waitsForAHuman()}), or was built for one run
     *     ({@link ToolGate#boundToOneRun()}) — this instance serves every run on the task
     *     queue — or if a tool in {@code tools} declares that it holds such a gate itself
     *     ({@link Tool#holdsGateWaitingForAHuman()}, {@link Tool#holdsGateBoundToOneRun()}),
     *     or that it holds state built for one run ({@link Tool#boundToOneRun()})
     */
    public ToolActivitiesImpl(ToolRegistry tools, ToolGate gate) {
        this(tools, TrustFloor.none(Objects.requireNonNull(gate, "gate")));
    }

    /**
     * Runs tools from {@code tools}, tightening the policy for the rest of a run once it
     * has read somebody else's words (#122).
     *
     * <p>Both of the floor's gates are checked for blocking, because a run reaches both. A
     * floor whose <em>second</em> gate waits for a person would otherwise be accepted here
     * and kill the run the first time anything returned a web page.
     *
     * @throws IllegalArgumentException if either policy may block waiting for a person
     *     ({@link ToolGate#waitsForAHuman()}), or either was built for one run
     *     ({@link ToolGate#boundToOneRun()}), or if a tool in {@code tools} declares that
     *     it holds such a gate itself ({@link Tool#holdsGateWaitingForAHuman()},
     *     {@link Tool#holdsGateBoundToOneRun()}), or that it holds state built for one run
     *     ({@link Tool#boundToOneRun()})
     */
    public ToolActivitiesImpl(ToolRegistry tools, TrustFloor floor) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.floor = Objects.requireNonNull(floor, "floor");
        if (floor.waitsForAHuman()) {
            // Refused where it is wired rather than where it would time out. An approver
            // blocks a thread in process and the run waits, which is what it is for; here
            // it would burn the start-to-close timeout, fail the activity, and be retried
            // — asking the person again, and again, before failing the call anyway.
            //
            // Best-effort, in the same sense as TemporalAgent's rejection of a stateful
            // model client. A hand-written decorator that forwards evaluate and takes the
            // default for this answers false and launders a blocking gate through, which
            // is a reason the durable path should not be the only thing standing between
            // an approver and an activity.
            //
            // This comment used to name a second way past: "And a *tool* may hold a gate
            // of its own -- CodeExecutionTool requires one -- which this never sees,
            // because it is handed the run's gate and not the registry's contents." That
            // is no longer true, and #283 is why it stopped being: the registry IS in
            // hand here, so the loop below asks every tool the same questions, and a
            // tool that declares what it holds is refused like any gate. What is still
            // not caught is the tool that does not declare -- see that loop.
            throw new IllegalArgumentException(
                    "this gate can block waiting for a person, which a Temporal activity"
                            + " cannot do: it would time out and be retried, asking again"
                            + " each time. Gate a durable run on what is already known —"
                            + " tool names, declared side effects, budgets — and put human"
                            + " approval in the workflow, where waiting is free"
                            + " (ToolGates.parkForApproval). If this approver in fact"
                            + " decides without waiting, it has to say so: an Approver"
                            + " written as a lambda is presumed to wait, and"
                            + " Approver.withoutWaiting(...) is how a lambda declares that"
                            + " it does not.");
        }
        if (floor.boundToOneRun()) {
            // Refused where it is wired, like the blocking check above and for the sibling
            // reason. That one is about a policy this runner cannot execute; this one is
            // about a policy that is not this run's. One instance of this class serves every
            // run on the task queue, so a gate built for one run applies that run's
            // objective to every other run's calls.
            //
            // Measured before this check existed, with a gate holding "Onboard
            // alice@example.com" and two runs arriving at the same worker:
            //
            //   worker accepted the gate            : yes
            //   run A's own call refused            : no
            //   run B's call refused                : yes, "Not named in the objective."
            //
            // Nothing logged and nothing failed. Run B was screened against a stranger's
            // objective and told the refusal was policy, which it was — somebody else's.
            //
            // Best-effort, exactly as far as the check above is: a plain lambda closing
            // over a run's objective answers false because nothing asked it, and a
            // hand-written decorator that takes the default launders one past. What this
            // catches is the gate that says what it is, which is every gate
            // ToolGates.screeningAgainst builds.
            //
            // The message below used to end "and do action screening in process, or
            // inside the tool." The second half was advice into the hole #283 opened: a
            // screening gate handed to CodeExecutionTool.Builder.toolGate is built once
            // and registered on the worker, so it is one run's objective judging every
            // run's scripts -- the very thing this branch refuses. The loop below now
            // refuses that too, so the advice says what actually works: screen against an
            // objective the tool body is handed per call.
            throw new IllegalArgumentException(
                    "this gate says it was built for one run — carrying that run's objective"
                            + " is the case it was added for — and one instance of this"
                            + " activity serves every run on the task queue, so it would"
                            + " judge other runs' calls against a stranger's objective. Gate"
                            + " a durable run on what is true of every run — tool names,"
                            + " declared side effects, budgets — and do action screening"
                            + " per call inside the tool body, where the objective is this"
                            + " call's.");
        }
        for (Tool tool : tools.tools()) {
            // The same gate questions, asked of the registry rather than of the floor
            // (#283). A gate a *tool* holds is invisible to everything above it: it is
            // consulted inside Tool.execute, one layer below this class and below the
            // agent loop, and CodeExecutionTool *requires* one. So both checks above had
            // the same blind spot, and both failures were the ones they were built to
            // prevent — a blocking approver paged once per activity retry, and every
            // script on the worker screened against one run's objective.
            //
            // Best-effort in exactly the sense the two checks above are, and no further.
            // What this catches is the tool that says what it holds, which is every
            // CodeExecutionTool. What it does not catch: a tool that runs an approver by
            // some means of its own and never overrides the declaration; a hand-written
            // Tool decorator that wraps a declaring tool and takes the interface default
            // (the shipped decorators -- Tools.withSideEffects, Tools.withProvenance,
            // TracingTool -- all forward it, by extending ForwardingTool since #296, which
            // is also what a decorator written here should extend); and anything registered
            // into a mutable registry after this constructor read it, since tools() is a
            // snapshot taken here, exactly as the gate checks are snapshots of the floor
            // passed here.
            //
            // The growable subagent roster is the sharpest case of that last one (#313).
            // SubagentTools' delegate now answers both declarations by ORing across its
            // roster, and it renders that answer live -- so it is right for every caller
            // that asks. This caller asks once, at registration, and a subagent added to
            // the roster afterwards by a model that can grow its own roster is not caught.
            // Live rendering makes the answer correct; it cannot make a check that has
            // already run ask a second time. A deployment registering a delegating
            // supervisor on a worker should finalise the roster before it registers, which
            // is the same discipline the mutable-registry sentence above asks for.
            if (tool.holdsGateWaitingForAHuman()) {
                throw new IllegalArgumentException(
                        "the tool '" + tool.name() + "' holds a gate that can block waiting"
                                + " for a person, which a Temporal activity cannot do: it"
                                + " would time out and be retried, asking again each time."
                                + " A gate inside a tool is not the gate passed to this"
                                + " worker and is never consulted here, so it has to say so"
                                + " — see Tool.holdsGateWaitingForAHuman(). Gate the tool on"
                                + " what is already known — tool names, declared side"
                                + " effects, budgets — and put human approval in the"
                                + " workflow, where waiting is free"
                                + " (ToolGates.parkForApproval). If the approver inside this"
                                + " tool in fact decides without waiting, it has to say so:"
                                + " an Approver written as a lambda is presumed to wait, and"
                                + " Approver.withoutWaiting(...) is how a lambda declares"
                                + " that it does not.");
            }
            if (tool.holdsGateBoundToOneRun()) {
                throw new IllegalArgumentException(
                        "the tool '" + tool.name() + "' holds a gate that says it was built"
                                + " for one run — carrying that run's objective is the case"
                                + " it was added for — and one instance of this activity"
                                + " serves every run on the task queue, so every run's calls"
                                + " into that tool would be judged against a stranger's"
                                + " objective. See Tool.holdsGateBoundToOneRun(). Gate the"
                                + " tool on what is true of every run, and screen against an"
                                + " objective the tool is handed per call rather than one"
                                + " built into it.");
            }
            if (tool.boundToOneRun()) {
                // The third question, and the one the other two could not ask (#328). Those
                // are about a GATE a tool holds; this is about any state whose lifetime is
                // one run. AgentScope is the case it was added for: a tool built over one
                // closes over that run's outstanding work and its AgentRun, holds no gate at
                // all, and would therefore have registered here clean while handing every
                // run on the queue a stranger's scope.
                throw new IllegalArgumentException(
                        "the tool '" + tool.name() + "' says it holds state built for one"
                                + " run, and one instance of this activity serves every run"
                                + " on the task queue — so every run's calls into that tool"
                                + " would reach a stranger's state. See Tool.boundToOneRun()."
                                + " A scope-backed tool (AgentScope) belongs to the run that"
                                + " created it; build it per run in the workflow rather than"
                                + " registering it on the worker.");
            }
        }
    }

    /**
     * Runs one invocation, and — since #103 — runs its body <em>once</em> whatever it throws.
     *
     * <p>Measured before this, with a tool that recorded its side effect and then threw
     * {@code AssertionError}: the in-process runner executed the tool body once and the run
     * died; the durable runner executed it <strong>three</strong> times and reported
     * {@code COMPLETED}. Three real side effects where the reference implementation produces
     * one, for the same fault. The {@code catch} below was {@code RuntimeException}, so an
     * {@code Error} escaped the activity and Temporal retried what escaped.
     *
     * <p>{@code ToolActivities}' at-least-once caveat does not cover it. That warning is
     * about <em>infrastructure</em> faults — a lost worker, a start-to-close timeout — where
     * retrying can succeed. A fault the tool body produces is deterministic: retrying cannot
     * succeed, and each attempt re-runs whatever the tool did before it threw. This class's
     * javadoc already claimed the fix, saying a thrown tool is converted "so a deterministic
     * tool error does not trigger pointless Temporal retries" — which is exactly what an
     * {@code Error} triggered.
     *
     * <h4>An error result, not a failed activity, and not a carve-out</h4>
     *
     * <p>The first attempt at this made the activity <em>fail</em> non-retryably, and kept
     * {@code OutOfMemoryError} and {@code LinkageError} escaping on the theory that "a worker
     * this broken should die rather than report a tool result". The review measured all
     * three claims and none survived:
     *
     * <ul>
     *   <li><strong>Nothing dies.</strong> Temporal's activity task handler catches
     *       {@code Throwable} itself, so rethrowing from here reaches nothing that stops a
     *       worker. The carve-out's entire rationale was about a runtime this does not run
     *       on: measured with a synthetic {@code OutOfMemoryError}, the worker kept serving
     *       and the run reached {@code COMPLETED}.</li>
     *   <li><strong>It kept the retries for the most deterministic fault there is.</strong>
     *       A {@code NoClassDefFoundError} will still be missing on attempts two and three,
     *       and it was the one routed into three attempts — three side effects — while
     *       {@code OutOfMemoryError}, the one case where a retry could plausibly succeed,
     *       was also rethrown. The line was drawn backwards from the rule it cited.</li>
     *   <li><strong>Failing the activity made the workflow blame the wrong thing.</strong>
     *       {@code AgentWorkflowImpl} counts activity-level failures and ends a run after two
     *       consecutive turns of them with "the tool worker is likely unavailable or
     *       misconfigured". Measured: a tool throwing {@code AssertionError} on two turns
     *       ended an otherwise-healthy run with that verdict. The tool worker was fine. That
     *       is verbatim the failure this class's own gate comment, one branch above, calls
     *       out as worth fixing.</li>
     * </ul>
     *
     * <p>So there is one rule and no exceptions: whatever the tool body throws becomes an
     * error {@link ToolResult} and the activity succeeds, which is what the
     * {@code RuntimeException} branch has always done. The run continues, the model is told,
     * and the body ran once.
     *
     * <p>Whether an {@code Error} should also <em>end</em> a durable run — it ends an
     * in-process one — is #129. That changes workflow control flow, which has to be
     * versioned to be safe for runs already in flight.
     */
    @Override
    public ToolOutcome executeTool(ToolInvocation invocation) {
        return run(invocation, null, false);
    }

    @Override
    public ToolOutcome executeToolUnderLoweredTrust(ToolInvocation invocation) {
        return run(invocation, null, true);
    }

    /**
     * The resume path, and the only place a person's verdict is read.
     *
     * <p>Returns a plain {@link ToolResult}: a resumed call cannot park again. The gate is
     * asked afresh and will usually say a person must decide, which is exactly the question
     * this verdict answers. Parking again on the answer to the park would be a loop with a
     * person in it.
     *
     * <p>"Usually" rather than "always", and the difference is worth being honest about.
     * This class requires a durable gate to be a pure function of the tool and the
     * invocation, and nothing enforces that — the requirement is stated in the class javadoc
     * and called best-effort there. A gate that is not pure may answer differently on the
     * resume, and that is fine: whatever it says now is the policy that applies. What the
     * verdict cannot do is override it.
     */
    @Override
    public ToolOutcome resumeTool(ToolInvocation invocation, ApprovalVerdict verdict) {
        return resume(invocation, verdict, false);
    }

    @Override
    public ToolOutcome resumeToolUnderLoweredTrust(ToolInvocation invocation,
                                                   ApprovalVerdict verdict) {
        return resume(invocation, verdict, true);
    }

    private ToolOutcome resume(ToolInvocation invocation, ApprovalVerdict verdict,
                               boolean lowered) {
        Objects.requireNonNull(verdict, "verdict");
        if (!verdict.invocationId().equals(invocation.id())) {
            // The workflow builds both sides of this, so it is a self-check rather than a
            // boundary — but it is the kind of self-check worth having at an authorization
            // boundary, because the failure it catches is an approval for one action being
            // spent on another. Refused rather than ignored: proceeding without the verdict
            // would run a parked call with nobody's decision behind it.
            LOG.warn("Approval for call '{}' does not answer call '{}'; refused",
                    Quoted.of(verdict.invocationId()), Quoted.of(invocation.id()));
            // NOT_ATTEMPTED and not REFUSED: this is the framework declining to start
            // something, before any gate was consulted, so there is no decision and no
            // decider to record. Calling it a denial would put a governance event in
            // history that nobody authored (#181).
            return outcomeOf(invocation, ToolResult.refused(
                    "The approval that came back does not answer this call, so it was not"
                            + " run."), Disposition.NOT_ATTEMPTED);
        }
        // Re-gated under the policy that parked the call, which is the whole point of a
        // verdict: it answers the question that policy asked. Resuming under the ordinary
        // policy meant the tightened gate was never consulted, so the branch that applies a
        // verdict to the parked call was skipped and the model's original invocation ran —
        // a reviewer's edit discarded, and every other restriction of the tightened gate
        // waived by the act of approving.
        return run(invocation, verdict, lowered);
    }

    /**
     * One tool call, gated, with an optional decision already in hand.
     *
     * <p>Both entry points come through here, which is the point: the gate is evaluated in
     * one place, and what a verdict may and may not do is decided in one place. Two spellings
     * of "when does an approval let something run" is how the two agent loops came to
     * disagree about a replacement that renamed a tool.
     *
     * <p>The order is the load-bearing part. The gate decides first, always. A verdict is
     * consulted only in the branch where the gate said a person must decide — so a gate that
     * denies outright still denies with a signed approval in hand, and an approval can never
     * widen what policy allows. It can only answer the question policy asked.
     */
    private ToolOutcome run(ToolInvocation invocation, ApprovalVerdict verdict, boolean lowered) {
        // Every activity entry point on this class funnels through here, so this is the one
        // place the flag has to be dealt with. The rule itself lives in ActivityThread,
        // because the model activity needs the identical thing and two spellings is how one
        // of them comes to be missing (#136).
        boolean inherited = ActivityThread.arrivesInterrupted();
        try {
            // Before the tool is even resolved, which is where #185 says the check belongs:
            // a body that starts on an interrupted thread has its own blocking I/O fail for
            // a reason unrelated to its work, and the model is then told its tool failed.
            // Inside the try, so the finally still clears the flag on the way out — the
            // refusal has to be reported to the server, and reporting is the call the flag
            // breaks.
            ActivityThread.refuseToStartIfArrivedInterrupted("Tool", invocation.name(),
                    inherited);
            return gated(invocation, verdict, lowered);
        } finally {
            ActivityThread.clearBeforeReturning("Tool", invocation.name(), inherited);
        }
    }

    private ToolOutcome gated(ToolInvocation invocation, ApprovalVerdict verdict,
                              boolean lowered) {
        Optional<Tool> tool = tools.find(invocation.name());
        if (tool.isEmpty()) {
            // Attributed like the in-process loop's, so the two transcripts agree about
            // whose words these are. Nothing gated it and nothing ran, so the proposal is
            // the settled call — there was no second invocation to report.
            //
            // And spelled like it, through ToolResult.unknownTool (#278): agreeing about
            // the label while disagreeing about the bound is the shape #113 and #122 were,
            // and this runner writes to durable history, where an unbounded echo is
            // persisted rather than merely printed.
            return outcomeOf(invocation, ToolResult.unknownTool(invocation.name()),
                    Disposition.UNKNOWN_TOOL);
        }
        // What history will report this call as, for the catch blocks below (#179). It
        // starts as the proposal and is reassigned in each branch at the moment the gate
        // has settled the question and before anything is run with the answer, so the two
        // failure paths report the two different truths without either being guessed at: a
        // gate that threw settled nothing, and its catch still holds the proposal; a tool
        // that threw was handed something, and its catch holds what.
        //
        // A local rather than a return value because the catch blocks are the readers, and
        // a helper that returned it could not tell them what it was holding when it threw.
        ToolInvocation settled = invocation;
        // The second thing the catch blocks below cannot work out for themselves, kept on
        // exactly the discipline the comment above describes for `settled` (#181). It starts
        // at the truth for a throw that happens before anything has been decided — the gate
        // is the only thing that has run — and is reassigned to THREW on the statement
        // before control is handed to the tool, so "the tool was entered" is a fact about
        // where the assignment sits and not an inference from anything the catch can see.
        //
        // Assigned before the call rather than after it because only the catch blocks read
        // it: on every path that returns normally the disposition comes back from execute(),
        // which is the one place that knows whether a gate result meant "run this" or
        // "refuse it".
        //
        // Two such statements, and it was three. #181's mutation pass found
        // the marker pinned on one branch only: deleting it from the resumed-park branch, and
        // separately from the drift branch, survived every test in this module, so a tool
        // that threw on a resumed call was recorded as a broken gate. #163 then merged those
        // two branches into the one verdict branch above, for its own reason — they differed
        // by a line and one of them had the line wrong. So the count is two, and the mutant
        // that survived twice is now one mutant. The tests that found it are kept and pin
        // something narrower: the verdict branch is reached by a disjunction, and each drives
        // one disjunct.
        //
        // The residual, stated rather than glossed: between this assignment and the return,
        // a refused call spends time in code that is not the tool — execute()'s refusal
        // branch, then bounded() and TrustFloor.lowersOn() inside outcomeOf() — and a throw
        // from any of it would be reported as THREW. Reassigning to ran.disposition() the
        // moment execute() returns would close it, and is deliberately not done, because
        // none of that code can be made to throw from outside this class: the refusal branch
        // reads two record components of a GateResult and logs; bounded() is a length
        // comparison and, above the cap, Cut.to plus a log; lowersOn() is contains() on a
        // Set.copyOf the TrustFloor constructor already took. A line no test can reach is a
        // line whose deletion no test can notice, which is a worse thing to add to this
        // method than a residual with an argument attached.
        Disposition howFar = Disposition.GATE_FAILED;
        try {
            // Resolve, gate, execute — the order Agent.runTool uses, and the order matters:
            // a gate deciding from the tool needs it resolved, and nothing may run before
            // the gate has seen it.
            //
            // Inside the try, as both in-process callers do. Outside it, a gate that threw
            // escaped the activity: Temporal retried it toolMaxAttempts times — hammering
            // whatever the policy consults — and the run then died through the
            // dead-tool-worker heuristic, telling the operator the tool worker was
            // unavailable when the tool worker was fine and the gate had thrown. Agent's
            // own comment states the rule: a thrown gate must not abort the run.
            GateResult decision = floor.inForce(lowered).evaluate(tool.get(), invocation);
            if (verdict != null && verdict.kind() == ApprovalDecision.Kind.DENY) {
                // A refusal is honoured whatever the gate now says, and before the gate is
                // consulted. Every other part of this method reads the gate first, on the
                // argument that an approval must not widen what policy allows — and a
                // refusal is never a widening, so the argument does not apply to it.
                //
                // It matters because policy moves while a call sits on somebody's desk. A
                // gate that has stopped parking and started allowing would otherwise
                // discard a recorded "no" and run the tool, which is the one direction with
                // a bad outcome.
                LOG.info("Refusing parked call '{}' on tool '{}': denied by {}",
                        Quoted.of(invocation.id()), Quoted.of(invocation.name()),
                        Quoted.of(verdict.decidedBy()));
                // The call as it arrived, which on this path is the one the reviewer was
                // shown: the workflow resumes with the parked call, not the model's
                // original. Nothing ran and the gate's own verdict was discarded for a
                // person's, so there is no second invocation to name.
                // REFUSED and not PARKED: this call was parked, a person answered, and the
                // answer was no. What history should say is that it is settled and will not
                // run, which is what an auditor asks of a row that once had a decision
                // outstanding (#181).
                return outcomeOf(invocation,
                        ToolResult.refused(verdict.asDecision().reason()),
                        Disposition.REFUSED);
            }
            // ONE unwrapping of the gate's answer, for every branch below (#163).
            //
            // Asked once and carried, which is the #131 rule this method already stated at
            // its last branch: asking effectiveFor a second time to record what ran lets a
            // stateful gate answer differently, and the second answer is the one the
            // auditor reads. What was not stated is the other half — that every branch has
            // to ask it at all.
            //
            // Measured before this, with a gate that parks a publish and, on the resume,
            // has drifted to allowWith({"text":"/tmp/narrowed.txt"}), plus a plain
            // approval:
            //
            //   gate on the resume said : allowWith({"text":"/tmp/narrowed.txt"})
            //   tool was entered with   : {"text":"/tmp/parked.txt"}
            //   history recorded        : {"text":"/tmp/parked.txt"}
            //
            // The drift branch built its GateResult from the arriving call and never asked
            // `decision` what it had settled on, so a gate that narrows on the resume was
            // overruled by the stale call the reviewer had been shown — a narrowing the
            // policy in force asked for, dropped, at an authorization boundary, with
            // nothing in the log or in history saying so. That is #104's defect (a
            // narrowing gate turned into a rubber stamp) reached through the one branch
            // #179 did not have to touch, because there the record and the execution agreed
            // — they were both wrong.
            //
            // The repair is the shape and not a third spelling. The park branch and the
            // drift branch had been written separately and differed by exactly this line,
            // so they are one branch now: unwrap the gate once, apply a person's decision
            // on top of THAT, unwrap once more. A private `Settled(GateResult, Invocation)`
            // record was written and rejected — it carried the pair correctly but left
            // three call sites free to build it from the wrong base, which is the mistake
            // that was made; merging the branches removes the second site instead of
            // making it easier to get right.
            ToolInvocation gateSettled = decision.effectiveFor(invocation);
            // Reported as the settled call whether or not a tool ever runs: on a park it is
            // what a reviewer is shown and what runs on approval, which is the whole reason
            // needsAPerson carries a replacement at all (#104). Reassigned below if a
            // person edits again.
            settled = gateSettled;
            if (decision.awaiting().isPresent() && verdict == null) {
                PendingApproval pending =
                        new PendingApproval(gateSettled, decision.awaiting().orElseThrow());
                LOG.info("Tool '{}' parked by the gate for a person: {}",
                        Quoted.of(invocation.name()), Quoted.of(pending.why().reason()));
                // Not passed through bounded(): a parked outcome's content is the
                // gate's reason, and the same reason travels beside it inside
                // `awaiting`. Cutting one copy and not the other removes no stall,
                // so #137's cap is not applied here at all rather than half-applied.
                // The class javadoc says why that is a boundary and not an oversight.
                return ToolOutcome.parked(pending);
            }
            if (verdict != null && (decision.awaiting().isPresent() || decision.allowed())) {
                // The only place a verdict is read. Two ways in, and they are the same
                // arithmetic either way: the gate still parks and the verdict answers the
                // question it asked, or policy drifted while the call sat on somebody's
                // desk and the gate now simply allows. The operator's line distinguishes
                // them because drift is worth seeing; the code below must not, because the
                // one time it did, one of the two forgot to honour the gate.
                //
                // The refusal direction of that drift is handled above and its comment
                // argues the general principle. An approval still cannot widen anything:
                // the gate has already settled on a call, and what a verdict adds is at
                // most an edit of THAT call, which goes through effectiveFor exactly as a
                // gate's replacement does — so a reviewer is refused a rename for the same
                // reason a gate is.
                // One line and not a branch per way in. Two lines were written first and
                // the mutation pass is why they are one: `if (true)` over them survived the
                // whole suite, because this repository wires slf4j-simple and has no
                // log-capturing harness anywhere — a control mutant that garbled the park
                // line above, which predates this change, survives too. So a branch here is
                // a branch nothing can check. Made a value instead, which also tells the
                // operator more than either line did: the parked line never named the
                // drift, and the drift line never named the tool.
                LOG.info("Applying a {} decision by {} to call '{}' on tool '{}', which the"
                                + " gate {}", verdict.kind(), Quoted.of(verdict.decidedBy()),
                        Quoted.of(invocation.id()), Quoted.of(invocation.name()),
                        decision.awaiting().isPresent() ? "parked" : "no longer parks");
                // The mapping from approve/deny/edit to a gate result is
                // ApprovalDecision.toGateResult's and is not restated here. That method is
                // why it is public: the in-process path reaches it through
                // ToolGates.requireApproval and this one from a signal that arrived hours
                // later, and a second spelling is how two runners come to disagree about
                // what an approval means.
                //
                // Applied to gateSettled, so a plain approval runs what the gate settled on
                // — the call the reviewer saw — rather than what the model proposed.
                //
                // The second line is the load-bearing one and the first is for the reader.
                // Measured: passing `invocation` to toGateResult survives the whole suite,
                // and provably so — toGateResult reads only the id and the name, and
                // GateResult.effectiveFor refuses any replacement that changes either, so
                // gateSettled and invocation cannot differ in the two things it looks at.
                // Said rather than left as a puzzle for whoever runs the mutation pass
                // next; passing gateSettled is still what the sentence above means, and it
                // is what stays correct if a decision ever reads more than a name.
                GateResult approved = verdict.asDecision().toGateResult(gateSettled);
                settled = approved.effectiveFor(gateSettled);
                // One of the two statements in this method that hand a call to the tool.
                // See `howFar`'s declaration for why the marker is set here rather than in
                // the catch (#181).
                howFar = Disposition.THREW;
                Ran ran = execute(tool.get(), approved, settled);
                return outcomeOf(settled, ran.result(), ran.disposition());
            }
            // Denial and execution both go through execute, which logs the denial. The
            // durable path has no AgentObserver, so that line and the activity result in
            // history are the only places a denial appears; #58 was "nothing failed and
            // nothing logged", and the second half is only fixed here.
            //
            // Reached with `decision` still the gate's own result and `settled` the call it
            // settled on: an ordinary allowed call, or a denial — which carries no
            // replacement, so gateSettled is the proposal and there is no second invocation
            // to name.
            //
            // The other of the two statements that hand a call to the tool — and the one
            // that may instead refuse it, which is why the disposition on the way out comes
            // from execute() and not from this marker (#181).
            howFar = Disposition.THREW;
            Ran ran = execute(tool.get(), decision, settled);
            return outcomeOf(settled, ran.result(), ran.disposition());
        } catch (RuntimeException e) {
            // Logged, because Agent.runTool logs its equivalent and this path had nothing.
            // The denial two branches up was given a line for exactly this reason — #58 was
            // "nothing failed and nothing logged", and leaving the failure branch silent
            // fixes one half of that and keeps the other. A gate author's mistake at an
            // authorization boundary reaching only the model is not "loud".
            // Two lines and two levels, not one line for two facts (#260). The in-process
            // loop's equivalent branch makes the argument at length and this runner says
            // the same thing in the same order, because the two coming to differ about what
            // an operator is told is the defect this class already carries three comments
            // about.
            //
            // Worth one difference of emphasis here. This path DOES separate the two facts
            // structurally: `howFar` becomes GATE_FAILED or THREW, it rides out on the
            // ToolOutcome, and Temporal history is an auditor's record of it (#181). So
            // unlike in process, the log line is not the only channel — but it is still the
            // only one an operator watches without opening a run, and an operator's alert
            // cannot be built on a sentence that says "or". `howFar` and not a second
            // inference, so the line and the history row are read off one variable.
            if (howFar == Disposition.THREW) {
                LOG.warn("Tool '{}' was entered and threw on the durable path; the model is"
                                + " told the call failed and the run continues",
                        Quoted.of(invocation.name()), Quoted.failure(e));
            } else {
                LOG.error("Gate for tool '{}' threw on the durable path, so nothing was"
                                + " decided and nothing ran; this deployment's policy code"
                                + " is failing and every call it should judge is getting an"
                                + " error result instead", Quoted.of(invocation.name()),
                        Quoted.failure(e));
            }
            // Fenced, like the in-process loop (#113). The first version of that change
            // fixed four sites and left this one, which then emitted the exact sentence the
            // issue was opened about — character for character, still declared FIRST_PARTY
            // while embedding a tool's words. This class's own comments justify themselves
            // by "the two runners diverging is worse than either answer" three times over.
            // attributedTo, like the in-process loop's equivalent branch and like the
            // success path in execute(). It was missing here, so the two runners disagreed
            // about who wrote a thrown tool's words: in process the failure inherited the
            // tool's THIRD_PARTY, durably it stayed UNKNOWN. Invisible until #122 gave
            // provenance teeth, at which point a third-party tool that failed lowered the
            // floor on one runner and not the other — found by a test written for the
            // durable rule after the in-process one passed.
            // `settled` and not `invocation`: a tool that threw reports what it was
            // handed, and a gate that threw — or whose replacement effectiveFor refused —
            // reports the proposal, because it never got as far as settling on anything.
            // One variable answers both because it is assigned where the answer changes.
            return outcomeOf(settled, ToolResult.failed("Tool '" + invocation.name() + "' failed.",
                    dev.agentkit.core.prompt.Source.of("tool", invocation.name()), e).attributedTo(tool.get()), howFar);
        } catch (Throwable t) {
            // Not a RuntimeException: an Error, or a checked exception thrown through a
            // signature that does not declare it. Both had escaped the activity entirely,
            // and Temporal retried what escaped.
            LOG.warn("Tool '{}' threw {} on the durable path", Quoted.of(invocation.name()),
                    Quoted.of(t.getClass().getName()), Quoted.failure(t));
            // The type, not the message. getMessage is overridable and this branch is
            // reached by throwables nobody in this repository wrote — a message that throws
            // would turn one failure into a different one, on a path whose whole job is not
            // to. Quoted.failure above already survives that and gives the operator the
            // rest.
            // The second argument to outcomeOf's five-argument form is the whole of #129:
            // this branch is the durable counterpart of the catch the in-process loop does
            // NOT have, and the line it draws is the same one — a RuntimeException is an
            // ordinary failure the model routes around, and anything outside it means an
            // invariant broke. `howFar == THREW` restricts it to a tool body that was
            // entered: a gate that threw an Error is the deployment's policy code failing
            // before anything ran, and this class has a stated, tested decision that such a
            // failure does not end a run (DurableToolErrorTest, #103). Reversing that under
            // this issue's number is what #129 itself warns against.
            // FIRST_PARTY by hand and deliberately NOT through ToolResult.refused, which
            // the four refusal sites in this class now use (#272). This is not a refusal:
            // the tool was entered and threw part-way, so something may already have
            // happened. What makes the sentence first-party is that this class composed it
            // and the only variable in it is a class name from the deployment's own code —
            // a different claim from "nothing ran", and one refused() would misstate.
            return outcomeOf(settled, ToolResult.from(Provenance.FIRST_PARTY,
                    "Tool '" + invocation.name() + "' failed with "
                            + t.getClass().getName() + ". It threw part-way through, so any"
                            + " work it had already done has been done.").asError(), howFar,
                    howFar == Disposition.THREW);
        }
    }

    /**
     * How much of a tool's answer this runner writes into Temporal history (#137).
     *
     * <p><strong>Nothing bounded it, and past Jackson's ceiling the run stalled rather than
     * failed.</strong> An activity result is serialized by the worker and deserialized again
     * by the <em>workflow</em>, on the original write and on every replay after it. The
     * write always succeeds; the read is the half that has a limit, and above it the
     * workflow task fails — which Temporal retries indefinitely. {@code DurableJson}'s own
     * javadoc names that outcome: the run stalls rather than failing it. Measured on the
     * in-memory test server, with a tool returning one string:
     *
     * <pre>
     *  8MB content | tool executions=1 | stopReason=COMPLETED
     * 21MB content | tool executions=1 | run never resolves, workflow task attempt=2,3,4,...
     *              | DataConverterException: String value length (20051112) exceeds the
     *              | maximum allowed (20000000, from StreamReadConstraints.getMaxStringLength())
     *              | (through reference chain: dev.agentkit.temporal.ToolOutcome["content"])
     * </pre>
     *
     * <p>There is no result, no error and no {@code onFinish} — the operator sees a run that
     * is simply still going, which this repository has repeatedly called the worse failure.
     * A tool reading a document, scraping a page or relaying a verbose API response reaches
     * this without anybody being hostile.
     *
     * <p><strong>Why this figure.</strong> Jackson's 20,000,000-character ceiling is the one
     * that was hit here, but it is not the binding constraint on a real cluster: Temporal
     * rejects a payload over 2 MB and warns over 512 KB, and the in-memory test server has
     * neither limit, so the 2 MB–20 MB band was not reproducible. 100,000 characters is at
     * most 300 KB of UTF-8 (a Java {@code char} costs at most three bytes; an astral pair
     * costs four across two chars) and at most 600 KB once JSON-escaped in the worst case,
     * so one result cannot approach either limit. It is also more text than a model will
     * make use of in a single tool result — roughly 25,000 tokens.
     *
     * <p><strong>What it does not bound</strong>, said rather than implied: the
     * <em>accumulated</em> transcript. This content is written into history twice — as this
     * activity's result and again inside the model activity's input, which carries the whole
     * message list — so a long run making many maximal calls still grows towards the blob
     * limit. That is cumulative and pre-existing, and bounding one result does not fix it.
     */
    static final int MAX_CONTENT_CHARS = 100_000;

    /**
     * A completed call, cut to what the wire will carry, and judged against the floor's
     * trigger.
     *
     * <p>Every path that produces a result goes through here, including the failure
     * branches: a tool that throws still returns whoever's words were in the exception, and
     * #113 is the issue about that being somebody else's. A failure that is not judged is a
     * free pass for a tool that fails on purpose. That single funnel is why the bound sits
     * here — a per-branch cut is a rule eight places could come to disagree about.
     *
     * <p><strong>Here and not in {@code ToolOutcome}'s constructor</strong>, which looks like
     * the tighter place to put it and is the wrong one. Both reasons come from one fact: a
     * durable type's constructor runs on the <em>read</em> side too, so it runs once per
     * replay rather than once per result.
     *
     * <ul>
     *   <li><strong>The operator's line is the point of the cut, and it would fire on every
     *       replay.</strong> One truncation would be announced once per workflow task for
     *       the life of the run, and the constructor cannot name the tool that overran —
     *       which is the half of the line an operator acts on.</li>
     *   <li><strong>A transform on the read side has to be idempotent, and this one only
     *       almost is.</strong> {@link Cut#to} is idempotent for text it can cut cleanly:
     *       re-cutting {@code x[0..max] + MARKER} takes the same first {@code max}
     *       characters and re-appends the same marker. Where the bound would split a
     *       surrogate pair it stops one character short, so a second application does reach
     *       the bound, eats a character of the answer and leaves a stray period against the
     *       marker — and then converges. Said plainly because two earlier versions of this
     *       paragraph overstated it: the cost of a second cap in that constructor is one
     *       character of an astral answer, not a result that erodes on every replay, and a
     *       mutant that adds one survives this class's tests. <em>The bullet above is the
     *       reason; this one is a footnote to it.</em></li>
     * </ul>
     *
     * <p><strong>Here and not in {@code ToolResult}</strong>, so the in-process runner is
     * deliberately left unbounded — see this class's {@code executeTool} javadoc for the
     * argument, which is that the cap is a property of a wire the other runner does not
     * have.
     *
     * <p><strong>{@code settled} is the audited call, not the proposed one (#179).</strong>
     * Every caller passes what the gate settled on, and it travels into the outcome so
     * history records which call actually ran. It is also what {@link #bounded} names in the
     * operator's truncation line, which is the same string either way — a replacement may
     * edit arguments but never the name, and {@code GateResult.effectiveFor} enforces that.
     */
    private ToolOutcome outcomeOf(ToolInvocation settled, ToolResult result,
                                  Disposition disposition) {
        return outcomeOf(settled, result, disposition, false);
    }

    /**
     * The same, and whether the tool body threw outside {@code RuntimeException} (#129).
     *
     * <p>One caller — the {@code catch (Throwable)} in {@link #gated} — and the default
     * above is what every other path means. Kept as an overload rather than a fifth
     * argument everywhere so that the six sites that cannot produce one do not each carry a
     * {@code false} that a reader has to check.
     *
     * <p><strong>The residual {@code howFar} carries, restated because its price went
     * up.</strong> {@code howFar} is set on the statement before control is handed to the
     * tool, so between that statement and the tool a refused call spends time in
     * {@link #execute}'s refusal branch — a record read, {@code Quoted.of} and a log line —
     * and a throw from there reads as {@code THREW}. #181 accepted that on the grounds that
     * none of it can be made to throw from outside this class, and weighed it against
     * adding a line no test can reach. What changed is the direction of a false positive:
     * it was an audit row mislabelled, and it is now a run that stops. That is the safe
     * direction for an unreachable branch — a stop is loud and keeps every step already
     * taken — so the trade is better than the one #181 measured, not worse, and the
     * measurement it rests on is unchanged.
     */
    private ToolOutcome outcomeOf(ToolInvocation settled, ToolResult result,
                                  Disposition disposition, boolean brokeAnInvariant) {
        return ToolOutcome.of(settled, bounded(settled, result),
                floor.lowersOn(result.provenance()), disposition, brokeAnInvariant);
    }

    /**
     * {@code result} cut to {@link #MAX_CONTENT_CHARS}, with both readers told.
     *
     * <p>The model is told in-band, by {@link Cut#MARKER} landing inside the content, which
     * is what every other bounded site in the framework does — {@code ToolResult.failed} and
     * {@code ToolResult.fromThirdParty} both cut and both log. <strong>The operator's line is
     * not decoration.</strong> {@code Synthesizers.fenceOf}'s javadoc states the rule this
     * follows: a cap nobody is told about reads as "everything was carried". And the in-band
     * marker alone would not be enough even for the model, for the reason
     * {@code ToolResult.failed}'s own comment gives — any body can print the same marker, so
     * its presence proves nothing and its absence proves nothing. The operator gets the one
     * that cannot be forged.
     *
     * <p>Logged at {@code warn}, one level above the framework's other truncation lines. A
     * third-party answer cut at 32,000 characters is the size that path was designed for; a
     * tool result reaching this bound is a tool the deployment should probably change, and
     * before this change it was a stalled run.
     *
     * <p>{@code isError}, {@code provenance} and {@code views} are carried across unchanged.
     * Views are bounded by {@code View}'s own constructor rather than here — its bounds are
     * on the shape a renderer is handed, and cutting a table's rows to fit a character budget
     * would silently change what a person is shown. A view too big for this path is refused
     * where it is built, in the tool's own thread, rather than quietly halved on the way to
     * history. The reason to carry {@code provenance} matters more: the floor's trigger is read from the result one line up in
     * {@link #outcomeOf}, and a cut that reset the label to {@code UNKNOWN} would stop a
     * third party's page lowering the run's trust floor (#122).
     */
    private static ToolResult bounded(ToolInvocation call, ToolResult result) {
        String content = result.content();
        if (content.length() <= MAX_CONTENT_CHARS) {
            return result;
        }
        LOG.warn("Tool '{}' returned {} characters; cut to {} for the durable path, which"
                        + " cannot carry more. The model is told it was cut.",
                Quoted.of(call.name()), content.length(), MAX_CONTENT_CHARS);
        return new ToolResult(Cut.to(content, MAX_CONTENT_CHARS), result.isError(),
                result.provenance(), result.views());
    }

    /**
     * Runs the call {@code decision} settled on against {@code tool}, or refuses it.
     *
     * <p>One place, so the ordinary path and the resumed path cannot come to differ about
     * what a gate result means — including a person's edit, which goes through
     * {@code effectiveFor} exactly as a gate's does, so the rename-and-renumber refusal
     * applies to a reviewer too.
     *
     * <p><strong>{@code settled} is passed in rather than derived here (#179).</strong> This
     * used to call {@code decision.effectiveFor(call)} itself, which left the executed call
     * knowable only inside this method — so the outcome the caller recorded described the
     * proposal, and Temporal history, which on this path <em>is</em> the audit trail, said a
     * narrowing gate's proposal was what ran. Recomputing it at the caller instead of moving
     * it would ask a gate result twice and let a stateful one answer differently, with the
     * second answer being the one the auditor reads.
     *
     * @param settled the call the gate settled on, already unwrapped by exactly one
     *     {@code effectiveFor} at the caller, which is also what the caller records
     * @return the result, paired with whether a tool produced it — see {@link Ran}
     */
    private static Ran execute(Tool tool, GateResult decision, ToolInvocation settled) {
        if (!decision.allowed()) {
            LOG.info("Tool '{}' blocked: {}", Quoted.of(settled.name()),
                    Quoted.of(decision.reason()));
            return new Ran(ToolResult.refused(decision.reason()), Disposition.REFUSED);
        }
        // An approved edit is what runs, not what was proposed. Dropping the replacement is
        // how two in-process decorators turned an editing gate into a rubber stamp;
        // honouring one that renames the tool is how a gate written to downgrade a publish
        // to a draft let the publish happen (#104).
        return new Ran(tool.execute(settled).attributedTo(tool), Disposition.RAN);
    }

    /**
     * What {@link #execute} did: the result, and whether a tool produced it (#181).
     *
     * <p>Two values rather than the caller re-asking {@code decision.allowed()}, which is
     * the whole reason this method exists in the first place — "one place, so the ordinary
     * path and the resumed path cannot come to differ about what a gate result means". A
     * caller that read the gate result a second time to label the row would be that second
     * spelling, and the label is what the auditor reads.
     *
     * <p>A gate result cannot be recovered from the {@link ToolResult} either: a refusal and
     * a tool that returned an error are both {@code isError} with the deployment's own
     * words in them.
     */
    private record Ran(ToolResult result, Disposition disposition) {}

}
