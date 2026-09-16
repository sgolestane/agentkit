package dev.agentkit.itops.runtime;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.tools.ToolCatalog;
import dev.agentkit.itops.tools.ToolPolicy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What this run has already done, in the vocabulary the policy needs to read it back.
 *
 * <p>The itops answer to {@code RunLedger} (#310), and it is a separate class rather than a
 * use of that one for a reason worth stating: {@code RunLedger} classifies a call by what it
 * meant to a {@code DeclaredPlan} — a delegation, a subagent build, a verification — and this
 * module has no plan and no subagents. What {@link RunRules} needs is the classification this
 * module already has and core does not: a call's {@link ToolPolicy#capability()}, which is
 * the string {@code ToolCatalog} indexes and {@code search_tools} searches on. Two records
 * with different keys, and neither is a subset of the other.
 *
 * <h2>The one judgement, and where it comes from</h2>
 *
 * <p>{@link #refusedCapabilities()} counts a call if and only if its
 * {@link Disposition} is {@link Disposition#REFUSED}. That is not a rule invented here; it is
 * the framework's own word for the state, and every carve-out a refusal-stickiness control
 * needs falls out of the enum rather than out of a second opinion in this file:
 *
 * <ul>
 *   <li><strong>A park is not a refusal.</strong> {@link Disposition#PARKED} is "a gate
 *       stopped the call pending somebody's decision", and its own javadoc draws the line —
 *       "no later approval turns {@code REFUSED} into a run". A control that treated the two
 *       alike would arm on the ordinary case this platform exists to demonstrate, and the
 *       approved resume behind it would then be refused by a rule the approval was granted
 *       to get past.</li>
 *   <li><strong>A tool that ran and failed is not a refusal.</strong> {@link Disposition#RAN}
 *       covers a tool that returned {@code ToolResult.error(...)} — {@code assign_ticket} on
 *       an id that does not exist — and {@link Disposition#THREW} covers one that broke.
 *       Neither is a policy decision, and reading {@code result.isError()} instead would have
 *       made both of them one, which is the seven-states-one-bit defect {@code Disposition}
 *       was added for (#181).</li>
 *   <li><strong>Nor is an unknown tool, a call nothing decided about, or a broken gate.</strong>
 *       {@link Disposition#UNKNOWN_TOOL}, {@link Disposition#NOT_ATTEMPTED} and
 *       {@link Disposition#GATE_FAILED} all mean no policy answered, and
 *       {@code Disposition}'s javadoc says why they must not read as a denial: "an audit
 *       trail that reports it as a denial invents a decision nobody made".</li>
 * </ul>
 *
 * <h2>What a witness can and cannot do</h2>
 *
 * <p>This is an {@link AgentObserver}, so it stops nothing: every callback returns
 * {@code void} and a throw out of one is absorbed by {@code Observations.ran}. The
 * intervening belongs to {@link RunRules}, which reads this record live from inside a
 * {@code ToolGate} — the same division {@code RunLedger} and
 * {@code Conformance.holdingTo} draw, and for the same reason.
 *
 * <p><strong>The record is allowed to be incomplete, and the gates fail in the right
 * direction because of it.</strong> A lost {@code onToolResult} row makes
 * {@link #refusedCapabilities()} <em>smaller</em>, so a route around a refusal that should
 * have been denied is allowed — which is the wrong direction, and is why it is said here
 * rather than left to be discovered. It is also not fixable in this class: absorbing observer
 * failures is {@code Observations}' contract and the right one. A deployment that needs the
 * record to be complete sets {@code Agent.Builder.onObservationFailure} and fails the run.
 * {@link #succeeded(String)} loses a row in the safe direction — a precondition that was met
 * and went unrecorded denies a call that could have run.
 *
 * <p><strong>One history is one run.</strong> It accumulates for the life of the object, so a
 * second run through the same object is judged against the first run's calls.
 * {@link ExecutionRunner#run} builds a fresh one per attempt, and every gate over it declares
 * {@code boundToOneRun()} so a durable worker refuses the wiring rather than doing this
 * quietly.
 *
 * <p>Thread-safe, because the callbacks arrive on the agent loop's thread and a gate reads
 * from whichever thread the loop is on when it consults policy.
 */
public final class RunHistory implements AgentObserver {

    /**
     * One settled call, in this module's terms.
     *
     * @param tool        the tool the gate settled on
     * @param capability  its {@link ToolPolicy#capability()}, or {@code unknown} for a tool
     *     no policy declares — the same fallback {@link ToolCatalog#policyOrUnknown} gives
     *     the supervisor, so a tool nobody wrote a policy for cannot be quietly exempt from
     *     one control while being treated as {@code HIGH} by the other
     * @param disposition how far the call got, as the runner stamped it
     * @param succeeded   whether the tool ran <em>and</em> did not return an error; separate
     *     from {@code disposition} because {@link Disposition#RAN} covers a tool that ran and
     *     reported a failure
     */
    public record Step(String tool, String capability, Disposition disposition,
                       boolean succeeded) {

        public Step {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(disposition, "disposition");
        }
    }

    private final List<Step> steps = new CopyOnWriteArrayList<>();

    /** Every settled call, in the order the observer saw them. */
    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /**
     * The capabilities a gate has refused this run outright.
     *
     * <p>Readable mid-run, which is what makes {@link RunRules#noRouteAroundARefusal} possible:
     * a refusal settles one step before whatever the run tries next, so by the time that next
     * call is gated the capability is already here.
     */
    public Set<String> refusedCapabilities() {
        Set<String> refused = new LinkedHashSet<>();
        for (Step step : steps) {
            if (step.disposition() == Disposition.REFUSED) {
                refused.add(step.capability());
            }
        }
        return refused;
    }

    /** Whether the tool called {@code name} has run and returned a non-error result. */
    public boolean succeeded(String name) {
        Objects.requireNonNull(name, "name");
        for (Step step : steps) {
            if (step.succeeded() && step.tool().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code effective}, not {@code proposed}, for the reason {@code AuditObserver}
     * does: what the gate settled on is what policy decided about (#131). The two carry the
     * same tool name on every path — {@code GateResult.effectiveFor} refuses a replacement
     * that renames (#104) — so the capability looked up here is the same either way, and
     * taking both name and disposition off one invocation is what keeps a future narrowing
     * gate from splitting them.
     */
    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                             ToolInvocation effective, ToolResult result,
                             Disposition disposition) {
        String name = effective.name();
        steps.add(new Step(name, ToolCatalog.policyOrUnknown(name).capability(), disposition,
                disposition.reachedTool() && !result.isError()));
    }
}
