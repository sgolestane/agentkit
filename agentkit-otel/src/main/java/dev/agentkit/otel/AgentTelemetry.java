package dev.agentkit.otel;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolSpec;
import dev.agentkit.core.concurrent.TaskContext;
import dev.agentkit.core.util.Quoted;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * OpenTelemetry traces and metrics for AgentKit, following the
 * <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/">GenAI semantic
 * conventions</a>.
 *
 * <p>Instrumentation is opt-in and applied by decoration, so nothing in
 * {@code agentkit-core} knows about it:
 *
 * <pre>{@code
 * AgentTelemetry telemetry = AgentTelemetry.using(openTelemetry, "anthropic");
 *
 * Agent agent = Agent.builder(
 *         telemetry.instrument("agent", llm),
 *         telemetry.instrument(tools),
 *         config).build();
 *
 * AgentResult result = telemetry.invokeAgent("researcher", () -> agent.run(goal));
 * }</pre>
 *
 * <p>That produces one {@code invoke_agent} span per run, a child {@code chat} span per
 * model call, and a child {@code execute_tool} span per tool execution, plus the
 * {@code gen_ai.client.token.usage} and {@code gen_ai.client.operation.duration}
 * metrics.
 *
 * <p><strong>Instrument every client, not just the agent's.</strong> The loop's own
 * turns are often the smaller half of a run: compaction, verification, reflection,
 * planning and critics each hold their own {@link LlmClient}. Wrap each with its own
 * role — {@code telemetry.instrument("compaction", llm)} — and the trace attributes the
 * spend correctly. Wrap only the agent's and the rest is invisible, which is the same
 * blind spot {@code UsageMeter} exists to close.
 *
 * <p><strong>Not everything is covered.</strong> Durable runs are not: {@code
 * agentkit-temporal} executes model and tool calls as activities, and Temporal does not
 * carry OTel context across that boundary on its own, so instrumented activities would
 * each become a detached root — use Temporal's own tracing interceptors there. Nor is
 * the approval gate: a {@code ToolGate} is evaluated before {@code Tool.execute}, so a
 * each become a detached root — use Temporal's own tracing interceptors there. Anything
 * that fans out needs {@link #taskContext()}, and a {@code ToolGate} needs
 * {@link #instrumentGate(ToolGate)} — the loop evaluates a gate before {@code
 * Tool.execute}, so a denied call is invisible to {@link #instrument(Tool)}.
 *
 * <p><strong>No prompts or completions are recorded</strong> — not on spans, not as
 * events. The conventions make message content opt-in precisely because it is the user's
 * data, and a default that ships conversations to a trace backend is the wrong default
 * for a library. Add what you need yourself, on {@link Span#current()}, where you know
 * what is safe to emit.
 */
public final class AgentTelemetry {

    /** The instrumentation scope reported for everything this module emits. */
    public static final String INSTRUMENTATION_NAME = "dev.agentkit.otel";

    // Deliberately alarming, matching UsageMeter's default for the same reason: a
    // forgotten instrument() should be visible on a dashboard, not quietly filed under
    // "agent" alongside the calls that really were the agent's.
    private static final String DEFAULT_ROLE = "unattributed";
    private static final String DEFAULT_PROVIDER = "agentkit";

    /**
     * One shared instance: it holds no state, and handing out a fresh object per call
     * would make two {@code taskContext()} results unequal — a trap for any later
     * idempotence check, and pointless allocation on a hot path.
     */
    private static final TaskContext TASK_CONTEXT = new TaskContext() {
        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
            // Context.current() is read here, on the submitting thread. Reading it inside
            // the returned callable would read the worker's context, which is exactly the
            // empty one this exists to avoid.
            return Context.current().wrap(task);
        }

        @Override
        public String toString() {
            return "AgentTelemetry.taskContext()";
        }
    };

    private final Tracer tracer;
    private final LongHistogram tokenUsage;
    private final DoubleHistogram operationDuration;
    private final String provider;

    private AgentTelemetry(OpenTelemetry openTelemetry, String provider) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);
        // Explicit buckets, because the SDK's generic defaults are unusable for both of
        // these. That default's first non-zero boundary is 5 and its top is 10000: for a
        // duration in *seconds* nearly every chat call lands in the first bucket or two,
        // and for token counts any modern context window lands in the overflow bucket.
        // Either way the percentiles are unrecoverable. These follow the boundaries the
        // GenAI conventions advise for these two instruments.
        this.tokenUsage = meter.histogramBuilder(GenAi.METRIC_TOKEN_USAGE)
                .setUnit(GenAi.METRIC_TOKEN_USAGE_UNIT)
                .setDescription("Number of input and output tokens used.")
                .ofLongs()
                .setExplicitBucketBoundariesAdvice(GenAi.TOKEN_USAGE_BUCKETS)
                .build();
        this.operationDuration = meter.histogramBuilder(GenAi.METRIC_OPERATION_DURATION)
                .setUnit(GenAi.METRIC_OPERATION_DURATION_UNIT)
                .setDescription("GenAI operation duration.")
                .setExplicitBucketBoundariesAdvice(GenAi.OPERATION_DURATION_BUCKETS)
                .build();
    }

    /**
     * Instrumentation backed by {@code openTelemetry}. Pass the configured SDK instance;
     * {@link OpenTelemetry#noop()} disables everything without changing the wiring.
     */
    public static AgentTelemetry using(OpenTelemetry openTelemetry) {
        return new AgentTelemetry(openTelemetry, DEFAULT_PROVIDER);
    }

    /**
     * As {@link #using(OpenTelemetry)}, but reporting {@code provider} as
     * {@code gen_ai.provider.name} — {@code "anthropic"}, {@code "aws.bedrock"},
     * {@code "openrouter"}. Worth setting: the default is a placeholder, because this
     * module decorates the provider-agnostic {@link LlmClient} interface and cannot tell
     * what is underneath it.
     */
    public static AgentTelemetry using(OpenTelemetry openTelemetry, String provider) {
        if (Objects.requireNonNull(provider, "provider").isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        return new AgentTelemetry(openTelemetry, provider);
    }

    // --- decoration ----------------------------------------------------------

    /** Traces {@code llm}'s calls under the default {@code agent} role. */
    public LlmClient instrument(LlmClient llm) {
        return instrument(DEFAULT_ROLE, llm);
    }

    /**
     * Traces {@code llm}'s calls, attributed to {@code role} — {@code agent},
     * {@code compaction}, {@code verification}, or whatever names the caller. See the
     * class javadoc on why the framework's own clients are worth naming separately.
     */
    public LlmClient instrument(String role, LlmClient llm) {
        Objects.requireNonNull(role, "role");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        Objects.requireNonNull(llm, "llm");
        // Idempotent, because the docs push hard on instrumenting every client and that
        // invites wrapping once at the builder and again at a call site. Wrapping twice
        // would double every span and every metric sample — a silently wrong token
        // total, which is worse than no telemetry.
        if (llm instanceof TracingLlmClient traced && traced.belongsTo(tracer)) {
            return traced;
        }
        return new TracingLlmClient(tracer, tokenUsage, operationDuration, role, provider, llm);
    }

    /** Traces {@code tool}'s executions. Idempotent, as {@link #instrument(String, LlmClient)} is. */
    public Tool instrument(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        if (tool instanceof TracingTool traced && traced.belongsTo(tracer)) {
            return traced;
        }
        return new TracingTool(tracer, tool);
    }

    /**
     * Traces {@code gate}'s decisions, so a blocked call and the time a human approver
     * took are both visible.
     *
     * <p>Named apart from the {@code instrument} overloads. The original reason has
     * expired: {@link ToolGate} and {@link LlmClient} were both single-argument
     * single-method interfaces, so an {@code instrument} taking a gate made every lambda
     * call site of either one ambiguous. {@code ToolGate}'s method is now two-argument,
     * so arity separates them. The name stays because it says which thing is instrumented.
     */
    public ToolGate instrumentGate(ToolGate gate) {
        Objects.requireNonNull(gate, "gate");
        if (gate instanceof TracingToolGate traced && traced.belongsTo(tracer)) {
            return traced;
        }
        return new TracingToolGate(tracer, gate);
    }

    /**
     * Traces both of a {@link TrustFloor}'s policies (#122).
     *
     * <p>Both, because a run reaches both and an operator whose dashboard traced only the
     * ordinary one would see a run go quiet at exactly the moment the tightened policy
     * started refusing things — which is the #121 shape, a control that stops the call
     * without a span to say so.
     *
     * <p>What it does <em>not</em> add is an attribute saying which policy was in force.
     * That is run state, and a gate is not told it: the runner picks the gate and the gate
     * answers about the call. The span for a call made after the floor lowered is the
     * tightened gate's span, and telling them apart from the outside means giving the two
     * gates different names — which a deployment can do and this cannot do for it.
     */
    public TrustFloor instrumentFloor(TrustFloor floor) {
        Objects.requireNonNull(floor, "floor");
        ToolGate ordinarily = instrumentGate(floor.ordinarily());
        // One gate wired twice stays one gate. Wrapping it twice produced two distinct
        // TracingToolGate objects, which stopped it *being* one gate wired twice — and
        // TrustFloor.none is exactly that shape, so instrumenting a floor with no floor
        // threw. Two public methods added together, composed the obvious way.
        ToolGate onceLowered = floor.ordinarily() == floor.onceLowered()
                ? ordinarily
                : instrumentGate(floor.onceLowered());
        return new TrustFloor(ordinarily, onceLowered, floor.lowersOn());
    }

    /**
     * Traces every tool in {@code registry}, leaving its disclosure policy alone: which
     * specs are advertised on a turn is the registry's decision, and this wrapper
     * forwards that question rather than answering it. Tools are wrapped as they are
     * handed out, so a registry that reveals tools progressively still does.
     */
    public ToolRegistry instrument(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        // Wrappers are memoised, so repeated lookups of the same tool return the same
        // object. Handing out a fresh wrapper each time would make a tool that is `==`
        // to itself through the underlying registry stop being so through this one.
        Map<Tool, Tool> wrappers = new ConcurrentHashMap<>();
        return new ToolRegistry() {
            @Override
            public Optional<Tool> find(String name) {
                return registry.find(name).map(this::wrap);
            }

            @Override
            public List<Tool> tools() {
                return registry.tools().stream().map(this::wrap).toList();
            }

            private Tool wrap(Tool tool) {
                return wrappers.computeIfAbsent(tool, AgentTelemetry.this::instrument);
            }

            @Override
            public List<ToolSpec> advertisedSpecs() {
                return registry.advertisedSpecs();
            }

            @Override
            public String toString() {
                return "TracingToolRegistry[" + registry + "]";
            }
        };
    }

    /**
     * Carries the current trace context into work handed to AgentKit's parallel
     * primitives, so concurrent branches stay in one trace.
     *
     * <p><strong>Set this whenever anything fans out.</strong> OpenTelemetry context is
     * thread-local and does not survive {@code submit}, so without it a supervised run of
     * three subagents arrives at the backend as four unrelated traces, each subagent a
     * detached root with no sign of the run that started it.
     *
     * <pre>{@code
     * Supervisor supervisor = Supervisor.builder(roster)
     *         .taskContext(telemetry.taskContext())
     *         .build();
     *
     * AgentGraph graph = AgentGraph.builder()
     *         .taskContext(telemetry.taskContext())
     *         // ... nodes and edges
     *         .build();
     * }</pre>
     *
     * <p>Prefer this to {@link #instrument(ExecutorService)}: both primitives fall back to
     * a fresh per-call executor that nothing outside can wrap, so wrapping the executor
     * fixes only the configuration where one was injected — and silently stops working the
     * day someone removes the injection.
     */
    public TaskContext taskContext() {
        return TASK_CONTEXT;
    }

    /**
     * Wraps {@code executor} so work submitted to it runs with the submitting thread's
     * trace context.
     *
     * <p>For executors you own and hand to something other than AgentKit's primitives.
     * For those, prefer {@link #taskContext()}, which also covers their default per-call
     * executor.
     */
    public ExecutorService instrument(ExecutorService executor) {
        return Context.taskWrapping(Objects.requireNonNull(executor, "executor"));
    }

    // --- the run span --------------------------------------------------------

    /**
     * Runs {@code work} inside an {@code invoke_agent} span, so the model and tool spans
     * it produces hang off one run.
     *
     * <p>Deliberately a wrapper rather than an {@code AgentObserver}: an observer would
     * have to open a scope in {@code onStart} and close it in {@code onFinish}, and an
     * exception escaping between the two would leave the calling thread's context
     * pointing at a finished span — which, on a pooled thread, then corrupts unrelated
     * work. Try-with-resources cannot get that wrong. It also works for anything that
     * runs an agent, not just {@code Agent} itself.
     *
     * <h4>Still no {@code agentkit.run.id} on the span, and now for a sharper reason (#317)</h4>
     *
     * <p>#311 said no on the grounds that this is a wrapper and not an observer, so it never
     * sees an {@code AgentRun} — and that span parentage already answers "on whose behalf"
     * across a thread hop, which a JVM-scoped counter cannot. #317 then added a parent link
     * to {@code AgentRun} and asked whether that changes the answer. It does not, and the
     * reason is worth stating because the obvious reading is that it should.
     *
     * <p>An id here would buy one thing: a <em>join</em> between the span tree and the
     * observer's rows, letting a reviewer move from a span to the trail and back. But with
     * the parent link both views are already trees answering the same question
     * independently, so the join is the whole of the value — and the join that could be
     * built is a partial one. {@code TracingTool} does now learn the run, through
     * {@code Tool.boundTo}, so tool spans <em>could</em> carry it. This method is what names
     * an agent, and it takes a {@code String} and a {@code Supplier} from a caller that has
     * no {@code AgentRun} in hand: the run span above those tool spans could not. A tree in
     * which the leaves carry a run id and the node naming the run does not invites exactly
     * the wrong inference — that a span without the attribute belongs to no run — and this
     * repository's rule for a fact a reviewer will act on is that it is right everywhere or
     * absent.
     *
     * <p>What would change the answer, stated so it does not have to be rediscovered a third
     * time: an overload of this method taking the {@link dev.agentkit.core.agent.AgentRun}
     * the work will run under. Then the run span, the tool spans beneath it and the
     * observer's rows would all carry the same id and the same parentage, and the attribute
     * would belong on all of them at once. That is a change to how a deployment calls this,
     * not a change here, which is why it is not being made as a rider.
     */
    public <T> T invokeAgent(String agentName, Supplier<T> work) {
        Objects.requireNonNull(agentName, "agentName");
        Objects.requireNonNull(work, "work");
        Span span = tracer.spanBuilder(GenAi.OPERATION_INVOKE_AGENT + " " + agentName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAi.OPERATION_NAME, GenAi.OPERATION_INVOKE_AGENT)
                .setAttribute(GenAi.PROVIDER_NAME, provider)
                .setAttribute(GenAi.AGENT_NAME, agentName)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            T result = work.get();
            describe(span, result);
            return result;
        } catch (RuntimeException e) {
            span.setAttribute(GenAi.ERROR_TYPE, e.getClass().getName());
            // Escaped, and recorded through the escaped wrapper. A span exporter renders
            // a status message and an exception into an operator's incident view exactly
            // as a log line does, and a tool that names the argument it could not satisfy
            // puts the model's own text there. The log site one frame out already goes
            // through Quoted; this is the second renderer of the same object (#98).
            span.setStatus(StatusCode.ERROR, e.getMessage() == null
                    ? e.getClass().getSimpleName() : Quoted.of(e.getMessage()));
            span.recordException(Quoted.failure(e));
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Adds the run's outcome to the span when {@code work} returned an {@link AgentResult}.
     *
     * <p>A type test rather than a separate {@code Supplier<AgentResult>} overload, which
     * would be ambiguous against the generic one at every call site that uses a lambda.
     * The point is that a failed run does not throw — it returns a result carrying the
     * error — so without this the span for a run that died on step 3 looks identical to
     * one that succeeded.
     */
    private static void describe(Span span, Object result) {
        if (!(result instanceof AgentResult agentResult)) {
            return;
        }
        span.setAttribute(GenAi.AGENTKIT_STOP_REASON,
                agentResult.stopReason().name().toLowerCase(Locale.ROOT));
        span.setAttribute(GenAi.AGENTKIT_STEPS, agentResult.steps());
        // Under agentkit.*, not gen_ai.usage.*, which the child chat spans already carry:
        // a backend summing that attribute across spans would count this run's tokens
        // twice. This is a rollup of those spans, not another observation.
        span.setAttribute(GenAi.AGENTKIT_LOOP_INPUT_TOKENS, agentResult.usage().inputTokens());
        span.setAttribute(GenAi.AGENTKIT_LOOP_OUTPUT_TOKENS, agentResult.usage().outputTokens());
        agentResult.error().ifPresent(error -> {
            // The type is read off the original, so it stays exact for grouping; only the
            // message goes through the escaped wrapper. This is the same throwable Agent
            // has already logged safely, arriving at a second renderer (#98).
            span.setAttribute(GenAi.ERROR_TYPE, error.getClass().getName());
            span.setStatus(StatusCode.ERROR, error.getMessage() == null
                    ? error.getClass().getSimpleName() : Quoted.of(error.getMessage()));
            span.recordException(Quoted.failure(error));
        });
    }
}
