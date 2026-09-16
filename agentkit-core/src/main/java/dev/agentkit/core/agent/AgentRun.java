package dev.agentkit.core.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Whose run an observer is being told about.
 *
 * <p>Minted once per {@link Agent#run(Goal)} and handed to every {@link AgentObserver}
 * callback. It is the answer to a question the callbacks could not previously be asked:
 * {@code step} is an {@code int} that restarts at 1 in every agent, so one observer wired
 * into a supervisor and into the agents its subagents build — which is the only way to get
 * one trace — received a supervisor's step 3 and a subagent's step 3 as the same row.
 *
 * <h2>The measured gap (#311)</h2>
 *
 * <p>From {@code SelfWiringAgentTest.keepsItsWord}, a run that declares one subagent and
 * delegates to it, printing the trace the shared observer collected:
 *
 * <pre>
 * declare_plan     &lt;- the supervisor
 * lookup           &lt;- inside the researcher
 * delegate         &lt;- the supervisor, completing
 * verify_claim     &lt;- the supervisor
 * </pre>
 *
 * <p>The researcher's {@code lookup} lands <em>before</em> the {@code delegate} that caused
 * it, because delegation is synchronous and the child finishes first, and nothing in the row
 * said it belonged to a child. That example survived only because {@code delegate} happens to
 * carry the subagent's name in its own arguments, so a reader could reconstruct the boundary
 * by hand — and the reconstruction fails for the case the trail exists for: a call the policy
 * stopped inside a child was attributed to nobody. {@code AgentObserver.onToolResult}'s
 * javadoc argues at length that a compliance row must be right about <em>what</em> ran; this
 * is the same argument about <em>who</em> ran it.
 *
 * <h2>Carried on the callbacks, not wired by the deployment</h2>
 *
 * <p>Two cheaper shapes were rejected. An <strong>observer factory per run</strong> and a
 * shipped <strong>{@code Attributing} decorator</strong> both leave an unattributed trace
 * reachable by forgetting to wire something — a deployment that does not think of it gets a
 * trace that looks complete and is not, which is the documentation-as-mitigation shape this
 * repository keeps rejecting. This one cannot be forgotten: there is no way to receive a
 * callback without being told whose it is. It breaks every implementor, which is how #131
 * and #181 both moved this interface, and for the same reason — an observer that is silently
 * never called, or silently wrong, is the failure the interface exists to prevent.
 *
 * <h2>A name as well as an id</h2>
 *
 * <p>An opaque id separates the rows and stops there: a reviewer holding
 * {@code 7f3a…: shred, REFUSED} knows it was not the supervisor and cannot say who it was.
 * So the identity carries the agent's {@link #name()} too, and {@link #id()} is built from it
 * — {@code drafter#4} rather than a UUID — because the id is read by people at least as often
 * as it is compared by machines.
 *
 * <p>The name is not something a deployment has to remember to set for the case that matters.
 * {@link dev.agentkit.core.supervisor.Subagent#of(String, String,
 * java.util.function.Supplier)} runs its child under the roster name it was declared with, so
 * every subagent is named by construction and the name in the trace is the same string
 * {@code delegate} puts in its arguments. {@link Agent.Builder#name(String)} is for the
 * top-level agent, which nothing else can name; leaving it unset yields {@link #ANONYMOUS},
 * which is a legible placeholder rather than a blank — the pressure to name an agent shows up
 * in its own trace instead of in a paragraph nobody reads.
 *
 * <h2>And whose behalf it is on (#317)</h2>
 *
 * <p>{@link #parent()} is the run that delegated to this one, so a trace is a tree rather
 * than a set of attributed rows. Two sibling subagents of two different supervisors used to
 * be distinguishable only by name, and a reviewer holding {@code shred, REFUSED} could say
 * <em>who</em> made the call and not <em>on whose behalf</em>.
 *
 * <p><strong>The ambient {@code ThreadLocal} was rejected, and it is worth saying why here
 * rather than only in the issue.</strong> It is right for synchronous delegation, which is
 * the common case, and silently wrong on an injected pool: the child either inherits a
 * parent from unrelated earlier work or inherits nothing. {@code AgentGraph} and
 * {@code Supervisor} both read their concurrency permit on the driver thread and carry it
 * into each submission <em>by hand</em>, in identical comments, for exactly this reason —
 * "an injected pool's threads were created long before this run and would inherit nothing".
 * A parent link that is right on the common path and quietly wrong on the other is worse
 * than none, because a reviewer will trust it. So the link travels explicitly:
 * {@link dev.agentkit.core.tool.Tool#boundTo(AgentRun)} hands the executing run to the tool
 * about to run, {@code SubagentTools}' {@code delegate} is the one tool that wants it, and
 * {@link dev.agentkit.core.supervisor.Subagent} mints the child under it through
 * {@link #child(String)}.
 *
 * <h2>What the parent link does not promise</h2>
 *
 * <p><strong>Empty is "no dispatch that knew a parent", not "top level".</strong> Three
 * paths reach a tool with no run in hand and therefore produce a child with no parent: the
 * durable runner ({@code ToolActivitiesImpl}, which executes tools from an activity and
 * never holds an {@code AgentRun}), {@code ToolBridges} (the tools a sandboxed script
 * calls), and any caller that invokes {@code Tool.execute} itself. Each degrades to
 * <em>no</em> parent and never to a wrong one, which is the property that makes the link
 * worth trusting where it is present.
 *
 * <p><strong>A decorator can still drop it.</strong>
 * {@link dev.agentkit.core.tool.Tool#boundTo(AgentRun)} has the trap every other declaration
 * on that interface has: a hand-written {@code implements Tool} takes the default and
 * answers for itself. {@code ForwardingTool} forwards it, and what a decorator that does not
 * degrades to is again a missing parent rather than a wrong one.
 *
 * <p><strong>{@link dev.agentkit.core.supervisor.Subagent#handling(String, String,
 * java.util.function.Function)} does not stamp it</strong>, because that handler owns the
 * run and may not be running an {@code Agent} at all. {@code Subagent.handlingUnder} is the
 * overload for a handler that <em>is</em> running one: the framework mints the identity,
 * parent and all, and hands it to the handler to pass to {@link Agent#run(Goal, AgentRun)}.
 *
 * <p><strong>Not a distributed trace id.</strong> {@link #id()} is unique within one JVM,
 * which is the scope of one observer's view. Correlating across processes or across a durable
 * replay is OpenTelemetry's job, and {@code agentkit-otel} already does it with span
 * parentage that survives a thread hop. #317 asked whether a parent link here strengthens
 * the case for an {@code agentkit.run.id} attribute on those spans, and the answer stayed
 * no: {@code AgentTelemetry.invokeAgent} carries the argument, at the method that would have
 * to change for it to become yes.
 *
 * @param id     unique per run within this JVM, and readable
 * @param name   the agent this run belongs to — a roster name for a subagent,
 *     {@link #ANONYMOUS} for a top-level agent nobody named
 * @param parent the run that delegated to this one, or empty where no dispatch that knew a
 *     parent was involved
 */
public record AgentRun(String id, String name, Optional<AgentRun> parent) {

    /**
     * The name of an agent nobody named.
     *
     * <p>A placeholder and not an absence: every run still has a distinct {@link #id()}, so
     * the rows still separate. What is missing is only the part a person reads.
     */
    public static final String ANONYMOUS = "agent";

    /**
     * Process-wide, so two runs of two different agents cannot collide either.
     *
     * <p>A counter rather than a UUID because these are read: {@code drafter#4} next to
     * {@code drafter#7} in a trace says what two random hex strings do not.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public AgentRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parent, "parent");
        if (id.isBlank()) {
            throw new IllegalArgumentException("An agent run's id cannot be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("An agent run's name cannot be blank");
        }
    }

    /**
     * A fresh identity for one run of the agent called {@code name}.
     *
     * <p>Every call returns a different {@link #id()}, including two calls with the same
     * name: two delegations to the same subagent are two runs, and a trace that merged them
     * would have the interleaving problem back for the case that produces it most often.
     */
    public static AgentRun of(String name) {
        Objects.requireNonNull(name, "name");
        return new AgentRun(mint(name), name, Optional.empty());
    }

    /**
     * A fresh identity for one run of {@code name}, delegated to <em>by this run</em> (#317).
     *
     * <p>The only way a parent is ever set, and an instance method rather than a second
     * {@code of} overload because the parent is the thing that has to be in hand: a caller
     * holding no run cannot reach this, so there is no spelling of "a child of nobody" that
     * looks like a parent link. {@link #of(String)} is that case and says so.
     *
     * <p>The chain is finite and immutable by construction — a parent exists before its
     * child and no component is ever reassigned — so there is no cycle for {@code equals} or
     * {@code hashCode} to walk into. {@link #toString()} is the id alone, so a deep chain
     * still prints as one row.
     */
    public AgentRun child(String name) {
        Objects.requireNonNull(name, "name");
        return new AgentRun(mint(name), name, Optional.of(this));
    }

    /** The readable id, in one place because two factories mint one. */
    private static String mint(String name) {
        return name + "#" + SEQUENCE.incrementAndGet();
    }

    /** The id, which is what belongs in a trace row. */
    @Override
    public String toString() {
        return id;
    }
}
