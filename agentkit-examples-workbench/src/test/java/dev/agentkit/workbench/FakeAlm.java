package dev.agentkit.workbench;

import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for the runtime tests — the production path has no in-memory ALM, but the
 * supervisor and workbench are exercised without a network. It records every write so a
 * test can assert what actually reached the "ALM".
 */
final class FakeAlm implements Alm {

    final List<String> commentsWritten = new CopyOnWriteArrayList<>();
    final List<String> transitionsRun = new CopyOnWriteArrayList<>();
    final List<String> assignments = new CopyOnWriteArrayList<>();

    private final Ticket ticket;

    FakeAlm(Ticket ticket) {
        this.ticket = ticket;
    }

    static Ticket ticket(String key, String summary, String description) {
        return new Ticket(key, "jira", summary, description, "Open", Ticket.Category.OPEN,
                "Medium", null, "Riley Chen", Instant.parse("2026-08-27T09:00:00Z"),
                Instant.parse("2026-08-27T10:00:00Z"));
    }

    static Ticket doneTicket(String key, String summary, String description) {
        return new Ticket(key, "jira", summary, description, "Done", Ticket.Category.DONE,
                "Medium", "Someone Else", "Riley Chen",
                Instant.parse("2026-08-27T09:00:00Z"),
                Instant.parse("2026-08-27T10:00:00Z"));
    }

    @Override
    public String name() {
        return "jira";
    }

    @Override
    public Me myself() {
        return new Me("acc-1", "Workbench Integration", "workbench@example.com");
    }

    @Override
    public List<Ticket> inbox(int limit) {
        return List.of(ticket);
    }

    @Override
    public List<Ticket> search(String text, int limit) {
        return List.of(ticket);
    }

    @Override
    public Optional<Ticket> ticket(String key) {
        return ticket.key().equals(key) ? Optional.of(ticket) : Optional.empty();
    }

    @Override
    public List<Ticket.Comment> comments(String key) {
        return List.of();
    }

    @Override
    public void addComment(String key, String body) {
        commentsWritten.add(key + ": " + body);
    }

    @Override
    public List<Transition> transitions(String key) {
        return List.of(new Transition("31", "Done"));
    }

    @Override
    public void transition(String key, String transitionName) {
        transitionsRun.add(key + " -> " + transitionName);
    }

    @Override
    public void assignToMe(String key) {
        assignments.add(key);
    }
}
