package dev.agentkit.core.tool;

import dev.agentkit.core.agent.AgentRun;
import java.util.List;
import java.util.Map;

/**
 * A capability the agent can invoke.
 *
 * <p>A tool declares a {@link #name()}, a {@link #description()} the model uses
 * for selection, and an {@link #inputSchema() input schema}; when invoked it
 * receives the parsed {@link ToolInvocation} and returns a {@link ToolResult}.
 *
 * <p>Implementations should be side-effect-aware: hard-to-reverse actions are
 * candidates for gating (see the verification subsystem). {@link #execute} may
 * throw to signal an unexpected failure; the agent loop converts thrown
 * exceptions into an error {@link ToolResult} so a single tool failure does not
 * abort the run.
 *
 * <p><strong>Decorating a tool: extend {@link ForwardingTool}.</strong> Four members here
 * are abstract and eight are not, so {@code implements Tool} compiles as soon as the four are
 * forwarded — and the eight defaults then describe the decorator rather than the tool inside
 * it. Three of those eight ({@link #holdsGateWaitingForAHuman()},
 * {@link #holdsGateBoundToOneRun()}, {@link #boundToOneRun()}) default to {@code false} and
 * are read by a durable worker's registration check, so an omission there fails
 * <em>open</em>.
 * {@link ForwardingTool} makes the whole tool the default and leaves you to override only
 * what you are decorating. It is opt-in: a hand-written {@code implements Tool} still
 * compiles and still takes every default, which is why each declaration below repeats the
 * warning.
 */
public interface Tool {

    /** The unique name the model uses to reference this tool. */
    String name();

    /** A natural-language description used by the model for tool selection. */
    String description();

    /** The JSON-Schema-style description of this tool's arguments. */
    Map<String, Object> inputSchema();

    /** Executes the tool for the given invocation. */
    ToolResult execute(ToolInvocation invocation);

    /**
     * Optional few-shot example argument sets shown to the model alongside the
     * schema, each a map matching {@link #inputSchema}. Empty by default; override
     * to teach the model correct usage of a tool whose schema underspecifies it.
     */
    default List<Map<String, Object>> inputExamples() {
        return List.of();
    }

    /**
     * What this tool changes that outlives the call. Declaring {@link SideEffects#NONE} is
     * what lets a tool run under {@code ToolGates.readOnly}. Undeclared means
     * {@link SideEffects#UNKNOWN}, which is treated as unsafe. A decorator that forwards
     * this must forward it explicitly, since the default would report every wrapped tool as
     * undeclared — extend {@link ForwardingTool} rather than remembering to.
     */
    default SideEffects sideEffects() {
        return SideEffects.UNKNOWN;
    }

    /**
     * Who wrote the content this tool returns. Undeclared means
     * {@link Provenance#UNKNOWN}, and what a policy makes of that is the policy's choice —
     * {@code TrustFloor} is the first thing that has to decide, and offers both readings by
     * name. Declaring costs one line and removes the guess.
     *
     * <p>Independent of {@link #sideEffects()}: that declares what a call <em>changes</em>,
     * this what it <em>brings back</em>. A web fetch changes nothing and returns a
     * stranger's words; an insert into your own table changes something and returns yours.
     *
     * <p>A decorator that forwards this must forward it explicitly, since the default would
     * report every wrapped tool as undeclared — the same trap {@link #sideEffects()} has,
     * and {@link ForwardingTool} is the answer to both.
     */
    default Provenance provenance() {
        return Provenance.UNKNOWN;
    }

    /**
     * Whether a gate <em>this tool holds internally</em> may block waiting for a person.
     *
     * <p>A hazard declaration rather than a capability, and the tool-shaped counterpart of
     * {@code ToolGate.waitsForAHuman()}. It exists because a gate a tool holds is invisible
     * to whoever wires the tool: {@code CodeExecutionTool} requires a gate for the tools a
     * script calls, and that gate is consulted inside {@link #execute}, one layer below
     * anything a runner asks. A durable worker refuses a blocking gate at registration
     * precisely because an activity that blocks times out and is retried, paging the person
     * again on every attempt — and before this declaration existed a blocking gate reached
     * that worker unseen, because the check was handed the run's gate and not the registry's
     * contents.
     *
     * <p>Defaults to {@code false}, which is right for every tool that holds no gate. Say
     * {@code true} — or forward the answer of the gate you hold — if a call into this tool
     * can end up waiting on somebody outside the process.
     *
     * <p><strong>A decorator must forward this</strong>, the same trap
     * {@link #sideEffects()} has: the default would report every wrapped tool as holding
     * nothing, so wrapping a tool would launder the hazard past a registration check.
     * Extend {@link ForwardingTool} and it is forwarded for you; that is what
     * {@code Tools.withSideEffects}, {@code Tools.withProvenance} and the tracing decorator
     * do. Java cannot force an override, so this remains a thing to get right rather than a
     * thing that cannot be got wrong — what {@link ForwardingTool} changes is which answer
     * you get by saying nothing.
     */
    default boolean holdsGateWaitingForAHuman() {
        return false;
    }

    /**
     * Whether a gate <em>this tool holds internally</em> was built for one run, so that
     * registering the tool anywhere shared applies one run's policy to another's calls.
     *
     * <p>The sibling of {@link #holdsGateWaitingForAHuman()} and the tool-shaped counterpart
     * of {@code ToolGate.boundToOneRun()}. The case it is for is action screening:
     * {@code ToolGates.screeningAgainst(objective, screen)} binds one run's objective into a
     * gate, and a {@code CodeExecutionTool} built over such a gate and registered on a
     * durable worker screens every script every run executes against that one objective.
     * {@code ToolGate.boundToOneRun()}'s javadoc carries what that was measured doing.
     *
     * <p>Defaults to {@code false}. Say {@code true} — or forward the answer of the gate you
     * hold — if the gate inside this tool was built for one run and would be wrong for the
     * next.
     *
     * <p><strong>A decorator must forward this</strong>, for
     * {@link #holdsGateWaitingForAHuman()}'s reason, and {@link ForwardingTool} is again
     * the way not to have to remember.
     */
    default boolean holdsGateBoundToOneRun() {
        return false;
    }

    /**
     * Whether this tool holds state built for one run, so registering it anywhere shared
     * applies one run's state to another's calls.
     *
     * <p>The sibling of {@link #holdsGateBoundToOneRun()}, and it exists because that one is
     * about a <em>gate</em> and the hazard is wider than gates. {@code AgentScope} (#328)
     * holds a run's outstanding work and its parent {@link
     * dev.agentkit.core.agent.AgentRun}; tools built over one are safe for exactly one run
     * and hold no gate at all, so the existing declaration could not describe them and
     * {@code ToolActivitiesImpl}'s registration check could not see them.
     *
     * <p>That is the shape #313 closed one level down: a hazard that nothing can infer,
     * reaching a durable worker through a door the check does not cover. A declaration
     * rather than an inference, for the same reason — a {@code Subagent} could not be asked
     * whether it blocked, and a tool cannot be asked whether the object it closes over
     * belongs to one run.
     *
     * <p>Defaults to {@code false}. Say {@code true} if this tool closes over anything whose
     * lifetime is one run.
     *
     * <p><strong>A decorator must forward this</strong>, for
     * {@link #holdsGateWaitingForAHuman()}'s reason, and {@link ForwardingTool} is the way
     * not to have to remember.
     */
    default boolean boundToOneRun() {
        return false;
    }

    /**
     * This tool, told which run is about to execute it — or {@code this}, which is what a
     * tool with no use for the answer returns and what the default returns for you.
     *
     * <p>The seam a parent link travels through (#317). {@code Agent} calls this once per
     * dispatch, after the gate has decided and before {@code execute}, and runs the tool it
     * gets back. Almost every tool ignores it. The one in this repository that does not is
     * {@code SubagentTools}' {@code delegate}, which runs a whole child agent inside
     * {@link #execute} and, without this, had no way to tell that child whose behalf it was
     * working on: a {@code Subagent} is a {@code Function<Goal, AgentResult>}, so there is
     * no {@code AgentRun} anywhere in reach of the call. The result was a trace that was a
     * set of attributed rows and not a tree.
     *
     * <p><strong>Why a {@code Tool}-side opt-in and not an ambient {@code ThreadLocal}.</strong>
     * An ambient one is right for synchronous delegation and silently wrong on an injected
     * pool, where the child inherits either nothing or an unrelated earlier run's identity.
     * {@code AgentGraph} and {@code Supervisor} both carry their concurrency permit into
     * each submission by hand for exactly that reason. {@link AgentRun}'s javadoc carries
     * the full argument. What this shape buys over a second {@code execute} overload is that
     * a tool author who has never heard of it cannot fail open: the default returns the tool
     * unchanged, the runner executes the tool unchanged, and the only thing lost is a parent
     * link that would not have existed anyway.
     *
     * <p><strong>What an implementation may return.</strong> A tool equivalent to this one
     * in everything a caller can observe except that it knows {@code run} — same
     * {@link #name()}, same {@link #spec()}, same declarations. The runner deliberately does
     * <em>not</em> re-ask the returned tool for its declarations: the gate has already been
     * evaluated against the registered tool, and the result inherits the registered tool's
     * {@link #provenance()}. So a {@code boundTo} that changed a declaration would not
     * thereby escape a policy — it would be ignored, which is the direction to fail in.
     *
     * <p><strong>A fault here costs the link and nothing else.</strong> An implementation
     * that throws, or that returns {@code null}, does not fail the call: the runner logs it
     * and executes the tool unbound, which is exactly what a tool that never overrode this
     * gets. That is deliberate and was measured — an earlier draft let the throw fall into
     * the dispatch path's own catch, one statement before the flag that says a tool body was
     * entered, so a tool that would have run returned an error result to the model, reported
     * {@link Disposition#GATE_FAILED}, and logged that the deployment's policy code was
     * failing. A new method on this interface must not be able to break a tool that worked
     * before it existed, and the loudest of those three symptoms sent an operator to audit a
     * gate that had done nothing. {@link Disposition#THREW} would be wrong too: it means a
     * body was entered and may have landed half a side effect (#241), and nothing is entered
     * here.
     *
     * <p>Called on every dispatch, so an implementation that allocates should allocate
     * something small. Returning {@code this} costs nothing, which is what the default does
     * and what every tool that does not delegate should keep doing.
     *
     * <p><strong>A decorator must forward this</strong>, and forwarding it is not
     * {@code delegate().boundTo(run)}: that would return the tool <em>inside</em> the
     * decorator and hand the runner an undecorated tool to execute, dropping a span, a
     * counter or a rate limit on the floor. {@link ForwardingTool} does it correctly —
     * rebuild the decorator around the bound delegate — and asks its subclasses for one
     * method to make that possible. A decorator that does nothing here loses the parent
     * link and nothing else, which is the same "missing, never wrong" degradation the
     * durable path takes.
     */
    default Tool boundTo(AgentRun run) {
        return this;
    }

    /** The public specification (metadata) advertised to the model. */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), inputSchema(), inputExamples());
    }
}
