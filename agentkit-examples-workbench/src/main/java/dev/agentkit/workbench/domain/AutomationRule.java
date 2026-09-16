package dev.agentkit.workbench.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * "Next time you see a ticket like this, handle it automatically" — as a durable, auditable
 * rule rather than a preference the model remembers.
 *
 * <p>A rule names a triage {@code category} and is created by a person, normally after they
 * have supervised runs of that category and grown comfortable. While enabled, a run in
 * {@link Run.Mode#AUTO} may perform writes up to {@link Risk#MEDIUM} for tickets triaged
 * into that category without parking; anything graded higher still waits for a person, and
 * disabling the rule restores full supervision. Automation is earned per category through
 * actual usage, never switched on globally.
 *
 * @param id        stable identifier
 * @param tenantId  tenant scope
 * @param category  the triage category this covers
 * @param enabled   whether it is currently in force
 * @param createdBy who turned it on
 * @param createdAt when
 */
public record AutomationRule(String id, String tenantId, String category, boolean enabled,
                             String createdBy, Instant createdAt) {

    public AutomationRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public AutomationRule toggled(boolean on) {
        return new AutomationRule(id, tenantId, category, on, createdBy, createdAt);
    }
}
