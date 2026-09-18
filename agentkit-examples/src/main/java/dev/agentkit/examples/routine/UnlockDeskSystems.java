package dev.agentkit.examples.routine;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * In-memory stand-ins for what an account unlock touches — the directory, Okta, a notification service and the IT
 * ticket queue — exposed as tools.
 *
 * <p>The arguments are deliberately structured: a notification names a template and an address rather than carrying
 * text the model wrote. That is what lets the work settle into a routine. Free text differs every time a model writes
 * it, so a run whose arguments include prose never agrees exactly with the last one and is never replayed — which is
 * the right outcome, and the reason to design tools this way when the saving matters.
 */
public final class UnlockDeskSystems {

    /** A directory entry and its Okta state. {@code hardwareToken} accounts cannot have MFA reset remotely. */
    public record Account(String email, String name, String manager, boolean locked, boolean hardwareToken) {
    }

    /** A notification that went out. */
    public record Notice(String to, String template, String about) {
    }

    /** A ticket opened with the IT desk. */
    public record Ticket(String forEmail, String category) {
    }

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<String> toolCalls = new ArrayList<>();
    private final List<String> unlocked = new ArrayList<>();
    private final List<String> mfaReset = new ArrayList<>();
    private final List<Notice> notices = new ArrayList<>();
    private final List<Ticket> tickets = new ArrayList<>();

    /** Ten locked-out people, one of whom carries a hardware token. */
    public static UnlockDeskSystems seeded() {
        UnlockDeskSystems systems = new UnlockDeskSystems();
        String[][] people = {
                {"ana.silva", "Ana Silva", "lena.ortiz"},
                {"ben.cho", "Ben Cho", "lena.ortiz"},
                {"cara.nwosu", "Cara Nwosu", "sam.okafor"},
                {"dev.patel", "Dev Patel", "sam.okafor"},
                {"eve.martin", "Eve Martin", "lena.ortiz"},
                {"finn.berg", "Finn Berg", "dana.kim"},
                {"gus.reyes", "Gus Reyes", "dana.kim"},
                {"hana.ito", "Hana Ito", "dana.kim"},
                {"ivan.petrov", "Ivan Petrov", "sam.okafor"},
                {"jo.adams", "Jo Adams", "lena.ortiz"},
        };
        for (String[] person : people) {
            String email = person[0] + "@acme.example";
            systems.accounts.put(email, new Account(email, person[1], person[2] + "@acme.example", true,
                    person[0].equals("gus.reyes")));
        }
        return systems;
    }

    /** Everyone, in the order their tickets arrive. */
    public synchronized List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    public synchronized Account account(String email) {
        return accounts.get(email);
    }

    /** Every tool call that reached a system, in order, as {@code tool(subject)}. */
    public synchronized List<String> toolCalls() {
        return List.copyOf(toolCalls);
    }

    /** Whose accounts were unlocked, once per unlock — a person listed twice was unlocked twice. */
    public synchronized List<String> unlocked() {
        return List.copyOf(unlocked);
    }

    /** Whose MFA was reset, once per reset. */
    public synchronized List<String> mfaReset() {
        return List.copyOf(mfaReset);
    }

    public synchronized List<Notice> notices() {
        return List.copyOf(notices);
    }

    public synchronized List<Ticket> tickets() {
        return List.copyOf(tickets);
    }

    /** The tools, in one registry. */
    public ToolRegistry registry() {
        return new SimpleToolRegistry(List.of(directoryLookup(), oktaUnlock(), oktaResetMfa(), notifyTool(), createTicket()));
    }

    private Tool directoryLookup() {
        return tool("directory_lookup", "Look a person up by work email: name, manager, whether their account is "
                        + "locked, and their MFA method (app or hardware token).",
                Map.of("email", str("Work email")), List.of("email"), SideEffects.NONE, "email", inv -> {
                    Account a = accounts.get(email(inv, "email"));
                    if (a == null) {
                        return ToolResult.error("No person with the email " + inv.stringArgument("email"));
                    }
                    return ToolResult.ok(a.name() + " — manager " + a.manager() + ", account "
                            + (a.locked() ? "locked" : "active") + ", MFA " + (a.hardwareToken() ? "hardware token" : "app"));
                });
    }

    private Tool oktaUnlock() {
        return tool("okta_unlock", "Unlock a person's Okta account.",
                Map.of("email", str("Work email")), List.of("email"), SideEffects.IDEMPOTENT, "email", inv -> {
                    String email = email(inv, "email");
                    Account a = accounts.get(email);
                    if (a == null) {
                        return ToolResult.error("No Okta account for " + email);
                    }
                    accounts.put(email, new Account(a.email(), a.name(), a.manager(), false, a.hardwareToken()));
                    unlocked.add(email);
                    return ToolResult.ok("Unlocked " + email);
                });
    }

    private Tool oktaResetMfa() {
        return tool("okta_reset_mfa", "Reset a person's MFA enrolment so they can enrol again. Fails for hardware "
                        + "tokens, which must be reset in person.",
                Map.of("email", str("Work email")), List.of("email"), SideEffects.EXTERNAL, "email", inv -> {
                    String email = email(inv, "email");
                    Account a = accounts.get(email);
                    if (a == null) {
                        return ToolResult.error("No Okta account for " + email);
                    }
                    if (a.hardwareToken()) {
                        return ToolResult.error(email + " uses a hardware token, which cannot be reset remotely; "
                                + "open an IT ticket (category hardware_token_reset) instead.");
                    }
                    mfaReset.add(email);
                    return ToolResult.ok("MFA reset for " + email);
                });
    }

    private Tool notifyTool() {
        return tool("notify", "Send a templated notification. Templates: account_unlocked (to the person), "
                        + "manager_copy (to their manager, about the person), token_reset_pending (to the person "
                        + "whose hardware token must be reset in person).",
                Map.of("to_email", str("Recipient's work email"),
                        "template", Map.of("type", "string",
                                "enum", List.of("account_unlocked", "manager_copy", "token_reset_pending")),
                        "about_email", str("Whose account the notice is about")),
                List.of("to_email", "template", "about_email"), SideEffects.EXTERNAL, "to_email", inv -> {
                    String template = inv.stringArgument("template");
                    if (!List.of("account_unlocked", "manager_copy", "token_reset_pending").contains(template)) {
                        return ToolResult.error("Unknown template " + template);
                    }
                    notices.add(new Notice(email(inv, "to_email"), template, email(inv, "about_email")));
                    return ToolResult.ok("Sent " + template + " to " + email(inv, "to_email"));
                });
    }

    private Tool createTicket() {
        return tool("it_create_ticket", "Open a ticket with the IT desk for work that cannot be done remotely.",
                Map.of("for_email", str("Whose ticket it is"),
                        "category", Map.of("type", "string", "enum", List.of("hardware_token_reset"))),
                List.of("for_email", "category"), SideEffects.EXTERNAL, "for_email", inv -> {
                    tickets.add(new Ticket(email(inv, "for_email"), inv.stringArgument("category")));
                    return ToolResult.ok("Ticket opened for " + email(inv, "for_email"));
                });
    }

    private FunctionTool tool(String name, String description, Map<String, Object> properties, List<String> required,
                              SideEffects sideEffects, String subjectArgument, Function<ToolInvocation, ToolResult> handler) {
        return FunctionTool.builder(name, description)
                .schema(Map.of("type", "object", "properties", properties, "required", required))
                .sideEffects(sideEffects)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> {
                    synchronized (this) {
                        toolCalls.add(name + "(" + inv.stringArgument(subjectArgument) + ")");
                        return handler.apply(inv);
                    }
                })
                .build();
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static String email(ToolInvocation inv, String argument) {
        String value = inv.stringArgument(argument);
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
