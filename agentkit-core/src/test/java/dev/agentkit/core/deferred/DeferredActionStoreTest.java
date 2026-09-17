package dev.agentkit.core.deferred;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A schedule that does not survive a restart is not a schedule; these are what the directory store keeps. */
class DeferredActionStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-16T15:00:00Z");

    @TempDir
    Path dir;

    private static DeferredAction action(String id, Instant runAt, String goal) {
        return new DeferredAction(id, "grant", "GR-1", runAt, "at expires_at", goal, NOW, "dana@example.com",
                DeferredAction.Status.SCHEDULED, "", null);
    }

    @Test
    void everyFieldSurvivesARestartIncludingMultiLineGoalsAndOutcomes() {
        DeferredActionStore store = DeferredActionStore.inDirectory(dir);
        String goal = "Revoke GR-1.\nThen tell dana@example.com: \"it's gone\" = done\\n";
        store.put(action("grant_GR-1_202609161700", Instant.parse("2026-09-16T17:00:00Z"), goal));
        store.claim("grant_GR-1_202609161700", NOW);
        store.finish("grant_GR-1_202609161700", true, "Revoked.\nTold Dana.", Instant.parse("2026-09-16T17:00:05Z"));

        DeferredAction reloaded = DeferredActionStore.inDirectory(dir).get("grant_GR-1_202609161700").orElseThrow();

        assertThat(reloaded.goal()).isEqualTo(goal);
        assertThat(reloaded.outcome()).isEqualTo("Revoked.\nTold Dana.");
        assertThat(reloaded.status()).isEqualTo(DeferredAction.Status.DONE);
        assertThat(reloaded.runAt()).isEqualTo(Instant.parse("2026-09-16T17:00:00Z"));
        assertThat(reloaded.finishedAt()).isEqualTo(Instant.parse("2026-09-16T17:00:05Z"));
        assertThat(reloaded.scheduledBy()).isEqualTo("dana@example.com");
    }

    @Test
    void anActionThatWasRunningWhenTheProcessStoppedIsScheduledAgain() {
        DeferredActionStore store = DeferredActionStore.inDirectory(dir);
        store.put(action("a", NOW.minusSeconds(60), "x"));
        assertThat(store.claim("a", NOW)).isTrue();
        assertThat(store.claim("a", NOW)).isFalse();

        DeferredActionStore reloaded = DeferredActionStore.inDirectory(dir);
        assertThat(reloaded.get("a")).hasValueSatisfying(a -> assertThat(a.status()).isEqualTo(DeferredAction.Status.SCHEDULED));
        assertThat(reloaded.due(NOW)).extracting(DeferredAction::id).containsExactly("a");
    }

    @Test
    void dueIsScheduledActionsWhoseTimeHasComeEarliestFirst() {
        DeferredActionStore store = DeferredActionStore.inMemory();
        store.put(action("late", NOW.plusSeconds(10), "x"));
        store.put(action("second", NOW.minusSeconds(10), "x"));
        store.put(action("first", NOW.minusSeconds(20), "x"));

        assertThat(store.due(NOW)).extracting(DeferredAction::id).containsExactly("first", "second");
        assertThat(store.put(action("first", NOW.minusSeconds(20), "replaced"))).isTrue();
        assertThat(store.get("first")).hasValueSatisfying(a -> assertThat(a.goal()).isEqualTo("replaced"));
    }

    @Test
    void anIdThatCouldEscapeTheDirectoryIsRefusedAndAnUnreadableFileIsNamed() throws Exception {
        DeferredActionStore store = DeferredActionStore.inDirectory(dir);
        assertThatThrownBy(() -> store.put(action("../escape", NOW, "x"))).isInstanceOf(IllegalArgumentException.class);

        Files.writeString(dir.resolve("broken.properties"), "id=broken\nrunAt=not-a-time\n");
        assertThatThrownBy(() -> DeferredActionStore.inDirectory(dir))
                .hasMessageContaining("broken.properties");
    }

    @Test
    void aRunningActionCannotBeReplacedAndOnlyARunningOneCanBeFinished() {
        DeferredActionStore store = DeferredActionStore.inMemory();
        store.put(action("a", NOW.minusSeconds(60), "old goal"));

        assertThat(store.finish("a", true, "never ran", NOW)).isFalse();
        assertThat(store.claim("a", NOW)).isTrue();
        assertThatThrownBy(() -> store.put(action("a", NOW.minusSeconds(60), "new goal")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.finish("a", true, "done", NOW)).isTrue();
        assertThat(store.finish("a", false, "again", NOW)).isFalse();

        assertThat(store.get("a")).hasValueSatisfying(a -> {
            assertThat(a.goal()).isEqualTo("old goal");
            assertThat(a.status()).isEqualTo(DeferredAction.Status.DONE);
            assertThat(a.outcome()).isEqualTo("done");
        });
    }

    @Test
    void idsThatDifferOnlyInCaseWouldShareAFileOnSomeDisksSoTheSecondIsRefused() {
        DeferredActionStore store = DeferredActionStore.inDirectory(dir);
        store.put(action("grant_ab_1", NOW, "x"));

        assertThatThrownBy(() -> store.put(action("grant_AB_1", NOW, "y")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only in case");
        assertThat(DeferredActionStore.inDirectory(dir).all()).extracting(DeferredAction::goal).containsExactly("x");
    }

    @Test
    void aWriteThatFailsChangesNothing() throws Exception {
        DeferredActionStore store = DeferredActionStore.inDirectory(dir);
        store.put(action("a", NOW.minusSeconds(60), "x"));
        java.util.Set<java.nio.file.attribute.PosixFilePermission> writable = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThatThrownBy(() -> store.claim("a", NOW)).isInstanceOf(java.io.UncheckedIOException.class);
            assertThatThrownBy(() -> store.put(action("b", NOW, "y"))).isInstanceOf(java.io.UncheckedIOException.class);
        } finally {
            Files.setPosixFilePermissions(dir, writable);
        }

        assertThat(store.get("a")).hasValueSatisfying(a -> assertThat(a.status()).isEqualTo(DeferredAction.Status.SCHEDULED));
        assertThat(store.get("b")).isEmpty();
        assertThat(store.due(NOW)).extracting(DeferredAction::id).containsExactly("a");
    }

    @Test
    void aFileWhoseIdIsNotItsNameIsRefusedOnLoad() throws Exception {
        DeferredActionStore.inDirectory(dir).put(action("a", NOW, "x"));
        Files.move(dir.resolve("a.properties"), dir.resolve("b.properties"));

        assertThatThrownBy(() -> DeferredActionStore.inDirectory(dir)).hasMessageContaining("b.properties")
                .hasMessageContaining("does not match its file name");
    }
}
