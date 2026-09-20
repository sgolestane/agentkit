package dev.agentkit.host;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which versions of each organization's repository the host has loaded, and when: what it serves again after a
 * restart. A conversation is pinned to the version it started on ({@link OrgHost}), and a host that forgot the
 * versions it was serving would tell every such conversation to start over.
 */
public interface VersionLog {

    /** Notes that {@code org} was loaded at {@code version}. */
    void loaded(String org, String version, Instant at);

    /** The versions of {@code org} loaded most recently, newest first, at most {@code limit}. */
    List<String> recent(String org, int limit);

    /** A log kept for as long as the process runs, which is to say: nothing survives a restart. */
    static VersionLog inMemory() {
        Map<String, Map<String, Instant>> loads = new ConcurrentHashMap<>();
        return new VersionLog() {
            @Override
            public void loaded(String org, String version, Instant at) {
                loads.computeIfAbsent(org, o -> new ConcurrentHashMap<>()).put(version, at);
            }

            @Override
            public List<String> recent(String org, int limit) {
                List<Map.Entry<String, Instant>> entries = new ArrayList<>(loads.getOrDefault(org, Map.of()).entrySet());
                entries.sort(Map.Entry.<String, Instant>comparingByValue(Comparator.reverseOrder()));
                return entries.stream().limit(limit).map(Map.Entry::getKey).toList();
            }
        };
    }
}
