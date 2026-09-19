package dev.agentkit.accessdesk.desk;

import dev.agentkit.accessdesk.desk.AccessLedger.Grant;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Revokes any grant still active a grace period after it expired.
 *
 * <p>Revocation is normally a deferred action the agent scheduled when the access was granted. That path is
 * written by a model, so it can be missing or fail. Access that outlives its expiry is the one failure this
 * application exists to prevent, so this deterministic check runs after every sweep and closes the gap,
 * recording that it had to. It is a backstop, not the mechanism: the evals check that the agent's own
 * deferred action did the work.
 */
public final class ExpiryBackstop {

    /** How long after expiry a grant may stay active before the backstop takes it away. */
    public static final Duration GRACE = Duration.ofMinutes(5);

    private final AccessLedger ledger;
    private final DeskTools desk;

    /** @param desk the desk's tools acting as {@link DeskTools#DESK} */
    public ExpiryBackstop(AccessLedger ledger, DeskTools desk) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.desk = Objects.requireNonNull(desk, "desk");
    }

    /** Revokes every grant expired more than {@link #GRACE} ago; returns their ids. */
    public List<String> revokeOverdue(Instant now) {
        List<String> revoked = new ArrayList<>();
        for (Grant grant : ledger.grants()) {
            if (grant.status() == Grant.Status.ACTIVE && !grant.expiresAt().plus(GRACE).isAfter(now)) {
                ToolResult result = desk.catalog().entry("revoke_grant").orElseThrow().tool().execute(new ToolInvocation(
                        "backstop-" + grant.id(), "revoke_grant", new java.util.HashMap<>(Map.of("grant_id", grant.id(),
                        "reason", "expired at " + grant.expiresAt() + " and no deferred action had revoked it (backstop)"))));
                if (!result.isError()) {
                    revoked.add(grant.id());
                }
            }
        }
        return revoked;
    }
}
