package dev.agentkit.host.models;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each organization's agents spent on models, by UTC day, agent, model and who paid: the host's account or the
 * organization's own. Budgets are kept against it, so with several host instances it must be shared (Postgres).
 */
public interface UsageLedger {

    /**
     * Who paid for a call.
     *
     * @param hostPaid whether it was on the host's model account rather than the organization's own
     */
    record Key(String org, LocalDate day, String agent, String model, boolean hostPaid) {
        public Key {
            Objects.requireNonNull(org, "org");
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(model, "model");
        }
    }

    /** What one agent spent on one model, paid by one account, over some days. */
    record Row(String agent, String model, boolean hostPaid, Spend spend) {
    }

    /** Adds one call's spend. */
    void add(Key key, Spend spend);

    /** What {@code org} spent from {@code from} to {@code to}, both included; only on the host's account if asked. */
    Spend spent(String org, LocalDate from, LocalDate to, boolean hostPaidOnly);

    /** What {@code org} spent from {@code from} to {@code to}, by agent, model and payer. */
    List<Row> rows(String org, LocalDate from, LocalDate to);

    /** A ledger kept in this process: for one instance, and forgotten on restart. */
    static UsageLedger inMemory() {
        Map<Key, Spend> kept = new ConcurrentHashMap<>();
        return new UsageLedger() {
            @Override
            public void add(Key key, Spend spend) {
                kept.merge(key, spend, Spend::plus);
            }

            @Override
            public Spend spent(String org, LocalDate from, LocalDate to, boolean hostPaidOnly) {
                return kept.entrySet().stream()
                        .filter(e -> in(e.getKey(), org, from, to) && (!hostPaidOnly || e.getKey().hostPaid()))
                        .map(Map.Entry::getValue).reduce(Spend.ZERO, Spend::plus);
            }

            @Override
            public List<Row> rows(String org, LocalDate from, LocalDate to) {
                Map<List<Object>, Spend> grouped = new java.util.LinkedHashMap<>();
                kept.entrySet().stream().filter(e -> in(e.getKey(), org, from, to))
                        .forEach(e -> grouped.merge(List.of(e.getKey().agent(), e.getKey().model(), e.getKey().hostPaid()),
                                e.getValue(), Spend::plus));
                List<Row> rows = new ArrayList<>();
                grouped.forEach((k, spend) -> rows.add(new Row((String) k.get(0), (String) k.get(1), (Boolean) k.get(2), spend)));
                rows.sort(java.util.Comparator.comparing(Row::agent).thenComparing(Row::model));
                return rows;
            }

            private static boolean in(Key key, String org, LocalDate from, LocalDate to) {
                return key.org().equals(org) && !key.day().isBefore(from) && !key.day().isAfter(to);
            }
        };
    }
}
