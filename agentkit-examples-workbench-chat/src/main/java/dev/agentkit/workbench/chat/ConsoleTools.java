package dev.agentkit.workbench.chat;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.View;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.workbench.capture.EvalCaptures;
import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.connector.AlmException;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.AutomationRule;
import dev.agentkit.workbench.domain.CapabilityGap;
import dev.agentkit.workbench.domain.OperatorAction;
import dev.agentkit.workbench.domain.Risk;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.domain.TriageVerdict;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.StandingApprovals;
import dev.agentkit.workbench.runtime.Triage;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import dev.agentkit.workbench.tools.ToolCatalog;
import dev.agentkit.workbench.tools.ToolPolicy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The workbench, as things the console's model can ask for.
 *
 * <h2>A second tier, and it must not be confused with the first</h2>
 *
 * <p>{@link dev.agentkit.workbench.tools.ToolCatalog} already exists and is the <em>agent's</em>
 * tools: what a run may reach <em>while working one ticket</em>, built per run, closing over
 * its {@code RunContext} so the tenant never appears in a schema, with the supervisor's gates
 * and the trust floor around it.
 *
 * <p>What is here is a different thing: the tools the <em>conversation's</em> model calls to
 * drive the workbench on an operator's behalf. It lists the inbox, forms a verdict, starts a
 * preview, toggles a rule — the fifteen-odd things the dashboard puts behind buttons.
 *
 * <p><strong>The distinction is the safety story, not a tidiness preference.</strong> Nothing
 * here reaches Jira on the agent's behalf. A ticket-changing action the <em>agent</em> takes is
 * reached only through {@link Workbench#preview} and {@link Workbench#execute}, which build a
 * run around {@code ToolCatalog}'s registry under {@code ToolCatalog}'s policies — so every
 * gate, every approval and every standing refusal that module enforces applies unchanged.
 * Registering {@code AlmTools}' writers on this surface would route around all of it, and the
 * console would be a second, undefended door into the same system.
 *
 * <p>The two exceptions are deliberate, and they are the <em>operator's own hands</em>:
 * {@code alm.comment} and {@code alm.transition} write as the signed-in identity, with no run
 * and no approval, because that is a person acting rather than an agent acting. The dashboard
 * offers exactly these two for exactly this reason and records them as the operator's; so does
 * this, into the same {@link OperatorAction} log.
 *
 * <h2>Whose words these are</h2>
 *
 * <p>Everything a requester wrote — a summary, a description, a comment, a run's own output
 * quoting all three — comes back fenced as {@link Spotlight.Kind#EVIDENCE}. An operator asking
 * the question does not make the requester's words trustworthy, and the console's model holds
 * the same tools whether it is answering "what is in my inbox" or acting on what it read there.
 */
public final class ConsoleTools {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleTools.class);

    /** Characters of somebody else's text the model is shown, per document, per turn. */
    private static final int MAX_TEXT_CHARS = 20_000;

    /** How many rows a listing hands back before the model should ask something narrower. */
    private static final int PAGE = 50;

    /**
     * How much of somebody else's text goes on a card.
     *
     * <p>Far below {@link #MAX_TEXT_CHARS}, and for a different reason: that one is what the
     * model may read, this one is what a person can take in at a glance. A card carrying a
     * pasted stack trace has stopped being a card. The whole text is in the digest.
     */
    private static final int MAX_CARD_CHARS = 1_200;

    /**
     * The ceiling on one bulk execution.
     *
     * <p>The same 25 the dashboard's bulk endpoint uses, and for the same reason it gives:
     * these are real agent runs against one Jira project, and twenty-five of them is already
     * more than anybody wants to read the first failure out of.
     */
    private static final int MAX_BULK = 25;

    /**
     * How many moments one run's timeline carries, and how much text each may hold.
     *
     * <p>A ceiling rather than a preference. {@link View} refuses a payload over
     * {@link View#MAX_CHARS}, and it refuses it by <em>throwing</em> — so a run with several
     * hundred events would have made {@code runs.get} raise out of its handler rather than
     * answer, which is the failure this whole surface is built not to have. Sized so that the
     * product stays an order of magnitude under the bound.
     */
    private static final int MAX_MOMENTS = 200;

    private static final int MAX_MOMENT_CHARS = 400;

    private static final String CAP_READ = "workbench.read";
    private static final String CAP_TRIAGE = "workbench.triage";
    private static final String CAP_RUN = "workbench.run";
    private static final String CAP_DECIDE = "workbench.decide";
    private static final String CAP_OPERATOR = "workbench.operator";
    private static final String CAP_EVALS = "workbench.evals";

    private ConsoleTools() {
    }

    /**
     * What the console knows about these tools before anyone calls them.
     *
     * <p>Stated as {@link ToolPolicy} rather than in a vocabulary of this module's own, so
     * that the console's surface can be graded, displayed and gated by exactly the machinery
     * that grades the run's — {@code ToolCatalog.policyOrUnknown} answers for a name from
     * either tier, and a supervisor put in front of this surface later needs no new dialect.
     *
     * <p>Reversibility is about the effect, not the ease: a comment cannot be unsaid, so it is
     * irreversible however low its risk. {@code workbench.execute} is {@link Risk#HIGH} because it
     * is the door to everything the run tier can do — it is not that starting a run is
     * dangerous, it is that what a run may do is bounded by the run tier's gates rather than
     * by anything stated here.
     */
    public static List<ToolPolicy> policies() {
        return List.of(
                ToolPolicy.read("tickets.inbox", CAP_READ, "jira"),
                ToolPolicy.read("tickets.get", CAP_READ, "jira"),
                ToolPolicy.read("tickets.search", CAP_READ, "jira"),
                ToolPolicy.read("runs.list", CAP_READ, "workbench"),
                ToolPolicy.read("runs.get", CAP_READ, "workbench"),
                ToolPolicy.read("rules.list", CAP_READ, "workbench"),
                ToolPolicy.read("gaps.list", CAP_READ, "workbench"),
                ToolPolicy.read("learnings.list", CAP_READ, "workbench"),
                ToolPolicy.read("workbench.capabilities", CAP_READ, "workbench"),
                ToolPolicy.read("approvals.list", CAP_READ, "workbench"),
                ToolPolicy.read("decisions.refusals", CAP_READ, "workbench"),
                ToolPolicy.read("decisions.trusted", CAP_READ, "workbench"),

                // A rehearsal changes nothing outside this process by construction — every
                // writing tool inside it is refused — but it spends real model calls and
                // leaves a run in the log, so it is not filed READ.
                ToolPolicy.write("workbench.preview", CAP_RUN, "workbench", Risk.LOW, true, true),
                // Judging is the same shape: it writes only a verdict this deployment owns.
                ToolPolicy.write("triage.ticket", CAP_TRIAGE, "workbench", Risk.LOW, true, true),
                ToolPolicy.write("triage.sweep", CAP_TRIAGE, "workbench", Risk.LOW, true, true),

                // The door to the run tier. Not reversible and not repeatable: what happens
                // inside is a run, and a second run is a second set of comments.
                ToolPolicy.write("workbench.execute", CAP_RUN, "workbench", Risk.HIGH, false, false),
                ToolPolicy.write("workbench.bulk_execute", CAP_RUN, "workbench", Risk.HIGH,
                        false, false),

                // Deciding. Each is undoable by its opposite, and saying the same thing twice
                // says it once.
                ToolPolicy.write("approvals.decide", CAP_DECIDE, "workbench", Risk.HIGH,
                        false, false),
                ToolPolicy.write("approvals.answer", CAP_DECIDE, "workbench", Risk.MEDIUM,
                        false, false),
                ToolPolicy.write("rules.automate", CAP_DECIDE, "workbench", Risk.HIGH, true, true),
                ToolPolicy.write("rules.toggle", CAP_DECIDE, "workbench", Risk.MEDIUM, true, true),
                ToolPolicy.write("decisions.lift", CAP_DECIDE, "workbench", Risk.HIGH, true, true),
                ToolPolicy.write("decisions.revoke", CAP_DECIDE, "workbench", Risk.LOW, true, true),

                // The operator's own hands, straight at the ALM.
                ToolPolicy.write("alm.comment", CAP_OPERATOR, "jira", Risk.LOW, false, false),
                ToolPolicy.write("alm.transition", CAP_OPERATOR, "jira", Risk.MEDIUM, true, true),

                // Filed under its own capability rather than with the reads. It writes a
                // file, and a capability is what a standing refusal and the disclosure
                // search key on — a writer wearing the read family's name is a writer that
                // a refusal of "workbench.read" would stop and a refusal of the writers
                // would not.
                ToolPolicy.write("evals.capture", CAP_EVALS, "workbench", Risk.LOW, true, true));
    }

    /**
     * Who this console is for, and where the tickets it talks about actually live.
     *
     * <p>Three strings that travel together through every tool and were three parameters
     * until a card asked for a link. {@code ticketBaseUrl} is the one that is genuinely new:
     * a ticket card without a way through to the ticket is a card a person reads and then
     * goes and finds the real thing anyway.
     *
     * @param tenantId      every store call is scoped by it, and it never appears in a schema
     * @param operator      who the console's writes are recorded as
     * @param ticketBaseUrl the ALM's own base, e.g. {@code https://acme.atlassian.net}; empty
     *                      in a deployment that has none, in which case cards carry no link
     *                      rather than a broken one
     */
    public record Deployment(String tenantId, String operator, String ticketBaseUrl) {
        public Deployment {
            Objects.requireNonNull(tenantId, "tenantId");
            // Not Spotlight.requireName: an operator identity is routinely an email address,
            // and '@' is not a name character. It is never printed on a line the framework
            // writes — it goes into the store, and the two places a stored identity is read
            // back out (decisions.trusted, and the ALM's own audit trail) test it there.
            Objects.requireNonNull(operator, "operator");
            ticketBaseUrl = ticketBaseUrl == null ? "" : ticketBaseUrl.strip();
        }

        /** Where a person would go to read this ticket, or empty if this console cannot say. */
        String urlFor(String ticketKey) {
            if (ticketBaseUrl.isEmpty() || !Spotlight.isName(ticketKey)) {
                return "";
            }
            String base = ticketBaseUrl.endsWith("/")
                    ? ticketBaseUrl.substring(0, ticketBaseUrl.length() - 1)
                    : ticketBaseUrl;
            return base + "/browse/" + ticketKey;
        }
    }

    /**
     * What the console knows about one tool, with the answer it gives when nobody wrote a
     * policy: the most dangerous grading it has.
     *
     * <p>The same doctrine {@code ToolCatalog.policyOrUnknown} states for the run tier, and it
     * matters here for the same reason — "nobody wrote a policy" and "somebody decided it was
     * harmless" must not resolve the same way. The gate this feeds is the console's, so an
     * ungraded tool arriving on this surface asks a person before it runs rather than running.
     */
    public static ToolPolicy policyOrUnknown(String toolName) {
        return policies().stream()
                .filter(policy -> policy.name().equals(toolName))
                .findFirst()
                .orElseGet(() -> ToolPolicy.write(toolName, ToolCatalog.UNKNOWN_CAPABILITY,
                        "unknown", Risk.HIGH, false, false));
    }

    /**
     * Everything the console can do, ready to hand to an agent.
     *
     * <p>Behind {@link DisclosingToolRegistry}, so the model searches for what it needs
     * instead of carrying two dozen schemas in every turn — the framework's own progressive
     * disclosure, used by its own example. What is available from the first turn is what a
     * conversation opens with: the inbox, one ticket, a search, and the two questions an
     * operator actually starts with — what would you do, and go ahead. Everything else is a
     * search away, and a search away is where {@code ToolCatalog} learned deferral stops
     * paying: at seven tools it cost a resumed run the work it had just been approved for; at
     * twenty-six, with five of them always in front of the model, it pays again.
     *
     * @param workbench may be null in a deployment with no model wired up, in which case the
     *                  tools that need one are simply absent rather than present and failing
     * @param triage    likewise
     * @param captures  likewise — a deployment with no capture directory has no such tool
     */
    public static DisclosingToolRegistry registry(Deployment deployment, Alm alm,
            Workbench workbench, Triage triage, Learnings learnings, WorkbenchStore store,
            EvalCaptures captures) {
        return registryBuilder(deployment, alm, workbench, triage, learnings, store, captures)
                .build();
    }

    /**
     * The same, unbuilt, for a caller with a tool of its own to add.
     *
     * <p>{@code ask_person} is the one that matters: it belongs to the console runtime rather
     * than to the workbench, it needs the runtime that is constructed <em>around</em> these
     * tools, and it has to be always-available — a model that has to search for how to ask a
     * question will answer instead of asking.
     */
    public static DisclosingToolRegistry.Builder registryBuilder(Deployment deployment,
            Alm alm, Workbench workbench, Triage triage, Learnings learnings,
            WorkbenchStore store, EvalCaptures captures) {
        DisclosingToolRegistry.Builder builder = DisclosingToolRegistry.builder();
        List<String> upfront = List.of("tickets.inbox", "tickets.get", "tickets.search",
                "workbench.preview", "workbench.execute");
        for (Tool tool : of(deployment, alm, workbench, triage, learnings, store, captures)) {
            if (upfront.contains(tool.name())) {
                builder.alwaysAvailable(tool);
            } else {
                builder.deferred(tool);
            }
        }
        return builder;
    }

    /** The same tools, unregistered — for a caller assembling its own registry, and for tests. */
    public static List<Tool> of(Deployment deployment, Alm alm, Workbench workbench,
            Triage triage, Learnings learnings, WorkbenchStore store, EvalCaptures captures) {
        Objects.requireNonNull(deployment, "deployment");
        Objects.requireNonNull(alm, "alm");
        Objects.requireNonNull(learnings, "learnings");
        Objects.requireNonNull(store, "store");
        String tenantId = deployment.tenantId();
        String operator = deployment.operator();

        List<Tool> tools = new ArrayList<>(List.of(
                inbox(tenantId, alm, learnings, store),
                ticket(deployment, alm, learnings, store),
                search(alm),
                runs(tenantId, store),
                run(tenantId, store),
                rules(tenantId, store),
                gaps(tenantId, store),
                learnings(learnings),
                catalog(),
                approvals(tenantId, store),
                automate(tenantId, operator, store),
                toggle(tenantId, store),
                comment(tenantId, operator, alm, store),
                transition(tenantId, operator, alm, store)));
        if (triage != null) {
            tools.add(triageOne(alm, triage));
            tools.add(triageSweep(triage));
        }
        if (workbench != null) {
            tools.add(preview(workbench));
            tools.add(execute(workbench));
            tools.add(bulkExecute(workbench));
            tools.add(decide(operator, workbench));
            tools.add(answer(operator, workbench));
            tools.add(refusals(workbench));
            tools.add(lift(workbench));
            tools.add(trusted(workbench));
            tools.add(revoke(workbench));
        }
        if (captures != null) {
            tools.add(capture(captures));
        }
        return List.copyOf(tools);
    }

    // --- reading ------------------------------------------------------------------

    private static Tool inbox(String tenantId, Alm alm, Learnings learnings,
            WorkbenchStore store) {
        return FunctionTool.builder("tickets.inbox",
                        "capability: workbench.read. The open tickets, each with its triage "
                                + "verdict if it has one and whether that verdict is still "
                                + "current. Start here when the operator asks what is waiting.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "limit", Map.of("type", "integer",
                                        "description", "At most " + PAGE + ".")),
                        "required", List.of()))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> reading(() -> {
                    List<Ticket> tickets = alm.inbox(bounded(invocation, "limit", 20));
                    if (tickets.isEmpty()) {
                        return ToolResult.ok("Nothing is open.");
                    }
                    int knowledge = learnings.fingerprint();
                    List<List<Object>> rows = new ArrayList<>();
                    StringBuilder said = new StringBuilder(tickets.size() + " open ticket(s). "
                            + "Summaries are the requester's words:");
                    for (Ticket ticket : tickets) {
                        Optional<WorkbenchStore.TriageEntry> entry =
                                store.triage(tenantId, ticket.key());
                        String verdict = entry
                                .map(one -> verdictWord(one.verdict())
                                        + (stale(one, ticket, knowledge) ? " (stale)" : ""))
                                .orElse("not triaged");
                        // One fence per ticket, labelled with a constant, so a summary that
                        // mimics the next line's shape cannot forge an entry.
                        said.append("\n\n").append(ticket.key()).append(" [")
                                .append(ticket.status()).append("] ").append(verdict)
                                .append('\n').append(fence("ticket-summary", ticket.summary()));
                        rows.add(List.of(ticket.key(), ticket.summary(), ticket.status(),
                                verdict, ticket.assignee() == null ? "" : ticket.assignee(),
                                since(ticket.updatedAt())));
                    }
                    return ToolResult.ok(said.toString())
                            .withView(View.table(View.Column.texts(
                                    "ticket", "summary", "status", "triage", "assignee",
                                    "updated"), rows));
                }))
                .build();
    }

    /**
     * Whether a stored verdict was formed against a world that has since moved.
     *
     * <p>Both halves matter and the second is the learning loop: a ticket that changed needs
     * re-reading, and knowledge that changed can flip "The agent cannot handle this" into "it can
     * now". {@link WorkbenchStore.TriageEntry} records both for exactly this question, and a
     * console that showed a badge without asking it would be showing yesterday's answer with
     * today's confidence.
     */
    private static boolean stale(WorkbenchStore.TriageEntry entry, Ticket ticket, int knowledge) {
        return entry.ticketUpdatedAt().isBefore(ticket.updatedAt())
                || entry.knowledgeFingerprint() != knowledge;
    }

    private static Tool ticket(Deployment deployment, Alm alm, Learnings learnings,
            WorkbenchStore store) {
        return FunctionTool.builder("tickets.get",
                        "capability: workbench.read. One ticket in full: what was filed, its "
                                + "comments, the transitions it will currently accept, every "
                                + "run against it, and anything the operator has already done "
                                + "to it by hand.")
                .schema(keySchema())
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> reading(() -> withTicket(alm, invocation, ticket -> {
                    String tenantId = deployment.tenantId();
                    String key = ticket.key();
                    StringBuilder said = new StringBuilder();
                    said.append(key).append(" [").append(ticket.status()).append("] priority ")
                            .append(ticket.priority()).append(", assigned to ")
                            .append(ticket.assignee() == null ? "nobody" : ticket.assignee())
                            .append(", reported by ")
                            .append(ticket.reporter() == null ? "unknown" : ticket.reporter())
                            .append("\n\nAs filed:\n").append(fence("ticket", ticket.summary()
                                    + "\n\n" + ticket.description()));

                    List<Ticket.Comment> comments = alm.comments(key);
                    said.append("\n\n").append(comments.isEmpty() ? "No comments."
                            : comments.size() + " comment(s):");
                    for (Ticket.Comment one : comments) {
                        said.append("\n\nat ").append(one.at()).append('\n')
                                .append(fence("ticket-comment",
                                        "author: " + one.author() + "\n" + one.body()));
                    }

                    List<String> names = alm.transitions(key).stream()
                            .map(one -> Spotlight.name(one.toName())).toList();
                    said.append("\n\nTransitions it will accept: ")
                            .append(names.isEmpty() ? "none right now" : String.join(", ", names));

                    List<Run> against = store.runsForTicket(tenantId, key);
                    said.append("\nRuns: ").append(against.isEmpty() ? "none" : "");
                    against.forEach(one -> said.append("\n- ").append(one.id()).append(' ')
                            .append(one.mode()).append(' ').append(one.status())
                            .append(" (started by ").append(one.trigger()).append(')'));

                    List<OperatorAction> byHand = store.operatorActions(tenantId, key);
                    said.append("\nWhat the operator did by hand: ")
                            .append(byHand.isEmpty() ? "nothing" : "");
                    byHand.forEach(one -> said.append("\n- ").append(one.by()).append(' ')
                            .append(one.kind()).append(' ').append(one.at()));

                    store.triage(tenantId, key).ifPresent(entry -> {
                        said.append("\nTriage: ").append(verdictWord(entry.verdict()))
                                .append(" — ").append(entry.verdict().plan());
                        if (stale(entry, ticket, learnings.fingerprint())) {
                            // The console has no button on the card, so the nudge is the
                            // digest's: the dashboard re-triages from the badge, and here the
                            // model is told the badge is out of date and what to call.
                            said.append("\nThat verdict is out of date — the ticket or what "
                                    + "the agent knows has changed since. triage.ticket forms a "
                                    + "current one.");
                        }
                    });

                    return ToolResult.ok(said.toString())
                            .withView(ticketCard(deployment, ticket, learnings, store));
                })))
                .build();
    }

    /**
     * The ticket as a person should look at it.
     *
     * <p>The description is a field rather than prose the console formats, which is what makes
     * it render as filed: the card's renderer never runs markdown over a field, so a requester
     * who typed {@code **URGENT**} is shown as having typed it. That is the same judgement
     * the workbench dashboard made and wrote down, and it matters more here — the console's model is
     * holding {@code workbench.execute} in the same turn.
     */
    private static View ticketCard(Deployment deployment, Ticket ticket, Learnings learnings,
            WorkbenchStore store) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", ticket.status());
        fields.put("priority", ticket.priority() == null ? "unset" : ticket.priority());
        fields.put("assignee", ticket.assignee() == null ? "nobody" : ticket.assignee());
        fields.put("reporter", ticket.reporter() == null ? "unknown" : ticket.reporter());
        int knowledge = learnings.fingerprint();
        store.triage(deployment.tenantId(), ticket.key()).ifPresent(entry ->
                fields.put("triage", verdictWord(entry.verdict())
                        + " · " + entry.verdict().category()
                        + (stale(entry, ticket, knowledge) ? " · out of date" : "")));
        fields.put("runs", store.runsForTicket(deployment.tenantId(), ticket.key()).size());
        if (!ticket.description().isBlank()) {
            // Bounded, because a card is meant to be read at a glance and a ticket with a
            // pasted stack trace in it is not. The whole description is in the digest above.
            fields.put("as filed", Cut.to(ticket.description(), MAX_CARD_CHARS));
        }
        fields.put("updated", since(ticket.updatedAt()));
        return View.cards(List.of(new View.Card(ticket.key(), ticket.summary(), fields,
                deployment.urlFor(ticket.key()))));
    }

    private static Tool search(Alm alm) {
        return FunctionTool.builder("tickets.search",
                        "capability: workbench.read. Tickets matching free text, for finding "
                                + "one whose key the operator did not give.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "text", Map.of("type", "string", "description", "What to match."),
                                "limit", Map.of("type", "integer")),
                        "required", List.of("text")))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> reading(() -> {
                    String text = invocation.stringArgument("text");
                    if (text == null || text.isBlank()) {
                        return ToolResult.error("A search needs 'text'.");
                    }
                    List<Ticket> found = alm.search(text, bounded(invocation, "limit", 20));
                    if (found.isEmpty()) {
                        return ToolResult.ok("Nothing matched.");
                    }
                    StringBuilder said = new StringBuilder(found.size() + " match(es):");
                    found.forEach(one -> said.append("\n\n").append(one.key()).append(" [")
                            .append(one.status()).append("]\n")
                            .append(fence("ticket-summary", one.summary())));
                    return ToolResult.ok(said.toString());
                }))
                .build();
    }

    private static Tool runs(String tenantId, WorkbenchStore store) {
        return FunctionTool.builder("runs.list",
                        "capability: workbench.read. Every run the agent has made: which ticket, "
                                + "rehearsal or real, what started it, how it ended.")
                .schema(empty())
                .readOnly()
                .handler(invocation -> {
                    List<Run> all = store.runs(tenantId);
                    if (all.isEmpty()) {
                        return ToolResult.ok("The agent has not run yet.");
                    }
                    StringBuilder said = new StringBuilder(all.size() + " run(s):");
                    all.forEach(one -> said.append("\n- ").append(one.id()).append(' ')
                            .append(one.ticketKey()).append(' ').append(one.mode()).append(' ')
                            .append(one.status()).append(" (").append(one.trigger()).append(')'));
                    List<List<Object>> rows = all.stream().map(one -> List.<Object>of(one.id(),
                            one.ticketKey(), one.mode().name(), one.status().name(),
                            one.trigger().name(), since(enteredAt(one)))).toList();
                    return ToolResult.ok(said.toString())
                            .withView(View.table(View.Column.texts(
                                    "run", "ticket", "mode", "status", "started by", "since"),
                                    rows));
                })
                .build();
    }

    private static Tool run(String tenantId, WorkbenchStore store) {
        return FunctionTool.builder("runs.get",
                        "capability: workbench.read. One run in full, including every event "
                                + "inside it — what it read, what it called, where it stopped.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "run_id", Map.of("type", "string")),
                        "required", List.of("run_id")))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    String id = invocation.stringArgument("run_id");
                    if (id == null || id.isBlank()) {
                        return ToolResult.error("A 'run_id' is required. runs.list has them.");
                    }
                    return store.run(tenantId, id).map(found -> {
                        List<Run.Event> log = store.events(found.id());
                        StringBuilder said = new StringBuilder(found.id() + " "
                                + found.ticketKey() + " " + found.mode() + " " + found.status()
                                + "\ngoal: " + found.goal()
                                + "\nsummary: " + summaryLine(found.summary())
                                + "\n\n" + log.size() + " event(s):");
                        // A run's events quote ticket text and tool results verbatim, so the
                        // whole log travels fenced rather than each line of it.
                        StringBuilder events = new StringBuilder();
                        log.forEach(event -> events.append(event.type()).append(' ')
                                .append(event.detail()).append('\n'));
                        said.append('\n').append(fence("run-events", events.toString()));
                        return ToolResult.ok(said.toString()).withView(timeline(found, log));
                    }).orElseGet(() -> ToolResult.error("No run "
                            + Quoted.distinguishably(id, 120) + ". runs.list has them."));
                })
                .build();
    }

    /**
     * A run, as the thing that happened rather than a log to read.
     *
     * <p>The two questions a person asks of a run are "where did it stop" and "what did it do
     * before that", and both are about order — which is why this is a timeline and not the
     * table every other listing here uses. A failed or refused moment is marked, so the eye
     * goes to it rather than counting rows.
     */
    private static View timeline(Run run, List<Run.Event> log) {
        List<View.Moment> moments = new ArrayList<>();
        moments.add(new View.Moment("run", run.mode() + " on " + run.ticketKey(), run.goal(),
                stamp(run.createdAt()), false));
        // The most recent, because a person opening a run is looking for where it got to. A
        // silent drop would read as "that is all that happened", so what was left out is a
        // moment of its own rather than nothing.
        List<Run.Event> shown = log.size() <= MAX_MOMENTS ? log
                : log.subList(log.size() - MAX_MOMENTS, log.size());
        if (shown.size() < log.size()) {
            moments.add(new View.Moment("run", (log.size() - shown.size())
                    + " earlier event(s) not shown", "This run is longer than a timeline "
                    + "usefully draws; the answer above has the whole log.", "", false));
        }
        for (Run.Event event : shown) {
            String kind = switch (event.type()) {
                case TOOL_STARTED, TOOL_COMPLETED, TOOL_FAILED -> "tool";
                case ACTION_PROPOSED, ACTION_ALLOWED, ACTION_REJECTED,
                     HUMAN_APPROVAL_REQUESTED, HUMAN_QUESTION_ASKED, HUMAN_APPROVED,
                     HUMAN_REJECTED, HUMAN_ANSWERED -> "decision";
                case LEARNING_RECORDED, CORRECTION_RECORDED, CORRECTIONS_RECALLED,
                     CAPABILITY_GAP_REPORTED -> "learned";
                case OPERATOR_COMMENTED, OPERATOR_TRANSITIONED -> "operator";
                default -> "run";
            };
            boolean wrong = switch (event.type()) {
                case TOOL_FAILED, ACTION_REJECTED, HUMAN_REJECTED -> true;
                default -> false;
            };
            // The detail map is the run's own record of somebody else's text. It is bounded
            // and flattened here rather than fenced: a view is not read by the model, and the
            // renderer draws it verbatim.
            moments.add(new View.Moment(kind, words(event.type().name()),
                    Cut.to(OneLine.of(String.valueOf(event.detail())), MAX_MOMENT_CHARS),
                    stamp(event.at()), wrong));
        }
        boolean badly = run.status() == Run.Status.FAILED || run.status() == Run.Status.CANCELLED;
        moments.add(new View.Moment("run", words(run.status().name()),
                summaryLine(run.summary()), stamp(run.completedAt()), badly));
        return View.timeline(run.id() + " · " + run.ticketKey(), moments);
    }

    /** How much of a run summary fits on one line of a timeline. */
    private static final int SUMMARY_CHARS = 200;

    /**
     * A stored run summary on one line.
     *
     * <p>Summaries are markdown flattened into a single string, so heading markers arrive
     * mid-sentence — {@code "# Analysis ## Plan"} — and a hard character slice cuts words in
     * half. The dashboard carries the scar in a comment: the screenshot that motivated its own
     * version of this read "Ass", which was "Assigned".
     *
     * <p>So two things happen, and both are needed. Markers become separators, and the cut
     * <em>backs up to a word boundary</em>. {@link Cut} alone does the first half of the job
     * and not the second — it is a bound, and a bound lands where it lands — which is how the
     * first draft of this reintroduced exactly the defect the card said not to.
     */
    static String summaryLine(String summary) {
        if (summary == null || summary.isBlank()) {
            return "none recorded";
        }
        String flat = OneLine.of(summary)
                .replaceAll("^#{1,4} ", "")
                .replaceAll(" #{1,4} ", " · ")
                .strip();
        if (flat.length() <= SUMMARY_CHARS) {
            return flat;
        }
        // Backed up to a boundary, then the ellipsis. Anchored at the end and over a bounded
        // slice, so there is no input a caller can choose that makes this expensive.
        String head = flat.substring(0, SUMMARY_CHARS).replaceAll("\\s+\\S*$", "");
        return (head.isBlank() ? flat.substring(0, SUMMARY_CHARS) : head) + "…";
    }

    /** An enum constant as a person reads it: {@code TOOL_FAILED} becomes "tool failed". */
    private static String words(String constant) {
        return constant.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /**
     * When, to the minute, in UTC — a timeline is read, not computed with.
     *
     * <p>Formatted rather than sliced out of {@code Instant.toString()}, whose shape depends
     * on the value: a whole second prints {@code 10:00:00Z} and a fractional one prints
     * {@code 10:00:00.123Z}, so a slice that matched the first left the second alone and the
     * column was ragged for exactly the events that had sub-second precision.
     */
    private static final java.time.format.DateTimeFormatter MINUTE =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'")
                    .withZone(java.time.ZoneOffset.UTC);

    private static String stamp(Instant at) {
        return at == null ? "" : MINUTE.format(at);
    }

    private static Tool gaps(String tenantId, WorkbenchStore store) {
        return FunctionTool.builder("gaps.list",
                        "capability: workbench.read. Capabilities the agent asked for and did not "
                                + "have: what a run needed and could not do. What to build "
                                + "next, in the agent's own words.")
                .schema(empty())
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    List<CapabilityGap> found = store.gaps(tenantId);
                    if (found.isEmpty()) {
                        return ToolResult.ok("The agent has not asked for anything it does not have.");
                    }
                    StringBuilder text = new StringBuilder();
                    List<List<Object>> rows = new ArrayList<>();
                    for (CapabilityGap gap : found) {
                        text.append(gap.capability()).append(" (").append(gap.ticketKey())
                                .append("): ").append(gap.description()).append('\n');
                        rows.add(List.of(gap.capability(), gap.ticketKey(),
                                Cut.to(OneLine.of(gap.description()), MAX_CARD_CHARS)));
                    }
                    // The description is the run's own prose about somebody else's request.
                    return ToolResult.ok(found.size() + " gap(s):\n"
                                    + fence("gaps", text.toString()))
                            .withView(View.table(View.Column.texts(
                                    "capability", "asked for while working", "what was needed"),
                                    rows));
                })
                .build();
    }

    private static Tool learnings(Learnings learnings) {
        return FunctionTool.builder("learnings.list",
                        "capability: workbench.read. What the agent has been taught about this "
                                + "customer: answers people gave to questions it asked, and "
                                + "lessons from runs that went wrong.")
                .schema(empty())
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    List<String> recalled = learnings.recall();
                    if (recalled.isEmpty()) {
                        return ToolResult.ok("The agent has learned nothing about this customer yet.");
                    }
                    // A one-column table rather than prose: it is a list somebody reads, and
                    // fifty of them is a list somebody filters. Cells render verbatim, which
                    // is right — a lesson is distilled from an operator's or a requester's
                    // own words.
                    List<List<Object>> rows = recalled.stream()
                            .map(one -> List.<Object>of(one)).toList();
                    return ToolResult.ok(recalled.size() + " learning(s):\n"
                                    + fence("learnings", String.join("\n", recalled)))
                            .withView(View.table(
                                    View.Column.texts("what the agent has been taught"), rows));
                })
                .build();
    }

    private static Tool catalog() {
        return FunctionTool.builder("workbench.capabilities",
                        "capability: workbench.read. Every tool a workbench RUN may reach, with "
                                + "its capability, the system it speaks to, its risk and "
                                + "whether the effect can be undone. What the agent can and "
                                + "cannot do to a ticket.")
                .schema(empty())
                .readOnly()
                .handler(invocation -> {
                    List<ToolPolicy> policies = ToolCatalog.policies();
                    StringBuilder said = new StringBuilder("A run may reach "
                            + policies.size() + " tool(s):");
                    List<List<Object>> rows = new ArrayList<>();
                    for (ToolPolicy policy : policies) {
                        said.append("\n- ").append(policy.name()).append(" (")
                                .append(policy.capability()).append(", ")
                                .append(policy.baselineRisk()).append(policy.reversible()
                                        ? ", reversible" : ", NOT reversible").append(')');
                        rows.add(List.of(policy.name(), policy.capability(), policy.connector(),
                                policy.baselineRisk().name(),
                                policy.reversible() ? "yes" : "no",
                                policy.idempotent() ? "yes" : "no"));
                    }
                    return ToolResult.ok(said.toString())
                            .withView(View.table(View.Column.texts("tool", "capability",
                                    "system", "risk", "reversible", "repeatable"), rows));
                })
                .build();
    }

    private static Tool approvals(String tenantId, WorkbenchStore store) {
        return FunctionTool.builder("approvals.list",
                        "capability: workbench.read. Runs that stopped to ask, and what they "
                                + "asked. A PENDING one is a run waiting on the operator; "
                                + "approvals.decide and approvals.answer are how it resumes.")
                .schema(empty())
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    List<Approval> all = store.approvals(tenantId);
                    if (all.isEmpty()) {
                        return ToolResult.ok("No run has stopped to ask anything.");
                    }
                    StringBuilder said = new StringBuilder(all.size() + " approval(s):");
                    List<List<Object>> rows = new ArrayList<>();
                    for (Approval one : all) {
                        // reason and effect are the run's prose about somebody else's ticket.
                        said.append("\n\n").append(one.id()).append(' ').append(one.state())
                                .append(' ').append(one.kind()).append(" on ")
                                .append(one.ticketKey()).append(" (risk ").append(one.risk())
                                .append(")\n").append(fence("approval",
                                        one.reason() + "\n" + one.effect()));
                        rows.add(List.of(one.id(), one.ticketKey(), one.kind().name(),
                                one.state().name(), one.risk().name(),
                                one.decidedBy() == null ? "" : one.decidedBy(),
                                // Same rule as the cards, and this table needs it most: a
                                // PENDING row is the single most actionable thing this console
                                // renders, and the one whose truth expires soonest.
                                since(one.decidedAt() == null
                                        ? one.requestedAt() : one.decidedAt())));
                    }
                    return ToolResult.ok(said.toString())
                            .withView(View.table(View.Column.texts("approval", "ticket", "kind",
                                    "state", "risk", "decided by", "since"), rows));
                })
                .build();
    }

    // --- judging ------------------------------------------------------------------

    private static Tool triageOne(Alm alm, Triage triage) {
        return FunctionTool.builder("triage.ticket",
                        "capability: workbench.triage. Judge one ticket: whether the agent can "
                                + "handle it, what family it belongs to, how sure that is, "
                                + "the plan, and what is missing if it cannot. Use this when "
                                + "tickets.inbox called a verdict stale, or when there is none.")
                .schema(keySchema())
                .sideEffects(SideEffects.IDEMPOTENT)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> attempt("That verdict could not be formed",
                        () -> reading(() -> withTicket(alm, invocation, ticket ->
                                triage.triage(ticket)
                                        .map(entry -> ToolResult
                                                .ok(verdictText(ticket.key(), entry.verdict()))
                                                .withView(verdictCard(ticket.key(),
                                                        entry.verdict(), entry.triagedAt())))
                                        .orElseGet(() -> ToolResult.error("The verdict could "
                                                + "not be formed. Nothing was changed; read "
                                                + "the ticket and say what you make of it."))))))
                .build();
    }

    private static Tool triageSweep(Triage triage) {
        return FunctionTool.builder("triage.sweep",
                        "capability: workbench.triage. Judge every open ticket without a "
                                + "current verdict. One model call per ticket, so it is slow "
                                + "and it costs — prefer triage.ticket unless the operator "
                                + "asked about the whole inbox.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "limit", Map.of("type", "integer",
                                        "description", "At most " + PAGE + " tickets.")),
                        "required", List.of()))
                .sideEffects(SideEffects.IDEMPOTENT)
                .handler(invocation -> attempt("That sweep could not be finished", () -> {
                    List<WorkbenchStore.TriageEntry> judged =
                            triage.sweep(bounded(invocation, "limit", PAGE));
                    if (judged.isEmpty()) {
                        return ToolResult.ok("Every open ticket already had a current verdict.");
                    }
                    Map<String, Integer> byCategory = new LinkedHashMap<>();
                    long canHandle = judged.stream().filter(e -> e.verdict().canHandle()).count();
                    judged.stream().filter(e -> e.verdict().canHandle()).forEach(e ->
                            byCategory.merge(e.verdict().category(), 1, Integer::sum));
                    List<List<Object>> rows = byCategory.entrySet().stream()
                            .map(e -> List.<Object>of(e.getKey(), e.getValue())).toList();
                    StringBuilder said = new StringBuilder("Judged " + judged.size()
                            + " ticket(s); the agent believes it can handle " + canHandle + ".");
                    byCategory.forEach((category, count) ->
                            said.append("\n- ").append(category).append(": ").append(count));
                    return ToolResult.ok(said.toString()).withView(View.table(
                            List.of(View.Column.text("family"), View.Column.number("tickets")),
                            rows));
                }))
                .build();
    }

    private static String verdictWord(TriageVerdict verdict) {
        return verdict.canHandle()
                ? "The agent can handle it (" + verdict.category() + ")"
                : "needs a person";
    }

    private static String verdictText(String key, TriageVerdict verdict) {
        // plan and missing are the judging model's prose about somebody else's ticket.
        return key + ": " + verdictWord(verdict)
                + "\nconfidence: " + Math.round(verdict.confidence() * 100) + "%\n"
                + fence("triage-verdict", "plan: " + verdict.plan()
                        + (verdict.missing() == null || verdict.missing().isBlank()
                                ? "" : "\nmissing: " + verdict.missing()));
    }

    private static View verdictCard(String key, TriageVerdict verdict,
            java.time.Instant judgedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("verdict", verdict.canHandle() ? "The agent can handle it" : "needs a person");
        fields.put("family", verdict.category());
        fields.put("confidence", Math.round(verdict.confidence() * 100) + "%");
        fields.put("plan", verdict.plan());
        if (verdict.missing() != null && !verdict.missing().isBlank()) {
            fields.put("missing", verdict.missing());
        }
        // A verdict is the one thing here that is already known to go out of date — the
        // inbox marks it stale when the ticket or the knowledge has moved since. That check
        // runs when the TOOL runs, though, and then freezes with everything else, so a
        // verdict rendered fresh at 10:32 reads fresh forever. Saying when it was judged is
        // what lets a reader do the check the frozen badge can no longer do for them.
        fields.put("judged", since(judgedAt));
        return View.cards(List.of(new View.Card(key, "triage", fields, "")));
    }

    // --- running ------------------------------------------------------------------

    private static Tool preview(Workbench workbench) {
        return FunctionTool.builder("workbench.preview",
                        "capability: workbench.run. What would the agent do with this ticket? "
                                + "Runs the whole thing with every tool that changes anything "
                                + "refused, so nothing outside this process happens. The "
                                + "honest way to answer 'what would you do' — do not guess it.")
                .schema(keySchema())
                .sideEffects(SideEffects.IDEMPOTENT)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> running(invocation, key ->
                        outcome(workbench.preview(key))))
                .build();
    }

    private static Tool execute(Workbench workbench) {
        return FunctionTool.builder("workbench.execute",
                        "capability: workbench.run. Let the agent work this ticket for real. It "
                                + "may stop part-way to ask the operator to approve something, "
                                + "which is the design and not a failure — approvals.list "
                                + "then shows what it asked. Preview first unless the operator "
                                + "has already said to go.")
                .schema(keySchema())
                .sideEffects(SideEffects.EXTERNAL)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> running(invocation, key ->
                        outcome(workbench.execute(key, Run.Trigger.OPERATOR))))
                .build();
    }

    private static Tool bulkExecute(Workbench workbench) {
        return FunctionTool.builder("workbench.bulk_execute",
                        "capability: workbench.run. Let the agent work several tickets, one after "
                                + "another. At most " + MAX_BULK + ", and only when the "
                                + "operator has said which — a triage sweep saying 'can "
                                + "handle' is not the operator saying go. The answer is one "
                                + "line per ticket; runs.get has what each one actually did.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "ticket_keys", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "The tickets, at most " + MAX_BULK + ".")),
                        "required", List.of("ticket_keys")))
                .sideEffects(SideEffects.EXTERNAL)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    if (!(invocation.argument("ticket_keys") instanceof List<?> raw)
                            || raw.isEmpty()) {
                        return ToolResult.error("Bulk execution needs a non-empty "
                                + "'ticket_keys' list.");
                    }
                    List<?> keys = raw.stream().limit(MAX_BULK).toList();
                    // Sequential on purpose, as the dashboard's own bulk endpoint is: these
                    // are real agent runs against one project, and twenty-five of them racing
                    // helps nobody read the first failure.
                    StringBuilder said = new StringBuilder();
                    List<List<Object>> rows = new ArrayList<>();
                    for (Object key : keys) {
                        String ticketKey = String.valueOf(key);
                        try {
                            Workbench.Outcome one =
                                    workbench.execute(ticketKey, Run.Trigger.BULK);
                            said.append('\n').append(headline(one));
                            rows.add(List.of(ticketKey, one.run().id(),
                                    one.run().status().name(),
                                    one.parked() ? "waiting on you" : ""));
                        } catch (RuntimeException failure) {
                            String why = bounded(failure);
                            said.append("\n").append(ticketKey).append(" → failed: ").append(why);
                            rows.add(List.of(ticketKey, "", "FAILED", why));
                        }
                    }
                    String skipped = raw.size() > MAX_BULK
                            ? "\n\n" + (raw.size() - MAX_BULK) + " more were NOT run: this "
                                    + "stops at " + MAX_BULK + ". Say so, and offer the rest."
                            : "";
                    return ToolResult.ok("Ran " + keys.size() + " ticket(s):" + said + skipped)
                            .withView(View.table(View.Column.texts(
                                    "ticket", "run", "status", "note"), rows));
                })
                .build();
    }

    /** One line about an outcome, safe to put on the framework's own line. */
    private static String headline(Workbench.Outcome outcome) {
        return outcome.run().ticketKey() + " → " + outcome.run().id() + " "
                + outcome.run().mode() + " " + outcome.run().status()
                + (outcome.parked() ? " — stopped to ask, approval "
                        + outcome.pending().map(Approval::id).orElse("(unrecorded)") : "");
    }


    /**
     * When the state above is true as of — the rule every lifecycle view here follows.
     *
     * <h4>Why a view that names mutable state must also name a time (#405)</h4>
     *
     * <p>A view is frozen the moment its tool runs. {@code Turn} copies its views in and the
     * console renders that stored data; nothing ever revisits it. For most of what a view
     * carries that is exactly right — a table of what the inbox held at 10:32 <em>is</em> a
     * fact about 10:32.
     *
     * <p>It is wrong for a card that names something with a lifecycle. A run card reading
     * {@code STATUS WAITING_FOR_HUMAN} goes on reading that after the approval is decided,
     * and it carries a dashboard's authority with a transcript's staleness — which is worse
     * than either. Measured, not supposed: recording the console for #387 left exactly that
     * card sitting in one conversation while a second correctly reported the run was still
     * waiting, and it would have gone on saying so after somebody decided.
     *
     * <h4>The entity's own clock, not the wall</h4>
     *
     * <p>Taken from the thing being described — {@code Run.startedAt}, {@code Approval
     * .requestedAt}, {@code Ticket.updatedAt} — rather than from {@code Instant.now()} at
     * render time. Three consequences, and all three are the reason:
     *
     * <ul>
     *   <li>It stays <strong>true forever</strong>. "waiting since 10:32" is a correct
     *       sentence at 11:15 and at Christmas; "as at 10:32" is only correct about the
     *       rendering, which is not a fact anybody wants.</li>
     *   <li>It is <strong>more useful</strong>: a reader learns how long, not merely that the
     *       card is old.</li>
     *   <li>There is <strong>no clock to inject</strong>, so nothing here needs a fake one and
     *       no test measures the machine it ran on.</li>
     * </ul>
     *
     * <p><strong>The rule for a new view:</strong> if it names state that can change without
     * the view changing, it names when that state began. If it is a fact about a moment — a
     * count, a chart, a diff, an answer — it does not.
     */
    private static String since(java.time.Instant when) {
        // To the second. Milliseconds are noise in an answer to "when did this begin" — no
        // person reads them — and they are not free: at full precision this is 24 characters,
        // which wrapped onto two lines in the inbox table and dragged the ticket column into
        // wrapping with it. Seen the first time the stack was brought up after adding it,
        // which is the whole argument for bringing it up.
        //
        // Truncated rather than reformatted, so it stays ISO-8601 and Instant.parse still
        // reads it: a client that wants to say "3 hours ago" needs the value, not a phrase
        // this console chose for it.
        return when == null ? "unknown"
                : when.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /**
     * The moment a run entered the status it is in.
     *
     * <p>Not {@code createdAt} for everything: a run that finished at 10:41 and one that has
     * been waiting since 10:32 are different facts, and reporting the moment it was queued
     * for both would answer a question nobody asked. A run that is still queued has not
     * started, so its own creation is the honest answer for it.
     */
    // Package-private so it can be tested directly over the status matrix. Asserting it
    // through a live run cannot separate it from a wall clock: the run completes and the
    // card is built microseconds later, so at second precision Instant.now() and
    // completedAt agree, and the mutation that matters most survives.
    static java.time.Instant enteredAt(Run run) {
        return switch (run.status()) {
            case COMPLETED, FAILED, CANCELLED -> run.completedAt() == null
                    ? run.startedAt() : run.completedAt();
            case RUNNING, WAITING_FOR_HUMAN -> run.startedAt() == null
                    ? run.createdAt() : run.startedAt();
            case PENDING -> run.createdAt();
        };
    }


    private static ToolResult outcome(Workbench.Outcome outcome) {
        // The run's output quotes the ticket, the comments and its own tool results, so it
        // goes back fenced whichever tier asked for it.
        String said = headline(outcome) + "\n" + fence("run-output", outcome.output());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ticket", outcome.run().ticketKey());
        fields.put("mode", outcome.run().mode().name());
        fields.put("status", outcome.run().status().name());
        fields.put("since", since(enteredAt(outcome.run())));
        if (outcome.parked()) {
            fields.put("waiting on", outcome.pending().map(Approval::id).orElse("a decision"));
        }
        return ToolResult.ok(said).withView(View.cards(List.of(
                new View.Card(outcome.run().id(), "run", fields, ""))));
    }

    // --- deciding -----------------------------------------------------------------

    private static Tool decide(String operator, Workbench workbench) {
        return FunctionTool.builder("approvals.decide",
                        "capability: workbench.decide. Approve or refuse what a parked run "
                                + "asked to do, AS THE OPERATOR, and let it carry on. Only "
                                + "when they have said which — this is their decision, not a "
                                + "judgement to make on their behalf. 'standing' makes a "
                                + "refusal apply to every future run, which is a big thing to "
                                + "do and needs to have been asked for in those terms.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "approval_id", Map.of("type", "string"),
                                "approved", Map.of("type", "boolean",
                                        "description", "true to allow it, false to refuse."),
                                "note", Map.of("type", "string",
                                        "description", "Why, in the operator's words."),
                                "standing", Map.of("type", "boolean",
                                        "description", "Refuse this capability from now on.")),
                        "required", List.of("approval_id", "approved")))
                .sideEffects(SideEffects.EXTERNAL)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    String id = invocation.stringArgument("approval_id");
                    if (id == null || id.isBlank()) {
                        return ToolResult.error("An 'approval_id' is required. approvals.list "
                                + "has them.");
                    }
                    if (!(invocation.argument("approved") instanceof Boolean approved)) {
                        return ToolResult.error("'approved' must be true or false. Ask the "
                                + "operator which; do not decide it for them.");
                    }
                    String note = invocation.stringArgument("note");
                    boolean standing = Boolean.TRUE.equals(invocation.argument("standing"));
                    return workbench.resume(id, operator, approved,
                                    note == null ? "" : note, standing)
                            .map(ConsoleTools::outcome)
                            .orElseGet(() -> ToolResult.error("No approval "
                                    + Quoted.distinguishably(id, 120)
                                    + " is waiting. approvals.list shows what is."));
                })
                .build();
    }

    private static Tool answer(String operator, Workbench workbench) {
        return FunctionTool.builder("approvals.answer",
                        "capability: workbench.decide. Answer a question a parked run asked, "
                                + "IN THE OPERATOR'S WORDS, and let it carry on. Do not answer "
                                + "from your own knowledge — the run stopped precisely because "
                                + "only a person here knows.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "approval_id", Map.of("type", "string"),
                                "answer", Map.of("type", "string",
                                        "description", "What the operator said.")),
                        "required", List.of("approval_id", "answer")))
                .sideEffects(SideEffects.EXTERNAL)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    String id = invocation.stringArgument("approval_id");
                    String text = invocation.stringArgument("answer");
                    if (id == null || id.isBlank()) {
                        return ToolResult.error("An 'approval_id' is required.");
                    }
                    if (text == null || text.isBlank()) {
                        return ToolResult.error("An 'answer' is required.");
                    }
                    return workbench.answer(id, operator, text)
                            .map(ConsoleTools::outcome)
                            .orElseGet(() -> ToolResult.error("No question "
                                    + Quoted.distinguishably(id, 120)
                                    + " is waiting. approvals.list shows what is."));
                })
                .build();
    }

    private static Tool automate(String tenantId, String operator, WorkbenchStore store) {
        return FunctionTool.builder("rules.automate",
                        "capability: workbench.decide. Let the agent work a whole family of "
                                + "tickets without being asked each time. A big step: suggest "
                                + "supervising a run or two of that family first, and use the "
                                + "family name exactly as triage reports it.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "category", Map.of("type", "string",
                                        "description", "The family, as triage names it, e.g. "
                                                + "access-request.")),
                        "required", List.of("category")))
                .sideEffects(SideEffects.IDEMPOTENT)
                .handler(invocation -> {
                    String asked = invocation.stringArgument("category");
                    String category = asked == null ? "" : asked.strip();
                    // Stored categories always went through Spotlight.name on the way in, so
                    // a category that is not a name matches nothing — and would be saved as
                    // "unknown", which is the bucket for tickets triage could not name. A
                    // rule quietly automating THAT is the worst outcome available here, so
                    // this refuses rather than reducing.
                    if (!Spotlight.isName(category)) {
                        return ToolResult.error("A family is a short name like "
                                + "'access-request' — letters, digits, '.', '_' or '-'. "
                                + "tickets.inbox and triage.sweep report the real ones. "
                                + "Nothing was automated.");
                    }
                    Optional<AutomationRule> existing = store.rules(tenantId).stream()
                            .filter(rule -> rule.category().equalsIgnoreCase(category))
                            .findFirst();
                    if (existing.isPresent()) {
                        AutomationRule rule = existing.get();
                        return ToolResult.ok("'" + rule.category() + "' already has a rule ("
                                + rule.id() + "), currently "
                                + (rule.enabled() ? "on." : "paused — rules.toggle resumes it."));
                    }
                    AutomationRule rule = store.save(new AutomationRule(
                            WorkbenchStore.Ids.next("rule"), tenantId, category, true,
                            operator, Instant.now()));
                    return ToolResult.ok("The agent will now work '" + rule.category()
                            + "' tickets it believes it can handle, without asking first. "
                            + rule.id() + " — rules.toggle pauses it.");
                })
                .build();
    }

    private static Tool toggle(String tenantId, WorkbenchStore store) {
        return FunctionTool.builder("rules.toggle",
                        "capability: workbench.decide. Pause or resume one automation rule, "
                                + "by the id rules.list gives.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "rule_id", Map.of("type", "string")),
                        "required", List.of("rule_id")))
                .sideEffects(SideEffects.IDEMPOTENT)
                .handler(invocation -> {
                    String id = invocation.stringArgument("rule_id");
                    if (id == null || id.isBlank()) {
                        return ToolResult.error("A 'rule_id' is required. rules.list has them.");
                    }
                    return store.rule(tenantId, id)
                            .map(rule -> {
                                AutomationRule after = store.save(rule.toggled(!rule.enabled()));
                                long covers = store.triageEntries(tenantId).stream()
                                        .filter(entry -> entry.verdict().canHandle())
                                        .filter(entry -> after.category()
                                                .equalsIgnoreCase(entry.verdict().category()))
                                        .count();
                                // What a toggle actually did, in tickets. "Paused" alone is a
                                // state; "paused, and these six now wait for you" is the
                                // consequence, and it is the consequence somebody is deciding
                                // about.
                                return ToolResult.ok(after.enabled()
                                        ? "'" + after.category() + "' is worked without asking "
                                                + "again. " + covers + " open ticket(s) the agent "
                                                + "believes it can handle are now its to start."
                                        : "'" + after.category() + "' is paused. The agent will not "
                                                + "start these on its own; " + covers
                                                + " open ticket(s) it believes it can handle "
                                                + "now wait for you.");
                            })
                            .orElseGet(() -> ToolResult.error("No rule "
                                    + Quoted.distinguishably(id, 120)
                                    + ". rules.list shows what exists."));
                })
                .build();
    }

    private static Tool rules(String tenantId, WorkbenchStore store) {
        return FunctionTool.builder("rules.list",
                        "capability: workbench.read. The automation rules: which families "
                                + "The agent works without being asked, how many open tickets each "
                                + "one currently covers, and how many runs it has started.")
                .schema(empty())
                .readOnly()
                .handler(invocation -> {
                    List<AutomationRule> all = store.rules(tenantId);
                    if (all.isEmpty()) {
                        return ToolResult.ok("No family is automated: every run is started by "
                                + "a person.");
                    }
                    // Which category a run belonged to is not on the Run, so it is read back
                    // through the ticket's verdict — the same join the rule itself makes when
                    // it decides whether to fire.
                    List<WorkbenchStore.TriageEntry> verdicts = store.triageEntries(tenantId);
                    List<Run> runs = store.runs(tenantId);
                    StringBuilder said = new StringBuilder(all.size() + " rule(s):");
                    List<List<Object>> rows = new ArrayList<>();
                    for (AutomationRule rule : all) {
                        List<WorkbenchStore.TriageEntry> family = verdicts.stream()
                                .filter(entry -> rule.category()
                                        .equalsIgnoreCase(entry.verdict().category()))
                                .toList();
                        long covers = family.stream()
                                .filter(entry -> entry.verdict().canHandle()).count();
                        // The other half of "what does this rule cover": the tickets in the
                        // same family that it will NOT touch, because the agent said a person is
                        // needed. A count of what is covered without it reads as the whole
                        // family, which is how somebody concludes a rule is handling work
                        // that is actually sitting there waiting for them.
                        long waiting = family.size() - covers;
                        List<Run> byThisRule = runs.stream()
                                .filter(one -> one.trigger() == Run.Trigger.RULE)
                                .filter(one -> family.stream().anyMatch(entry ->
                                        entry.ticketKey().equals(one.ticketKey())))
                                .toList();
                        long finished = byThisRule.stream()
                                .filter(one -> one.status() == Run.Status.COMPLETED).count();
                        long parked = byThisRule.stream()
                                .filter(one -> one.status() == Run.Status.WAITING_FOR_HUMAN)
                                .count();
                        String last = byThisRule.stream()
                                .map(Run::createdAt)
                                .filter(java.util.Objects::nonNull)
                                .max(Instant::compareTo)
                                .map(ConsoleTools::stamp)
                                .orElse("never");
                        said.append("\n- ").append(rule.category())
                                .append(rule.enabled() ? " [on]" : " [paused]")
                                .append(", covers ").append(covers)
                                .append(" open ticket(s) and leaves ").append(waiting)
                                .append(" to you; ").append(byThisRule.size())
                                .append(" run(s), ").append(finished).append(" finished, ")
                                .append(parked).append(" waiting on you; last acted ")
                                .append(last).append(" — ").append(rule.id());
                        rows.add(List.of(rule.id(), rule.category(),
                                rule.enabled() ? "on" : "paused", covers, waiting,
                                byThisRule.size(), finished, parked, last));
                    }
                    return ToolResult.ok(said.toString()).withView(View.table(List.of(
                            View.Column.text("rule"), View.Column.text("family"),
                            View.Column.text("state"), View.Column.number("covers"),
                            View.Column.number("leaves to you"), View.Column.number("runs"),
                            View.Column.number("finished"), View.Column.number("waiting"),
                            View.Column.text("last acted")), rows));
                })
                .build();
    }

    private static Tool refusals(Workbench workbench) {
        return FunctionTool.builder("decisions.refusals",
                        "capability: workbench.read. Capabilities a person told the agent to keep "
                                + "refusing. A run reaching for one is stopped whatever it "
                                + "believes, and whatever it was approved for a moment ago.")
                .schema(empty())
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    var standing = workbench.standingRefusals();
                    if (standing.isEmpty()) {
                        return ToolResult.ok("Nothing is being refused as a standing rule.");
                    }
                    StringBuilder text = new StringBuilder();
                    standing.forEach(one -> text.append(one.area()).append(" — ")
                            .append(one.decidedBy()).append(": ").append(one.note()).append('\n'));
                    // The note is what a person typed when they refused something.
                    return ToolResult.ok(standing.size() + " standing refusal(s):\n"
                            + fence("refusals", text.toString()));
                })
                .build();
    }

    private static Tool lift(Workbench workbench) {
        return FunctionTool.builder("decisions.lift",
                        "capability: workbench.decide. Stop refusing a capability, leaving the "
                                + "earlier refusals as advice. Only when the operator has said "
                                + "so — this is the one way back through a wall they built.")
                .schema(capabilitySchema())
                .sideEffects(SideEffects.IDEMPOTENT)
                .handler(invocation -> {
                    String capability = invocation.stringArgument("capability");
                    if (capability == null || capability.isBlank()) {
                        return ToolResult.error("A 'capability' is required. "
                                + "decisions.refusals lists them.");
                    }
                    return workbench.liftStandingRefusal(capability)
                            .map(lifted -> lifted == 0
                                    ? ToolResult.error("Nothing was being refused for '"
                                            + Spotlight.name(capability) + "'. "
                                            + "decisions.refusals lists what is.")
                                    : ToolResult.ok("Lifted " + lifted + " standing refusal(s) "
                                            + "for '" + Spotlight.name(capability) + "'. They "
                                            + "remain as advice; runs are no longer stopped."))
                            .orElseGet(() -> ToolResult.error("This deployment keeps no "
                                    + "correction book, so nothing is refused as a standing "
                                    + "rule."));
                })
                .build();
    }

    private static Tool trusted(Workbench workbench) {
        return FunctionTool.builder("decisions.trusted",
                        "capability: workbench.read. Capabilities a person told the agent to stop "
                                + "asking about, so a run may use them without stopping.")
                .schema(empty())
                .readOnly()
                .handler(invocation -> {
                    List<StandingApprovals.Granted> granted = workbench.trustedCapabilities();
                    if (granted.isEmpty()) {
                        return ToolResult.ok("Every gated capability still stops to ask.");
                    }
                    StringBuilder said = new StringBuilder(granted.size() + " trusted:");
                    List<List<Object>> rows = new ArrayList<>();
                    for (StandingApprovals.Granted one : granted) {
                        said.append("\n- ").append(Spotlight.name(one.capability()))
                                .append(", trusted by ").append(Spotlight.name(one.by()));
                        rows.add(List.of(one.capability(), one.by(),
                                one.at() == null ? "" : one.at()));
                    }
                    return ToolResult.ok(said.toString()).withView(View.table(
                            View.Column.texts("capability", "trusted by", "when"), rows));
                })
                .build();
    }

    private static Tool revoke(Workbench workbench) {
        return FunctionTool.builder("decisions.revoke",
                        "capability: workbench.decide. Start asking about a capability again. "
                                + "Only when the operator has said so.")
                .schema(capabilitySchema())
                .sideEffects(SideEffects.IDEMPOTENT)
                .handler(invocation -> {
                    String capability = invocation.stringArgument("capability");
                    if (capability == null || capability.isBlank()) {
                        return ToolResult.error("A 'capability' is required. "
                                + "decisions.trusted lists them.");
                    }
                    return workbench.untrust(capability)
                            ? ToolResult.ok("The agent will ask about '"
                                    + Spotlight.name(capability) + "' again.")
                            : ToolResult.error("'" + Spotlight.name(capability) + "' was not "
                                    + "being trusted. decisions.trusted lists what is.");
                })
                .build();
    }

    // --- the operator's own hands ---------------------------------------------------

    private static Tool comment(String tenantId, String operator, Alm alm,
            WorkbenchStore store) {
        return FunctionTool.builder("alm.comment",
                        "capability: workbench.operator. Write a comment on a ticket AS THE "
                                + "OPERATOR — their name on it, no run, no approval. Only when "
                                + "they have said what it should say. If the agent should be the "
                                + "one commenting, that is workbench.execute.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "ticket_key", Map.of("type", "string"),
                                "body", Map.of("type", "string",
                                        "description", "The comment, in the operator's words.")),
                        "required", List.of("ticket_key", "body")))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> writing(alm, invocation, key -> {
                    String body = invocation.stringArgument("body");
                    if (body == null || body.isBlank()) {
                        return ToolResult.error("A comment needs a 'body'.");
                    }
                    alm.addComment(key, body);
                    store.save(new OperatorAction(WorkbenchStore.Ids.next("act"), tenantId, key,
                            OperatorAction.Kind.COMMENT, body, operator, Instant.now()));
                    return ToolResult.ok("Commented on " + Spotlight.name(key)
                            + " as the operator.");
                }))
                .build();
    }

    private static Tool transition(String tenantId, String operator, Alm alm,
            WorkbenchStore store) {
        return FunctionTool.builder("alm.transition",
                        "capability: workbench.operator. Move a ticket AS THE OPERATOR, "
                                + "including resolving it — no run, no approval. tickets.get "
                                + "lists the transitions it will accept, and Jira allows only "
                                + "some from each status.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "ticket_key", Map.of("type", "string"),
                                "transition", Map.of("type", "string",
                                        "description", "The name, exactly as listed.")),
                        "required", List.of("ticket_key", "transition")))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> writing(alm, invocation, key -> {
                    String to = invocation.stringArgument("transition");
                    if (to == null || to.isBlank()) {
                        return ToolResult.error("A 'transition' name is required.");
                    }
                    alm.transition(key, to);
                    store.save(new OperatorAction(WorkbenchStore.Ids.next("act"), tenantId, key,
                            OperatorAction.Kind.TRANSITION, to, operator, Instant.now()));
                    // A transition name is Jira's, not a name in this framework's sense —
                    // 'In Progress' has a space in it — so it is quoted rather than tested.
                    return ToolResult.ok("Moved " + Spotlight.name(key) + " via "
                            + Quoted.distinguishably(to, 80) + " as the operator.");
                }))
                .build();
    }

    // --- teaching the next one ------------------------------------------------------

    private static Tool capture(EvalCaptures captures) {
        return FunctionTool.builder("evals.capture",
                        "capability: workbench.evals. Save a finished run as an eval case, so a "
                                + "change to the workbench can be replayed against it. Worth offering "
                                + "after a run that went notably well or notably badly.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "run_id", Map.of("type", "string")),
                        "required", List.of("run_id")))
                .sideEffects(SideEffects.IDEMPOTENT)
                .handler(invocation -> {
                    String id = invocation.stringArgument("run_id");
                    if (id == null || id.isBlank()) {
                        return ToolResult.error("A 'run_id' is required. runs.list has them.");
                    }
                    try {
                        Path written = captures.capture(id);
                        return ToolResult.ok("Captured as " + written.getFileName() + ".");
                    } catch (IllegalArgumentException notCapturable) {
                        // "still running", "no such run", "ended in a way that teaches
                        // nothing" — all things the model can act on rather than faults.
                        return ToolResult.error(bounded(notCapturable));
                    } catch (RuntimeException failed) {
                        return ToolResult.error("The case could not be written: "
                                + bounded(failed));
                    }
                })
                .build();
    }

    // --- shared handling ------------------------------------------------------------

    /** Runs a read, turning provider faults into results the model can act on. */
    private static ToolResult reading(Supplier<ToolResult> read) {
        try {
            return read.get();
        } catch (AlmException fault) {
            return ToolResult.error(bounded(fault));
        }
    }

    /** Runs something that needs a ticket key, normalising "no such ticket". */
    private static ToolResult writing(Alm alm, ToolInvocation invocation,
            java.util.function.Function<String, ToolResult> write) {
        String key = key(invocation);
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

    /** Runs a preview or an execute, which need a ticket key and may fail in any way at all. */
    private static ToolResult running(ToolInvocation invocation,
            java.util.function.Function<String, ToolResult> start) {
        String key = key(invocation);
        if (key == null) {
            return ToolResult.error("The 'ticket_key' argument is required.");
        }
        return attempt("That run could not be completed", () -> start.apply(key));
    }

    /**
     * Anything that reaches a model or a run, which is a great many things going wrong in a
     * great many ways.
     *
     * <p>A {@code RuntimeException} out of one of those is a bad turn rather than a broken
     * console: the model is told what happened and can say so, where a thrown exception would
     * end the operator's turn with a stack trace and no ticket worked. This is not blanket
     * swallowing — the tools that only read this deployment's own store do not use it, so a
     * genuine bug in the console still surfaces as a failure.
     */
    private static ToolResult attempt(String what, Supplier<ToolResult> body) {
        try {
            return body.get();
        } catch (RuntimeException failure) {
            return ToolResult.error(what + ": " + bounded(failure));
        }
    }

    private static ToolResult withTicket(Alm alm, ToolInvocation invocation,
            java.util.function.Function<Ticket, ToolResult> body) {
        String key = key(invocation);
        if (key == null) {
            return ToolResult.error("The 'ticket_key' argument is required.");
        }
        return alm.ticket(key)
                .map(body)
                .orElseGet(() -> ToolResult.error("No ticket "
                        + Quoted.distinguishably(key, 120)
                        + ". tickets.inbox lists what is open."));
    }

    private static String key(ToolInvocation invocation) {
        String key = invocation.stringArgument("ticket_key");
        return key == null || key.isBlank() ? null : key.strip();
    }

    /**
     * A message from below, bounded and flattened.
     *
     * <p>These routinely quote somebody else's ticket text, and an unbounded one puts a
     * requester's prose on the framework's own line unfenced.
     */
    private static String bounded(RuntimeException fault) {
        return Cut.to(OneLine.of(String.valueOf(fault.getMessage())), 400);
    }

    /** Somebody else's words, fenced, cut, and logged when the cut happens. */
    private static String fence(String kind, String text) {
        Spotlight.Bounded fenced = Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE,
                Source.of(kind), text, MAX_TEXT_CHARS);
        if (fenced.cut()) {
            LOG.info("A {} was cut to {} characters before the model saw it",
                    kind, MAX_TEXT_CHARS);
        }
        return fenced.fence();
    }

    private static int bounded(ToolInvocation invocation, String name, int fallback) {
        Object raw = invocation.argument(name);
        int asked = fallback;
        if (raw instanceof Number number) {
            asked = number.intValue();
        } else if (raw != null) {
            try {
                asked = Integer.parseInt(String.valueOf(raw).strip());
            } catch (NumberFormatException notANumber) {
                asked = fallback;
            }
        }
        return Math.max(1, Math.min(PAGE, asked));
    }

    private static Map<String, Object> empty() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    private static Map<String, Object> keySchema() {
        return Map.of("type", "object", "properties", Map.of(
                        "ticket_key", Map.of("type", "string",
                                "description", "The ticket key, e.g. IT-421.")),
                "required", List.of("ticket_key"));
    }

    private static Map<String, Object> capabilitySchema() {
        return Map.of("type", "object", "properties", Map.of(
                        "capability", Map.of("type", "string",
                                "description", "The capability, e.g. ticketing.write.")),
                "required", List.of("capability"));
    }
}
