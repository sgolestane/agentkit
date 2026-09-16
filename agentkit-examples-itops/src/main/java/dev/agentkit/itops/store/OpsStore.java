package dev.agentkit.itops.store;

import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Artifact;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.TicketProcessingRecord;
import dev.agentkit.itops.domain.ToolInvocationRecord;
import java.time.Instant;
import java.util.ArrayList;
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
 * Everything the platform remembers, behind one tenant-scoped facade.
 *
 * <p>In-memory here so the demo runs with no database. The shape is the part worth copying:
 * every method takes a {@code tenantId} and every collection is keyed by it, because
 * multi-tenancy retro-fitted is multi-tenancy with a hole in it. Moving to Postgres means
 * replacing the bodies — the {@link #claimTicket} contract is written to be exactly one
 * {@code INSERT … ON CONFLICT DO NOTHING}, and the event sequence is a per-execution
 * counter that a database would get from a serial column.
 *
 * <p>Not a general-purpose repository. It exposes the queries this application makes and
 * nothing else, which is why there is no {@code find(Predicate)} — a store that can be
 * asked anything ends up being asked things it cannot index.
 */
public final class OpsStore {

    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    private final Map<String, List<Execution.Event>> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> eventSequence = new ConcurrentHashMap<>();
    private final Map<String, ApprovalRequest> approvals = new ConcurrentHashMap<>();
    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();
    private final Map<String, TicketProcessingRecord> processing = new ConcurrentHashMap<>();
    private final List<ToolInvocationRecord> invocations = new CopyOnWriteArrayList<>();
    private final List<Consumer<Execution.Event>> listeners = new CopyOnWriteArrayList<>();

    // --- executions ---------------------------------------------------------------

    public Execution createExecution(String tenantId, String agentId, Execution.Trigger trigger,
            String triggerReference, String goal) {
        Execution execution = new Execution(Ids.next("exec"), tenantId, agentId, trigger,
                triggerReference, goal, Execution.Status.PENDING, Instant.now(), null, null, null);
        executions.put(execution.id(), execution);
        return execution;
    }

    public Optional<Execution> execution(String tenantId, String id) {
        return Optional.ofNullable(executions.get(id)).filter(e -> e.tenantId().equals(tenantId));
    }

    public List<Execution> executions(String tenantId) {
        return executions.values().stream()
                .filter(e -> e.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(Execution::createdAt).reversed())
                .toList();
    }

    /** Replaces the stored row. Callers derive the new value from the old one. */
    public Execution save(Execution execution) {
        executions.put(execution.id(), execution);
        return execution;
    }

    // --- events -------------------------------------------------------------------

    /**
     * Appends an event and hands it to the live listeners.
     *
     * <p>Persist first, notify second: a listener that throws — a disconnected browser, say
     * — must not be able to lose the audit record, which is the one thing here that has to
     * survive everything else going wrong.
     */
    public Execution.Event append(String executionId, Execution.Event.Type type,
            Map<String, Object> detail) {
        long sequence = eventSequence.computeIfAbsent(executionId, k -> new AtomicLong())
                .incrementAndGet();
        Execution.Event event = new Execution.Event(executionId, sequence, type, Instant.now(),
                detail);
        events.computeIfAbsent(executionId, k -> new CopyOnWriteArrayList<>()).add(event);
        for (Consumer<Execution.Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // A subscriber's failure is not the execution's failure.
            }
        }
        return event;
    }

    public List<Execution.Event> events(String executionId) {
        return List.copyOf(events.getOrDefault(executionId, List.of()));
    }

    /** Subscribes to every event appended from now on; used by the UI's event stream. */
    public AutoCloseable subscribe(Consumer<Execution.Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // --- ticket processing --------------------------------------------------------

    /**
     * Claims processing rights for a ticket, or reports that someone else holds them.
     *
     * <p>The whole deduplication story is this one method being atomic. Two schedulers that
     * both see {@code INC0012345} both call this; one gets an empty {@code Optional} back
     * meaning "you did not get it", and only the winner does any work. Reading first and
     * then inserting would reintroduce the race this exists to close, so there is no
     * {@code exists()} to call beforehand.
     *
     * @return the record if this caller claimed it, empty if it was already claimed
     */
    public Optional<TicketProcessingRecord> claimTicket(String tenantId, String provider,
            String externalTicketId, String executionId) {
        TicketProcessingRecord claim = new TicketProcessingRecord(tenantId, provider,
                externalTicketId, Instant.now(), Instant.now(), executionId,
                TicketProcessingRecord.Status.CLAIMED, null, null);
        String key = tenantId + '/' + claim.key();
        return processing.putIfAbsent(key, claim) == null ? Optional.of(claim) : Optional.empty();
    }

    public Optional<TicketProcessingRecord> processingRecord(String tenantId, String provider,
            String externalTicketId) {
        return Optional.ofNullable(processing.get(tenantId + '/' + provider + '/' + externalTicketId));
    }

    public void finishProcessing(TicketProcessingRecord record, TicketProcessingRecord.Status status,
            String result) {
        processing.put(record.tenantId() + '/' + record.key(), record.finished(status, result));
    }

    public List<TicketProcessingRecord> processingRecords(String tenantId) {
        return processing.values().stream().filter(r -> r.tenantId().equals(tenantId)).toList();
    }

    // --- approvals ----------------------------------------------------------------

    public ApprovalRequest save(ApprovalRequest request) {
        approvals.put(request.id(), request);
        return request;
    }

    public Optional<ApprovalRequest> approval(String tenantId, String id) {
        return Optional.ofNullable(approvals.get(id)).filter(a -> a.tenantId().equals(tenantId));
    }

    public List<ApprovalRequest> approvals(String tenantId) {
        return approvals.values().stream()
                .filter(a -> a.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(ApprovalRequest::requestedAt).reversed())
                .toList();
    }

    /** The pending approval for an execution, if it is parked on one. */
    public Optional<ApprovalRequest> pendingApprovalFor(String executionId) {
        return approvals.values().stream()
                .filter(a -> a.executionId().equals(executionId))
                .filter(a -> a.state() == ApprovalRequest.State.PENDING)
                .findFirst();
    }

    // --- artifacts ----------------------------------------------------------------

    public Artifact save(Artifact artifact) {
        artifacts.put(artifact.id(), artifact);
        return artifact;
    }

    public Optional<Artifact> artifact(String tenantId, String id) {
        return Optional.ofNullable(artifacts.get(id)).filter(a -> a.tenantId().equals(tenantId));
    }

    public List<Artifact> artifacts(String tenantId) {
        return artifacts.values().stream()
                .filter(a -> a.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(Artifact::createdAt).reversed())
                .toList();
    }

    // --- tool invocations ---------------------------------------------------------

    public void record(ToolInvocationRecord record) {
        invocations.add(record);
    }

    public List<ToolInvocationRecord> invocations(String executionId) {
        List<ToolInvocationRecord> matching = new ArrayList<>();
        for (ToolInvocationRecord record : invocations) {
            if (record.executionId().equals(executionId)) {
                matching.add(record);
            }
        }
        return matching;
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
