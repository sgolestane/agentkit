package dev.agentkit.core.plan;

import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.supervisor.SubagentTools;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.verify.VerifierTools;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A run's declaration held against what the run actually did (#310), and the one gate that
 * can act on the answer while the run is still going.
 *
 * <h2>What this ships, and what it deliberately does not</h2>
 *
 * <p>#310 offered three directions and this is two of them. The third is refused.
 *
 * <ul>
 *   <li><strong>Shipped: the record and the comparison.</strong> {@link RunLedger} and
 *       {@link #check} were written by hand in {@code SelfWiringAgent}, and the piece a
 *       deployment gets wrong re-deriving them is first-declaration-wins. This adds no power
 *       to intervene and says so.</li>
 *   <li><strong>Shipped: a gate bound to one run</strong> — {@link #holdingTo(RunLedger)}.
 *       Read the next section before believing it does more than it does.</li>
 *   <li><strong>Refused: history on {@code ToolGate.evaluate}.</strong> #310 called it "the
 *       direct repair and the one with the largest blast radius", and the measurement is
 *       that it is not needed: a gate that reads the run's trace is expressible today by
 *       closing over the ledger, which is what {@link #holdingTo} does, and
 *       {@code ToolGates.screeningAgainst} already ships a run-bound gate built exactly that
 *       way. What contains the blast radius already exists too —
 *       {@link ToolGate#boundToOneRun()}, which {@code ToolActivitiesImpl} refuses at
 *       registration, because a worker built once per task queue would otherwise judge every
 *       run on it against one run's trace. Putting a trace parameter on
 *       {@code evaluate} instead would give <em>every</em> gate in the repository a
 *       history argument that the durable runner has nothing to fill, so a gate written
 *       against it would compile, register, and screen against an empty trace — which is
 *       failing open at the boundary the whole seam exists to close.</li>
 * </ul>
 *
 * <h2>Exactly what {@link #holdingTo} can stop, and what nothing can</h2>
 *
 * <p>A declaration has two halves and only one of them is reachable from a gate.
 *
 * <ul>
 *   <li><strong>Reachable: doing something the plan did not sanction.</strong> A delegation
 *       to a subagent the plan never named is a tool call, a gate is asked before a tool
 *       call, and so it can be refused before it happens. This is the half where
 *       "prevented" means anything at all, because it is the half where something the run
 *       did not declare would otherwise <em>occur</em>.</li>
 *   <li><strong>Unreachable: failing to do what the plan promised.</strong> "You said you
 *       would delegate to the researcher and this is your eighth step without doing it" —
 *       #310's own sentence — cannot be enforced by a gate, and not because a gate lacks
 *       the trace. A gate is only ever asked about <em>a call</em>, and a run that breaks
 *       this half of its word does so by making no call: it stops and answers. There is
 *       nothing to refuse. {@code SelfWiringAgentTest.reportsAPromiseItDidNotKeep} pins that
 *       honestly — {@code COMPLETED}, with the answer returned, alongside a broken
 *       promise — and it is still true with this gate wired.</li>
 * </ul>
 *
 * <p><strong>The size of that first half, measured.</strong> Replacing {@link #holdingTo}'s
 * body with {@code GateResult.allow()} and running the whole suite fails exactly five tests,
 * and every one of them asserts that a call <em>did not happen</em> — three here, and in
 * {@code SelfWiringAgentTest} the two that check a {@code REFUSED} row and an untouched
 * roster. Not one report-level assertion moves: the same divergences are reported either
 * way, because a run that tried and was refused still tried. That is the difference between
 * preventing and reporting, as a number rather than as a claim.
 *
 * <p>So the gap #310 opened is <em>narrowed and not closed</em>, and the residual is stated
 * rather than glossed: the omission half is reported after {@code onFinish}, where its report
 * is the deliverable. A deployment that needs an omission to be consequential can put the
 * whole agent inside a re-running wrapper the way {@code SelfVerifyingAgent} does; the
 * framework does not ship that here, because a re-run cannot undo what the first run already
 * did, and a seam that sounds like prevention while delivering a retry is the kind of
 * over-claim #310 explicitly asked for less of.
 *
 * <h2>A criterion the model wrote can only take away</h2>
 *
 * <p>The obvious objection to a gate built from a model's own declaration is that the model
 * is then writing its own policy. It is not, and the reason is structural rather than a
 * matter of care: {@link #holdingTo} only ever returns {@code deny} or {@code allow}, it is
 * composed with the coding-time policy through {@code ToolGates.allOf}, and {@code allOf}
 * requires <em>every</em> member to allow. A declaration can therefore subtract from what the
 * deployment permitted and can never add to it. A run that declares "I will use the shredder"
 * is refused by the shredder's own arm exactly as before.
 *
 * @param findings everything a reviewer is told, in the order the check produced it
 */
public record Conformance(List<Finding> findings) {

    /** One thing the reviewer is told about the run. */
    public record Finding(Kind kind, String what) {

        public Finding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(what, "what");
        }
    }

    /**
     * Why a finding is being reported — the distinction #316 exists for.
     *
     * <p>Collapsing these two loses what makes either useful. Before #316 there was one
     * arm and it fired on both, so a run that used the sanctioned {@code spawn_subagent}
     * capability exactly as designed was reported as having broken its promise. A report
     * that fires on correct behaviour is worse than no report, because it trains a reviewer
     * to skim past the line that matters.
     */
    public enum Kind {

        /** The run did not do what it said it would. {@link #keptItsWord()} counts these. */
        BROKE_ITS_WORD,

        /**
         * The run did something a reviewer should see, and did not break its word doing it.
         *
         * <p>Delegating to a specialist it built mid-run is the case: the plan could not have
         * named it, because it did not exist when the plan was declared (#308, #316). Worth
         * seeing — a run inventing a collaborator is not a footnote — and not a divergence.
         */
        WORTH_A_LOOK
    }

    public Conformance {
        findings = List.copyOf(findings);
    }

    /** True when the run did what it said it would. Notes do not count against it. */
    public boolean keptItsWord() {
        return brokenPromises().isEmpty();
    }

    /** The findings that say the run did not do what it said it would. */
    public List<Finding> brokenPromises() {
        return findings.stream().filter(f -> f.kind() == Kind.BROKE_ITS_WORD).toList();
    }

    /** The findings a reviewer should see that are not a broken promise (#316). */
    public List<Finding> notes() {
        return findings.stream().filter(f -> f.kind() == Kind.WORTH_A_LOOK).toList();
    }

    /**
     * Holds {@code trace} against {@code declared}.
     *
     * <p>Runs after {@code onFinish} rather than inside an observer, because an
     * {@code AgentObserver} returns {@code void} on every callback and cannot stop anything:
     * the honest place for a check with no power to intervene is after the run, where its
     * report is the deliverable. {@link #holdingTo(RunLedger)} is the half of this that can
     * act, and its javadoc says which half that is.
     *
     * <p>Reads {@link RunLedger.Step#kind()} and never a tool name, so a deployment whose
     * subagent-building tool is called something else needs to change nothing here.
     *
     * @param declared       the declaration the run is held to, if it made one
     * @param redeclarations later declarations, which the run is not held to
     * @param trace          every settled call, attributed
     */
    public static Conformance check(Optional<DeclaredPlan> declared,
                                    List<DeclaredPlan> redeclarations,
                                    List<RunLedger.Step> trace) {
        List<Finding> out = new ArrayList<>();
        if (declared.isEmpty()) {
            out.add(broke("The run never declared a plan, so there is nothing to hold it to."));
            return new Conformance(out);
        }
        DeclaredPlan plan = declared.get();
        for (DeclaredPlan later : redeclarations) {
            out.add(broke("The plan was re-declared mid-run: " + later.rationale()));
        }

        Set<String> delegatedTo = new LinkedHashSet<>();
        Set<String> triedToDelegateTo = new LinkedHashSet<>();
        Set<String> built = new LinkedHashSet<>();
        Set<String> triedToBuild = new LinkedHashSet<>();
        boolean verified = false;
        for (RunLedger.Step step : trace) {
            switch (step.kind()) {
                case DELEGATION -> {
                    if (step.target() != null) {
                        // happened(), not "the row exists": a delegation the gate refused is
                        // a delegation that did not occur, and a conformance check calibrated
                        // on proposals reports a promise kept that the gate stopped it
                        // keeping.
                        (step.happened() ? delegatedTo : triedToDelegateTo).add(step.target());
                    }
                }
                case SUBAGENT_BUILD -> {
                    if (step.target() != null) {
                        // succeeded(), not happened(): a build refused for a duplicate name
                        // ran and created nothing.
                        (step.succeeded() ? built : triedToBuild).add(step.target());
                    }
                }
                case VERIFICATION -> verified |= step.happened();
                case OTHER -> {
                    // Recorded in the trace, judged by nothing here: a plan says which
                    // subagents and whether to verify, and every other tool is the
                    // coding-time policy's business.
                }
            }
        }
        triedToDelegateTo.removeAll(delegatedTo);
        triedToBuild.removeAll(built);

        for (String name : plan.subagents()) {
            if (!delegatedTo.contains(name)) {
                out.add(broke("Promised to use subagent " + quoted(name) + " and did not."));
            }
        }
        for (String name : delegatedTo) {
            if (plan.named(name)) {
                continue;
            }
            // The whole of #316. A name this run built could not have been in a plan
            // declared before it existed, so routing to it is not the same event as routing
            // around the plan to a subagent that was there all along.
            out.add(built.contains(name)
                    ? note("Used subagent " + quoted(name) + ", which this run built.")
                    : broke("Used subagent " + quoted(name)
                            + ", which the plan did not name."));
        }
        for (String name : triedToDelegateTo) {
            if (!plan.named(name) && !built.contains(name)) {
                out.add(broke("Tried to use subagent " + quoted(name)
                        + ", which the plan did not name; the call did not run."));
            }
        }
        if (!plan.spawns()) {
            for (String name : built) {
                out.add(broke("Built subagent " + quoted(name)
                        + " after declaring it would not build any."));
            }
            for (String name : triedToBuild) {
                out.add(broke("Tried to build subagent " + quoted(name)
                        + " after declaring it would not build any; nothing was created."));
            }
        }
        if (plan.verifies() && !verified) {
            out.add(broke("Promised to verify its answer and never called "
                    + VerifierTools.VERIFY_CLAIM + "."));
        }
        return new Conformance(out);
    }

    /**
     * A gate that refuses the calls a run's own declaration did not sanction.
     *
     * <p>Read the class javadoc for the half of a declaration this reaches and the half
     * nothing does. In one line: it can stop the run doing something it did not declare, and
     * it cannot make the run do something it did.
     *
     * <p>Two arms, both silent until the run has declared something — before that there is
     * nothing to hold it to, and {@link #check} reports the missing declaration afterwards:
     *
     * <ul>
     *   <li>A {@link SubagentTools#DELEGATE} to a subagent the plan did not name and this run
     *       did not build.</li>
     *   <li>A call to one of {@link RunLedger#subagentBuildingTools()} when the plan declared
     *       {@code spawns == false} (#316).</li>
     * </ul>
     *
     * <p>Denies rather than parks. A declaration is the model's own, so there is nobody for a
     * park to ask: the party that can fix a call outside the plan is the party that wrote the
     * plan, and a denial is a result it reads and can act on.
     *
     * <p><strong>Bound to one run</strong>, and it says so through
     * {@link ToolGate#boundToOneRun()}, so a durable worker refuses it at registration rather
     * than judging every run on the worker against one run's ledger. That is not a formality
     * here: the ledger is filled by an {@code AgentObserver} and the durable path has no
     * observer at all, so this gate on a Temporal worker would read an empty trace and clear
     * every call while reporting a control. Loud at registration beats wrong in production —
     * the same trade {@code ToolGates.screeningAgainst} already makes.
     *
     * <p><strong>Which way it fails when the record is incomplete.</strong> Observer failures
     * are absorbed ({@code Observations.ran}), so a row can go missing. A missing build row
     * makes {@link RunLedger#builtSubagents()} smaller, and a smaller set makes the first arm
     * <em>deny</em> a delegation it would have allowed. A missing delegation row changes no
     * later decision. So a lost row costs the run a call it could have made, rather than
     * letting through one it could not — which is the direction a control has to fail in.
     *
     * @param ledger the run's own record, read live: a build settles one step before the
     *     delegation that uses it, so the name is already there when that delegation is gated
     */
    public static ToolGate holdingTo(RunLedger ledger) {
        Objects.requireNonNull(ledger, "ledger");
        return new ToolGate() {
            @Override
            public boolean boundToOneRun() {
                // Stated rather than inherited. This gate reads one run's ledger, and a
                // runner that serves more than one run must refuse it.
                return true;
            }

            @Override
            public GateResult evaluate(Tool tool, ToolInvocation invocation) {
                Objects.requireNonNull(tool, "tool");
                Optional<DeclaredPlan> declared = ledger.declaredPlan();
                if (declared.isEmpty()) {
                    return GateResult.allow();
                }
                DeclaredPlan plan = declared.get();
                if (SubagentTools.DELEGATE.equals(invocation.name())) {
                    String target = invocation.stringArgument("subagent");
                    if (target != null && !plan.named(target)
                            && !ledger.builtSubagents().contains(target)) {
                        // Quoted, because these names came out of the model and go back into
                        // a sentence this framework wrote (#278).
                        return GateResult.deny("Your plan named " + Quoted.each(plan.subagents())
                                + ", and " + quoted(target) + " is not one of them. Delegate"
                                + " to one you named, or answer with what you have.");
                    }
                }
                if (ledger.subagentBuildingTools().contains(invocation.name())
                        && !plan.spawns()) {
                    return GateResult.deny("You declared that you would not build subagents."
                            + " Delegate to one you named, or answer with what you have.");
                }
                return GateResult.allow();
            }
        };
    }

    /**
     * How many characters of a model-written subagent name reach a sentence about it.
     *
     * <p>{@code Spotlight}'s own ceiling is 40, so a name that got past a spawning tool is
     * inside it already. A name in a {@link DeclaredPlan} went through no such check — the
     * model may declare a subagent it never delegates to, and nothing refuses the string —
     * so the bound is applied here rather than assumed.
     */
    private static final int MAX_NAME_CHARS = 60;

    /**
     * A model-written name inside quotes it cannot close.
     *
     * <p>{@link Quoted#distinguishably} rather than {@link Quoted#of}, which is the shape
     * {@link Quoted#each(List)} uses per element and for the same reason: these strings go
     * back to the model in a sentence this framework wrote, and {@code of} deliberately
     * leaves the apostrophe alone, so a name shaped like an opened and re-opened quote would
     * show the reader a clause nobody wrote (#278).
     */
    private static String quoted(String name) {
        return "'" + Quoted.distinguishably(name, MAX_NAME_CHARS) + "'";
    }

    private static Finding broke(String what) {
        return new Finding(Kind.BROKE_ITS_WORD, what);
    }

    private static Finding note(String what) {
        return new Finding(Kind.WORTH_A_LOOK, what);
    }
}
