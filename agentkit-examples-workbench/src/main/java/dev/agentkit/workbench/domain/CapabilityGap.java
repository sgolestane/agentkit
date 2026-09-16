package dev.agentkit.workbench.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A capability the agent needed and did not have, reported from the middle of real work.
 *
 * <p>This is the workbench's discovery loop: instead of guessing every integration during
 * onboarding, the product records what an actual ticket actually needed — with the ticket
 * attached — and the backlog writes itself.
 *
 * @param id          stable identifier
 * @param tenantId    tenant scope
 * @param runId       the run that hit the gap
 * @param ticketKey   the ticket that needed it
 * @param capability  the missing capability's short name, e.g. {@code okta.group_membership}
 * @param description what was needed, in the agent's words (somebody else's text — escape
 *                    where rendered)
 * @param reportedAt  when
 */
public record CapabilityGap(String id, String tenantId, String runId, String ticketKey,
                            String capability, String description, Instant reportedAt) {

    public CapabilityGap {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ticketKey, "ticketKey");
        Objects.requireNonNull(capability, "capability");
        description = description == null ? "" : description;
        Objects.requireNonNull(reportedAt, "reportedAt");
    }
}
