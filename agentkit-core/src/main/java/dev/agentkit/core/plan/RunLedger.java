package dev.agentkit.core.plan;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.supervisor.SubagentTools;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.verify.VerifierTools;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The record a run is held against: what it declared, and every call that settled (#310).
 *
 * <p>This was written by hand in {@code SelfWiringAgent} and is here because every
 * deployment that wants a run held to its own declaration writes the same three pieces — a
 * declaration slot, an attributed trace, and a comparison — and the detail that is easy to
 * get wrong is not any of the three. It is <strong>first declaration wins</strong>. Last-wins
 * lets a run rewrite its promise after seeing how the run went, which makes the declaration
 * worth nothing; {@link #declare} enforces first-wins with a compare-and-set and keeps the
 * later one, because a model that changed its mind is exactly what a reviewer wants to see.
 *
 * <h2>What it can and cannot do</h2>
 *
 * <p>An {@link AgentObserver} is a witness. Every callback returns {@code void} and a throw
 * out of one is absorbed by {@code Observations.ran}, so nothing here stops anything, and
 * that is the right contract — {@code Observations}' own javadoc argues it. The intervening
 * is {@link Conformance#holdingTo(RunLedger)}'s, which is a {@code ToolGate} reading this
 * record, and its javadoc states exactly which half of a declaration a gate can hold a run
 * to and which half nothing can.
 *
 * <p>One consequence worth knowing before building a control on this: because observer
 * failures are absorbed, this record is <em>allowed to be incomplete</em>. A deployment for
 * which that matters sets {@code Agent.Builder.onObservationFailure}. The gate is built so
 * that a lost row makes it refuse a call it would have allowed rather than the other way
 * round — see {@link Conformance#holdingTo(RunLedger)}.
 *
 * <h2>One ledger, many runs</h2>
 *
 * <p>Wire one of these into a supervisor <em>and</em> into every agent its subagents build.
 * That is the only way "what did this run touch" has one answer, and since #311 each row
 * says which of them made the call, so a call a gate stopped inside a child is attributed to
 * that child rather than to nobody.
 *
 * <p>One ledger is <strong>one run</strong>. It accumulates, and it holds the first
 * declaration it is given for the life of the object, so a second run through the same
 * object would be judged against the first run's promise. Build a fresh one per goal.
 *
 * <p>Thread-safe: a subagent may run on another thread, and a {@code declare_plan} handler
 * writes from the tool thread while the observer callbacks write from the agent loop.
 */
public final class RunLedger implements AgentObserver {

    /**
     * What a settled call meant to a plan, as far as this ledger can tell.
     *
     * <p>Classified here rather than in {@link Conformance} so that the comparison needs no
     * tool names at all. Two of the four are core's own tools; the third is not, because the
     * framework ships no {@code spawn_subagent} — which subagent-building tool a deployment
     * has, and what it calls the argument carrying the new name, are the deployment's
     * (#313), so they are named at construction.
     */
    public enum Kind {

        /** {@link SubagentTools#DELEGATE} — {@link Step#target()} is the subagent asked. */
        DELEGATION,

        /**
         * A tool the deployment named as building a subagent — {@link Step#target()} is the
         * name it was asked to create (#308).
         */
        SUBAGENT_BUILD,

        /** {@link VerifierTools#VERIFY_CLAIM} — the run had its answer checked. */
        VERIFICATION,

        /** Everything else. Recorded, because a trace with holes is not a trace. */
        OTHER
    }

    /**
     * One settled tool call, as the observer saw it, and whose call it was.
     *
     * <p>{@code run} is what makes a shared trace readable as one trace (#311). A
     * supervisor's rows and its children's arrive interleaved — synchronously, so a child's
     * calls land <em>before</em> the {@code delegate} that caused them — and {@code step}
     * restarts at 1 in every child, so without an identity a supervisor's step 3 and a
     * researcher's step 3 are the same row. {@link AgentRun#name()} is the roster name,
     * which is the same string {@code delegate} carries in its arguments, so the two join up.
     *
     * @param run         which agent's run this row belongs to
     * @param step        that run's own step number, which restarts at 1 per agent
     * @param tool        the tool the gate settled on
     * @param target      the subagent this call named, or {@code null} — a delegation's
     *     {@code subagent}, or the name a build was asked to create
     * @param kind        what the call meant to a plan
     * @param disposition how far the call got; {@link Step#happened()} is the useful bit
     * @param succeeded   whether the tool ran <em>and</em> did not return an error.
     *     Separate from {@code disposition} on purpose: {@link Disposition#RAN} covers a tool
     *     that ran and refused, so "it ran" is not "it worked", and a build that was refused
     *     for a duplicate name created nothing while reporting {@code RAN}
     */
    public record Step(AgentRun run, int step, String tool, String target, Kind kind,
                       Disposition disposition, boolean succeeded) {

        public Step {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(disposition, "disposition");
        }

        /** Whether a tool actually ran — a denied or parked call did not happen. */
        public boolean happened() {
            return disposition.reachedTool();
        }

        /** The agent that made this call — a top-level name, or a subagent's roster name. */
        public String actor() {
            return run.name();
        }
    }

    private final Map<String, String> subagentBuilders;
    private final AtomicReference<DeclaredPlan> declared = new AtomicReference<>();
    private final List<DeclaredPlan> redeclarations = new CopyOnWriteArrayList<>();
    private final List<Step> trace = new CopyOnWriteArrayList<>();

    /** A ledger for a deployment whose model cannot build subagents. */
    public RunLedger() {
        this(Map.of());
    }

    /**
     * A ledger that also recognises the deployment's subagent-building tools (#308).
     *
     * @param subagentBuilders tool name to the argument carrying the name of the subagent it
     *     builds — {@code Map.of("spawn_subagent", "name")} for the shape
     *     {@code SelfWiringAgent} uses. Empty is right for a deployment with no such tool.
     *     Named here rather than guessed because the framework ships none (#313) and a
     *     ledger that guessed would be wrong in the silent direction: a build it failed to
     *     recognise reads as a delegation to a subagent nobody named, which is exactly the
     *     false report #316 is about.
     */
    public RunLedger(Map<String, String> subagentBuilders) {
        this.subagentBuilders = Map.copyOf(subagentBuilders);
    }

    /**
     * Records the first plan and keeps any later one aside.
     *
     * <p>First wins rather than last, so a run cannot rewrite its promise after seeing how it
     * went — which is the whole point of declaring one. A second declaration is not dropped:
     * {@link Conformance#check} reports it.
     *
     * @return {@code true} if this was the declaration the run will be held to
     */
    public boolean declare(DeclaredPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (declared.compareAndSet(null, plan)) {
            return true;
        }
        redeclarations.add(plan);
        return false;
    }

    /** The declaration the run is held to, if it made one. */
    public Optional<DeclaredPlan> declaredPlan() {
        return Optional.ofNullable(declared.get());
    }

    /** Every later declaration, in order, none of which the run is held to. */
    public List<DeclaredPlan> redeclarations() {
        return List.copyOf(redeclarations);
    }

    /**
     * The tool names this ledger reads as building a subagent.
     *
     * <p>Read by {@link Conformance#holdingTo(RunLedger)}, so that the gate needs no second
     * copy of a name the deployment has already given once. Two copies of a tool name is two
     * places to misspell it, and a misspelling would fail silently in the permissive
     * direction — a build the gate did not recognise is a build it did not refuse.
     */
    public Set<String> subagentBuildingTools() {
        return subagentBuilders.keySet();
    }

    /** Every settled call, in the order the observer saw them. */
    public List<Step> trace() {
        return List.copyOf(trace);
    }

    /** Every call this trace attributes to the agent called {@code name}. */
    public List<Step> by(String name) {
        return trace.stream().filter(step -> step.actor().equals(name)).toList();
    }

    /**
     * The subagents this run built for itself, in the order it built them (#308, #316).
     *
     * <p>A build that <em>ran and returned an error</em> — a duplicate name, a name the
     * framework could not print — is not in here, because nothing was created. It reports
     * {@link Disposition#RAN} all the same, which is why {@link Step#succeeded()} exists and
     * why this is not a filter on {@link Step#happened()}.
     *
     * <p>Readable mid-run, which is what makes the gate possible: a build settles at the step
     * before the delegation that uses it, so by the time that delegation is gated the name is
     * already here.
     */
    public Set<String> builtSubagents() {
        Set<String> built = new LinkedHashSet<>();
        for (Step step : trace) {
            if (step.kind() == Kind.SUBAGENT_BUILD && step.succeeded() && step.target() != null) {
                built.add(step.target());
            }
        }
        return built;
    }

    /** This run's declaration against this run's trace. */
    public Conformance conformance() {
        return Conformance.check(declaredPlan(), redeclarations(), trace());
    }

    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                             ToolInvocation effective, ToolResult result,
                             Disposition disposition) {
        // effective, not proposed: what the gate settled on is what a reviewer is owed, and
        // a narrowing gate exists precisely to overrule the proposal (#131).
        //
        // Measured, so the claim is the right size: it is the ARGUMENTS that differ. Reading
        // the tool NAME off `proposed` instead is an equivalent change here, because
        // GateResult.effectiveFor refuses a replacement that renames (#104), so the two
        // names are the same string on every path. `effective` all the way through anyway —
        // a row that took its name from one invocation and its target from the other would
        // be one refactor away from being wrong about both.
        String name = effective.name();
        Kind kind;
        String target = null;
        if (SubagentTools.DELEGATE.equals(name)) {
            kind = Kind.DELEGATION;
            target = effective.stringArgument("subagent");
        } else if (subagentBuilders.containsKey(name)) {
            // A build is the one call that changes what the rest of the run may route to, so
            // a row recording it as a bare tool name would leave a reviewer unable to say
            // which specialist appeared, or when.
            kind = Kind.SUBAGENT_BUILD;
            target = effective.stringArgument(subagentBuilders.get(name));
        } else if (VerifierTools.VERIFY_CLAIM.equals(name)) {
            kind = Kind.VERIFICATION;
        } else {
            kind = Kind.OTHER;
        }
        trace.add(new Step(run, step, name, target, kind, disposition,
                disposition.reachedTool() && !result.isError()));
    }
}
