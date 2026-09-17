package dev.agentkit.examples.onboarding;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.examples.deferred.DeferredAction;
import dev.agentkit.examples.deferred.DeferredActionScheduler;
import dev.agentkit.examples.deferred.Effect;
import dev.agentkit.examples.deferred.SubjectRecord;
import dev.agentkit.examples.deferred.SubjectResolver;
import dev.agentkit.examples.deferred.ToolInfo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * In-memory stand-ins for the systems an IT onboarding touches — Okta, GitHub, AWS, Salesforce,
 * Slack, Workday, the IT service desk and a scheduler — exposed as tools. This is the product
 * side: it behaves the same for every customer, and a customer changes behavior through the
 * policy and prompts in {@link OnboardingConfig}, never here.
 *
 * <p>Every write is recorded so the onboarding evals can verify a run by what it did to
 * these systems rather than by what the model said it did. One instance serves one run.
 *
 * <h2>Tools describe themselves</h2>
 *
 * <p>Each tool declares a {@link ToolInfo}: the system it belongs to, its {@link Effect}, and which
 * argument names the person it acts on. The record of every {@link Grant} is derived from that, and
 * so are the bounds on deferred actions, which live in {@code dev.agentkit.examples.deferred} and
 * know nothing about onboarding.
 *
 * <h2>Resolving a missing GitHub username</h2>
 *
 * <p>{@code github_add_member} takes an email, not a username: it adds the GitHub account <em>on
 * record</em> for that person, and refuses when none is. Only the HRIS, the person's own
 * Slack profile, or the person answering a Slack form put one on record. A search records nothing:
 * a name match is only a suggestion the person must confirm.
 *
 * <h2>Deferred actions</h2>
 *
 * <p>Okta has no native account expiry. Work that belongs on a later date is scheduled with the
 * generic {@link DeferredActionScheduler}. Onboarding supplies only what is specific to it: the HRIS
 * as a {@link SubjectResolver} for subjects of kind {@code worker} (identified by work email or
 * employee id, with their manager as the one contact), and what a worker holds, from the grant record.
 */
public final class OnboardingSystems {

    /** An Okta account. */
    public record OktaUser(String email, String firstName, String lastName, String status,
                           Set<String> groups) {
    }

    /**
     * A worker as the HRIS knows them: whatever fields the customer's HRIS has.
     * {@code employee_id}, {@code work_email} and {@code manager} are the ones the product relies on.
     */
    public record Worker(Map<String, String> fields) {
        public Worker {
            fields = Map.copyOf(fields);
        }

        public String employeeId() {
            return fields.get("employee_id");
        }

        public String email() {
            return fields.get("work_email");
        }

        public String manager() {
            return fields.get("manager");
        }

        /** This worker as a deferred-action subject: referred to by work email or employee id, manager as contact. */
        public SubjectRecord asSubject() {
            return new SubjectRecord(WORKER, employeeId(), Set.of(email(), employeeId()),
                    manager() == null ? Set.of() : Set.of(manager()), fields);
        }
    }

    /** The subject kind onboarding schedules deferred actions for. */
    public static final String WORKER = "worker";

    /** Access a {@link Effect#GRANT} tool gave a person, recorded by the tool wrapper from its {@link ToolInfo}. */
    public record Grant(String email, String system, String tool) {
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

    /** The example's "today". Fixed, so scheduled dates and checks never drift with the calendar. */
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    /** What an HRIS field holds when it holds nothing. */
    private static final Set<String> NO_VALUE = Set.of("", "none", "n/a", "na", "unknown", "not on file", "-");

    /** GitHub's own username rule: alphanumerics and single inner hyphens, at most 39 characters. */
    private static final Pattern GITHUB_USERNAME =
            Pattern.compile("^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,38}$", Pattern.CASE_INSENSITIVE);

    private final Map<String, OktaUser> okta = new LinkedHashMap<>();
    private final List<String> oktaCreated = new ArrayList<>();
    private final List<String> oktaReactivated = new ArrayList<>();
    private final List<String> oktaDeactivated = new ArrayList<>();
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
    private final Map<String, Worker> workers = new LinkedHashMap<>();
    private final Set<Grant> grants = new LinkedHashSet<>();
    private final Map<String, ToolInfo> toolInfo = new LinkedHashMap<>();
    private final List<String> toolCalls = new ArrayList<>();
    private final long replyWaitMillis;

    private final DeferredActionScheduler scheduler;

    private OnboardingSystems(long replyWaitMillis) {
        this.replyWaitMillis = replyWaitMillis;
        this.scheduler = new DeferredActionScheduler(subjects(), () -> TODAY, this::holdings);
    }

    /** The HRIS, as the generic deferred-action code sees it. */
    public SubjectResolver subjects() {
        return new SubjectResolver() {
            @Override
            public Set<String> kinds() {
                return Set.of(WORKER);
            }

            @Override
            public Optional<SubjectRecord> resolve(String kind, String id) {
                synchronized (OnboardingSystems.this) {
                    return WORKER.equals(kind) ? Optional.ofNullable(workers.get(id)).map(Worker::asSubject)
                            : Optional.empty();
                }
            }
        };
    }

    /** The systems a subject holds something in, from the grant record. */
    private synchronized List<String> holdings(SubjectRecord subject) {
        return grants.stream().filter(g -> subject.refersTo(g.email())).map(Grant::system).distinct().toList();
    }

    /** Empty systems: no workers, accounts or history. A person answering a Slack form takes a second. */
    public static OnboardingSystems create() {
        return create(1_000);
    }

    /** Empty systems, where a person answering a Slack form takes {@code replyWaitMillis}. */
    public static OnboardingSystems create(long replyWaitMillis) {
        return new OnboardingSystems(replyWaitMillis);
    }

    // ---------------------------------------------------------------- existing state
    //
    // What the connected systems already hold before an onboarding runs. A real deployment reads
    // this from the systems themselves; the fakes are told it.

    /**
     * Adds a worker to the HRIS. A {@code github_username} field that holds a username (rather than a
     * placeholder such as "none" or "not on file") puts that GitHub account on record for them, with
     * the HRIS as its source.
     */
    public synchronized Worker addWorker(Map<String, String> fields) {
        Worker worker = new Worker(fields);
        Objects.requireNonNull(worker.employeeId(), "employee_id");
        Objects.requireNonNull(worker.email(), "work_email");
        workers.put(worker.employeeId(), worker);
        String github = fields.get("github_username");
        if (github != null && !NO_VALUE.contains(lower(github)) && GITHUB_USERNAME.matcher(github.strip()).matches()) {
            recordIdentity(lower(worker.email()), lower(github), "hris");
        }
        return worker;
    }

    /** An Okta account deactivated when its owner left. */
    public synchronized void addDeactivatedOktaUser(String email, String firstName, String lastName) {
        okta.put(lower(email), new OktaUser(lower(email), firstName, lastName, "DEACTIVATED", Set.of()));
    }

    /** A public GitHub account; {@code publicName} may be empty. */
    public synchronized void addGithubUser(String login, String publicName) {
        githubDirectory.put(lower(login), publicName == null ? "" : publicName);
    }

    /** A Slack account created before the start date, with whatever the person put on their profile. */
    public synchronized void addPreboardingSlackAccount(String email, Map<String, String> profile) {
        slack.put(lower(email), new SlackAccount(lower(email), "guest", Set.of("#welcome")));
        slackProfiles.put(lower(email), new LinkedHashMap<>(profile));
    }

    /** What a person submits when a Slack form asks them for their GitHub username; unset means they never answer. */
    public synchronized void setGithubUsernameReply(String email, String reply) {
        hireReplies.put(lower(email), reply);
    }

    /** Every tool, in one registry. Build a fresh registry per executor step. */
    public ToolRegistry registry() {
        return new SimpleToolRegistry(allTools());
    }

    /** Every tool, as a list. */
    public List<Tool> tools() {
        return allTools();
    }

    /** What the named tool declares, if it is one of these tools. */
    public synchronized Optional<ToolInfo> declared(String toolName) {
        if (!toolInfo.containsKey(toolName)) {
            allTools(); // declarations are registered as the tools are built
        }
        return Optional.ofNullable(toolInfo.get(toolName));
    }

    /** What the named tool declares about itself; throws for a tool that is not one of these. */
    public ToolInfo toolInfo(String toolName) {
        return declared(toolName).orElseThrow(() -> new IllegalArgumentException("Unknown tool " + toolName));
    }

    private List<Tool> allTools() {
        return List.of(
                oktaCreateUser(), oktaReactivateUser(), oktaDeactivateUser(),
                slackGetProfile(), githubSearchUsers(), slackRequestGithubUsername(),
                githubAddMember(), githubRemoveMember(),
                awsGrantAccess(), awsRevokeAccess(),
                salesforceAssignSeat(), salesforceReleaseSeat(),
                slackCreateAccount(), slackRemoveAccount(), slackSendMessage(),
                shipLaptop(), itCreateTicket(), workdayEnrollBenefits(), scheduleDeferredAction());
    }

    // ---------------------------------------------------------------- Okta

    private FunctionTool oktaCreateUser() {
        return tool("okta_create_user", "Create a new Okta account and add it to groups.",
                new ToolInfo("okta", Effect.GRANT, "email"),
                schema(Map.of(
                        "email", str("Work email"),
                        "first_name", str("First name"),
                        "last_name", str("Last name"),
                        "groups", strings("Okta group names")),
                        "email", "first_name", "last_name", "groups"),
                SideEffects.EXTERNAL, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    if (okta.containsKey(email)) {
                        return ToolResult.error("Okta: a user with email " + email
                                + " already exists (status " + okta.get(email).status() + ")");
                    }
                    okta.put(email, new OktaUser(email, inv.stringArgument("first_name"),
                            inv.stringArgument("last_name"), "ACTIVE", groups(inv)));
                    oktaCreated.add(email);
                    return ToolResult.ok("Okta: created " + email + " in groups " + groups(inv));
                });
    }

    private FunctionTool oktaReactivateUser() {
        return tool("okta_reactivate_user", "Reactivate a deactivated Okta account and set its groups.",
                new ToolInfo("okta", Effect.GRANT, "email"),
                schema(Map.of("email", str("Work email"), "groups", strings("Okta group names")),
                        "email", "groups"),
                SideEffects.EXTERNAL, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    OktaUser existing = okta.get(email);
                    if (existing == null || !existing.status().equals("DEACTIVATED")) {
                        return ToolResult.error("Okta: no deactivated user " + email);
                    }
                    okta.put(email, new OktaUser(email, existing.firstName(), existing.lastName(),
                            "ACTIVE", groups(inv)));
                    oktaReactivated.add(email);
                    return ToolResult.ok("Okta: reactivated " + email + " in groups " + groups(inv));
                });
    }

    private FunctionTool oktaDeactivateUser() {
        return tool("okta_deactivate_user", "Deactivate an Okta account.",
                new ToolInfo("okta", Effect.REVOKE, "email"),
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.EXTERNAL, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    OktaUser existing = okta.get(email);
                    if (existing == null || !existing.status().equals("ACTIVE")) {
                        return ToolResult.error("Okta: no active user " + email);
                    }
                    okta.put(email, new OktaUser(email, existing.firstName(), existing.lastName(),
                            "DEACTIVATED", existing.groups()));
                    oktaDeactivated.add(email);
                    return ToolResult.ok("Okta: deactivated " + email);
                });
    }

    // ---------------------------------------------------------------- GitHub

    private FunctionTool slackGetProfile() {
        return tool("slack_get_profile",
                "Read a person's Slack profile fields. If the person has filled in the GitHub field of "
                        + "their own profile and that account exists, it is recorded as their GitHub "
                        + "account (source: slack_profile).",
                new ToolInfo("slack", Effect.READ, "email"),
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
                new ToolInfo("github", Effect.READ, null),
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
                new ToolInfo("slack", Effect.READ, "email"),
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
                new ToolInfo("github", Effect.GRANT, "email"),
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

    private FunctionTool githubRemoveMember() {
        return tool("github_remove_member",
                "Remove a person's GitHub account on record from the acme org. Takes the person's work email.",
                new ToolInfo("github", Effect.REVOKE, "email"),
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    GithubIdentity identity = githubIdentities.get(email);
                    if (identity == null || githubMembers.remove(identity.username()) == null) {
                        return ToolResult.error("GitHub: " + email + " is not a member of the acme org");
                    }
                    return ToolResult.ok("GitHub: removed " + identity.username() + " from the acme org");
                });
    }

    // ---------------------------------------------------------------- AWS, Salesforce

    private FunctionTool awsGrantAccess() {
        return tool("aws_grant_access", "Grant a user access to an AWS environment.",
                new ToolInfo("aws", Effect.GRANT, "email"),
                schema(Map.of("email", str("Work email"),
                                "environment", Map.of("type", "string", "enum", List.of("staging", "production"))),
                        "email", "environment"),
                SideEffects.IDEMPOTENT, inv -> {
                    AwsGrant grant = new AwsGrant(lower(inv.stringArgument("email")),
                            lower(inv.stringArgument("environment")));
                    if (!Set.of("staging", "production").contains(grant.environment())) {
                        return ToolResult.error("AWS: unknown environment \"" + grant.environment() + "\"");
                    }
                    awsGrants.add(grant);
                    return ToolResult.ok("AWS: granted " + grant.environment() + " to " + grant.email());
                });
    }

    private FunctionTool awsRevokeAccess() {
        return tool("aws_revoke_access", "Revoke a user's access to an AWS environment.",
                new ToolInfo("aws", Effect.REVOKE, "email"),
                schema(Map.of("email", str("Work email"),
                                "environment", Map.of("type", "string", "enum", List.of("staging", "production"))),
                        "email", "environment"),
                SideEffects.IDEMPOTENT, inv -> {
                    AwsGrant grant = new AwsGrant(lower(inv.stringArgument("email")),
                            lower(inv.stringArgument("environment")));
                    if (!awsGrants.remove(grant)) {
                        return ToolResult.error("AWS: " + grant.email() + " has no " + grant.environment() + " access");
                    }
                    return ToolResult.ok("AWS: revoked " + grant.environment() + " from " + grant.email());
                });
    }

    private FunctionTool salesforceAssignSeat() {
        return tool("salesforce_assign_seat", "Assign a Salesforce license seat to a user.",
                new ToolInfo("salesforce", Effect.GRANT, "email"),
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    salesforceSeats.add(email);
                    return ToolResult.ok("Salesforce: seat assigned to " + email);
                });
    }

    private FunctionTool salesforceReleaseSeat() {
        return tool("salesforce_release_seat", "Release the Salesforce license seat assigned to a user.",
                new ToolInfo("salesforce", Effect.REVOKE, "email"),
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    if (!salesforceSeats.remove(email)) {
                        return ToolResult.error("Salesforce: no seat assigned to " + email);
                    }
                    return ToolResult.ok("Salesforce: seat released from " + email);
                });
    }

    // ---------------------------------------------------------------- Slack

    private FunctionTool slackCreateAccount() {
        return tool("slack_create_account",
                "Create a Slack account (or convert a pre-boarding account) and set its channels.",
                new ToolInfo("slack", Effect.GRANT, "email"),
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

    private FunctionTool slackRemoveAccount() {
        return tool("slack_remove_account", "Deactivate a person's Slack account.",
                new ToolInfo("slack", Effect.REVOKE, "email"),
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    if (slack.remove(email) == null) {
                        return ToolResult.error("Slack: no Slack account for " + email);
                    }
                    return ToolResult.ok("Slack: deactivated the account for " + email);
                });
    }

    private FunctionTool slackSendMessage() {
        return tool("slack_send_message", "Send a Slack direct message to a user by email.",
                new ToolInfo("slack", Effect.NOTIFY, "to_email"),
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
                new ToolInfo("laptop", Effect.GRANT, "email"),
                schema(Map.of("email", str("Work email of the recipient"), "address", str("Full shipping address")),
                        "email", "address"),
                SideEffects.EXTERNAL, inv -> {
                    shipments.add(new Shipment(lower(inv.stringArgument("email")), inv.stringArgument("address")));
                    return ToolResult.ok("Shipping: laptop dispatched to " + inv.stringArgument("address"));
                });
    }

    private FunctionTool itCreateTicket() {
        return tool("it_create_ticket", "Open an IT service desk ticket.",
                new ToolInfo("it desk", Effect.REQUEST, "for_email"),
                schema(Map.of(
                                "category", Map.of("type", "string",
                                        "enum", List.of("laptop_pickup", "laptop_return", "access_request")),
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
                new ToolInfo("workday", Effect.GRANT, "employee_id"),
                schema(Map.of("employee_id", str("Workday employee id")), "employee_id"),
                SideEffects.IDEMPOTENT, inv -> {
                    benefitsEnrolled.add(inv.stringArgument("employee_id"));
                    return ToolResult.ok("Workday: benefits enrolled for " + inv.stringArgument("employee_id"));
                });
    }

    // ---------------------------------------------------------------- Scheduler

    private FunctionTool scheduleDeferredAction() {
        return tool(DeferredActionScheduler.TOOL_NAME, scheduler.description(),
                new ToolInfo("scheduler", Effect.SCHEDULE, "subject_id"),
                scheduler.schema(), SideEffects.IDEMPOTENT, scheduler::schedule);
    }

    // ---------------------------------------------------------------- state, for verification

    public List<DeferredAction> deferredActions() {
        return scheduler.scheduled();
    }

    public synchronized List<Grant> grants() {
        return List.copyOf(grants);
    }

    public synchronized Worker worker(String employeeId) {
        return workers.get(employeeId);
    }

    public synchronized OktaUser oktaUser(String email) {
        return okta.get(email);
    }

    public synchronized List<String> oktaCreated() {
        return List.copyOf(oktaCreated);
    }

    public synchronized List<String> oktaReactivated() {
        return List.copyOf(oktaReactivated);
    }

    public synchronized List<String> oktaDeactivated() {
        return List.copyOf(oktaDeactivated);
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

    /** A subject argument as a work email: an employee id is looked up in the HRIS when it can be. */
    private String subjectEmail(String subject) {
        Worker worker = workers.get(subject);
        return lower(worker != null ? worker.email() : subject);
    }

    /** Stands in for the time a person takes to answer. */
    private void waitForReply() {
        try {
            Thread.sleep(replyWaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Builds a tool that declares what it is. Every call is logged and runs under this instance's
     * lock, and a successful call to a {@link Effect#GRANT} tool is recorded as a {@link Grant} —
     * from the declaration, so no tool has to remember to do it.
     */
    private FunctionTool tool(String name, String description, ToolInfo info, Map<String, Object> schema,
                              SideEffects sideEffects, Function<ToolInvocation, ToolResult> handler) {
        synchronized (this) {
            toolInfo.put(name, info);
        }
        return FunctionTool.builder(name, description)
                .schema(schema)
                .sideEffects(sideEffects)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> {
                    synchronized (this) {
                        toolCalls.add(name);
                        ToolResult result = handler.apply(inv);
                        if (info.effect() == Effect.GRANT && !result.isError() && info.subjectParam() != null) {
                            grants.add(new Grant(subjectEmail(inv.stringArgument(info.subjectParam())), info.system(), name));
                        }
                        return result;
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

    static String lower(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
