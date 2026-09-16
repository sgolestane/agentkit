package dev.agentkit.core.supervisor;

import dev.agentkit.core.util.OneLine;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered set of {@link Subagent}s a {@link Supervisor} can delegate to,
 * keyed by name.
 *
 * <p>Insertion order is preserved so the delegation catalog and any advertised
 * tool schema are stable across runs (which keeps prompt caches warm). Names are
 * unique: registering a second subagent under an existing name is rejected
 * rather than silently overwriting a routing target.
 *
 * <h2>A roster may be grown while it is being read (#308)</h2>
 *
 * <p>{@link #add} was always public and always mutating, so growing a roster mid-run was
 * reachable through this class's own front door. Two things made doing it unsafe, and both
 * are now closed:
 *
 * <ul>
 *   <li>{@link SubagentTools#delegateTool} snapshotted the catalog and the {@code subagent}
 *       enum at build time, so a roster grown afterwards advertised a schema that no longer
 *       described what the handler would accept. It now renders from the live roster on
 *       every {@code advertisedSpecs()} call.</li>
 *   <li>This class was a bare {@link LinkedHashMap}. A delegation runs a whole subagent and
 *       nothing says that happens on the agent loop's thread — {@link Subagent#handling}
 *       exists precisely so a caller can put a timeout, an executor or a telemetry span
 *       around one — so a tool handler calling {@code add} while the loop rendered
 *       {@link #catalog()} was an unsynchronised write against a concurrent iteration: a
 *       {@link java.util.ConcurrentModificationException} at best, a torn read at
 *       worst.</li>
 * </ul>
 *
 * <p><strong>Copy-on-write, not a synchronised map.</strong> The map is immutable once
 * published, and {@link #add} replaces it wholesale under a lock only writers take. Readers
 * take no lock and every read sees one whole roster. That is more than a
 * {@code synchronizedMap} would give: it lets {@link #published()} hand a caller
 * <em>one</em> roster to answer several questions from, which is what
 * {@code delegateTool} needs — it renders a catalog and an enum for the same advertised
 * spec, and a version that read the roster twice could advertise a description and a schema
 * describing different rosters, which is the divergence #308 is about wearing different
 * clothes.
 *
 * <p>The cost is that {@code add} is O(n) in the roster's size and allocates a fresh map.
 * Rosters are small, and they are grown by hand at wiring time or by a model one entry at a
 * time, while they are read on every turn of every run; paying on the rare side is the
 * trade. It is the reason {@code CopyOnWriteArrayList} is the right list for an observer
 * registry and the wrong one for a queue.
 *
 * <p><strong>What this does not make safe.</strong> A {@link Subagent} handed to
 * {@code add} is shared as it is; whether <em>it</em> may be run from two threads is
 * {@link Subagent#of(String, String, dev.agentkit.core.agent.Agent)}'s question and not
 * this one. And a roster grown during a run changes what that run advertises, which costs a
 * prompt cache — {@link SubagentTools#delegateTool} states what that costs and why this is
 * the side to pay on.
 */
public final class SubagentRoster {

    /**
     * The published roster: immutable, replaced wholesale, never edited in place.
     *
     * <p>{@code volatile} rather than {@code final}, because the replacement <em>is</em> the
     * mechanism — a reader's next read of this field sees a complete newer map or a complete
     * older one, and nothing in between.
     */
    private volatile Map<String, Subagent> byName = Map.of();

    /**
     * Registers {@code subagent}; returns {@code this} for chaining.
     *
     * <p>Safe to call while another thread is reading, which is what makes a roster a model
     * can grow mid-run possible (#308). Duplicate names are still rejected, and the check
     * and the insert happen under the same lock, so two threads racing the same name cannot
     * both win.
     *
     * <p><strong>A roster grown mid-run re-renders what the model is shown.</strong> From
     * the turn after this returns, {@code delegate} advertises a different description and a
     * different {@code subagent} enum, so the prompt prefix a provider had cached for this
     * run stops being a prefix. That is the intended cost, it is bounded to the turns on
     * which the roster actually changed, and {@link SubagentTools#delegateTool} carries the
     * reasoning rather than leaving a caller to find it.
     *
     * @throws IllegalArgumentException if a subagent of the same name is already registered
     */
    public synchronized SubagentRoster add(Subagent subagent) {
        Objects.requireNonNull(subagent, "subagent");
        if (byName.containsKey(subagent.name())) {
            throw new IllegalArgumentException("Duplicate subagent name: '" + subagent.name() + "'");
        }
        Map<String, Subagent> grown = new LinkedHashMap<>(byName);
        grown.put(subagent.name(), subagent);
        byName = Collections.unmodifiableMap(grown);
        return this;
    }

    /** Looks up a subagent by name. */
    public Optional<Subagent> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** All subagents, in registration order. */
    public List<Subagent> all() {
        return List.copyOf(byName.values());
    }

    /** All subagent names, in registration order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * One roster, immutable, for a caller that has more than one question to ask of it.
     *
     * <p>Package-private because it is an implementation seam, not a feature: what it buys
     * over calling {@link #names()} and {@link #catalog()} in turn is that the answers
     * describe the same roster even when another thread grows it in between. That is
     * exactly {@link SubagentTools#delegateTool}'s need, and it is in this package.
     *
     * <p>The returned map is the published one, so it is unmodifiable and is never edited
     * after publication — holding it pins one version of the roster rather than copying it.
     */
    Map<String, Subagent> published() {
        return byName;
    }

    /**
     * Renders a {@code name: description} catalog, one subagent per line, for
     * inclusion in a supervisor's system prompt.
     */
    public String catalog() {
        return catalogOf(byName.values());
    }

    /**
     * {@link #catalog()} over an already-taken snapshot.
     *
     * <p>Separate so that a caller holding a {@link #published()} map renders the catalog
     * from that map rather than from a second read of the field — one formatting rule, two
     * ways in.
     */
    static String catalogOf(Collection<Subagent> subagents) {
        StringBuilder sb = new StringBuilder();
        for (Subagent subagent : subagents) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(OneLine.of(subagent.name()))
                    .append(": ").append(OneLine.of(subagent.description()));
        }
        return sb.toString();
    }

    public static SubagentRoster of(Subagent... subagents) {
        SubagentRoster roster = new SubagentRoster();
        for (Subagent subagent : subagents) {
            roster.add(subagent);
        }
        return roster;
    }
}
