package dev.agentkit.host;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rehearsals reported for each organization — a pull request's check, sent by {@code rehearse} — so the admin view
 * shows what a change to an agent did before it was merged.
 */
public interface RehearsalLog {

    /** How many reports a log keeps per organization, at least. */
    int KEPT = 50;

    /** Keeps {@code report}, a {@code rehearse} report, as received at {@code at}. */
    void add(String org, Map<String, Object> report, Instant at);

    /** The organization's reports, newest first, each with {@code receivedAt}; at most {@code limit}. */
    List<Map<String, Object>> recent(String org, int limit);

    /** A log for as long as the process runs, keeping the last {@link #KEPT} reports per organization. */
    static RehearsalLog inMemory() {
        Map<String, Deque<Map<String, Object>>> reports = new ConcurrentHashMap<>();
        return new RehearsalLog() {
            @Override
            public void add(String org, Map<String, Object> report, Instant at) {
                Map<String, Object> kept = new LinkedHashMap<>(report);
                kept.put("receivedAt", at.toString());
                Deque<Map<String, Object>> theirs = reports.computeIfAbsent(org, o -> new ArrayDeque<>());
                synchronized (theirs) {
                    theirs.addFirst(kept);
                    while (theirs.size() > KEPT) {
                        theirs.removeLast();
                    }
                }
            }

            @Override
            public List<Map<String, Object>> recent(String org, int limit) {
                Deque<Map<String, Object>> theirs = reports.get(org);
                if (theirs == null) {
                    return List.of();
                }
                synchronized (theirs) {
                    return new ArrayList<>(theirs).stream().limit(limit).toList();
                }
            }
        };
    }
}
