package dev.agentkit.workbench.store;

import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.AutomationRule;
import dev.agentkit.workbench.domain.CapabilityGap;
import dev.agentkit.workbench.domain.OperatorAction;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.TriageVerdict;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Everything the workbench remembers about its own work, behind one tenant-scoped facade.
 *
 * <p>In-memory so the demo needs no database; the ALM stays the system of record for
 * tickets, and durable customer knowledge lives in the file-backed lesson book. The shape
 * is the part worth copying — every method takes a {@code tenantId}, and the event log is
 * append-only with a per-run sequence, which is exactly what a database would give you with
 * a serial column.
 */
public final class WorkbenchStore {

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, List<Run.Event>> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> eventSequence = new ConcurrentHashMap<>();
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final Map<String, AutomationRule> rules = new ConcurrentHashMap<>();
    private final Map<String, CapabilityGap> gaps = new ConcurrentHashMap<>();
    private final List<OperatorAction> operatorActions = new CopyOnWriteArrayList<>();
    private final Map<String, TriageEntry> triage = new ConcurrentHashMap<>();
    private final List<Consumer<Run.Event>> listeners = new CopyOnWriteArrayList<>();

    /**
     * A verdict plus what it was computed against: the ticket revision, and a fingerprint
     * of the learned knowledge the triage saw. Both make it stale — a ticket that changed
     * needs re-reading, and knowledge that changed can flip "The agent cannot handle this"
     * to "it can now", which is the workbench's whole learning loop.
     */
    public record TriageEntry(String ticketKey, Instant ticketUpdatedAt, TriageVerdict verdict,
                              Instant triagedAt, int knowledgeFingerprint) {}

    // --- runs ---------------------------------------------------------------------

    public Run createRun(String tenantId, String ticketKey, Run.Mode mode, Run.Trigger trigger,
            String goal) {
        Run run = new Run(Ids.next("run"), tenantId, ticketKey, mode, trigger, goal,
                Run.Status.PENDING, Instant.now(), null, null, null);
        runs.put(run.id(), run);
        return run;
    }

    public Optional<Run> run(String tenantId, String id) {
        return Optional.ofNullable(runs.get(id)).filter(r -> r.tenantId().equals(tenantId));
    }

    public List<Run> runs(String tenantId) {
        return runs.values().stream()
                .filter(r -> r.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(Run::createdAt).reversed())
                .toList();
    }

    public List<Run> runsForTicket(String tenantId, String ticketKey) {
        return runs.values().stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.ticketKey().equals(ticketKey))
                .sorted(Comparator.comparing(Run::createdAt).reversed())
                .toList();
    }

    /** Replaces the stored row. Callers derive the new value from the old one. */
    public Run save(Run run) {
        runs.put(run.id(), run);
        return run;
    }

    // --- events -------------------------------------------------------------------

    /** Appends an event; persist first, notify second, so a listener cannot lose the record. */
    public Run.Event append(String runId, Run.Event.Type type, Map<String, Object> detail) {
        long sequence = eventSequence.computeIfAbsent(runId, k -> new AtomicLong())
                .incrementAndGet();
        Run.Event event = new Run.Event(runId, sequence, type, Instant.now(), detail);
        events.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(event);
        for (Consumer<Run.Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // A subscriber's failure is not the run's failure.
            }
        }
        return event;
    }

    public List<Run.Event> events(String runId) {
        return List.copyOf(events.getOrDefault(runId, List.of()));
    }

    /** Subscribes to every event appended from now on; used by the console's live feed. */
    public AutoCloseable subscribe(Consumer<Run.Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // --- approvals ----------------------------------------------------------------

    public Approval save(Approval approval) {
        approvals.put(approval.id(), approval);
        return approval;
    }

    public Optional<Approval> approval(String tenantId, String id) {
        return Optional.ofNullable(approvals.get(id)).filter(a -> a.tenantId().equals(tenantId));
    }

    public List<Approval> approvals(String tenantId) {
        return approvals.values().stream()
                .filter(a -> a.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(Approval::requestedAt).reversed())
                .toList();
    }

    /** The pending item for a run, if it is parked on one. */
    public Optional<Approval> pendingFor(String runId) {
        return approvals.values().stream()
                .filter(a -> a.runId().equals(runId))
                .filter(a -> a.state() == Approval.State.PENDING)
                .findFirst();
    }

    // --- automation rules ---------------------------------------------------------

    public AutomationRule save(AutomationRule rule) {
        rules.put(rule.id(), rule);
        return rule;
    }

    public Optional<AutomationRule> rule(String tenantId, String id) {
        return Optional.ofNullable(rules.get(id)).filter(r -> r.tenantId().equals(tenantId));
    }

    public List<AutomationRule> rules(String tenantId) {
        return rules.values().stream()
                .filter(r -> r.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(AutomationRule::createdAt).reversed())
                .toList();
    }

    /** Whether an enabled rule covers this triage category. */
    public boolean automated(String tenantId, String category) {
        if (category == null || category.isBlank()) {
            return false;
        }
        return rules.values().stream()
                .anyMatch(r -> r.tenantId().equals(tenantId) && r.enabled()
                        && r.category().equalsIgnoreCase(category));
    }

    // --- what the operator did themselves -----------------------------------------

    public OperatorAction save(OperatorAction action) {
        operatorActions.add(action);
        return action;
    }

    /** What a person did to this ticket by hand, oldest first. */
    public List<OperatorAction> operatorActions(String tenantId, String ticketKey) {
        return operatorActions.stream()
                .filter(action -> action.tenantId().equals(tenantId)
                        && action.ticketKey().equals(ticketKey))
                .sorted(Comparator.comparing(OperatorAction::at))
                .toList();
    }

    // --- capability gaps ----------------------------------------------------------

    public CapabilityGap save(CapabilityGap gap) {
        gaps.put(gap.id(), gap);
        return gap;
    }

    public List<CapabilityGap> gaps(String tenantId) {
        return gaps.values().stream()
                .filter(g -> g.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(CapabilityGap::reportedAt).reversed())
                .toList();
    }

    // --- triage cache -------------------------------------------------------------

    public void saveTriage(String tenantId, TriageEntry entry) {
        triage.put(tenantId + '/' + entry.ticketKey(), entry);
    }

    public Optional<TriageEntry> triage(String tenantId, String ticketKey) {
        return Optional.ofNullable(triage.get(tenantId + '/' + ticketKey));
    }

    /** Every ticket this tenant has a verdict for — what an automation rule's reach is read from. */
    public List<TriageEntry> triageEntries(String tenantId) {
        String prefix = tenantId + '/';
        return triage.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(TriageEntry::ticketKey))
                .toList();
    }

    /** Identifier minting, kept in one place so ids are recognisable in a log. */
    public static final class Ids {
        private static final AtomicLong COUNTER = new AtomicLong();

        private Ids() {
        }

        public static String next(String prefix) {
            return prefix + '-' + Objects.toString(COUNTER.incrementAndGet());
        }
    }
}
