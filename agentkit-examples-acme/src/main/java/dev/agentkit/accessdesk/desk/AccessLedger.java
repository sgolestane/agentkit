package dev.agentkit.accessdesk.desk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The desk's record of access requests, grants and everything that happened to them, kept in a JSON file
 * rewritten atomically on every change. With no file it is kept in memory.
 */
public final class AccessLedger {

    /** Someone asking for access. */
    public record AccessRequest(String id, String requester, String resourceId, String resourceName, String level,
                                int hours, String justification, String approver, Status status, Instant createdAt,
                                Instant decidedAt, String decidedBy, String note, Integer approvedHours, String grantId) {
        public enum Status { PENDING, APPROVED, DENIED }
    }

    /** Access that was given, until when, and whether it has been taken away. */
    public record Grant(String id, String requestId, String resourceId, String resourceName, String resourceOwner,
                        String email, String level, Instant grantedAt, Instant expiresAt, String approvedBy,
                        Status status, Instant revokedAt, String revokedBy, String revokeReason) {
        public enum Status { ACTIVE, REVOKED }
    }

    /** One thing that happened. */
    public record AuditEvent(Instant at, String actor, String action, String detail, String requestId, String grantId) {
    }

    private record Contents(List<AccessRequest> requests, List<Grant> grants, List<AuditEvent> audit) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path file;
    private final Map<String, AccessRequest> requests = new LinkedHashMap<>();
    private final Map<String, Grant> grants = new LinkedHashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();

    private AccessLedger(Path file) {
        this.file = file;
    }

    /** A ledger kept in {@code file}, loading what it holds; null keeps it in memory. */
    public static AccessLedger open(Path file) {
        AccessLedger ledger = new AccessLedger(file);
        if (file != null && Files.exists(file)) {
            try {
                Contents contents = MAPPER.readValue(file.toFile(), Contents.class);
                contents.requests().forEach(r -> ledger.requests.put(r.id(), r));
                contents.grants().forEach(g -> ledger.grants.put(g.id(), g));
                ledger.audit.addAll(contents.audit());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the access ledger from " + file, e);
            }
        }
        return ledger;
    }

    public synchronized String nextRequestId() {
        return "REQ-" + (1001 + requests.size());
    }

    public synchronized String nextGrantId() {
        return "GR-" + (1001 + grants.size());
    }

    public synchronized void put(AccessRequest request) {
        requests.put(request.id(), request);
        save();
    }

    public synchronized void put(Grant grant) {
        grants.put(grant.id(), grant);
        save();
    }

    public synchronized Optional<AccessRequest> request(String id) {
        return Optional.ofNullable(id == null ? null : requests.get(id.strip().toUpperCase(java.util.Locale.ROOT)));
    }

    public synchronized Optional<Grant> grant(String id) {
        return Optional.ofNullable(id == null ? null : grants.get(id.strip().toUpperCase(java.util.Locale.ROOT)));
    }

    public synchronized List<AccessRequest> requests() {
        return List.copyOf(requests.values());
    }

    public synchronized List<Grant> grants() {
        return List.copyOf(grants.values());
    }

    public synchronized void updateRequest(String id, UnaryOperator<AccessRequest> change) {
        requests.computeIfPresent(id, (k, v) -> change.apply(v));
        save();
    }

    public synchronized void updateGrant(String id, UnaryOperator<Grant> change) {
        grants.computeIfPresent(id, (k, v) -> change.apply(v));
        save();
    }

    public synchronized void record(Instant at, String actor, String action, String detail, String requestId, String grantId) {
        audit.add(new AuditEvent(at, actor, action, detail, requestId, grantId));
        save();
    }

    /** Every event, newest first. */
    public synchronized List<AuditEvent> audit() {
        List<AuditEvent> events = new ArrayList<>(audit);
        events.sort(Comparator.comparing(AuditEvent::at).reversed());
        return events;
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),
                    new Contents(List.copyOf(requests.values()), List.copyOf(grants.values()), List.copyOf(audit)));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the access ledger to " + file, e);
        }
    }
}
