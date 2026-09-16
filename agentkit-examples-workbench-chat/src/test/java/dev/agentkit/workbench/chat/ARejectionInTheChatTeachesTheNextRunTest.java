package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.workbench.domain.Run;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A refusal typed into the conversation teaches the next run, exactly as one clicked on the
 * dashboard does.
 *
 * <p>{@code ARejectionTeachesTheNextRunTest} in the workbench's own module is the ancestor and
 * it drives {@code Workbench} directly. This drives the <em>tools</em> — {@code workbench.execute}
 * and {@code approvals.decide} — because that is the path a person in the chat actually takes,
 * and the interesting question is not whether the workbench still works but whether the
 * console reaches it correctly. A console that dropped the note on the floor would leave the
 * mechanism intact and the feature gone.
 */
class ARejectionInTheChatTeachesTheNextRunTest {

    private static final Map<String, Object> COMMENT =
            ScriptedLlm.args("ticket_key", "IT-2", "body", "Access granted.");

    private Console parked(boolean... ignored) {
        Console console = new Console(new ConsoleAlm(ConsoleAlm.open("IT-2", "Grant access",
                "Please add dana@example.com to the reporting group.")));
        console.llm.proposes("jira.add_comment", COMMENT)
                .proposes("jira.add_comment", COMMENT)
                .says("Refused by standing policy; leaving it for a person.")
                .proposes("jira.add_comment", COMMENT);
        console.say("workbench.execute", "ticket_key", "IT-2");
        return console;
    }

    @Test
    void theReasonAPersonTypedIsRecalledIntoTheNextRun() {
        Console console = parked();
        String approvalId = console.store.approvals(Console.TENANT).getFirst().id();

        console.say("approvals.decide", "approval_id", approvalId, "approved", false,
                "note", "Dana's access request needs her manager's sign-off first.");

        // Durably in the book, keyed by the capability, and marked advisory because nobody
        // asked for it to bind.
        assertThat(console.corrections.recall(Console.TENANT, List.of("ticketing.write")))
                .singleElement()
                .satisfies(correction -> {
                    assertThat(correction.note()).contains("manager's sign-off");
                    assertThat(correction.standing()).isFalse();
                });

        Run next = runAfter(console, () -> console.say("workbench.execute", "ticket_key", "IT-2"));

        // The next run was told, on the audit trail — and advice does not block, so the
        // proposal still parks for a person rather than being refused outright.
        assertThat(console.store.events(next.id()))
                .anyMatch(event -> event.type() == Run.Event.Type.CORRECTIONS_RECALLED);
        assertThat(next.status()).isEqualTo(Run.Status.WAITING_FOR_HUMAN);
        assertThat(console.alm.commentsWritten).isEmpty();
    }

    @Test
    void aRefusalTheyMeantToKeepIsVisibleAsOneAndCanBeLifted() {
        Console console = parked();
        String approvalId = console.store.approvals(Console.TENANT).getFirst().id();

        console.say("approvals.decide", "approval_id", approvalId, "approved", false,
                "note", "We never comment on access requests.", "standing", true);

        // The console can say what is being refused — which is the whole reason a standing
        // refusal is defensible: a wall nobody can see is a wall nobody can take down.
        assertThat(console.say("decisions.refusals"))
                .contains("ticketing.write")
                .contains("never comment on access requests");

        // And it binds: the next run is refused by the gate rather than parking.
        Run bound = runAfter(console, () -> console.say("workbench.execute", "ticket_key", "IT-2"));
        assertThat(bound.status()).isNotEqualTo(Run.Status.WAITING_FOR_HUMAN);
        assertThat(console.alm.commentsWritten).isEmpty();

        assertThat(console.say("decisions.lift", "capability", "ticketing.write"))
                .contains("Lifted 1");

        // Lifting restores exactly the parking behaviour the refusal replaced. Not "allows
        // it" — a lifted refusal is advice again, and advice still stops to ask.
        Run after = runAfter(console, () -> console.say("workbench.execute", "ticket_key", "IT-2"));
        assertThat(after.status()).isEqualTo(Run.Status.WAITING_FOR_HUMAN);
        assertThat(console.alm.commentsWritten).isEmpty();
    }

    /**
     * The run {@code work} started.
     *
     * <p>By difference rather than by position. {@code WorkbenchStore.runs} sorts newest first
     * — so {@code getLast()} is the oldest, which is how the first draft of this asserted
     * against run one and reported that a refusal had taught nothing — and {@code getFirst()}
     * would be right only until two runs share a {@code createdAt}, which at this speed they
     * can.
     */
    private static Run runAfter(Console console, Runnable work) {
        Set<String> before = console.store.runs(Console.TENANT).stream()
                .map(Run::id).collect(java.util.stream.Collectors.toSet());
        work.run();
        return console.store.runs(Console.TENANT).stream()
                .filter(run -> !before.contains(run.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing ran"));
    }

    @Test
    void liftingSomethingNobodyRefusedSaysSoRatherThanReportingSuccess() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-2", "Grant access", "Add someone.")));

        assertThat(console.call("decisions.lift", "capability", "ticketing.write").isError())
                .isTrue();
    }
}
