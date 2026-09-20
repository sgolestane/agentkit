package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.llm.TokenUsage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A turn runs in the instance that began it. When that instance stops, another ends its turns as failed — with a
 * sentence saying so — once it has not been heard from for a minute; not while it is, and never its own.
 */
class ATurnLeftBehindByAStoppedInstanceIsEndedTest {

    @Test
    void anotherInstanceEndsTheTurnsOfOneThatStopped() {
        Database database = TestDatabase.get();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-18T12:00:00Z"));
        Instances a = new Instances(database, "a-" + TestDatabase.org(), now::get);
        Instances b = new Instances(database, "b-" + TestDatabase.org(), now::get);
        PostgresChatStore onA = new PostgresChatStore(database, Clock.system(ZoneOffset.UTC), a.id());
        PostgresChatStore onB = new PostgresChatStore(database, Clock.system(ZoneOffset.UTC), b.id());
        String tenant = TestDatabase.org() + "/priya@acme.example";
        Conversation conversation = onA.create(tenant, "laptop");
        Turn running = onA.begin(tenant, conversation.id(), "My laptop will not boot", List.of());
        onA.markRunning(tenant, conversation.id(), running.id());
        Turn queued = onA.begin(tenant, conversation.id(), "And my monitor", List.of());
        Turn done = onA.begin(tenant, conversation.id(), "Thanks", List.of());
        onA.end(tenant, conversation.id(), done.id(), Turn.State.COMPLETED, "ok", "", TokenUsage.ZERO);
        Turn ownOnB = onB.begin(tenant, conversation.id(), "One more", List.of());
        a.heartbeat();
        b.heartbeat();

        now.set(now.get().plusSeconds(30));
        b.heartbeat();
        b.endLeftBehind(onB);
        assertThat(onB.turn(tenant, conversation.id(), running.id()).orElseThrow().state())
                .as("a is still heard from").isEqualTo(Turn.State.RUNNING);

        now.set(now.get().plus(Instances.GONE_AFTER).plusSeconds(1));
        b.heartbeat();
        b.endLeftBehind(onB);

        for (Turn left : List.of(running, queued)) {
            Turn ended = onB.turn(tenant, conversation.id(), left.id()).orElseThrow();
            assertThat(ended.state()).isEqualTo(Turn.State.FAILED);
            assertThat(ended.detail()).isEqualTo(Instances.LEFT_BEHIND);
        }
        assertThat(onB.turn(tenant, conversation.id(), done.id()).orElseThrow().state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(onB.turn(tenant, conversation.id(), ownOnB.id()).orElseThrow().state())
                .as("b's own turn, while b runs").isEqualTo(Turn.State.QUEUED);
        b.close();
        a.close();
    }
}
