package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A roster grown after the {@code delegate} tool was built (#308).
 *
 * <p>{@code delegateTool} used to copy two things out of the roster at build time — the
 * catalog inside {@code description()} and the {@code enum} inside {@code inputSchema()} —
 * while the handler resolved against the live roster. {@link SubagentRoster#add} is public
 * and mutating, so the two drifted apart through the API's own front door, and nothing
 * detected it. Every test here fails on that version and is written to say so.
 */
class GrowableRosterTest {

    private static Subagent textSubagent(String name, String output) {
        return Subagent.of(name, name + " specialist", () -> new Agent(
                new FakeLlmClient(FakeLlmClient.text(output)),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
    }

    /** The {@code subagent} enum a spec advertises, which is the half that went stale. */
    @SuppressWarnings("unchecked")
    private static List<String> advertisedNames(Map<String, Object> schema) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> subagent = (Map<String, Object>) properties.get("subagent");
        return List.copyOf((List<String>) subagent.get("enum"));
    }

    private static ToolSpec delegateSpec(LlmRequest request) {
        return request.tools().stream()
                .filter(spec -> SubagentTools.DELEGATE.equals(spec.name()))
                .findFirst().orElseThrow();
    }

    /**
     * The claim, at the smallest scale that states it: a subagent added after the tool was
     * built is in the enum and in the catalog the next time the spec is asked for.
     *
     * <p>The {@code isEmpty}/{@code doesNotContain} half is not decoration. Without it the
     * test would also pass on a tool that advertised every name that ever existed, and the
     * thing being pinned is that the advertised contract tracks the roster rather than
     * accumulating.
     */
    @Test
    @DisplayName("a subagent added after the tool was built is advertised on the next spec()")
    void aRosterGrownAfterTheToolWasBuiltIsAdvertised() {
        SubagentRoster roster = SubagentRoster.of(textSubagent("researcher", "facts"));
        Tool delegate = SubagentTools.delegateTool(roster);

        assertThat(advertisedNames(delegate.spec().inputSchema())).containsExactly("researcher");
        assertThat(delegate.spec().description()).doesNotContain("auditor");

        roster.add(textSubagent("auditor", "checked"));

        assertThat(advertisedNames(delegate.spec().inputSchema()))
                .containsExactly("researcher", "auditor");
        assertThat(delegate.spec().description()).contains("auditor specialist");
    }

    /**
     * The other half of the divergence: the handler always resolved live, so a stale enum
     * meant the model was told a callable subagent was not one. Both halves now agree, and
     * this pins the agreement rather than either side of it.
     */
    @Test
    @DisplayName("what the enum advertises is exactly what delegate resolves")
    void theEnumAndTheHandlerAgreeBeforeAndAfterGrowth() {
        SubagentRoster roster = SubagentRoster.of(textSubagent("researcher", "facts"));
        Tool delegate = SubagentTools.delegateTool(roster);

        ToolResult before = delegate.execute(new ToolInvocation("t1", SubagentTools.DELEGATE,
                Map.of("subagent", "auditor", "goal", "check it")));
        assertThat(before.isError()).isTrue();
        assertThat(before.content()).contains("Unknown subagent 'auditor'");
        assertThat(advertisedNames(delegate.spec().inputSchema())).doesNotContain("auditor");

        roster.add(textSubagent("auditor", "checked"));

        ToolResult after = delegate.execute(new ToolInvocation("t2", SubagentTools.DELEGATE,
                Map.of("subagent", "auditor", "goal", "check it")));
        assertThat(after.isError()).isFalse();
        assertThat(after.content()).contains("checked");
        assertThat(advertisedNames(delegate.spec().inputSchema())).contains("auditor");
    }

    /**
     * The same thing through the agent loop, which is where it has to be true.
     *
     * <p>{@code Agent.buildRequest} calls {@code advertisedSpecs()} on every turn and
     * {@code Tool.spec()} rebuilds itself from the two getters, so nothing but the tool had
     * to change for a grown roster to reach the model. This asserts on the requests the
     * model actually received: turn one does not offer {@code auditor}, turn two does, and
     * the delegation on turn two succeeds.
     */
    @Test
    @DisplayName("the model is offered the grown roster on the turn after it grew")
    void theLoopAdvertisesTheGrownRosterOnTheNextTurn() {
        SubagentRoster roster = SubagentRoster.of(textSubagent("researcher", "facts"));
        Tool hire = FunctionTool.builder("hire", "Adds an auditor to the roster")
                .handler(invocation -> {
                    roster.add(textSubagent("auditor", "the numbers check out"));
                    return ToolResult.ok("hired");
                })
                .build();

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("1", "hire", Map.of()),
                FakeLlmClient.toolUse("2", SubagentTools.DELEGATE,
                        Map.of("subagent", "auditor", "goal", "check the numbers")),
                FakeLlmClient.text("They check out."));

        AgentResult result = new Agent(llm,
                new SimpleToolRegistry(List.of(hire, SubagentTools.delegateTool(roster))),
                AgentConfig.builder("m").maxSteps(5).build())
                .run(Goal.of("Check the numbers."));

        assertThat(result.isSuccess()).isTrue();
        List<LlmRequest> requests = llm.received();
        assertThat(requests).hasSize(3);

        // Turn one: the roster the tool was built from.
        assertThat(advertisedNames(delegateSpec(requests.get(0)).inputSchema()))
                .containsExactly("researcher");

        // Turn two: the roster as `hire` left it. This is the assertion the snapshotting
        // version fails — there it is still ["researcher"], while the handler that ran on
        // this very turn resolved "auditor" happily.
        assertThat(advertisedNames(delegateSpec(requests.get(1)).inputSchema()))
                .containsExactly("researcher", "auditor");
        assertThat(delegateSpec(requests.get(1)).description()).contains("auditor specialist");
    }

    /**
     * The cost, stated as a test so the statement cannot quietly become false.
     *
     * <p>Rendering live means the tool definitions in a request can change between turns,
     * and tool definitions sit in the prefix a provider caches on. What bounds that cost is
     * that they change <em>only</em> when the roster changes: a roster nobody grows renders
     * byte-identical text on every turn and invalidates nothing. That is the claim
     * {@code delegateTool}'s javadoc makes, and this is it.
     */
    @Test
    @DisplayName("an unchanged roster renders a byte-identical description and schema")
    void anUnchangedRosterCostsNoPromptCache() {
        SubagentRoster roster = SubagentRoster.of(
                textSubagent("researcher", "facts"), textSubagent("drafter", "prose"));
        Tool delegate = SubagentTools.delegateTool(roster);

        ToolSpec first = delegate.spec();
        for (int i = 0; i < 50; i++) {
            assertThat(delegate.spec().description()).isEqualTo(first.description());
            assertThat(delegate.spec().inputSchema()).isEqualTo(first.inputSchema());
        }

        roster.add(textSubagent("auditor", "checks"));
        assertThat(delegate.spec().description()).isNotEqualTo(first.description());
    }

    /**
     * The declarations the hand-written tool has to make for itself.
     *
     * <p>{@code delegateTool} returned a {@code FunctionTool} before #308, and a
     * purpose-built {@link Tool} answers six non-abstract members that the builder used to
     * answer — the trap {@code ForwardingTool}'s javadoc is about. {@link Provenance} is the
     * one that was declared and would be silently lost, so it is the one pinned; the rest
     * take the same defaults they took before.
     */
    @Test
    @DisplayName("rendering live did not drop the declarations the builder used to make")
    void theHandWrittenToolKeepsItsDeclarations() {
        Tool delegate = SubagentTools.delegateTool(
                SubagentRoster.of(textSubagent("researcher", "facts")));

        assertThat(delegate.name()).isEqualTo(SubagentTools.DELEGATE);
        assertThat(delegate.provenance()).isEqualTo(Provenance.THIRD_PARTY);
        assertThat(delegate.inputExamples()).isEmpty();
        assertThat(delegate.spec().name()).isEqualTo(SubagentTools.DELEGATE);
        assertThat(delegate.spec().description()).isEqualTo(delegate.description());
        assertThat(delegate.spec().inputSchema()).isEqualTo(delegate.inputSchema());
    }

    /**
     * A roster read while it is grown, which is the case a growable roster creates.
     *
     * <p>A delegation runs a whole subagent and nothing says that happens on the agent
     * loop's thread, so a tool handler calling {@code add} while the loop renders the
     * catalog is a real interleaving rather than a hypothetical one. On the
     * {@link java.util.LinkedHashMap} this class used to be, the reader below throws
     * {@link java.util.ConcurrentModificationException} within the first few dozen
     * iterations; on the copy-on-write map it cannot, because the map a reader is iterating
     * is never the map a writer edits.
     */
    @Test
    @DisplayName("a roster can be grown while another thread renders it")
    void growingWhileReadingIsSafe() throws Exception {
        SubagentRoster roster = SubagentRoster.of(textSubagent("researcher", "facts"));
        Tool delegate = SubagentTools.delegateTool(roster);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                reading.countDown();
                while (done.getCount() > 0) {
                    delegate.spec();
                    roster.catalog();
                    roster.names();
                    roster.all();
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        reader.start();
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            for (int i = 0; i < 2_000; i++) {
                roster.add(textSubagent("worker-" + i, "does " + i));
            }
        } finally {
            done.countDown();
            reader.join(10_000);
        }

        assertThat(failures).isEmpty();
        assertThat(roster.names()).hasSize(2_001).startsWith("researcher", "worker-0");
    }

    /**
     * The description and the enum always describe the <em>same</em> roster.
     *
     * <p>#308's divergence is a description and a schema that disagree, and the obvious fix
     * for it reintroduces the divergence at a smaller scale: {@code Tool.spec()}'s default
     * calls {@code description()} and {@code inputSchema()} in turn, so a roster grown
     * between those two calls yields a spec whose catalog and whose enum list different
     * subagents. {@code delegateTool} overrides {@code spec()} to take one snapshot, and
     * this is what would catch dropping that override: every spec read here is taken while
     * another thread grows the roster, and each one has to be internally consistent.
     */
    @Test
    @DisplayName("a spec taken while the roster grows never mixes two rosters")
    void theCatalogAndTheEnumAreTakenFromOneRoster() throws Exception {
        SubagentRoster roster = SubagentRoster.of(textSubagent("researcher", "facts"));
        Tool delegate = SubagentTools.delegateTool(roster);
        List<String> mismatches = new CopyOnWriteArrayList<>();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            reading.countDown();
            while (done.getCount() > 0) {
                ToolSpec spec = delegate.spec();
                List<String> advertised = advertisedNames(spec.inputSchema());
                List<String> catalogued = new ArrayList<>();
                for (String line : spec.description().split("\n")) {
                    if (line.startsWith("- ")) {
                        catalogued.add(line.substring(2, line.indexOf(':')));
                    }
                }
                if (!advertised.equals(catalogued)) {
                    mismatches.add(advertised + " vs " + catalogued);
                }
            }
        });
        reader.start();
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            for (int i = 0; i < 2_000; i++) {
                roster.add(textSubagent("worker-" + i, "does " + i));
            }
        } finally {
            done.countDown();
            reader.join(10_000);
        }

        assertThat(mismatches).isEmpty();
    }
}
