package dev.agentkit.workbench.tools;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.connector.AlmException;
import dev.agentkit.workbench.domain.Risk;
import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.runtime.RunContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ticketing capability over the live ALM, in the workbench's vocabulary.
 *
 * <p>Every read hands the model text that <em>someone outside this process wrote</em> —
 * anybody who can file a Jira ticket can put a sentence in the description, and that
 * description is read by an agent holding real credentials. So bodies, summaries and
 * comments go back fenced as {@link Spotlight.Kind#EVIDENCE}: the model is told to weigh
 * what the ticket says, not to do what it says.
 *
 * <p>The identity used for writes is the signed-in integration identity, fixed at
 * connection time — a model that could choose an author could sign a comment as somebody
 * else.
 */
public final class AlmTools {

    private static final Logger LOG = LoggerFactory.getLogger(AlmTools.class);

    /** Characters of ticket text the model is shown, per document, per turn. */
    private static final int MAX_TICKET_CHARS = 20_000;

    private static final String CAP_READ = "ticketing.read";
    private static final String CAP_WRITE = "ticketing.write";

    private AlmTools() {
    }

    /** What the supervisor is told about these tools, independent of who built them. */
    public static List<ToolPolicy> policies() {
        return List.of(
                ToolPolicy.read("jira.search_tickets", CAP_READ, "jira"),
                ToolPolicy.read("jira.get_ticket", CAP_READ, "jira"),
                ToolPolicy.read("jira.get_comments", CAP_READ, "jira"),
                ToolPolicy.read("jira.list_transitions", CAP_READ, "jira"),
                // A comment cannot be unsaid, which is why it is not filed reversible even
                // though it is low risk.
                ToolPolicy.write("jira.add_comment", CAP_WRITE, "jira", Risk.LOW, false, false),
                // Claiming is a real change to a shared queue, trivially undone, safe to repeat.
                ToolPolicy.write("jira.assign_to_me", CAP_WRITE, "jira", Risk.MEDIUM, true, true),
                ToolPolicy.write("jira.transition_ticket", CAP_WRITE, "jira", Risk.MEDIUM,
                        true, true));
    }

    public static List<Tool> of(Alm alm, RunContext context) {
        List<Tool> tools = new ArrayList<>();

        tools.add(FunctionTool.builder("jira.search_tickets",
                        "capability: ticketing.read. Find tickets by free text, or list the "
                                + "inbox when no query is given. Use this to discover work.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "Free text to match; omit to list the inbox."),
                                "limit", Map.of("type", "integer", "description", "Maximum results.")),
                        "required", List.of()))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> reading(() -> {
                    int limit = intArgument(invocation, "limit", 10);
                    String query = invocation.stringArgument("query");
                    List<Ticket> found = query == null || query.isBlank()
                            ? alm.inbox(limit)
                            : alm.search(query, limit);
                    if (found.isEmpty()) {
                        return ToolResult.ok("No tickets matched.");
                    }
                    // One fence per ticket, labelled with a constant: a ticket body that
                    // mimics the next entry's header must not be able to forge an entry.
                    StringBuilder sb = new StringBuilder(found.size() + " ticket(s):");
                    int position = 0;
                    for (Ticket ticket : found) {
                        sb.append("\n\n").append(++position).append(". [")
                                .append(ticket.statusCategory()).append("]\n")
                                .append(fence(ticket));
                    }
                    return ToolResult.ok(sb.toString());
                }))
                .build());

        tools.add(FunctionTool.builder("jira.get_ticket",
                        "capability: ticketing.read. Retrieve one ticket in full, including its "
                                + "description and current assignment.")
                .schema(keySchema("The ticket key, e.g. IT-421."))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> reading(() -> withTicket(alm, invocation, ticket -> {
                    context.fact("ticket", ticket.key());
                    // A reading, not evidence: the request being served exists only in the
                    // requester's own words, which nothing corroborates against.
                    context.reading(Cut.to(ticket.asPromptText(), MAX_TICKET_CHARS));
                    return ToolResult.ok(fence(ticket));
                })))
                .build());

        tools.add(FunctionTool.builder("jira.get_comments",
                        "capability: ticketing.read. Retrieve the comments on a ticket.")
                .schema(keySchema("The ticket key."))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> reading(() -> {
                    String key = required(invocation);
                    if (key == null) {
                        return ToolResult.error("The 'ticket_key' argument is required.");
                    }
                    List<Ticket.Comment> comments = alm.comments(key);
                    if (comments.isEmpty()) {
                        return ToolResult.ok("That ticket has no comments.");
                    }
                    StringBuilder sb = new StringBuilder("Comments on that ticket:");
                    for (Ticket.Comment comment : comments) {
                        sb.append("\n\nat ").append(comment.at()).append('\n')
                                .append(fencedComment(comment));
                        context.reading(Cut.to("author: " + comment.author() + "\n"
                                + comment.body(), MAX_TICKET_CHARS));
                    }
                    return ToolResult.ok(sb.toString());
                }))
                .build());

        tools.add(FunctionTool.builder("jira.list_transitions",
                        "capability: ticketing.read. List the workflow transitions Jira "
                                + "currently allows for a ticket, by name.")
                .schema(keySchema("The ticket key."))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> reading(() -> {
                    String key = required(invocation);
                    if (key == null) {
                        return ToolResult.error("The 'ticket_key' argument is required.");
                    }
                    List<Alm.Transition> transitions = alm.transitions(key);
                    if (transitions.isEmpty()) {
                        return ToolResult.ok("Jira offers no transitions for that ticket right now.");
                    }
                    StringBuilder sb = new StringBuilder("Available transitions:");
                    for (Alm.Transition transition : transitions) {
                        sb.append("\n- ").append(Spotlight.name(transition.toName()));
                    }
                    return ToolResult.ok(sb.toString());
                }))
                .build());

        tools.add(FunctionTool.builder("jira.assign_to_me",
                        "capability: ticketing.write. Take ownership of a ticket by assigning "
                                + "it to the signed-in identity. Do this before changing anything.")
                .schema(keySchema("The ticket key."))
                .provenance(Provenance.FIRST_PARTY)
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> writing(invocation, key -> {
                    alm.assignToMe(key);
                    context.evidence("Ticket " + key + " was assigned to the signed-in identity.");
                    return ToolResult.ok("Assigned to the signed-in identity.");
                }))
                .build());

        tools.add(FunctionTool.builder("jira.add_comment",
                        "capability: ticketing.write. Add a comment describing what was done "
                                + "and why, so the requester and the next engineer can read it.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "ticket_key", Map.of("type", "string", "description", "The ticket."),
                                "body", Map.of("type", "string", "description", "The comment.")),
                        "required", List.of("ticket_key", "body")))
                .provenance(Provenance.FIRST_PARTY)
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> writing(invocation, key -> {
                    String body = invocation.stringArgument("body");
                    if (body == null || body.isBlank()) {
                        return ToolResult.error("A comment needs a 'body'.");
                    }
                    alm.addComment(key, body);
                    context.evidence("A comment was added to " + key + ".");
                    return ToolResult.ok("Comment added to that ticket.");
                }))
                .build());

        tools.add(FunctionTool.builder("jira.transition_ticket",
                        "capability: ticketing.write. Move a ticket through a named workflow "
                                + "transition, e.g. 'In Progress' or 'Done'. List the "
                                + "transitions first; Jira only allows some from each status.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "ticket_key", Map.of("type", "string", "description", "The ticket."),
                                "transition", Map.of("type", "string",
                                        "description", "The transition name, exactly as listed.")),
                        "required", List.of("ticket_key", "transition")))
                .provenance(Provenance.FIRST_PARTY)
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> writing(invocation, key -> {
                    String transition = invocation.stringArgument("transition");
                    if (transition == null || transition.isBlank()) {
                        return ToolResult.error("A 'transition' name is required.");
                    }
                    alm.transition(key, transition);
                    context.evidence("Ticket " + key + " was transitioned via '"
                            + transition + "'.");
                    return ToolResult.ok("Transitioned.");
                }))
                .build());

        return tools;
    }

    // --- shared handling ----------------------------------------------------------

    /** Runs a read, turning provider faults into results the model can act on. */
    private static ToolResult reading(Supplier<ToolResult> read) {
        try {
            return read.get();
        } catch (AlmException fault) {
            return ToolResult.error(bounded(fault));
        }
    }

    /** Runs a write, requiring the key and normalising "no such ticket". */
    private static ToolResult writing(ToolInvocation invocation,
            java.util.function.Function<String, ToolResult> write) {
        String key = required(invocation);
        if (key == null) {
            return ToolResult.error("The 'ticket_key' argument is required.");
        }
        try {
            return write.apply(key);
        } catch (AlmException fault) {
            if (fault.notFound()) {
                return ToolResult.error("No ticket exists with key "
                        + Quoted.distinguishably(key, 120)
                        + ". Nothing was changed. Check the key, or search first.");
            }
            return ToolResult.error(bounded(fault));
        }
    }

    /** The provider's message, bounded — it routinely quotes somebody else's ticket text. */
    private static String bounded(AlmException fault) {
        return Cut.to(dev.agentkit.core.util.OneLine.of(String.valueOf(fault.getMessage())), 400);
    }

    private static String required(ToolInvocation invocation) {
        String key = invocation.stringArgument("ticket_key");
        return key == null || key.isBlank() ? null : key.strip();
    }

    /** The ticket as the model may read it: our framing outside, their words inside. */
    public static String fence(Ticket ticket) {
        Spotlight.Bounded fenced = Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE,
                Source.of("ticket"), ticket.asPromptText(), MAX_TICKET_CHARS);
        if (fenced.cut()) {
            LOG.info("A ticket was cut to {} characters before the model saw it",
                    MAX_TICKET_CHARS);
        }
        return fenced.fence();
    }

    private static String fencedComment(Ticket.Comment comment) {
        Spotlight.Bounded fenced = Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE,
                Source.of("ticket-comment"),
                "author: " + comment.author() + "\n" + comment.body(), MAX_TICKET_CHARS);
        if (fenced.cut()) {
            LOG.info("A ticket comment was cut to {} characters before the model saw it",
                    MAX_TICKET_CHARS);
        }
        return fenced.fence();
    }

    private static ToolResult withTicket(Alm alm, ToolInvocation invocation,
            java.util.function.Function<Ticket, ToolResult> body) {
        String key = required(invocation);
        if (key == null) {
            return ToolResult.error("The 'ticket_key' argument is required.");
        }
        return alm.ticket(key)
                .map(body)
                .orElseGet(() -> ToolResult.error("No ticket with that key."));
    }

    static int intArgument(ToolInvocation invocation, String key, int fallback) {
        Object raw = invocation.argument(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(raw.toString().strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static Map<String, Object> keySchema(String description) {
        return Map.of("type", "object",
                "properties", Map.of("ticket_key",
                        Map.of("type", "string", "description", description)),
                "required", List.of("ticket_key"));
    }
}
