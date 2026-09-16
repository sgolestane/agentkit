package dev.agentkit.itops.runtime;

import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.store.OpsStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-execution handle everything below the runtime is given.
 *
 * <p>One of these exists per run, and tools are built against it, which is what keeps the
 * tenant id and the execution id out of the model's reach entirely: the agent asks for
 * {@code add_user_to_group(user, group)} and the tool already knows whose tenant it is
 * acting in. A tenant argument the model could set would be a tenant argument the model
 * could be persuaded to set to something else.
 *
 * <p>It also collects the run's <em>evidence</em> — the facts the agent established along
 * the way. That list is what an approval card shows a human, and it is gathered by the tool
 * layer rather than written by the model, so it says what actually came back from the
 * external systems rather than what the agent believes came back.
 */
public final class OpsContext {

    private final String tenantId;
    private final String executionId;
    private final OpsStore store;
    private final List<String> evidence = new CopyOnWriteArrayList<>();
    private final List<String> readings = new CopyOnWriteArrayList<>();
    private final Map<String, Object> facts = new ConcurrentHashMap<>();

    public OpsContext(String tenantId, String executionId, OpsStore store) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.store = Objects.requireNonNull(store, "store");
    }

    public String tenantId() {
        return tenantId;
    }

    public String executionId() {
        return executionId;
    }

    public OpsStore store() {
        return store;
    }

    /** Appends an event to this execution's audit trail. */
    public void event(Execution.Event.Type type, Map<String, Object> detail) {
        store.append(executionId, type, detail);
    }

    /**
     * Records something the run established from an external system.
     *
     * <p>Written by tools, not by the agent. The distinction is the point: "the directory
     * says Bob is terminated" is evidence, and "I have confirmed Bob is terminated" is a
     * claim by the party whose judgement the approval exists to check.
     */
    public void evidence(String statement) {
        evidence.add(statement);
    }

    public List<String> evidence() {
        return List.copyOf(evidence);
    }

    /**
     * Records a third-party document the run read along the way — a ticket body, a comment
     * thread — as distinct from {@link #evidence}.
     *
     * <p>The two channels differ in who wrote the words, and everything downstream turns on
     * that. Evidence is what a system of record returned about the world; a reading is what
     * some person filed, which is the channel an attacker writes. The goal-alignment screen
     * corroborates against evidence and must never see a reading ("content is not
     * authorisation"); the reviewing model is shown readings inside a fence, because "does
     * this action follow from what was asked?" is unanswerable on a chat run whose objective
     * is one sentence naming a ticket — the request being served exists only in the ticket's
     * own words. The reviewer weighs those words; nothing corroborates against them.
     *
     * <p>Raw here, like evidence: every consumer fences it, and fencing at the source would
     * nest fences at the consumer that does.
     */
    public void reading(String document) {
        readings.add(document);
    }

    public List<String> readings() {
        return List.copyOf(readings);
    }

    /** Structured facts the runtime itself needs later, e.g. the ticket under repair. */
    public void fact(String key, Object value) {
        facts.put(key, value);
    }

    public Object fact(String key) {
        return facts.get(key);
    }
}
