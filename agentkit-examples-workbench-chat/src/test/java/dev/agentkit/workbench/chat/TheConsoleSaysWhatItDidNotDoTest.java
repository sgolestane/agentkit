package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import dev.agentkit.core.tool.View;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Bounds the console keeps, and the rule that it says so when it hits one.
 *
 * <p>A cap that silently truncates reads as "that was all of them", which is how an operator
 * comes to believe twenty-five tickets were worked when they asked for thirty.
 */
class TheConsoleSaysWhatItDidNotDoTest {

    @Test
    void bulkExecutionStopsAtTwentyFiveAndNamesWhatItLeft() {
        ConsoleAlm alm = new ConsoleAlm(IntStream.rangeClosed(1, 30)
                .mapToObj(i -> ConsoleAlm.open("IT-" + i, "Grant access", "Add me."))
                .toArray(dev.agentkit.workbench.domain.Ticket[]::new));
        Console console = new Console(alm);
        console.llm.says("Nothing to do here.");

        String said = console.say("workbench.bulk_execute", "ticket_keys",
                IntStream.rangeClosed(1, 30).mapToObj(i -> "IT-" + i).toList());

        assertThat(console.store.runs(Console.TENANT)).hasSize(25);
        assertThat(said).contains("5 more were NOT run");
        assertThat(said).contains("IT-25").doesNotContain("IT-26");
    }

    @Test
    void aBulkThatFitsSaysNothingAboutSkipping() {
        ConsoleAlm alm = new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me."),
                ConsoleAlm.open("IT-2", "Reset password", "Locked out."));
        Console console = new Console(alm);
        console.llm.says("Nothing to do here.");

        String said = console.say("workbench.bulk_execute",
                "ticket_keys", List.of("IT-1", "IT-2"));

        assertThat(console.store.runs(Console.TENANT)).hasSize(2);
        assertThat(said).doesNotContain("NOT run");
        // Filed as a bulk selection rather than as the operator clicking twice, which is what
        // rules.list reads back when it says how many runs a rule has started.
        assertThat(console.store.runs(Console.TENANT))
                .allSatisfy(run -> assertThat(run.trigger())
                        .isEqualTo(dev.agentkit.workbench.domain.Run.Trigger.BULK));
    }

    @Test
    void oneFailedTicketDoesNotStopTheRest() {
        ConsoleAlm alm = new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me."),
                ConsoleAlm.open("IT-2", "Reset password", "Locked out."));
        Console console = new Console(alm);
        console.llm.says("Nothing to do here.");

        // IT-9 is not in the ALM at all, so the workbench refuses it. The two real ones on
        // either side of it still run, and the answer says which failed.
        String said = console.say("workbench.bulk_execute",
                "ticket_keys", List.of("IT-1", "IT-9", "IT-2"));

        assertThat(console.store.runs(Console.TENANT)).hasSize(2);
        assertThat(said).contains("IT-9").contains("failed");
    }

    @Test
    void anEmptyBulkIsRefusedRatherThanReportedAsNothingToDo() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        ToolResult refused = console.call("workbench.bulk_execute", "ticket_keys", List.of());

        assertThat(refused.isError()).isTrue();
        assertThat(console.store.runs(Console.TENANT)).isEmpty();
    }

    @Test
    void aListingIsCappedWhateverTheModelAsksFor() {
        ConsoleAlm alm = new ConsoleAlm(IntStream.rangeClosed(1, 80)
                .mapToObj(i -> ConsoleAlm.open("IT-" + i, "Grant access", "Add me."))
                .toArray(dev.agentkit.workbench.domain.Ticket[]::new));
        Console console = new Console(alm);

        assertThat(console.say("tickets.inbox", "limit", 500)).contains("50 open ticket(s)");
        // A model that sends a string, or a negative, gets a page rather than an exception.
        assertThat(console.say("tickets.inbox", "limit", "twelve")).contains("open ticket(s)");
        assertThat(console.say("tickets.inbox", "limit", -3)).contains("1 open ticket(s)");
    }

    @Test
    void aReadThatIsWorthLookingAtCarriesAView() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        assertThat(console.call("tickets.inbox").views())
                .singleElement()
                .satisfies(view -> assertThat(view.kind()).isEqualTo("table"));
        assertThat(console.call("tickets.get", "ticket_key", "IT-1").views())
                .singleElement()
                .satisfies(view -> assertThat(view.kind()).isEqualTo("cards"));
        assertThat(console.call("workbench.capabilities").views())
                .singleElement()
                .satisfies(view -> assertThat(view.kind()).isEqualTo("table"));
    }

    @Test
    void aTableSaysWhichColumnsAreNumbersSoTheySortAsNumbers() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        console.say("rules.automate", "category", "access-request");

        View table = console.call("rules.list").views().getFirst();

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> columns =
                (List<java.util.Map<String, Object>>) table.data().get("columns");
        assertThat(columns).extracting(column -> column.get("name") + ":" + column.get("type"))
                .contains("covers:number", "runs:number", "family:text");
    }

    @Test
    void theSurfaceOpensWithTheFewToolsAConversationStartsWith() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        var registry = ConsoleTools.registry(Console.DEPLOYMENT, console.alm,
                console.workbench, null, console.learnings, console.store, null);
        List<String> advertised = registry.advertisedSpecs().stream()
                .map(ToolSpec::name).toList();

        // What an operator opens a conversation with, and the way to everything else.
        assertThat(advertised).contains("tickets.inbox", "tickets.get", "tickets.search",
                "workbench.preview", "workbench.execute", "search_tools");
        assertThat(advertised).doesNotContain("rules.automate", "decisions.lift");
        // Deferred is context economy, not a boundary: the tool is registered and callable,
        // and what stops a call is the gate. A search finds it and it works.
        assertThat(registry.find("rules.automate")).isPresent();
        assertThat(registry.tools().stream().map(Tool::name).toList())
                .contains("rules.automate", "decisions.lift");
    }
}
