package dev.agentkit.core.codeexec;

import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Quoted;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factories for {@link ToolBridge}s.
 */
public final class ToolBridges {

    /**
     * The operator's channel for this runner, which until #268 it did not have one of.
     *
     * <p>A bridged call reaches no {@code AgentObserver} and produces no {@code AgentResult}
     * — a script's tool calls happen inside the sandbox, and what comes back to the model is
     * whatever the script chose to print. So for the one fact that is a deployment's own
     * policy code being broken, this log is not "another channel", it is the only one.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ToolBridges.class);

    /** No limit on the number of tool calls a single script may make. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private ToolBridges() {
    }

    /**
     * A bridge that resolves and runs tools from {@code registry}, <strong>with no
     * gate</strong> and no call cap. An unknown or throwing tool becomes an error
     * {@link ToolResult} rather than a thrown exception.
     *
     * <p><strong>Fail-open — name says so.</strong> A {@link ToolGate} set on an
     * {@code Agent} does <em>not</em> apply to tools called from inside code — the
     * agent only sees the outer code-execution call. This factory bridges every tool
     * with no policy and no throttle, so use it only when every bridged tool is safe
     * to call freely and unboundedly. When any bridged tool is hard-to-reverse
     * (delete, send, pay), use {@link #of(ToolRegistry, ToolGate)} so the same policy
     * is enforced within the sandbox. The explicit name keeps the ungated choice
     * visible at the call site instead of hiding behind a convenient default.
     */
    public static ToolBridge ofUngated(ToolRegistry registry) {
        return of(registry, ToolGate.ALLOW_ALL, UNLIMITED);
    }

    /**
     * A bridge that evaluates {@code gate} before running each tool, so the same
     * authorization policy the agent applies to direct tool calls also governs calls
     * made from inside code. A denial becomes an error {@link ToolResult}; an
     * approval that edits arguments (see {@code Approver}) is honored, mirroring the
     * agent loop. The gate is also the natural audit point for code-called tools,
     * which the {@code AgentObserver} does not see.
     *
     * <p>Imposes no cap on the number of calls; see
     * {@link #of(ToolRegistry, ToolGate, int)} to bound a script that could otherwise
     * fan out unboundedly (e.g. a loop over a million iterations), since in-script
     * calls never round-trip the model and so escape {@code maxSteps} and any token
     * budget.
     */
    public static ToolBridge of(ToolRegistry registry, ToolGate gate) {
        return of(registry, gate, UNLIMITED);
    }

    /**
     * A gated bridge that also refuses more than {@code maxCalls} tool calls. Because
     * tools called from inside code never round-trip the model, neither
     * {@code AgentConfig.maxSteps} nor a token budget bounds them — a single
     * {@code run_code} turn could otherwise trigger unbounded side effects. The
     * {@code maxCalls + 1}-th call (and every later one) returns an error
     * {@link ToolResult} instead of executing.
     *
     * <p>The cap counts calls made through <em>this</em> bridge instance. To bound a
     * script run rather than a tool's whole lifetime, construct one bridge per run —
     * which is what {@link CodeExecutionTool} does, so its cap resets each run.
     *
     * @param maxCalls the maximum number of tool calls this bridge will execute
     *                 (&gt; 0), or {@link #UNLIMITED} for no cap
     */
    public static ToolBridge of(ToolRegistry registry, ToolGate gate, int maxCalls) {
        return of(registry, gate, maxCalls, new AtomicLong());
    }

    /**
     * As {@link #of(ToolRegistry, ToolGate, int)}, but drawing invocation ids from a
     * caller-supplied {@code idSequence}. This lets one caller build a fresh
     * (cap-resetting) bridge per script run while still minting ids that stay unique
     * across runs — mirroring the globally-unique tool-use ids of the agent loop, so a
     * gate/approver that dedupes or audits by id never conflates calls from different
     * runs. The call cap uses its own per-bridge counter and so resets per bridge.
     */
    /**
     * A bridge that tightens its policy for the rest of <em>this script</em> once the
     * script has read somebody else's words (#122).
     *
     * <p>Without this, a floor wired on the agent loop bought nothing against the attack it
     * is named for: measured with a script that fetched a {@code THIRD_PARTY} page and then
     * published, both calls ran under the ordinary policy, because read and write both
     * happen inside one outer {@code Tool.execute} and the loop cannot intervene between
     * them. The loop's floor lowers when the whole call returns, which is after.
     *
     * <p>The bit lives here, in the bridge, and that is safe for the reason the durable
     * activity's is not: a bridge is built per script run ({@code CodeExecutionTool} mints
     * one on every {@code execute}), so it is already per-run state and cannot leak into
     * another one.
     *
     * <p><strong>It starts raised, whatever the caller has read.</strong> A script begun by
     * a run whose floor is already down begins ungated here. That is a real gap and not a
     * choice: a {@link dev.agentkit.core.tool.Tool} is handed an invocation and nothing
     * else, so {@code CodeExecutionTool} cannot see the run's state to pass on. An earlier
     * version took an {@code alreadyLowered} flag for it; nothing could supply one, so it
     * was a dead parameter with a paragraph attached — worse than the gap stated plainly.
     *
     * <p>With {@code readOnly()} as the tightened policy the seam closes by construction:
     * the loop's tightened gate refuses {@code run_code} unless it is declared {@code NONE},
     * and that declaration requires both policies to guarantee read-only. With
     * {@code denyTools(...)} it is open.
     */
    public static ToolBridge of(ToolRegistry registry, TrustFloor floor, int maxCalls) {
        return of(registry, floor, maxCalls, new AtomicLong());
    }

    static ToolBridge of(ToolRegistry registry, ToolGate gate, int maxCalls, AtomicLong idSequence) {
        return of(registry, TrustFloor.none(Objects.requireNonNull(gate, "gate")), maxCalls,
                idSequence);
    }

    static ToolBridge of(ToolRegistry registry, TrustFloor floor, int maxCalls,
                         AtomicLong idSequence) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(idSequence, "idSequence");
        if (maxCalls <= 0) {
            throw new IllegalArgumentException("maxCalls must be > 0, was: " + maxCalls);
        }
        AtomicLong callCount = new AtomicLong();
        // Per script run, like callCount beside it. Monotonic, for TrustFloor's reason:
        // once the script has the page, nothing here can see what it did with it.
        AtomicBoolean lowered = new AtomicBoolean();
        return (toolName, arguments) -> {
            // Denied and unknown calls are still fan-out attempts, so they count against
            // the cap — a loop of them cannot be used to sidestep it.
            if (callCount.incrementAndGet() > maxCalls) {
                // refused(), not error(): the framework's own sentence about a call that
                // resolved nothing and entered nothing. See ToolResult.refused (#272).
                return ToolResult.refused("Tool-call budget exhausted: a single script may"
                        + " make at most " + maxCalls + " tool call(s).");
            }
            Optional<Tool> tool = registry.find(toolName);
            if (tool.isEmpty()) {
                // Through the one factory, bounded and escaped (#278). The name here is a
                // sandboxed script's rather than a model's turn, which makes it no better:
                // the script is whatever the model wrote, and a CodeSandbox hands this
                // string over without a bound of its own.
                return ToolResult.unknownTool(toolName);
            }
            // A unique id per executed invocation, so a gate/approver that dedupes by
            // invocation id treats each code-called invocation distinctly (as the agent
            // loop does).
            // Through the one door every runner uses (#246), and outside the try below
            // rather than inside it. It cannot throw — that is the whole of what
            // ProposedCall.of is — so there is nothing here for a catch to catch, and the
            // comment that used to stand on this statement was arguing for the wrong
            // remedy: it was right that a CodeSandbox hands over a map it built itself and
            // that a refused argument must surface to the script as data, and wrong that
            // catching it here was how. Caught, this came back as ToolResult.failed("Tool
            // 'x' failed.") with the framework's own IllegalArgumentException fenced inside
            // it — a sentence saying the tool failed about a tool that was never entered,
            // and one a script cannot act on because it does not say what to send instead.
            String id = "code-" + idSequence.incrementAndGet();
            ProposedCall proposed =
                    ProposedCall.of(id, toolName, arguments == null ? Map.of() : arguments);
            if (proposed instanceof UnusableToolUseBlock unusable) {
                // FIRST_PARTY declared rather than left to ToolResult.error's UNKNOWN
                // default. This text is the framework's own, written without reading
                // anything, and Agent's equivalent branch declares it — two runners
                // disagreeing about who wrote a refusal is the shape #113 and #122 were.
                //
                // Through ToolResult.refused now, which the "Unknown tool", budget and
                // denial branches above no longer settle for either (#272): one factory,
                // so a fifth runner cannot arrive at a different answer by writing
                // ToolResult.error and not thinking about it.
                // It is not cosmetic on this runner either: a caller's TrustFloor is asked
                // lowersOn(provenance), so an UNKNOWN here would let a call that never
                // happened tighten the policy for the rest of the script.
                //
                // Deliberately NOT through lowerIfNeeded, and that is a behaviour change
                // worth naming rather than only the one it keeps. The catch below judges a
                // thrown tool's message because a tool that throws returns whoever's words
                // were in the exception — and this refusal used to go through that catch,
                // so it arrived as a result attributed to the tool and could lower the
                // floor for the rest of the script. A call that resolved nothing, entered
                // nothing and read nothing was tightening the policy, on the strength of
                // the framework's own exception wearing the tool's declaration. The
                // direction is the safe one either way (a floor only tightens), so this is
                // an accuracy fix and not a loosening of anything a policy relies on.
                return ToolResult.refused(unusable.refusal());
            }
            // The one thing the catch below cannot work out for itself, and the reason this
            // runner had nothing to say at all until #268: it covers a gate that threw and a
            // tool that threw, and those are two different facts. A gate that threw is the
            // deployment's own policy code broken at an authorization boundary; a tool that
            // threw is an ordinary failure the script routes around. Set on the statement
            // before control enters the tool, so "the tool was entered" is a fact about
            // where this assignment sits rather than an inference from anything the catch
            // can see -- the same construction Agent.runTool uses, and for the same reason.
            // Deriving it from `effective != invocation` would be wrong for every gate that
            // allows without narrowing, which is most of them.
            boolean entered = false;
            try {
                ToolInvocation invocation =
                        new ToolInvocation(id, toolName, ((ToolUseBlock) proposed).input());
                // Pass the resolved tool: a gate that decides from Tool.sideEffects() has
                // nothing to decide from without it, so dropping it here would break the
                // composition the code-execution docs promise.
                GateResult decision = floor.inForce(lowered.get()).evaluate(tool.get(), invocation);
                if (!decision.allowed()) {
                    if (decision.awaiting().isPresent()) {
                        // The gate wants a person, and this runner has nobody to ask and
                        // nowhere to come back to: a script is mid-execution inside a
                        // sandbox with its own timeout, and there is no resume that lands
                        // back on this line. The two agent loops both arrange the waiting;
                        // this one says so instead of pretending, because a refusal that
                        // reads "submitted for approval" would have the script — and the
                        // model reading its output — believe somebody is going to answer.
                        // A gate is the deployment's own policy speaking, and the
                        // sentence after it is this runner's. Neither read anything.
                        return ToolResult.refused(decision.reason()
                                + " Nobody can be asked from inside a sandboxed script, so"
                                + " this call was refused rather than held. Propose it"
                                + " directly instead of through code.");
                    }
                    return ToolResult.refused(decision.reason());
                }
                // The same question the two agent loops ask, asked here too. This is a
                // third runner and the one where the gate is load-bearing: it is the only
                // reason a script may be declared sideEffects=NONE, so a replacement that
                // renamed the tool ran the originally resolved one with the replacement's
                // arguments — under a declaration that said nothing could happen (#104).
                //
                // Hoisted to its own statement since #268, so that a replacement's
                // effectiveFor throwing counts as the gate failing rather than as the tool
                // failing. It is asked once and carried either way, so nothing else moves.
                ToolInvocation effective = decision.effectiveFor(invocation);
                entered = true;
                // No Tool.boundTo here, and that is the honest answer rather than an
                // omission (#317). This runner has no AgentRun: it is handed a registry and
                // a floor and runs whatever a sandboxed script calls, and the agent whose
                // run it belongs to is two layers up, on the far side of a
                // CodeExecutionTool.execute that never saw one either. Binding to something
                // invented here would give a delegated subagent a parent that is not its
                // parent, which is the failure mode the whole seam was shaped to avoid. So a
                // delegate reached from inside a script produces a child with no parent, the
                // same degradation the durable path takes.
                ToolResult produced = tool.get().execute(effective).attributedTo(tool.get());
                // Before the next call in the same script, not after the script ends. The
                // whole point is that read-then-write inside one script is the attack.
                lowerIfNeeded(floor, lowered, produced);
                return produced;
            } catch (RuntimeException e) {
                // Two lines and two levels, not silence for two facts (#268). Until this,
                // a gate that threw here reached NOTHING: no log line, no Disposition, no
                // observer -- the script was handed "Tool 'x' failed." and could not tell
                // a broken authorization boundary from a tool that had an ordinary bad day.
                // That is worse than the state #260 complained about on the two agent
                // loops, and it is worst on the runner where the gate is most load-bearing:
                // this is the only reason a script may be declared sideEffects=NONE, so a
                // gate that throws on every call inside a sandbox leaves the declaration
                // standing with nothing behind it.
                //
                // The wording, the levels and the `entered` flag are the two agent loops'
                // (#260), because "one rule, several runners" is the bar and a fourth
                // vocabulary for the same fact is how an operator's alert comes to cover
                // three runners out of four. error for a gate and warn for a tool, for the
                // reason those loops give at length: an operator has to be able to alert on
                // one and not the other, and a warn nobody can afford to read is not a warn.
                //
                // The residual, stated rather than glossed and identical to Agent.runTool's:
                // a throw from anything between the try and the gate — building the
                // ToolInvocation, TrustFloor.inForce — reports as the gate having failed.
                // All of it is framework code that takes no argument from outside except a
                // map ProposedCall has already frozen, so it cannot be made to throw from a
                // script; and "nothing was decided and nothing ran" is still true of it.
                if (entered) {
                    LOG.warn("Tool '{}' was entered and threw inside a code-execution"
                                    + " script; the script is told the call failed and the"
                                    + " script continues", Quoted.of(toolName),
                            Quoted.failure(e));
                } else {
                    LOG.error("Gate for tool '{}' threw inside a code-execution script, so"
                                    + " nothing was decided and nothing ran; this"
                                    + " deployment's policy code is failing and every call"
                                    + " it should judge is getting an error result instead",
                            Quoted.of(toolName), Quoted.failure(e));
                }
                // Fenced (#113), like the loop this mirrors — and it is not a guarantee,
                // which is worth saying rather than implying. What comes back here goes to
                // the *script*, and only whatever the script prints reaches the model. A
                // two-line script that drops lines beginning "<untrusted " delivers the
                // payload with no fence at all; measured. So this is what a well-behaved
                // script propagates, and the boundary that can actually hold is
                // CodeExecutionTool's own output, which is where the sandbox's failures are
                // fenced and where #60 owns the successful ones.
                ToolResult failure = ToolResult.failed("Tool '" + toolName + "' failed.",
                        dev.agentkit.core.prompt.Source.of("tool", toolName), e).attributedTo(tool.get());
                // Judged too. A tool that throws returns whoever's words were in the
                // exception (#113), so failing is not a way to read without paying for it.
                lowerIfNeeded(floor, lowered, failure);
                return failure;
            }
        };
    }

    private static void lowerIfNeeded(TrustFloor floor, AtomicBoolean lowered,
                                      ToolResult result) {
        // No exists() check: a floor that does not exist has an empty trigger set, so
        // lowersOn answers false for everything and the extra condition only reads as
        // though it were doing something.
        if (!lowered.get() && floor.lowersOn(result.provenance())) {
            lowered.set(true);
        }
    }
}
