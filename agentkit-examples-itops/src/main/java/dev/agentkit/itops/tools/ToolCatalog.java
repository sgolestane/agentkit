package dev.agentkit.itops.tools;

import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.TicketProvider;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.store.OpsStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles the tools a run may reach, and decides which of them it starts out knowing about.
 *
 * <p>The distinction the platform runs on is between a <em>capability</em> — "I need to
 * change group membership" — and a <em>concrete tool</em> — {@code identity.add_user_to_group}.
 * The model begins with the ticketing tools and a way to search for the rest; when it works
 * out that a ticket is about access, it searches for {@code identity.group_membership} and
 * the specific tools appear. Each tool's description opens with its capability string, which
 * is what makes that search land.
 *
 * <p>This is context economy, and it should not be mistaken for a security boundary. A
 * deferred tool is registered — a model that guesses the name can call it, and the framework
 * will resolve it. What stops a call is the gate, never the fact that a tool was not
 * advertised. Keeping those two ideas apart is why the supervisor reads
 * {@link #policy(String)} for every invocation rather than only for the disclosed ones.
 *
 * <p>Built per execution, because the tools close over the run's {@link OpsContext}: the
 * tenant is fixed at construction and never appears in a schema, so the model has no
 * argument through which to reach another tenant's data.
 */
public final class ToolCatalog {

    private static final Map<String, ToolPolicy> POLICIES = index(
            TicketTools.policies(), DirectoryTools.policies(), IdentityTools.policies(),
            PlatformTools.policies());

    /**
     * The capability a tool no policy declares is filed under.
     *
     * <p>One string for all of them, which has a consequence {@code RunRules} states: a
     * refusal at an undeclared tool closes every undeclared tool for the rest of that run.
     */
    public static final String UNKNOWN_CAPABILITY = "unknown";

    private ToolCatalog() {
    }

    @SafeVarargs
    private static Map<String, ToolPolicy> index(List<ToolPolicy>... groups) {
        Map<String, ToolPolicy> byName = new LinkedHashMap<>();
        for (List<ToolPolicy> group : groups) {
            for (ToolPolicy policy : group) {
                byName.put(policy.name(), policy);
            }
        }
        return Map.copyOf(byName);
    }

    /** What the supervisor knows about a tool before it runs, if anything. */
    public static Optional<ToolPolicy> policy(String toolName) {
        return Optional.ofNullable(POLICIES.get(toolName));
    }

    /**
     * The same, with the answer this platform gives when nobody wrote a policy.
     *
     * <p>An unregistered tool is not a safe tool: "nobody wrote a policy" and "somebody
     * decided it was harmless" must not resolve the same way, so the fallback is the most
     * dangerous grading the module has — {@link Risk#HIGH}, neither reversible nor
     * idempotent, under the capability {@code unknown}.
     *
     * <p>Here rather than at each call site because there are now two readers and they must
     * not drift. {@code Supervisor} wrote this fallback inline and {@code RunRules} needs the
     * <em>capability</em> off the same object; a second copy that spelt the capability
     * differently would let a refused undeclared tool close one family while the supervisor
     * graded the next one under another, and the disagreement would be invisible — both
     * halves would look like a control working.
     *
     * @param toolName the name a call resolved under, which for a registered tool is that
     *     tool's own name rather than free-form text the model chose
     */
    public static ToolPolicy policyOrUnknown(String toolName) {
        return policy(toolName).orElseGet(() ->
                ToolPolicy.write(toolName, UNKNOWN_CAPABILITY, "unknown", Risk.HIGH,
                        false, false));
    }

    /** Every declared policy, for the UI's tool inventory. */
    public static List<ToolPolicy> policies() {
        return List.copyOf(POLICIES.values());
    }

    /**
     * The registry for one run.
     *
     * <p>Always available: how to read tickets, how to say whether this run can proceed, and
     * how to find everything else. Deferred: the tools that change something, plus the
     * lookups that only matter once a direction has been chosen. Starting with the writers
     * hidden is not a defence — see the class note — but it does keep the model from
     * reaching for a change before it has read anything.
     */
    public static DisclosingToolRegistry forExecution(TicketProvider tickets, String integrationUser,
            DirectoryConnector directory, IdentityConnector identity, OpsContext context,
            OpsStore store) {
        Objects.requireNonNull(tickets, "tickets");
        List<Tool> ticketTools = TicketTools.of(tickets, integrationUser, context);
        List<Tool> platformTools = PlatformTools.of(context, store);
        List<Tool> directoryTools = DirectoryTools.of(directory, context);
        List<Tool> identityTools = IdentityTools.of(identity, context);

        List<String> alwaysAvailable = List.of("ticketing.search_tickets", "ticketing.get_ticket",
                "ticketing.get_ticket_comments", PlatformTools.REPORT_CAPABILITY);

        DisclosingToolRegistry.Builder builder = DisclosingToolRegistry.builder();
        List<Tool> everything = new ArrayList<>();
        everything.addAll(ticketTools);
        everything.addAll(platformTools);
        everything.addAll(directoryTools);
        everything.addAll(identityTools);
        for (Tool tool : everything) {
            if (alwaysAvailable.contains(tool.name())) {
                builder.alwaysAvailable(tool);
            } else {
                builder.deferred(tool);
            }
        }
        return builder.build();
    }
}
