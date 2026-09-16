package dev.agentkit.core.goap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GoapDomainTest {

    private static Agent replying(String output) {
        return new Agent(new FakeLlmClient(FakeLlmClient.text(output)),
                new SimpleToolRegistry(), AgentConfig.builder("m").build());
    }

    // --- WorldState -----------------------------------------------------------

    @Test
    void aStateIsUnaffectedByChangesToTheMapItWasBuiltFrom() {
        // Facts are what the planner reasons over and what a trace is replayed against; a
        // state that changed under the caller's feet would make both meaningless.
        Map<String, Object> source = new HashMap<>(Map.of("sources", "s"));
        WorldState state = WorldState.of(source);

        source.put("smuggled", "in");

        assertThat(state.keys()).containsExactly("sources");
        assertThatThrownBy(() -> state.facts().put("also", "no"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withReturnsANewStateRatherThanMutatingThisOne() {
        WorldState before = WorldState.of("sources", "s");

        WorldState after = before.with("article", "a");

        assertThat(before.keys()).containsExactly("sources");
        assertThat(after.keys()).containsExactly("sources", "article");
    }

    @Test
    void factOrderSurvivesSoTracesAndMessagesAreReproducible() {
        // Map.copyOf and Set.copyOf randomise iteration order per JVM run — not per
        // instance — so the two-key version of this passed locally and failed in CI. Twelve
        // keys makes a regression fail on essentially every run instead of half of them.
        List<String> keys = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> "fact_" + i).toList();
        WorldState state = WorldState.EMPTY;
        for (String key : keys) {
            state = state.with(key, key);
        }
        Objective objective = new Objective("Everything", new java.util.LinkedHashSet<>(keys));

        assertThat(state.keys()).containsExactlyElementsOf(keys);
        assertThat(objective.required()).containsExactlyElementsOf(keys);
        // Read straight into a failure message; a message that reorders itself run to run
        // is a message you cannot diff.
        assertThat(objective.missingIn(WorldState.EMPTY)).containsExactlyElementsOf(keys);
    }

    @Test
    void aNullFactValueIsRejectedRatherThanLookingLikeAMissingFact() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("sources", null);

        assertThatThrownBy(() -> WorldState.of(withNull))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sources");
    }

    @Test
    void aFactThatIsNotTextStillReadsBackRatherThanLookingAbsent() {
        // The one confusion that must not happen here: empty means "not established", and a
        // fact holding a record would otherwise report as if the action had never run.
        WorldState state = WorldState.of("count", 42);

        assertThat(state.text("count")).contains("42");
        assertThat(state.text("nothing_here")).isEmpty();
    }

    // --- Action ---------------------------------------------------------------

    @Test
    void aBuilderWithNoHandlerIsRejectedAtBuildTime() {
        // The terminal is build(), so this is now reachable — it was dead code when
        // handler(...) constructed the action itself.
        assertThatThrownBy(() -> Action.named("noop").produces("x").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no handler");
    }

    @Test
    void anActionThatProducesNothingIsRejected() {
        assertThatThrownBy(() -> Action.named("noop")
                .handler(state -> ActionResult.ok(Map.of())).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produces nothing");
    }

    @Test
    void anActionThatNeedsWhatItProducesIsRejected() {
        // It could only run once its own output existed, so it never would — and the run
        // would fail somewhere else, naming a fact rather than the action that broke it.
        assertThatThrownBy(() -> Action.named("polish").needs("draft").produces("draft")
                .handler(state -> ActionResult.ok("draft", "d")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could only run after it had already run");
    }

    @Test
    void aNonPositiveCostIsRejectedBecauseItBreaksTheCheapestPlanGuarantee() {
        assertThatThrownBy(() -> Action.named("free").cost(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cost must be > 0");
    }

    @Test
    void aFailedActionMayNotEstablishFacts() {
        // Otherwise a failure could satisfy a later action's precondition, and the planner
        // would route through a step that had just reported it did not work.
        assertThatThrownBy(() -> new ActionResult(Map.of("sources", "s"),
                java.util.Optional.empty(), java.util.Optional.of("could not")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not establish facts");
    }

    // --- Action.agent(...) ----------------------------------------------------

    @Test
    void anAgentActionFilesItsOutputUnderTheKeyItProduces() {
        GoapResult result = GoapRunner.forObjective(Objective.of("An article", "article"))
                .action(Action.named("write").produces("article")
                        .agent("Write it", () -> replying("the finished article")).build())
                .run(WorldState.EMPTY);

        assertThat(result.output("article")).contains("the finished article");
    }

    @Test
    void anAgentActionIsHandedOnlyTheFactsItDeclaredItNeeds() {
        // Declaring needs is how an action says what its context is. A fact-checker that
        // asked for sources should not also be shown the draft it is meant to check.
        List<String> prompts = new ArrayList<>();
        FakeLlmClient inner = new FakeLlmClient(FakeLlmClient.text("checked"));
        Agent recording = new Agent(request -> {
            request.messages().stream().findFirst()
                    .ifPresent(message -> prompts.add(message.text()));
            return inner.generate(request);
        }, new SimpleToolRegistry(), AgentConfig.builder("m").build());

        // The objective requires draft too, so the draft action actually runs and its fact
        // is in the state when check is planned. Without that, doesNotContain("THE DRAFT")
        // passed because the draft was never produced — the test held with agent(...)
        // rendering every fact.
        GoapRunner.forObjective(Objective.of("A checked article", "check", "draft"))
                .action(Action.named("research").produces("sources")
                        .handler(state -> ActionResult.ok("sources", "THE SOURCES")).build())
                .action(Action.named("draft").produces("draft")
                        .handler(state -> ActionResult.ok("draft", "THE DRAFT")).build())
                .action(Action.named("check").cost(5).needs("sources").produces("check")
                        .agent("Check every claim", () -> recording).build())
                .run(WorldState.EMPTY);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("Check every claim").contains("THE SOURCES");
        assertThat(prompts.get(0)).doesNotContain("THE DRAFT");
    }

    @Test
    void anAgentActionBuildsAFreshAgentPerAttempt() {
        // Same reason Subagent and GraphNode do it: a registry that accumulates revealed
        // tools, or a working-memory scratchpad, must not leak between attempts — including
        // between the two the planner makes when it routes around a failure.
        AtomicInteger built = new AtomicInteger();

        GoapRunner.forObjective(Objective.of("Two drafts", "a", "b"))
                .action(Action.named("first").produces("a")
                        .agent("Write A", () -> {
                            built.incrementAndGet();
                            return replying("a");
                        }).build())
                .action(Action.named("second").produces("b")
                        .agent("Write B", () -> {
                            built.incrementAndGet();
                            return replying("b");
                        }).build())
                .run(WorldState.EMPTY);

        assertThat(built).hasValue(2);
    }

    @Test
    void anAgentActionProducingMoreThanOneKeyIsRejectedAtWiringTime() {
        assertThatThrownBy(() -> Action.named("write").produces("article", "bibliography")
                .agent("Write it", () -> replying("x")).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("produces [article, bibliography]");
    }

    // --- Objective ------------------------------------------------------------

    @Test
    void anObjectiveRequiringNothingIsRejectedRatherThanBeingMetImmediately() {
        assertThatThrownBy(() -> Objective.of("Something vague"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("met before anything runs");
    }

    @Test
    void missingInNamesWhatIsLeft() {
        Objective objective = Objective.of("An article with notes", "article", "notes");

        assertThat(objective.missingIn(WorldState.of("article", "a"))).containsExactly("notes");
        assertThat(objective.isMetBy(WorldState.of(Map.of("article", "a", "notes", "n")))).isTrue();
    }
}
