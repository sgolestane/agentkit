package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.supervisor.SubagentTools;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A run says whose behalf it is on, and never guesses (#317).
 *
 * <h2>The defect</h2>
 *
 * <p>#311 gave every observer callback an {@link AgentRun}, so a reviewer could say
 * <em>who</em> made a call. It carried no parent, so an attributed trace was a set of rows
 * and not a tree: two sibling subagents of two different supervisors were distinguishable
 * only by name, and "on whose behalf" had no answer at all.
 *
 * <h2>The seam, and the one that was rejected</h2>
 *
 * <p>An ambient {@code ThreadLocal} is right for synchronous delegation and silently wrong
 * on an injected pool, where a child inherits nothing or inherits an unrelated earlier run.
 * {@code AgentGraph} and {@code Supervisor} both carry their concurrency permit into each
 * submission by hand for exactly that reason. So the link travels explicitly:
 * {@link Tool#boundTo(AgentRun)} is a tool-side opt-in the agent loop consults at dispatch,
 * {@code SubagentTools}' {@code delegate} is the one tool that takes it, and
 * {@link Subagent} mints the child through {@link AgentRun#child(String)}.
 *
 * <h2>What is asserted, and how it is wired</h2>
 *
 * <p>Every test here goes through the <strong>real</strong> {@code delegate} tool inside a
 * real {@link Agent}, not a hand-written stand-in that calls {@code Subagent.handle} itself.
 * The point of the change is that no tool author has to care and a tool that does not care
 * cannot fail open, and a stand-in would have proved neither: it would have passed against
 * a build in which {@code Agent} never called {@code boundTo} at all.
 *
 * <h2>What the mutation pass killed</h2>
 *
 * <p>Each mutant was planted on this branch, built clean, and put through this class,
 * {@code ForwardingToolTest} and {@code AgentTelemetryTest} in {@code agentkit-otel}. Counts
 * come from {@code Tests run:} lines rather than from the build's exit status.
 *
 * <pre>
 * mutant                                                    here  Forwarding  otel
 * Agent.runTool executing tool.get() instead of bound         4        -        -
 * DelegateTool.boundTo returning this                         5        -        -
 * DelegateTool.execute dropping the parent it holds           4        -        -
 * Subagent.runFor ignoring the parent it was handed           6        -        -
 * AgentRun.child dropping the parent                          7        -        -
 * an unbound DelegateTool inventing a parent                  1        -        -
 * Subagent.handlingUnder minting without the parent           1        -        -
 * recordingParks rebuilt on Subagent.handling                 1        -        -
 * ForwardingTool.boundTo deleted                              1        4        1
 * ForwardingTool.boundTo as delegate().boundTo(run)           1        5        6
 * Tools.Attributed.rebuiltAround deleted                      1        1        -
 * TracingTool.rebuiltAround deleted                           -        -        1
 * </pre>
 *
 * <p><strong>Two mutants survived their first pass, and both survived for the same
 * reason.</strong> Deleting {@code ForwardingTool.boundTo} killed four tests in
 * {@code ForwardingToolTest} and nothing here: every test in this file registered the bare
 * {@code delegate}, so the decorated composition — which is what an instrumented deployment
 * runs — was measured only in isolation, against decorators the test wrote itself. Deleting
 * {@code TracingTool.rebuiltAround} passed the whole {@code otel} suite for the same reason.
 * {@link #aDecoratedDelegateStillNamesTheParent()} and
 * {@code AgentTelemetryTest.instrumentingDelegateKeepsBothTheSpanAndTheParentLink} were
 * added, and each kills its mutant. No survivors after that, and none skipped.
 */
class RunKnowsItsParentTest {

    /** One row of what an observer was told, with whose row it was. */
    private record Row(AgentRun run, String tool, Disposition disposition) {
    }

    /** One instance, wired into the supervisor and into every child — the only useful shape. */
    private static final class Shared implements AgentObserver {
        private final List<Row> rows = new ArrayList<>();

        @Override
        public synchronized void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                                              ToolInvocation effective, ToolResult result,
                                              Disposition disposition) {
            rows.add(new Row(run, effective.name(), disposition));
        }

        synchronized List<Row> rows() {
            return List.copyOf(rows);
        }

        synchronized Row forTool(String name) {
            return rows.stream().filter(row -> row.tool().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no row for " + name));
        }
    }

    private static final ToolGate ALLOW = (tool, invocation) -> GateResult.allow();

    private static FunctionTool tool(String name) {
        return FunctionTool.builder(name, "does " + name)
                .schema(Map.of("type", "object"))
                .handler(invocation -> ToolResult.ok(name + " done"))
                .build();
    }

    private static ToolRegistry registryOf(Tool... tools) {
        return new SimpleToolRegistry(List.of(tools));
    }

    private static Agent agent(FakeLlmClient llm, Shared observer, String name,
                               ToolGate gate, Tool... tools) {
        Agent.Builder builder = Agent.builder(llm, registryOf(tools),
                        AgentConfig.builder("m").maxSteps(8).build())
                .toolGate(gate)
                .observer(observer);
        if (name != null) {
            builder.name(name);
        }
        return builder.build();
    }

    // --- the headline -------------------------------------------------------------

    /**
     * A subagent's run names the supervisor's run as its parent, through the real tool.
     *
     * <p>The child's tool call and the supervisor's own are both step 1 on the same shared
     * observer, so neither the step number nor the ordering separates them — and the child's
     * row arrives first, because delegation is synchronous. The parent link is the only
     * thing in either row that says which of them caused the other.
     */
    @Test
    @DisplayName("a subagent's run names the run that delegated to it")
    void aChildNamesTheRunThatDelegatedToIt() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(
                // the supervisor's step 1: the real delegate tool
                FakeLlmClient.toolUse("s1", SubagentTools.DELEGATE,
                        Map.of("subagent", "researcher", "goal", "look up the returns policy")),
                // the researcher's own step 1, on the same observer
                FakeLlmClient.toolUse("r1", "lookup", Map.of("topic", "returns")),
                FakeLlmClient.text("30 days with a receipt."),
                // back in the supervisor
                FakeLlmClient.text("Told you: 30 days."));

        SubagentRoster roster = SubagentRoster.of(Subagent.of("researcher", "looks things up",
                () -> agent(llm, observed, null, ALLOW, tool("lookup"))));

        agent(llm, observed, "supervisor", ALLOW, SubagentTools.delegateTool(roster))
                .run(Goal.of("what is the returns policy"));

        AgentRun supervisor = observed.forTool(SubagentTools.DELEGATE).run();
        AgentRun researcher = observed.forTool("lookup").run();

        assertThat(researcher.name()).isEqualTo("researcher");
        assertThat(supervisor.name()).isEqualTo("supervisor");
        // The whole of it. Before this the child knew itself and nothing above it.
        assertThat(researcher.parent()).contains(supervisor);
        // Asserted on the id as well as on the record, because the record's equals walks the
        // whole chain and would also be satisfied by a *copy* of the supervisor's identity
        // minted somewhere else — which is not the same claim.
        assertThat(researcher.parent().orElseThrow().id()).isEqualTo(supervisor.id());
        // And the supervisor is the top: nothing invented a parent for it.
        assertThat(supervisor.parent()).isEmpty();

        // Neither the step number nor the ordering could have said any of that: the child's
        // row is first and both calls are step 1 in their own runs.
        assertThat(observed.rows()).extracting(Row::tool)
                .containsExactly("lookup", SubagentTools.DELEGATE);
    }

    /**
     * Two siblings under two supervisors, which is the case #317 names as unanswerable.
     *
     * <p>The two children have the <em>same roster name</em>, so the name cannot separate
     * them and neither can the tool. Only the parent can.
     */
    @Test
    @DisplayName("two subagents of the same name under two supervisors are told apart")
    void twoSiblingsOfTwoParentsAreDistinguishable() {
        Shared observed = new Shared();
        FakeLlmClient first = new FakeLlmClient(
                FakeLlmClient.toolUse("a1", SubagentTools.DELEGATE,
                        Map.of("subagent", "worker", "goal", "do a")),
                FakeLlmClient.toolUse("w1", "act", Map.of()),
                FakeLlmClient.text("a done"),
                FakeLlmClient.text("first done"));
        FakeLlmClient second = new FakeLlmClient(
                FakeLlmClient.toolUse("b1", SubagentTools.DELEGATE,
                        Map.of("subagent", "worker", "goal", "do b")),
                FakeLlmClient.toolUse("w2", "act", Map.of()),
                FakeLlmClient.text("b done"),
                FakeLlmClient.text("second done"));

        SubagentRoster one = SubagentRoster.of(Subagent.of("worker", "works",
                () -> agent(first, observed, null, ALLOW, tool("act"))));
        SubagentRoster two = SubagentRoster.of(Subagent.of("worker", "works",
                () -> agent(second, observed, null, ALLOW, tool("act"))));

        agent(first, observed, "alpha", ALLOW, SubagentTools.delegateTool(one))
                .run(Goal.of("a"));
        agent(second, observed, "beta", ALLOW, SubagentTools.delegateTool(two))
                .run(Goal.of("b"));

        List<Row> children = observed.rows().stream()
                .filter(row -> row.tool().equals("act")).toList();
        assertThat(children).hasSize(2);
        assertThat(children).extracting(row -> row.run().name())
                .as("the name is the same string in both, which is the point")
                .containsExactly("worker", "worker");
        assertThat(children).extracting(row -> row.run().parent().orElseThrow().name())
                .containsExactly("alpha", "beta");
    }

    /**
     * A call a policy stopped inside a child says whose behalf it was on.
     *
     * <p>#311's headline row, one question further along. A reviewer holding
     * {@code shred, REFUSED} could already say the worker did it; now they can say who asked
     * the worker to.
     */
    @Test
    @DisplayName("a call the policy stopped inside a child names the delegating run")
    void aStoppedCallInsideAChildNamesTheDelegatingRun() {
        Shared observed = new Shared();
        ToolGate policy = (tool, invocation) -> "shred".equals(invocation.name())
                ? GateResult.deny("policy says no") : GateResult.allow();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("s1", SubagentTools.DELEGATE,
                        Map.of("subagent", "worker", "goal", "tidy up")),
                FakeLlmClient.toolUse("w1", "shred", Map.of("id", "order-1")),
                FakeLlmClient.text("I could not erase that."),
                FakeLlmClient.text("Done what I could."));

        SubagentRoster roster = SubagentRoster.of(Subagent.of("worker", "tidies up",
                () -> agent(llm, observed, null, policy, tool("shred"))));

        agent(llm, observed, "supervisor", policy, SubagentTools.delegateTool(roster))
                .run(Goal.of("tidy up"));

        Row shred = observed.forTool("shred");
        assertThat(shred.disposition()).isEqualTo(Disposition.REFUSED);
        assertThat(shred.run().name()).isEqualTo("worker");
        assertThat(shred.run().parent().orElseThrow().name()).isEqualTo("supervisor");
    }

    // --- the degradations, which are the reason to trust the link ------------------

    /**
     * A {@code delegate} nobody bound produces <em>no</em> parent, not a wrong one.
     *
     * <p>The shape of the durable path ({@code ToolActivitiesImpl} executes tools from an
     * activity and never holds an {@code AgentRun}), of {@code ToolBridges}, and of anybody
     * calling {@code Tool.execute} directly. This calls the tool exactly the way those do —
     * without {@code boundTo} — and requires the child to come back parentless.
     */
    @Test
    @DisplayName("a delegate executed with no run in hand produces a child with no parent")
    void anUnboundDelegateProducesNoParent() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("w1", "act", Map.of()),
                FakeLlmClient.text("done"));

        SubagentRoster roster = SubagentRoster.of(Subagent.of("worker", "works",
                () -> agent(llm, observed, null, ALLOW, tool("act"))));

        // No boundTo: this is what a runner with nothing to bind does.
        SubagentTools.delegateTool(roster).execute(new ToolInvocation(
                "d1", SubagentTools.DELEGATE, Map.of("subagent", "worker", "goal", "work")));

        AgentRun child = observed.forTool("act").run();
        assertThat(child.name()).isEqualTo("worker");
        assertThat(child.parent())
                .as("missing, never wrong: an invented parent is worse than none because a"
                        + " reviewer would trust it")
                .isEmpty();
    }

    /**
     * Binding is a copy, so one registered {@code delegate} serving concurrent runs cannot
     * hand one run's identity to another's child.
     *
     * <p>The reason {@code boundTo} returns a new tool rather than setting a field. A field
     * would be the last dispatch's run answering for whichever call read it, which is the
     * pooled-thread failure the {@code ThreadLocal} was rejected for, rebuilt inside the
     * thing that replaced it.
     */
    @Test
    @DisplayName("boundTo copies, so the unbound tool and two bindings do not share a parent")
    void bindingIsACopyAndNotAField() {
        SubagentRoster roster = SubagentRoster.of(Subagent.of("worker", "works",
                () -> agent(new FakeLlmClient(FakeLlmClient.text("done")), new Shared(),
                        null, ALLOW)));
        Tool unbound = SubagentTools.delegateTool(roster);
        AgentRun alpha = AgentRun.of("alpha");
        AgentRun beta = AgentRun.of("beta");

        Tool boundToAlpha = unbound.boundTo(alpha);
        Tool boundToBeta = unbound.boundTo(beta);

        assertThat(boundToAlpha).isNotSameAs(unbound);
        assertThat(boundToBeta).isNotSameAs(boundToAlpha);
        // And the advertised contract still renders live from the shared roster, which is
        // what a snapshot taken at binding time would have quietly ended (#308).
        roster.add(Subagent.of("late", "arrived after both bindings",
                () -> agent(new FakeLlmClient(FakeLlmClient.text("done")), new Shared(),
                        null, ALLOW)));
        assertThat(boundToAlpha.inputSchema().toString()).contains("late");
        assertThat(boundToAlpha.description()).contains("late");
    }

    /**
     * {@code Subagent.handling} carries no parent, and {@code handlingUnder} is the way to
     * one — the residual #317 named, and what was done about it.
     */
    @Test
    @DisplayName("handling carries no parent; handlingUnder hands the identity to the handler")
    void handlingCarriesNoParentAndHandlingUnderDoes() {
        Shared observed = new Shared();
        AgentRun supervisor = AgentRun.of("supervisor");

        Agent child = agent(new FakeLlmClient(
                        FakeLlmClient.toolUse("c1", "act", Map.of()),
                        FakeLlmClient.text("done")),
                observed, "built-as", ALLOW, tool("act"));

        // The handler owns the run and is told nothing about it.
        Subagent opaque = Subagent.handling("wrapped", "wraps", child::run);
        opaque.handle(Goal.of("go"), supervisor);
        AgentRun unstamped = observed.forTool("act").run();
        assertThat(unstamped.name())
                .as("the handler's own agent name, because nothing could stamp it")
                .isEqualTo("built-as");
        assertThat(unstamped.parent()).isEmpty();

        Shared second = new Shared();
        Agent other = agent(new FakeLlmClient(
                        FakeLlmClient.toolUse("c2", "act", Map.of()),
                        FakeLlmClient.text("done")),
                second, "built-as", ALLOW, tool("act"));
        // The same handler shape, told which identity to run under.
        Subagent stamped = Subagent.handlingUnder("wrapped", "wraps", other::run);
        stamped.handle(Goal.of("go"), supervisor);
        AgentRun told = second.forTool("act").run();
        assertThat(told.name()).isEqualTo("wrapped");
        assertThat(told.parent()).contains(supervisor);
    }

    /**
     * A subagent behind {@code SubagentTools.recordingParks} still names its parent.
     *
     * <p>The wrapper is the shipped one, and it is the composition most likely to be in the
     * way: a deployment that wants to hold a delegated park wraps every subagent in it.
     * Built with {@code Subagent.handling} — which is what it used and what the obvious
     * repair reaches for — it would drop the parent silently, because {@code handling} takes
     * a {@code Function} that has nowhere to put one. The same wrapper also carried both
     * hazard declarations away; {@code DelegateDeclaresWhatItsRosterHoldsTest} pins that
     * half.
     */
    @Test
    @DisplayName("a subagent behind recordingParks still names the run that delegated to it")
    void aWrappedSubagentStillNamesItsParent() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("s1", SubagentTools.DELEGATE,
                        Map.of("subagent", "worker", "goal", "work")),
                FakeLlmClient.toolUse("w1", "act", Map.of()),
                FakeLlmClient.text("done"),
                FakeLlmClient.text("all done"));

        SubagentRoster roster = SubagentRoster.of(SubagentTools.recordingParks(
                Subagent.of("worker", "works",
                        () -> agent(llm, observed, null, ALLOW, tool("act"))),
                park -> { }));

        agent(llm, observed, "supervisor", ALLOW, SubagentTools.delegateTool(roster))
                .run(Goal.of("work"));

        AgentRun child = observed.forTool("act").run();
        assertThat(child.name()).isEqualTo("worker");
        assertThat(child.parent().orElseThrow().name()).isEqualTo("supervisor");
    }

    /**
     * A decorated {@code delegate} still names the parent, through a real dispatch.
     *
     * <p>{@code ForwardingTool.boundTo} rebuilds the decorator around the bound tool rather
     * than forwarding to it, and {@code ForwardingToolTest} measures that in isolation. This
     * measures the composition the isolated test cannot: a shipped decorator wrapping the
     * real {@code delegate}, registered on a real {@link Agent}, dispatched by the real loop.
     * Dropping the forward leaves every decorated supervisor's children parentless, and
     * decorating tools is what {@code Tools.withProvenance} and {@code agentkit-otel} exist
     * for — so this is not an exotic composition, it is the instrumented deployment.
     *
     * <p>Both halves are asserted, because they fail in opposite directions: the parent has
     * to arrive (the forward happened) <em>and</em> the re-declaration has to survive (the
     * forward rebuilt rather than handed over). An implementation answering
     * {@code delegate().boundTo(run)} passes the first and fails the second.
     */
    @Test
    @DisplayName("a decorated delegate carries the parent and keeps its own declaration")
    void aDecoratedDelegateStillNamesTheParent() {
        Shared observed = new Shared();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("s1", SubagentTools.DELEGATE,
                        Map.of("subagent", "worker", "goal", "work")),
                FakeLlmClient.toolUse("w1", "act", Map.of()),
                FakeLlmClient.text("done"),
                FakeLlmClient.text("all done"));

        SubagentRoster roster = SubagentRoster.of(Subagent.of("worker", "works",
                () -> agent(llm, observed, null, ALLOW, tool("act"))));
        Tool decorated = dev.agentkit.core.tool.Tools.withProvenance(
                SubagentTools.delegateTool(roster),
                dev.agentkit.core.tool.Provenance.FIRST_PARTY);

        agent(llm, observed, "supervisor", ALLOW, decorated).run(Goal.of("work"));

        AgentRun child = observed.forTool("act").run();
        assertThat(child.parent().orElseThrow().name())
                .as("a decorator that drops boundTo leaves every instrumented supervisor's"
                        + " children parentless")
                .isEqualTo("supervisor");
        assertThat(decorated.boundTo(AgentRun.of("anybody")).provenance())
                .as("and rebuilding rather than handing over is what keeps the decorator's"
                        + " own re-declaration on the tool the loop actually runs")
                .isEqualTo(dev.agentkit.core.tool.Provenance.FIRST_PARTY);
    }

    // --- the record itself ---------------------------------------------------------

    @Test
    @DisplayName("a top-level run has no parent and a child of it does")
    void theRecordCarriesTheChain() {
        AgentRun root = AgentRun.of("supervisor");
        AgentRun child = root.child("researcher");
        AgentRun grandchild = child.child("reader");

        assertThat(root.parent()).isEmpty();
        assertThat(child.parent()).contains(root);
        assertThat(grandchild.parent().orElseThrow().parent()).contains(root);
        assertThat(child.name()).isEqualTo("researcher");
        // Ids stay distinct and stay readable: the chain is a chain of rows, not a blob.
        assertThat(List.of(root.id(), child.id(), grandchild.id())).doesNotHaveDuplicates();
        assertThat(child.toString()).isEqualTo(child.id()).startsWith("researcher#");
    }

    @Test
    @DisplayName("two children of one run are two identities under one parent")
    void twoChildrenOfOneRun() {
        AgentRun root = AgentRun.of("supervisor");
        AgentRun one = root.child("worker");
        AgentRun two = root.child("worker");

        assertThat(one).isNotEqualTo(two);
        assertThat(one.parent()).contains(root);
        assertThat(two.parent()).contains(root);
    }

    @Test
    @DisplayName("the canonical constructor refuses a null parent rather than reading it as none")
    void aNullParentIsNotAnEmptyOne() {
        assertThat(AgentRun.of("solo").parent()).isEqualTo(Optional.empty());
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new AgentRun("a#1", "a", null))
                .isInstanceOf(NullPointerException.class);
    }
}
