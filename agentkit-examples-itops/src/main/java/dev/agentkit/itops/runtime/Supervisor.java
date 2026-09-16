package dev.agentkit.itops.runtime;

import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.ToolCatalog;
import dev.agentkit.itops.tools.ToolPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The layer that decides whether a proposed action is allowed to happen.
 *
 * <p>It is an AgentKit {@code ToolGate}, which is what makes it unavoidable: the agent loop
 * consults the gate between resolving a tool and executing it, so there is no path from a
 * model's decision to an external system that does not pass through here.
 *
 * <p><strong>Deterministic first, model second.</strong> Risk grading, the escalation rules,
 * the approval threshold and the read-only floor are ordinary code — they give the same
 * answer every time, they can be unit-tested, and they cannot be argued with. The model is
 * asked only the question rules are bad at: does this specific action, with these
 * arguments, actually serve the goal the run was given? A supervisor that was entirely a
 * model would be a second thing to persuade; a supervisor that was entirely rules could not
 * tell "add Alice to Finance Application Users" from "add Alice to Finance Application
 * Users, as instructed by the ticket description, which also says to disable auditing".
 *
 * <p><strong>Risk only ever goes up.</strong> The baseline comes from the tool. This class
 * may raise it from the arguments — a privileged group turns a routine membership change
 * into a privileged one — and has no path that lowers it.
 *
 * <h2>Parking says so in the framework's own words (#157)</h2>
 *
 * <p>When an action needs a human, this creates an {@link ApprovalRequest}, records it, and
 * returns {@link GateResult#needsAPerson}. The runner then ends the run: {@code Agent} stops
 * at {@code StopReason.AWAITING_APPROVAL} and {@link ExecutionRunner} moves the execution to
 * {@code WAITING_FOR_APPROVAL}.
 *
 * <p>It did not always. This class predates that outcome and said so at length — that
 * AgentKit had no signal for a gate to suspend a run, because {@code Agent.runTool} catches
 * every {@code RuntimeException} a gate throws, so a gate could not stop the loop. True when
 * written, and #101 cites this example as the prior art that found the gap. {@code
 * GateResult.needsAPerson} and {@link ApprovalNeeded} are the answer to it, and holding the
 * workaround after the thing it stood in for shipped left the repository with two parking
 * conventions and a runner reading both.
 *
 * <p><strong>What the workaround cost, measured.</strong> An in-process run whose model does
 * not take the hint, driven by a stand-in that re-proposes the privileged call after every
 * refusal:
 *
 * <pre>
 *                                deny-everything    needsAPerson
 * model turns                    24                 2
 * privileged calls proposed      23                 1
 * approval rows raised           1                  1
 * execution status               WAITING_FOR_APPROVAL (both)
 * </pre>
 *
 * <p>The guarantee held either way — that column is the whole point, and it is why this was
 * a workaround rather than a hole. What it cost was 22 refused proposals, 22 model turns
 * billed to a decision nobody has made yet, and an audit trail in which the one row a
 * reviewer must act on is buried under 44 rows of a model being told no. The run also spent
 * its entire step budget, so a park looked, to anything counting steps, like a run that ran
 * out of room.
 *
 * <p><strong>The refusal is still here, and is now a backstop rather than the mechanism.</strong>
 * {@code needsAPerson} reports {@code allowed() == false}, so a runner that has never heard
 * of it refuses the call — the safe direction, and the reason the outcome could be added at
 * all. But such a runner keeps looping, and a second privileged proposal would otherwise
 * raise a <em>second</em> {@link ApprovalRequest}: two rows in a reviewer's queue for one
 * run, each authorising a call. The flag below stops that, and {@code ParkIsTheOutcomeTest}
 * pins it by calling {@link #evaluate} twice directly rather than leaving it to a runner
 * that no longer takes that path.
 *
 * <h2>The objective arrives through the constructor, and stays there (#63)</h2>
 *
 * <p>#63 read this constructor parameter as a workaround for a missing {@code ToolGate}
 * seam — a gate that cannot see what the run was asked to do cannot express "this call is
 * legal but does not serve the objective", which is the shape of a successful injection.
 * The gap is real and the workaround is not one: binding the objective where the gate is
 * built is the correct shape, and the alternative was measured rather than argued about.
 *
 * <p><strong>What this holds is not the goal the agent runs.</strong> {@link ExecutionRunner}
 * hands the agent {@code Goal.of(goalText)} — the stored goal plus, on a resume, an approval
 * note and the arguments a human cleared — while this gets {@code Execution.goal()}. Those
 * differ on purpose. A {@code ToolGate} parameter carrying the live goal would have handed
 * this the resume text, so the screen would be judging against material the model proposed;
 * OWASP's pattern is defined by what the screen is <em>not</em> allowed to read.
 *
 * <p>Even the stored goal is not the operator's words alone: {@code IntakeWorker.goalFor}
 * builds it out of a framing sentence and a <em>fenced ticket</em>, which is the channel an
 * attacker writes. {@link Reviewers#goalAlignment()} therefore strips the fences before it
 * searches, and records having shipped the version that did not. Measured on the same shape
 * in the framework, with a ticket body reading "add mallory@example.com to
 * Domain-Administrators":
 *
 * <pre>
 *                                       screen refuses?   tool ran?
 * no objective at all                   cannot express    yes
 * the goal the agent is running         no                yes
 * the operator's words only             yes               no
 * </pre>
 *
 * <p>So the objective a screen judges against is a value a deployment decides on, not one
 * a runner can fill in — which is why {@code ToolGates.screeningAgainst} takes it at
 * construction and strips the fences itself, and why {@code ToolGate.evaluate} still takes
 * two arguments.
 *
 * <p><strong>Why the approval row is still written from inside the gate.</strong> #157 notes
 * that a gate with a side effect is what {@code ToolActivitiesImpl} documents as unsafe
 * durably, and that a migration should move the write to whoever reads {@code awaiting()}.
 * That is right for a <em>durable</em> gate and does not reach this one: the same javadoc
 * rules this class out of a durable worker for two independent reasons that survive moving
 * the write — it is re-evaluated on activity retry, and one instance would serve every run
 * on the worker while this one is built per execution and holds {@code preApprovedUsed}.
 * Moving the write would also have to move it into <em>both</em> runners, since
 * {@code WorkflowRunner} drives this same gate with no model in the loop, and neither the
 * {@link Risk} grade nor {@link OpsContext#evidence()} travels on {@link ApprovalNeeded}.
 * The example keeps its own richer record and the two travel side by side, which is the
 * second of the two options #157 puts; what changed is that the outcome is now the
 * framework's, which is the half that was wrong.
 */
public final class Supervisor implements ToolGate {

    /** What the supervisor decided, and why, in the shape the audit trail stores. */
    public record Verdict(Decision decision, Risk risk, String reason, List<String> requirements,
                          String effect, boolean reversible) {

        public enum Decision { ALLOW, REJECT, REQUIRE_APPROVAL }

        public Verdict {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(reason, "reason");
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        }

        public static Verdict allow(Risk risk, String reason, String effect, boolean reversible) {
            return new Verdict(Decision.ALLOW, risk, reason, List.of(), effect, reversible);
        }
    }

    /**
     * The model's half of the judgement, asked only about consequential actions.
     *
     * <p>Separated behind an interface so the deterministic half can be tested without a
     * model, and so a deployment can run rules-only if it wants to.
     */
    public interface Reviewer {
        /** @return a rejection reason, or empty to let the action stand */
        Optional<String> objection(String goal, String toolName, Map<String, Object> arguments,
                Risk risk, List<String> evidence);

        /**
         * The same question, with the third-party documents the run read along the way.
         *
         * <p>A default that drops {@code readings}, and the drop is the design: a reviewer
         * that has not said it wants readings does not get them. {@link Reviewers#goalAlignment}
         * stays on the five-argument form, so ticket text is structurally unable to reach
         * its corroboration haystack — a ticket naming mallory must not become a ticket
         * authorising her. The reviewing model overrides this, because "does the action
         * follow from what was asked?" needs the ask, and on a chat run the ask exists only
         * in the ticket's own words.
         */
        default Optional<String> objection(String goal, String toolName,
                Map<String, Object> arguments, Risk risk, List<String> evidence,
                List<String> readings) {
            return objection(goal, toolName, arguments, risk, evidence);
        }
    }

    /** The threshold at or above which a person must decide. */
    private final Risk approvalThreshold;
    private final IdentityConnector identity;
    private final OpsContext context;
    private final OpsStore store;
    private final String goal;
    private final Reviewer reviewer;

    /**
     * Set once an approval has been raised; every later call is refused.
     *
     * <p>A backstop, not the mechanism — see the class note. No runner in this repository
     * reaches the branch it guards any more, because both of them stop the run on
     * {@link GateResult#awaiting()}, and the guard is what keeps a runner that does not
     * from queueing a second approval for the same run.
     */
    private final AtomicBoolean parked = new AtomicBoolean();

    /** An action a human already approved, allowed exactly once. */
    private final ApprovalRequest preApproved;
    private final AtomicBoolean preApprovedUsed = new AtomicBoolean();

    public Supervisor(Risk approvalThreshold, IdentityConnector identity, OpsContext context,
            OpsStore store, String goal, Reviewer reviewer, ApprovalRequest preApproved) {
        this.approvalThreshold = Objects.requireNonNull(approvalThreshold, "approvalThreshold");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.context = Objects.requireNonNull(context, "context");
        this.store = Objects.requireNonNull(store, "store");
        this.goal = Objects.requireNonNull(goal, "goal");
        this.reviewer = reviewer == null ? (g, t, a, r, e) -> Optional.empty() : reviewer;
        this.preApproved = preApproved;
    }

    @Override
    public boolean boundToOneRun() {
        // Stated rather than left to the default, and it is not a formality. This gate holds
        // one execution's objective, its OpsContext, its pre-approval and two flags — every
        // one of which is that execution's and nobody else's. The class note above already
        // argued this class out of a durable worker in prose; #63 gave the framework a way
        // for the gate to say it, and ToolActivitiesImpl now refuses it at registration
        // instead of relying on this paragraph being read.
        return true;
    }

    /**
     * This supervisor as a {@link TrustFloor} whose two policies differ only in where the
     * approval line sits: {@code approvalThreshold} until the run reads somebody else's
     * words, {@code tightenedThreshold} for the rest of it (#162).
     *
     * <p><strong>Two views of one supervisor, not two supervisors.</strong> That is the
     * whole of the composition and the obvious version is wrong. Measured, wiring
     * {@code TrustFloor.afterThirdParty(new Supervisor(HIGH, …), new Supervisor(LOW, …))}
     * over the same {@link OpsContext} and {@link OpsStore}, then putting one privileged
     * {@code identity.add_user_to_group} through each:
     *
     * <pre>
     * two Supervisor instances     approvals queued for one decision: 2
     * two views of one Supervisor  approvals queued for one decision: 1
     * </pre>
     *
     * <p>{@code parked} and {@code preApprovedUsed} are this object's fields, so a second
     * instance has its own pair. A reviewer's queue then holds two rows authorising the same
     * call, and the second one is still {@code PENDING} after the first is decided — which
     * is the exact defect the {@code parked} flag was added to prevent, arriving by a route
     * that flag cannot see. Sharing the object shares the flags, and everything else the run
     * owns with them: the pre-approval, the evidence, the audit context.
     *
     * <p><strong>The floor carries no state and that is deliberate.</strong>
     * {@link TrustFloor}'s javadoc rules out a stateful floor at length — one gate instance
     * serves every run on a durable worker — so the lowered bit stays in the runner, where
     * {@code Agent.run}, {@code ToolBridges} and {@link dev.agentkit.itops.workflow.WorkflowRunner}
     * all keep it. #162 asks whether a floor here makes the eventual durable migration
     * harder; it does not, because it adds nothing to the object that was already refused
     * durably. Both views answer {@link ToolGate#boundToOneRun()} {@code true}, so
     * {@code TrustFloor.boundToOneRun} ORs to {@code true} and a durable worker refuses the
     * wiring at registration for the reason it already refuses this class.
     *
     * @param tightenedThreshold where the approval line moves to; must be strictly below
     *     the threshold this supervisor decides at ordinarily. Refused when equal or
     *     higher, because
     *     a floor whose two policies decide the same way reports a lowering that changes
     *     nothing — the shape {@code TrustFloor}'s own constructor refuses in the only
     *     spelling it can detect, which is reference identity, and cannot detect here
     * @throws IllegalArgumentException if {@code tightenedThreshold} is not below the
     *     threshold this supervisor was built with
     */
    public TrustFloor floorAt(Risk tightenedThreshold) {
        Objects.requireNonNull(tightenedThreshold, "tightenedThreshold");
        if (tightenedThreshold.atLeast(approvalThreshold)) {
            throw new IllegalArgumentException("a tightened threshold of " + tightenedThreshold
                    + " is not below this supervisor's " + approvalThreshold + ", so lowering"
                    + " the floor would change nothing while reporting that it did");
        }
        return TrustFloor.afterThirdParty(at(approvalThreshold), at(tightenedThreshold));
    }

    /** One policy: this supervisor, deciding at {@code threshold}. */
    private ToolGate at(Risk threshold) {
        return new ToolGate() {
            @Override
            public boolean boundToOneRun() {
                // The enclosing supervisor's answer, for the enclosing supervisor's reason:
                // this view holds no state of its own, but it decides from state that is one
                // execution's and nobody else's.
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                return decide(tool, invocation, threshold);
            }
        };
    }

    @Override
    public GateResult evaluate(Tool tool, ToolInvocation invocation) {
        return decide(tool, invocation, approvalThreshold);
    }

    private GateResult decide(Tool tool, ToolInvocation invocation, Risk threshold) {
        Objects.requireNonNull(tool, "tool");
        // An unregistered tool is not a safe tool. "Nobody wrote a policy" and "somebody
        // decided it was harmless" must not resolve the same way. The fallback moved to
        // ToolCatalog.policyOrUnknown when RunRules became a second reader of it: two copies
        // is two places to spell the capability differently, and the disagreement would look
        // like two controls working.
        ToolPolicy policy = ToolCatalog.policyOrUnknown(tool.name());

        if (parked.get()) {
            // A plain denial and deliberately not a second needsAPerson: the approval this
            // run needs already exists, and asking again would queue a second row for one
            // decision. Nothing in this repository reaches here — both runners end the run
            // on the first park — and the class note says why it stays.
            return GateResult.deny("This execution is parked awaiting human approval and cannot "
                    + "take further action.");
        }

        Risk risk = effectiveRisk(policy, invocation);
        String effect = describeEffect(invocation);

        // Reads never need any of the machinery below, and saying so first keeps the audit
        // trail readable: an investigation is a dozen reads and two decisions, and burying
        // the decisions under a verdict per read helps nobody.
        if (risk == Risk.READ) {
            return GateResult.allow();
        }

        context.event(Execution.Event.Type.ACTION_PROPOSED, Map.of(
                "tool", tool.name(),
                "arguments", invocation.arguments(),
                "baselineRisk", policy.baselineRisk().name(),
                "effectiveRisk", risk.name(),
                "effect", effect));

        // A previously approved action: allowed once, and only if it is still the same action
        // and the world still looks the way it did when the human said yes.
        if (preApproved != null && matchesApproved(invocation)) {
            if (!preApprovedUsed.compareAndSet(false, true)) {
                return deny(tool.name(), risk,
                        "That approval has already been used once and does not authorise a repeat.");
            }
            Optional<String> changed = conditionsChanged();
            if (changed.isPresent()) {
                return deny(tool.name(), risk, "Conditions changed since approval: "
                        + changed.get() + " The approval no longer applies.");
            }
            context.event(Execution.Event.Type.ACTION_ALLOWED, Map.of(
                    "tool", tool.name(), "risk", risk.name(),
                    "reason", "Approved by " + preApproved.decidedBy(),
                    "approvalId", preApproved.id()));
            return GateResult.allow();
        }

        Optional<String> objection = reviewer.objection(goal, tool.name(), invocation.arguments(),
                risk, context.evidence(), context.readings());
        if (objection.isPresent()) {
            return deny(tool.name(), risk, objection.get());
        }

        if (risk.atLeast(threshold)) {
            return park(invocation, risk, effect, policy.reversible());
        }

        context.event(Execution.Event.Type.ACTION_ALLOWED, Map.of(
                "tool", tool.name(), "risk", risk.name(),
                "reason", "Below the approval threshold and consistent with the goal."));
        return GateResult.allow();
    }

    /**
     * The tool's baseline, raised by what the arguments actually name.
     *
     * <p>The example the whole design turns on: {@code add_user_to_group} is one row in the
     * policy table, and {@code Employees-All} and {@code Production-Administrators} are the
     * same call to it. Static metadata cannot separate them; the argument can.
     */
    private Risk effectiveRisk(ToolPolicy policy, ToolInvocation invocation) {
        Risk risk = policy.baselineRisk();
        // Escalation is about the consequence of a change, so there is nothing to escalate
        // on a read. A first version raised the risk from the arguments before asking this,
        // which made *looking up* Production-Administrators a privileged act: the run parked
        // on identity.find_group and never reached the action anyone cared about. Reading
        // about privilege is not exercising it, and a supervisor that cannot tell the
        // difference teaches agents not to check things.
        if (risk == Risk.READ) {
            return risk;
        }
        String group = invocation.stringArgument("group");
        if (group != null && identity.isPrivileged(group)) {
            risk = risk.raisedTo(Risk.HIGH);
        }
        if (!policy.reversible() && risk.atLeast(Risk.MEDIUM)) {
            risk = risk.raisedTo(Risk.HIGH);
        }
        return risk;
    }

    private String describeEffect(ToolInvocation invocation) {
        return switch (invocation.name()) {
            case "identity.add_user_to_group" -> invocation.stringArgument("user")
                    + " gains every permission granted by " + invocation.stringArgument("group") + ".";
            case "identity.remove_user_from_group" -> invocation.stringArgument("user")
                    + " loses every permission granted by " + invocation.stringArgument("group") + ".";
            case "identity.suspend_user" -> "The account " + invocation.stringArgument("email")
                    + " can no longer sign in. It can be reactivated.";
            case "identity.delete_user" -> "The account " + invocation.stringArgument("email")
                    + " and its credentials are permanently removed. Group memberships are lost "
                    + "and cannot be reconstructed from the account.";
            case "ticketing.assign_ticket" -> "Ticket " + invocation.stringArgument("ticket_id")
                    + " leaves the unassigned queue.";
            case "ticketing.add_comment" -> "A work note is added to "
                    + invocation.stringArgument("ticket_id") + " and cannot be unsaid.";
            default -> "Runs " + invocation.name() + ".";
        };
    }

    /**
     * Whether this is the call a human approved.
     *
     * <p>Compared in the form {@code ApprovedArguments} <em>showed</em> the resumed run, not
     * the raw map (#141). Fencing the arguments made the rendering lossy — NFKC folds
     * fullwidth characters — and the resumed run's only source for them is that rendering.
     * Comparing the raw map against a proposal reconstructed from the rendered one refuses a
     * run that reproduced exactly what it was told, which parks again, which a human
     * approves, which renders the same way. A loop with no exit, and worse than the
     * injection it came from: an approval nothing can consume.
     *
     * <p>One method decides both, so what is shown and what is accepted cannot drift.
     */
    private boolean matchesApproved(ToolInvocation invocation) {
        return preApproved.toolName().equals(invocation.name())
                && ApprovedArguments.asShown(preApproved.arguments())
                        .equals(ApprovedArguments.asShown(invocation.arguments()));
    }

    /**
     * Whether the world still supports the decision a human made earlier.
     *
     * <p>An approval is a judgement about a situation, not a permanent licence for a tool
     * call. Between parking and resuming, the employee may have been rehired, the account
     * may already be gone, or someone may have handled it by hand. Re-checking is the
     * difference between resuming an execution and replaying one.
     *
     * <p><strong>Asked of the invocation, not rebuilt from a map (#170).</strong> This read
     * the email as {@code String.valueOf(preApproved.arguments().get("email"))} — a fourth
     * spelling of "read a string argument" in a codebase that has
     * {@code ToolInvocation.stringArgument}, and one that cannot tell a JSON {@code null}
     * from the four-character string {@code "null"}. Measured on both inputs:
     *
     * <pre>
     *                          {"email": null}   {"email": "null"}
     * String.valueOf(get)      "null"            "null"
     * stringArgument           null              "null"
     * </pre>
     *
     * <p>So a directory holding an account whose email is the string {@code "null"} answered
     * "the account still exists" for an approval whose email was absent, and the run went on
     * to call {@code identity.delete_user} — the one irreversible operation in the catalogue
     * — with no email at all. It needs such an account to exist: the demo connector seeds
     * none and no tool in the catalogue creates one, so this was latent rather than live,
     * and it is filed that way rather than dressed up. {@code RecordsCarryTheInvocationTest}
     * seeds the account and pins the verdict, so the flip is measured rather than asserted.
     *
     * <p><strong>What this does not do.</strong> It removes the second spelling; it does not
     * close the class. {@code IdentityConnector.findUser} normalises a null email to
     * {@code ""}, so an account keyed {@code ""} reproduces the same wrong answer through the
     * new spelling. Closing it properly means refusing an approved destructive call that
     * names no account at all, which is a new control rather than #170's shape change, and
     * belongs to its own issue. The message a reviewer sees is unchanged either way: string
     * concatenation renders a null {@code email} as {@code "the account null no longer
     * exists."}, which is exactly what {@code String.valueOf} produced.
     *
     * <p><strong>Why not the recovered call id, which #170 asked for.</strong> The issue's
     * fourth item reads this method as "re-deriving intent from an argument value" and asks
     * for {@code invocation.id()} instead. Measured against what the method does: the id
     * cannot answer it. The question is whether the account named by the approved arguments
     * still exists, and only the {@code email} argument names it — an id identifies a call,
     * not a subject. What the recovered id is good for is joining an approval to the call
     * and the event that raised it, which is {@link ApprovalRequest#callId()}; what belongs
     * here is asking the invocation for its own argument instead of taking its map apart,
     * which is what this now does.
     */
    private Optional<String> conditionsChanged() {
        ToolInvocation approved = preApproved.invocation();
        String email = approved.stringArgument("email");
        if ("identity.delete_user".equals(approved.name())
                || "identity.suspend_user".equals(approved.name())) {
            if (identity.findUser(email).isEmpty()) {
                return Optional.of("the account " + email + " no longer exists.");
            }
        }
        return Optional.empty();
    }

    private GateResult deny(String toolName, Risk risk, String reason) {
        context.event(Execution.Event.Type.ACTION_REJECTED,
                Map.of("tool", toolName, "risk", risk.name(), "reason", reason));
        return GateResult.deny(reason);
    }

    /**
     * Raises the approval and stops the call, saying a person must decide (#157).
     *
     * <p><strong>The outcome is {@link GateResult#needsAPerson}, where it was
     * {@link GateResult#deny}.</strong> The two answers reach the model as the same
     * sentence — {@code needsAPerson} copies the reason into {@link GateResult#reason()}
     * precisely so a runner that predates it still says something true — and they reach the
     * <em>runner</em> as different things. See the class note for what the denial cost and
     * why the flag it set is still here.
     *
     * <p><strong>The reason no longer ends "Stop here — do not attempt an alternative route
     * to the same effect."</strong> That sentence was talking the model out of doing what
     * the loop now stops it doing, and it was addressed to the wrong reader twice over: it
     * also travels on {@link ApprovalNeeded#reason()} to the person deciding, who is not the
     * party being told to stop. What is left is the policy stating what it did.
     *
     * <p><strong>One-argument {@code needsAPerson}, not the overload that carries a
     * replacement.</strong> This gate edits nothing — it allows a call as proposed or stops
     * it — so there is no narrowing here to survive the park. Handing it {@code invocation}
     * would say "an earlier member of a composite edited this" when none did, and a
     * composite that genuinely did edit it rebuilds the outcome anyway:
     * {@code ToolGates.allOf} tracks the effective call across its members and re-wraps a
     * park with {@code needsAPerson(why, effective)} itself.
     *
     * <p><strong>{@code reversible} is claimed only when the policy claims it.</strong>
     * {@link ApprovalNeeded#reversible()} defaults to {@code false}, which its javadoc reads
     * as <em>not claimed reversible</em> rather than <em>proved irreversible</em> — the
     * answer that makes a reviewer look harder — so the {@code thatCanBeUndone()} call is
     * conditional and the default carries the safe direction. It is the same boolean the
     * {@link ApprovalRequest} already records, from {@code ToolPolicy.reversible()}.
     *
     * <p><strong>Hands {@link ApprovalRequest} the invocation whole (#170).</strong> It used
     * to take {@code tool.name()} and {@code invocation.arguments()} as two parameters, so
     * the record a human decides from was assembled here out of two sources that nothing
     * required to describe the same call — and the arguments were deep-copied a second time
     * on the way in, over a map {@code ToolInvocation} had already frozen. Both are gone;
     * see {@code ApprovalRequest} for the measurement.
     *
     * <p><strong>The recorded tool name is therefore {@code invocation.name()} where it
     * was {@code tool.name()}, and that needed measuring rather than assuming.</strong> They
     * are the same string on every path that reaches a gate: {@code Agent.runTool} resolves
     * the tool with {@code tools.find(invocation.name())}, both registries in the repository
     * key that lookup on {@code tool.name()} itself, and {@code GateResult.effectiveFor}
     * refuses a replacement that renames — so a name that resolved is a registered tool's
     * own name, not free-form text the model chose. {@code RecordsCarryTheInvocationTest}
     * pins that equality instead of leaving it to this paragraph, because the approval's
     * name is later interpolated into the resume prompt by {@code ExecutionRunner}.
     *
     * <p>The {@code HUMAN_APPROVAL_REQUESTED} event is read off {@code request} for the same
     * reason: the event and the row a reviewer opens should not be able to name different
     * tools. What stays {@code tool.name()} is the reviewer prompt three branches up, and
     * deliberately — the registry's name is a string literal in {@code ToolCatalog} and the
     * invocation's arrived over the wire, so where the two feed a model the trusted one is
     * worth keeping even while they are provably equal.
     */
    private GateResult park(ToolInvocation invocation, Risk risk, String effect,
            boolean reversible) {
        List<String> requirements = new ArrayList<>();
        requirements.add("Authorisation from a human operator");
        if (!reversible) {
            requirements.add("Confirmation that the effect is intended, since it cannot be undone");
        }
        ApprovalRequest request = new ApprovalRequest(OpsStore.Ids.next("apr"), context.tenantId(),
                context.executionId(), invocation, risk,
                risk == Risk.DESTRUCTIVE
                        ? "This action is irreversible."
                        : "This action grants or changes privileged access.",
                effect, reversible, context.evidence(), ApprovalRequest.State.PENDING,
                Instant.now(), null, null, null);
        store.save(request);
        parked.set(true);
        context.event(Execution.Event.Type.HUMAN_APPROVAL_REQUESTED, Map.of(
                "approvalId", request.id(), "tool", request.toolName(), "risk", risk.name(),
                "effect", effect, "arguments", request.arguments()));
        ApprovalNeeded why = ApprovalNeeded
                .because("This action requires human approval and has been submitted for "
                        + "review (" + request.id() + ").")
                .withEffect(effect);
        return GateResult.needsAPerson(reversible ? why.thatCanBeUndone() : why);
    }
}
