package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The properties the reshape exists to guarantee. Each was violated by a hand-written
 * decorator before it, and each failure was silent or misattributed.
 */
class ForwardingToolGateTest {

    private static final Tool READER = FunctionTool.builder("search", "reads")
            .readOnly().handler(i -> ToolResult.ok("results")).build();
    private static final Tool WRITER = FunctionTool.builder("send", "writes")
            .sideEffects(SideEffects.EXTERNAL).handler(i -> ToolResult.ok("sent")).build();

    private static ToolInvocation call(String name) {
        return new ToolInvocation("c", name, Map.of());
    }

    /** Counts decisions and changes none of them. */
    private static final class Counting extends ForwardingToolGate {
        private final ToolGate delegate;
        private final AtomicInteger seen = new AtomicInteger();

        Counting(ToolGate delegate) {
            this.delegate = delegate;
        }

        @Override
        protected ToolGate delegate() {
            return delegate;
        }

        @Override
        public GateResult evaluate(Tool tool, ToolInvocation invocation) {
            seen.incrementAndGet();
            return super.evaluate(tool, invocation);
        }
    }

    @Test
    @DisplayName("a decorator keeps the wrapped policy's decisions, both ways")
    void forwardsTheDecision() {
        Counting wrapped = new Counting(ToolGates.readOnly());

        assertThat(wrapped.evaluate(READER, call("search")).allowed()).isTrue();
        assertThat(wrapped.evaluate(WRITER, call("send")).allowed()).isFalse();
        assertThat(wrapped.seen).hasValue(2);
    }

    @Test
    @DisplayName("a decorator keeps the read-only guarantee, so wrapping does not break a build")
    void forwardsTheGuarantee() {
        // Dropped, this makes a CodeExecutionTool that built before the gate was wrapped
        // stop building — observability changing what compiles.
        assertThat(new Counting(ToolGates.readOnly()).guaranteesReadOnly()).isTrue();
        assertThat(new Counting(ToolGate.ALLOW_ALL).guaranteesReadOnly()).isFalse();
    }

    @Test
    @DisplayName("a decorator keeps the waiting declaration, so wrapping cannot launder it")
    void forwardsWhetherItWaitsForAHuman() {
        // The two booleans fail in opposite directions, which is why this one needs its own
        // test rather than trusting the pattern. Dropping guaranteesReadOnly stops a build:
        // loud. Dropping this one answers false, and a gate that reaches a person is then
        // accepted by a runner that cannot host one — quiet, and open.
        ToolGate reachesAPerson = ToolGates.requireApproval(
                invocation -> true, (tool, invocation) -> ApprovalDecision.approve());

        assertThat(reachesAPerson.waitsForAHuman()).isTrue();
        assertThat(new Counting(reachesAPerson).waitsForAHuman())
                .as("wrapping a blocking gate made it look non-blocking")
                .isTrue();
        assertThat(new Counting(ToolGate.ALLOW_ALL).waitsForAHuman()).isFalse();
    }

    @Test
    @DisplayName("composition cannot launder it either, and it follows the approver")
    void waitingSurvivesCompositionAndFollowsTheApprover() {
        ToolGate reachesAPerson = ToolGates.requireApproval(
                invocation -> true, (tool, invocation) -> ApprovalDecision.approve());

        assertThat(ToolGates.allOf(ToolGates.readOnly(), reachesAPerson).waitsForAHuman()).isTrue();
        assertThat(ToolGates.allOf(ToolGates.allOf(reachesAPerson)).waitsForAHuman()).isTrue();
        assertThat(ToolGates.allOf(ToolGates.readOnly(), ToolGate.ALLOW_ALL).waitsForAHuman())
                .isFalse();

        // The declaration belongs to the approver, not to approval as a shape. DENY_ALL is
        // this framework's recommendation for an unattended run and waits for nobody, so
        // presuming otherwise would ban it from the runner built for unattended work.
        assertThat(ToolGates.requireApproval(invocation -> true, Approver.DENY_ALL)
                .waitsForAHuman()).isFalse();
        assertThat(ToolGates.requireApproval(invocation -> true, Approver.APPROVE_ALL)
                .waitsForAHuman()).isFalse();
        // A hand-written approver is presumed to reach a person until it says otherwise.
        assertThat(ToolGates.requireApproval(invocation -> true,
                (tool, invocation) -> ApprovalDecision.approve()).waitsForAHuman()).isTrue();
        assertThat(ToolGates.requireConfirmation(invocation -> true, (tool, invocation) -> true)
                .waitsForAHuman()).isTrue();
    }

}
