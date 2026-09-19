package dev.agentkit.host.routing;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each message in a conversation pinned to no agent went, per organization — for the admin view's account of
 * routing — and the messages a person sent again to another agent than the one they were routed to, which are the
 * cases a {@code routing.yaml} is missing.
 */
public interface RoutingLog {

    /** How many of each a log keeps per organization, at least. */
    int KEPT = 500;

    /**
     * One message routed.
     *
     * @param to     the agent it went to; null when the router answered, asked or offered a form itself
     * @param action {@code agent}, {@code answer}, {@code ask} or {@code form} as the router decided, or {@code chosen}
     *               when the person named the agent
     */
    record Route(Instant at, String tenant, String conversation, String turn, String said, String to, String action,
                 String why, long inputTokens, long outputTokens) {
        public Route {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(action, "action");
            said = said == null ? "" : said;
            why = why == null ? "" : why;
        }
    }

    /** One earlier turn, as a routing case's {@code before} gives it. */
    record Before(String said, String agent, String answer) {
    }

    /**
     * A message the router sent to {@code routedTo} (null for itself) that the person then sent to {@code chosen}.
     *
     * @param id     the log's own, to name it by when it is made a case
     * @param before the turns before it in its conversation, oldest first, at most a few
     */
    record Misroute(String id, Instant at, String tenant, String conversation, String said, String routedTo,
                    String chosen, List<Before> before) {
        public Misroute {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(chosen, "chosen");
            before = before == null ? List.of() : List.copyOf(before);
        }
    }

    void add(String org, Route route);

    /** The organization's routed messages, newest first; at most {@code limit}. */
    List<Route> recent(String org, int limit);

    void addMisroute(String org, Misroute misroute);

    /** The organization's misroutes, newest first; at most {@code limit}. */
    List<Misroute> misroutes(String org, int limit);

    /** A log for as long as the process runs, keeping the last {@link #KEPT} of each per organization. */
    static RoutingLog inMemory() {
        Map<String, Deque<Route>> routes = new ConcurrentHashMap<>();
        Map<String, Deque<Misroute>> misroutes = new ConcurrentHashMap<>();
        return new RoutingLog() {
            @Override
            public void add(String org, Route route) {
                keep(routes.computeIfAbsent(org, o -> new ArrayDeque<>()), route);
            }

            @Override
            public List<Route> recent(String org, int limit) {
                return read(routes.get(org), limit);
            }

            @Override
            public void addMisroute(String org, Misroute misroute) {
                keep(misroutes.computeIfAbsent(org, o -> new ArrayDeque<>()), misroute);
            }

            @Override
            public List<Misroute> misroutes(String org, int limit) {
                return read(misroutes.get(org), limit);
            }

            private <T> void keep(Deque<T> theirs, T one) {
                synchronized (theirs) {
                    theirs.addFirst(one);
                    while (theirs.size() > KEPT) {
                        theirs.removeLast();
                    }
                }
            }

            private <T> List<T> read(Deque<T> theirs, int limit) {
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
