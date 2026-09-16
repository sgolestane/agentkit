package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.codeexec.CodeExecutionTool;
import dev.agentkit.core.codeexec.CodeSandbox;
import dev.agentkit.core.codeexec.SandboxExecution;
import dev.agentkit.core.reliability.ActionScreen;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.Approver;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.supervisor.SubagentTools;
import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.Tools;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A gate a <em>tool</em> holds does not go on a worker either (#283).
 *
 * <p>{@code ToolActivitiesImpl} checked the gate it was handed and never the registry, so a
 * {@code CodeExecutionTool} — which <em>requires</em> a gate of its own for the tools a
 * script calls — carried a blocking or per-run policy onto a shared worker unrefused. Both
 * arms are the outcomes the two existing checks exist to prevent.
 *
 * <p>The first test is the hazard rather than the control: it takes the undeclared route,
 * which is the residual the check still cannot see, so it keeps measuring the defect even
 * if the refusal were widened by accident.
 *
 * <h2>#290: the refusal was reaching a case it was not about</h2>
 *
 * <p>{@code Approver.waitsForAHuman()} presumes {@code true}, and #283 made that
 * presumption load-bearing at registration. Together with {@code Tool}'s sibling
 * declarations, which default to {@code false} and are opted into, that left one shape
 * unreachable: a {@code CodeExecutionTool} over a {@code requireApproval} whose approver is
 * a <em>lambda</em> deciding from the arguments. It never waits, and had no way to say so
 * short of implementing the interface as a class to override one method — so it was refused
 * with advice ("put approval in the workflow") that is right in general and wrong for it.
 *
 * <p>Nothing about the presumption moved. {@code Approver.withoutWaiting(...)} is a way to
 * declare the negative, and {@link #aBareLambdaApproverIsStillPresumedToWait} asks the same
 * wiring without the declaration and requires it to stay refused.
 *
 * <h2>What the mutation pass killed, and where</h2>
 *
 * <p>Each mutant below was applied to this branch, built clean, and put through
 * {@code ApprovalGateTest} in {@code agentkit-core} and this class. Every one is reported by
 * its {@code Tests run:} line, never by the build's exit status.
 *
 * <pre>
 * mutant                                                  killed by
 * withoutWaiting's waitsForAHuman() returning true        ApprovalGateTest
 *                                                         .withoutWaitingDeclaresTheNegative…
 *                                                         and …NonBlockingApproverRegisters…
 * Approver.waitsForAHuman()'s default flipped to false    ApprovalGateTest
 *                                                         .aLambdaApproverIsPresumedToWait…
 *                                                         and aBareLambdaApproverIsStill…
 * withoutWaiting discarding the review, approving         3 in ApprovalGateTest, and
 *                                                         …RegistersAndStillGates on
 *                                                         "the approver was never consulted"
 * withoutWaiting passing null for the tool                ApprovalGateTest
 *                                                         .withoutWaitingPassesTheToolThrough…
 * the argument's null check dropped                       ApprovalGateTest
 *                                                         .withoutWaitingRefusesNull…
 * a null decision allowed through                         the same test, second assertion
 * requireApproval declaring true instead of asking        ApprovalGateTest and
 *   the approver                                          …RegistersAndStillGates
 * CodeExecutionTool reporting true unconditionally        5 failures and 2 errors here
 * either refusal message reverted to its #283 wording     theRefusalSaysHowToDeclare…,
 *                                                         each half independently
 * </pre>
 *
 * <p>No survivors on this change, and none skipped.
 *
 * <h2>#296: the decorator that takes the default</h2>
 *
 * <p>The residual named above — "a hand-written {@code Tool} decorator that wraps a
 * declaring tool and takes the interface default" — is measured here on both sides rather
 * than described. {@link #aFourMethodDecoratorStillLaundersABlockingApproverOntoTheWorker}
 * writes the decorator the compiler asks for, registers it, and counts the pages: it
 * registers unrefused and somebody is reached once per attempt.
 * {@link #theSameDecoratorOverForwardingToolIsRefusedAtRegistration} is the same decorator
 * over {@code ForwardingTool}, refused, with nobody paged at all.
 *
 * <p>The first of those is the hazard and must keep passing. {@code ForwardingTool} is
 * opt-in — Java cannot force an override — so what it removes is the wrong answer being the
 * one you get by saying nothing, not the possibility of saying the wrong thing.
 * {@code ForwardingToolTest} in {@code agentkit-core} carries the mutation table for it.
 */
class DurableToolHeldGateTest {

    /** How many times the "person" was reached. One per activity attempt, before #283. */
    private static final AtomicInteger PAGES = new AtomicInteger();

    /** How many times an approver that waits for nobody was consulted (#290). */
    private static final AtomicInteger DECISIONS = new AtomicInteger();

    private static final CodeSandbox SANDBOX = (code, bridge) -> {
        // The script calls the one bridged tool; the bridge consults the tool's own gate.
        bridge.invoke("write_row", Map.of("row", "1"));
        return SandboxExecution.ok("done");
    };

    private static final ActionScreen NAMES_A_KNOWN_TARGET = (objective, tool, invocation) -> {
        Object user = invocation.arguments().get("user");
        return user != null && !objective.toLowerCase(Locale.ROOT)
                .contains(user.toString().toLowerCase(Locale.ROOT))
                ? Optional.of("That target appears nowhere in what this run was asked to do.")
                : Optional.empty();
    };

    /** An approver that says out loud that it reaches a person, and counts the pages. */
    private static final Approver PAGES_SOMEBODY = new Approver() {
        @Override
        public boolean waitsForAHuman() {
            return true;
        }

        @Override
        public ApprovalDecision review(Tool tool, ToolInvocation invocation) {
            PAGES.incrementAndGet();
            return ApprovalDecision.deny("Nobody answered.");
        }
    };

    private static ToolRegistry bridged() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("write_row", "Writes a row.")
                        .schema(Map.of("type", "object", "properties", Map.of(),
                                "required", List.of()))
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> ToolResult.ok("written"))
                        .build());
    }

    private static ToolRegistry workerRegistryHolding(Tool tool) {
        return new SimpleToolRegistry().register(tool);
    }

    private static Tool codeToolWith(ToolGate bridgeGate) {
        return CodeExecutionTool.builder(SANDBOX, bridged()).toolGate(bridgeGate).build();
    }

    /**
     * As {@link #codeToolWith}, but the script hands back what the bridged call answered.
     *
     * <p>{@link #SANDBOX} discards it, which is right for the tests that only need the
     * bridge to be reached. A test asserting that the gate is still consulted after
     * registration needs the gate's own words to come out of the tool, or "it registered"
     * would be satisfied by a declaration that had quietly dropped the approval.
     */
    private static Tool codeToolReportingTheBridgedAnswer(ToolGate bridgeGate) {
        CodeSandbox reporting = (code, bridge) ->
                SandboxExecution.ok(bridge.invoke("write_row", Map.of("row", "1")).content());
        return CodeExecutionTool.builder(reporting, bridged()).toolGate(bridgeGate).build();
    }

    // --- a delegating supervisor, which reached this check through one indirection ------

    /**
     * A {@code delegate} whose roster holds a blocking subagent is refused here (#313).
     *
     * <p>The defect this closes is the one the whole class is about, arriving by a route the
     * class could not see: {@code SubagentTools.delegateTool} answered
     * {@code holdsGateWaitingForAHuman()} {@code false} for every roster, so a supervisor
     * whose subagent parks on an approver registered as holding nothing and the approver was
     * paged once per activity retry. The subagent declares it now and {@code delegate} ORs
     * the roster.
     *
     * <p>Measured against the real worker constructor rather than against the boolean,
     * because the boolean is only worth having if this refusal happens.
     */
    @Test
    void aDelegateWhoseRosterHoldsABlockingSubagentIsRefused() {
        SubagentRoster roster = SubagentRoster.of(
                subagent("researcher"),
                subagent("publisher").holdingAGateWaitingForAHuman());

        assertThatThrownBy(() -> new ToolActivitiesImpl(
                        workerRegistryHolding(SubagentTools.delegateTool(roster))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'delegate'")
                .hasMessageContaining("block waiting for a person");
    }

    /** The sibling hazard, by the same route. */
    @Test
    void aDelegateWhoseRosterHoldsAPerRunSubagentIsRefused() {
        SubagentRoster roster = SubagentRoster.of(
                subagent("screener").holdingAGateBoundToOneRun());

        assertThatThrownBy(() -> new ToolActivitiesImpl(
                        workerRegistryHolding(SubagentTools.delegateTool(roster))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'delegate'")
                .hasMessageContaining("built for one run");
    }

    /**
     * An ordinary roster still registers, or the refusal would ban model-driven
     * decomposition from the runner built for unattended work.
     */
    @Test
    void anOrdinaryDelegatingSupervisorStillRegisters() {
        SubagentRoster roster = SubagentRoster.of(subagent("researcher"), subagent("drafter"));

        assertThatCode(() -> new ToolActivitiesImpl(
                        workerRegistryHolding(SubagentTools.delegateTool(roster)),
                        ToolGates.readOnly()))
                .doesNotThrowAnyException();
    }

    /**
     * The residual, stated as a test rather than only as a comment: the check is a snapshot.
     *
     * <p>{@code delegate} renders the OR live, so it is right for every caller that asks
     * after the roster grew. This caller asks once, in a constructor, and a roster a model
     * grows afterwards is not re-examined — the same residual {@code ToolActivitiesImpl}
     * already states for a mutable registry. It is pinned here so that a future change
     * claiming to close it has something to break, and so the honest limit is executable
     * rather than a sentence.
     */
    @Test
    void aRosterGrownAfterRegistrationIsNotCaught() {
        SubagentRoster roster = SubagentRoster.of(subagent("researcher"));
        Tool delegate = SubagentTools.delegateTool(roster);

        assertThatCode(() -> new ToolActivitiesImpl(workerRegistryHolding(delegate),
                        ToolGates.readOnly()))
                .doesNotThrowAnyException();

        roster.add(subagent("publisher").holdingAGateWaitingForAHuman());

        // The tool tells the truth now, to anybody who asks after the growth...
        assertThat(delegate.holdsGateWaitingForAHuman()).isTrue();
        // ...and a worker constructed before it is not told again, because the check is a
        // snapshot taken at the only moment there is anything left to refuse.
        assertThatCode(() -> new ToolActivitiesImpl(workerRegistryHolding(delegate),
                        ToolGates.readOnly()))
                .as("a worker built now does refuse it, which is what makes the sentence"
                        + " above about timing rather than about detection")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A subagent with no LLM anywhere near it: what it holds is a declaration, not a gate. */
    private static Subagent subagent(String name) {
        return Subagent.handling(name, name + " does things",
                goal -> AgentResult.completed("done", 1));
    }

    private static ToolInvocation runCode() {
        return new ToolInvocation("c1", CodeExecutionTool.DEFAULT_NAME, Map.of("code", "x"));
    }

    @Test
    void anUndeclaredToolHeldApproverStillReachesTheWorkerAndIsAskedOnEveryCall() {
        // The residual, measured through the door the declaration cannot see: a hand-written
        // Tool that runs an approver itself and never overrides holdsGateWaitingForAHuman().
        // This is the hazard, not the control -- it is what "best-effort" costs, and it must
        // keep passing.
        PAGES.set(0);
        Tool undeclared = new Tool() {
            @Override
            public String name() {
                return "publish";
            }

            @Override
            public String description() {
                return "Publishes.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                PAGES_SOMEBODY.review(this, invocation);
                return ToolResult.error("refused");
            }
        };

        ToolActivitiesImpl worker = new ToolActivitiesImpl(workerRegistryHolding(undeclared));
        worker.executeTool(new ToolInvocation("p1", "publish", Map.of()));
        worker.executeTool(new ToolInvocation("p2", "publish", Map.of()));

        assertThat(PAGES.get())
                .as("an undeclared tool-held approver is asked once per call, unrefused")
                .isEqualTo(2);
    }

    @Test
    void aCodeExecutionToolHoldingABlockingApproverIsRefusedAtRegistration() {
        PAGES.set(0);
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(
                        codeToolWith(ToolGates.requireApproval(
                                invocation -> true, PAGES_SOMEBODY)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run_code")
                .hasMessageContaining("block waiting for a person");
        assertThat(PAGES.get())
                .as("refused where it is wired, so nobody was paged at all")
                .isZero();
    }

    @Test
    void aCodeExecutionToolHoldingOneRunsObjectiveIsRefusedAtRegistration() {
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(
                        codeToolWith(ToolGates.screeningAgainst("Onboard alice@example.com.",
                                NAMES_A_KNOWN_TARGET)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run_code")
                .hasMessageContaining("built for one run");
    }

    @Test
    void aBridgeFloorWhoseTightenedPolicyIsUnshareableIsRefusedToo() {
        // The one that would otherwise engage only after a script had read somebody else's
        // words: the worker accepts it, serves a hundred runs, and starts paging the first
        // time a bridged tool returns a web page.
        Tool tool = CodeExecutionTool.builder(SANDBOX, bridged())
                .toolFloor(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL,
                        ToolGates.requireApproval(invocation -> true, PAGES_SOMEBODY)))
                .build();

        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(tool)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");
    }

    @Test
    void theUngatedRegistrationChecksTheRegistryToo() {
        // Choosing no gate for the worker says nothing about the gate a tool holds below it,
        // so the single-argument constructor must not be a way in.
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(
                        codeToolWith(ToolGates.requireApproval(
                                invocation -> true, PAGES_SOMEBODY)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");
    }

    @Test
    void aShippedDecoratorDoesNotLaunderItOntoTheWorker() {
        // Tools.withSideEffects is the escape hatch CodeExecutionTool's own javadoc points
        // at, so it is the likeliest wrapper to find around one of these.
        Tool wrapped = Tools.withSideEffects(
                codeToolWith(ToolGates.requireApproval(invocation -> true, PAGES_SOMEBODY)),
                SideEffects.NONE);

        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(wrapped)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");

        Tool attributed = Tools.withProvenance(
                codeToolWith(ToolGates.screeningAgainst("Onboard alice@example.com.",
                        NAMES_A_KNOWN_TARGET)),
                Provenance.FIRST_PARTY);

        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(attributed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("built for one run");
    }

    /**
     * The decorator the compiler asks for: the four abstract members of {@code Tool} and
     * nothing else. This is the shape {@code ToolActivitiesImpl} names among what its check
     * cannot catch, and what {@code Tools}' javadoc used to call hand-writing a forwarder.
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

    /** The same decorator, decorating the same nothing, over {@link ForwardingTool}. */
    private static final class OverTheBase extends ForwardingTool {
        private final Tool delegate;

        OverTheBase(Tool delegate) {
            this.delegate = delegate;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }
    }

    @Test
    void aFourMethodDecoratorStillLaundersABlockingApproverOntoTheWorker() {
        // The hazard, measured end to end on the runner rather than argued from the
        // declaration: this must keep passing, because ForwardingTool is opt-in and Java
        // cannot force an override. What it costs is exactly what #283 was about -- the
        // approver is reached once per activity attempt, and a Temporal activity that
        // blocks times out and is retried, so "per attempt" is "per page".
        PAGES.set(0);
        Tool blocking = codeToolWith(ToolGates.requireApproval(
                invocation -> true, PAGES_SOMEBODY));
        assertThat(blocking.holdsGateWaitingForAHuman())
                .as("the tool itself declares the hazard honestly")
                .isTrue();

        Tool laundered = new FourMethods(blocking);
        assertThat(laundered.holdsGateWaitingForAHuman())
                .as("and the decorator reports it as holding nothing")
                .isFalse();

        ToolActivitiesImpl worker = new ToolActivitiesImpl(workerRegistryHolding(laundered));
        worker.executeTool(runCode());
        worker.executeTool(runCode());

        assertThat(PAGES.get())
                .as("registered unrefused, and somebody was paged once per attempt")
                .isEqualTo(2);
    }

    @Test
    void theSameDecoratorOverForwardingToolIsRefusedAtRegistration() {
        // The control, and the whole of #296: the decorator that decorates nothing now
        // says nothing by inheriting the tool's answer rather than by taking a default.
        PAGES.set(0);
        Tool wrapped = new OverTheBase(codeToolWith(ToolGates.requireApproval(
                invocation -> true, PAGES_SOMEBODY)));

        assertThat(wrapped.holdsGateWaitingForAHuman()).isTrue();
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(wrapped)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run_code")
                .hasMessageContaining("block waiting for a person");
        assertThat(PAGES.get())
                .as("refused where it is wired, so nobody was paged at all")
                .isZero();
    }

    @Test
    void theBaseAlsoCarriesThePerRunDeclarationThroughADecorator() {
        Tool perRun = codeToolWith(ToolGates.screeningAgainst("Onboard alice@example.com.",
                NAMES_A_KNOWN_TARGET));

        assertThat(new FourMethods(perRun).holdsGateBoundToOneRun())
                .as("the four-method decorator hides this one too")
                .isFalse();
        assertThatCode(() -> new ToolActivitiesImpl(
                        workerRegistryHolding(new FourMethods(perRun))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new ToolActivitiesImpl(
                        workerRegistryHolding(new OverTheBase(perRun))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("built for one run");
    }

    @Test
    void aDecoratorOverTheBaseInsideACodeToolsBridgeIsSeenByTheOr() {
        // CodeExecutionTool ORs its floor with the tools it bridges to, so a decorator in
        // that chain is read by the OR rather than by the worker directly. The composition
        // has to survive one more layer than the registration check sees.
        Tool inner = codeToolWith(ToolGates.requireApproval(invocation -> true, PAGES_SOMEBODY));
        Tool outer = CodeExecutionTool.builder(SANDBOX,
                        new SimpleToolRegistry().register(new OverTheBase(inner)))
                .name("outer_code")
                .allowAllTools()
                .build();

        assertThat(outer.holdsGateWaitingForAHuman()).isTrue();
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(outer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");
    }

    @Test
    void nestingOneCodeToolInsideAnotherDoesNotHideTheInnerGate() {
        Tool inner = codeToolWith(ToolGates.requireApproval(invocation -> true, PAGES_SOMEBODY));
        Tool outer = CodeExecutionTool.builder(SANDBOX,
                        new SimpleToolRegistry().register(inner))
                .name("outer_code")
                .allowAllTools()
                .build();

        assertThat(outer.holdsGateWaitingForAHuman())
                .as("the outer tool reports what the tool it bridges to holds")
                .isTrue();
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(outer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");
    }

    @Test
    void anOrdinaryCodeExecutionToolIsStillRegisteredAndStillRuns() {
        // The refusal must not cost the durable path the tools it is meant to run: a bridge
        // gate that decides from declared side effects is true of every run and stays wired.
        Tool tool = codeToolWith(ToolGates.denyTools(java.util.Set.of("drop_table")));
        ToolActivitiesImpl worker = new ToolActivitiesImpl(workerRegistryHolding(tool));

        ToolOutcome outcome = worker.executeTool(runCode());

        assertThat(outcome.result().isError()).isFalse();
        assertThat(outcome.result().content()).contains("done");
    }

    @Test
    void aPlainWorkerGateAndAToolHeldOneAreCheckedIndependently() {
        // A worker gate that is fine does not excuse the tool, and a tool that is fine does
        // not excuse the worker gate. Both directions, so neither check can absorb the other.
        assertThatThrownBy(() -> new ToolActivitiesImpl(
                        workerRegistryHolding(codeToolWith(ToolGates.requireApproval(
                                invocation -> true, PAGES_SOMEBODY))),
                        ToolGates.readOnly()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run_code");

        assertThatCode(() -> new ToolActivitiesImpl(
                        workerRegistryHolding(codeToolWith(ToolGates.denyTools(java.util.Set.of("drop_table")))),
                        ToolGates.readOnly()))
                .doesNotThrowAnyException();
    }

    @Test
    void aGateThatParksIsAcceptedInsideAToolAsItIsOnTheWorker() {
        // parkForApproval reaches a decision at once, so waitsForAHuman() is false and the
        // declaration must not over-report. Banning it would ban the only approval shape
        // built for a runner that can wait.
        Tool tool = codeToolWith(ToolGates.parkForApproval(invocation -> true,
                dev.agentkit.core.reliability.ApprovalNeeded.because("needs a person")));

        assertThat(tool.holdsGateWaitingForAHuman()).isFalse();
        assertThatCode(() -> new ToolActivitiesImpl(workerRegistryHolding(tool)))
                .doesNotThrowAnyException();
    }

    @Test
    void aToolThatDeclaresNothingIsNotRefused() {
        // The default must stay false for the population that holds no gate at all, or the
        // check would refuse every ordinary worker.
        assertThatCode(() -> new ToolActivitiesImpl(bridged(), ToolGates.readOnly()))
                .doesNotThrowAnyException();
    }

    @Test
    void aGateDenialInsideTheToolIsStillAnErrorResultRatherThanAThrow() {
        // The accepted path still gates: denyTools on the bridge refuses the bridged call,
        // and that comes back as a script error rather than a failed activity.
        Tool tool = codeToolWith(ToolGates.denyTools(java.util.Set.of("write_row")));
        ToolActivitiesImpl worker = new ToolActivitiesImpl(workerRegistryHolding(tool));

        assertThat(worker.executeTool(runCode()).result().content()).isNotBlank();
    }

    @Test
    void theDeclarationsAreFalseByDefaultOnAnOrdinaryTool() {
        Tool plain = bridged().tools().get(0);

        assertThat(plain.holdsGateWaitingForAHuman()).isFalse();
        assertThat(plain.holdsGateBoundToOneRun()).isFalse();
    }

    @Test
    void aGateResultIsUnchangedForToolsThatHoldNoGate() {
        // Guards the loop against refusing on the wrong condition: a worker gate that denies
        // is still a denial at call time, not a registration failure.
        ToolActivitiesImpl worker = new ToolActivitiesImpl(bridged(),
                (tool, invocation) -> GateResult.deny("no"));

        assertThat(worker.executeTool(new ToolInvocation("w1", "write_row", Map.of()))
                .result().isError()).isTrue();
    }

    @Test
    void aCodeExecutionToolOverANonBlockingApproverRegistersAndStillGates() {
        // #290, and the test that could not be written before it: an approver that decides
        // from the arguments is refused durably for a reason that is not true of it, because
        // Approver.waitsForAHuman() presumes true and a lambda has no way to say otherwise.
        // Approver.withoutWaiting is that way. Nothing about the presumption moves -- see
        // aBareLambdaApproverIsStillPresumedToWait below, which is the same wiring without
        // the declaration and is still refused.
        //
        // Registration not throwing is the smaller half. The gate must still be consulted
        // inside the tool afterwards, or "it registers" would be satisfied by a declaration
        // that had quietly disabled the approval it declared.
        PAGES.set(0);
        DECISIONS.set(0);
        Tool tool = codeToolReportingTheBridgedAnswer(ToolGates.requireApproval(
                invocation -> true,
                Approver.withoutWaiting((gated, invocation) -> {
                    DECISIONS.incrementAndGet();
                    return ApprovalDecision.deny("Over the unattended limit.");
                })));

        assertThat(tool.holdsGateWaitingForAHuman())
                .as("the tool forwards the approver's own answer, so a declared"
                        + " non-blocking approver must not be reported as blocking")
                .isFalse();

        ToolActivitiesImpl worker = new ToolActivitiesImpl(workerRegistryHolding(tool));
        ToolOutcome outcome = worker.executeTool(runCode());

        assertThat(DECISIONS.get())
                .as("the approver was never consulted, so the tool registered by having"
                        + " lost the gate rather than by declaring it honestly")
                .isEqualTo(1);
        assertThat(outcome.result().content())
                .as("the bridged call was refused with the approver's own reason")
                .contains("Over the unattended limit.");
        assertThat(PAGES.get()).isZero();
    }

    @Test
    void aBareLambdaApproverIsStillPresumedToWait() {
        // The presumption #290 must not weaken, asked of the exact wiring #290 is about: the
        // same tool over the same kind of lambda, undeclared. Refusing something that might
        // block is the safe side; withoutWaiting adds a way to say "this one does not", not
        // a change to what silence means.
        Tool tool = codeToolWith(ToolGates.requireApproval(invocation -> true,
                (gated, invocation) -> ApprovalDecision.approve()));

        assertThat(tool.holdsGateWaitingForAHuman()).isTrue();
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(tool)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run_code")
                .hasMessageContaining("block waiting for a person");
    }

    @Test
    void theRefusalSaysHowToDeclareANonBlockingApprover() {
        // Where someone hitting this actually reads. Before #290 the message named only the
        // remedy that is right in general -- move approval into the workflow -- which is
        // wrong for the deployment whose approver never waited in the first place, and it
        // named no way to say so.
        assertThatThrownBy(() -> new ToolActivitiesImpl(workerRegistryHolding(
                        codeToolWith(ToolGates.requireApproval(
                                invocation -> true, PAGES_SOMEBODY)))))
                .hasMessageContaining("Approver.withoutWaiting");

        assertThatThrownBy(() -> new ToolActivitiesImpl(bridged(),
                        ToolGates.requireApproval(invocation -> true, PAGES_SOMEBODY)))
                .as("the worker's own gate is the other door into the same dead end")
                .hasMessageContaining("Approver.withoutWaiting");
    }
}
