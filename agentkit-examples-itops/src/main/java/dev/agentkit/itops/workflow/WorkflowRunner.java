package dev.agentkit.itops.workflow;

import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.TicketProvider;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.runtime.RunRules;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.runtime.Supervisor;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.ToolCatalog;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes a {@link Workflow} through the same tools, supervisor and audit trail an agent uses.
 *
 * <p>That sharing is the point. A workflow is not a second product with its own permissions
 * and its own idea of what is risky — it is a different way of deciding <em>which</em> tool
 * call happens next. The call itself still goes through the registry, still passes the
 * supervisor, still lands in the event log, and still parks for approval when the risk
 * warrants it. Everything below the decision is identical, which is why a step promoted out
 * of a successful agent run into a reusable workflow does not need re-certifying.
 *
 * <p>Execution state is held here and in the event stream, never written back into the
 * definition: two people running the same workflow concurrently share the graph and share
 * nothing else.
 *
 * <p><strong>No {@code ContextStrategy}, decided rather than inherited (#243).</strong>
 * #151 gave an agent run a transcript bound because a transcript is re-sent to a model every
 * turn, so failure text somebody else wrote costs a run quadratically; #243 asks whether this
 * runner needs the same thing, since it too feeds one step's content into the next step's
 * arguments. It does not, for three reasons that are facts about this loop rather than
 * preferences. There is no transcript and no model: a {@code ContextStrategy} is a function
 * on a {@code List<Message>} and this runner never builds one, so applying the default here
 * would mean inventing a message list for it to edit. The substitution is by name — {@code
 * $someNode} resolves to exactly that node's output — so a step's argument cost is what it
 * names and not the sum of everything before it, which is the accumulation #151 measured.
 * And an error result <em>ends the run</em> at the {@code isError} branch below, so the
 * repeated-failure shape those measurements are of cannot occur at all: the second failure
 * never happens. What does grow is {@code scope}, by one entry per node, bounded by the step
 * budget of 64 a few lines down — the author's own graph, not a third party's choice.
 *
 * <p>{@code AGENT} nodes are not implemented. They are the interesting half — a step that
 * says "work out how to do this" — and they want the full runtime, a nested execution row
 * and their own goal. Left explicit rather than silently skipped, because a workflow that
 * quietly ignores a step is worse than one that refuses to start.
 */
public final class WorkflowRunner {

    private final OpsStore store;
    private final TicketProvider tickets;
    private final DirectoryConnector directory;
    private final IdentityConnector identity;
    private final String integrationUser;
    private final Risk approvalThreshold;
    private final Risk tightenedThreshold;
    private final CorrectionBook corrections;

    /** No floor: one policy governs a run whatever it reads. */
    public WorkflowRunner(OpsStore store, TicketProvider tickets, DirectoryConnector directory,
            IdentityConnector identity, String integrationUser, Risk approvalThreshold) {
        this(store, tickets, directory, identity, integrationUser, approvalThreshold, null);
    }

    /**
     * The same, with a trust floor: once a step has read somebody else's words, the
     * approval line moves to {@code tightenedThreshold} for the rest of the run (#162).
     *
     * @param tightenedThreshold where the line moves to, or {@code null} for no floor. Must
     *     be strictly below {@code approvalThreshold} — see {@code Supervisor.floorAt}
     */
    public WorkflowRunner(OpsStore store, TicketProvider tickets, DirectoryConnector directory,
            IdentityConnector identity, String integrationUser, Risk approvalThreshold,
            Risk tightenedThreshold) {
        this(store, tickets, directory, identity, integrationUser, approvalThreshold,
                tightenedThreshold, null);
    }

    /**
     * The same, honouring standing refusals a person recorded (#329).
     *
     * <p><strong>A workflow needs this even though it has no model.</strong> The other rules
     * in {@code RunRules} are about a model's behaviour — do not route around a refusal,
     * declare before you write — and a runner with no model in its loop reasonably skips
     * them. A standing refusal is not about the model at all: it is a person saying "not in
     * this capability until somebody lifts it", and the README says it in those words.
     *
     * <p>Measured without it: a one-node workflow naming
     * {@code identity.remove_user_from_group} ran to {@code COMPLETED}, unparked and
     * undenied, and the member was removed — while a standing refusal on exactly that
     * capability was in force. The shipped {@code employee-offboarding} workflow contains
     * that node and its endpoint is reachable. A control whose stated scope is a capability,
     * and which a second execution path in the same process ignores, is worse than none:
     * it reads as covered.
     *
     * @param corrections where standing refusals are kept, or {@code null} not to honour any
     */
    public WorkflowRunner(OpsStore store, TicketProvider tickets, DirectoryConnector directory,
            IdentityConnector identity, String integrationUser, Risk approvalThreshold,
            Risk tightenedThreshold, CorrectionBook corrections) {
        this.corrections = corrections;
        this.store = Objects.requireNonNull(store, "store");
        this.tickets = tickets;
        this.directory = directory;
        this.identity = identity;
        this.integrationUser = integrationUser;
        this.approvalThreshold = approvalThreshold;
        this.tightenedThreshold = tightenedThreshold;
    }

    /** Where a run stopped, and what it produced along the way. */
    public record Result(Execution execution, String lastNodeId, boolean parked,
                         Map<String, String> outputs) {}

    /**
     * Runs {@code workflow} from its start node, through this module's {@link Supervisor}.
     *
     * @param variables values that {@code $name} arguments resolve against
     */
    public Result run(String tenantId, Workflow workflow, Map<String, Object> variables) {
        // Cast, because the two four-argument overloads both accept null and neither is
        // more specific. Named to the TrustFloor one deliberately: "no policy supplied"
        // means this runner builds its own, and that answer now includes whether a floor
        // was configured on the runner. Routing through the ToolGate overload would wrap a
        // null gate in TrustFloor.none and NPE.
        return run(tenantId, workflow, variables, (TrustFloor) null);
    }

    /**
     * The same, against a gate the caller supplies.
     *
     * <p><strong>Why this overload exists.</strong> Not to make the gate configurable — a
     * deployment that wants different policy changes the {@link Supervisor}, and nothing in
     * the product calls this. It exists because the gate this runner builds for itself could
     * only ever say allow or deny, so three of {@link GateResult}'s answers were unreachable
     * from here and therefore untestable (#182). Two of the three still are: that supervisor
     * parks through {@link GateResult#needsAPerson} since #157, but it never narrows a call,
     * so {@code allowWith} and a park carrying a replacement remain reachable only from
     * here. Measured on the three-argument path, with the completion event's
     * {@code effective.arguments()} replaced by the proposal:
     *
     * <pre>
     * mvn -o -pl agentkit-examples-itops -am test    Tests run: 48, Failures: 0    SURVIVES
     * </pre>
     *
     * <p>The mutant survived by being unobservable rather than by being untested. A latent
     * line in an example module is exactly the kind that rots — a refactor breaks it with
     * nothing to say so, and an example is where a reader learns the idiom.
     *
     * <p>Supplying a gate also reached a defect rather than only a blind spot: this runner
     * turned {@link GateResult#needsAPerson} into a rejection, which is the half-handling
     * {@code needsAPerson}'s own javadoc predicts of "a runner that predates this". It
     * refused the call and surfaced the reason, both true, and did not do the second half,
     * which is asking. See {@link #invoke} for what it does now — one branch, taken now by
     * the default supervisor as well, where #182 left this runner reading a supplied gate's
     * verdict and the default gate's private flag.
     *
     * @param gate the gate every tool call passes; {@code null} builds this module's
     *     {@link Supervisor}, which is what the three-argument overload does. Null rather
     *     than an {@code Optional} parameter because the default is not the caller's to
     *     construct: the supervisor needs the {@link OpsContext} this method creates.
     */
    public Result run(String tenantId, Workflow workflow, Map<String, Object> variables,
            ToolGate gate) {
        return run(tenantId, workflow, variables,
                gate == null ? null : TrustFloor.none(gate));
    }

    /**
     * The same, against a {@link TrustFloor} the caller supplies (#162).
     *
     * <p><strong>This runner was the fifth {@code ToolGate.evaluate} call site and the one
     * that could not have a floor at all.</strong> #159 lists it among the paths a park did
     * not reach and #162 among the paths a floor does not; the first was fixed by #182 and
     * this is the second. A workflow is where a floor has something to protect, which the
     * agent runner is measurably not — see {@code ExecutionRunner} for why it keeps a plain
     * gate — because a graph author decides where the read happens and both workflows this
     * module ships never read a ticket at all:
     *
     * <pre>
     * workflow                   tool steps   steps that lower the floor
     * employee-offboarding       4            0
     * privileged-access-review   1            0
     * </pre>
     *
     * <p>So the ordinary policy is what governs the shipped graphs, and the tightened one
     * engages only for a graph that reaches into ticketing — which nothing stops an author
     * writing, since {@code ToolCatalog.forExecution} registers the ticket tools for this
     * runner too and {@link #resolve} feeds a step's <em>content</em> into the next step's
     * arguments. That is the shape the floor is for: read somebody else's words, then act on
     * them, with no model in the loop and — because the gate this runner builds for itself
     * passes {@code null} for the reviewer — no goal-alignment screen either.
     *
     * <p>The lowered bit is a local in the loop below, exactly as in {@code Agent.run} and
     * {@code ToolBridges}: {@link TrustFloor} holds two pure policies and the runner holds
     * the one thing that has to be remembered. And it is set <em>after</em> the call that
     * produced the content, matching both of those runners — the step that read the ticket
     * is judged by the policy that was in force when it was proposed, and the next one is
     * not.
     *
     * @param floor the policy, as two policies and the moment between them; {@code null}
     *     builds this module's {@link Supervisor}, wrapped in a floor when this runner was
     *     given a tightened threshold and in {@link TrustFloor#none} otherwise
     */
    public Result run(String tenantId, Workflow workflow, Map<String, Object> variables,
            TrustFloor floor) {
        Execution execution = store.createExecution(tenantId, "workflow:" + workflow.id(),
                Execution.Trigger.WORKFLOW, workflow.id(),
                "Run workflow " + workflow.name() + " v" + workflow.version());
        // Reassigned from what save() hands back. Dropping it left `execution` as the
        // PENDING record createExecution returned, and withStatus only sets startedAt on the
        // transition INTO RUNNING — so every terminal save below rebuilt from the stale copy
        // and wrote startedAt back as null. The same one-line defect #267 measured on
        // IntakeWorker's sweep row, at a site the issue did not name; found because routing
        // this runner's terminal writes through Execution.concluded meant reading all of
        // them, and left unfixed it would have been "one rule, several runners" with one
        // runner quietly opted out.
        execution = store.save(execution.withStatus(Execution.Status.RUNNING));
        store.append(execution.id(), Execution.Event.Type.EXECUTION_STARTED,
                Map.of("workflow", workflow.id(), "version", workflow.version()));

        OpsContext context = new OpsContext(tenantId, execution.id(), store);
        ToolRegistry registry = ToolCatalog.forExecution(tickets, integrationUser, directory,
                identity, context, store);
        TrustFloor policy = floor != null ? floor : defaultFloor(context, workflow);
        // Run state, and the only piece of it a floor is allowed to imply: monotonic, held
        // here rather than on the floor, for the reason TrustFloor spells out at length.
        boolean lowered = false;

        Map<String, Object> scope = new HashMap<>(variables == null ? Map.of() : variables);
        Map<String, String> outputs = new LinkedHashMap<>();
        Workflow.Node current = workflow.node("start").orElseThrow(
                () -> new IllegalArgumentException("Workflow " + workflow.id() + " has no start node"));
        String branch = null;
        String lastId = current.id();

        // A step budget rather than a visited-set: a workflow may legitimately revisit a
        // node (a retry loop), but it may not do so forever, and an unbounded graph walk
        // driven by data is a hang waiting for the wrong input.
        for (int step = 0; step < 64; step++) {
            lastId = current.id();
            switch (current.type()) {
                case START -> { }
                case END -> {
                    store.append(execution.id(), Execution.Event.Type.EXECUTION_COMPLETED,
                            Map.of("node", current.id(), "label", String.valueOf(current.label())));
                    return new Result(store.save(execution.concluded(
                            Execution.Status.COMPLETED, "Reached " + current.label())),
                            lastId, false, outputs);
                }
                case TOOL -> {
                    Step attempt;
                    try {
                        attempt = invoke(registry, policy.inForce(lowered), context, current,
                                scope);
                    } catch (RuntimeException | Error thrown) {
                        // The outer half of #135's third site. invoke() turns a *tool* that
                        // throws a RuntimeException into an error result a few lines down,
                        // which is what every other runner here does; this catches what is
                        // left — a gate that throws, an append that throws, and an Error from
                        // anywhere — because the invariant is about the Execution's status
                        // and not about which line broke it. TOOL_STARTED has been appended
                        // by the time control is inside invoke(), so anything escaping leaves
                        // a row saying a tool call began, no row saying how it ended, and an
                        // Execution sitting RUNNING in the store with nothing in the system
                        // that will ever move it. That is wider than #103, which needed a
                        // durable retry to hurt.
                        //
                        // The terminal rows are written either way. What differs is what
                        // happens next, and it differs deliberately. A RuntimeException
                        // becomes a FAILED run reported through Result, which is this
                        // runner's ordinary vocabulary for a call that did not work. An
                        // Error is rethrown as itself, because "the call failed" is the one
                        // thing it is not: #135's own warning is that catching Throwable
                        // everywhere would make an OutOfMemoryError look like a handled tool
                        // failure in five more places, and this is one of the five. So the
                        // audit trail is completed and the caller still gets the Error —
                        // GoapRunner's and AgentGraph's reading, terminal for this unit of
                        // work, rather than #130's non-terminal durable branch, which was
                        // non-terminal because ending a durable run is a versioned
                        // control-flow change (#129) and there is no replay history here.
                        //
                        // If the store itself is what threw, these two calls throw again and
                        // the Execution stays RUNNING. That is a broken store rather than a
                        // broken tool, and writing the failure anywhere would need a second
                        // store to write it to.
                        //
                        // Cut and flattened, like the completion event's `result` a hundred
                        // lines down. A throwable's toString is whatever it was constructed
                        // with, and a tool's exception message routinely carries text the
                        // tool read from somewhere else; an audit row and an execution
                        // summary are not the place to find out how long that is.
                        String reported = dev.agentkit.core.util.Cut.to(
                                dev.agentkit.core.util.OneLine.of(String.valueOf(thrown)),
                                Execution.SUMMARY_LIMIT);
                        store.append(execution.id(), Execution.Event.Type.EXECUTION_FAILED,
                                Map.of("node", current.id(), "error", reported));
                        Execution stopped = store.save(execution.concluded(
                                Execution.Status.FAILED,
                                "Step " + current.id() + " threw " + reported));
                        if (thrown instanceof Error error) {
                            throw error;
                        }
                        return new Result(stopped, lastId, false, outputs);
                    }
                    ToolResult result = attempt.result();
                    // After the call, not before it. The step that read somebody else's
                    // words was judged by the policy in force when it was proposed; the
                    // next one is not. Agent.run and ToolBridges.lowerIfNeeded both order
                    // it this way, and a floor that lowered first would refuse the very
                    // read that lowers it.
                    if (!lowered && policy.lowersOn(result.provenance())) {
                        lowered = true;
                        store.append(execution.id(),
                                Execution.Event.Type.TRUST_FLOOR_LOWERED,
                                Map.of("node", current.id(), "tool", current.tool(),
                                        "provenance", result.provenance().name()));
                    }
                    outputs.put(current.id(), result.content());
                    // Before isError, and that order is load-bearing: both kinds of park
                    // produce an error result, so asking "did it fail" first would report a
                    // waiting run as a failed one.
                    if (attempt.parked()) {
                        return parked(execution, lastId, outputs);
                    }
                    if (result.isError()) {
                        store.append(execution.id(), Execution.Event.Type.EXECUTION_FAILED,
                                Map.of("node", current.id(), "error", result.content()));
                        // result.content() is a tool's own text, unbounded and multi-line,
                        // and it went into the summary raw until Execution.concluded started
                        // cutting it (#267). The neighbouring throw path had been cutting its
                        // throwable since #135; this one had not, four lines away.
                        return new Result(store.save(execution.concluded(
                                Execution.Status.FAILED,
                                "Step " + current.id() + " failed: " + result.content())),
                                lastId, false, outputs);
                    }
                    scope.put(current.id(), result.content());
                }
                case CONDITION -> branch = String.valueOf(evaluate(current.expression(), scope));
                case APPROVAL -> {
                    // An explicit checkpoint, independent of any single tool's risk: some
                    // processes want a person to look before the next step whatever the
                    // policy says about it.
                    store.append(execution.id(), Execution.Event.Type.HUMAN_APPROVAL_REQUESTED,
                            Map.of("node", current.id(), "label", String.valueOf(current.label())));
                    return parked(execution, lastId, outputs);
                }
                case WORKFLOW -> throw new UnsupportedOperationException(
                        "Sub-workflows are not implemented yet (node " + current.id() + ")");
                case AGENT -> throw new UnsupportedOperationException(
                        "Agent nodes are not implemented yet (node " + current.id() + ")");
            }

            Optional<Workflow.Node> next = workflow.next(current.id(),
                    current.type() == Workflow.Node.Type.CONDITION ? branch : null);
            if (next.isEmpty()) {
                store.append(execution.id(), Execution.Event.Type.EXECUTION_COMPLETED,
                        Map.of("node", current.id(), "reason", "no outgoing edge"));
                return new Result(store.save(execution.concluded(Execution.Status.COMPLETED,
                        "Stopped at " + current.id() + ": no outgoing edge.")),
                        lastId, false, outputs);
            }
            current = next.get();
        }
        return new Result(store.save(execution.concluded(Execution.Status.FAILED,
                "Stopped: step budget exhausted.")), lastId, false, outputs);
    }

    /**
     * The floor this runner builds when the caller supplied none.
     *
     * <p>Built here rather than by the caller because the {@link Supervisor} it wraps needs
     * the {@link OpsContext} {@code run} creates, which is the same reason the gate overload
     * takes {@code null} rather than an {@code Optional} — and the reason a floor cannot be
     * a constructor argument on this class either.
     *
     * <p>{@link TrustFloor#none} when no tightened threshold was configured, and that
     * spelling matters: the framework used to fake "no floor" as
     * {@code afterThirdParty(gate, gate)}, which lowers, so a deployment that had never
     * heard of a floor was told its floor had engaged. {@code none} says what it means and
     * {@link TrustFloor#lowersOn} then answers false for everything, so the branch above
     * costs one set lookup per step and never fires.
     */
    private TrustFloor defaultFloor(OpsContext context, Workflow workflow) {
        Supervisor supervisor = new Supervisor(approvalThreshold, identity, context, store,
                "Run workflow " + workflow.name(), null, null);
        // Standing refusals in front of the supervisor, for the ordering reason RunRules
        // gives: allOf short-circuits on a denial, so nothing downstream writes an
        // ApprovalRequest for a step that can never run.
        ToolGate governing = corrections == null ? supervisor
                : ToolGates.allOf(
                        RunRules.honourStandingRefusals(corrections, context.tenantId()),
                        supervisor);
        return tightenedThreshold == null ? TrustFloor.none(governing)
                : supervisor.floorAt(tightenedThreshold);
    }

    private Result parked(Execution execution, String lastId, Map<String, String> outputs) {
        store.append(execution.id(), Execution.Event.Type.EXECUTION_PARKED,
                Map.of("node", lastId));
        return new Result(store.save(execution.concluded(
                Execution.Status.WAITING_FOR_APPROVAL,
                "Waiting for approval at " + lastId + ".")), lastId, true, outputs);
    }

    /**
     * What one tool step produced, and whether it parked rather than ran.
     *
     * <p>Both are error results — a park did not run and a failure did not succeed — so the
     * caller cannot tell them apart from the {@link ToolResult} alone, and filing a run that
     * is waiting for a person as a run that failed is #182's own defect.
     *
     * <p>The flag used to have two sources: {@link GateResult#awaiting()} for a gate that
     * said so, and {@code parkedOutOfBand}, an {@code instanceof Supervisor} check reading a
     * flag this module's own gate set on itself. #157 removed the second by making that gate
     * speak the framework's language, so there is now one answer and this record carries it
     * from {@link #invoke} to the loop rather than the loop asking the gate a second time —
     * which is the {@code effectiveFor}-twice defect in another costume, since a stateful
     * gate may answer differently and the second answer is the one the auditor reads (#131).
     */
    private record Step(ToolResult result, boolean parked) {}

    private Step invoke(ToolRegistry registry, ToolGate gate, OpsContext context,
            Workflow.Node node, Map<String, Object> scope) {
        Optional<Tool> tool = registry.find(node.tool());
        if (tool.isEmpty()) {
            // refused(), not error(): this framework wrote the sentence, about a tool
            // that was never entered, and the loop below asks
            // policy.lowersOn(result.provenance()) on every step's result. Left at
            // ToolResult.error's UNKNOWN default, a step that resolved nothing lowered the
            // run's trust floor and wrote TRUST_FLOOR_LOWERED into the audit trail (#272).
            //
            // The fourth site of #278's three, found by sweeping for the shape rather than
            // trusting the list: this one wrote "Workflow step " + node.id() + " names an
            // unknown tool: " + node.tool(), with neither echo bounded and neither quoted.
            // Now the same sentence the other three runners write, through the same
            // factory. The step id is dropped from it rather than bounded, because it was
            // never this sentence's to carry: the loop below files EXECUTION_FAILED with
            // Map.of("node", current.id(), "error", result.content()) and concludes the
            // Execution with "Step " + current.id() + " failed: " + result.content(), so
            // an operator read the id twice —
            //
            //   was : Step s1 failed: Workflow step s1 names an unknown tool: foo
            //   now : Step s1 failed: Unknown tool: 'foo'
            //
            // — so nothing an operator reads is lost, and what was gained is that the
            // remaining echo is bounded and escaped at its source rather than only by
            // Execution.SUMMARY_LIMIT's cut of the whole summary, which said nothing about
            // the line terminators in it.
            return new Step(ToolResult.unknownTool(node.tool()), false);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        node.arguments().forEach((key, value) -> arguments.put(key, resolve(value, scope)));
        // Through the one door every runner uses (#246). This construction used to be a
        // bare `new ToolInvocation(...)`, and a step whose arguments this framework will
        // not carry threw out of invoke() into the loop's catch — which files the whole
        // Execution FAILED with a stringified IllegalArgumentException in the audit row.
        // A workflow author is not more trusted than a model here (the comment below says
        // so about the gate) and neither is more able to act on "tool arguments nest deeper
        // than 100 levels" than on a sentence saying what to send instead; and the
        // arguments are not the author's anyway once resolve() has substituted this run's
        // scope into them, which is how a tool's own output reaches them.
        String id = OpsStore.Ids.next("wf");
        ProposedCall proposed = ProposedCall.of(id, node.tool(), arguments);
        if (proposed instanceof UnusableToolUseBlock unusable) {
            // The unknown-tool shape above: a Step carrying an error result and no park, so
            // the loop below files the run through its ordinary vocabulary rather than
            // through the escape hatch. No audit row of its own, for the same reason that
            // branch writes none — nothing was gated, nothing was started, and TOOL_STARTED
            // has deliberately not been appended yet, so there is no half-open row to
            // close. The loop's EXECUTION_FAILED carries the reason.
            //
            // FIRST_PARTY declared rather than left to ToolResult.error's UNKNOWN default:
            // this text is the framework's own, written without reading anything, and the
            // loop asks policy.lowersOn(result.provenance()) — so an UNKNOWN here would let
            // a call that never happened tighten the policy for the rest of the run.
            //
            // Through ToolResult.refused now, with the unknown-tool and gate branches
            // beside it, so this runner has one answer rather than one branch that thought
            // about it and three that did not (#272).
            return new Step(ToolResult.refused(unusable.refusal()), false);
        }
        ToolInvocation invocation = new ToolInvocation(id, node.tool(),
                ((ToolUseBlock) proposed).input());

        // The proposal, and labelled as such: nothing has gated it yet, so it is the truth
        // at this moment. What the tool receives is recorded on the completion event (#131).
        store.append(context.executionId(), Execution.Event.Type.TOOL_STARTED,
                Map.of("node", node.id(), "tool", node.tool(), "proposedArguments", arguments));
        // Through the supervisor, exactly as an agent's proposal would be. A workflow author
        // is not more trusted than a model here: the author wrote the graph before the
        // arguments existed, and the arguments are what carry the risk.
        GateResult verdict = gate.evaluate(tool.get(), invocation);
        if (!verdict.allowed()) {
            // A person must decide is not a refusal, and recording it as one was this
            // runner's half of #182. GateResult.needsAPerson's javadoc names the failure
            // exactly -- "a runner that predates this refuses the call and surfaces the
            // reason, which is a true thing to say; it just does not do the second half,
            // which is asking" -- and this was that runner. Measured with a parking gate
            // supplied through the overload above, before this branch existed:
            //
            //   events   = [.., ACTION_REJECTED, EXECUTION_FAILED]
            //   status   = FAILED
            //
            // A run waiting for a person was filed as a run that failed, so the thing a
            // reviewer had to act on did not appear as one.
            if (verdict.awaiting().isPresent()) {
                // Written only when the gate queued nothing of its own (#157). This
                // module's Supervisor now parks through needsAPerson like any other gate,
                // and it also writes a far richer HUMAN_APPROVAL_REQUESTED of its own --
                // approvalId, risk, effect, arguments -- against an ApprovalRequest a
                // reviewer opens. Appending the thin row beside it would put two rows of
                // one type in the queue for one decision, and the thin one is the one with
                // nothing to act on. Asked of the store rather than of the gate, because
                // "did a park queue an approval in this execution" is a fact about this
                // module's audit trail; asking the gate is how parkedOutOfBand came to
                // hard-code `instanceof Supervisor` into a runner (#182).
                //
                // For a gate that queues nothing the thin row is still the record that a
                // person was asked, and the APPROVAL node case above emits the same shape
                // with no approvalId, so this is the runner's existing vocabulary rather
                // than a second one invented here.
                if (store.pendingApprovalFor(context.executionId()).isEmpty()) {
                    store.append(context.executionId(),
                            Execution.Event.Type.HUMAN_APPROVAL_REQUESTED,
                            Map.of("node", node.id(), "tool", node.tool(),
                                    "reason", verdict.reason()));
                }
                // The gate is the deployment's own policy speaking, and a park read
                // nothing — a person has not answered yet. Recording it as content of
                // unknown origin tightened the policy for a run that is still waiting.
                return new Step(ToolResult.refused(verdict.reason()), true);
            }
            store.append(context.executionId(), Execution.Event.Type.ACTION_REJECTED,
                    Map.of("node", node.id(), "reason", verdict.reason()));
            return new Step(ToolResult.refused(verdict.reason()), false);
        }
        // Asked of GateResult, like both agent loops and the code-execution bridge. Latent
        // here — this supervisor only ever allows or denies — but the unwrap idiom is the
        // defect, and an example is where a reader learns the idiom (#104).
        //
        // Asked ONCE and carried, so the call executed and the call recorded below are the
        // same object. Asking twice would let a stateful gate answer differently, and the
        // second answer is the one the auditor would read (#131).
        ToolInvocation effective = verdict.effectiveFor(invocation);
        ToolResult result;
        try {
            result = tool.get().execute(effective).attributedTo(tool.get());
        } catch (RuntimeException thrown) {
            // Unguarded until #135. TOOL_STARTED had already been appended, so a tool that
            // threw left run() with no TOOL_COMPLETED, no TOOL_FAILED and no
            // EXECUTION_FAILED, and the Execution sat RUNNING in the store with nothing in
            // the system that would ever move it. Wider than #103, which needed a durable
            // retry to hurt: here a plain RuntimeException was enough.
            //
            // An error result rather than a rethrow, because that is what every other
            // runner in this repository does with a tool that throws — Agent.runTool,
            // ToolActivitiesImpl, ToolBridges — and because the loop below already knows
            // what to do with one: the isError branch appends EXECUTION_FAILED and files
            // the run FAILED. So the throw becomes a terminal step through the runner's own
            // vocabulary instead of a second exit that has to learn the audit trail again.
            //
            // Fenced and attributed like ToolBridges' equivalent (#113): an exception
            // message out of a tool routinely carries whatever the tool read, and resolve()
            // feeds a step's content into the next step's arguments. Nothing reads it in
            // this run — isError ends it — but the content lands in `outputs`, which the
            // caller gets back.
            result = ToolResult.failed("Tool '" + node.tool() + "' failed.",
                    dev.agentkit.core.prompt.Source.of("tool", node.tool()), thrown)
                    .attributedTo(tool.get());
        }
        store.append(context.executionId(), result.isError()
                        ? Execution.Event.Type.TOOL_FAILED : Execution.Event.Type.TOOL_COMPLETED,
                Map.of("node", node.id(), "tool", node.tool(),
                        // What the tool RECEIVED, not what the node asked for (#131). The
                        // TOOL_STARTED event above records the proposal, which is the truth
                        // at the moment it fires; this one is the truth about what ran, and
                        // an audit trail that reports the proposal as the execution is wrong
                        // about the only question anybody asks it.
                        "arguments", effective.arguments(),
                        // The declaration, not the content: this event is the audit trail,
                        // and "where did this come from" is the question #60 opened with.
                        "provenance", result.provenance().name(),
                        "result", dev.agentkit.core.util.Cut.to(
                                dev.agentkit.core.util.OneLine.of(result.content()), 400)));
        // Not parked: a verdict that allowed the call cannot also have asked for a person --
        // GateResult's constructor refuses that combination outright. This used to ask
        // parkedOutOfBand here too, and that call was documented as unreachable and kept
        // because it read a flag whose invariant belonged to Supervisor rather than to this
        // runner. With the flag no longer read, the framework's own type carries the rule.
        return new Step(result, false);
    }

    private static Object resolve(Object value, Map<String, Object> scope) {
        if (value instanceof String text && text.startsWith("$")) {
            return scope.getOrDefault(text.substring(1), "");
        }
        return value;
    }

    /**
     * The condition language, which is deliberately tiny: {@code <name> contains <text>}.
     *
     * <p>Kept to one form because a workflow condition is read by the person approving the
     * workflow, and an expression language grows until only its author can audit it. When
     * this is not enough, the answer is an {@code AGENT} node — a step that reasons, under
     * the same supervisor — rather than a richer grammar here.
     */
    private static boolean evaluate(String expression, Map<String, Object> scope) {
        if (expression == null) {
            return false;
        }
        int at = expression.indexOf(" contains ");
        if (at < 0) {
            return false;
        }
        String name = expression.substring(0, at).strip();
        String needle = expression.substring(at + " contains ".length()).strip();
        Object value = scope.get(name.startsWith("$") ? name.substring(1) : name);
        return value != null && value.toString().contains(needle);
    }
}
