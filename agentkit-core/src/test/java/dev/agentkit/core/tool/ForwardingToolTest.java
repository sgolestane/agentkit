package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.codeexec.CodeExecutionTool;
import dev.agentkit.core.codeexec.CodeSandbox;
import dev.agentkit.core.codeexec.SandboxExecution;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.Approver;
import dev.agentkit.core.reliability.ToolGates;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link ForwardingTool} carries, and what it does not remove (#296).
 *
 * <p>{@link Tool} has four abstract members and seven with defaults. A decorator that
 * forwards the four compiles, and the seven then answer for the decorator rather than for
 * the tool inside it. Two of the seven — {@link Tool#holdsGateWaitingForAHuman()} and
 * {@link Tool#holdsGateBoundToOneRun()} — default to {@code false} and are read by a
 * durable worker at registration, so an omission there fails <em>open</em>. The rest of
 * that story is measured against a real worker in {@code DurableToolHeldGateTest}; here it
 * is measured against the declarations themselves, which is where it starts.
 *
 * <p>{@link #aHandWrittenForwarderStillDropsEverythingItAlwaysDid} is the honest half: the
 * mistake is still expressible, and this test pins exactly how much of it survives.
 *
 * <h2>What the mutation pass killed, and where</h2>
 *
 * <p>Each mutant was applied to this branch, built clean, and put through this class,
 * {@code ReadOnlyGateTest}, {@code DeclaredProvenanceReachesThePolicyTest},
 * {@code AgentTelemetryTest} in {@code agentkit-otel} and {@code DurableToolHeldGateTest}
 * in {@code agentkit-temporal}. Every one is reported by its {@code Tests run:} line, never
 * by the build's exit status — a killed surefire fork prints {@code Tests run: 0}, which is
 * not a result either.
 *
 * <pre>
 * mutant                                              killed by
 * ForwardingTool.holdsGateWaitingForAHuman() deleted  6 here, 3 durable, 1 otel
 * ForwardingTool.holdsGateBoundToOneRun() deleted     5 here, 2 durable, 1 otel
 * ForwardingTool.spec() deleted                       4 here, 1 otel
 * ForwardingTool.inputExamples() deleted              4 here, 1 otel
 * ForwardingTool.sideEffects() deleted                4 here, 1 ReadOnlyGateTest, 2 otel
 * ForwardingTool.provenance() deleted                 3 here, 1 otel
 * ForwardingTool.name() returning a constant          1 here, 1 durable, 1 otel
 * ForwardingTool.execute() not delegating             2 here
 * ForwardingTool.boundTo() deleted                    4 here, 1 RunKnowsItsParent, 1 otel
 * ForwardingTool.boundTo() as delegate().boundTo()    5 here, 1 RunKnowsItsParent, 6 otel
 * ForwardingTool.rebuiltAround() returning `bound`    1 here
 * Tools.Declared.rebuiltAround() deleted              1 here
 * Tools.Attributed.rebuiltAround() deleted            1 here, 1 RunKnowsItsParent
 * holdsGateWaitingForAHuman() forced to true          forwardsTheNegativeToo, 2 durable
 * holdsGateBoundToOneRun() forced to true             forwardsTheNegativeToo
 * Tools.Declared.sideEffects() forwarding instead     theShippedDeclaringDecorator…
 * Tools.Attributed.provenance() forwarding instead    theShippedAttributingDecorator…,
 *                                                      DeclaredProvenanceReachesThePolicy…
 * TracingTool reverted to bare `implements Tool`      4 otel
 * </pre>
 *
 * <p><strong>Two mutants survived on the first pass, and the survival is the finding rather
 * than the footnote.</strong> Forcing either gate declaration to {@code true} killed nothing
 * here: every delegate in {@link #forwardsAllTenMembers} answers {@code true}, so a constant
 * satisfied all of it. Over-reporting is not harmless — it bans an ordinary decorated tool
 * from the runner built for unattended work — so {@link #forwardsTheNegativeToo} was added,
 * and it kills both. Deleting {@code provenance()} likewise left the whole {@code otel}
 * suite green, because {@code TracingTool} reads the delegate directly when it writes the
 * span attribute and nothing there asked the wrapper what it answers;
 * {@code AgentTelemetryTest.tracingAToolPreservesWhoWroteWhatItReturns} was added for that.
 * No survivors after those two, and none skipped.
 *
 * <p><strong>#317's pass added a third survivor of the same shape.</strong> Deleting
 * {@link ForwardingTool#boundTo} killed four tests here and nothing in
 * {@code RunKnowsItsParentTest}, because every test there registered the bare
 * {@code delegate} — so the composition an instrumented deployment actually runs was
 * measured only against decorators this file writes itself. A test that wires a shipped
 * decorator around the real {@code delegate} inside a real {@code Agent} was added there,
 * and one in {@code agentkit-otel} for {@code TracingTool}, which had the same hole.
 */
class ForwardingToolTest {

    private static final ToolSpec HAND_WRITTEN_SPEC = new ToolSpec(
            "publish", "a description the getters do not return",
            Map.of("type", "object"), List.of(Map.of("q", "kittens")));

    /** Distinct in every one of the ten answers, so no forward can pass by coincidence. */
    private static final class Declaring implements Tool {
        private final List<ToolInvocation> ran = new ArrayList<>();

        @Override
        public String name() {
            return "publish";
        }

        @Override
        public String description() {
            return "publishes things";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
        }

        @Override
        public List<Map<String, Object>> inputExamples() {
            return List.of(Map.of("q", "kittens"));
        }

        @Override
        public ToolSpec spec() {
            return HAND_WRITTEN_SPEC;
        }

        @Override
        public SideEffects sideEffects() {
            return SideEffects.EXTERNAL;
        }

        @Override
        public Provenance provenance() {
            return Provenance.THIRD_PARTY;
        }

        @Override
        public boolean holdsGateWaitingForAHuman() {
            return true;
        }

        @Override
        public boolean boundToOneRun() {
            return true;
        }

        @Override
        public boolean holdsGateBoundToOneRun() {
            return true;
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            ran.add(invocation);
            return ToolResult.ok("published");
        }
    }

    /** Decorates nothing; every answer has to come from the base class. */
    private static final class Bare extends ForwardingTool {
        private final Tool delegate;

        Bare(Tool delegate) {
            this.delegate = delegate;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }
    }

    /**
     * The decorator anyone writes when the compiler is the only guide: the four abstract
     * members, forwarded, and nothing else.
     */
    private static final class FourMethods implements Tool {
        private final Tool delegate;

        FourMethods(Tool delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public Map<String, Object> inputSchema() {
            return delegate.inputSchema();
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return delegate.execute(invocation);
        }
    }

    private static ToolInvocation call() {
        return new ToolInvocation("c1", "publish", Map.of("q", "kittens"));
    }

    @Test
    @DisplayName("every declaration survives the wrapper, including the two that fail open")
    void forwardsAllTenMembers() {
        Declaring inner = new Declaring();
        Tool wrapped = new Bare(inner);

        assertThat(wrapped.name()).isEqualTo("publish");
        assertThat(wrapped.description()).isEqualTo("publishes things");
        assertThat(wrapped.inputSchema()).isEqualTo(inner.inputSchema());
        assertThat(wrapped.inputExamples()).isEqualTo(List.of(Map.of("q", "kittens")));
        // Not rebuilt from the getters: the delegate's spec says something they do not.
        assertThat(wrapped.spec()).isEqualTo(HAND_WRITTEN_SPEC);
        assertThat(wrapped.spec().description())
                .isEqualTo("a description the getters do not return");
        assertThat(wrapped.sideEffects()).isEqualTo(SideEffects.EXTERNAL);
        assertThat(wrapped.provenance()).isEqualTo(Provenance.THIRD_PARTY);
        assertThat(wrapped.holdsGateWaitingForAHuman())
                .as("the default is false, so dropping this launders a blocking gate")
                .isTrue();
        assertThat(wrapped.holdsGateBoundToOneRun())
                .as("the default is false, so dropping this launders one run's objective")
                .isTrue();
        assertThat(wrapped.boundToOneRun())
                .as("the default is false, so dropping this launders one run's state --"
                        + " the reflection check below only proves the method is declared,"
                        + " and a forward replaced by `return false` passed every test in"
                        + " agentkit-core and agentkit-temporal (#328)")
                .isTrue();

        assertThat(wrapped.execute(call()).content()).isEqualTo("published");
        assertThat(inner.ran).singleElement().isEqualTo(call());
    }

    @Test
    @DisplayName("and it does not over-report: a tool holding nothing still holds nothing")
    void forwardsTheNegativeToo() {
        // The other direction, and it needs its own test: every delegate in the test above
        // answers true, so a forward replaced by a constant `true` would pass all of it.
        // Over-reporting is not safe either -- it bans an ordinary decorated tool from the
        // one runner built for unattended work, which is how a fail-closed answer to the
        // wrong question still costs something.
        Tool plain = FunctionTool.builder("search", "reads")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> ToolResult.ok("results"))
                .build();
        Tool wrapped = new Bare(plain);

        assertThat(wrapped.holdsGateWaitingForAHuman()).isFalse();
        assertThat(wrapped.holdsGateBoundToOneRun()).isFalse();
        assertThat(wrapped.boundToOneRun()).isFalse();
        assertThat(wrapped.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(wrapped.inputExamples()).isEmpty();
    }

    @Test
    @DisplayName("the base forwards every method Tool declares, counted rather than listed")
    void nothingOnTheInterfaceIsLeftBehind() {
        // A hand-written list of assertions cannot fail when a method is added to Tool.
        // This can: any new member without an override here is named in the failure.
        List<String> members = new ArrayList<>();
        List<String> unforwarded = new ArrayList<>();
        for (Method m : Tool.class.getDeclaredMethods()) {
            if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            members.add(m.getName());
            try {
                ForwardingTool.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException absent) {
                unforwarded.add(m.getName());
            }
        }

        // An empty interface would satisfy the assertion below vacuously, and reflection is
        // exactly the kind of test that can quietly start reading nothing.
        assertThat(new TreeSet<>(members)).containsExactly(
                "boundTo", "boundToOneRun", "description", "execute",
                "holdsGateBoundToOneRun",
                "holdsGateWaitingForAHuman", "inputExamples", "inputSchema", "name",
                "provenance", "sideEffects", "spec");
        assertThat(new TreeSet<>(unforwarded))
                .as("ForwardingTool must override every member of Tool, or the interface"
                        + " default answers for the decorator instead of the tool")
                .isEmpty();
    }

    @Test
    @DisplayName("a hand-written forwarder still drops seven of the eleven, the point")
    void aHandWrittenForwarderStillDropsEverythingItAlwaysDid() {
        // ForwardingTool does not make the mistake unexpressible -- Java cannot force an
        // override -- so this pins the size of what is left rather than claiming it is gone.
        // It must keep passing: it is the hazard, not the control.
        Declaring inner = new Declaring();
        Tool naive = new FourMethods(inner);

        assertThat(naive.name()).isEqualTo("publish");
        assertThat(naive.inputExamples()).as("the few-shot examples the model was to see")
                .isEmpty();
        assertThat(naive.spec()).as("rebuilt from the getters, not the delegate's own")
                .isNotEqualTo(HAND_WRITTEN_SPEC);
        assertThat(naive.sideEffects()).as("declared EXTERNAL, reported UNKNOWN")
                .isEqualTo(SideEffects.UNKNOWN);
        assertThat(naive.provenance()).as("declared THIRD_PARTY, reported UNKNOWN")
                .isEqualTo(Provenance.UNKNOWN);
        assertThat(naive.holdsGateWaitingForAHuman())
                .as("declared true, reported false — and false is the answer that passes"
                        + " a durable worker's registration check")
                .isFalse();
        assertThat(naive.holdsGateBoundToOneRun()).isFalse();
        assertThat(naive.boundToOneRun())
                .as("declared true, reported false — the third answer that passes a durable"
                        + " worker's registration check")
                .isFalse();
        assertThat(naive.boundTo(dev.agentkit.core.agent.AgentRun.of("supervisor")))
                .as("answers itself, so the run never reaches the tool inside and a child"
                        + " delegated through it names no parent (#317)")
                .isSameAs(naive);
    }

    @Test
    @DisplayName("a subclass overrides what it decorates and still inherits the rest")
    void anOverrideDoesNotCostTheOtherForwards() {
        Declaring inner = new Declaring();
        AtomicInteger calls = new AtomicInteger();
        Tool counting = new ForwardingTool() {
            @Override
            protected Tool delegate() {
                return inner;
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                calls.incrementAndGet();
                return super.execute(invocation);
            }
        };

        assertThat(counting.execute(call()).content()).isEqualTo("published");
        assertThat(calls).hasValue(1);
        assertThat(counting.holdsGateWaitingForAHuman()).isTrue();
        assertThat(counting.sideEffects()).isEqualTo(SideEffects.EXTERNAL);
    }

    // --- boundTo, which forwards by rebuilding rather than by handing over (#317) ----

    /**
     * A tool that wants the run: answers a distinct instance carrying it, the way
     * {@code SubagentTools}' {@code delegate} does.
     */
    private static final class WantsTheRun implements Tool {
        private final dev.agentkit.core.agent.AgentRun bound;

        WantsTheRun(dev.agentkit.core.agent.AgentRun bound) {
            this.bound = bound;
        }

        @Override
        public String name() {
            return "delegate";
        }

        @Override
        public String description() {
            return "hands work to somebody";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        @Override
        public Tool boundTo(dev.agentkit.core.agent.AgentRun run) {
            return new WantsTheRun(run);
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.ok(bound == null ? "no parent" : bound.id());
        }
    }

    @Test
    @DisplayName("a decorator is rebuilt around the bound tool, keeping both the run and itself")
    void aDecoratorRebuiltAroundABoundToolKeepsItsDecoration() {
        AtomicInteger calls = new AtomicInteger();
        dev.agentkit.core.agent.AgentRun supervisor =
                dev.agentkit.core.agent.AgentRun.of("supervisor");
        class Counting extends ForwardingTool {
            private final Tool delegate;

            Counting(Tool delegate) {
                this.delegate = delegate;
            }

            @Override
            protected Tool delegate() {
                return delegate;
            }

            @Override
            protected Tool rebuiltAround(Tool bound) {
                return new Counting(bound);
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                calls.incrementAndGet();
                return super.execute(invocation);
            }
        }

        Tool bound = new Counting(new WantsTheRun(null)).boundTo(supervisor);

        // The run reached the tool inside: delegate().boundTo(run) alone would have done
        // that too, so this half is not the interesting one.
        assertThat(bound.execute(call()).content()).isEqualTo(supervisor.id());
        // And the decoration survived it, which delegate().boundTo(run) would have thrown
        // away: the runner executes whatever boundTo returns, so returning the inner tool
        // silently drops the span, the counter or the limit the decorator exists to add.
        assertThat(calls)
                .as("the counting decorator still ran, so it was rebuilt rather than bypassed")
                .hasValue(1);
    }

    @Test
    @DisplayName("a decorator that does not rebuild keeps itself and loses the run")
    void aDecoratorThatDoesNotRebuildDegradesToNoParent() {
        // The default for rebuiltAround, and the direction it fails in. Bare overrides
        // nothing, so the decoration is kept and the binding is dropped -- a missing parent,
        // never a wrong one, and never an undecorated tool handed to the runner.
        Tool bare = new Bare(new WantsTheRun(null));
        Tool bound = bare.boundTo(dev.agentkit.core.agent.AgentRun.of("supervisor"));

        assertThat(bound).isSameAs(bare);
        assertThat(bound.execute(call()).content()).isEqualTo("no parent");
    }

    @Test
    @DisplayName("a wrapped tool that ignores the run costs nothing: the decorator is reused")
    void wrappingAToolThatIgnoresTheRunAllocatesNothing() {
        // The common case by a very long way, and the reason the identity check is in the
        // base class: almost every tool answers boundTo with itself, so there is nothing to
        // rebuild around and rebuiltAround is never reached.
        Tool wrapped = new Bare(new Declaring());

        assertThat(wrapped.boundTo(dev.agentkit.core.agent.AgentRun.of("supervisor")))
                .isSameAs(wrapped);
    }

    @Test
    @DisplayName("Tools.withSideEffects re-declares one thing and forwards the other nine")
    void theShippedDeclaringDecoratorStillForwards() {
        Declaring inner = new Declaring();
        Tool declared = Tools.withSideEffects(inner, SideEffects.NONE);

        // Rebuilt around the bound tool, keeping the re-declaration: a shipped decorator
        // that returned `bound` here would hand the runner a tool whose sideEffects() is
        // EXTERNAL again, which ToolGates.readOnly refuses (#317).
        Tool boundDeclared = Tools.withSideEffects(new WantsTheRun(null), SideEffects.NONE)
                .boundTo(dev.agentkit.core.agent.AgentRun.of("supervisor"));
        assertThat(boundDeclared.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(boundDeclared.execute(call()).content()).startsWith("supervisor#");

        assertThat(declared.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(declared.provenance()).isEqualTo(Provenance.THIRD_PARTY);
        assertThat(declared.spec()).isEqualTo(HAND_WRITTEN_SPEC);
        assertThat(declared.inputExamples()).isEqualTo(List.of(Map.of("q", "kittens")));
        assertThat(declared.holdsGateWaitingForAHuman()).isTrue();
        assertThat(declared.holdsGateBoundToOneRun()).isTrue();
        assertThat(declared).hasToString("Tools.withSideEffects[" + inner + ", NONE]");
    }

    @Test
    @DisplayName("Tools.withProvenance likewise")
    void theShippedAttributingDecoratorStillForwards() {
        Declaring inner = new Declaring();
        Tool attributed = Tools.withProvenance(inner, Provenance.FIRST_PARTY);

        Tool boundAttributed =
                Tools.withProvenance(new WantsTheRun(null), Provenance.FIRST_PARTY)
                        .boundTo(dev.agentkit.core.agent.AgentRun.of("supervisor"));
        assertThat(boundAttributed.provenance()).isEqualTo(Provenance.FIRST_PARTY);
        assertThat(boundAttributed.execute(call()).content()).startsWith("supervisor#");

        assertThat(attributed.provenance()).isEqualTo(Provenance.FIRST_PARTY);
        assertThat(attributed.sideEffects()).isEqualTo(SideEffects.EXTERNAL);
        assertThat(attributed.spec()).isEqualTo(HAND_WRITTEN_SPEC);
        assertThat(attributed.inputExamples()).isEqualTo(List.of(Map.of("q", "kittens")));
        assertThat(attributed.holdsGateWaitingForAHuman()).isTrue();
        assertThat(attributed.holdsGateBoundToOneRun()).isTrue();
        assertThat(attributed).hasToString(
                "Tools.withProvenance[" + inner + ", FIRST_PARTY]");
    }

    @Test
    @DisplayName("CodeExecutionTool's OR sees through a ForwardingTool in the bridge")
    void aWrappedBridgedToolStillContributesItsHazard() {
        // CodeExecutionTool computes its own declarations as its floor OR-ed with the tools
        // it bridges to, reading Tool::holdsGate* off each. A decorator sitting between the
        // two is the composition that matters, because that is where the OR reads a
        // decorator's answer rather than a tool's.
        Tool blocking = CodeExecutionTool.builder(harmlessSandbox(), bridgedTools())
                .name("inner_code")
                .toolGate(ToolGates.requireApproval(invocation -> true, pagesSomebody()))
                .build();
        assertThat(blocking.holdsGateWaitingForAHuman()).isTrue();

        Tool outer = CodeExecutionTool.builder(harmlessSandbox(),
                        new SimpleToolRegistry().register(new Bare(blocking)))
                .name("outer_code")
                .allowAllTools()
                .build();

        assertThat(outer.holdsGateWaitingForAHuman())
                .as("the OR reads the decorator, so the decorator has to answer for the tool")
                .isTrue();

        // The same shape through the forwarder that only satisfies the compiler.
        Tool laundered = CodeExecutionTool.builder(harmlessSandbox(),
                        new SimpleToolRegistry().register(new FourMethods(blocking)))
                .name("laundering_code")
                .allowAllTools()
                .build();

        assertThat(laundered.holdsGateWaitingForAHuman())
                .as("measured, not assumed: the four-method forwarder hides it from the OR")
                .isFalse();
    }

    @Test
    @DisplayName("and the per-run declaration composes the same way")
    void aWrappedBridgedToolStillContributesTheBoundHazard() {
        Tool perRun = CodeExecutionTool.builder(harmlessSandbox(), bridgedTools())
                .name("inner_code")
                .toolGate(ToolGates.screeningAgainst("Onboard alice@example.com.",
                        (objective, tool, invocation) -> Optional.empty()))
                .build();
        assertThat(perRun.holdsGateBoundToOneRun()).isTrue();

        Tool outer = CodeExecutionTool.builder(harmlessSandbox(),
                        new SimpleToolRegistry().register(new Bare(perRun)))
                .name("outer_code")
                .allowAllTools()
                .build();

        assertThat(outer.holdsGateBoundToOneRun()).isTrue();
    }

    private static CodeSandbox harmlessSandbox() {
        return (code, bridge) -> SandboxExecution.ok("done");
    }

    private static ToolRegistry bridgedTools() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("write_row", "Writes a row.")
                        .schema(Map.of("type", "object", "properties", Map.of(),
                                "required", List.of()))
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> ToolResult.ok("written"))
                        .build());
    }

    private static Approver pagesSomebody() {
        return new Approver() {
            @Override
            public boolean waitsForAHuman() {
                return true;
            }

            @Override
            public ApprovalDecision review(Tool tool, ToolInvocation invocation) {
                return ApprovalDecision.deny("Nobody answered.");
            }
        };
    }
}
