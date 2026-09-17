package dev.agentkit.core.deferred;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What the scheduler checks before it stores a goal, for subjects whose record holds dates (a worker's
 * termination date) and times (a grant's expiry) alike.
 */
class DeferredActionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T15:00:00Z");

    private final SubjectRecord worker = new SubjectRecord("worker", "W-1002", Set.of("W-1002", "marcus@example.com"),
            Set.of("lena@example.com"), Map.of("termination_date", "2026-12-31", "office", "New York"));
    private final SubjectRecord grant = new SubjectRecord("grant", "GR-1", Set.of("GR-1"), Set.of(),
            Map.of("expires_at", "2026-09-16T17:00:00Z"));

    private final SubjectResolver resolver = new SubjectResolver() {
        @Override
        public Set<String> kinds() {
            return Set.of("worker", "grant");
        }

        @Override
        public Optional<SubjectRecord> resolve(String kind, String id) {
            return Stream.of(worker, grant).filter(s -> s.kind().equals(kind) && s.id().equals(id)).findFirst();
        }
    };

    private final DeferredActionStore store = DeferredActionStore.inMemory();
    private final DeferredActionScheduler scheduler = new DeferredActionScheduler(resolver, store, () -> NOW,
            subject -> subject.kind().equals("grant") ? List.of("GR-1") : List.of());

    private ToolResult schedule(Object... args) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            arguments.put((String) args[i], args[i + 1]);
        }
        return scheduler.tool("dana@example.com").execute(new ToolInvocation("t", DeferredActionScheduler.TOOL_NAME, arguments));
    }

    @Test
    void relativeToADateFieldInDays() {
        ToolResult result = schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "Remind lena.",
                "relative_to", "termination_date", "offset_days", -14);

        assertThat(result.isError()).as(result.content()).isFalse();
        assertThat(result.content()).contains("14 days before termination_date");
        assertThat(store.get("worker_W-1002_202612170000")).hasValueSatisfying(a -> {
            assertThat(a.runAt()).isEqualTo(Instant.parse("2026-12-17T00:00:00Z"));
            assertThat(a.scheduledBy()).isEqualTo("dana@example.com");
            assertThat(a.scheduledAt()).isEqualTo(NOW);
        });
    }

    @Test
    void relativeToATimeFieldInMinutesAndDaysTogether() {
        assertThat(schedule("subject_kind", "grant", "subject_id", "GR-1", "goal", "Remind about GR-1.",
                "relative_to", "expires_at", "offset_minutes", -15).content()).contains("15 minutes before expires_at");
        assertThat(schedule("subject_kind", "grant", "subject_id", "GR-1", "goal", "Revoke GR-1.",
                "relative_to", "expires_at", "offset_days", 1, "offset_minutes", 30).content())
                .contains("1 day 30 minutes after expires_at");

        assertThat(store.all()).extracting(DeferredAction::runAt)
                .containsExactly(Instant.parse("2026-09-16T16:45:00Z"), Instant.parse("2026-09-17T17:30:00Z"));
    }

    @Test
    void anExplicitTimeMayBeADateOrAnInstant() {
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x", "run_at", "2026-12-31")
                .isError()).isFalse();
        assertThat(schedule("subject_kind", "grant", "subject_id", "GR-1", "goal", "x", "run_at", "2026-09-16T18:00:00Z")
                .isError()).isFalse();
        assertThat(store.all()).extracting(DeferredAction::runAt)
                .containsExactly(Instant.parse("2026-09-16T18:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));
    }

    @Test
    void whatCodeCanCheckIsChecked() {
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-9", "goal", "x", "run_at", "2026-12-31").content())
                .contains("no worker with id W-9");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", " ", "run_at", "2026-12-31").content())
                .contains("goal is required");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x".repeat(4_001),
                "run_at", "2026-12-31").content()).contains("limit is 4000");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x").content())
                .contains("exactly one of run_at or relative_to");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x", "run_at", "2026-12-31",
                "relative_to", "termination_date").content()).contains("exactly one of run_at or relative_to");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x", "run_at", "12/31/2026").content())
                .contains("run_at must be");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x", "relative_to", "start_date")
                .content()).contains("no date or time field start_date").contains("termination_date");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x", "relative_to", "termination_date",
                "offset_days", "two").content()).contains("whole numbers");
        assertThat(schedule("subject_kind", "worker", "subject_id", "W-1002", "goal", "x", "run_at", "2026-09-16").content())
                .contains("is not after now");
        assertThat(store.all()).isEmpty();
    }

    @Test
    void theSameSubjectAndMinuteReplacesAndTheResultSaysWhatTheGoalDoesNotName() {
        ToolResult vague = schedule("subject_kind", "grant", "subject_id", "GR-1", "goal", "Take the access away.",
                "relative_to", "expires_at");
        ToolResult named = schedule("subject_kind", "grant", "subject_id", "GR-1", "goal", "Revoke GR-1.",
                "relative_to", "expires_at");

        assertThat(vague.content()).contains("scheduled").contains("does not name: [GR-1]");
        assertThat(named.content()).contains("replaced").contains("the goal names all of it");
        assertThat(store.all()).singleElement().satisfies(a -> assertThat(a.goal()).isEqualTo("Revoke GR-1."));
    }
}
