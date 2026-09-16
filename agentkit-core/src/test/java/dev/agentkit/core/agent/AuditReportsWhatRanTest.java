package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The observer stream has to say what the gate settled on, not what the model asked for
 * (#131).
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Agent.executeTools} fired both observer callbacks with the <strong>proposed</strong>
 * invocation while {@code runTool} executed {@code gate.effectiveFor(invocation)}. An editing
 * gate is a supported and documented feature — {@code ApprovalDecision.approveWithArguments}
 * and every {@code GateResult.allowWith} in the repository produce one — so the record and
 * the execution disagreed. Measured:
 *
 * <pre>
 * observer/audit saw = [/etc/shadow]         &lt;- the model's proposal
 * tool actually ran  = [/tmp/harmless.txt]   &lt;- the gate's narrowing
 * </pre>
 *
 * <p>In the itops example the observer stream <em>is</em> the compliance trail, so a reviewer
 * reconstructing a run got the model's proposal — precisely the thing a narrowing gate exists
 * to overrule.
 *
 * <h2>Why the two callbacks answer differently, on purpose</h2>
 *
 * <p>{@code onToolProposed} keeps reporting the proposal, because at that moment nothing has
 * gated it and there is no effective call to report. It also has to fire for calls a gate goes
 * on to deny or park, which is the "wiring looked right and did nothing" trajectory the loop's
 * own comment guards against. {@code onToolResult} reports what the gate settled on, because
 * by then that is the truth and it is where an audit row gets written.
 *
 * <h2>The four paths where nothing ran</h2>
 *
 * <p>Half of this file is those paths, because the first version of the change got two of
 * them wrong while claiming a single rule. A denied call and a gate that threw report the
 * proposal, having no other call to report. A <em>parked</em> call reports the replacement,
 * because that is what a reviewer will be shown and what runs on approval (#104) — and a
 * <em>tool</em> that threw reports what it was handed, because it was handed something and
 * may have acted on it before throwing.
 */
class AuditReportsWhatRanTest {

    /** Records what it was actually handed. */
    private record Reader(List<String> received) implements Tool {
        public String name() {
            return "read_file";
        }

        public String description() {
            return "reads a file";
        }

        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        public ToolResult execute(ToolInvocation invocation) {
            received.add(String.valueOf(invocation.arguments().get("path")));
            return ToolResult.ok("contents");
        }
    }

    /** Records what it was handed and then throws, like a tool failing part-way. */
    private record Thrower(List<String> received) implements Tool {
        public String name() {
            return "read_file";
        }

        public String description() {
            return "reads a file";
        }

        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        public ToolResult execute(ToolInvocation invocation) {
            received.add(String.valueOf(invocation.arguments().get("path")));
            throw new IllegalStateException("the remote end hung up");
        }
    }

    /** The documented, supported feature: a gate that narrows the arguments. */
    private static ToolGate narrowingTo(String path) {
        return (tool, invocation) -> GateResult.allowWith(
                new ToolInvocation(invocation.id(), invocation.name(), Map.of("path", path)));
    }

    /** A gate that narrows and then parks — the shape {@code allOf(narrow, park)} produces. */
    private static ToolGate narrowingThenParkingAt(String path) {
        return (tool, invocation) -> GateResult.needsAPerson(
                ApprovalNeeded.because("a person must decide"),
                new ToolInvocation(invocation.id(), invocation.name(), Map.of("path", path)));
    }

    /** Collects the pair the observer is given, as {@code proposed -> effective}. */
    private static final class Pairs implements AgentObserver {
        private final List<String> seen = new ArrayList<>();

        @Override
        public void onToolResult(AgentRun run, int step,
                                 ToolInvocation proposed, ToolInvocation effective,
                                 ToolResult result,
                                 dev.agentkit.core.tool.Disposition disposition) {
            seen.add(proposed.arguments().get("path") + " -> " + effective.arguments().get("path"));
        }
    }

    private static Agent agentWith(ToolGate gate, AgentObserver observer, Tool tool) {
        return Agent.builder(
                        new FakeLlmClient(
                                FakeLlmClient.toolUse("c1", "read_file",
                                        Map.of("path", "/etc/shadow")),
                                FakeLlmClient.text("done")),
                        new SimpleToolRegistry().register(tool),
                        AgentConfig.builder("m").maxSteps(3).build())
                .toolGate(gate)
                .observer(observer)
                .build();
    }

    private static Agent agentWith(ToolGate gate, AgentObserver observer, List<String> ran) {
        return agentWith(gate, observer, new Reader(ran));
    }

    @Test
    void theRecordShowsWhatTheToolReceivedAndWhatTheModelAskedFor() {
        // The whole of #131 in one assertion: the pair disagrees, and both halves are
        // reported. Before this, an audit row could only say "/etc/shadow" — the one thing
        // that did not happen.
        List<String> ran = new ArrayList<>();
        Pairs pairs = new Pairs();

        agentWith(narrowingTo("/tmp/harmless.txt"), pairs, ran).run(Goal.of("read something"));

        assertThat(ran).containsExactly("/tmp/harmless.txt");
        assertThat(pairs.seen)
                .as("the record did not show that a gate narrowed the call")
                .containsExactly("/etc/shadow -> /tmp/harmless.txt");
    }

    @Test
    void anUngatedCallReportsTheSameInvocationTwice() {
        // The overwhelmingly common case, and the reason the pair is not confusing to read:
        // with no gate editing anything, proposed and effective are the same call.
        List<String> ran = new ArrayList<>();
        Pairs pairs = new Pairs();

        agentWith((tool, invocation) -> GateResult.allow(), pairs, ran)
                .run(Goal.of("read something"));

        assertThat(ran).containsExactly("/etc/shadow");
        assertThat(pairs.seen).containsExactly("/etc/shadow -> /etc/shadow");
    }

    @Test
    void theProposalIsStillWhatIsReportedBeforeAnythingHasGatedIt() {
        // onToolProposed deliberately did NOT move. At that point there is no effective
        // call — the gate has not run — and the callback also has to fire for calls a gate
        // goes on to deny or park.
        List<String> ran = new ArrayList<>();
        List<String> atProposal = new ArrayList<>();

        agentWith(narrowingTo("/tmp/harmless.txt"), new AgentObserver() {
            @Override
            public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
                atProposal.add(String.valueOf(invocation.arguments().get("path")));
            }
        }, ran).run(Goal.of("read something"));

        assertThat(atProposal)
                .as("onToolProposed reported something the model had not asked for")
                .containsExactly("/etc/shadow");
    }

    @Test
    void aDeniedCallReportsTheProposalBecauseThereIsNoOtherCall() {
        // Not "because nothing ran", which was this test's first reason and is the reason
        // the parked case below disproves. A plain deny reports the proposal because
        // GateResult's constructor forbids a replacement on any not-allowed result except a
        // park — so on this path there is literally no second invocation to report.
        List<String> ran = new ArrayList<>();
        Pairs pairs = new Pairs();

        agentWith((tool, invocation) -> GateResult.deny("no"), pairs, ran)
                .run(Goal.of("read something"));

        assertThat(ran).as("a denied call must not run").isEmpty();
        assertThat(pairs.seen).containsExactly("/etc/shadow -> /etc/shadow");
    }

    @Test
    void aParkedCallReportsTheCallAReviewerWillBeShown() {
        // The one path where "effective" is a call no tool has received, and the reason the
        // contract is worded as "what the gate settled on" rather than "what the tool
        // received". Measured against the first version of this change, which asserted the
        // latter in its own javadoc:
        //
        //   tool actually ran = []
        //   effective         = [/tmp/harmless.txt]
        //
        // Reporting the proposal instead would be the worse repair. #104 is why: a gate may
        // narrow and then park, GateResult.needsAPerson(why, replacement) exists precisely
        // so the narrowing survives the park, and what a reviewer is shown has to be the
        // call that runs when they approve. An audit row saying "/etc/shadow" for a call
        // whose approval request says "/tmp/harmless.txt" describes a different decision
        // from the one a person is about to make.
        List<String> ran = new ArrayList<>();
        Pairs pairs = new Pairs();

        AgentResult result = agentWith(narrowingThenParkingAt("/tmp/harmless.txt"), pairs, ran)
                .run(Goal.of("read something"));

        assertThat(ran).as("a parked call must not run").isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(pairs.seen)
                .as("the record showed the un-narrowed proposal, not the call the reviewer"
                        + " was shown and will approve")
                .containsExactly("/etc/shadow -> /tmp/harmless.txt");
    }

    @Test
    void aToolThatThrowsReportsWhatItWasHanded() {
        // #131's own defect, surviving inside the change that closes it. runTool's catch
        // covers a throwing gate AND a throwing tool — its own comment says so — but the
        // comment written to justify reporting the proposal there addressed only the gate.
        // Measured before the fix, with a narrowing gate and a tool that throws:
        //
        //   tool received = [/tmp/harmless.txt]
        //   observer told = [/etc/shadow]
        //
        // This is the worst place to be wrong about it. A tool that threw part-way may
        // already have landed a side effect, so the row naming the arguments is the only
        // record of what that side effect was against — and it named arguments the tool
        // never received.
        List<String> handed = new ArrayList<>();
        Pairs pairs = new Pairs();

        agentWith(narrowingTo("/tmp/harmless.txt"), pairs, new Thrower(handed))
                .run(Goal.of("read something"));

        assertThat(handed)
                .as("the tool did not run, so this test measured nothing")
                .containsExactly("/tmp/harmless.txt");
        assertThat(pairs.seen)
                .as("a tool threw after being handed the gate's narrowing, and the record"
                        + " named the arguments it was never given")
                .containsExactly("/etc/shadow -> /tmp/harmless.txt");
    }

    @Test
    void aGateThatThrowsReportsTheProposal() {
        // The other half of the same catch, and the half the old comment was right about.
        // The gate never returned, so it never settled the call: the proposal is the only
        // invocation that exists.
        List<String> ran = new ArrayList<>();
        Pairs pairs = new Pairs();

        agentWith((tool, invocation) -> {
            throw new IllegalStateException("the policy service is down");
        }, pairs, ran).run(Goal.of("read something"));

        assertThat(ran).isEmpty();
        assertThat(pairs.seen).containsExactly("/etc/shadow -> /etc/shadow");
    }
}
