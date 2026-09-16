package dev.agentkit.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.codeexec.CodeExecutionTool;
import dev.agentkit.core.codeexec.SandboxExecution;
import dev.agentkit.core.llm.DelegatingLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import io.opentelemetry.api.OpenTelemetry;
import dev.agentkit.core.graph.AgentGraph;
import dev.agentkit.core.supervisor.DelegatedTask;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.supervisor.Supervisor;
import dev.agentkit.core.supervisor.Synthesizers;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AgentTelemetryTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private AgentTelemetry telemetry;

    @BeforeEach
    void setUp() {
        // Built here rather than in a field initializer: OpenTelemetryExtension resets
        // the meter provider in beforeEach, which orphans any instrument created
        // earlier — the metrics then record into nothing, and an assertion looking for
        // one finds no metric at all rather than a wrong one. (The tracer provider is
        // untouched, so a field-initialized tracer would have worked and hidden this.)
        telemetry = AgentTelemetry.using(OTEL.getOpenTelemetry(), "anthropic");
    }

    // --- fakes ---------------------------------------------------------------

    /** Replays scripted responses, one per call. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<LlmResponse> script = new ArrayDeque<>();
        private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();

        ScriptedLlm(LlmResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            lastRequest.set(request);
            if (script.isEmpty()) {
                throw new IllegalStateException("no scripted response left");
            }
            return script.removeFirst();
        }
    }

    private static LlmResponse text(String body, long in, long out) {
        return new LlmResponse(new Message(Role.ASSISTANT, List.of(new TextBlock(body))),
                LlmStopReason.END_TURN, new TokenUsage(in, out), Optional.empty());
    }

    private static LlmResponse toolCall(String id, String tool) {
        return new LlmResponse(
                new Message(Role.ASSISTANT, List.of(ProposedCall.of(id, tool, Map.of()))),
                LlmStopReason.TOOL_USE, new TokenUsage(5, 5), Optional.empty());
    }

    /** Not a record: some tests subclass it to override execute() or inputExamples(). */
    private static class EchoTool implements Tool {
        private final String name;
        private final ToolResult result;

        EchoTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "echoes";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return result;
        }
    }

    /** A subagent whose run makes a traced model call inside its own invoke_agent span. */
    private Subagent tracedSubagent(String name) {
        return Subagent.handling(name, "handles " + name, subgoal ->
                telemetry.invokeAgent(name, () -> AgentResult.completed(
                        telemetry.instrument(new ScriptedLlm(text(name, 1, 1)))
                                .generate(LlmRequest.builder("m")
                                        .addMessage(Message.user("go")).build()).message().text(), 1)));
    }

    private static SpanData spanNamed(String name) {
        return OTEL.getSpans().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named '" + name + "' in "
                        + OTEL.getSpans().stream().map(SpanData::getName).toList()));
    }

    // --- LLM client ----------------------------------------------------------

    @Test
    void aModelCallBecomesAChatSpanWithTheConventionalAttributes() {
        LlmClient llm = telemetry.instrument("compaction",
                new ScriptedLlm(text("hi", 11, 7)));

        llm.generate(LlmRequest.builder("claude-opus-4-8")
                .maxTokens(512)
                .addMessage(Message.user("go"))
                .build());

        SpanData span = spanNamed("chat claude-opus-4-8");
        assertThat(span.getAttributes().asMap())
                .containsEntry(GenAi.OPERATION_NAME, "chat")
                .containsEntry(GenAi.PROVIDER_NAME, "anthropic")
                .containsEntry(GenAi.REQUEST_MODEL, "claude-opus-4-8")
                .containsEntry(GenAi.REQUEST_MAX_TOKENS, 512L)
                .containsEntry(GenAi.REQUEST_STREAM, false)
                .containsEntry(GenAi.USAGE_INPUT_TOKENS, 11L)
                .containsEntry(GenAi.USAGE_OUTPUT_TOKENS, 7L)
                .containsEntry(GenAi.RESPONSE_FINISH_REASONS, List.of("end_turn"))
                .containsEntry(GenAi.AGENTKIT_ROLE, "compaction");
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void aFailedModelCallIsRecordedAndTheExceptionStillPropagates() {
        LlmClient llm = telemetry.instrument(request -> {
            throw new LlmException("provider exploded");
        });

        assertThatThrownBy(() -> llm.generate(LlmRequest.builder("m")
                .addMessage(Message.user("go")).build()))
                .isInstanceOf(LlmException.class).hasMessage("provider exploded");

        SpanData span = spanNamed("chat m");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(GenAi.ERROR_TYPE)).isEqualTo(LlmException.class.getName());
        assertThat(span.getEvents()).isNotEmpty();
    }

    @Test
    void anErrorNotJustARuntimeExceptionIsRecordedAsAFailure() {
        // Catching only RuntimeException left an OutOfMemoryError or AssertionError from
        // the delegate looking like a success: green span, no error.type, and a duration
        // sample indistinguishable from a healthy call.
        LlmClient llm = telemetry.instrument(request -> {
            throw new AssertionError("the JVM had opinions");
        });

        assertThatThrownBy(() -> llm.generate(LlmRequest.builder("m")
                .addMessage(Message.user("go")).build())).isInstanceOf(AssertionError.class);

        SpanData span = spanNamed("chat m");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(GenAi.ERROR_TYPE)).isEqualTo(AssertionError.class.getName());
        assertThat(span.hasEnded()).isTrue();
        assertThat(Span.current().getSpanContext().isValid()).isFalse();

        var duration = OTEL.getMetrics().stream()
                .filter(m -> m.getName().equals(GenAi.METRIC_OPERATION_DURATION))
                .findFirst().orElseThrow();
        assertThat(duration.getHistogramData().getPoints()).singleElement().satisfies(point ->
                assertThat(point.getAttributes().get(GenAi.ERROR_TYPE))
                        .isEqualTo(AssertionError.class.getName()));
    }

    @Test
    void anErrorFromAToolIsAlsoRecordedAsAFailure() {
        Tool tool = telemetry.instrument(new EchoTool("boom", ToolResult.ok("")) {
            @Override
            public ToolResult execute(ToolInvocation invocation) {
                throw new AssertionError("nope");
            }
        });

        assertThatThrownBy(() -> tool.execute(new ToolInvocation("c", "boom", Map.of())))
                .isInstanceOf(AssertionError.class);

        SpanData span = spanNamed("execute_tool boom");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(GenAi.ERROR_TYPE)).isEqualTo(AssertionError.class.getName());
    }

    @Test
    void spanKindsFollowTheConventions() {
        // chat leaves the process, tool and agent work do not.
        telemetry.instrument(new ScriptedLlm(text("hi", 1, 1)))
                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());
        telemetry.instrument(new EchoTool("t", ToolResult.ok("")))
                .execute(new ToolInvocation("c", "t", Map.of()));
        telemetry.invokeAgent("a", () -> null);

        assertThat(spanNamed("chat m").getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(spanNamed("execute_tool t").getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(spanNamed("invoke_agent a").getKind()).isEqualTo(SpanKind.INTERNAL);
    }

    @Test
    void theProvidersOwnFinishReasonIsReportedRatherThanOurNormalisedOne() {
        // Normalising loses real distinctions — a refusal and a content filter both map
        // to OTHER — and for OpenAI-shaped providers it is wrong outright, reporting
        // `stop` as `end_turn`.
        LlmResponse filtered = new LlmResponse(
                new Message(Role.ASSISTANT, List.of(new TextBlock(""))),
                LlmStopReason.OTHER, new TokenUsage(1, 1), Optional.of("content_filter"));

        telemetry.instrument(new ScriptedLlm(filtered))
                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());

        assertThat(spanNamed("chat m").getAttributes().get(GenAi.RESPONSE_FINISH_REASONS))
                .isEqualTo(List.of("content_filter"));
    }

    @Test
    void theNormalisedReasonIsOnlyAFallback() {
        telemetry.instrument(new ScriptedLlm(text("hi", 1, 1)))
                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());

        assertThat(spanNamed("chat m").getAttributes().get(GenAi.RESPONSE_FINISH_REASONS))
                .isEqualTo(List.of("end_turn"));
    }

    @Test
    void aChatSpanIsCurrentWhileTheDelegateRuns() {
        // So anything the delegate instruments itself — an HTTP client, a nested call —
        // attaches here rather than to whatever ran before it.
        AtomicReference<String> seenParent = new AtomicReference<>();
        telemetry.instrument(request -> {
            seenParent.set(Span.current().getSpanContext().getSpanId());
            return text("hi", 1, 1);
        }).generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());

        assertThat(seenParent.get()).isEqualTo(spanNamed("chat m").getSpanId());
    }

    @Test
    void tokenAndDurationMetricsAreRecordedPerCall() {
        telemetry.instrument("verification", new ScriptedLlm(text("hi", 30, 4)))
                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());

        var tokens = OTEL.getMetrics().stream()
                .filter(m -> m.getName().equals(GenAi.METRIC_TOKEN_USAGE))
                .findFirst().orElseThrow();
        assertThat(tokens.getUnit()).isEqualTo("{token}");
        assertThat(tokens.getHistogramData().getPoints())
                .anySatisfy(point -> {
                    assertThat(point.getAttributes().get(GenAi.TOKEN_TYPE)).isEqualTo("input");
                    assertThat(point.getSum()).isEqualTo(30);
                })
                .anySatisfy(point -> {
                    assertThat(point.getAttributes().get(GenAi.TOKEN_TYPE)).isEqualTo("output");
                    assertThat(point.getSum()).isEqualTo(4);
                });

        var duration = OTEL.getMetrics().stream()
                .filter(m -> m.getName().equals(GenAi.METRIC_OPERATION_DURATION))
                .findFirst().orElseThrow();
        assertThat(duration.getUnit()).isEqualTo("s");
        assertThat(duration.getHistogramData().getPoints()).singleElement().satisfies(point -> {
            assertThat(point.getCount()).isEqualTo(1);
            assertThat(point.getAttributes().get(GenAi.AGENTKIT_ROLE)).isEqualTo("verification");
            // The value, not just the unit label: a fast fake call is well under a
            // second, so recording milliseconds (or dividing by the wrong power of ten)
            // under a unit of `s` would land far outside this range.
            assertThat(point.getSum()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void aFailedCallStillContributesToTheDurationHistogram() {
        // A provider that is slow and then fails is exactly what a latency dashboard is
        // for; dropping those samples would make the histogram describe only successes.
        LlmClient llm = telemetry.instrument(request -> {
            throw new LlmException("boom");
        });
        assertThatThrownBy(() -> llm.generate(LlmRequest.builder("m")
                .addMessage(Message.user("go")).build())).isInstanceOf(LlmException.class);

        var duration = OTEL.getMetrics().stream()
                .filter(m -> m.getName().equals(GenAi.METRIC_OPERATION_DURATION))
                .findFirst().orElseThrow();
        assertThat(duration.getHistogramData().getPoints()).singleElement().satisfies(point ->
                assertThat(point.getAttributes().get(GenAi.ERROR_TYPE))
                        .isEqualTo(LlmException.class.getName()));
    }

    @Test
    void streamingIsForwardedRatherThanCollapsedOntoTheBlockingCall() {
        StringBuilder seen = new StringBuilder();
        LlmClient streamingDelegate = new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                throw new AssertionError("the blocking overload must not be used");
            }

            @Override
            public LlmResponse generate(LlmRequest request, dev.agentkit.core.llm.StreamHandler handler) {
                handler.onTextDelta("par");
                handler.onTextDelta("tial");
                return text("partial", 1, 2);
            }
        };

        telemetry.instrument(streamingDelegate).generate(
                LlmRequest.builder("m").addMessage(Message.user("go")).build(), seen::append);

        assertThat(seen).hasToString("partial");
        assertThat(spanNamed("chat m").getAttributes().get(GenAi.REQUEST_STREAM)).isTrue();
    }

    @Test
    void aTracedClientDoesNotHideWhatItWraps() {
        // The Temporal worker's BudgetLlmClient guard peels decorators before checking
        // the type; a tracer that concealed its delegate would defeat it.
        LlmClient inner = new ScriptedLlm();
        LlmClient traced = telemetry.instrument(inner);

        assertThat(traced).isInstanceOf(DelegatingLlmClient.class);
        assertThat(((DelegatingLlmClient) traced).delegate()).isSameAs(inner);
        assertThat(DelegatingLlmClient.unwrap(traced)).isSameAs(inner);
        assertThat(DelegatingLlmClient.unwrap(telemetry.instrument(traced))).isSameAs(inner);
    }

    // --- tools ---------------------------------------------------------------

    @Test
    void aToolExecutionBecomesAnExecuteToolSpan() {
        Tool tool = telemetry.instrument(new EchoTool("search", ToolResult.ok("found")));

        ToolResult result = tool.execute(new ToolInvocation("call-7", "search", Map.of()));

        assertThat(result.content()).isEqualTo("found");
        SpanData span = spanNamed("execute_tool search");
        assertThat(span.getAttributes().asMap())
                .containsEntry(GenAi.OPERATION_NAME, "execute_tool")
                .containsEntry(GenAi.TOOL_NAME, "search")
                .containsEntry(GenAi.TOOL_CALL_ID, "call-7")
                .containsEntry(GenAi.AGENTKIT_TOOL_ERROR, false);
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void anErrorResultIsAnAttributeNotAFailedSpan() {
        // The loop feeds an error result back to the model and carries on, so marking the
        // span ERROR would report a handled outcome as a fault.
        telemetry.instrument(new EchoTool("search", ToolResult.error("not found")))
                .execute(new ToolInvocation("c", "search", Map.of()));

        SpanData span = spanNamed("execute_tool search");
        assertThat(span.getAttributes().get(GenAi.AGENTKIT_TOOL_ERROR)).isTrue();
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void aThrowingToolIsAFailedSpanAndStillThrows() {
        Tool tool = telemetry.instrument(new EchoTool("boom", ToolResult.ok("")) {
            @Override
            public ToolResult execute(ToolInvocation invocation) {
                throw new IllegalStateException("kaboom");
            }
        });

        assertThatThrownBy(() -> tool.execute(new ToolInvocation("c", "boom", Map.of())))
                .isInstanceOf(IllegalStateException.class);

        SpanData span = spanNamed("execute_tool boom");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(GenAi.ERROR_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void tracingAToolPreservesItsAdvertisedSpecification() {
        // The model picks tools from these. A wrapper that rebuilt the spec from its own
        // getters would look correct for an ordinary tool and quietly discard whatever a
        // tool that overrides spec() put there — so the fixture overrides spec() with
        // something the components cannot reproduce.
        Tool inner = new EchoTool("search", ToolResult.ok("x")) {
            @Override
            public List<Map<String, Object>> inputExamples() {
                return List.of(Map.of("q", "kittens"));
            }

            @Override
            public ToolSpec spec() {
                return new ToolSpec("search", "a hand-written description the getters do not return",
                        Map.of("type", "object"), inputExamples());
            }
        };
        Tool traced = telemetry.instrument(inner);

        assertThat(traced.spec()).isEqualTo(inner.spec());
        assertThat(traced.spec().description()).isEqualTo(
                "a hand-written description the getters do not return");
        assertThat(traced.name()).isEqualTo("search");
        assertThat(traced.description()).isEqualTo("echoes");
        assertThat(traced.inputSchema()).isEqualTo(inner.inputSchema());
        assertThat(traced.inputExamples()).isEqualTo(List.of(Map.of("q", "kittens")));
    }

    @Test
    void tracingAToolPreservesItsSideEffectsSoARehearsalStillRuns() {
        // The declaration's default is UNKNOWN and ToolGates.readOnly refuses UNKNOWN, so
        // a wrapper that let the default answer would turn a rehearsal into
        // deny-everything for exactly the setup the README recommends: instrument the
        // registry, then gate it.
        Tool readOnly = new EchoTool("search", ToolResult.ok("x")) {
            @Override
            public SideEffects sideEffects() {
                return SideEffects.NONE;
            }
        };
        Tool instrumented = telemetry.instrument(readOnly);

        assertThat(instrumented.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(ToolGates.readOnly()
                .evaluate(instrumented, new ToolInvocation("c1", "search", Map.of())).allowed()).isTrue();
    }

    @Test
    void tracingAToolPreservesWhoWroteWhatItReturns() {
        // The sibling declaration, and it had no test at all until #296: the mutation pass
        // dropped TracingTool's provenance() forward and the whole otel suite stayed green,
        // because the span attribute reads the delegate directly rather than through the
        // wrapper. Nothing here observed what the wrapper itself answers, which is what a
        // TrustFloor asks it.
        Tool stranger = new EchoTool("fetch", ToolResult.ok("a page")) {
            @Override
            public Provenance provenance() {
                return Provenance.THIRD_PARTY;
            }
        };

        assertThat(telemetry.instrument(stranger).provenance()).isEqualTo(Provenance.THIRD_PARTY);
    }

    @Test
    void tracingAToolPreservesWhatItSaysAboutTheGateItHolds() {
        // A durable worker refuses a tool that declares it holds a blocking or per-run gate
        // (#283). The declarations default to false, so a wrapper that let the default
        // answer would launder the hazard onto a shared worker for anyone who instrumented
        // their registry first — which is the order the README recommends.
        Tool holdsOne = new EchoTool("run_code", ToolResult.ok("x")) {
            @Override
            public boolean holdsGateWaitingForAHuman() {
                return true;
            }

            @Override
            public boolean holdsGateBoundToOneRun() {
                return true;
            }
        };

        Tool instrumented = telemetry.instrument(holdsOne);

        assertThat(instrumented.holdsGateWaitingForAHuman()).isTrue();
        assertThat(instrumented.holdsGateBoundToOneRun()).isTrue();
    }

    @Test
    void instrumentingARegistryLeavesItsDisclosurePolicyAlone() {
        // A SimpleToolRegistry advertises everything, so it cannot tell a forwarding
        // wrapper from one that answers "what is advertised?" itself. A disclosing
        // registry can: it advertises a subset that grows as tools are revealed.
        DisclosingToolRegistry inner = DisclosingToolRegistry.builder()
                .alwaysAvailable(new EchoTool("always", ToolResult.ok("")))
                .deferred(new EchoTool("weather", ToolResult.ok("")))
                .build();
        ToolRegistry traced = telemetry.instrument(inner);

        List<String> advertisedBefore = traced.advertisedSpecs().stream().map(ToolSpec::name).toList();
        assertThat(advertisedBefore).contains("always").doesNotContain("weather");

        inner.reveal("weather");

        assertThat(traced.advertisedSpecs().stream().map(ToolSpec::name).toList())
                .contains("always", "weather");
        assertThat(traced.tools()).allMatch(TracingTool.class::isInstance);
        assertThat(traced.find("always")).get().isInstanceOf(TracingTool.class);
        assertThat(traced.find("nope")).isEmpty();
    }

    @Test
    void aTracedRegistryHandsOutTheSameWrapperForTheSameTool() {
        // A fresh wrapper per lookup would make a tool stop being == to itself through
        // this registry, which is a trap for any caller keying a map on a Tool.
        ToolRegistry traced = telemetry.instrument(
                new SimpleToolRegistry(List.of(new EchoTool("a", ToolResult.ok("")))));

        assertThat(traced.find("a").orElseThrow()).isSameAs(traced.find("a").orElseThrow());
        assertThat(traced.tools().get(0)).isSameAs(traced.find("a").orElseThrow());
    }

    // --- the approval gate ---------------------------------------------------

    @Test
    void aParkedCallIsRecordedAsParkedRatherThanDenied() {
        // Denied and parked are both "the call did not run", and they are different facts
        // about what happens next: one is finished, one is a question somebody still owes
        // an answer on. An operator whose dashboard cannot tell them apart cannot see a
        // queue building up, which is the failure mode a human-in-the-loop policy has.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "delete"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(
                                List.of(new EchoTool("delete", ToolResult.ok("deleted"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate(dev.agentkit.core.reliability.ToolGates
                        .parkForApproval(invocation -> true,
                                dev.agentkit.core.reliability.ApprovalNeeded
                                        .because("deleting needs a person"))))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("delete it")));

        SpanData gate = spanNamed("agentkit.gate_tool delete");
        assertThat(gate.getAttributes().asMap())
                .containsEntry(GenAi.AGENTKIT_GATE_ALLOWED, false)
                .containsEntry(GenAi.AGENTKIT_GATE_OUTCOME, GenAi.GATE_OUTCOME_PARKED);
        // A policy asking the question it was written to ask is not a fault either.
        assertThat(gate.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        // And the tool genuinely did not run, which is the point of every one of these.
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().equals("execute_tool delete"));
    }

    @Test
    void aDeniedCallIsVisibleEvenThoughItNeverExecutes() {
        // The loop evaluates the gate before Tool.execute, so without a gate span a
        // blocked call leaves the trace showing a model turn asking for a tool and then
        // nothing at all — as if the request had evaporated.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "delete"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(
                                List.of(new EchoTool("delete", ToolResult.ok("deleted"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate((tool, invocation) -> GateResult.deny("needs a human")))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("delete it")));

        SpanData gate = spanNamed("agentkit.gate_tool delete");
        assertThat(gate.getAttributes().asMap())
                .containsEntry(GenAi.TOOL_NAME, "delete")
                .containsEntry(GenAi.TOOL_CALL_ID, "c1")
                .containsEntry(GenAi.AGENTKIT_GATE_ALLOWED, false)
                .containsEntry(GenAi.AGENTKIT_GATE_REPLACED, false)
                .containsEntry(GenAi.AGENTKIT_GATE_OUTCOME, GenAi.GATE_OUTCOME_DENIED);
        // A guardrail doing its job is not a fault: marking this ERROR would alarm on
        // every blocked call.
        assertThat(gate.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        // The tool genuinely did not run, which is the point.
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().equals("execute_tool delete"));
        assertThat(gate.getParentSpanId()).isEqualTo(spanNamed("invoke_agent run").getSpanId());
    }

    @Test
    void aReplacementTheRunnerWillNotHonourIsNotRecordedAsAnAllowedCall() {
        // #121. The span closed on the gate's answer, and a replacement that renames the
        // tool is refused afterwards, at the runner. So the trace read gate.allowed=true,
        // gate.replaced=true, and then no execute_tool span — the shape this class's
        // javadoc opens by calling the problem it exists to solve. An operator reading
        // "allowed with a replacement" followed by silence has been told the opposite of
        // what happened.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "publish"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(List.of(
                                new EchoTool("publish", ToolResult.ok("published")),
                                new EchoTool("draft", ToolResult.ok("drafted"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate((tool, invocation) -> GateResult.allowWith(
                        new dev.agentkit.core.tool.ToolInvocation(
                                invocation.id(), "draft", invocation.arguments()))))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("publish it")));

        SpanData gate = spanNamed("agentkit.gate_tool publish");
        assertThat(gate.getAttributes().asMap())
                .as("the gate's own answer is still the gate's own answer")
                .containsEntry(GenAi.AGENTKIT_GATE_ALLOWED, true)
                .containsEntry(GenAi.AGENTKIT_GATE_REPLACED, true)
                .containsEntry(GenAi.AGENTKIT_GATE_OUTCOME, GenAi.GATE_OUTCOME_REFUSED);
        // ERROR, unlike a denial: a guardrail doing its job is not a fault, and a
        // replacement the runner will not honour is a mistake in the gate. The same gate
        // composed with ToolGates.allOf already produced an ERROR span, because allOf
        // applies effectiveFor between members and the throw lands inside this span — same
        // policy, same mistake, two different traces depending on composition.
        assertThat(gate.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        // Neither tool ran: not the one the gate named, and not the one the model asked
        // for either. That is what "refused" has to mean, and asserting only the first
        // would pass for a runner that quietly ran the original.
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().startsWith("execute_tool"));
    }

    @Test
    void aGateThatDidNotStopTheCallSaysNothingAboutTheOutcome() {
        // An earlier version emitted outcome="allowed" here, and that was the bug rather
        // than the fix: a traced gate that is a *member* of a ToolGates.allOf then asserted
        // "allowed" for a call a later member refused, with no execute_tool span beside it
        // — the #121 shape reintroduced one composition step out, stated affirmatively,
        // where before the attribute was merely absent.
        //
        // A gate cannot know what the rest of the chain will decide. Denied, refused and
        // failed are terminal wherever it sits; "allowed" is not, so it is not said.
        // gate.allowed still carries this gate's own answer.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "publish"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(
                                List.of(new EchoTool("publish", ToolResult.ok("published"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate((tool, invocation) -> GateResult.allowWith(
                        new dev.agentkit.core.tool.ToolInvocation(
                                invocation.id(), invocation.name(), Map.of("text", "[edited]")))))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("publish it")));

        assertThat(spanNamed("agentkit.gate_tool publish").getAttributes().asMap())
                .containsEntry(GenAi.AGENTKIT_GATE_ALLOWED, true)
                .containsEntry(GenAi.AGENTKIT_GATE_REPLACED, true)
                .doesNotContainKey(GenAi.AGENTKIT_GATE_OUTCOME);
        assertThat(spanNamed("agentkit.gate_tool publish").getStatus().getStatusCode())
                .isNotEqualTo(StatusCode.ERROR);
        // And the tool really did run, which is what "allowed" has to mean.
        assertThat(OTEL.getSpans()).anyMatch(span -> span.getName().equals("execute_tool publish"));
    }

    @Test
    void instrumentingAGateDoesNotChangeWhenItThrows() {
        // The class's central claim, and nothing enforced it: a decorator that set the same
        // attributes and *then* rethrew the refusal — changing the policy it was asked to
        // observe — left the suite green. Asserted on the delegate's own behaviour rather
        // than on the span, because the span is the thing that would not notice.
        java.util.List<String> raised = new java.util.ArrayList<>();
        ToolGate renaming = (tool, invocation) -> GateResult.allowWith(
                new dev.agentkit.core.tool.ToolInvocation(
                        invocation.id(), "draft", invocation.arguments()));
        dev.agentkit.core.tool.ToolInvocation call =
                new dev.agentkit.core.tool.ToolInvocation("c1", "publish", Map.of());
        dev.agentkit.core.tool.Tool publish = new EchoTool("publish", ToolResult.ok("ok"));

        for (ToolGate gate : List.of(renaming, telemetry.instrumentGate(renaming))) {
            try {
                gate.evaluate(publish, call);
                raised.add("returned");
            } catch (RuntimeException e) {
                raised.add("threw:" + e.getClass().getSimpleName());
            }
        }

        assertThat(raised).as("instrumenting a gate changed when it throws")
                .containsExactly("returned", "returned");
    }

    @Test
    void theSameMistakeLooksTheSameWhetherOrNotItWasComposed() {
        // The asymmetry #121 also names, and the reason the case above is marked ERROR.
        // ToolGates.allOf applies effectiveFor between members, so a renaming member throws
        // *inside* evaluate and therefore inside this span — while the same gate used alone
        // was refused afterwards, at the runner, and the span closed green. Same policy,
        // same mistake, two different traces depending on composition. Asserted rather than
        // claimed, because the javadoc that says so is the kind of sentence this repository
        // keeps getting wrong.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "publish"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(List.of(
                                new EchoTool("publish", ToolResult.ok("published")),
                                new EchoTool("draft", ToolResult.ok("drafted"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate(ToolGates.allOf(
                        (tool, invocation) -> GateResult.allowWith(
                                new dev.agentkit.core.tool.ToolInvocation(
                                        invocation.id(), "draft", invocation.arguments())),
                        (tool, invocation) -> GateResult.allow())))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("publish it")));

        SpanData gate = spanNamed("agentkit.gate_tool publish");
        assertThat(gate.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        // The attribute, not only the status. The first version of this change set the
        // outcome on the returning path only, so a composed refusal — where allOf raises it
        // from inside evaluate — carried no gate attributes at all, and a dashboard
        // counting refusals missed every one composed this way. It unified the status code
        // and claimed it had unified the traces.
        assertThat(gate.getAttributes().asMap())
                .containsEntry(GenAi.AGENTKIT_GATE_OUTCOME, GenAi.GATE_OUTCOME_REFUSED);
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().startsWith("execute_tool"));
    }

    @Test
    void aTracedMemberOfAChainDoesNotClaimACallThatNeverRan() {
        // instrumentGate is public precisely so a single policy inside a chain can be
        // traced, and this is the shape that made the previous version worse than no
        // attribute at all: the traced member allows, a later member's replacement is
        // refused, and nothing runs.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "publish"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(List.of(
                                new EchoTool("publish", ToolResult.ok("published")),
                                new EchoTool("draft", ToolResult.ok("drafted"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(ToolGates.allOf(
                        telemetry.instrumentGate((tool, invocation) -> GateResult.allow()),
                        (tool, invocation) -> GateResult.allowWith(
                                new dev.agentkit.core.tool.ToolInvocation(
                                        invocation.id(), "draft", invocation.arguments()))))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("publish it")));

        assertThat(spanNamed("agentkit.gate_tool publish").getAttributes().asMap())
                .as("a member claimed an outcome the chain had not reached")
                .doesNotContainKey(GenAi.AGENTKIT_GATE_OUTCOME);
        // And the call really did not proceed, so "no outcome" is not "nothing happened".
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().startsWith("execute_tool"));
    }

    @Test
    void aRefusalCannotPutAModelsOwnTextUnboundedOnASpan() {
        // The refusal quotes the proposed call's id, which nothing validates — a model
        // chooses it — and the gate pattern that reaches this branch is the one
        // GateResult.effectiveFor's javadoc names as the upgrade hazard: stamping every
        // gated call with an audit id. Measured before the cut: 204,000 characters of span
        // status, where the previous version set none at all on this path.
        String hostile = "A".repeat(200_000);
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall(hostile, "publish"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(
                                List.of(new EchoTool("publish", ToolResult.ok("published"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate((tool, invocation) -> GateResult.allowWith(
                        new dev.agentkit.core.tool.ToolInvocation(
                                "audit-" + invocation.id(), invocation.name(),
                                invocation.arguments()))))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("publish it")));

        SpanData gate = spanNamed("agentkit.gate_tool publish");
        assertThat(gate.getAttributes().asMap())
                .containsEntry(GenAi.AGENTKIT_GATE_OUTCOME, GenAi.GATE_OUTCOME_REFUSED);
        assertThat(gate.getStatus().getDescription())
                .as("a model chose how big this span is")
                .hasSizeLessThan(400);
        // The attack was attempted: without the cut this would be six figures.
        assertThat(gate.getStatus().getDescription()).isNotEmpty();
    }

    @Test
    void aGateThatSimplyBlowsUpIsNotReportedAsARefusedReplacement() {
        // The fourth value. A gate that throws for its own reasons reached no decision
        // either, and reporting that as "refused" would tell an operator a replacement was
        // rejected when there was none.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "publish"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(
                                List.of(new EchoTool("publish", ToolResult.ok("published"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate((tool, invocation) -> {
                    throw new IllegalStateException("the policy service is down");
                }))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("publish it")));

        assertThat(spanNamed("agentkit.gate_tool publish").getAttributes().asMap())
                .containsEntry(GenAi.AGENTKIT_GATE_OUTCOME, GenAi.GATE_OUTCOME_FAILED);
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().startsWith("execute_tool"));
    }

    @Test
    void instrumentingAGateDoesNotChangeTheDecisionItObserves() {
        // Regression: the wrapper overrode only evaluate(invocation) and inherited the
        // default two-arg form, which drops the tool. ToolGates.readOnly() has to refuse
        // the invocation-only form, so wrapping it for observability turned it into
        // deny-everything — turning tracing on changed the policy being traced.
        Tool readable = new EchoTool("search", ToolResult.ok("results")) {
            @Override
            public SideEffects sideEffects() {
                return SideEffects.NONE;
            }
        };
        Tool writer = new EchoTool("send", ToolResult.ok("sent")) {
            @Override
            public SideEffects sideEffects() {
                return SideEffects.EXTERNAL;
            }
        };
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(
                                toolCall("c1", "search"), toolCall("c2", "send"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(List.of(readable, writer))),
                        AgentConfig.builder("m").maxSteps(6).build())
                .toolGate(telemetry.instrumentGate(ToolGates.readOnly()))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("search then send")));

        // Both directions, or this pins nothing: an allow-only assertion passes just as
        // well against ALLOW_ALL, which is not the policy being observed.
        assertThat(spanNamed("agentkit.gate_tool search").getAttributes().asMap())
                .containsEntry(GenAi.AGENTKIT_GATE_ALLOWED, true);
        assertThat(spanNamed("agentkit.gate_tool send").getAttributes().asMap())
                .containsEntry(GenAi.AGENTKIT_GATE_ALLOWED, false);
        // "Allowed" has to mean the tool ran, and "denied" has to mean it did not.
        assertThat(OTEL.getSpans()).anyMatch(span -> span.getName().equals("execute_tool search"));
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().equals("execute_tool send"));
    }

    @Test
    void instrumentingAReadOnlyGateStillLetsACodeExecutionToolDeclareItselfHarmless() {
        // The declaration is checked against the gate at build time, so a wrapper that
        // dropped the claim would make instrumenting a gate break a construction that
        // compiled without it — observability changing what builds.
        Tool tool = CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok("x"),
                        new SimpleToolRegistry())
                .toolGate(telemetry.instrumentGate(ToolGates.readOnly()))
                .sideEffects(SideEffects.NONE)
                .build();

        assertThat(tool.sideEffects()).isEqualTo(SideEffects.NONE);
    }

    @Test
    void aGateSpanIsCurrentWhileTheDelegateDecides() {
        // The class javadoc sells this as API — it tells you to add your denial reason on
        // Span.current() from inside your gate — so it needs the same guard the chat path
        // has. The two-arg form is the one the agent loop calls.
        AtomicReference<String> seenParent = new AtomicReference<>();
        ToolGate gate = telemetry.instrumentGate((tool, invocation) -> {
            seenParent.set(Span.current().getSpanContext().getSpanId());
            return GateResult.allow();
        });

        gate.evaluate(new EchoTool("send", ToolResult.ok("sent")),
                new ToolInvocation("c", "send", Map.of()));

        assertThat(seenParent.get()).isEqualTo(spanNamed("agentkit.gate_tool send").getSpanId());
    }

    @Test
    void anApproverThatBlocksHasItsWaitAttributedToTheGate() {
        // The one feature whose latency is measured in human attention. Before this the
        // wait landed nowhere: outside execute_tool, outside chat, inside no span at all.
        ToolGate slow = telemetry.instrumentGate((tool, invocation) -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GateResult.allow();
        });

        slow.evaluate(new EchoTool("send", ToolResult.ok("sent")),
                new ToolInvocation("c", "send", Map.of()));

        SpanData gate = spanNamed("agentkit.gate_tool send");
        assertThat(gate.getEndEpochNanos() - gate.getStartEpochNanos())
                .isGreaterThan(Duration.ofMillis(50).toNanos());
        assertThat(gate.getAttributes().get(GenAi.AGENTKIT_GATE_ALLOWED)).isTrue();
    }

    @Test
    void aGateThatEditsTheArgumentsSaysSo() {
        // The model's proposal is not what ran — a human narrowing a delete to one path.
        telemetry.instrumentGate((tool, invocation) ->
                        GateResult.allowWith(new ToolInvocation(invocation.id(), invocation.name(),
                                Map.of("path", "/tmp/only-this"))))
                .evaluate(new EchoTool("delete", ToolResult.ok("gone")),
                        new ToolInvocation("c", "delete", Map.of("path", "/")));

        assertThat(spanNamed("agentkit.gate_tool delete").getAttributes()
                .get(GenAi.AGENTKIT_GATE_REPLACED)).isTrue();
    }

    @Test
    void aGateSpanIsASiblingOfExecutionRatherThanItsParent() {
        // The real shape: the gate decides, finishes, and only then does the tool run.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(toolCall("c1", "send"), text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(
                                List.of(new EchoTool("send", ToolResult.ok("sent"))))),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(telemetry.instrumentGate((tool, invocation) -> GateResult.allow()))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("send it")));

        SpanData run = spanNamed("invoke_agent run");
        assertThat(spanNamed("agentkit.gate_tool send").getParentSpanId()).isEqualTo(run.getSpanId());
        assertThat(spanNamed("execute_tool send").getParentSpanId()).isEqualTo(run.getSpanId());
    }

    @Test
    void aThrowingGateIsAFailedSpanAndStillThrows() {
        ToolGate gate = telemetry.instrumentGate((tool, invocation) -> {
            throw new IllegalStateException("approver unreachable");
        });

        assertThatThrownBy(() -> gate.evaluate(new EchoTool("send", ToolResult.ok("sent")),
                new ToolInvocation("c", "send", Map.of())))
                .isInstanceOf(IllegalStateException.class);

        SpanData span = spanNamed("agentkit.gate_tool send");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(GenAi.ERROR_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void instrumentingAGateTwiceDoesNotDoubleTheSpans() {
        ToolGate once = telemetry.instrumentGate((tool, invocation) -> GateResult.allow());
        ToolGate twice = telemetry.instrumentGate(once);

        assertThat(twice).isSameAs(once);
        twice.evaluate(new EchoTool("send", ToolResult.ok("sent")),
                new ToolInvocation("c", "send", Map.of()));

        assertThat(OTEL.getSpans()).hasSize(1);
    }

    // --- the run span --------------------------------------------------------

    @Test
    void aWholeRunNestsUnderOneInvokeAgentSpan() {
        ScriptedLlm llm = new ScriptedLlm(toolCall("c1", "search"), text("done", 9, 3));
        Agent agent = Agent.builder(
                        telemetry.instrument(llm),
                        telemetry.instrument(new SimpleToolRegistry(
                                List.of(new EchoTool("search", ToolResult.ok("hit"))))),
                        AgentConfig.builder("m").maxSteps(5).build())
                .build();

        AgentResult result = telemetry.invokeAgent("researcher", () -> agent.run(Goal.of("find it")));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        SpanData run = spanNamed("invoke_agent researcher");
        assertThat(run.getAttributes().asMap())
                .containsEntry(GenAi.OPERATION_NAME, "invoke_agent")
                .containsEntry(GenAi.AGENT_NAME, "researcher")
                .containsEntry(GenAi.AGENTKIT_STOP_REASON, "completed")
                .containsEntry(GenAi.AGENTKIT_STEPS, 2L);

        // Every model and tool span belongs to this run, not to whatever ran before it.
        List<SpanData> children = OTEL.getSpans().stream()
                .filter(s -> !s.getName().startsWith("invoke_agent"))
                .toList();
        assertThat(children).hasSize(3);
        assertThat(children).allSatisfy(child -> {
            assertThat(child.getParentSpanId()).isEqualTo(run.getSpanId());
            assertThat(child.getTraceId()).isEqualTo(run.getTraceId());
        });
    }

    @Test
    void aFailedRunIsAFailedSpanEvenThoughItDoesNotThrow() {
        // AgentResult.failed() is returned, not thrown, so without reading the result the
        // span for a run that died would look exactly like one that succeeded.
        Agent agent = Agent.builder(
                        telemetry.instrument(request -> {
                            throw new LlmException("provider down");
                        }),
                        new SimpleToolRegistry(List.of()),
                        AgentConfig.builder("m").build())
                .build();

        AgentResult result = telemetry.invokeAgent("doomed", () -> agent.run(Goal.of("try")));

        assertThat(result.stopReason()).isEqualTo(StopReason.ERROR);
        SpanData run = spanNamed("invoke_agent doomed");
        assertThat(run.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(run.getAttributes().get(GenAi.ERROR_TYPE)).isEqualTo(LlmException.class.getName());
        assertThat(run.getAttributes().get(GenAi.AGENTKIT_STOP_REASON)).isEqualTo("error");
    }

    @Test
    void anExceptionEscapingTheRunEndsTheSpanAndLeavesNoDanglingContext() {
        assertThatThrownBy(() -> telemetry.invokeAgent("leaky", () -> {
            throw new IllegalStateException("escaped");
        })).isInstanceOf(IllegalStateException.class);

        SpanData run = spanNamed("invoke_agent leaky");
        assertThat(run.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(run.hasEnded()).isTrue();
        // The critical part: this thread's context must be clean, or the next unrelated
        // work on this (possibly pooled) thread would attach to a finished span.
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void concurrentSubagentsStayInTheRunsTraceOnAnInstrumentedExecutor() {
        // OTel context is thread-local and does not survive submit(), so a supervisor's
        // parallel fan-out would otherwise produce one detached root trace per subagent.
        try (ExecutorService pool = telemetry.instrument(Executors.newVirtualThreadPerTaskExecutor())) {
            telemetry.invokeAgent("supervisor", () -> {
                List<Future<?>> futures = List.of(
                        pool.submit(() -> telemetry.instrument(new ScriptedLlm(text("a", 1, 1)))
                                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build())),
                        pool.submit(() -> telemetry.instrument(new ScriptedLlm(text("b", 1, 1)))
                                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build())));
                futures.forEach(f -> {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                return null;
            });
        }

        SpanData run = spanNamed("invoke_agent supervisor");
        List<SpanData> chats = OTEL.getSpans().stream()
                .filter(s -> s.getName().equals("chat m")).toList();
        assertThat(chats).hasSize(2).allSatisfy(chat -> {
            assertThat(chat.getTraceId()).isEqualTo(run.getTraceId());
            assertThat(chat.getParentSpanId()).isEqualTo(run.getSpanId());
        });
    }

    @Test
    void aSupervisorFanOutStaysInOneTraceOnItsDefaultExecutor() {
        // The case that matters: a Supervisor built without an explicit executor makes its
        // own per-call one, which nothing outside can wrap — so wrapping the executor
        // cannot fix the configuration most people run. taskContext travels with the task.
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(tracedSubagent("alpha"), tracedSubagent("beta")))
                .taskContext(telemetry.taskContext())
                .synthesizer(Synthesizers.concatenating())
                .build();

        telemetry.invokeAgent("supervisor", () -> supervisor.fanOut(Goal.of("split it"), List.of(
                new DelegatedTask("alpha", Goal.of("half one")),
                new DelegatedTask("beta", Goal.of("half two")))));

        SpanData run = spanNamed("invoke_agent supervisor");
        List<SpanData> subagents = OTEL.getSpans().stream()
                .filter(s -> s.getName().startsWith("invoke_agent ") && s != run)
                .toList();
        assertThat(subagents).hasSize(2).allSatisfy(child -> {
            assertThat(child.getTraceId()).isEqualTo(run.getTraceId());
            assertThat(child.getParentSpanId()).isEqualTo(run.getSpanId());
        });
        // And the model calls under them, so the whole fan-out is one tree.
        assertThat(OTEL.getSpans().stream().filter(s -> s.getName().equals("chat m")).toList())
                .hasSize(2)
                .allSatisfy(chat -> assertThat(chat.getTraceId()).isEqualTo(run.getTraceId()));
    }

    @Test
    void withoutATaskContextTheSameFanOutOrphansEverySubagent() {
        // Pinned so the fix cannot quietly regress into the thing it replaced.
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(tracedSubagent("alpha"), tracedSubagent("beta")))
                .synthesizer(Synthesizers.concatenating())
                .build();

        telemetry.invokeAgent("supervisor", () -> supervisor.fanOut(Goal.of("split it"), List.of(
                new DelegatedTask("alpha", Goal.of("half one")),
                new DelegatedTask("beta", Goal.of("half two")))));

        SpanData run = spanNamed("invoke_agent supervisor");
        assertThat(OTEL.getSpans().stream().filter(s -> s.getName().equals("chat m")).toList())
                .hasSize(2)
                .allSatisfy(chat -> assertThat(chat.getTraceId()).isNotEqualTo(run.getTraceId()));
    }

    @Test
    void aGraphsConcurrentBranchesStayInOneTraceOnItsDefaultExecutor() {
        AgentGraph graph = AgentGraph.builder()
                .node("left", input -> telemetry.invokeAgent("left", () -> AgentResult.completed(
                        telemetry.instrument(new ScriptedLlm(text("L", 1, 1)))
                                .generate(LlmRequest.builder("m")
                                        .addMessage(Message.user("go")).build()).message().text(), 1)))
                .node("right", input -> telemetry.invokeAgent("right", () -> AgentResult.completed(
                        telemetry.instrument(new ScriptedLlm(text("R", 1, 1)))
                                .generate(LlmRequest.builder("m")
                                        .addMessage(Message.user("go")).build()).message().text(), 1)))
                .taskContext(telemetry.taskContext())
                .build();

        telemetry.invokeAgent("graph", () -> graph.run(Goal.of("go")));

        SpanData run = spanNamed("invoke_agent graph");
        assertThat(OTEL.getSpans()).hasSize(5).allSatisfy(span ->
                assertThat(span.getTraceId()).isEqualTo(run.getTraceId()));
    }

    @Test
    void anUninstrumentedExecutorOrphansThoseSpans() {
        // Pinned so the limitation is a documented fact rather than a surprise: this is
        // what Supervisor's own per-call executor does, and nothing outside can wrap it.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            telemetry.invokeAgent("supervisor", () -> {
                try {
                    pool.submit(() -> telemetry.instrument(new ScriptedLlm(text("a", 1, 1)))
                            .generate(LlmRequest.builder("m")
                                    .addMessage(Message.user("go")).build())).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
        }

        assertThat(spanNamed("chat m").getTraceId())
                .isNotEqualTo(spanNamed("invoke_agent supervisor").getTraceId());
    }

    @Test
    void aSubagentCanBeGivenItsOwnRunSpan() {
        // Agent is final and Subagent.of calls run() itself, so without a handler seam
        // invokeAgent could not reach any agent the framework runs for you.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(text("found", 4, 2))),
                        new SimpleToolRegistry(List.of()),
                        AgentConfig.builder("m").build())
                .build();
        Subagent subagent = Subagent.handling("researcher", "searches",
                subgoal -> telemetry.invokeAgent("researcher", () -> agent.run(subgoal)));

        AgentResult result = telemetry.invokeAgent("supervisor",
                () -> subagent.handle(Goal.of("find it")));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        SpanData supervisor = spanNamed("invoke_agent supervisor");
        SpanData researcher = spanNamed("invoke_agent researcher");
        assertThat(researcher.getParentSpanId()).isEqualTo(supervisor.getSpanId());
        assertThat(researcher.getAttributes().get(GenAi.AGENTKIT_STOP_REASON)).isEqualTo("completed");
        assertThat(spanNamed("chat m").getParentSpanId()).isEqualTo(researcher.getSpanId());
    }

    @Test
    void instrumentingTwiceDoesNotDoubleTheSpansOrTheTokens() {
        // The docs push hard on instrumenting every client, which invites a wrap at the
        // builder and another at a call site. Doubling the token samples would be a
        // silently wrong total — worse than no telemetry.
        LlmClient once = telemetry.instrument("agent", new ScriptedLlm(text("hi", 6, 1)));
        LlmClient twice = telemetry.instrument("agent", once);

        assertThat(twice).isSameAs(once);
        twice.generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());

        assertThat(OTEL.getSpans()).hasSize(1);
        var tokens = OTEL.getMetrics().stream()
                .filter(m -> m.getName().equals(GenAi.METRIC_TOKEN_USAGE))
                .findFirst().orElseThrow();
        assertThat(tokens.getHistogramData().getPoints()).hasSize(2);
    }

    @Test
    void theDefaultRoleIsAlarmingRatherThanPlausible() {
        // Matching UsageMeter: a forgotten role should be visible on a dashboard, not
        // quietly filed under "agent" beside the calls that really were the agent's.
        telemetry.instrument(new ScriptedLlm(text("hi", 1, 1)))
                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());

        assertThat(spanNamed("chat m").getAttributes().get(GenAi.AGENTKIT_ROLE))
                .isEqualTo("unattributed");
    }

    @Test
    void theRunSpanRollsUpUsageOutsideTheGenAiNamespace() {
        // The child chat spans already carry gen_ai.usage.*; repeating it on the parent
        // would make any backend summing that attribute across spans double-count.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(text("done", 10, 3))),
                        new SimpleToolRegistry(List.of()),
                        AgentConfig.builder("m").build())
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("go")));

        SpanData run = spanNamed("invoke_agent run");
        assertThat(run.getAttributes().get(GenAi.AGENTKIT_LOOP_INPUT_TOKENS)).isEqualTo(10L);
        assertThat(run.getAttributes().get(GenAi.AGENTKIT_LOOP_OUTPUT_TOKENS)).isEqualTo(3L);
        assertThat(run.getAttributes().get(GenAi.USAGE_INPUT_TOKENS)).isNull();
        assertThat(run.getAttributes().get(GenAi.USAGE_OUTPUT_TOKENS)).isNull();
    }

    @Test
    void aNoopOpenTelemetryChangesNothingButTheWiring() {
        AgentTelemetry off = AgentTelemetry.using(OpenTelemetry.noop());
        LlmClient llm = off.instrument(new ScriptedLlm(text("hi", 1, 1)));

        AgentResult result = off.invokeAgent("quiet", () ->
                AgentResult.completed(llm.generate(LlmRequest.builder("m")
                        .addMessage(Message.user("go")).build()).message().text(), 1));

        assertThat(result.output()).isEqualTo("hi");
        assertThat(OTEL.getSpans()).isEmpty();
    }

    @Test
    void aBlankRoleOrProviderIsRejected() {
        assertThatThrownBy(() -> telemetry.instrument(" ", new ScriptedLlm()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("role");
        assertThatThrownBy(() -> AgentTelemetry.using(OTEL.getOpenTelemetry(), " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provider");
    }

    @Test
    void theDefaultProviderIsAPlaceholderRatherThanAGuess() {
        // This module decorates the provider-agnostic LlmClient and genuinely cannot tell
        // what is underneath; claiming "anthropic" by default would be a lie in a trace.
        AgentTelemetry unnamed = AgentTelemetry.using(OTEL.getOpenTelemetry());
        unnamed.instrument(new ScriptedLlm(text("hi", 1, 1)))
                .generate(LlmRequest.builder("m").addMessage(Message.user("go")).build());

        assertThat(spanNamed("chat m").getAttributes().get(GenAi.PROVIDER_NAME)).isEqualTo("agentkit");
    }

    @Test
    void theRegistryWrapperReportsWhatItWraps() {
        ToolRegistry inner = new SimpleToolRegistry(List.of());
        assertThat(telemetry.instrument(inner)).hasToString("TracingToolRegistry[" + inner + "]");
        Tool tool = new EchoTool("t", ToolResult.ok(""));
        assertThat(telemetry.instrument(tool)).hasToString("TracingTool[" + tool + "]");
    }

    @Test
    void instrumentingAFloorTracesTheTightenedPolicyToo() {
        // The first version used a floor whose ordinary gate denied the only call, so
        // nothing third-party ever came back, the tightened gate was never evaluated, and a
        // mutant tracing only the ordinary one survived. The claim it defends is that an
        // operator would otherwise see a run go quiet at exactly the moment the tightened
        // policy started refusing things — so the tightened policy has to be what refuses.
        Agent agent = Agent.builder(
                        telemetry.instrument(new ScriptedLlm(
                                toolCall("c1", "fetch"), toolCall("c2", "delete"),
                                text("ok", 1, 1))),
                        telemetry.instrument(new SimpleToolRegistry(List.of(
                                new EchoTool("fetch", ToolResult.from(
                                        dev.agentkit.core.tool.Provenance.THIRD_PARTY, "a page")),
                                new EchoTool("delete", ToolResult.ok("deleted"))))),
                        AgentConfig.builder("m").maxSteps(5).build())
                .trustFloor(telemetry.instrumentFloor(
                        dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                                dev.agentkit.core.reliability.ToolGate.ALLOW_ALL,
                                dev.agentkit.core.reliability.ToolGates.readOnly())))
                .build();

        telemetry.invokeAgent("run", () -> agent.run(Goal.of("fetch then delete")));

        assertThat(spanNamed("agentkit.gate_tool delete").getAttributes().asMap())
                .as("the call the tightened policy refused has a span saying so")
                .containsEntry(GenAi.AGENTKIT_GATE_OUTCOME, GenAi.GATE_OUTCOME_DENIED);
        assertThat(OTEL.getSpans()).noneMatch(span -> span.getName().equals("execute_tool delete"));
        assertThat(OTEL.getSpans())
                .as("and the fetch before it really did run, so this is about the floor")
                .anyMatch(span -> span.getName().equals("execute_tool fetch"));
    }

    /**
     * Instrumenting {@code delegate} keeps the span <em>and</em> the parent link (#317).
     *
     * <p>{@code TracingTool} is a {@code ForwardingTool}, and {@code boundTo} is the one
     * member whose obvious forward — {@code delegate().boundTo(run)} — is wrong: it hands
     * the agent loop the tool <em>inside</em> the wrapper, so an instrumented
     * {@code delegate} would quietly stop producing spans. Rebuilding keeps both, and both
     * are asserted here because each failure mode leaves the other one intact.
     *
     * <p>Wired against the real {@code SubagentTools.delegateTool} and a real child
     * {@link Agent} rather than a stand-in: what is being measured is a composition of three
     * things this module does not own, and a fake for any of them would measure the fake.
     */
    @Test
    void instrumentingDelegateKeepsBothTheSpanAndTheParentLink() {
        java.util.List<dev.agentkit.core.agent.AgentRun> childRuns =
                new java.util.ArrayList<>();
        dev.agentkit.core.agent.AgentObserver watching =
                new dev.agentkit.core.agent.AgentObserver() {
                    @Override
                    public void onStart(dev.agentkit.core.agent.AgentRun run, Goal goal) {
                        childRuns.add(run);
                    }
                };
        SubagentRoster roster = SubagentRoster.of(Subagent.of("researcher", "searches",
                () -> Agent.builder(new ScriptedLlm(text("found", 1, 1)),
                                new SimpleToolRegistry(List.of()),
                                AgentConfig.builder("m").build())
                        .observer(watching)
                        .build()));

        Tool instrumented = telemetry.instrument(
                dev.agentkit.core.supervisor.SubagentTools.delegateTool(roster));
        dev.agentkit.core.agent.AgentRun supervisor =
                dev.agentkit.core.agent.AgentRun.of("supervisor");

        ToolResult result = instrumented.boundTo(supervisor)
                .execute(new ToolInvocation("d1",
                        dev.agentkit.core.supervisor.SubagentTools.DELEGATE,
                        Map.of("subagent", "researcher", "goal", "find it")));

        assertThat(result.isError()).isFalse();
        // The decoration survived the binding: a forward that handed the inner tool over
        // would leave this span missing entirely.
        assertThat(spanNamed("execute_tool delegate").getKind()).isEqualTo(SpanKind.INTERNAL);
        // And the binding survived the decoration: a decorator that answered `this` would
        // leave the child parentless.
        assertThat(childRuns).singleElement()
                .satisfies(run -> assertThat(run.parent()).contains(supervisor));
    }

    @Test
    void instrumentingAFloorThatIsNotAFloorDoesNotThrow() {
        // Two public methods added in the same change, composed the obvious way, blew up:
        // instrumentGate wrapped one gate twice into two distinct objects, which stopped it
        // being one gate wired twice — and TrustFloor.none is exactly that shape.
        dev.agentkit.core.reliability.TrustFloor traced = telemetry.instrumentFloor(
                dev.agentkit.core.reliability.TrustFloor.none(
                        dev.agentkit.core.reliability.ToolGate.ALLOW_ALL));

        assertThat(traced.exists()).isFalse();
        assertThat(traced.ordinarily())
                .as("and it is still one gate wired twice, not two that happen to agree")
                .isSameAs(traced.onceLowered());
    }
}
