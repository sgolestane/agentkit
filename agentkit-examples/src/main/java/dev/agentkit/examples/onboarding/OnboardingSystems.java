package dev.agentkit.examples.onboarding;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * In-memory stand-ins for the SaaS systems an IT onboarding touches — Okta, GitHub, AWS,
 * Salesforce, Slack, Workday, and the IT service desk — exposed to the executor as tools.
 *
 * <p>Every write is recorded so {@link OnboardingExample} can verify a run by what it
 * actually did to these systems rather than by what the model said it did. One instance
 * serves one onboarding run; the example builds a fresh one per scenario.
 *
 * <h2>Resolving a missing GitHub username</h2>
 *
 * <p>{@code github_add_member} takes an email, not a username: it adds whichever GitHub
 * account is <em>on record</em> for that person, and refuses when none is. Only three
 * things put one on record, each tagged with where it came from — the HRIS (seeded), the
 * person's own Slack profile ({@code slack_get_profile}), or the person answering a Slack
 * form ({@code slack_request_github_username}). {@code github_search_users} records
 * nothing: a name match is a guess, and the only way to use one is to suggest it to the
 * person and let them confirm or correct it. So the model never copies a username into a
 * grant, and a search hit can never become a membership on its own.
 */
public final class OnboardingSystems {

    /** An Okta account. {@code expiresOn} is null for accounts that do not expire. */
    public record OktaUser(String email, String firstName, String lastName, String status,
                           Set<String> groups, String expiresOn) {
    }

    public record SlackAccount(String email, String accountType, Set<String> channels) {
    }

    public record SlackMessage(String to, String text) {
    }

    /** A Slack form asking someone for their GitHub username, and what they submitted (null if nothing). */
    public record SlackQuestion(String email, String suggestedUsername, String reply) {
    }

    /** The GitHub account on record for a person, and which source established it. */
    public record GithubIdentity(String email, String username, String source) {
    }

    public record AwsGrant(String email, String environment) {
    }

    public record Shipment(String email, String address) {
    }

    public record ItTicket(String category, String forEmail, String summary) {
    }

    /** GitHub's own username rule: alphanumerics and single inner hyphens, at most 39 characters. */
    private static final Pattern GITHUB_USERNAME =
            Pattern.compile("^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,38}$", Pattern.CASE_INSENSITIVE);

    private final Map<String, OktaUser> okta = new LinkedHashMap<>();
    private final List<String> oktaCreated = new ArrayList<>();
    private final List<String> oktaReactivated = new ArrayList<>();
    private final Map<String, String> githubDirectory = new LinkedHashMap<>(); // login -> public name ("" if none)
    private final Map<String, GithubIdentity> githubIdentities = new LinkedHashMap<>();
    private final Map<String, String> githubMembers = new LinkedHashMap<>();
    private final List<AwsGrant> awsGrants = new ArrayList<>();
    private final Set<String> salesforceSeats = new LinkedHashSet<>();
    private final Map<String, SlackAccount> slack = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> slackProfiles = new LinkedHashMap<>();
    private final Map<String, String> hireReplies = new LinkedHashMap<>(); // what each person would submit
    private final List<SlackQuestion> slackQuestions = new ArrayList<>();
    private final List<SlackMessage> slackMessages = new ArrayList<>();
    private final List<Shipment> shipments = new ArrayList<>();
    private final List<ItTicket> tickets = new ArrayList<>();
    private final Set<String> benefitsEnrolled = new LinkedHashSet<>();
    private final List<String> toolCalls = new ArrayList<>();
    private final long replyWaitMillis;

    private OnboardingSystems(long replyWaitMillis) {
        this.replyWaitMillis = replyWaitMillis;
    }

    /** Systems seeded with the company's existing state that the scenarios rely on. */
    public static OnboardingSystems seeded() {
        return seeded(1_000);
    }

    static OnboardingSystems seeded(long replyWaitMillis) {
        OnboardingSystems s = new OnboardingSystems(replyWaitMillis);

        // A former employee whose Okta account was deactivated when she left.
        s.okta.put("maria.chen@acme.example", new OktaUser("maria.chen@acme.example",
                "Maria", "Chen", "DEACTIVATED", Set.of(), null));

        // Public GitHub. An account called "Jordan Lee" exists, and it is not our Jordan Lee.
        for (String[] user : new String[][] {
                {"priyan-dev", "Priya N."}, {"mchen-sre", "Maria Chen"}, {"nfischer-code", "Noah Fischer"},
                {"ar-codes", ""}, {"jlee", "Jordan Lee"}, {"jl-builds", ""}}) {
            s.githubDirectory.put(user[0], user[1]);
        }

        // The HRIS already knows these engineers' GitHub accounts.
        s.recordIdentity("priya.natarajan@acme.example", "priyan-dev", "hris");
        s.recordIdentity("maria.chen@acme.example", "mchen-sre", "hris");

        // Hires invited to Slack before their start date. Only Noah filled in his GitHub field.
        for (String email : List.of("noah.fischer@acme.example", "aisha.rahman@acme.example",
                "jordan.lee@acme.example", "alex.rivera@acme.example")) {
            s.slack.put(email, new SlackAccount(email, "guest", Set.of("#welcome")));
            s.slackProfiles.put(email, new LinkedHashMap<>(Map.of("title", "New hire", "timezone", "America/Chicago")));
        }
        s.slackProfiles.get("noah.fischer@acme.example").put("github", "nfischer-code");

        // What each person submits if asked. Alex never answers.
        s.hireReplies.put("noah.fischer@acme.example", "nfischer-code");
        s.hireReplies.put("aisha.rahman@acme.example", "ar-codes");
        s.hireReplies.put("jordan.lee@acme.example", "jl-builds");
        return s;
    }

    /** Every tool, in one registry. Build a fresh registry per executor step. */
    public ToolRegistry registry() {
        return new SimpleToolRegistry(List.of(
                oktaCreateUser(), oktaReactivateUser(),
                slackGetProfile(), githubSearchUsers(), slackRequestGithubUsername(), githubAddMember(),
                awsGrantAccess(), salesforceAssignSeat(), slackCreateAccount(), slackSendMessage(), shipLaptop(),
                itCreateTicket(), workdayEnrollBenefits()));
    }

    // ---------------------------------------------------------------- Okta

    private FunctionTool oktaCreateUser() {
        return tool("okta_create_user", "Create a new Okta account and add it to groups.",
                schema(Map.of(
                        "email", str("Work email"),
                        "first_name", str("First name"),
                        "last_name", str("Last name"),
                        "groups", strings("Okta group names"),
                        "expires_on", str("Optional account expiry date, YYYY-MM-DD")),
                        "email", "first_name", "last_name", "groups"),
                SideEffects.EXTERNAL, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    if (okta.containsKey(email)) {
                        return ToolResult.error("Okta: a user with email " + email
                                + " already exists (status " + okta.get(email).status() + ")");
                    }
                    String expires = blankToNull(inv.stringArgument("expires_on"));
                    okta.put(email, new OktaUser(email, inv.stringArgument("first_name"),
                            inv.stringArgument("last_name"), "ACTIVE", groups(inv), expires));
                    oktaCreated.add(email);
                    return ToolResult.ok("Okta: created " + email + " in groups " + groups(inv)
                            + (expires == null ? "" : ", expires " + expires));
                });
    }

    private FunctionTool oktaReactivateUser() {
        return tool("okta_reactivate_user",
                "Reactivate a deactivated Okta account and set its groups.",
                schema(Map.of("email", str("Work email"), "groups", strings("Okta group names")),
                        "email", "groups"),
                SideEffects.EXTERNAL, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    OktaUser existing = okta.get(email);
                    if (existing == null || !existing.status().equals("DEACTIVATED")) {
                        return ToolResult.error("Okta: no deactivated user " + email);
                    }
                    okta.put(email, new OktaUser(email, existing.firstName(), existing.lastName(),
                            "ACTIVE", groups(inv), null));
                    oktaReactivated.add(email);
                    return ToolResult.ok("Okta: reactivated " + email + " in groups " + groups(inv));
                });
    }

    // ---------------------------------------------------------------- GitHub identity

    private FunctionTool slackGetProfile() {
        return tool("slack_get_profile",
                "Read a person's Slack profile fields. If the person has filled in the GitHub field of "
                        + "their own profile and that account exists, it is recorded as their GitHub "
                        + "account (source: slack_profile).",
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    Map<String, String> profile = slackProfiles.get(email);
                    if (profile == null) {
                        return ToolResult.error("Slack: no Slack user with email " + email);
                    }
                    StringBuilder out = new StringBuilder("Slack profile for " + email + ": " + profile);
                    String github = profile.get("github");
                    if (github == null) {
                        out.append("\nThe GitHub field is empty.");
                    } else if (githubDirectory.containsKey(lower(github))) {
                        recordIdentity(email, lower(github), "slack_profile");
                        out.append("\nRecorded ").append(lower(github)).append(" as their GitHub account (source: slack_profile).");
                    } else {
                        out.append("\nThe GitHub field names an account that does not exist; nothing recorded.");
                    }
                    return ToolResult.ok(out.toString());
                });
    }

    private FunctionTool githubSearchUsers() {
        return tool("github_search_users",
                "Search public GitHub users by name. Results are possible matches only — nothing is "
                        + "recorded, and a match by name does not establish that the account belongs to "
                        + "the person.",
                schema(Map.of("query", str("A person's name")), "query"),
                SideEffects.NONE, inv -> {
                    String query = lower(inv.stringArgument("query"));
                    List<String> hits = new ArrayList<>();
                    githubDirectory.forEach((login, name) -> {
                        if (!query.isEmpty() && (lower(name).contains(query) || login.contains(query))) {
                            hits.add(login + (name.isEmpty() ? "" : " (" + name + ")"));
                        }
                    });
                    return ToolResult.ok(hits.isEmpty()
                            ? "GitHub: no users match \"" + query + "\""
                            : "GitHub: " + hits.size() + " possible match(es) for \"" + query + "\": " + hits);
                });
    }

    private FunctionTool slackRequestGithubUsername() {
        return tool("slack_request_github_username",
                "Send a person a Slack form asking for their GitHub username, optionally prefilled with a "
                        + "suggested username for them to confirm or correct, and wait for them to submit it. "
                        + "A valid submitted username is recorded as their GitHub account "
                        + "(source: confirmed_by_hire).",
                schema(Map.of("email", str("Work email of the person to ask"),
                                "suggested_username", str("Optional username to prefill for them to confirm")),
                        "email"),
                SideEffects.EXTERNAL, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    if (!slack.containsKey(email)) {
                        return ToolResult.error("Slack: no Slack user with email " + email);
                    }
                    String suggestion = blankToNull(inv.stringArgument("suggested_username"));
                    String reply = hireReplies.get(email);
                    slackQuestions.add(new SlackQuestion(email, suggestion == null ? null : lower(suggestion), reply));
                    waitForReply();
                    if (reply == null) {
                        return ToolResult.error("Slack: no reply from " + email
                                + " within the waiting window. Their GitHub username is still unknown.");
                    }
                    String username = lower(reply);
                    if (!GITHUB_USERNAME.matcher(username).matches() || !githubDirectory.containsKey(username)) {
                        return ToolResult.error("Slack: " + email + " submitted a value that is not an existing "
                                + "GitHub username. Nothing recorded.");
                    }
                    recordIdentity(email, username, "confirmed_by_hire");
                    return ToolResult.ok("Slack: " + email + " submitted GitHub username " + username
                            + (suggestion != null && !username.equals(lower(suggestion))
                                    ? " (they corrected the suggestion " + lower(suggestion) + ")" : "")
                            + ". Recorded as their GitHub account (source: confirmed_by_hire).");
                });
    }

    private FunctionTool githubAddMember() {
        return tool("github_add_member",
                "Add a person's GitHub account on record to the acme org and a team. Takes the person's work "
                        + "email; fails if no GitHub account is on record for them yet.",
                schema(Map.of("email", str("Work email"), "team", str("Team slug")), "email", "team"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    GithubIdentity identity = githubIdentities.get(email);
                    if (identity == null) {
                        return ToolResult.error("GitHub: no GitHub account is on record for " + email
                                + "; it has to be obtained before they can be added.");
                    }
                    String team = lower(inv.stringArgument("team"));
                    githubMembers.put(identity.username(), team);
                    return ToolResult.ok("GitHub: added " + identity.username() + " to team " + team
                            + " (account source: " + identity.source() + ")");
                });
    }

    // ---------------------------------------------------------------- AWS, Salesforce

    private FunctionTool awsGrantAccess() {
        return tool("aws_grant_access", "Grant a user access to an AWS environment.",
                schema(Map.of("email", str("Work email"),
                                "environment", Map.of("type", "string", "enum", List.of("staging", "production"))),
                        "email", "environment"),
                SideEffects.IDEMPOTENT, inv -> {
                    AwsGrant grant = new AwsGrant(lower(inv.stringArgument("email")),
                            lower(inv.stringArgument("environment")));
                    awsGrants.add(grant);
                    return ToolResult.ok("AWS: granted " + grant.environment() + " to " + grant.email());
                });
    }

    private FunctionTool salesforceAssignSeat() {
        return tool("salesforce_assign_seat", "Assign a Salesforce license seat to a user.",
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    salesforceSeats.add(email);
                    return ToolResult.ok("Salesforce: seat assigned to " + email);
                });
    }

    // ---------------------------------------------------------------- Slack

    private FunctionTool slackCreateAccount() {
        return tool("slack_create_account",
                "Create a Slack account (or convert a pre-boarding account) and set its channels.",
                schema(Map.of("email", str("Work email"),
                                "account_type", Map.of("type", "string", "enum", List.of("member", "guest")),
                                "channels", strings("Channel names, e.g. #general")),
                        "email", "account_type", "channels"),
                SideEffects.EXTERNAL, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    Set<String> channels = new LinkedHashSet<>();
                    for (String c : list(inv, "channels")) {
                        channels.add("#" + lower(c).replaceFirst("^#", ""));
                    }
                    boolean existed = slack.containsKey(email);
                    slack.put(email, new SlackAccount(email, lower(inv.stringArgument("account_type")), channels));
                    slackProfiles.putIfAbsent(email, new LinkedHashMap<>());
                    return ToolResult.ok("Slack: " + (existed ? "updated pre-boarding" : "created") + " "
                            + inv.stringArgument("account_type") + " account for " + email + " in " + channels);
                });
    }

    private FunctionTool slackSendMessage() {
        return tool("slack_send_message", "Send a Slack direct message to a user by email.",
                schema(Map.of("to_email", str("Recipient's work email"), "text", str("Message text")),
                        "to_email", "text"),
                SideEffects.EXTERNAL, inv -> {
                    slackMessages.add(new SlackMessage(lower(inv.stringArgument("to_email")),
                            inv.stringArgument("text")));
                    return ToolResult.ok("Slack: message sent to " + inv.stringArgument("to_email"));
                });
    }

    // ---------------------------------------------------------------- Equipment, IT, Workday

    private FunctionTool shipLaptop() {
        return tool("ship_laptop", "Ship a standard laptop to an address.",
                schema(Map.of("email", str("Work email of the recipient"), "address", str("Full shipping address")),
                        "email", "address"),
                SideEffects.EXTERNAL, inv -> {
                    shipments.add(new Shipment(lower(inv.stringArgument("email")), inv.stringArgument("address")));
                    return ToolResult.ok("Shipping: laptop dispatched to " + inv.stringArgument("address"));
                });
    }

    private FunctionTool itCreateTicket() {
        return tool("it_create_ticket", "Open an IT service desk ticket.",
                schema(Map.of(
                                "category", Map.of("type", "string", "enum", List.of("laptop_pickup", "access_request")),
                                "for_email", str("Work email of the person the ticket is for"),
                                "summary", str("What the ticket asks for, including any office or system")),
                        "category", "for_email", "summary"),
                SideEffects.EXTERNAL, inv -> {
                    ItTicket ticket = new ItTicket(lower(inv.stringArgument("category")),
                            lower(inv.stringArgument("for_email")), inv.stringArgument("summary"));
                    tickets.add(ticket);
                    return ToolResult.ok("IT: opened ticket INC-" + (1000 + tickets.size())
                            + " (" + ticket.category() + ")");
                });
    }

    private FunctionTool workdayEnrollBenefits() {
        return tool("workday_enroll_benefits", "Enroll an employee in the standard benefits package.",
                schema(Map.of("employee_id", str("Workday employee id")), "employee_id"),
                SideEffects.IDEMPOTENT, inv -> {
                    benefitsEnrolled.add(inv.stringArgument("employee_id"));
                    return ToolResult.ok("Workday: benefits enrolled for " + inv.stringArgument("employee_id"));
                });
    }

    // ---------------------------------------------------------------- state, for verification

    public synchronized OktaUser oktaUser(String email) {
        return okta.get(email);
    }

    public synchronized List<String> oktaCreated() {
        return List.copyOf(oktaCreated);
    }

    public synchronized List<String> oktaReactivated() {
        return List.copyOf(oktaReactivated);
    }

    public synchronized GithubIdentity githubIdentity(String email) {
        return githubIdentities.get(email);
    }

    /** GitHub org members: username to team. */
    public synchronized Map<String, String> githubMembers() {
        return Map.copyOf(githubMembers);
    }

    public synchronized List<SlackQuestion> slackQuestions() {
        return List.copyOf(slackQuestions);
    }

    /** The name of every tool call that reached a tool, in order. */
    public synchronized List<String> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public synchronized List<AwsGrant> awsGrants() {
        return List.copyOf(awsGrants);
    }

    public synchronized Set<String> salesforceSeats() {
        return Set.copyOf(salesforceSeats);
    }

    public synchronized SlackAccount slackAccount(String email) {
        return slack.get(email);
    }

    public synchronized List<SlackMessage> slackMessages() {
        return List.copyOf(slackMessages);
    }

    public synchronized List<Shipment> shipments() {
        return List.copyOf(shipments);
    }

    public synchronized List<ItTicket> tickets() {
        return List.copyOf(tickets);
    }

    public synchronized Set<String> benefitsEnrolled() {
        return Set.copyOf(benefitsEnrolled);
    }

    // ---------------------------------------------------------------- helpers

    private synchronized void recordIdentity(String email, String username, String source) {
        githubIdentities.put(email, new GithubIdentity(email, username, source));
    }

    /** Stands in for the time a person takes to answer. */
    private void waitForReply() {
        try {
            Thread.sleep(replyWaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Every tool logs its call and runs its handler under this instance's lock. */
    private FunctionTool tool(String name, String description, Map<String, Object> schema,
                              SideEffects sideEffects, Function<ToolInvocation, ToolResult> handler) {
        return FunctionTool.builder(name, description)
                .schema(schema)
                .sideEffects(sideEffects)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> {
                    synchronized (this) {
                        toolCalls.add(name);
                        return handler.apply(inv);
                    }
                })
                .build();
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> strings(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    private static Set<String> groups(ToolInvocation inv) {
        Set<String> groups = new LinkedHashSet<>();
        for (String g : list(inv, "groups")) {
            groups.add(lower(g));
        }
        return groups;
    }

    private static List<String> list(ToolInvocation inv, String key) {
        List<String> values = new ArrayList<>();
        if (inv.argument(key) instanceof List<?> raw) {
            for (Object o : raw) {
                if (o != null) {
                    values.add(o.toString().strip());
                }
            }
        }
        return values;
    }

    private static String lower(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
