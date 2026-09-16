package dev.agentkit.workbench.runtime;

import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The earned end state: while at least one automation rule is enabled, sweep the inbox,
 * triage what changed, and run the tickets a rule covers — automatically, in
 * {@link Run.Mode#AUTO}, with everything above {@code MEDIUM} still parking for a person.
 *
 * <p>Deliberately inert until a person enables a rule: automation is opted into per ticket
 * category, never on by default. A ticket is attempted at most once per revision — a new
 * run happens only when the ALM shows the ticket updated since the last one, so a run that
 * parked does not respawn every sweep.
 */
public final class AutoPilot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AutoPilot.class);

    private final WorkbenchStore store;
    private final Alm alm;
    private final Triage triage;
    private final Workbench workbench;
    private final String tenantId;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "workbench-autopilot");
                thread.setDaemon(true);
                return thread;
            });

    public AutoPilot(WorkbenchStore store, Alm alm, Triage triage, Workbench workbench,
            String tenantId) {
        this.store = Objects.requireNonNull(store, "store");
        this.alm = Objects.requireNonNull(alm, "alm");
        this.triage = Objects.requireNonNull(triage, "triage");
        this.workbench = Objects.requireNonNull(workbench, "workbench");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    }

    public void start(Duration interval) {
        scheduler.scheduleWithFixedDelay(this::sweepSafely, interval.toSeconds(),
                interval.toSeconds(), TimeUnit.SECONDS);
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException failure) {
            log.warn("Autopilot sweep failed; will try again next interval", failure);
        }
    }

    /** One sweep; public so a caller (or test) can trigger it directly. */
    public int sweep() {
        if (store.rules(tenantId).stream().noneMatch(rule -> rule.enabled())) {
            return 0;
        }
        int started = 0;
        for (Ticket ticket : alm.inbox(25)) {
            if (ticket.statusCategory() == Ticket.Category.DONE) {
                continue;
            }
            var entry = triage.triage(ticket).orElse(null);
            if (entry == null || !entry.verdict().canHandle()
                    || !store.automated(tenantId, entry.verdict().category())) {
                continue;
            }
            if (alreadyAttempted(ticket)) {
                continue;
            }
            log.info("Autopilot picking up {} ({})", ticket.key(), entry.verdict().category());
            workbench.execute(ticket.key(), Run.Trigger.RULE);
            started++;
        }
        return started;
    }

    /** Whether a run already covers this revision of the ticket. */
    private boolean alreadyAttempted(Ticket ticket) {
        return store.runsForTicket(tenantId, ticket.key()).stream()
                .anyMatch(run -> run.mode() != Run.Mode.PREVIEW
                        && run.createdAt().isAfter(ticket.updatedAt()));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
