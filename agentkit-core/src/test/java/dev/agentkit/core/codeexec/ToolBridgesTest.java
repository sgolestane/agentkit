package dev.agentkit.core.codeexec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolBridgesTest {

    private static SimpleToolRegistry registry() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("echo", "echoes its input")
                        .handler(i -> ToolResult.ok("echo:" + i.stringArgument("text")))
                        .build());
    }

    @Test
    void aGateThatDecidesFromTheToolSeesTheResolvedTool() {
        // Regression: the bridge resolved the tool and then evaluated the gate without it.
        // ToolGates.readOnly() must refuse the invocation-only form (without the tool it
        // cannot know), so dropping it turned a read-only bridge into deny-everything —
        // silently, and in the direction that looks like the gate working.
        SimpleToolRegistry registry = new SimpleToolRegistry()
                .register(FunctionTool.builder("search", "reads the corpus")
                        .readOnly()
                        .handler(i -> ToolResult.ok("results"))
                        .build())
                .register(FunctionTool.builder("send", "sends mail")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(i -> ToolResult.ok("sent"))
                        .build());
        ToolBridge bridge = ToolBridges.of(registry, ToolGates.readOnly());

        ToolResult allowed = bridge.invoke("search", Map.of());
        assertThat(allowed.isError()).as("a NONE tool must pass a read-only bridge").isFalse();
        assertThat(allowed.content()).isEqualTo("results");

        ToolResult denied = bridge.invoke("send", Map.of());
        assertThat(denied.isError()).isTrue();
        assertThat(denied.content()).contains("read-only").contains("send");
        // The wrong-overload symptom: readOnly()'s invocation-only branch reports UNKNOWN
        // ("it declares none either way") even for a tool that declares EXTERNAL.
        assertThat(denied.content()).doesNotContain("declares none either way");
    }

    @Test
    void argumentsAScriptBuiltThatTheFrameworkWillNotCarryComeBackAsARefusal() {
        // The third of the four runners #246 names, and the one whose caller is not a model:
        // a CodeSandbox hands over a map the script built, so a structure past the bound or
        // a value no JSON document could hold arrives here without anybody being hostile.
        //
        // Before, this was caught by the branch below and came back as
        // ToolResult.failed("Tool 'echo' failed.") with the framework's own
        // IllegalArgumentException fenced inside it — a sentence saying the tool failed
        // about a tool that was never entered, and one a script cannot act on because it
        // does not say what to send instead.
        Map<String, Object> at = new java.util.LinkedHashMap<>();
        at.put("v", "x");
        for (int i = 1; i < dev.agentkit.core.util.Frozen.MAX_DEPTH + 1; i++) {
            Map<String, Object> up = new java.util.LinkedHashMap<>();
            up.put("n", at);
            at = up;
        }
        AtomicInteger ran = new AtomicInteger();
        SimpleToolRegistry registry = new SimpleToolRegistry()
                .register(FunctionTool.builder("echo", "echoes its input")
                        .handler(i -> {
                            ran.incrementAndGet();
                            return ToolResult.ok("echoed");
                        })
                        .build());
        AtomicInteger gated = new AtomicInteger();
        ToolGate counting = (tool, invocation) -> {
            gated.incrementAndGet();
            return dev.agentkit.core.reliability.GateResult.allow();
        };

        ToolResult result = ToolBridges.of(registry, counting).invoke("echo", at);

        assertThat(result.isError()).isTrue();
        assertThat(result.content())
                .as("the script was told its tool failed, about a tool that was never entered")
                .doesNotContain("Tool 'echo' failed.");
        assertThat(result.content())
                .contains("refused before anything ran")
                .contains("No tool ran, no gate was asked");
        assertThat(gated.get()).as("a gate judged arguments the tool never received").isZero();
        assertThat(ran.get()).as("a tool ran on arguments nothing froze").isZero();
        // FIRST_PARTY, not ToolResult.error's UNKNOWN default: a caller's TrustFloor is asked
        // lowersOn(provenance), so an UNKNOWN here would let a call that never happened
        // tighten the policy for the rest of the script.
        assertThat(result.provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.FIRST_PARTY);
    }

    @Test
    void aRefusedCallStillCountsAgainstTheBridgesCallCap() {
        // Denied and unknown calls count, on the argument that they are still fan-out
        // attempts; a refused one is no different, and a loop of them must not be a way to
        // sidestep the cap. The counter is incremented before the arguments are looked at,
        // so this holds by where the statement sits — asserted because moving the refusal
        // above it would be an easy and invisible change.
        ToolBridge bridge = ToolBridges.of(registry(), ToolGate.ALLOW_ALL, 1);
        Map<String, Object> bad = Map.of("paths", new String[] {"/etc/shadow"});

        ToolResult first = bridge.invoke("echo", bad);
        ToolResult second = bridge.invoke("echo", Map.of("text", "hi"));

        assertThat(first.content()).contains("refused before anything ran");
        assertThat(second.isError()).isTrue();
        assertThat(second.content()).contains("Tool-call budget exhausted");
    }

    @Test
    void invokesAKnownToolAndReturnsItsResult() {
        ToolBridge bridge = ToolBridges.ofUngated(registry());
        ToolResult result = bridge.invoke("echo", Map.of("text", "hi"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("echo:hi");
    }

    @Test
    void anUnknownToolBecomesAnErrorResultNotAThrow() {
        ToolResult result = ToolBridges.ofUngated(registry()).invoke("missing", Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Unknown tool").contains("missing");
    }

    @Test
    void everyRefusalThisBridgeWritesSaysItIsTheFrameworksOwnWords() {
        // #272. Three branches this bridge owns — the budget, the unknown name, the gate's
        // denial — used to leave ToolResult.error's UNKNOWN default, which the design treats
        // as somebody else's. None of the three resolves a tool, enters one, or reads
        // anything.
        //
        // Asserted as a value here rather than as a consequence, and that is a real
        // limitation rather than a shortcut: this bridge returns each of these before
        // lowerIfNeeded, and its one consumer (CodeExecutionTool) reads provenance only to
        // ask whether it is THIRD_PARTY, so UNKNOWN and FIRST_PARTY are indistinguishable
        // to everything downstream of here today. What the label protects is a caller's own
        // audit trail and whatever policy reads the result next; the consequence version of
        // this property is asserted where a consequence exists, in itops'
        // WorkflowRefusalsAreFirstPartyTest, whose loop does ask lowersOn on every step.
        ToolBridge capped = ToolBridges.of(registry(), ToolGate.ALLOW_ALL, 1);
        capped.invoke("echo", Map.of("text", "hi"));

        assertThat(capped.invoke("echo", Map.of("text", "hi")).provenance())
                .as("a budget refusal reads as content of unknown origin")
                .isEqualTo(dev.agentkit.core.tool.Provenance.FIRST_PARTY);
        assertThat(ToolBridges.ofUngated(registry()).invoke("missing", Map.of()).provenance())
                .as("an unknown-tool refusal reads as content of unknown origin")
                .isEqualTo(dev.agentkit.core.tool.Provenance.FIRST_PARTY);
        assertThat(ToolBridges.of(registry(),
                        (ToolGate) (tool, invocation) ->
                                dev.agentkit.core.reliability.GateResult.deny("out of scope"))
                .invoke("echo", Map.of("text", "hi")).provenance())
                .as("a gate denial — the deployment's own policy speaking — reads as"
                        + " content of unknown origin")
                .isEqualTo(dev.agentkit.core.tool.Provenance.FIRST_PARTY);
        // The fourth: a gate that wants a person, which this runner cannot ask. The frame
        // is the gate's reason and the sentence after it is this bridge's own; neither read
        // anything, and a park is the branch where "nothing has been decided yet" is most
        // clearly true.
        ToolResult parked = ToolBridges.of(registry(),
                        (ToolGate) (tool, invocation) ->
                                dev.agentkit.core.reliability.GateResult.needsAPerson(
                                        dev.agentkit.core.reliability.ApprovalNeeded.because(
                                                "a person must look"), invocation))
                .invoke("echo", Map.of("text", "hi"));
        assertThat(parked.content())
                .as("the denominator for this arm: the gate really did want a person")
                .contains("Nobody can be asked from inside a sandboxed script");
        assertThat(parked.provenance())
                .as("a call nobody could be asked about reads as content of unknown origin")
                .isEqualTo(dev.agentkit.core.tool.Provenance.FIRST_PARTY);
    }

    @Test
    void aToolsOwnFailureIsStillNotTheFrameworksWords() {
        // The denominator, and the half a blanket FIRST_PARTY default on ToolResult.error
        // would have got wrong: what comes back from a tool that threw carries whoever's
        // words were in the exception, so it inherits the tool's declaration rather than
        // claiming the deployment's authorship.
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("boom", "throws")
                        .provenance(dev.agentkit.core.tool.Provenance.THIRD_PARTY)
                        .handler(i -> {
                            throw new IllegalStateException("kaboom");
                        }).build());

        assertThat(ToolBridges.ofUngated(registry).invoke("boom", Map.of()).provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
    }

    @Test
    void aThrowingToolBecomesAnErrorResult() {
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("boom", "throws").handler(i -> {
                    throw new IllegalStateException("kaboom");
                }).build());
        ToolResult result = ToolBridges.ofUngated(registry).invoke("boom", Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("boom").contains("kaboom");
    }

    @Test
    void nullArgumentsAreTreatedAsEmpty() {
        ToolResult result = ToolBridges.ofUngated(registry()).invoke("echo", null);
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("echo:null"); // no "text" arg
    }

    @Test
    void aCallBudgetStopsAScriptFromFanningOutUnbounded() {
        AtomicInteger calls = new AtomicInteger();
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("ping", "pings").handler(i -> {
                    calls.incrementAndGet();
                    return ToolResult.ok("pong");
                }).build());
        ToolBridge bridge = ToolBridges.of(registry, ToolGate.ALLOW_ALL, 2);

        assertThat(bridge.invoke("ping", Map.of()).isError()).isFalse();
        assertThat(bridge.invoke("ping", Map.of()).isError()).isFalse();
        ToolResult third = bridge.invoke("ping", Map.of());

        assertThat(third.isError()).isTrue();
        assertThat(third.content()).contains("budget exhausted");
        assertThat(calls).hasValue(2); // the third call never reached the tool
    }

    @Test
    void aDeniedCallStillConsumesTheBudget() {
        // A denied call is still a fan-out attempt, so it counts — a loop of them
        // cannot be used to sidestep the cap.
        ToolBridge bridge = ToolBridges.of(registry(), ToolGates.denyTools(Set.of("echo")), 1);
        assertThat(bridge.invoke("echo", Map.of()).isError()).isTrue(); // denied, uses the 1 slot
        assertThat(bridge.invoke("echo", Map.of()).content()).contains("budget exhausted");
    }

    @Test
    void anUnknownCallStillConsumesTheBudget() {
        ToolBridge bridge = ToolBridges.of(registry(), ToolGate.ALLOW_ALL, 1);
        assertThat(bridge.invoke("missing", Map.of()).content()).contains("Unknown tool"); // uses the slot
        assertThat(bridge.invoke("echo", Map.of()).content()).contains("budget exhausted");
    }

    @Test
    void unlimitedImposesNoCap() {
        AtomicInteger calls = new AtomicInteger();
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("ping", "pings").handler(i -> {
                    calls.incrementAndGet();
                    return ToolResult.ok("pong");
                }).build());
        ToolBridge bridge = ToolBridges.of(registry, ToolGate.ALLOW_ALL, ToolBridges.UNLIMITED);
        for (int i = 0; i < 5000; i++) {
            assertThat(bridge.invoke("ping", Map.of()).isError()).isFalse();
        }
        assertThat(calls).hasValue(5000);
    }

    @Test
    void aNonPositiveCallBudgetIsRejected() {
        assertThatThrownBy(() -> ToolBridges.of(registry(), ToolGate.ALLOW_ALL, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGatedBridgeDeniesBlockedToolsInsideCode() {
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("delete", "deletes things")
                        .handler(i -> ToolResult.ok("deleted")).build());
        ToolBridge bridge = ToolBridges.of(registry, ToolGates.denyTools(Set.of("delete")));

        ToolResult result = bridge.invoke("delete", Map.of("path", "/etc/passwd"));

        assertThat(result.isError()).isTrue(); // the gate blocked it, same as the agent loop would
    }

    @Test
    void eachBridgedCallGetsAUniqueInvocationId() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("t", "t").handler(i -> ToolResult.ok("ok")).build());
        // A gate that observes each invocation's id; the bridge must not reuse ids
        // (else an id-keyed approver would conflate distinct code-called invocations).
        ToolBridge bridge = ToolBridges.of(registry, ToolGates.denyIf(i -> {
            ids.add(i.id());
            return false;
        }, "n/a"));

        bridge.invoke("t", Map.of());
        bridge.invoke("t", Map.of());

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void aGatedBridgeHonorsAnApprovalThatEditsArguments() {
        Object[] executedAmount = new Object[1];
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("wire", "wires money").handler(i -> {
                    executedAmount[0] = i.argument("amount");
                    return ToolResult.ok("wired");
                }).build());
        // The approver caps the amount, exactly as it would for a direct tool call.
        ToolBridge bridge = ToolBridges.of(registry, ToolGates.requireApproval(i -> true,
                (tool, i) -> ApprovalDecision.approveWithArguments(Map.of("amount", 10))));

        bridge.invoke("wire", Map.of("amount", 1_000_000));

        assertThat(executedAmount[0]).isEqualTo(10);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a gate cannot redirect the bridge to another tool")
    void aRedirectRunsNeitherTool() {
        // The third runner, and the one where the gate is load-bearing: it is the only
        // reason a script may be declared sideEffects=NONE, so a replacement that renamed
        // the tool ran the originally resolved one with the replacement's arguments, under
        // a declaration saying nothing could happen (#104). The two agent loops were fixed
        // and this was not, which is exactly the drift GateResult.effectiveFor exists to
        // stop — three runners, one rule.
        java.util.List<String> published = new java.util.ArrayList<>();
        java.util.List<String> drafted = new java.util.ArrayList<>();
        dev.agentkit.core.tool.SimpleToolRegistry registry =
                new dev.agentkit.core.tool.SimpleToolRegistry()
                        .register(dev.agentkit.core.tool.FunctionTool
                                .builder("publish", "publishes publicly")
                                .handler(i -> {
                                    published.add("ran");
                                    return ToolResult.ok("published");
                                }).build())
                        .register(dev.agentkit.core.tool.FunctionTool
                                .builder("draft", "saves privately")
                                .handler(i -> {
                                    drafted.add("ran");
                                    return ToolResult.ok("drafted");
                                }).build());
        dev.agentkit.core.reliability.ToolGate redirecting = (tool, invocation) ->
                dev.agentkit.core.reliability.GateResult.allowWith(
                        new dev.agentkit.core.tool.ToolInvocation(
                                invocation.id(), "draft", invocation.arguments()));

        ToolResult result = ToolBridges.of(registry, redirecting)
                .invoke("publish", Map.of("secret", "yes"));

        assertThat(published).as("the redirect was ignored and the public tool ran").isEmpty();
        assertThat(drafted).as("the redirect was honoured, so an ungated tool ran").isEmpty();
        // The positive control: the refusal came back as an error rather than nothing
        // having been attempted.
        assertThat(result.isError()).isTrue();
    }

    @Test
    void aParkedCallIsRefusedRatherThanHeld() {
        // A script is mid-execution inside a sandbox with its own timeout, and there is no
        // resume that lands back on the line that called invoke(). So this runner gives the
        // only honest answer it has. Counting the tool, not the gate: a bridge that refused
        // and ran it anyway is the failure worth asserting against.
        AtomicInteger ran = new AtomicInteger();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            ran.incrementAndGet();
                            return ToolResult.ok("published");
                        })
                        .build());
        ToolBridge bridge = ToolBridges.of(registry, ToolGates.parkForApproval(
                i -> true, dev.agentkit.core.reliability.ApprovalNeeded.because("needs a person")));

        ToolResult result = bridge.invoke("publish", Map.of("text", "x"));

        assertThat(ran.get()).isZero();
        assertThat(result.isError()).isTrue();
        assertThat(result.content())
                .as("a refusal reading 'submitted for approval' would have the script — and"
                        + " the model reading its output — believe somebody is going to answer")
                .contains("Nobody can be asked");
    }

    @Test
    void aBrokenGateInsideAScriptIsNotReportedAsAFlakyTool() {
        // #268, and the reason this runner rather than a field on AgentResult.
        //
        // #260 split the operator's line in two on Agent.runTool and ToolActivitiesImpl:
        // error for a gate that threw, warn for a tool that threw. This runner was left
        // with NEITHER. A gate that throws here produced ToolResult.failed("Tool 'x'
        // failed."), attributed to a tool that was never entered, and nothing else at all --
        // no log line, no Disposition, no observer, because a bridged call reaches no
        // AgentObserver and produces no AgentResult. So the deployment #268 describes -- a
        // ToolGate that throws on every call, runs completing, everything looking healthy --
        // was worse here than on the two loops the issue names, and invisible instead of
        // merely blurred.
        //
        // That is also why the two options #268 priced were both rejected. A non-NONE
        // default observer would emit a SECOND line for a fact the two agent loops already
        // log at error, and would fire onTextDelta once per streamed chunk for every caller
        // who never asked for an observer -- and it would reach this runner not at all. A
        // field on AgentResult would reach this runner not at all either (there is no
        // AgentResult for a call made from inside a sandbox), and its durable mirror on
        // AgentRunResult crosses DurableJson, which disables FAIL_ON_UNKNOWN_PROPERTIES:
        // an old worker would silently drop it and report a clean run, which is a positive
        // claim of health and worse than the absence. ContentBlockMixin states that hazard
        // and #246 rejected a design for it.
        //
        // Levels are asserted and not only sentences, because half of what #260 decided is
        // that the two facts sit at different levels, and a change that renamed the lines
        // while leaving both at warn would pass every assertion about their text.
        //
        // WHAT THIS KILLS. Nine mutants against the change, each applied to this branch,
        // built with `clean`, and put through this class (18 tests, 0 skipped). All nine
        // died here, and a tenth -- a comment added beside the branch and nothing else --
        // was run first and survived, which is what says the other nine were really applied:
        //
        //   both log statements deleted (the defect restored)  -> "said nothing at all"
        //   one line for two facts, "(gate or execution) threw" -> "said nothing at all"
        //   `entered = true` deleted                            -> the tool-threw block
        //   `entered` initialised true                          -> "said nothing at all"
        //   effectiveFor put back inside the execute statement  -> the refused-redirect block
        //   the two levels swapped                              -> "raised to the level
        //                                                          reserved for one nobody
        //                                                          routes around"
        //   the gate line downgraded to warn                    -> "left at the level used
        //                                                          for an ordinary tool
        //                                                          failure"
        //   Quoted.failure(e) dropped from the gate line        -> the cause assertion
        //   the tool name hard-coded in the gate line           -> "said nothing at all"
        AtomicInteger ran = new AtomicInteger();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "publishes")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            ran.incrementAndGet();
                            return ToolResult.ok("published");
                        })
                        .build());
        ToolGate brokenGate = (tool, invocation) -> {
            throw new IllegalStateException("the policy service is down");
        };

        ToolResult[] gateFailure = new ToolResult[1];
        String whenTheGateThrew = errWhile(() -> gateFailure[0] =
                ToolBridges.of(registry, brokenGate).invoke("publish", Map.of("text", "x")));

        assertThat(ran.get())
                .as("a gate that threw is fail-closed: the call must not have run")
                .isZero();
        assertThat(gateFailure[0].isError())
                .as("the script has to be told the call failed, whatever the operator is"
                        + " told")
                .isTrue();
        assertThat(whenTheGateThrew)
                .as("the deployment's own policy code broke at an authorization boundary"
                        + " inside a sandbox, and this runner said nothing at all about it")
                .contains("Gate for tool 'publish' threw inside a code-execution script")
                .contains("nothing was decided and nothing ran")
                // The cause, not only the name: the script's copy is fenced and cut, so the
                // operator's line is the one place the exception arrives as itself.
                .contains("the policy service is down");
        assertThat(whenTheGateThrew)
                .as("a broken gate was reported in the words used for a tool that ran and"
                        + " failed, so an operator alerting on one alerts on both")
                .doesNotContain("was entered and threw");
        assertThat(levelOfLineContaining(whenTheGateThrew, "Gate for tool"))
                .as("the one channel that reports a broken authorization boundary on this"
                        + " runner was left at the level used for an ordinary tool failure")
                .isEqualTo("ERROR");

        // The other fact, because a branch that fires the same line either way reports
        // nothing and an unguarded error passes every assertion above.
        AtomicInteger sideEffects = new AtomicInteger();
        SimpleToolRegistry throwing = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "publishes")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            sideEffects.incrementAndGet();
                            throw new IllegalStateException("the file was locked");
                        })
                        .build());
        String whenTheToolThrew = errWhile(() ->
                ToolBridges.ofUngated(throwing).invoke("publish", Map.of("text", "x")));

        assertThat(sideEffects.get())
                .as("the precondition: this path is the one where the tool WAS entered")
                .isEqualTo(1);
        assertThat(whenTheToolThrew)
                .contains("Tool 'publish' was entered and threw inside a code-execution"
                        + " script")
                .contains("the file was locked");
        assertThat(whenTheToolThrew)
                .as("an ordinary tool failure was reported as the deployment's policy code"
                        + " being broken, which is the alert this line exists to let an"
                        + " operator build")
                .doesNotContain("Gate for tool");
        assertThat(levelOfLineContaining(whenTheToolThrew, "was entered and threw"))
                .as("a failure the script routes around was raised to the level reserved"
                        + " for one nobody routes around")
                .isEqualTo("WARN");

        // A gate whose REPLACEMENT is refused counts as the gate failing, not the tool
        // failing, and that is what hoisting effectiveFor out of the execute statement
        // bought. GateResult.effectiveFor throws RefusedSubstitution for a replacement that
        // renames the tool -- the #104 case aRedirectRunsNeitherTool already pins from the
        // other side -- and before the hoist that throw shared a statement with execute()
        // and would have been reported as the tool having been entered. Nothing ran either
        // way, so the log line is the only thing that can tell them apart.
        AtomicInteger neverRan = new AtomicInteger();
        SimpleToolRegistry two = new SimpleToolRegistry()
                .register(FunctionTool.builder("publish", "publishes publicly")
                        .handler(i -> {
                            neverRan.incrementAndGet();
                            return ToolResult.ok("published");
                        }).build())
                .register(FunctionTool.builder("draft", "saves privately")
                        .handler(i -> {
                            neverRan.incrementAndGet();
                            return ToolResult.ok("drafted");
                        }).build());
        ToolGate redirecting = (tool, invocation) -> GateResult.allowWith(
                new ToolInvocation(invocation.id(), "draft", invocation.arguments()));

        String whenTheReplacementWasRefused = errWhile(() ->
                ToolBridges.of(two, redirecting).invoke("publish", Map.of("secret", "yes")));

        assertThat(neverRan.get()).as("neither tool may run for a refused redirect").isZero();
        assertThat(whenTheReplacementWasRefused)
                .as("a gate whose replacement was refused was filed as a tool that had been"
                        + " entered, so a call that never reached a tool reads in the log"
                        + " like one that may have landed half a side effect")
                .contains("Gate for tool 'publish' threw inside a code-execution script")
                .doesNotContain("was entered and threw");

        // The other side of both branches, because a line that fires unconditionally
        // reports nothing.
        ToolResult[] ok = new ToolResult[1];
        String whenNothingBroke = errWhile(() -> ok[0] =
                ToolBridges.ofUngated(registry()).invoke("echo", Map.of("text", "hi")));
        // The sentinel, and it is the reason this block is not two assertions that a
        // never-executed body would also satisfy. A successful call logs nothing at all, so
        // there is no line in the capture to anchor on; what anchors it is that the call
        // demonstrably happened and demonstrably succeeded. #225 found two tests in this
        // repository that passed because the thing they asserted about had not run.
        assertThat(ok[0].content())
                .as("the negative control did not actually make a successful call, so the"
                        + " two assertions below are about an empty capture")
                .isEqualTo("echo:hi");
        assertThat(whenNothingBroke)
                .as("a call that succeeded was reported as a failure")
                .doesNotContain("was entered and threw")
                .doesNotContain("Gate for tool");
    }

    /**
     * The slf4j level of the first captured line containing {@code needle}.
     *
     * <p>Fails loudly rather than returning something falsy when the line is absent: a
     * helper that answered {@code ""} for a missing line would turn every assertion built
     * on it into one that passes when nothing was logged, which is the shape a test must
     * never have. The same helper, for the same reason, as {@code AgentToolErrorTest}'s.
     *
     * <p>{@code slf4j-simple}'s default layout is {@code [thread] LEVEL logger - message}
     * and no {@code simplelogger.properties} overrides it anywhere in this repository, so
     * the level is the token after the bracketed thread name — matched by regex rather than
     * counted, since a thread name may contain spaces.
     */
    private static String levelOfLineContaining(String captured, String needle) {
        java.util.regex.Pattern level =
                java.util.regex.Pattern.compile("]\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s");
        for (String line : captured.split("\\R")) {
            if (line.contains(needle)) {
                java.util.regex.Matcher matcher = level.matcher(line);
                if (!matcher.find()) {
                    throw new AssertionError("unparseable log line: " + line);
                }
                return matcher.group(1);
            }
        }
        throw new AssertionError("no captured line contained: " + needle);
    }

    /**
     * Everything {@code body} writes to {@code System.err}, with the stream put back.
     *
     * <p>{@code slf4j-simple} resolves {@code System.err} on each write — its
     * {@code cacheOutputStream} setting is off by default — so a logger initialised long
     * before this call still lands in the buffer. Restored in a {@code finally}, because a
     * test that leaves {@code System.err} replaced takes the rest of the suite's output
     * with it.
     */
    private static String errWhile(Runnable body) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
