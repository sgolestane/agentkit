package dev.agentkit.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.deferred.SubjectRecord;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * In-memory stand-ins for the systems an IT onboarding touches — the HRIS, Okta, GitHub, AWS, Salesforce,
 * Slack, Workday and the IT service desk — exposed as tools, and served to the agent host as an MCP
 * connector by {@link OnboardingConnector}. In a real deployment the company's own MCP servers take their
 * place; nothing about onboarding is code on the host.
 *
 * <p>Every write is recorded so the onboarding evals can verify a run by what it did to
 * these systems rather than by what the model said it did.
 *
 * <h2>Tools describe themselves</h2>
 *
 * <p>Each tool declares a {@link ToolDeclaration}: the system it belongs to, its {@link ToolEffect}, and
 * which argument names the person it acts on. The host reads the declarations to decide what needs a
 * person's confirmation and what a deferred action may use; the record of every {@link Grant} is derived
 * from them here.
 *
 * <h2>Only a hire's manager</h2>
 *
 * <p>A tool that grants or revokes takes {@code requested_by}, which the host binds to the person in the
 * conversation, and refuses unless that is the worker's manager in the HRIS — or the onboarding agent itself
 * ({@code actor}), which is who a deferred action runs as. So whatever a model writes, a manager can onboard
 * only their own hires.
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
 * <p>Okta has no native account expiry, so work that belongs on a later date is scheduled with the host's
 * deferred work. {@code hris_get_worker} is how the host looks a worker up as the subject of such work: who
 * they are (work email and employee id), whom it may tell (their manager), their HRIS record, and which
 * systems hold something of theirs.
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

    /** Access a {@link ToolEffect#GRANT} tool gave a person, recorded by the tool wrapper from its {@link ToolDeclaration}. */
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
    private final Map<String, ToolDeclaration> declarations = new LinkedHashMap<>();
    private final List<String> toolCalls = new ArrayList<>();
    private final String actor;
    private final long replyWaitMillis;

    private OnboardingSystems(String actor, long replyWaitMillis) {
        this.actor = lower(Objects.requireNonNull(actor, "actor"));
        this.replyWaitMillis = replyWaitMillis;
    }

    /** The systems a worker holds something in, from the grant record. */
    private synchronized List<String> holdings(Worker worker) {
        String email = lower(worker.email());
        return grants.stream().filter(g -> g.email().equals(email)).map(Grant::system).distinct().toList();
    }

    /** Empty systems: no workers, accounts or history. */
    public static OnboardingSystems create(String actor, long replyWaitMillis) {
        return new OnboardingSystems(actor, replyWaitMillis);
    }

    /**
     * The systems as {@code onboarding/hr.json} seeds them: the HRIS's hires, and what the other systems hold before any
     * of them is onboarded.
     *
     * @param actor           who the onboarding agent's deferred actions run as
     * @param replyWaitMillis how long a person takes to answer a Slack form
     */
    public static OnboardingSystems open(String actor, long replyWaitMillis) {
        OnboardingSystems systems = create(actor, replyWaitMillis);
        try (InputStream in = OnboardingSystems.class.getClassLoader().getResourceAsStream("onboarding/hr.json")) {
            Seed seed = JSON.readValue(Objects.requireNonNull(in, "onboarding/hr.json"), Seed.class);
            seed.workers().forEach(systems::addWorker);
            seed.okta_deactivated().forEach(u -> systems.addDeactivatedOktaUser(u.email(), u.first_name(), u.last_name()));
            seed.github_users().forEach(u -> systems.addGithubUser(u.login(), u.name()));
            seed.slack_preboarding().forEach(a -> systems.addPreboardingSlackAccount(a.email(), a.profile()));
            seed.github_username_replies().forEach(systems::setGithubUsernameReply);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load onboarding/hr.json", e);
        }
        return systems;
    }

    private record Seed(List<Map<String, String>> workers, List<OktaSeed> okta_deactivated, List<GithubSeed> github_users,
                        List<SlackSeed> slack_preboarding, Map<String, String> github_username_replies) {
    }

    private record OktaSeed(String email, String first_name, String last_name) {
    }

    private record GithubSeed(String login, String name) {
    }

    private record SlackSeed(String email, Map<String, String> profile) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** Every tool, with what it declares about itself. */
    public DeclaredTools catalog() {
        DeclaredTools catalog = new DeclaredTools();
        for (Tool tool : allTools()) { // declarations are registered as the tools are built
            synchronized (this) {
                catalog.add(tool, declarations.get(tool.name()));
            }
        }
        return catalog;
    }

    private List<Tool> allTools() {
        return List.of(
                hrisGetWorker(), onboardingCheck(),
                oktaCreateUser(), oktaReactivateUser(), oktaDeactivateUser(),
                slackGetProfile(), githubSearchUsers(), slackRequestGithubUsername(),
                githubAddMember(), githubRemoveMember(),
                awsGrantAccess(), awsRevokeAccess(),
                salesforceAssignSeat(), salesforceReleaseSeat(),
                slackCreateAccount(), slackRemoveAccount(), slackSendMessage(),
                shipLaptop(), itCreateTicket(), workdayEnrollBenefits());
    }

    // ---------------------------------------------------------------- HRIS

    private FunctionTool hrisGetWorker() {
        return tool("hris_get_worker",
                "Look a worker up in the HRIS by employee id or work email. Answers with one JSON object: their id, "
                        + "the identifiers they go by, their manager as contact, their HRIS record as facts, and the "
                        + "systems that hold something of theirs.",
                new ToolDeclaration("hris", ToolEffect.READ, "employee_id"),
                schema(Map.of("employee_id", str("Employee id, such as W-1001, or work email")), "employee_id"),
                SideEffects.NONE, inv -> {
                    Worker worker = workerFor(inv.stringArgument("employee_id"));
                    if (worker == null) {
                        return ToolResult.error("HRIS: no worker " + inv.stringArgument("employee_id"));
                    }
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("id", worker.employeeId());
                    record.put("identifiers", List.of(worker.employeeId(), lower(worker.email())));
                    record.put("contacts", worker.manager() == null ? List.of() : List.of(lower(worker.manager())));
                    record.put("facts", new java.util.TreeMap<>(worker.fields()));
                    record.put("holdings", holdings(worker));
                    try {
                        return ToolResult.ok(JSON.writeValueAsString(record));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * Whether the person asking may have this worker onboarded — only their manager may — and, when they may, the
     * worker's HRIS record and what each system already holds for them, so work already done is not done again.
     */
    private FunctionTool onboardingCheck() {
        return tool("onboarding_check",
                "Check, before onboarding a worker, that the person asking is their manager, and say what is already "
                        + "in place for them in each system. An error means they may not be onboarded by this person.",
                new ToolDeclaration("hris", ToolEffect.READ, "employee_id"),
                schema(Map.of("employee_id", str("Employee id, such as W-1001, or work email"),
                        "requested_by", str("Work email of the person asking")), "employee_id", "requested_by"),
                SideEffects.NONE, inv -> {
                    String refused = refusal(inv.stringArgument("employee_id"), inv.stringArgument("requested_by"));
                    if (refused != null) {
                        return ToolResult.error(refused.replace("can have access granted or removed for them",
                                "can onboard them"));
                    }
                    Worker worker = workerFor(inv.stringArgument("employee_id"));
                    String email = lower(worker.email());
                    Map<String, Object> already = new LinkedHashMap<>();
                    OktaUser user = okta.get(email);
                    if (user != null) {
                        already.put("okta", "account " + user.status() + " in groups " + user.groups());
                    }
                    SlackAccount account = slack.get(email);
                    if (account != null) {
                        already.put("slack", account.accountType() + " account in " + account.channels());
                    }
                    GithubIdentity identity = githubIdentities.get(email);
                    if (identity != null) {
                        String team = githubMembers.get(identity.username());
                        already.put("github", "account " + identity.username()
                                + (team == null ? ", not in the acme org" : " in the acme org, team " + team));
                    }
                    List<String> aws = awsGrants.stream().filter(g -> g.email().equals(email))
                            .map(AwsGrant::environment).distinct().toList();
                    if (!aws.isEmpty()) {
                        already.put("aws", "access to " + aws);
                    }
                    if (salesforceSeats.contains(email)) {
                        already.put("salesforce", "a seat");
                    }
                    if (benefitsEnrolled.contains(worker.employeeId())) {
                        already.put("workday", "enrolled in benefits");
                    }
                    if (shipments.stream().anyMatch(sh -> sh.email().equals(email))) {
                        already.put("laptop", "shipped");
                    } else if (tickets.stream().anyMatch(t -> t.category().equals("laptop_pickup")
                            && lower(t.forEmail()).equals(email))) {
                        already.put("laptop", "pickup ticket open");
                    }
                    Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("allowed", true);
                    answer.put("manager", lower(worker.manager()));
                    answer.put("worker", new java.util.TreeMap<>(worker.fields()));
                    answer.put("already_in_place", already);
                    try {
                        return ToolResult.ok(JSON.writeValueAsString(answer));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    // ---------------------------------------------------------------- Okta

    private FunctionTool oktaCreateUser() {
        return tool("okta_create_user", "Create a new Okta account and add it to groups.",
                new ToolDeclaration("okta", ToolEffect.GRANT, "email"),
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
                new ToolDeclaration("okta", ToolEffect.GRANT, "email"),
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
                new ToolDeclaration("okta", ToolEffect.REVOKE, "email"),
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
                new ToolDeclaration("slack", ToolEffect.READ, "email"),
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
                new ToolDeclaration("github", ToolEffect.READ, null),
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
                new ToolDeclaration("slack", ToolEffect.READ, "email"),
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
                new ToolDeclaration("github", ToolEffect.GRANT, "email"),
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
                new ToolDeclaration("github", ToolEffect.REVOKE, "email"),
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
                new ToolDeclaration("aws", ToolEffect.GRANT, "email"),
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
                new ToolDeclaration("aws", ToolEffect.REVOKE, "email"),
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
                new ToolDeclaration("salesforce", ToolEffect.GRANT, "email"),
                schema(Map.of("email", str("Work email")), "email"),
                SideEffects.IDEMPOTENT, inv -> {
                    String email = lower(inv.stringArgument("email"));
                    salesforceSeats.add(email);
                    return ToolResult.ok("Salesforce: seat assigned to " + email);
                });
    }

    private FunctionTool salesforceReleaseSeat() {
        return tool("salesforce_release_seat", "Release the Salesforce license seat assigned to a user.",
                new ToolDeclaration("salesforce", ToolEffect.REVOKE, "email"),
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
                new ToolDeclaration("slack", ToolEffect.GRANT, "email"),
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
                new ToolDeclaration("slack", ToolEffect.REVOKE, "email"),
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
                new ToolDeclaration("slack", ToolEffect.NOTIFY, "to_email"),
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
                new ToolDeclaration("laptop", ToolEffect.GRANT, "email"),
                schema(Map.of("email", str("Work email of the recipient"), "address", str("Full shipping address")),
                        "email", "address"),
                SideEffects.EXTERNAL, inv -> {
                    shipments.add(new Shipment(lower(inv.stringArgument("email")), inv.stringArgument("address")));
                    return ToolResult.ok("Shipping: laptop dispatched to " + inv.stringArgument("address"));
                });
    }

    private FunctionTool itCreateTicket() {
        return tool("it_create_ticket", "Open an IT service desk ticket.",
                new ToolDeclaration("it desk", ToolEffect.REQUEST, "for_email"),
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
                new ToolDeclaration("workday", ToolEffect.GRANT, "employee_id"),
                schema(Map.of("employee_id", str("Workday employee id")), "employee_id"),
                SideEffects.IDEMPOTENT, inv -> {
                    benefitsEnrolled.add(inv.stringArgument("employee_id"));
                    return ToolResult.ok("Workday: benefits enrolled for " + inv.stringArgument("employee_id"));
                });
    }

    // ---------------------------------------------------------------- state, for verification

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
        Worker worker = workerFor(subject);
        return lower(worker != null ? worker.email() : subject);
    }

    /** The worker an employee id or work email names, or null. */
    private synchronized Worker workerFor(String subject) {
        if (subject == null) {
            return null;
        }
        Worker byId = workers.get(subject.strip());
        if (byId != null) {
            return byId;
        }
        String email = lower(subject);
        return workers.values().stream().filter(w -> lower(w.email()).equals(email)).findFirst().orElse(null);
    }

    /**
     * Why {@code requestedBy} may not have a grant or revocation done for the worker {@code subject} names, or null
     * when they may: they are the worker's manager, or the onboarding agent itself.
     */
    private String refusal(String subject, String requestedBy) {
        Worker worker = workerFor(subject);
        if (worker == null) {
            return "HRIS: no worker " + subject + "; onboarding acts only on people the HRIS knows.";
        }
        String who = lower(requestedBy);
        if (who.isEmpty() || !(who.equals(actor) || who.equals(lower(worker.manager())))) {
            return "Only " + worker.fields().getOrDefault("name", worker.employeeId()) + "'s manager can have "
                    + "access granted or removed for them, and " + (who.isEmpty() ? "nobody" : who) + " is not.";
        }
        return null;
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
     * Builds a tool that declares what it is. Every call is logged and runs under this instance's lock. A tool that
     * grants or revokes also takes {@code requested_by} and refuses anyone but the worker's manager; a successful
     * {@link ToolEffect#GRANT} is recorded as a {@link Grant} — from the declaration, so no tool has to remember to.
     */
    @SuppressWarnings("unchecked")
    private FunctionTool tool(String name, String description, ToolDeclaration info, Map<String, Object> schema,
                              SideEffects sideEffects, Function<ToolInvocation, ToolResult> handler) {
        synchronized (this) {
            declarations.put(name, info);
        }
        boolean guarded = info.effect() == ToolEffect.GRANT || info.effect() == ToolEffect.REVOKE;
        Map<String, Object> described = schema;
        if (guarded) {
            Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
            properties.put("requested_by", str("Work email of the person asking: the worker's manager"));
            List<String> required = new ArrayList<>((List<String>) schema.get("required"));
            required.add("requested_by");
            described = Map.of("type", "object", "properties", properties, "required", required);
        }
        return FunctionTool.builder(name, description)
                .schema(described)
                .sideEffects(sideEffects)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> {
                    synchronized (this) {
                        toolCalls.add(name);
                        if (guarded) {
                            String refused = refusal(inv.stringArgument(info.subjectParam()),
                                    inv.stringArgument("requested_by"));
                            if (refused != null) {
                                return ToolResult.error(refused);
                            }
                        }
                        ToolResult result = handler.apply(inv);
                        if (info.effect() == ToolEffect.GRANT && !result.isError() && info.subjectParam() != null) {
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
