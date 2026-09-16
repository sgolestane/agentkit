package dev.agentkit.core.collab;

import dev.agentkit.core.util.OneLine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered directory of {@link Peer}s that can collaborate, keyed by name, and the
 * one {@link MessageBudget} they all spend from.
 *
 * <p>Insertion order is preserved so the addressable-peer catalog and any
 * advertised tool schema are stable across runs (which keeps prompt caches warm).
 * Names are unique: registering a second peer under an existing name is rejected
 * rather than silently overwriting an addressable target.
 *
 * <h2>The group owns the message budget (#299)</h2>
 *
 * <p>{@link MessagingTools#sendMessageTool(PeerGroup)} reads the cap from here rather than
 * taking one alongside the group, so a group cannot be wired with two of them. That is the
 * whole of the argument; {@link MessageBudget} carries the measurement of what the previous
 * arrangement allowed, and the scope caution that applies to a group reused across runs.
 */
public final class PeerGroup {

    /**
     * The allowance a group gets when none is named.
     *
     * <p>Eight, which is above the six the shipped {@code CollaborationExample} chose for a
     * two-agent exchange and far below anything that would let a conversation run away. The
     * figure is a floor against an exchange that does not end rather than a tuning knob: a
     * deployment that means to allow more says so, with
     * {@link #of(MessageBudget, Peer...)}. There is a default at all because a group with no
     * cap is the one thing this class must not be able to represent — {@code send_message}'s
     * termination guarantee is the cap.
     */
    public static final int DEFAULT_MAX_MESSAGES = 8;

    private final Map<String, Peer> byName = new LinkedHashMap<>();
    private final MessageBudget messageBudget;

    /** A group with {@link #DEFAULT_MAX_MESSAGES} messages to spend between its peers. */
    public PeerGroup() {
        this(MessageBudget.of(DEFAULT_MAX_MESSAGES));
    }

    /** A group whose peers share {@code messageBudget}. */
    public PeerGroup(MessageBudget messageBudget) {
        this.messageBudget = Objects.requireNonNull(messageBudget, "messageBudget");
    }

    /**
     * The single budget every {@code send_message} tool over this group draws from.
     *
     * <p>Exposed so an operator can read {@link MessageBudget#remaining()} and
     * {@link MessageBudget#reset()} it between runs, which is the whole of the mitigation
     * for a group that outlives one run.
     */
    public MessageBudget messageBudget() {
        return messageBudget;
    }

    /** Registers {@code peer}; returns {@code this} for chaining. */
    public PeerGroup add(Peer peer) {
        Objects.requireNonNull(peer, "peer");
        if (byName.putIfAbsent(peer.name(), peer) != null) {
            throw new IllegalArgumentException("Duplicate peer name: '" + peer.name() + "'");
        }
        return this;
    }

    /** Looks up a peer by name. */
    public Optional<Peer> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** All peers, in registration order. */
    public List<Peer> all() {
        return List.copyOf(byName.values());
    }

    /** All peer names, in registration order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Renders a {@code name: description} catalog, one peer per line, for inclusion
     * in an agent's system prompt or a messaging tool description.
     */
    public String catalog() {
        StringBuilder sb = new StringBuilder();
        for (Peer peer : byName.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(OneLine.of(peer.name()))
                    .append(": ").append(OneLine.of(peer.description()));
        }
        return sb.toString();
    }

    /** A group of {@code peers} sharing {@link #DEFAULT_MAX_MESSAGES} messages. */
    public static PeerGroup of(Peer... peers) {
        return of(MessageBudget.of(DEFAULT_MAX_MESSAGES), peers);
    }

    /** A group of {@code peers} sharing {@code budget}. */
    public static PeerGroup of(MessageBudget budget, Peer... peers) {
        PeerGroup group = new PeerGroup(budget);
        for (Peer peer : peers) {
            group.add(peer);
        }
        return group;
    }
}
