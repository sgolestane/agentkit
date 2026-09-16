package dev.agentkit.workbench.chat;

import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.domain.Ticket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An ALM in a map, holding several tickets.
 *
 * <p>The dashboard module's own fake holds exactly one, which is right for a test about one
 * run working one ticket and wrong for every test here: an inbox, a search and a bulk
 * execution are all about the second ticket and the third.
 *
 * <p>It records every write, which is how the central claim of this surface is checked — that
 * a console tool other than the operator's own two never reaches Jira at all.
 */
final class ConsoleAlm implements Alm {

    final List<String> commentsWritten = new CopyOnWriteArrayList<>();
    final List<String> transitionsRun = new CopyOnWriteArrayList<>();
    final List<String> assignments = new CopyOnWriteArrayList<>();

    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private final Map<String, List<Ticket.Comment>> comments = new LinkedHashMap<>();

    private List<String> transitions = List.of("In Progress", "Done");

    ConsoleAlm(Ticket... seeded) {
        for (Ticket ticket : seeded) {
            tickets.put(ticket.key(), ticket);
        }
    }

    static Ticket open(String key, String summary, String description) {
        return open(key, summary, description, Instant.parse("2026-08-27T10:00:00Z"));
    }

    static Ticket open(String key, String summary, String description, Instant updatedAt) {
        return new Ticket(key, "jira", summary, description, "Open", Ticket.Category.OPEN,
                "Medium", null, "Riley Chen", Instant.parse("2026-08-27T09:00:00Z"), updatedAt);
    }

    /** Replaces a ticket, as a requester editing it would. */
    void update(Ticket ticket) {
        tickets.put(ticket.key(), ticket);
    }

    void seedComment(String key, String author, String body) {
        comments.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new Ticket.Comment(author, body, Instant.parse("2026-08-27T11:00:00Z")));
    }

    void offerTransitions(String... names) {
        transitions = List.of(names);
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
        return tickets.values().stream()
                .filter(ticket -> ticket.statusCategory() != Ticket.Category.DONE)
                .limit(limit)
                .toList();
    }

    @Override
    public List<Ticket> search(String text, int limit) {
        String needle = text.toLowerCase(java.util.Locale.ROOT);
        return tickets.values().stream()
                .filter(ticket -> (ticket.summary() + " " + ticket.description())
                        .toLowerCase(java.util.Locale.ROOT).contains(needle))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<Ticket> ticket(String key) {
        return Optional.ofNullable(tickets.get(key));
    }

    @Override
    public List<Ticket.Comment> comments(String key) {
        return List.copyOf(comments.getOrDefault(key, List.of()));
    }

    @Override
    public void addComment(String key, String body) {
        commentsWritten.add(key + ": " + body);
        comments.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new Ticket.Comment("Workbench Integration", body, Instant.now()));
    }

    @Override
    public List<Transition> transitions(String key) {
        List<Transition> offered = new ArrayList<>();
        for (int i = 0; i < transitions.size(); i++) {
            offered.add(new Transition("t" + (i + 1), transitions.get(i)));
        }
        return offered;
    }

    @Override
    public void transition(String key, String transitionName) {
        transitionsRun.add(key + ": " + transitionName);
    }

    @Override
    public void assignToMe(String key) {
        assignments.add(key);
    }
}
