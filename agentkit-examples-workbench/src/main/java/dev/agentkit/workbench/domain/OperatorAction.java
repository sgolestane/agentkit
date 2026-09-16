package dev.agentkit.workbench.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Something the human did to a ticket themselves, through the workbench.
 *
 * <p>The half of the workbench the approval queue does not cover. The workbench makes
 * the human agent more productive before anything is automated only if the work they
 * still do by hand happens <em>here</em> — otherwise every ticket the agent cannot finish
 * sends them back to the ALM's own UI, and the workbench is a viewer with an inbox. So the operator can
 * comment and transition from the ticket pane, and those writes go to the system of record
 * like any other.
 *
 * <p>Recorded separately from {@link Run} on purpose. A run is what the agent did under a
 * policy; this is what a person did on their own authority, needing no approval because it
 * is already theirs. Keeping them apart is what lets the trail answer "did the agent do this,
 * or did I?" — the question an operator asks first when a ticket looks wrong, and the one
 * a single merged history cannot answer.
 *
 * @param id        stable identifier
 * @param tenantId  tenant scope
 * @param ticketKey the ticket acted on
 * @param kind      what they did
 * @param detail    the comment they wrote, or the transition they took
 * @param by        who did it
 * @param at        when
 */
public record OperatorAction(String id, String tenantId, String ticketKey, Kind kind,
                             String detail, String by, Instant at) {

    public enum Kind { COMMENT, TRANSITION }

    public OperatorAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ticketKey, "ticketKey");
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail;
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(at, "at");
    }
}
