package dev.agentkit.itops.connector;

/**
 * The ticketing system has no record under that id.
 *
 * <p>A distinct type rather than an {@code IllegalArgumentException} because the two facts
 * it separates are read by different parties and only one of them is anybody's bug.
 *
 * <h2>What it was, and what that cost</h2>
 *
 * <p>{@code ServiceNowConnector.mutate} threw {@code IllegalArgumentException} for a missing
 * incident, and every ticketing write goes through it — {@code assign_ticket},
 * {@code add_comment}, {@code resolve_ticket} and {@code close_ticket}. So a model naming a
 * ticket that does not exist escaped the tool body, and {@code Agent} reported the call as
 * {@link dev.agentkit.core.tool.Disposition#THREW}.
 *
 * <p>The model is not much worse off: that branch feeds it an error result and the run
 * carries on, which {@code Agent}'s own comment says is "an ordinary failure the model
 * routes around". <strong>The auditor is.</strong> {@code THREW} means, in
 * {@code Disposition}'s words, that a tool body was entered and may have landed half a side
 * effect — and in this module the observer stream <em>is</em> the compliance trail, so a row
 * saying a write may have partly happened when the connector changed nothing is a false
 * statement in the one record a reviewer opens after an incident. It is the same class of
 * defect as #131 and #181: a row that is wrong about what happened.
 *
 * <p>Reaching for a bare {@code catch (RuntimeException)} in the handlers would have made
 * that row right and a different one wrong, by turning a genuine fault in this module into
 * "no such ticket" — which is why this is a type and not a catch-all.
 *
 * <h2>The rule it belongs to</h2>
 *
 * <p>A ticket id arrives as a <em>tool argument</em>: the model chose it and is the only
 * party that can choose another, so it is the party to tell, in an error {@code ToolResult}.
 * That is the split #313 drew for a subagent name, where {@code Spotlight.requireName}
 * throwing is right for wiring and wrong for a name a model wrote, and #166, #196 and #241
 * each closed with. A programming error in this module still throws and still reports
 * {@code THREW}, which is what that disposition is for.
 */
public final class NoSuchTicketException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String ticketId;

    public NoSuchTicketException(String ticketId) {
        // Unquoted here on purpose: this message reaches a log through Quoted.failure, which
        // escapes it, and the sentence the MODEL is shown is built in TicketTools, which
        // quotes the id itself. Escaping twice would show a reader a doubled backslash and
        // teach them the id contained one.
        super("No such ticket: " + ticketId);
        this.ticketId = ticketId;
    }

    /** The id as the caller supplied it — model-written, so quote it before echoing. */
    public String ticketId() {
        return ticketId;
    }
}
