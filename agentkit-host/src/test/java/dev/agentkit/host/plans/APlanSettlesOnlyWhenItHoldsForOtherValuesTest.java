package dev.agentkit.host.plans;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.host.repo.RepoLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which tasks are alike, how a plan is kept without their values, and when one has settled: only after plans in a
 * row agreed, were carried out cleanly, and saw each value they put back differ — so a plan is reused because it held
 * for other values, not because it met the same one each time.
 */
class APlanSettlesOnlyWhenItHoldsForOtherValuesTest {

    @TempDir
    Path dir;

    private AgentDefinition agent;
    private final PlanBook book = PlanBook.inMemory();
    private final PlanReuse reuse = new PlanReuse(book);
    private final PlanBook.Agent where = new PlanBook.Agent("acme", "replacement", "v1");

    @BeforeEach
    void load() throws Exception {
        Path root = dir.resolve("repo");
        Files.createDirectories(root.resolve("agents/replacement"));
        Files.writeString(root.resolve("org.yaml"), "org: acme\nmodel: m\n");
        Files.createDirectories(root.resolve("connectors"));
        Files.writeString(root.resolve("connectors/helpdesk.yaml"), "url: https://helpdesk.acme.example/mcp\n");
        Files.writeString(root.resolve("agents/replacement/agent.yaml"), """
                name: Replacement
                pattern: plan-execute
                prompt: {planner: planner.md, executor: executor.md}
                tools: [{connector: helpdesk, effects: [read]}]
                input: {schema: input.yaml}
                plans:
                  reuse: {after: 2, recheckEvery: 5, sameWhen: [office]}
                """);
        Files.writeString(root.resolve("agents/replacement/planner.md"), "Plan.");
        Files.writeString(root.resolve("agents/replacement/executor.md"), "Do.");
        Files.writeString(root.resolve("agents/replacement/input.yaml"), """
                type: object
                required: [recipient, laptop]
                properties:
                  recipient: {type: string}
                  laptop: {type: string, enum: [mac, pc]}
                  urgent: {type: boolean}
                  office: {type: string}
                  note: {type: string}
                """);
        agent = RepoLoader.load(root, "v1").agents().get("replacement");
    }

    private PlanTask task(Map<String, Object> input) {
        return PlanTask.of(agent, "v1", input, path -> path.equals("principal.email")
                ? Optional.of("priya@acme.example") : Optional.empty());
    }

    @Test
    void choicesAndFilledInFieldsMakeTheKindAndTheRestAreValues() {
        PlanTask marcus = task(Map.of("recipient", "Marcus Bell", "laptop", "mac", "office", "New York"));
        PlanTask ravi = task(Map.of("recipient", "Ravi Menon", "laptop", "mac", "office", "New York"));

        assertThat(marcus.shape()).isEqualTo(ravi.shape());
        assertThat(task(Map.of("recipient", "Ravi Menon", "laptop", "pc", "office", "New York")).shape())
                .as("another choice").isNotEqualTo(marcus.shape());
        assertThat(task(Map.of("recipient", "Ravi Menon", "laptop", "mac", "office", "Lisbon")).shape())
                .as("a field sameWhen names").isNotEqualTo(marcus.shape());
        assertThat(task(Map.of("recipient", "Ravi Menon", "laptop", "mac", "office", "New York", "note", "fragile"))
                .shape()).as("a field filled in that was not").isNotEqualTo(marcus.shape());
        assertThat(task(Map.of("recipient", "priya@acme.example", "laptop", "mac", "office", "New York")).shape())
                .as("two values that happen to be equal").isNotEqualTo(marcus.shape());

        String kept = marcus.parameterize("Order a mac for Marcus Bell; tell priya@acme.example. Marcus Bellamy is not him.");
        assertThat(kept).isEqualTo("Order a mac for {{input.recipient}}; tell {{principal.email}}. Marcus Bellamy is "
                + "not him.");
        assertThat(ravi.instantiate(kept)).hasValue("Order a mac for Ravi Menon; tell priya@acme.example. Marcus "
                + "Bellamy is not him.");
        assertThat(ravi.instantiate("Ship it to {{input.note}}")).as("a value this task has not").isEmpty();
    }

    @Test
    void aPlanSettlesAfterCleanAgreeingRunsWithValuesThatDiffered() {
        PlanTask marcus = task(Map.of("recipient", "Marcus Bell", "laptop", "mac"));
        PlanTask ravi = task(Map.of("recipient", "Ravi Menon", "laptop", "mac"));
        PlanTask lena = task(Map.of("recipient", "Lena Ortiz", "laptop", "mac"));
        AgentDefinition.PlanReuse rules = agent.planReuse();

        keep(marcus, "Order a mac for Marcus Bell.", false, true);
        assertThat(reuse.settled(where, rules, marcus.shape())).as("one plan is an anecdote").isEmpty();
        keep(marcus, "Order a mac for Marcus Bell.", false, true);
        assertThat(reuse.settled(where, rules, marcus.shape()))
                .as("two agreeing plans, but only ever for Marcus: the name was never seen to change").isEmpty();
        keep(ravi, "Order a mac for Ravi Menon.", false, true);
        assertThat(reuse.settled(where, rules, marcus.shape())).hasValueSatisfying(settled ->
                assertThat(settled.steps()).containsExactly("Order a mac for {{input.recipient}}."));
        assertThat(reuse.forRun(where, rules, lena)).as("run 4 of every 5 reuses it").isPresent();

        keep(lena, "Order a mac for Lena Ortiz.", true, true);
        assertThat(reuse.forRun(where, rules, lena)).as("run 5 of every 5 is planned afresh").isEmpty();
        keep(lena, "Order a mac for Lena Ortiz.", true, false);
        assertThat(reuse.settled(where, rules, marcus.shape())).as("a reused plan that did not go cleanly").isEmpty();

        keep(marcus, "Order a mac for Marcus Bell.", false, true);
        keep(ravi, "Order a mac, and a bag, for Ravi Menon.", false, true);
        assertThat(reuse.settled(where, rules, marcus.shape())).as("the model planned differently").isEmpty();
    }

    private void keep(PlanTask task, String step, boolean reused, boolean clean) {
        book.add(where, task.shape(), new PlanBook.Run(List.of(task.parameterize(step)), task.values(), reused, clean,
                Instant.now()));
    }
}
