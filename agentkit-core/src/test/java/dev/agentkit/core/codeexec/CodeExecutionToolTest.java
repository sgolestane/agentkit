package dev.agentkit.core.codeexec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.Tools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodeExecutionToolTest {

    private static CodeExecutionTool.Builder declaredHarmless(dev.agentkit.core.reliability.ToolGate gate) {
        return CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok("x"),
                        new SimpleToolRegistry())
                .toolGate(gate)
                .sideEffects(SideEffects.NONE);
    }

    /**
     * A bridged tool's {@code boundToOneRun()} reaches {@code run_code} (#328).
     *
     * <p>The two gate declarations were ORed across the bridged tools; this one was not, and
     * it is a different question despite the name it shares with
     * {@code TrustFloor.boundToOneRun()} — that one asks about the bridge's gate, this one
     * asks whether a bridged tool holds one run's <em>state</em>. So a registry holding
     * {@code ScopeTools}' run-bound tools produced a {@code run_code} that answered
     * {@code false} to every question a durable worker asks, and every run on the task queue
     * could reach one run's {@code AgentScope} by writing a script. A bridge that launders a
     * declaration is exactly the hazard #283 exists for.
     */
    @Test
    void aRunBoundBridgedToolIsCarriedByRunCode() {
        Tool runBound = new Tool() {
            @Override
            public String name() {
                return "collect_task";
            }

            @Override
            public String description() {
                return "collects a task of one run";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public boolean boundToOneRun() {
                return true;
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.ok("collected");
            }
        };

        SimpleToolRegistry holdingIt = new SimpleToolRegistry();
        holdingIt.register(runBound);
        assertThat(CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok("x"),
                        holdingIt)
                .allowAllTools()
                .sideEffects(SideEffects.EXTERNAL)
                .build()
                .boundToOneRun())
                .as("run_code can call anything in the registry, so it is as run-bound as"
                        + " the most run-bound tool it can reach")
                .isTrue();

        // The negative, so a constant `true` would not pass.
        SimpleToolRegistry plain = new SimpleToolRegistry();
        plain.register(FunctionTool.builder("search", "reads")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> ToolResult.ok("results"))
                .build());
        assertThat(CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok("x"), plain)
                .allowAllTools()
                .sideEffects(SideEffects.EXTERNAL)
                .build()
                .boundToOneRun())
                .isFalse();
    }

    @Test
    void declaringRunCodeHarmlessOverAPermissiveGateIsRefused() {
        // The declaration is a claim about what a script can do, and a script can do
        // whatever the bridge gate permits. Accepting this silently produced a run_code
        // that a rehearsal executes and a script that writes freely — fail-open, and
        // invisible until something was sent for real.
        assertThatThrownBy(() -> declaredHarmless(dev.agentkit.core.reliability.ToolGate.ALLOW_ALL).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SideEffects.NONE")
                .hasMessageContaining("ToolGates.readOnly()");

        // A gate that denies by name is not making the promise either: it refuses the
        // writers it was told about, not writers as such.
        assertThatThrownBy(() -> declaredHarmless(ToolGates.denyTools(Set.of("send"))).build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void declaringRunCodeHarmlessOverAReadOnlyGateIsAllowed() {
        assertThat(declaredHarmless(ToolGates.readOnly()).build().sideEffects())
                .isEqualTo(SideEffects.NONE);
        // Composed with anything, since one member's denial short-circuits the whole.
        assertThat(declaredHarmless(ToolGates.allOf(
                        ToolGates.denyTools(Set.of("send")), ToolGates.readOnly()))
                .build().sideEffects())
                .isEqualTo(SideEffects.NONE);
    }

    @Test
    void aGateThisCannotRecogniseCanStillBeVouchedForExplicitly() {
        // The builder enforces what it can verify. A gate that refuses writers by means
        // it cannot see is not blocked from the rehearsal — it goes through the vouching
        // API, which is named for what it is and verifies nothing.
        dev.agentkit.core.reliability.ToolGate refusesWritersButCannotSaySo =
                (tool, invocation) -> dev.agentkit.core.reliability.GateResult.deny("no writers here");

        assertThatThrownBy(() -> declaredHarmless(refusesWritersButCannotSaySo).build())
                .isInstanceOf(IllegalStateException.class);

        Tool undeclared = CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok("x"),
                        new SimpleToolRegistry())
                .toolGate(refusesWritersButCannotSaySo)
                .build();
        assertThat(undeclared.sideEffects()).isEqualTo(SideEffects.UNKNOWN);
        assertThat(Tools.withSideEffects(undeclared, SideEffects.NONE).sideEffects())
                .isEqualTo(SideEffects.NONE);
    }

    @Test
    void aGateSaysNoByDefault() {
        // The direction of the default is the safety property: the "allowed" cases above
        // all still pass if every gate answers yes, so something has to pin that they do
        // not. A lambda gate cannot answer anything else, which is the shape of the API.
        assertThat(dev.agentkit.core.reliability.ToolGate.ALLOW_ALL.guaranteesReadOnly()).isFalse();
        assertThat(ToolGates.denyTools(Set.of("send")).guaranteesReadOnly()).isFalse();
        assertThat(ToolGates.allOf().guaranteesReadOnly()).isFalse();
    }


    private static ToolRegistry weatherRegistry(AtomicInteger calls) {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("get_weather", "weather for a city")
                        .handler(i -> {
                            calls.incrementAndGet();
                            return ToolResult.ok("sunny in " + i.stringArgument("city"));
                        }).build());
    }

    private static ToolResult runCode(Tool tool, String code) {
        return tool.execute(new ToolInvocation("t1", tool.name(), Map.of("code", code)));
    }

    @Test
    void runsCodeThatOrchestratesToolsAndReturnsOnlyTheFinalOutput() {
        AtomicInteger calls = new AtomicInteger();
        // A stand-in sandbox: "the script" calls get_weather twice and returns a summary.
        CodeSandbox sandbox = (code, tools) -> {
            String a = tools.invoke("get_weather", Map.of("city", "Seattle")).content();
            String b = tools.invoke("get_weather", Map.of("city", "Portland")).content();
            return SandboxExecution.ok("Both checked: " + a + "; " + b);
        };
        Tool tool = CodeExecutionTool.builder(sandbox, weatherRegistry(calls)).allowAllTools().build();

        ToolResult result = runCode(tool, "for city in cities: get_weather(city)");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("Both checked: sunny in Seattle; sunny in Portland");
        assertThat(calls).hasValue(2); // the tools really ran, inside the sandbox
    }

    @Test
    void intermediateToolOutputStaysInTheSandboxNotInTheReturnedContent() {
        String huge = "X".repeat(10_000);
        ToolRegistry registry = new SimpleToolRegistry().register(
                FunctionTool.builder("fetch", "fetches a big blob")
                        .handler(i -> ToolResult.ok(huge)).build());
        // The script consumes the huge blob but returns only a short summary.
        CodeSandbox sandbox = (code, tools) -> {
            String blob = tools.invoke("fetch", Map.of()).content();
            return SandboxExecution.ok("length=" + blob.length());
        };
        Tool tool = CodeExecutionTool.builder(sandbox, registry).allowAllTools().build();

        ToolResult result = runCode(tool, "print(len(fetch()))");

        assertThat(result.content()).isEqualTo("length=10000");
        assertThat(result.content()).doesNotContain(huge); // the 10k blob never reaches the model
    }

    @Test
    void missingOrBlankCodeIsAnError() {
        Tool tool = CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok("x"),
                new SimpleToolRegistry()).allowAllTools().build();
        assertThat(tool.execute(new ToolInvocation("t", "run_code", Map.of())).isError()).isTrue();
        assertThat(runCode(tool, "   ").isError()).isTrue();
    }

    @Test
    void aSandboxErrorBecomesAnErrorResult() {
        CodeSandbox sandbox = (code, tools) -> SandboxExecution.error("SyntaxError: bad code");
        Tool tool = CodeExecutionTool.builder(sandbox, new SimpleToolRegistry()).allowAllTools().build();
        ToolResult result = runCode(tool, "def (");
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("SyntaxError");
    }

    @Test
    void aThrowingSandboxDoesNotAbortTheRun() {
        CodeSandbox sandbox = (code, tools) -> {
            throw new RuntimeException("sandbox provider unreachable");
        };
        Tool tool = CodeExecutionTool.builder(sandbox, new SimpleToolRegistry()).allowAllTools().build();
        ToolResult result = runCode(tool, "print(1)");
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("sandbox provider unreachable");
    }

    @Test
    void buildingWithoutAGateDecisionFailsLoudlyRatherThanAllowingEverything() {
        // The whole point of the gate is to constrain code-called tools; a forgotten
        // gate must not silently fall back to allow-all.
        var builder = CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok(""),
                new SimpleToolRegistry());
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool gate is required");
    }

    @Test
    void aPerRunToolCallCapBoundsInScriptFanOut() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new SimpleToolRegistry().register(
                FunctionTool.builder("ping", "pings").handler(i -> {
                    calls.incrementAndGet();
                    return ToolResult.ok("pong");
                }).build());
        // The script tries to call the tool five times; the cap of 3 stops it early.
        CodeSandbox sandbox = (code, tools) -> {
            int ok = 0;
            for (int i = 0; i < 5; i++) {
                if (!tools.invoke("ping", Map.of()).isError()) {
                    ok++;
                }
            }
            return SandboxExecution.ok("ran " + ok);
        };
        Tool tool = CodeExecutionTool.builder(sandbox, registry)
                .allowAllTools().maxToolCalls(3).build();

        ToolResult result = runCode(tool, "for _ in range(5): ping()");

        assertThat(result.content()).isEqualTo("ran 3");
        assertThat(calls).hasValue(3); // the tool ran only up to the cap
    }

    @Test
    void theToolCallCapResetsBetweenRuns() {
        // A single long-lived tool runs many turns; the cap bounds each run, it does
        // not accumulate one budget across the tool's whole lifetime.
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new SimpleToolRegistry().register(
                FunctionTool.builder("ping", "pings").handler(i -> {
                    calls.incrementAndGet();
                    return ToolResult.ok("pong");
                }).build());
        CodeSandbox sandbox = (code, tools) -> {
            int ok = 0;
            for (int i = 0; i < 2; i++) {
                if (!tools.invoke("ping", Map.of()).isError()) {
                    ok++;
                }
            }
            return SandboxExecution.ok("ran " + ok);
        };
        Tool tool = CodeExecutionTool.builder(sandbox, registry).allowAllTools().maxToolCalls(2).build();

        assertThat(runCode(tool, "run 1").content()).isEqualTo("ran 2");
        assertThat(runCode(tool, "run 2").content()).isEqualTo("ran 2"); // cap reset, not exhausted
        assertThat(calls).hasValue(4); // all four calls across the two runs executed
    }

    @Test
    void theDefaultCapBoundsToolCallsAtTheBuilderDefault() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new SimpleToolRegistry().register(
                FunctionTool.builder("ping", "pings").handler(i -> {
                    calls.incrementAndGet();
                    return ToolResult.ok("pong");
                }).build());
        int attempts = CodeExecutionTool.Builder.DEFAULT_MAX_TOOL_CALLS + 5;
        CodeSandbox sandbox = (code, tools) -> {
            int ok = 0;
            for (int i = 0; i < attempts; i++) {
                if (!tools.invoke("ping", Map.of()).isError()) {
                    ok++;
                }
            }
            return SandboxExecution.ok("ran " + ok);
        };
        // No maxToolCalls(...) — exercises the builder default wired through to the bridge.
        Tool tool = CodeExecutionTool.builder(sandbox, registry).allowAllTools().build();

        assertThat(runCode(tool, "fan out").content())
                .isEqualTo("ran " + CodeExecutionTool.Builder.DEFAULT_MAX_TOOL_CALLS);
        assertThat(calls).hasValue(CodeExecutionTool.Builder.DEFAULT_MAX_TOOL_CALLS);
    }

    @Test
    void aNonPositiveToolCallCapIsRejected() {
        assertThatThrownBy(() -> CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok(""),
                new SimpleToolRegistry()).maxToolCalls(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aToolGateGovernsToolsCalledFromInsideTheScript() {
        AtomicBoolean deleteRan = new AtomicBoolean(false);
        ToolRegistry registry = new SimpleToolRegistry().register(
                FunctionTool.builder("delete", "deletes things").handler(i -> {
                    deleteRan.set(true);
                    return ToolResult.ok("deleted");
                }).build());
        // The script tries to delete; the gate on the tool must block it inside the sandbox.
        CodeSandbox sandbox = (code, tools) ->
                SandboxExecution.ok(tools.invoke("delete", Map.of()).content());
        Tool tool = CodeExecutionTool.builder(sandbox, registry)
                .toolGate(ToolGates.denyTools(Set.of("delete")))
                .build();

        ToolResult result = runCode(tool, "delete()");

        assertThat(deleteRan).isFalse(); // the gate prevented execution inside code
        assertThat(result.content()).doesNotContain("deleted");
    }

    @Test
    void theDescriptionAdvertisesTheCallableToolsAndLanguage() {
        Tool tool = CodeExecutionTool.builder((code, tools) -> SandboxExecution.ok(""),
                        weatherRegistry(new AtomicInteger()))
                .allowAllTools().language("JavaScript").name("exec").build();

        assertThat(tool.name()).isEqualTo("exec");
        assertThat(tool.description()).contains("JavaScript").contains("get_weather")
                .contains("weather for a city");
        assertThat(tool.inputSchema()).containsEntry("required", java.util.List.of("code"));
    }

    @Test
    void aScriptCannotReadTheWebAndThenWriteInOneCall() {
        // The gap the first version of #122 shipped and did not admit. A floor wired on the
        // agent loop bought nothing here: read and write both happen inside one outer
        // Tool.execute, so the loop has nowhere to intervene between them and its floor
        // lowers only when the whole call returns — which is afterwards. Measured then:
        // both ran.
        java.util.List<String> entered = new java.util.ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry()
                .register(dev.agentkit.core.tool.FunctionTool.builder("fetch", "Fetches")
                        .sideEffects(dev.agentkit.core.tool.SideEffects.NONE)
                        .provenance(dev.agentkit.core.tool.Provenance.THIRD_PARTY)
                        .handler(i -> {
                            entered.add("fetch");
                            return ToolResult.ok("a page");
                        }).build())
                .register(dev.agentkit.core.tool.FunctionTool.builder("publish", "Publishes")
                        .sideEffects(dev.agentkit.core.tool.SideEffects.EXTERNAL)
                        .handler(i -> {
                            entered.add("publish");
                            return ToolResult.ok("published");
                        }).build());

        Tool runCode = CodeExecutionTool.builder((code, bridge) -> {
                    ToolResult read = bridge.invoke("fetch", java.util.Map.of());
                    bridge.invoke("publish", java.util.Map.of("text", read.content()));
                    return SandboxExecution.ok("done");
                }, registry)
                .toolFloor(dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                        dev.agentkit.core.reliability.ToolGate.ALLOW_ALL,
                        dev.agentkit.core.reliability.ToolGates.readOnly()))
                .build();

        runCode.execute(new dev.agentkit.core.tool.ToolInvocation("c1", "run_code",
                java.util.Map.of("code", "…")));

        assertThat(entered)
                .as("the floor lowers between the two calls of one script, because the whole"
                        + " attack fits inside one script")
                .containsExactly("fetch");
    }

    @Test
    void thatSameScriptWritesFreelyWhenItReadsOnlyOurOwnRecords() {
        // The control. Without it the test above passes for a bridge that refuses the
        // second call of every script, which is a different and much less useful thing.
        java.util.List<String> entered = new java.util.ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry()
                .register(dev.agentkit.core.tool.FunctionTool.builder("fetch", "Reads ours")
                        .sideEffects(dev.agentkit.core.tool.SideEffects.NONE)
                        .provenance(dev.agentkit.core.tool.Provenance.FIRST_PARTY)
                        .handler(i -> {
                            entered.add("fetch");
                            return ToolResult.ok("row 7");
                        }).build())
                .register(dev.agentkit.core.tool.FunctionTool.builder("publish", "Publishes")
                        .sideEffects(dev.agentkit.core.tool.SideEffects.EXTERNAL)
                        .handler(i -> {
                            entered.add("publish");
                            return ToolResult.ok("published");
                        }).build());

        Tool runCode = CodeExecutionTool.builder((code, bridge) -> {
                    ToolResult read = bridge.invoke("fetch", java.util.Map.of());
                    bridge.invoke("publish", java.util.Map.of("text", read.content()));
                    return SandboxExecution.ok("done");
                }, registry)
                .toolFloor(dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                        dev.agentkit.core.reliability.ToolGate.ALLOW_ALL,
                        dev.agentkit.core.reliability.ToolGates.readOnly()))
                .build();

        runCode.execute(new dev.agentkit.core.tool.ToolInvocation("c1", "run_code",
                java.util.Map.of("code", "…")));

        assertThat(entered).containsExactly("fetch", "publish");
    }

    @Test
    void aFloorWhoseOrdinaryPolicyLetsWritersThroughCannotDeclareNoSideEffects() {
        // guaranteesReadOnly is AND across both policies, where waitsForAHuman is OR. A run
        // reaches both, and the writes this would permit happen *before* the floor lowers.
        SimpleToolRegistry registry = new SimpleToolRegistry();

        assertThatThrownBy(() -> CodeExecutionTool.builder(
                        (code, bridge) -> SandboxExecution.ok("x"), registry)
                .toolFloor(dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                        dev.agentkit.core.reliability.ToolGate.ALLOW_ALL,
                        dev.agentkit.core.reliability.ToolGates.readOnly()))
                .sideEffects(dev.agentkit.core.tool.SideEffects.NONE)
                .build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void settingBothAGateAndAFloorIsRefusedRatherThanResolved() {
        // Both directions used to win silently, and the damage was measured, not imagined:
        // .toolFloor(afterThirdParty(ALLOW_ALL, readOnly())).toolGate(ALLOW_ALL) built fine
        // and a script fetched a page and then published; the reverse order refused the
        // publish. Same wiring, opposite security, decided by line order — and toolFloor is
        // the only wiring point the code-execution floor has.
        assertThatThrownBy(() -> CodeExecutionTool.builder(
                        (code, bridge) -> SandboxExecution.ok("x"), new SimpleToolRegistry())
                .toolFloor(dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                        dev.agentkit.core.reliability.ToolGate.ALLOW_ALL,
                        dev.agentkit.core.reliability.ToolGates.readOnly()))
                .toolGate(dev.agentkit.core.reliability.ToolGate.ALLOW_ALL)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alternatives");

        assertThatThrownBy(() -> CodeExecutionTool.builder(
                        (code, bridge) -> SandboxExecution.ok("x"), new SimpleToolRegistry())
                .toolGate(dev.agentkit.core.reliability.ToolGate.ALLOW_ALL)
                .toolFloor(dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                        dev.agentkit.core.reliability.ToolGate.ALLOW_ALL,
                        dev.agentkit.core.reliability.ToolGates.readOnly()))
                .build())
                .as("the other order too, since order deciding security is the defect")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theReadOnlyRefusalNamesThePolicyThatAnsweredNo() {
        // With a floor of (readOnly, parkForApproval) — strictly tightening, and a shape
        // TrustFloor's own javadoc recommends — the message used to print the ordinary gate
        // while reporting the AND across both, so it told the reader that readOnly()
        // answers guaranteesReadOnly() == false. It answers true, and the remediation that
        // followed pointed at a gate that was already correct.
        assertThatThrownBy(() -> CodeExecutionTool.builder(
                        (code, bridge) -> SandboxExecution.ok("x"), new SimpleToolRegistry())
                .toolFloor(dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                        dev.agentkit.core.reliability.ToolGates.readOnly(),
                        dev.agentkit.core.reliability.ToolGates.parkForApproval(i -> true,
                                dev.agentkit.core.reliability.ApprovalNeeded.because("ask"))))
                .sideEffects(dev.agentkit.core.tool.SideEffects.NONE)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guaranteesReadOnly")
                .hasMessageNotContaining("ToolGates$1");
    }

    @Test
    void aScriptCannotLaunderAReadByMakingTheToolFail() {
        // The bridge's own failure rule, which the two agent loops test and this runner did
        // not: a tool that throws returns whoever's words were in the exception (#113), so
        // failing is not a way to read without paying for it.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry()
                .register(FunctionTool.builder("fetch", "Fetches")
                        .sideEffects(SideEffects.NONE)
                        .provenance(Provenance.THIRD_PARTY)
                        .handler(i -> {
                            entered.add("fetch");
                            throw new IllegalStateException("HTTP 500: <the server's words>");
                        }).build())
                .register(FunctionTool.builder("publish", "Publishes")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(i -> {
                            entered.add("publish");
                            return ToolResult.ok("published");
                        }).build());

        CodeExecutionTool.builder((code, bridge) -> {
                    bridge.invoke("fetch", Map.of());
                    bridge.invoke("publish", Map.of("text", "x"));
                    return SandboxExecution.ok("done");
                }, registry)
                .toolFloor(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()))
                .build()
                .execute(new ToolInvocation("c1", "run_code", Map.of("code", "x")));

        assertThat(entered).containsExactly("fetch");
    }

    @Test
    void aSandboxThatDiesAfterAReadStillSaysWhatWasRead() {
        // Two of four exit paths discarded what the script had read, so a script that
        // fetched a page and then made the sandbox throw came back UNKNOWN — and a floor
        // keyed on THIRD_PARTY never lowered, leaving the next turn free to write.
        SimpleToolRegistry registry = new SimpleToolRegistry()
                .register(FunctionTool.builder("fetch", "Fetches")
                        .sideEffects(SideEffects.NONE)
                        .provenance(Provenance.THIRD_PARTY)
                        .handler(i -> ToolResult.ok("a page")).build());
        ToolInvocation call = new ToolInvocation("c1", "run_code", Map.of("code", "x"));

        Tool throwsAfterReading = CodeExecutionTool.builder((code, bridge) -> {
                    bridge.invoke("fetch", Map.of());
                    throw new IllegalStateException("sandbox transport died");
                }, registry).allowAllTools().build();
        Tool answersNothingAfterReading = CodeExecutionTool.builder((code, bridge) -> {
                    bridge.invoke("fetch", Map.of());
                    return null;
                }, registry).allowAllTools().build();

        assertThat(throwsAfterReading.execute(call).provenance()).isEqualTo(Provenance.THIRD_PARTY);
        assertThat(answersNothingAfterReading.execute(call).provenance())
                .isEqualTo(Provenance.THIRD_PARTY);
    }

    @Test
    void aSandboxThatDiesWithoutReadingAnythingSaysSo() {
        // The control: those two paths report what the script read, not "third-party
        // because something went wrong".
        Tool diesImmediately = CodeExecutionTool.builder((code, bridge) -> {
                    throw new IllegalStateException("sandbox transport died");
                }, new SimpleToolRegistry()).allowAllTools().build();

        assertThat(diesImmediately.execute(
                new ToolInvocation("c1", "run_code", Map.of("code", "x"))).provenance())
                .isEqualTo(Provenance.UNKNOWN);
    }

    @Test
    void allowAllToolsAlsoConflictsWithAFloor() {
        // It set the gate without clearing the floor, so .toolFloor(...).allowAllTools()
        // silently ignored allowAllTools — the same silent drop in a third direction.
        assertThatThrownBy(() -> CodeExecutionTool.builder(
                        (code, bridge) -> SandboxExecution.ok("x"), new SimpleToolRegistry())
                .toolFloor(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()))
                .allowAllTools()
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alternatives");
    }
}
