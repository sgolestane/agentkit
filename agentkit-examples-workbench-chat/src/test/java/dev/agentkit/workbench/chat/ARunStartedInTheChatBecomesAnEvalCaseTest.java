package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.capture.EvalCaptures;
import dev.agentkit.workbench.domain.Run;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conversation that went well is a test case, and it is one press away.
 *
 * <p>{@code ALiveRunBecomesAnEvalCaseTest} is the ancestor and it captures from a run the
 * dashboard started. The loop that matters is: watch the agent work a ticket, notice it did
 * something well or badly, and keep that as a case a change can be replayed against. A console
 * where a person can start the run and not keep it has half the loop.
 *
 * <p>What is really being checked is the joint. {@code EvalCaptures} works — that is the
 * ancestor's job. This is that {@code evals.capture} is wired to it, that a run reached through
 * {@code workbench.execute} is capturable at all, and that the two things the capture refuses are
 * refused as answers rather than as exceptions out of the turn.
 */
class ARunStartedInTheChatBecomesAnEvalCaseTest {

    @TempDir
    Path dir;

    private Console captureable(ConsoleAlm alm) {
        Console console = new Console(alm);
        console.captures = new EvalCaptures(dir, console.store, alm, console.learnings,
                Console.TENANT);
        return console;
    }

    @Test
    void aRunTheChatFinishedCanBeKept() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        alm.seedComment("IT-1", "Riley Chen", "Any progress?");
        Console console = captureable(alm);
        console.llm.says("Nothing needed doing here.");
        console.say("workbench.execute", "ticket_key", "IT-1");
        String runId = console.store.runs(Console.TENANT).getFirst().id();

        String said = console.say("evals.capture", "run_id", runId);

        assertThat(said).contains("case-" + runId + ".json");
        Path written = dir.resolve("case-" + runId + ".json");
        assertThat(Files.exists(written)).isTrue();
        // The case carries the world the run saw, not just its answer — that is what makes a
        // replay a replay rather than a re-run against whatever Jira looks like today.
        assertThat(console.captures.read(runId)).satisfies(one -> {
            assertThat(one.ticketKey()).isEqualTo("IT-1");
            assertThat(one.mode()).isEqualTo(Run.Mode.SUPERVISED);
        });
    }

    @Test
    void aRunThatIsStillGoingIsRefusedAsAnAnswer() {
        Console console = captureable(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        Run running = console.store.createRun(Console.TENANT, "IT-1", Run.Mode.SUPERVISED,
                Run.Trigger.OPERATOR, "work it");

        ToolResult refused = console.call("evals.capture", "run_id", running.id());

        // EvalCaptures raises IllegalArgumentException for a run that is unfinished or ended
        // in a way that teaches a replay nothing. Those are things the model can act on — say
        // so, wait, offer a different run — and an exception out of the handler would end the
        // operator's turn with a stack trace instead.
        assertThat(refused.isError()).isTrue();
        assertThat(refused.content()).isNotBlank().doesNotContain("Exception");
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void aRunThatDoesNotExistIsAnAnswerTooAndNamesWhereToLook() {
        Console console = captureable(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        ToolResult refused = console.call("evals.capture", "run_id", "run-nope");

        assertThat(refused.isError()).isTrue();
        assertThat(refused.content()).isNotBlank();
    }

    @Test
    void aDeploymentWithNowhereToPutOneDoesNotOfferTheTool() {
        // Not present-and-failing. A console with no capture directory has no such tool at
        // all, which is the same shape every other optional collaborator here takes: the
        // model cannot try something this deployment cannot do.
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        List<String> names = ConsoleTools.of(Console.DEPLOYMENT, console.alm, console.workbench,
                        null, console.learnings, console.store, null)
                .stream().map(dev.agentkit.core.tool.Tool::name).toList();

        assertThat(names).doesNotContain("evals.capture");
    }
}
