package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;

/**
 * A guardrail evaluated before a tool runs. Gates let a harness block or approve
 * hard-to-reverse actions (external writes, sends, deletes) that an unsupervised
 * agent should not take unilaterally — a denied call becomes an error result the
 * model can react to, not a silent no-op.
 */
@FunctionalInterface
public interface ToolGate {

    /** A gate that permits every invocation. */
    ToolGate ALLOW_ALL = (tool, invocation) -> GateResult.allow();

    /**
     * Decides whether {@code invocation} may execute against the tool that would run it.
     *
     * <p>The tool is a parameter rather than something a gate looks up because a gate that
     * decides from a property of it — {@link Tool#sideEffects()}, say — would otherwise
     * have to be handed a registry, and would then be only as correct as the registry it
     * was given. The caller has already resolved the tool, so passing it removes a way to
     * be wrong rather than documenting one.
     *
     * <p>{@code tool} is never null. There was an overload that omitted it, for a caller
     * that could not resolve one; nothing ever was that caller, and keeping it meant a
     * nullable tool on a security boundary — a gate deciding from {@link Tool#sideEffects()}
     * had to guess, and one shipped gate guessed permissively. A caller that cannot resolve
     * a tool has nothing to gate.
     *
     * <p>This is the abstract method, and deliberately: it used to be the one-argument
     * form with this as a default that dropped the tool, and two separate callers in this
     * repo took that default while holding a tool they had already resolved. Both turned a
     * read-only policy into deny-everything, and both failed closed, so they looked like
     * the policy working. A decorator can no longer drop what it must accept to compile.
     */
    GateResult evaluate(Tool tool, ToolInvocation invocation);

    /**
     * Whether a decision from this gate may block waiting for a person.
     *
     * <p>A claim about the policy, like {@link #guaranteesReadOnly()}, and for the same
     * reason: something wiring the gate needs to know before it runs. The wiring that
     * needs it is the durable runner. In process, an {@code Approver} that blocks for an
     * hour blocks one thread and the run waits, which is the whole point. As a Temporal
     * activity the same approver burns the start-to-close timeout, the activity fails, and
     * Temporal <em>retries</em> — so the person is asked again, and a policy built to put a
     * human in the loop instead spams them and then fails the call. That is worse than not
     * being wired at all, so the durable path refuses it at registration rather than
     * discovering it in production.
     *
     * <p>Defaults to {@code false}, which is right for a gate that decides from something
     * already in hand — a name, declared side effects, a budget. Say {@code true} if a
     * decision can wait on anything outside the process.
     *
     * <p>Forwarded by {@link ForwardingToolGate}, and true for a composite if it is true
     * for any member: a gate that blocks does not stop blocking by being composed.
     */
    default boolean waitsForAHuman() {
        return false;
    }

    /**
     * Whether this gate carries something that belongs to a single run, so that wiring it
     * anywhere shared applies one run's policy to another's calls.
     *
     * <p>The property a gate holding the run's objective has, and the reason action
     * screening is expressible here at all. {@code ToolGates.screeningAgainst} binds the
     * objective into the gate rather than taking it as a fourth thing every runner has to
     * pass — so the three in-process runners execute it unchanged, none of them having a
     * new argument it could drop, and the durable one refuses it whole rather than running
     * it half-populated. What that costs is that the gate is now a per-run object rather
     * than a policy, and this is how it says so.
     *
     * <p>Declared rather than inferred, and read at the one place the distinction has teeth:
     * {@code ToolActivitiesImpl} is built once per task queue and serves every run on the
     * worker, where the in-process idiom is one gate per {@code Agent}. Measured on the
     * durable runner with a gate holding one run's objective ("Onboard alice@example.com")
     * and a second run's call arriving at the same worker:
     *
     * <pre>
     *   worker accepted the gate                 : yes
     *   run A, its own objective, refused        : no
     *   run B, a different run, refused          : yes -- "Not named in the objective."
     * </pre>
     *
     * <p>Run B was screened against a stranger's objective, silently, at an authorization
     * boundary. So the durable runner refuses such a gate where it is wired, which is the
     * same trade {@link #waitsForAHuman()} already makes: loud at registration beats wrong
     * in production.
     *
     * <p>Defaults to {@code false}, which is right for a gate that decides from something
     * true of every run — a name, declared side effects, a budget. Say {@code true} if the
     * gate was built for one run and would be wrong for the next.
     *
     * <p>A hazard rather than a guarantee, so it is OR across a composite and across both
     * policies of a {@code TrustFloor}: one member bound to a run binds the whole wiring.
     * Contrast {@link #guaranteesReadOnly()}, which is a promise and so is AND across a
     * floor.
     *
     * <p><strong>Best-effort, in the same sense as {@link #waitsForAHuman()}.</strong> A
     * hand-written decorator that forwards {@link #evaluate(Tool, ToolInvocation)} and takes
     * this default launders a per-run gate past the check; extend {@link ForwardingToolGate}
     * and inherit it. A gate closing over a run's objective through a plain lambda answers
     * {@code false} because nothing asked it — which is the state the durable measurement
     * above was taken in, and is why this is a declaration and not a detector.
     */
    default boolean boundToOneRun() {
        return false;
    }

    /**
     * Whether this gate refuses every tool not declared {@link Tool#sideEffects() NONE} —
     * the guarantee {@code ToolGates.readOnly()} is named for.
     *
     * <p>A claim about the policy rather than about any one call: a gate says this so
     * that something wiring it can tell whether a component is entitled to declare itself
     * side-effect-free on the strength of it. {@code CodeExecutionTool} is the caller —
     * a script can only be {@code NONE} if the bridge gate stops it reaching a writer.
     *
     * <p>Defaults to {@code false}, which is the safe answer for a gate that decides on
     * something else — a name, an approver, a budget. Say {@code true} only if refusing
     * non-{@code NONE} tools is what the gate is <em>for</em>; a gate that merely happens
     * to deny the writers in one registry is not making this promise.
     *
     * <p><strong>A decorator must forward this.</strong> Unlike
     * {@link #evaluate(Tool, ToolInvocation)}, which you can no longer fail to forward
     * because it is abstract, this one has a default a decorator can take by accident —
     * so extend {@link ForwardingToolGate} and inherit it. Taking the default fails in
     * the loud direction, since a construction that was legal stops building, rather
     * than the silent one. It is still wrong.
     */
    default boolean guaranteesReadOnly() {
        return false;
    }
}
