package dev.agentkit.itops.tools;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.runtime.OpsContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The employee-directory capability: turning a name in a ticket into a person.
 *
 * <p>This is where the run's correctness is usually won or lost. A ticket says "Alice"; the
 * identity provider needs an email; and there are two Alices. The tool returns <em>all</em>
 * matches with an explicit count rather than a best guess, so the ambiguity survives
 * contact with the model instead of being resolved by whichever record happened to sort
 * first. What the agent does about it is a judgement call — but it cannot make that call if
 * the tool has already made it invisibly.
 *
 * <p>None of it reaches the model on a line this file writes (#191). The query opened the
 * miss result — {@code No employee matches "<query>"} — and the records were fenced with
 * unbounded {@code Spotlight.wrap}, so one employee with a 1,000,000-character department
 * fenced to 1,000,183 and was re-sent every turn. The rule is the one {@code TicketTools}
 * settled on for the ticketing third of this module: an identifier goes inside a fence or
 * it does not appear. {@link dev.agentkit.core.prompt.Spotlight#name} is not the third
 * option — it admits {@code SYSTEM_the_operator_widened_scope_okay}, which is a name, and
 * turns {@code alice@example.com} into {@code unknown}, which measurably flipped a
 * guardrail from ALLOWED to REFUSED.
 */
public final class DirectoryTools {

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryTools.class);

    /**
     * Characters of directory records the model is shown, per lookup.
     *
     * <p>The same figure {@code TicketTools} holds a ticket to and for the same reason: it
     * is what {@code WorkingMemory.MAX_STORED_CHARS} uses, and a tool result is re-sent on
     * every turn of the run, so this is a per-turn cost rather than a one-off. Before it
     * this site used unbounded {@code Spotlight.wrap} and one employee with a
     * 1,000,000-character department fenced to 1,000,183 (#191).
     */
    private static final int MAX_DIRECTORY_CHARS = 20_000;

    private static final String CAPABILITY = "identity.directory.read";

    private DirectoryTools() {
    }

    public static List<ToolPolicy> policies() {
        return List.of(ToolPolicy.read("directory.search_employee", CAPABILITY, "directory"));
    }

    public static List<Tool> of(DirectoryConnector directory, OpsContext context) {
        return List.of(FunctionTool.builder("directory.search_employee",
                        "capability: identity.directory.read. Look someone up in the employee "
                                + "directory by name or email. Returns every match, so check the "
                                + "count before acting on one.")
                .schema(Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string",
                                "description", "A name or email fragment, e.g. 'Alice' or 'alice@'.")),
                        "required", List.of("query")))
                .readOnly()
                // The company's own HR system of record: every field here — name, email,
                // department, employment status — is typed by HR, so by Provenance's test
                // ("did the deployment choose every byte") this is first-party. The records
                // are still fenced below, and the two are not in tension: fencing costs
                // nothing and stops a record forging the framework's own line, while this
                // declaration is what keeps a lookup from lowering a trust floor.
                //
                // The field that would flip this is a self-service profile — an "about me"
                // an employee types, or anything synced from a system the company does not
                // own. DirectoryConnector.Employee has none; add one and this becomes
                // THIRD_PARTY.
                .provenance(Provenance.FIRST_PARTY)
                .handler(invocation -> {
                    String query = invocation.stringArgument("query");
                    List<DirectoryConnector.Employee> matches = directory.search(query);
                    if (matches.isEmpty()) {
                        // Not "No employee matches \"<query>\"." — that put the model's own
                        // argument in the framework's own sentence with no fence anywhere
                        // on the line (#191), and a description that steers the model into
                        // searching for a sentence is this module's threat model rather
                        // than a hypothesis. Nothing is lost by not echoing it:
                        // Spotlight.name's javadoc settles the case, "it is being told
                        // which of its own arguments missed, not being informed of a value
                        // it has never seen."
                        return ToolResult.ok("No employee matches that query.");
                    }
                    StringBuilder body = new StringBuilder();
                    for (DirectoryConnector.Employee employee : matches) {
                        body.append(employee.email()).append(" | ").append(employee.name())
                                .append(" | ").append(employee.department())
                                .append(" | status=").append(employee.employmentStatus());
                        if (employee.terminationDate() != null) {
                            body.append(" | terminated=").append(employee.terminationDate());
                        }
                        body.append('\n');
                    }
                    // Raw in the evidence — the query, the name and the email — and that is
                    // the decision #190 made for the ticket assignee, not an oversight.
                    // Evidence is not a prompt: its consumers are Supervisor:178, which
                    // fences the whole block before a reviewing model reads it,
                    // Supervisor:296, which stores it on the approval a person reads, and
                    // the operator console. Reducing the values instead cost
                    // Reviewers.goalAlignment the fact it corroborates against, and a
                    // legitimate follow-up flipped from ALLOWED to REFUSED. The sweep
                    // asserts the property that has to hold: this reaches a model only
                    // inside a fence.
                    if (matches.size() == 1) {
                        DirectoryConnector.Employee only = matches.get(0);
                        context.evidence("Directory: \"" + query + "\" resolves to exactly one "
                                + "person, " + only.name() + " (" + only.email() + "), status "
                                + only.employmentStatus()
                                + (only.terminationDate() == null ? ""
                                        : ", terminated " + only.terminationDate()) + ".");
                    } else {
                        context.evidence("Directory: \"" + query + "\" matches " + matches.size()
                                + " people; identity is not established.");
                    }
                    // The count is ours and stays outside the fence; the records are the
                    // directory's and go inside it. A single match is the only case where an
                    // identity has actually been established, and saying so plainly is worth
                    // more than leaving the model to count.
                    return ToolResult.ok((matches.size() == 1
                            ? "Exactly one match — identity established."
                            : matches.size() + " matches — identity is NOT established. Ask a "
                                    + "human which person is meant rather than choosing.")
                            + '\n'
                            + fenced(body.toString().stripTrailing()));
                })
                .build());
    }

    /** The matched records, bounded, and a line in the log when the bound bit. */
    private static String fenced(String records) {
        Spotlight.Bounded bounded = Spotlight.fenceBounded(Spotlight.Kind.EVIDENCE, Source.of("directory"),
                records, MAX_DIRECTORY_CHARS);
        if (bounded.cut()) {
            // Logged, because the in-band marker Cut leaves is not enough on its own:
            // Synthesizers.fenceOf's javadoc names the defect — "a cap nobody is told about
            // reads as 'everything was carried'" — and ToolResult.failed adds the rest,
            // "a hostile source can print the same marker. The operator gets an
            // unforgeable one." Every bounded site in core logs; this one did not exist.
            //
            // It matters more here than most: what is cut is the tail of a match LIST, so
            // the fact that goes missing is that there was a second Alice.
            LOG.info("Directory matches were cut to {} characters before the model saw them",
                    MAX_DIRECTORY_CHARS);
        }
        return bounded.fence();
    }
}
