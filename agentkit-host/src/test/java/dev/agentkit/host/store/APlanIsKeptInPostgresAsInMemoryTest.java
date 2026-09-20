package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.host.plans.PlanBook;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The plans carried out, in Postgres as in memory: newest first, by agent, version and kind of task. */
class APlanIsKeptInPostgresAsInMemoryTest {

    @Test
    void inMemory() {
        check(PlanBook.inMemory(), "acme");
    }

    @Test
    void inPostgres() {
        check(new PostgresPlanBook(TestDatabase.get()), TestDatabase.org());
    }

    private static void check(PlanBook book, String org) {
        PlanBook.Agent v1 = new PlanBook.Agent(org, "onboarding", "v1");
        PlanBook.Agent v2 = new PlanBook.Agent(org, "onboarding", "v2");
        Instant at = Instant.parse("2026-09-18T12:00:00Z");
        book.add(v1, "contractor", new PlanBook.Run(List.of("Order for {{input.name}}."), Map.of("input.name", "Marcus"),
                false, true, at));
        book.add(v1, "engineer", new PlanBook.Run(List.of("Ship to {{input.home_address}}."), Map.of(), false, false,
                at.plusSeconds(1)));
        book.add(v1, "contractor", new PlanBook.Run(List.of("Order for {{input.name}}."), Map.of("input.name", "Ravi"),
                true, true, at.plusSeconds(2)));
        book.add(v2, "contractor", new PlanBook.Run(List.of("Something else."), Map.of(), false, true, at));

        List<PlanBook.Run> recent = book.recent(v1, "contractor", 10);
        assertThat(recent).extracting(r -> r.values().get("input.name")).containsExactly("Ravi", "Marcus");
        assertThat(recent.get(0).reused()).isTrue();
        assertThat(recent.get(0).steps()).containsExactly("Order for {{input.name}}.");
        assertThat(recent.get(0).at()).isEqualTo(at.plusSeconds(2));
        assertThat(book.recent(v1, "contractor", 1)).hasSize(1);
        assertThat(book.count(v1, "contractor")).isEqualTo(2);
        assertThat(book.count(v2, "contractor")).isEqualTo(1);
        assertThat(book.shapes(v1)).containsExactly("contractor", "engineer");
        assertThat(book.recent(v1, "engineer", 10).get(0).clean()).isFalse();
    }
}
