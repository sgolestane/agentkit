package dev.agentkit.workbench.runtime;

import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The per-run handle everything below the runtime is given.
 *
 * <p>One of these exists per run, and tools are built against it, which is what keeps the
 * tenant and run ids out of the model's reach entirely: the agent asks for
 * {@code jira.add_comment(ticket_key, body)} and the tool already knows whose run it is
 * acting in.
 *
 * <p>It also collects the run's <em>evidence</em> — facts the tool layer established from
 * the systems of record, shown on approval cards — and its <em>readings</em>, the
 * third-party documents (ticket bodies, comments) it read along the way. The two channels
 * differ in who wrote the words, and everything downstream turns on that.
 */
public final class RunContext {

    private final String tenantId;
    private final String runId;
    private final WorkbenchStore store;
    private final List<String> evidence = new CopyOnWriteArrayList<>();
    private final List<String> readings = new CopyOnWriteArrayList<>();
    private final Map<String, Object> facts = new ConcurrentHashMap<>();

    public RunContext(String tenantId, String runId, WorkbenchStore store) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.store = Objects.requireNonNull(store, "store");
    }

    public String tenantId() {
        return tenantId;
    }

    public String runId() {
        return runId;
    }

    public WorkbenchStore store() {
        return store;
    }

    /** Appends an event to this run's audit trail. */
    public void event(Run.Event.Type type, Map<String, Object> detail) {
        store.append(runId, type, detail);
    }

    /** Something the run established from a system of record — written by tools, not the model. */
    public void evidence(String statement) {
        evidence.add(statement);
    }

    public List<String> evidence() {
        return List.copyOf(evidence);
    }

    /** A third-party document the run read — the channel an attacker writes. */
    public void reading(String document) {
        readings.add(document);
    }

    public List<String> readings() {
        return List.copyOf(readings);
    }

    public void fact(String key, Object value) {
        facts.put(key, value);
    }

    public Object fact(String key) {
        return facts.get(key);
    }
}
