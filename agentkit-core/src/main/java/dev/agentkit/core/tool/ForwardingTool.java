package dev.agentkit.core.tool;

import dev.agentkit.core.agent.AgentRun;
import java.util.List;
import java.util.Map;

/**
 * A {@link Tool} that hands every method to another one.
 *
 * <p>Extend this to decorate a tool — to trace it, count it, re-declare one thing about it
 * — and override only what you are decorating. Everything else forwards, including the
 * declarations that are easy to forget and expensive to get wrong. The sibling of
 * {@code ForwardingToolGate}, added for the same reason and after the same mistake.
 *
 * <h2>What a decorator has to carry</h2>
 *
 * <p>A tool is not only {@link Tool#name()}, {@link Tool#description()},
 * {@link Tool#inputSchema()} and {@link Tool#execute}. Those four are abstract, so a
 * hand-written {@code implements Tool} compiles as soon as they are forwarded — and the
 * eight that are <em>not</em> abstract are then answered by the interface defaults, which
 * describe the decorator rather than the tool inside it:
 *
 * <ul>
 *   <li>{@link Tool#spec()} rebuilds itself from the getters, discarding whatever a tool
 *       that overrides {@code spec()} put there.</li>
 *   <li>{@link Tool#inputExamples()} answers empty, losing the few-shot examples the model
 *       was meant to see.</li>
 *   <li>{@link Tool#sideEffects()} answers {@link SideEffects#UNKNOWN}, which
 *       {@code ToolGates.readOnly} refuses — so every wrapped tool drops out of a
 *       rehearsal. Loud, and fails closed.</li>
 *   <li>{@link Tool#provenance()} answers {@link Provenance#UNKNOWN}, erasing a
 *       declaration the tool's author made.</li>
 *   <li>{@link Tool#holdsGateWaitingForAHuman()}, {@link Tool#holdsGateBoundToOneRun()} and
 *       {@link Tool#boundToOneRun()} answer {@code false}. These are the three that fail
 *       <em>open</em>: a durable worker refuses a tool holding a blocking or per-run gate at
 *       registration, and a decorator taking the default reports the tool as holding
 *       nothing — so the gate lands on the worker, and the approver behind it is paged once
 *       per activity retry (#283). {@link Tool#boundToOneRun()} is the newest of the three
 *       and therefore the one a hand-written decorator is likeliest to omit: it says the
 *       tool holds one run's state rather than one run's gate, and a decorator that drops it
 *       hands every run on the queue a stranger's scope (#328).</li>
 *   <li>{@link Tool#boundTo(AgentRun)} answers {@code this}, so the run the decorated tool
 *       is executing in never reaches the tool inside — and a subagent delegated to through
 *       a decorated {@code delegate} names no parent in the trace (#317). This one fails
 *       <em>closed</em>, in the weak sense that what is lost is a link rather than a
 *       guarantee, and it is still a fact a reviewer would have been shown and is not.</li>
 * </ul>
 *
 * <h2>What this does and does not remove</h2>
 *
 * <p><strong>It does not make the mistake unexpressible.</strong> Java has no way to force
 * an override, and {@code class MyTool implements Tool} still compiles with the four
 * abstract members and still answers all eight defaults. Nothing here changes that, and
 * nothing here can.
 *
 * <p>What it removes is <em>the wrong thing being the default thing at an authorization
 * boundary</em>. Before this, writing a correct decorator meant knowing that eight methods
 * exist beyond the four the compiler asks for, and knowing which three of them are read by a
 * registration check you may never have heard of. Extending this class, the default is the
 * whole tool; the only way to drop a declaration is to write an override that drops it.
 * That is a smaller and much more visible mistake than an omission.
 *
 * <p><strong>A hand-written {@code implements Tool} gets none of this</strong> — it is
 * exactly as safe, or unsafe, as it was, which is why {@link Tool}'s own javadoc still says
 * on each declaration that a decorator must forward it, and why the durable worker's check
 * still lists the hand-written decorator among the things it cannot catch. This class is
 * the thing to reach for, not a guarantee about the ones that do not.
 *
 * <h2>Using it</h2>
 *
 * <pre>{@code
 * final class CountingTool extends ForwardingTool {
 *     private final Tool delegate;
 *     private final AtomicInteger calls = new AtomicInteger();
 *
 *     CountingTool(Tool delegate) {
 *         this.delegate = delegate;
 *     }
 *
 *     @Override
 *     protected Tool delegate() {
 *         return delegate;
 *     }
 *
 *     @Override
 *     public ToolResult execute(ToolInvocation invocation) {
 *         calls.incrementAndGet();
 *         return super.execute(invocation);
 *     }
 * }
 * }</pre>
 *
 * <p>A decorator that wants {@link Tool#boundTo(AgentRun)} to reach the tool inside it also
 * overrides {@link #rebuiltAround(Tool)}, which is one line and the only method here a
 * subclass has to write beyond {@link #delegate()}:
 *
 * <pre>{@code
 * @Override
 * protected Tool rebuiltAround(Tool bound) {
 *     return new CountingTool(bound);   // this decorator, around a delegate that knows the run
 * }
 * }</pre>
 *
 * <p>{@link #delegate()} is called per method rather than captured in a field here, so a
 * subclass is free to compute it. A subclass that changes what it returns between calls is
 * changing what the tool <em>is</em> mid-flight; nothing stops that, and nothing here
 * depends on it not happening.
 *
 * <p>{@code equals} and {@code hashCode} are not forwarded, and deliberately: a decorator
 * is a different object from the tool it wraps, registries key on {@link Tool#name()}
 * rather than on identity, and a wrapper that claimed equality with its delegate would make
 * "is this tool traced" unanswerable.
 */
public abstract class ForwardingTool implements Tool {

    /** The tool every unoverridden method goes to. */
    protected abstract Tool delegate();

    @Override
    public String name() {
        return delegate().name();
    }

    @Override
    public String description() {
        return delegate().description();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return delegate().inputSchema();
    }

    @Override
    public List<Map<String, Object>> inputExamples() {
        return delegate().inputExamples();
    }

    @Override
    public ToolSpec spec() {
        // Forwarded rather than left to the interface default, which would rebuild the spec
        // from the getters above and discard whatever a tool that overrides spec() put
        // there. Both shipped decorators and the tracing one had to hand-write this.
        return delegate().spec();
    }

    @Override
    public SideEffects sideEffects() {
        // The default is UNKNOWN and ToolGates.readOnly refuses UNKNOWN, so dropping this
        // turns a rehearsal into deny-everything for anyone who wrapped their tools.
        return delegate().sideEffects();
    }

    @Override
    public Provenance provenance() {
        return delegate().provenance();
    }

    @Override
    public boolean holdsGateWaitingForAHuman() {
        // The one with teeth. The default is false, so a decorator that omits it reports
        // every wrapped tool as holding no gate — and a CodeExecutionTool holding a
        // blocking approver then passes a durable worker's registration check and pages
        // somebody once per activity retry (#283). Failing open, and silently.
        return delegate().holdsGateWaitingForAHuman();
    }

    @Override
    public boolean boundToOneRun() {
        return delegate().boundToOneRun();
    }

    @Override
    public boolean holdsGateBoundToOneRun() {
        // Its sibling, failing open the same way: one run's objective, wired into a gate
        // inside a tool, applied to every other run's calls on a shared worker.
        return delegate().holdsGateBoundToOneRun();
    }

    /**
     * Forwarded by rebuilding this decorator around a delegate that knows the run.
     *
     * <p>Not {@code delegate().boundTo(run)}, which is the obvious spelling and drops the
     * decoration: the runner executes whatever this returns, so returning the tool inside
     * would silently bypass the span, the counter or the limit this class was extended to
     * add. Not {@code this} either, which keeps the decoration and drops the run — that is
     * the default this class exists to replace.
     *
     * <p>The identity check is what keeps the common case free. Almost every tool answers
     * {@link Tool#boundTo(AgentRun)} with itself, so the delegate comes back unchanged,
     * there is nothing to rebuild around, and this returns the decorator it already had —
     * no allocation, and no call into {@link #rebuiltAround(Tool)} for the subclasses that
     * did not override it.
     */
    @Override
    public Tool boundTo(AgentRun run) {
        Tool inner = delegate();
        Tool bound = inner.boundTo(run);
        return bound == inner ? this : rebuiltAround(bound);
    }

    /**
     * This decorator, rebuilt around {@code bound} — the tool it wraps, told which run is
     * executing it.
     *
     * <p>Reached only when the wrapped tool actually wanted the run, which in this
     * repository means {@code delegate} and nothing else. The default keeps the decoration
     * and drops the run, because the alternatives are worse: returning {@code bound} would
     * hand the runner an undecorated tool, and throwing would turn "this decorator has not
     * been taught about a parent link" into a failed tool call at dispatch. So a decorator
     * that ignores this loses a trace edge and changes nothing else.
     *
     * <p>It is not abstract for the same reason: {@link ForwardingTool} is extended by
     * decorators outside this repository, and a new abstract method would break every one of
     * them to add a link most of them do not wrap a tool that can produce. The three
     * decorators shipped here — {@code Tools.withSideEffects}, {@code Tools.withProvenance}
     * and {@code TracingTool} — all override it, so the shipped composition of a traced,
     * re-declared {@code delegate} keeps its parent.
     *
     * @param bound the wrapped tool as {@link Tool#boundTo(AgentRun)} returned it, never the
     *              same object as {@link #delegate()} — the caller has already checked
     */
    protected Tool rebuiltAround(Tool bound) {
        return this;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        return delegate().execute(invocation);
    }
}
