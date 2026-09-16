package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every observer callback says which run it belongs to (#311).
 *
 * <h2>The defect, measured</h2>
 *
 * <p>One {@code AgentObserver} has to witness a supervisor <em>and</em> the agents its
 * subagents build, because "what did this run touch" must have one answer. Before this,
 * nothing on any callback said which of them a row came from, and {@code step} is an
 * {@code int} that restarts at 1 in every agent. From {@code SelfWiringAgentTest}, the trace
 * a shared observer collected for a run that declares one subagent and delegates to it:
 *
 * <pre>
 * declare_plan     &lt;- the supervisor
 * lookup           &lt;- inside the researcher
 * delegate         &lt;- the supervisor, completing
 * verify_claim     &lt;- the supervisor
 * </pre>
 *
 * <p>The child's call arrives <em>before</em> the {@code delegate} that caused it, because
 * delegation is synchronous and the child finishes first. That ordering is not fixable by
 * ordering, which is why the repair is attribution. The row it matters for is the one a
 * compliance trail is opened to read: a call the policy stopped inside a child, which named
 * no actor at all.
 *
 * <h2>What is asserted here</h2>
 *
 * <p>Each test pins a property that a plausible cheaper implementation breaks, and the
 * headline one — {@link #attributesAStoppedCallInsideAChildToThatChild()} — is written so
 * the step numbers <em>collide</em>: the child's stopped call is its step 1 and the
 * supervisor's own step 1 is a different tool, so nothing but the identity separates them.
 */
class ObserverKnowsWhoseStepItIsTest {

    /** One row of what an observer was told, with whose row it was. */
    private record Row(AgentRun run, String callback, int step, String tool,
                       Disposition disposition) {
    }

    /** The shared witness: one instance, wired into the supervisor and into every child. */
    private static final class Shared implements AgentObserver {
        private final List<Row> rows = new ArrayList<>();

        @Override
        public synchronized void onStart(AgentRun run, Goal goal) {
            rows.add(new Row(run, "onStart", 0, null, null));
        }

        @Override
        public synchronized void onModelResponse(AgentRun run, int step,
                                                 dev.agentkit.core.llm.LlmResponse response) {
            rows.add(new Row(run, "onModelResponse", step, null, null));
        }

        @Override
        public synchronized void onToolProposed(AgentRun run, int step,
                                                ToolInvocation invocation) {
            rows.add(new Row(run, "onToolProposed", step, invocation.name(), null));
        }

        @Override
        public synchronized void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                                              ToolInvocation effective, ToolResult result,
                                              Disposition disposition) {
            rows.add(new Row(run, "onToolResult", step, effective.name(), disposition));
        }

        @Override
        public synchronized void onFinish(AgentRun run, AgentResult result) {
            rows.add(new Row(run, "onFinish", 0, null, null));
        }

        synchronized List<Row> rows() {
            return List.copyOf(rows);
        }

        synchronized List<Row> toolResults() {
            return rows.stream().filter(row -> row.callback().equals("onToolResult")).toList();
        }
    }

    private static ToolRegistry registryOf(FunctionTool... tools) {
        return new SimpleToolRegistry(List.of(tools));
    }

    private static FunctionTool tool(String name) {
        return FunctionTool.builder(name, "does " + name)
                .schema(Map.of("type", "object"))
                .handler(invocation -> ToolResult.ok(name + " done"))
                .build();
    }

    private static Agent agent(FakeLlmClient llm, Shared observer, String name,
                               ToolGate gate, FunctionTool... tools) {
        Agent.Builder builder = Agent.builder(llm, registryOf(tools),
                        AgentConfig.builder("m").maxSteps(6).build())
                .toolGate(gate)
                .observer(observer);
        if (name != null) {
            builder.name(name);
        }
        return builder.build();
    }

    private static final ToolGate ALLOW = (tool, invocation) -> GateResult.allow();

    /** The framework's own denial, wired at coding time and shared by parent and child. */
    private static ToolGate denying(String toolName) {
        return (tool, invocation) -> toolName.equals(invocation.name())
                ? GateResult.deny("policy says no")
                : GateResult.allow();
    }

    // --- the headline ------------------------------------------------------------

    /**
     * A call the policy stopped inside a subagent is attributed to that subagent.
     *
     * <p>The case #311 names: "a reviewer asking who tried to run {@code shred} gets a row
     * with step 2 and no owner". Here the collision is sharper still — the child's denied
     * call is its own step 1, and the supervisor's step 1 is the {@code delegate}, so the
     * step number is not even a tiebreak. The gate is the same instance for both, which is
     * the point: one policy, wired at coding time, and a trail that says who it stopped.
     */
    @Test
    @DisplayName("a call the policy stopped inside a subagent is attributed to that subagent")
    void attributesAStoppedCallInsideAChildToThatChild() {
        Shared observed = new Shared();
        ToolGate policy = denying("shred");
        FakeLlmClient llm = new FakeLlmClient(
                // the supervisor's step 1
                FakeLlmClient.toolUse("s1", "delegate",
                        Map.of("subagent", "worker", "goal", "tidy up")),
                // the worker's own step 1, on the same shared observer
                FakeLlmClient.toolUse("w1", "shred", Map.of("id", "order-1")),
                FakeLlmClient.text("I could not erase that."),
                // back in the supervisor
                FakeLlmClient.text("Done what I could."));

        Agent worker = agent(llm, observed, null, policy, tool("shred"));
        Subagent roster = Subagent.of("worker", "tidies up", () -> worker);
        FunctionTool delegate = FunctionTool.builder("delegate", "hand work to a subagent")
                .schema(Map.of("type", "object"))
                .handler(invocation -> ToolResult.ok(
                        roster.handle(Goal.of(invocation.stringArgument("goal"))).output()))
                .build();

        agent(llm, observed, "supervisor", policy, delegate).run(Goal.of("tidy up"));

        Row shred = observed.toolResults().stream()
                .filter(row -> "shred".equals(row.tool())).findFirst().orElseThrow();
        assertThat(shred.disposition()).isEqualTo(Disposition.REFUSED);
        assertThat(shred.disposition().reachedTool()).isFalse();

        // The whole of it. Before #311 this row named nobody.
        assertThat(shred.run().name()).isEqualTo("worker");

        // And the step number could not have said so: the supervisor's own step 1 is a
        // different call on the same observer, under a different identity.
        Row delegated = observed.toolResults().stream()
                .filter(row -> "delegate".equals(row.tool())).findFirst().orElseThrow();
        assertThat(shred.step()).isEqualTo(1);
        assertThat(delegated.step()).isEqualTo(1);
        assertThat(delegated.run().name()).isEqualTo("supervisor");
        assertThat(shred.run()).isNotEqualTo(delegated.run());

        // The child's row arrives first, which is what made attribution the repair rather
        // than ordering: the delegation is synchronous, so the child finishes inside it.
        assertThat(observed.toolResults()).extracting(Row::tool)
                .containsExactly("shred", "delegate");
    }

    // --- the properties the headline rests on ------------------------------------

    @Test
    @DisplayName("one run is one identity, from onStart through to onFinish")
    void oneRunIsOneIdentityAcrossEveryCallback() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("c1", "act", Map.of()),
                FakeLlmClient.text("done"));

        agent(llm, observed, "solo", ALLOW, tool("act")).run(Goal.of("act"));

        // Every callback, not just the ones with a step: onStart and onFinish are what
        // bracket a run's rows in a shared trace, so a fresh identity at either end would
        // leave a reader unable to say where one run's rows stop.
        assertThat(observed.rows()).extracting(Row::callback)
                .containsExactly("onStart", "onModelResponse", "onToolProposed", "onToolResult",
                        "onModelResponse", "onFinish");
        assertThat(observed.rows()).extracting(Row::run)
                .containsOnly(observed.rows().getFirst().run());
        assertThat(observed.rows().getFirst().run().name()).isEqualTo("solo");
    }

    @Test
    @DisplayName("two runs of one agent are two identities under one name")
    void twoRunsOfOneAgentAreTwoIdentities() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.text("first"), FakeLlmClient.text("second"));

        Agent agent = agent(llm, observed, "solo", ALLOW);
        agent.run(Goal.of("one"));
        agent.run(Goal.of("two"));

        List<Row> starts = observed.rows().stream()
                .filter(row -> row.callback().equals("onStart")).toList();
        assertThat(starts).hasSize(2);
        assertThat(starts.get(0).run().name()).isEqualTo(starts.get(1).run().name());
        // Caching the identity on the Agent would merge two runs' rows in every trace that
        // reads them, which is the defect this parameter exists to close.
        assertThat(starts.get(0).run()).isNotEqualTo(starts.get(1).run());
    }

    @Test
    @DisplayName("a subagent runs under its roster name without anybody wiring it")
    void aSubagentIsNamedByItsRoster() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));

        // Built with no name of its own, exactly as a deployment's subagent factory builds
        // one: the Supplier has never heard of the roster. Subagent supplies the name.
        Subagent.of("researcher", "looks things up",
                        () -> agent(llm, observed, null, ALLOW))
                .handle(Goal.of("look it up"));

        assertThat(observed.rows().getFirst().run().name()).isEqualTo("researcher");
    }

    @Test
    @DisplayName("an agent nobody named still gets its own identity, under a legible name")
    void anUnnamedAgentIsStillAttributed() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.text("first"), FakeLlmClient.text("second"));

        Agent agent = agent(llm, observed, null, ALLOW);
        agent.run(Goal.of("one"));
        agent.run(Goal.of("two"));

        List<Row> starts = observed.rows().stream()
                .filter(row -> row.callback().equals("onStart")).toList();
        assertThat(starts).extracting(row -> row.run().name())
                .containsExactly(AgentRun.ANONYMOUS, AgentRun.ANONYMOUS);
        // Unnamed is not unattributed: the rows still separate. What is missing is only the
        // part a person reads.
        assertThat(starts.get(0).run()).isNotEqualTo(starts.get(1).run());
    }

    @Test
    @DisplayName("an identity the caller mints is the one the run reports")
    void aCallerSuppliedIdentityIsHonoured() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        AgentRun mine = AgentRun.of("caller-owned");

        agent(llm, observed, "built-as", ALLOW).run(Goal.of("go"), mine);

        // The seam Subagent uses, and the one a sound parent link would arrive through
        // (#317): the caller's identity wins over the name the agent was built with.
        assertThat(observed.rows()).extracting(Row::run).containsOnly(mine);
    }
}
