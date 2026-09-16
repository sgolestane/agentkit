package dev.agentkit.workbench.runtime;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.util.OneLine;
import dev.agentkit.workbench.domain.Risk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Capabilities a tenant has stopped wanting to be asked about (#331's mirror).
 *
 * <p>The workbench's ladder ends with the operator doing less and less of the repetitive
 * work, and the repetitive work is not only the ticket — it is the decision. Approving
 * {@code jira.assign_to_me} for the twentieth time teaches nobody anything, and a person
 * who must click it anyway stops reading the ones that matter. So a person can say, from
 * the card where they are approving it, "stop asking me about this."
 *
 * <p>Deliberately the same shape as a standing refusal, opposite sign:
 *
 * <ul>
 *   <li><strong>Keyed on the capability</strong>, not the tool. The judgement "taking
 *       ownership of a ticket is not a decision I need to make" is about the family, and
 *       a sibling tool in it is the same judgement.</li>
 *   <li><strong>Scoped to the tenant</strong>, so it is the customer's posture rather than
 *       one console session's. The grant records <em>who</em> made it, which is what a
 *       per-user scope would key on when one is wanted — the record does not need to
 *       change for that, only the lookup.</li>
 *   <li><strong>Durable</strong>, in the same store as the learnings. "I already told you
 *       this" surviving a restart is the whole of what makes it trust rather than a
 *       preference.</li>
 *   <li><strong>Liftable</strong>, from the same console, for the reason a standing
 *       refusal is: a control that can only be granted turns one comfortable afternoon
 *       into permanent policy.</li>
 * </ul>
 *
 * <h2>What it cannot do</h2>
 *
 * <p>It cannot clear a consequential action. {@link Risk#HIGH} and above still park, and
 * that is enforced at the gate rather than here — trusting a capability makes a supervised
 * run behave, <em>for that capability</em>, exactly as an automated one already does, and
 * no more. An argument that escalates a routine call to HIGH therefore still reaches a
 * person even though the capability is cleared, which is the case this ceiling exists for:
 * the grade is what the risk of <em>this call</em> is, and the trust was given to the
 * ordinary case.
 *
 * <p>It also cannot outrank a refusal. The standing-refusal gate is composed ahead of the
 * supervisor and denies, so a capability somebody refused and meant it about stays refused
 * whatever this says — no with a reason beats yes by habit.
 */
public final class StandingApprovals {

    /** Where a tenant's cleared capabilities live, one per line: {@code capability by at}. */
    private static final String PATH = "trusted/%s.md";

    /** Tenants and capabilities are path segments and prompt-free identifiers. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    /** One capability a person stopped wanting to be asked about. */
    public record Granted(String capability, String by, String at) {}

    private final MemoryStore store;

    public StandingApprovals(MemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Stops asking this tenant about {@code capability}.
     *
     * <p>Synchronized for {@code LessonBook}'s reason: this is check-then-write over a
     * single-writer file, and two consoles deciding at once would otherwise lose one.
     */
    public synchronized void grant(String tenantId, String capability, String by) {
        require(tenantId, "tenantId");
        require(capability, "capability");
        List<Granted> held = new ArrayList<>(all(tenantId));
        held.removeIf(granted -> granted.capability().equals(capability));
        held.add(new Granted(capability, OneLine.of(by == null ? "operator" : by),
                Instant.now().toString()));
        write(tenantId, held);
    }

    /** Starts asking again. Returns whether anything was actually cleared. */
    public synchronized boolean revoke(String tenantId, String capability) {
        require(tenantId, "tenantId");
        require(capability, "capability");
        List<Granted> held = new ArrayList<>(all(tenantId));
        if (!held.removeIf(granted -> granted.capability().equals(capability))) {
            return false;
        }
        write(tenantId, held);
        return true;
    }

    /** Whether this tenant has stopped wanting to be asked about {@code capability}. */
    public boolean covers(String tenantId, String capability) {
        return capability != null && all(tenantId).stream()
                .anyMatch(granted -> granted.capability().equals(capability));
    }

    /** Everything this tenant has cleared, oldest first. */
    public List<Granted> all(String tenantId) {
        require(tenantId, "tenantId");
        return store.read(PATH.formatted(tenantId))
                .map(content -> content.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .map(StandingApprovals::parse)
                        .filter(Objects::nonNull)
                        .toList())
                .orElse(List.of());
    }

    private void write(String tenantId, List<Granted> held) {
        StringBuilder content = new StringBuilder();
        for (Granted granted : held) {
            content.append(granted.capability()).append(' ')
                    .append(granted.by()).append(' ')
                    .append(granted.at()).append('\n');
        }
        store.write(PATH.formatted(tenantId), content.toString());
    }

    /** {@code "<capability> <who> <when>"}, or {@code null} for a line that is not one. */
    private static Granted parse(String line) {
        String[] parts = line.split(" ", 3);
        return parts.length == 3 ? new Granted(parts[0], parts[1], parts[2]) : null;
    }

    /**
     * Refuses anything that is not a plain identifier.
     *
     * <p>A capability reaches this from an HTTP body on the revoke path, and it becomes
     * part of a store key — the shape {@code MemoryStore}'s own confinement exists for.
     * Catalog capabilities are literals and pass; anything else is refused rather than
     * normalised, because a caller naming something unrecognisable is a caller mistaken
     * about what they are clearing.
     */
    private static void require(String value, String what) {
        Objects.requireNonNull(value, what);
        if (!SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    what + " must be a plain identifier, was: '" + value + "'");
        }
    }
}
