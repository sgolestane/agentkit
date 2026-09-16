package dev.agentkit.core.codeexec;

import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single {@link Tool} that lets the model orchestrate other tools by writing a
 * script, run in a {@link CodeSandbox}, rather than calling each tool through a
 * separate model turn.
 *
 * <p>Register this <em>instead of</em> the underlying tools, and the model calls it
 * with a {@code code} argument. The script may call the underlying tools many times;
 * only its final output returns to the model, so a task that would take twenty tool
 * round-trips (each re-sending the growing context) becomes one turn that returns a
 * few summary lines. The tool's description advertises the callable tools as the
 * script's available API.
 *
 * <p><strong>Security &amp; observability.</strong> Two agent-level guards do not
 * reach inside a script and must be handled here:
 * <ul>
 *   <li>A {@link ToolGate} set on the {@code Agent} governs only the outer
 *       code-execution call, not the tools the script invokes. The builder therefore
 *       requires an explicit gate decision — pass a policy via
 *       {@link Builder#toolGate(ToolGate)} (approval, deny-lists) to apply it to
 *       code-called tools, or opt into no gate with {@link Builder#allowAllTools()}.
 *       There is no fail-open default: forgetting to choose fails
 *       {@link Builder#build()} loudly rather than silently allowing every
 *       code-called tool. That gate is also invisible to everything <em>above</em> this
 *       tool, being consulted inside {@link #execute}, so the tool declares what it holds
 *       through {@link Tool#holdsGateWaitingForAHuman()} and
 *       {@link Tool#holdsGateBoundToOneRun()} — which is what lets a durable worker refuse
 *       an unshareable one at registration (#283). {@link Tool#boundToOneRun()} is carried
 *       the same way and for the same reason, over the bridged tools rather than the gate
 *       (#328).</li>
 *   <li>The {@code AgentObserver} and the eval-harness trajectory see only the
 *       {@code run_code} call, not the tools invoked inside it. Use the gate as the
 *       audit/enforcement point for those, and expect eval tool-use checks to see
 *       {@code run_code} rather than the underlying calls.</li>
 * </ul>
 * In-script tool calls also never round-trip the model, so {@code maxSteps} and any
 * token budget do not bound them; the builder caps them at
 * {@value Builder#DEFAULT_MAX_TOOL_CALLS} per run by default (see
 * {@link Builder#maxToolCalls(int)}). Executing model-written code requires real
 * isolation; see {@link CodeSandbox}.
 */
public final class CodeExecutionTool implements Tool {

    /** The default tool name. */
    public static final String DEFAULT_NAME = "run_code";

    private final String name;
    private final String language;
    private final CodeSandbox sandbox;
    private final ToolRegistry tools;
    private final dev.agentkit.core.reliability.TrustFloor toolFloor;
    private final int maxToolCalls;
    private final SideEffects sideEffects;
    // Computed once here rather than read live from `tools`, so a registry that has been
    // handed this very tool cannot make the answer recurse into itself. Snapshot semantics
    // are the same as the description above: both are fixed at build time, and the builder
    // already says to build the tool after the registry is finalised.
    private final boolean holdsGateWaitingForAHuman;
    private final boolean holdsGateBoundToOneRun;
    private final boolean boundToOneRun;
    // Lifetime-unique ids across runs (shared by every per-run bridge), while the call
    // cap is enforced by a fresh counter per run — see execute().
    private final AtomicLong callIdSequence = new AtomicLong();
    private final String description;

    private CodeExecutionTool(Builder b) {
        this.name = b.name;
        this.language = b.language;
        this.sandbox = b.sandbox;
        this.tools = b.tools;
        this.toolFloor = b.toolFloor != null
                ? b.toolFloor
                : dev.agentkit.core.reliability.TrustFloor.none(b.toolGate);
        this.maxToolCalls = b.maxToolCalls;
        this.sideEffects = b.sideEffects;
        // OR across the bridge gate and the bridged tools, because either can be the one
        // holding the hazard: a nested CodeExecutionTool is itself a tool with a gate.
        this.holdsGateWaitingForAHuman = this.toolFloor.waitsForAHuman()
                || b.tools.tools().stream().anyMatch(Tool::holdsGateWaitingForAHuman);
        this.holdsGateBoundToOneRun = this.toolFloor.boundToOneRun()
                || b.tools.tools().stream().anyMatch(Tool::holdsGateBoundToOneRun);
        // The third, and it is a different question from the two above despite the name it
        // shares with the second: TrustFloor.boundToOneRun() asks about the bridge GATE,
        // Tool.boundToOneRun() asks whether a bridged tool holds one run's STATE. Only the
        // gate half was asked, so a registry holding ScopeTools' run-bound tools produced a
        // run_code that answered false to every question a durable worker asks -- and every
        // run on the task queue could then reach one run's AgentScope by writing a script
        // (#328). A bridge that launders the declaration is the hazard #283 exists for.
        this.boundToOneRun = b.tools.tools().stream().anyMatch(Tool::boundToOneRun);
        this.description = buildDescription(language, b.tools.tools().stream().map(Tool::spec).toList());
    }

    /**
     * Whether the bridge gate — or a tool it bridges to — may block waiting for a person.
     *
     * <p>Forwarded from the gate this tool was built with, and the reason the declaration
     * exists on {@link Tool} at all (#283). Nothing wiring this tool can reach that gate:
     * it is consulted inside {@link #execute}, below the loop and below a durable runner's
     * registration checks. Before this, {@code toolGate(ToolGates.requireApproval(pred,
     * humanApprover))} registered on a Temporal worker was accepted, and the approver was
     * then paged once per activity retry.
     */
    @Override
    public boolean holdsGateWaitingForAHuman() {
        return holdsGateWaitingForAHuman;
    }

    /**
     * Whether the bridge gate — or a tool it bridges to — was built for one run.
     *
     * <p>The sibling of {@link #holdsGateWaitingForAHuman()}.
     * {@code toolGate(ToolGates.screeningAgainst(objective, screen))} is the case: an
     * immutable gate that is nonetheless one run's, which a shared worker would apply to
     * every other run's scripts.
     */
    @Override
    public boolean holdsGateBoundToOneRun() {
        return holdsGateBoundToOneRun;
    }

    /**
     * Whether a tool this bridges to holds state built for one run (#328).
     *
     * <p>Not the same question as {@link #holdsGateBoundToOneRun()}, which is about the
     * bridge's <em>gate</em>. A script can call anything in the registry, so {@code run_code}
     * is exactly as run-bound as the most run-bound tool it can reach, and a durable worker
     * that asked only the gate question would have registered it clean.
     */
    @Override
    public boolean boundToOneRun() {
        return boundToOneRun;
    }

    @Override
    public SideEffects sideEffects() {
        return sideEffects;
    }

    /**
     * @param sandbox the sandbox that runs the code
     * @param tools   the tools callable from within the code
     */
    public static Builder builder(CodeSandbox sandbox, ToolRegistry tools) {
        return new Builder(sandbox, tools);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("code", Map.of(
                        "type", "string",
                        "description", "The " + language + " script to run.")),
                "required", List.of("code"));
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String code = invocation.stringArgument("code");
        if (code == null || code.isBlank()) {
            return ToolResult.error("The 'code' argument is required and must be a non-empty script.");
        }
        // A fresh bridge per run: the tool-call cap resets each script run (a single
        // long-lived tool must not accumulate one budget across turns), while ids stay
        // unique across runs via the shared callIdSequence.
        // The floor is per script run, which is what makes it correct here: a bridge is
        // built on every execute, so the bit cannot leak into another script. Without it a
        // floor on the agent loop bought nothing against read-then-write inside one script
        // — the loop's floor lowers when this whole call returns, which is afterwards.
        ToolBridge inner = ToolBridges.of(tools, toolFloor, maxToolCalls, callIdSequence);
        // Wrapped here rather than inside the bridge, because the accumulation is scoped to
        // one script run and ToolBridge is a two-argument functional interface with nowhere
        // to hang a result on.
        java.util.concurrent.atomic.AtomicReference<Provenance> watched =
                new java.util.concurrent.atomic.AtomicReference<>(Provenance.UNKNOWN);
        ToolBridge bridge = (toolName, arguments) -> {
            ToolResult called = inner.invoke(toolName, arguments);
            if (called.provenance() == Provenance.THIRD_PARTY) {
                watched.set(Provenance.THIRD_PARTY);
            }
            return called;
        };
        SandboxExecution result;
        try {
            result = sandbox.run(code, bridge);
        } catch (RuntimeException e) {
            // A sandbox transport/infra failure must not abort the run.
            // Fenced (#113). A sandbox's failure message carries whatever the script,
            // the interpreter or the host had to say about it.
            // Attributed with what the script had already read, like the two paths below.
            // It was not, and that laundered the read: a script that fetched a page and
            // then made the sandbox die returned UNKNOWN, so a trust floor keyed on
            // THIRD_PARTY never lowered and the next turn could write. A remote sandbox
            // throwing on transport or timeout after bridge calls is routine, and whoever
            // wrote the page can steer the model into a script that ends that way (#122).
            return ToolResult.from(watched.get(),
                    ToolResult.failed("Code execution failed.", dev.agentkit.core.prompt.Source.of("sandbox"), e).content()).asError();
        }
        if (result == null) {
            // Same reasoning: a sandbox that answers nothing has still let the script read.
            return ToolResult.from(watched.get(), "Code execution returned no result.").asError();
        }
        // The aggregator case, and the one place a truthful per-call answer is cheap: the
        // bridge sees every inner result's provenance, so a script that read one page has
        // laundered a THIRD_PARTY result through the sandbox and the output says so.
        //
        // Never FIRST_PARTY, whatever the script called: the output is whatever
        // model-written code chose to print, so the floor is UNKNOWN rather than ours.
        Provenance observed = watched.get();
        if (result.error()) {
            // A traceback is the normal failure mode here and it never threw, so the catch
            // above never saw it (#113). It carries whatever the script, the interpreter or
            // the host had to say — and the script is model-written, which is why the
            // provenance floor below is UNKNOWN rather than ours.
            // Built by ToolResult.failed and re-attributed, rather than given a fifth
            // overload: the observed provenance is this tool's own answer and must not be
            // replaced by the failure helper's default.
            return ToolResult.from(observed,
                    ToolResult.failed("Code execution failed.", dev.agentkit.core.prompt.Source.of("sandbox"), result.output())
                            .content()).asError();
        }
        return ToolResult.from(observed, result.output());
    }

    private static String buildDescription(String language, List<ToolSpec> callable) {
        StringBuilder sb = new StringBuilder("Run a ").append(language)
                .append(" script that orchestrates tools. Call the tools listed below as functions;"
                        + " only what your script prints or returns comes back, so do multi-step work"
                        + " (loops, filtering, aggregation) in code to avoid a round-trip per tool call.");
        if (callable.isEmpty()) {
            sb.append(" No tools are currently available to the script.");
        } else {
            sb.append(" Available tools:");
            for (ToolSpec spec : callable) {
                sb.append("\n- ").append(spec.name()).append(": ").append(spec.description());
            }
        }
        return sb.toString();
    }

    /** Builder for {@link CodeExecutionTool}. */
    public static final class Builder {

        /** The default per-run cap on tool calls a script may make. */
        public static final int DEFAULT_MAX_TOOL_CALLS = 1000;

        private final CodeSandbox sandbox;
        private final ToolRegistry tools;
        private String name = DEFAULT_NAME;
        private String language = "Python";
        // Null until the caller makes an explicit gate decision; build() rejects null,
        // so there is no fail-open default (see toolGate / allowAllTools).
        private ToolGate toolGate;
        private boolean gateSet;
        private dev.agentkit.core.reliability.TrustFloor toolFloor;
        private boolean floorSet;
        private SideEffects sideEffects = SideEffects.UNKNOWN;
        private int maxToolCalls = DEFAULT_MAX_TOOL_CALLS;

        private Builder(CodeSandbox sandbox, ToolRegistry tools) {
            this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
            this.tools = Objects.requireNonNull(tools, "tools");
        }

        /** Overrides the tool name (default {@value #DEFAULT_NAME}). */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** The script language named in the tool description (default {@code Python}). */
        public Builder language(String language) {
            this.language = Objects.requireNonNull(language, "language");
            return this;
        }

        /**
         * A gate applied to every tool the script calls — the only way to enforce an
         * approval or deny-list policy inside the sandbox, since the agent's own gate
         * does not reach code-called tools. Set this whenever any bridged tool is hard
         * to reverse. Choosing a gate (or {@link #allowAllTools()}) is mandatory: there
         * is no allow-all default.
         */
        /**
         * Tightens the policy for the rest of a <em>script</em> once it has read somebody
         * else's words (#122).
         *
         * <p>The agent loop's floor does not reach inside a script, and cannot: read and
         * write happen inside one outer {@code Tool.execute}, so the loop has nowhere to
         * intervene between them. A floor wired here does, because the bridge is built per
         * script run.
         *
         * <p>Satisfies the same mandatory-choice rule as {@link #toolGate}: a floor is a
         * policy, so wiring one counts as having chosen.
         */
        public Builder toolFloor(dev.agentkit.core.reliability.TrustFloor toolFloor) {
            this.toolFloor = Objects.requireNonNull(toolFloor, "toolFloor");
            this.toolGate = toolFloor.ordinarily();
            this.floorSet = true;
            return this;
        }

        /**
         * A gate applied to every tool the script calls — the only way to enforce an
         * approval or deny-list policy inside the sandbox, since the agent's own gate
         * does not reach code-called tools. Set this whenever any bridged tool is hard
         * to reverse. Choosing a gate (or {@link #allowAllTools()} or
         * {@link #toolFloor(dev.agentkit.core.reliability.TrustFloor)}) is mandatory:
         * there is no allow-all default.
         *
         * <p><strong>This gate travels with the tool, so a durable worker refuses two kinds
         * of it (#283).</strong> The tool is registered once per task queue and serves every
         * run, exactly as the worker's own gate does — so a gate here that
         * {@code waitsForAHuman()} would be re-asked on every activity retry, and one that
         * is {@code boundToOneRun()} would judge every run's scripts against one run's
         * objective. The built tool reports both through
         * {@link Tool#holdsGateWaitingForAHuman()} and {@link Tool#holdsGateBoundToOneRun()},
         * and {@code TemporalAgent.register} refuses it at registration rather than in
         * production. In process neither is a problem: a blocking approver blocks the run's
         * own thread, and a per-run gate is per-run because the tool is built per run.
         *
         * <p><strong>An approver that decides without waiting has to say so, and a lambda
         * says so with {@code Approver.withoutWaiting(...)} (#290).</strong>
         * {@code Approver.waitsForAHuman()} presumes {@code true}, so
         * {@code requireApproval(pred, (tool, invocation) -> ...)} wired here is refused
         * durably however fast it answers — not because the check is wrong but because
         * nothing had asked the approver. That factory is the answer; the declaration stays
         * where the thing that knows it lives, rather than becoming a flag on this builder.
         */
        public Builder toolGate(ToolGate toolGate) {
            this.toolGate = Objects.requireNonNull(toolGate, "toolGate");
            this.toolFloor = null;
            this.gateSet = true;
            return this;
        }

        /**
         * Explicitly opts into running every code-called tool with no gate. This is
         * fail-open by design, so it is an explicit choice rather than a default — use
         * it only when every bridged tool is safe to call freely from model-written
         * code. When any tool is hard-to-reverse, use {@link #toolGate(ToolGate)}.
         */
        public Builder allowAllTools() {
            this.toolGate = ToolGate.ALLOW_ALL;
            this.toolFloor = null;
            this.gateSet = true;
            return this;
        }

        /**
         * Caps the number of tool calls a single script run may make (default
         * {@value #DEFAULT_MAX_TOOL_CALLS}). In-script calls do not round-trip the
         * model, so this is the only bound on how many times one {@code run_code} turn
         * can invoke tools; lower it for side-effecting tools, raise it for read-heavy
         * fan-out, or pass {@link ToolBridges#UNLIMITED} to remove the cap.
         *
         * @param maxToolCalls the maximum number of tool calls per run ({@code > 0})
         */
        public Builder maxToolCalls(int maxToolCalls) {
            if (maxToolCalls <= 0) {
                throw new IllegalArgumentException("maxToolCalls must be > 0, was: " + maxToolCalls);
            }
            this.maxToolCalls = maxToolCalls;
            return this;
        }

        /**
         * Declares what running a script does, default {@link SideEffects#UNKNOWN}.
         *
         * <p>{@code run_code} is undeclared by default because its own effects are
         * whatever the bridged tools' are. That means a rehearsal refuses it outright,
         * which is safe but takes programmatic tool calling out of every rehearsal.
         *
         * <p>Declare it {@link SideEffects#NONE} <strong>only</strong> if the bridge gate
         * is read-only over the tools the bridge can reach —
         * {@code toolGate(ToolGates.readOnly())} — so a script cannot reach a writer
         * either. That composes: an in-script call to a writer comes back as an error
         * result and the handler never runs. {@link Builder#build()} refuses the
         * combination it cannot support — {@code NONE} over a gate that does not refuse
         * writers — because that would produce a {@code run_code} a rehearsal runs and a
         * script that writes freely. If your gate refuses writers by some means the
         * builder cannot see, drop this call, build, and vouch for the result with
         * {@code Tools.withSideEffects(tool, SideEffects.NONE)} instead.
         *
         * <p>The gate is the only half of this the builder can check. A script's own I/O
         * is the sandbox's business, so {@code NONE} is also a claim that your
         * {@link CodeSandbox} does not let a script reach the network or the filesystem
         * directly — nothing here can tell whether it does.
         */
        public Builder sideEffects(SideEffects sideEffects) {
            this.sideEffects = Objects.requireNonNull(sideEffects, "sideEffects");
            return this;
        }

        /**
         * @throws IllegalStateException if no gate decision was made — call
         *                               {@link #toolGate(ToolGate)} or
         *                               {@link #allowAllTools()} first — or if
         *                               {@code run_code} is declared
         *                               {@link SideEffects#NONE} over a bridge gate that
         *                               does not refuse writers
         */
        public CodeExecutionTool build() {
            if (gateSet && floorSet) {
                // Symmetrical with Agent.Builder, and this class is why that one throws: a
                // floor already holds the policy a gate would be, so honouring one means
                // dropping the other. Both directions used to win silently, and the damage
                // was not hypothetical — measured with
                // .toolFloor(afterThirdParty(ALLOW_ALL, readOnly())).toolGate(ALLOW_ALL),
                // a script fetched a THIRD_PARTY page and then published. The reverse order
                // refused the publish. Same wiring, opposite security, decided by line
                // order — and this is the only wiring point the code-execution floor has.
                throw new IllegalStateException(
                        "toolGate(...) and toolFloor(...) are alternatives, not a pair: a"
                                + " floor already holds the policy a gate would be. Set one.");
            }
            if (toolGate == null) {
                throw new IllegalStateException("A tool gate is required: call toolGate(...) to apply a "
                        + "policy to code-called tools, or allowAllTools() to explicitly run them ungated. "
                        + "The agent's own gate does not reach tools called from inside a script.");
            }
            // The NONE declaration is a claim about what a script can do, and a script can
            // do whatever the bridge gate lets it. Declaring NONE over a permissive gate
            // produces a run_code that a rehearsal executes and a script that writes
            // freely — fail-open, and invisible until something is sent for real.
            // Asked of the floor, which answers AND across both policies: a run reaches
            // both, so a floor whose ordinary policy lets writers through cannot support a
            // NONE declaration however strict it becomes later — the writes happen first.
            dev.agentkit.core.reliability.TrustFloor declared = toolFloor != null
                    ? toolFloor
                    : dev.agentkit.core.reliability.TrustFloor.none(toolGate);
            if (sideEffects == SideEffects.NONE && !declared.guaranteesReadOnly()) {
                // Name the policy that actually answered no. The message used to print the
                // ordinary gate while reporting the floor's AND across both, so a floor of
                // (readOnly, parkForApproval) — which this repository's own javadoc
                // recommends — was told that readOnly() answers guaranteesReadOnly() ==
                // false. It answers true. The remediation that followed then pointed the
                // reader at a gate that was already correct.
                ToolGate blame = declared.ordinarily().guaranteesReadOnly()
                        ? declared.onceLowered()
                        : declared.ordinarily();
                throw new IllegalStateException("'" + name + "' is declared SideEffects.NONE, but its "
                        + "tool policy (" + blame.getClass().getName() + ") answers "
                        + "ToolGate.guaranteesReadOnly() == false, so a script can still reach a writer. "
                        + "If that gate wraps another, forward guaranteesReadOnly() to the delegate — a "
                        + "decorator must forward it, as it must forward the decision. If it refuses writers "
                        + "by some means this cannot see, drop the sideEffects(NONE) call, build, and "
                        + "vouch for the result with Tools.withSideEffects(tool, SideEffects.NONE). "
                        + "Otherwise gate the bridge with ToolGates.readOnly(), or narrow the policy you "
                        + "have with ToolGates.allOf(yourGate, ToolGates.readOnly()).");
            }
            return new CodeExecutionTool(this);
        }
    }
}
