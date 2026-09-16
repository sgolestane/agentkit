package dev.agentkit.itops.runtime;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reflect.Correction;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.tools.PlatformTools;
import dev.agentkit.itops.tools.ToolCatalog;
import dev.agentkit.itops.tools.ToolPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Two sentences of the system prompt, turned into gates that hold this run to them.
 *
 * <p>{@code ExecutionRunner.SYSTEM_PROMPT} makes five demands of the model and, until these
 * existed, nothing checked any of them. Every one was a request to a model — including
 * <em>"if an action is refused, stop; do not look for another route to the same effect"</em>,
 * which is a security control written as a sentence in a prompt. The framework has had both
 * halves of a better answer since #310 and #181: a record of a run's own settled calls, and
 * the shape of a gate that closes over it, declares {@link ToolGate#boundToOneRun()}, and can
 * only ever {@code deny} or {@code allow}.
 *
 * <h2>Which of the five are here, and why the other three are not</h2>
 *
 * <p>Two are enforced. Three are deliberately not, and the reasons are different in each
 * case; they are checked in {@code agentkit-eval} instead, where a case knows things the
 * runtime does not and where the report <em>is</em> the deliverable.
 *
 * <ul>
 *   <li><strong>Enforced — "if an action is refused, stop"</strong>, as
 *       {@link #noRouteAroundARefusal}. The one of the five that is a security control rather
 *       than a habit, and the one where a prose request costs the most when it is not
 *       honoured.</li>
 *   <li><strong>Enforced — "say whether you can do the job before you start doing it"</strong>,
 *       as {@link #capabilityDeclaredFirst}. It is the ordering demand every run can satisfy
 *       on every path — {@code report_capability} is {@link Risk#READ}, is always disclosed,
 *       and never parks — so unlike the one below it needs no "does not apply here" input.
 *       And the verdict has a consequence: {@code IntakeWorker.processingStatus} reads
 *       {@code Outcome.capability()} and files a ticket {@code SKIPPED_UNSUPPORTED} on it, so
 *       a run that changed something without ever declaring leaves that branch reading
 *       {@code null}.</li>
 *   <li><strong>Not enforced — "take ownership before you change anything downstream".</strong>
 *       The harm it reads as protecting against is two workers on one ticket, and
 *       {@code OpsStore.claimTicket} already closes that: one winner, decided in a
 *       compare-and-set, with no model in the loop. {@code ticketing.assign_ticket} is that
 *       claim's <em>external</em> reflection, and a second, weaker answer to a question
 *       already answered is not worth a gate. What settles it is that the precondition is
 *       unsatisfiable on two ordinary paths — a chat run with no ticket, and a resume, whose
 *       fresh {@link RunHistory} never saw the assignment the parked attempt made — so the
 *       gate would have to be told when it does not apply, by the same wiring that builds it.
 *       A control with an off switch set at its own call site is not a control.</li>
 *   <li><strong>Not enforced — "read before you act".</strong> A gate requiring
 *       {@code ticketing.get_ticket} would deny the first write of every <em>scheduled</em>
 *       run, which is the majority path: {@code IntakeWorker.goalFor} fences the whole ticket
 *       into the goal, so the run has read it and correctly never calls the tool. The rest of
 *       the sentence — "establish the facts it depends on from the systems that own them" —
 *       names no call at all.</li>
 *   <li><strong>Not enforced, and no runtime report either — "verify afterwards".</strong>
 *       A gate cannot reach it: a run that fails to read something back does so by making no
 *       call, and there is nothing to refuse — the same half of a declaration
 *       {@code Conformance}'s javadoc shows nothing can enforce. A report is reachable and
 *       every formulation available to <em>this</em> module is worse than none. "Was there a
 *       later successful read on the same connector" fires on every correct run, because the
 *       {@code add_comment}/{@code resolve}/{@code close} tail of a finished ticket is never
 *       read back, and a report that fires on correct behaviour trains a reviewer to skim
 *       past the line that matters (#316). Anything narrower needs a per-tool table saying
 *       which read confirms which write, which this module does not have and would be
 *       guessing. An eval case does know — for the routine access request it is
 *       {@code identity.get_group_members} after {@code identity.add_user_to_group} — which
 *       is why the check lives there and reports rather than pretending to prevent.</li>
 * </ul>
 *
 * <h2>A gate over a run's own history can only take away</h2>
 *
 * <p>The same structural argument {@code Conformance.holdingTo} makes, and it is what lets
 * these compose with {@link Supervisor} safely: every gate here returns {@code deny} or
 * {@code allow} and nothing else, they are composed through {@code ToolGates.allOf}, and
 * {@code allOf} requires every member to allow. So they subtract from what the deployment
 * permitted and can never add to it. Neither ever parks: there is nobody for a park to ask —
 * the party that could fix a call the run's own history has closed off is the run — and a
 * denial is a result the model reads and can act on. Both denial reasons therefore say what
 * to do instead, because a refusal a model cannot act on is a loop.
 *
 * <h2>They go before the supervisor, and that is measured rather than stylistic</h2>
 *
 * <p>{@code allOf} short-circuits on a denial and deliberately does <em>not</em>
 * short-circuit on a park. Measured on one execution whose model proposes
 * {@code identity.suspend_user} after a refusal in the same capability, with the approval
 * threshold at {@code HIGH}:
 *
 * <pre>
 * order                          approval rows raised   supervisor left parked   tool ran
 * allOf(rules…, supervisor)      0                      no                       no
 * allOf(supervisor, rules…)      1  (PENDING, for ever) yes                      no
 * </pre>
 *
 * <p>Behind the second row: the supervisor grades the call {@code HIGH}, writes an
 * {@link dev.agentkit.itops.domain.ApprovalRequest}, sets its own {@code parked} flag and
 * returns {@code needsAPerson}; the chain runs on, a rule denies, and the denial wins. The
 * call is stopped either way — the guarantee holds in both orders, which is why this is an
 * ordering choice and not a hole — but a reviewer is left holding a row authorising a call
 * that can never run, and the supervisor refuses everything afterwards. Cheaper and quieter
 * first.
 *
 * @see RunHistory
 */
public final class RunRules {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RunRules.class);

    private RunRules() {
    }

    /**
     * Refuses any call reaching a {@link ToolPolicy#capability()} this run has already been
     * refused at.
     *
     * <p><strong>What changes.</strong> Before this, "if an action is refused, stop, do not
     * look for another route to the same effect" was enforced by asking. Measured on one
     * execution against the seeded connectors, with a model that is refused
     * {@code identity.remove_user_from_group} on a group the objective never names and then
     * proposes {@code identity.add_user_to_group} — a different tool, the same declared
     * capability {@code identity.group_membership.write}:
     *
     * <pre>
     *                      second call graded   second call ran   group membership changed
     * without this gate    MEDIUM, allowed      yes               yes
     * with it              refused              no                no
     * </pre>
     *
     * <p>The second call is individually legitimate: its target is named in the objective, so
     * the goal-alignment screen clears it, and {@code MEDIUM} is below the approval line. It
     * is stopped by what the run had already been told, which is the whole of the control.
     *
     * <p><strong>The unit is the capability, not the effect.</strong> Nothing here can tell that two calls have the same effect, and a control that
     * claimed to would be guessing. What it can tell is that they reach the same declared
     * capability, which is the string {@code ToolCatalog} indexes, {@code search_tools}
     * searches on, and every tool description opens with — and a route around a refusal is,
     * by construction, a second tool in the family of the first. That is deliberately wider
     * than "the same effect": being refused {@code identity.delete_user} also closes
     * {@code identity.suspend_user}, which is a different and lesser act. Wider in the safe
     * direction, and the run is told to stop rather than to try something smaller, which is
     * what the prompt asked for in the first place.
     *
     * <p>It is also <em>narrower</em> than "stop", and that is the residual: a refusal at
     * {@code identity.group_membership.write} leaves {@code ticketing.write} open, so a
     * refused run can still comment on and close its ticket — which is what it should do, and
     * is why this is not spelt "one refusal ends the run".
     *
     * <p><strong>A tool nobody wrote a policy for.</strong> Falls back to the capability {@code unknown}, through
     * {@link ToolCatalog#policyOrUnknown} — the same fallback the supervisor grades
     * {@code HIGH} on, in one place so the two cannot come to disagree. One consequence is
     * worth naming: a refused undeclared tool closes <em>every</em> undeclared tool for the
     * rest of the run, because they share that fallback string. That is the fail-closed
     * direction, and it matches the supervisor's own reading — "nobody wrote a policy" and
     * "somebody decided it was harmless" must not resolve the same way.
     *
     * <p><strong>Stickiness does not survive a resume, and that is a decision.</strong>
     * {@link ExecutionRunner#run} takes a {@code preApproved}, a resume is a fresh
     * {@code Execution} row and a fresh agent, and it therefore gets a fresh
     * {@link RunHistory} with nothing burned. Kept that way, for three reasons:
     *
     * <ul>
     *   <li><strong>A refusal is not what a resume comes back from.</strong>
     *       {@link dev.agentkit.core.tool.Disposition#REFUSED} is terminal and raises no
     *       approval; the only thing a human can approve is a park. So carrying a refusal
     *       across a resume would be carrying a decision that nobody was asked to revisit
     *       into a run that exists because somebody was asked about something else.</li>
     *   <li><strong>Carrying it needs durable state and has no expiry.</strong> The burned
     *       set would have to be written to {@link dev.agentkit.itops.store.OpsStore} and
     *       keyed to the ticket rather than to the run, and nothing in this module can say
     *       when it stops applying. One spurious refusal would then disable a capability for
     *       every later attempt at that ticket, permanently, with no operator surface to
     *       clear it — a control whose failure mode is silent and unbounded.</li>
     *   <li><strong>The resumed run is judged again from scratch anyway.</strong> The
     *       goal-alignment screen is deterministic in the objective, the proposal and the
     *       evidence, so a call refused in the first attempt is refused again in the second —
     *       by the reason it was refused for, not by a memory of having been refused. What
     *       does not carry across is stickiness at <em>other</em> tools in the family, and
     *       that is the honest size of what is given up.</li>
     * </ul>
     *
     * <p><strong>What it costs, said rather than glossed.</strong> Inside one resumed run,
     * a refusal at the approved call's capability that lands <em>before</em> the approved
     * call denies it — this gate runs ahead of the supervisor, so it never reaches the
     * branch that recognises a pre-approval. The approval is already marked
     * {@code APPROVED} by then, so it cannot be spent later either. Not reachable in this
     * module today: the resumed plans reach their approved call after reads only, and reads
     * are never refused. Filed as latent rather than dressed up, and the direction is
     * fail-closed — an approved call refused, with a row saying so, rather than an
     * unapproved one running. Closing it means teaching this gate what the supervisor's
     * {@code matchesApproved} means, which is a second spelling of a comparison #141 put in
     * one place on purpose.
     */
    public static ToolGate noRouteAroundARefusal(RunHistory history) {
        Objects.requireNonNull(history, "history");
        return new ToolGate() {
            @Override
            public boolean boundToOneRun() {
                // Stated rather than inherited. This reads one execution's history, and the
                // durable runner -- which has no observer at all, so this record would be
                // permanently empty there -- must refuse it at registration rather than
                // clearing every call while reporting a control.
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Objects.requireNonNull(tool, "tool");
                String capability = ToolCatalog.policyOrUnknown(invocation.name()).capability();
                if (!history.refusedCapabilities().contains(capability)) {
                    return GateResult.allow();
                }
                // The capability is a string literal in ToolCatalog, or the literal
                // "unknown"; neither came from the model, so neither needs quoting on the
                // way back into a sentence this framework wrote. The tool name did come
                // from the model and is deliberately not in this message.
                return GateResult.deny("An earlier action reaching " + capability
                        + " was refused on this run, so nothing else in that capability may"
                        + " run either. Do not look for another route to the same effect:"
                        + " record what you found and stop.");
            }
        };
    }

    /**
     * A capability a person refused and meant it about stays refused, across runs (#329).
     *
     * <p>{@link #noRouteAroundARefusal} keeps a refusal sticky <em>within</em> one run, on
     * exactly this key. This is the same idea across runs, with a person rather than a gate
     * as the source — and it exists because advisory text could not survive this module's
     * own system prompt, which tells the model that a privileged change needing approval
     * "is not your cue to stop: propose the change anyway". A recorded objection folded into
     * the user turn as advice loses to that, every time, and the run parks again and costs
     * the same person the same decision. Which is the cost #329 exists to reduce.
     *
     * <p>That is the argument this class was written to make: "if an action is refused,
     * stop" was a security control written as a sentence in a prompt, and a sentence is not
     * a control.
     *
     * <p><strong>Only corrections a person marked as standing.</strong> Most rejections mean
     * "not this one", and a gate built from every refusal would let the first routine one
     * disable a capability for good. {@code CorrectionBook.record} takes that as a choice
     * made when refusing.
     *
     * <p><strong>The denial names its remedy.</strong> A control that can only ever be added
     * turns one mistaken refusal into permanent policy, which is a worse failure than the
     * one it closes — so the message says who set it and that it can be lifted, because the
     * person who reads it in a trail is the person who can.
     *
     * <p>Deny or allow, never park, like everything else here: it subtracts from what the
     * deployment already permits and can add nothing.
     */
    public static ToolGate honourStandingRefusals(CorrectionBook corrections, String tenantId) {
        return honourStandingRefusals(corrections, tenantId, null);
    }

    /**
     * As above, deferring to an approval a person has just granted.
     *
     * @param justApproved a capability a person approved for this very run, which this gate
     *     therefore does not refuse — their approval is a newer and more specific answer than
     *     the standing refusal, and denying it produced a run that reported {@code COMPLETED}
     *     with the approved action undone, the approval spent and the ticket closed
     */
    public static ToolGate honourStandingRefusals(CorrectionBook corrections, String tenantId,
            String justApproved) {
        Objects.requireNonNull(corrections, "corrections");
        Objects.requireNonNull(tenantId, "tenantId");
        // Read once per capability per run, not once per call. This gate declares
        // boundToOneRun, so its lifetime IS the run, and a correction recorded mid-run
        // belongs to a rejection that has not happened yet. Without the cache every gated
        // call took a synchronized store read: measured over one tick of five tickets
        // against an EMPTY book, 50 reads became 86, and under FileMemoryStore each is a
        // filesystem read taken under the same monitor that serialises an HTTP thread's
        // record().
        java.util.Map<String, List<Correction>> seen = new java.util.HashMap<>();
        return new ToolGate() {
            @Override
            public boolean boundToOneRun() {
                // One tenant's standing refusals, fixed when this was built. A durable
                // worker serving every tenant on a queue would apply one tenant's policy to
                // another's calls -- the same reason its sibling above says true.
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Objects.requireNonNull(tool, "tool");
                String capability = ToolCatalog.policyOrUnknown(invocation.name()).capability();
                if (capability.equals(justApproved)) {
                    return GateResult.allow();
                }
                List<Correction> standing = seen.computeIfAbsent(capability,
                        one -> corrections.standing(tenantId, List.of(one)));
                if (standing.isEmpty()) {
                    return GateResult.allow();
                }
                Correction first = standing.get(0);
                // The capability and the marker are literals or Source qualifiers; the note
                // is the operator's own words and is the one part that is not, so it is
                // fenced on its way into a message the model reads.
                Spotlight.Bounded said = Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE,
                        Source.of("operator", first.decidedBy()), first.note(),
                        DENIAL_NOTE_CHARS);
                if (said.cut()) {
                    log.info("An operator's reason on {} was cut to {} characters on its way"
                            + " into a denial", capability, DENIAL_NOTE_CHARS);
                }
                return GateResult.deny("A person refused this capability (" + capability
                        + ") and asked that it keep applying. Do not look for another route"
                        + " to the same effect: record what you found and stop. What they"
                        + " said, and who:\n"
                        + said.fence()
                        + "\nAn operator can lift this if it no longer applies.");
            }
        };
    }

    /** How much of an operator's reason travels into a denial the model reads. */
    private static final int DENIAL_NOTE_CHARS = 400;


    /**
     * Refuses anything that changes something until {@code report_capability} has run.
     *
     * <p>The prompt's second demand, which asks the model to say whether it can do the job
     * before it starts doing it. {@code PlatformTools} exists for exactly this shape — "when
     * a model's conclusion has consequences, make it arrive through the tool boundary like
     * everything else" — and the conclusion had consequences while nothing required it to
     * arrive at all.
     *
     * <p><strong>Reads are untouched</strong>, and the line is {@link Risk#READ} on the
     * tool's own {@link ToolPolicy}, not a guess from the name. Declaring a capability before
     * having looked anything up would be the opposite of the prompt's first demand, and the
     * two would deadlock: {@code search_tools} is how the identity tools are found at all.
     * {@code report_capability} is itself {@code READ}, so it can never be refused by this.
     *
     * <p><strong>Succeeded, not attempted.</strong> {@code report_capability} returns
     * {@code ToolResult.error(...)} for a verdict outside its four words, and a run that
     * called it wrongly has recorded nothing —
     * {@code context.fact("capability")} is unset and {@code Outcome.capability()} still
     * reads {@code null}. Keying on the attempt would let a malformed call discharge the
     * requirement it exists to create.
     *
     * <p><strong>The denial is a detour, not a wall.</strong> It names the tool to call and
     * says the work can continue afterwards, so the cost is one model turn on a run that was
     * about to change something without declaring — and zero on a run that follows the
     * prompt, which is every scripted path in this module.
     */
    public static ToolGate capabilityDeclaredFirst(RunHistory history) {
        Objects.requireNonNull(history, "history");
        return new ToolGate() {
            @Override
            public boolean boundToOneRun() {
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Objects.requireNonNull(tool, "tool");
                ToolPolicy policy = ToolCatalog.policyOrUnknown(invocation.name());
                if (policy.baselineRisk() == Risk.READ
                        || history.succeeded(PlatformTools.REPORT_CAPABILITY)) {
                    return GateResult.allow();
                }
                return GateResult.deny("Say whether you can do this job before you start doing"
                        + " it: call " + PlatformTools.REPORT_CAPABILITY + " with SUPPORTED,"
                        + " UNSUPPORTED, NEEDS_MORE_CONTEXT or NEEDS_HUMAN, then carry on.");
            }
        };
    }

    /**
     * The rules this module holds every execution to, in the order they are asked.
     *
     * <p>One place, so {@link ExecutionRunner} composes a policy rather than a list and a
     * later rule cannot be added to one runner and forgotten in another. The supervisor is
     * <em>not</em> in here: it is built per execution with its own reviewer, threshold and
     * pre-approval, and folding it in would hide the ordering the class note measures.
     */
    public static ToolGate holdingTo(RunHistory history) {
        return ToolGates.allOf(noRouteAroundARefusal(history), capabilityDeclaredFirst(history));
    }
}
