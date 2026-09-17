package dev.agentkit.examples.onboarding;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.planning.LlmPlanner;
import dev.agentkit.core.planning.PlanExecution;
import dev.agentkit.core.planning.PlanningAgent;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.OneLine;
import dev.agentkit.examples.onboarding.OnboardingSystems.AwsGrant;
import dev.agentkit.examples.deferred.DeferredAction;
import dev.agentkit.examples.deferred.DeferredActions;
import dev.agentkit.examples.onboarding.OnboardingSystems.GithubIdentity;
import dev.agentkit.examples.onboarding.OnboardingSystems.ItTicket;
import dev.agentkit.examples.onboarding.OnboardingSystems.OktaUser;
import dev.agentkit.examples.onboarding.OnboardingSystems.SlackAccount;
import dev.agentkit.examples.onboarding.OnboardingSystems.SlackQuestion;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * IT onboarding with {@link PlanningAgent}, where the branching lives in the prompt.
 *
 * <p>The goal is the same onboarding policy for every hire — full of conditions (rehire or
 * new, full-time or contractor, remote or on-site, engineering or sales, production access
 * or not, GitHub username on file or not) — followed by one hire's facts. The
 * {@link LlmPlanner} resolves every condition against those facts <em>during planning</em>
 * and emits a flat list of unconditional tasks; the executor then carries each task out
 * against fake Okta, GitHub, AWS, Salesforce, Slack, Workday and IT-desk tools
 * ({@link OnboardingSystems}).
 *
 * <p>What planning cannot resolve is what only running reveals. "The GitHub username is
 * not on file" is a fact, so the planner turns it into a step: <em>obtain it</em>. How to
 * obtain it — the person's own Slack profile, a search, or asking them — depends on what
 * those lookups return, so that is left to the executor, which is an agent precisely so it
 * can make that call. The four {@code github-*} scenarios cover each outcome.
 *
 * <p>Work that belongs on a later date is scheduled, not done. The policy says that a hire with a
 * termination date gets two deferred actions once their access is set up — a reminder to their
 * manager 14 days before, and on the day the removal of everything they were given — and the
 * executor writes each action's goal from the policy and the results of the earlier steps. Nothing
 * about those actions is defined in code; {@link DeferredActions} only bounds what one may do when
 * it runs. The run cannot wait months, so it verifies that both were recorded for the right dates
 * and that the goal of the one on the termination date covers every system onboarding set up.
 *
 * <p>The policy and both prompts are what a customer changes, and they are files, not code
 * ({@link OnboardingConfig}): {@code ONBOARDING_POLICY_FILE}, {@code ONBOARDING_PLANNER_PROMPT_FILE}
 * and {@code ONBOARDING_EXECUTOR_PROMPT_FILE} each replace a shipped default.
 *
 * <p>Each scenario is verified on the plan (flat, with branches that do not apply pruned)
 * and on the fake systems' final state, including which path obtained a missing value.
 *
 * <pre>
 * export OPENROUTER_API_KEY=sk-or-...
 * export ONBOARDING_MODEL=anthropic/claude-sonnet-5   # optional
 * ./mvnw -f agentkit-examples/pom.xml exec:exec \
 *     -Dexec.mainClass=dev.agentkit.examples.onboarding.OnboardingExample
 *
 * # a subset: ONBOARDING_SCENARIOS="github-found github-missing" (this module's exec:exec
 * # configuration does not forward -Dexec.args to the program)
 * </pre>
 */
public final class OnboardingExample {

    static final String DEFAULT_MODEL = "anthropic/claude-sonnet-5";

    /** A step that only notes a branch was skipped, rather than doing anything. */
    static final Pattern NO_OP = Pattern.compile(
            "\\b(not applicable|n/a|skip|skipped|does not apply|doesn't apply|no action|not needed)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Conditional wording that must not survive planning. */
    static final Pattern CONDITIONAL = Pattern.compile("\\b(if|otherwise|unless|else|whether|depending on)\\b",
            Pattern.CASE_INSENSITIVE);

    /** One new hire: the facts the goal carries, and how to check the outcome. */
    record Scenario(String name, String email, String managerEmail, String facts,
                    Set<String> planMustNotMention, BiConsumer<OnboardingSystems, Checks> expectations) {
    }

    private OnboardingExample() {
    }

    public static void main(String[] args) {
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            System.err.println("Set " + OpenRouterLlmClient.API_KEY_ENV + " to run this example.");
            System.exit(2);
        }
        String model = System.getenv().getOrDefault("ONBOARDING_MODEL", DEFAULT_MODEL);
        OnboardingConfig config = OnboardingConfig.fromEnv();
        LlmClient llm = OpenRouterLlmClient.builder(key).title("agentkit onboarding example").build();

        String subset = args.length > 0 ? String.join(" ", args)
                : System.getenv().getOrDefault("ONBOARDING_SCENARIOS", "");
        List<String> wanted = subset.isBlank() ? List.of() : List.of(subset.strip().split("[\\s,]+"));
        List<String> results = new ArrayList<>();
        int failed = 0;
        for (Scenario scenario : scenarios()) {
            if (!wanted.isEmpty() && !wanted.contains(scenario.name())) {
                continue;
            }
            boolean passed = run(scenario, config, llm, model);
            results.add((passed ? "  PASS  " : "  FAIL  ") + scenario.name());
            failed += passed ? 0 : 1;
        }
        System.out.println("\nSummary:");
        results.forEach(System.out::println);
        System.out.println(failed == 0 ? "ALL SCENARIOS PASSED" : failed + " SCENARIO(S) FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** The goal handed to the planning agent: the customer's policy plus one hire's facts. */
    static Goal goalFor(Scenario scenario, OnboardingConfig config) {
        return Goal.of("Onboard the new hire below by following the onboarding policy.\n\n"
                + config.policy() + "\n\nNew hire:\n" + scenario.facts());
    }

    static PlanningAgent planningAgent(OnboardingConfig onboarding, LlmClient llm, String model,
                                       OnboardingSystems systems) {
        LlmPlanner planner = new LlmPlanner(llm, model, onboarding.plannerPrompt(), 1024);
        AgentConfig config = AgentConfig.builder(model)
                .systemPrompt(onboarding.executorPrompt())
                .maxSteps(8)
                .maxTokens(1024)
                .build();
        return new PlanningAgent(planner, () -> Agent.builder(llm, systems.registry(), config)
                .observer(new ToolCallPrinter())
                .build());
    }

    static boolean run(Scenario scenario, OnboardingConfig config, LlmClient llm, String model) {
        System.out.println("\n=== " + scenario.name() + " (" + scenario.email() + ") ===");
        OnboardingSystems systems = OnboardingSystems.seeded();
        PlanExecution execution = planningAgent(config, llm, model, systems).run(goalFor(scenario, config));

        System.out.println("\nPlan:");
        List<String> steps = execution.plan().steps();
        for (int i = 0; i < steps.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + steps.get(i));
        }
        System.out.printf("%nRun: %s after %d/%d step(s), %d loop turns, %d in / %d out tokens%n",
                execution.overall().stopReason(), execution.stepResults().size(), steps.size(),
                execution.overall().steps(), execution.overall().usage().inputTokens(),
                execution.overall().usage().outputTokens());
        GithubIdentity identity = systems.githubIdentity(scenario.email());
        System.out.println("GitHub account on record: " + (identity == null ? "none"
                : identity.username() + " (source: " + identity.source() + ")")
                + ", Slack questions sent: " + systems.slackQuestions().size());
        for (DeferredAction action : systems.deferredActions()) {
            System.out.println("\nDeferred action " + action.id() + " runs " + action.runOn() + " (" + action.when()
                    + "), scheduled " + action.scheduledOn() + ". It will run with tools "
                    + DeferredActions.restrict(systems.tools(), systems::declared).tools().stream().map(t -> t.name()).toList()
                    + " and this goal:");
            systems.subjects().resolve(action.subjectKind(), action.subjectId()).ifPresent(subject ->
                    DeferredActions.goalFor(action, subject, action.runOn()).description().lines()
                            .forEach(line -> System.out.println("    | " + line)));
        }

        Checks checks = new Checks();
        checks.that("run completed", execution.isSuccess());
        checks.that("plan is not empty", !steps.isEmpty());
        for (String step : steps) {
            checks.that("step is unconditional: " + step, !CONDITIONAL.matcher(step).find());
        }
        checks.that("no step is a skip / not-applicable note",
                steps.stream().noneMatch(step -> NO_OP.matcher(step).find()));
        // The manager summary may legitimately say what was skipped ("not enrolled in benefits"),
        // so branch pruning is checked on the action steps only.
        String plan = steps.stream()
                .filter(step -> !step.toLowerCase(Locale.ROOT).contains(scenario.managerEmail()))
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);
        for (String word : scenario.planMustNotMention()) {
            // Word-prefix match, so "ship" catches "shipping" but not "membership".
            boolean mentioned = Pattern.compile("\\b" + Pattern.quote(word)).matcher(plan).find();
            checks.that("plan prunes branches that do not apply (no \"" + word + "\")", !mentioned);
        }
        scenario.expectations().accept(systems, checks);
        checks.that("manager was messaged", systems.slackMessages().stream()
                .anyMatch(m -> m.to().equals(scenario.managerEmail())));

        System.out.println("\nVerification:");
        checks.print();
        return checks.passed();
    }

    static List<Scenario> scenarios() {
        return List.of(
                new Scenario("engineer", "priya.natarajan@acme.example", "dana.kim@acme.example", """
                        - Name: Priya Natarajan
                        - Employee id: W-1001
                        - Work email: priya.natarajan@acme.example
                        - Title: Senior Backend Engineer
                        - Department: Engineering
                        - Employment type: full-time
                        - Termination date: none
                        - Rehire: no
                        - Work location: remote, home address Rua Augusta 100, 1100-053 Lisbon, Portugal
                        - GitHub username: priyan-dev, team: backend
                        - Production AWS access requested: no
                        - Manager: dana.kim@acme.example""",
                        Set.of("salesforce", "guest", "reactivat", "contractors", "pickup", "pick up", "obtain", "deferred", "terminat"),
                        (s, c) -> {
                            String email = "priya.natarajan@acme.example";
                            OktaUser okta = s.oktaUser(email);
                            c.that("Okta account created (not reactivated)",
                                    s.oktaCreated().contains(email) && s.oktaReactivated().isEmpty());
                            c.that("Okta groups include engineering and all-staff, not contractors", okta != null
                                    && okta.groups().containsAll(Set.of("engineering", "all-staff"))
                                    && !okta.groups().contains("contractors"));
                            c.that("GitHub: priyan-dev on team backend",
                                    "backend".equals(s.githubMembers().get("priyan-dev")));
                            c.that("no Slack question sent (username was on file)", s.slackQuestions().isEmpty());
                            c.that("AWS: staging granted, production not",
                                    s.awsGrants().contains(new AwsGrant(email, "staging"))
                                            && !s.awsGrants().contains(new AwsGrant(email, "production")));
                            c.that("no access_request ticket", noTicket(s, "access_request"));
                            c.that("no Salesforce seat", s.salesforceSeats().isEmpty());
                            slackIs(c, s.slackAccount(email), "member", Set.of("#general", "#engineering"));
                            c.that("laptop shipped to Lisbon", s.shipments().size() == 1
                                    && s.shipments().get(0).address().contains("Lisbon"));
                            c.that("no laptop_pickup ticket", noTicket(s, "laptop_pickup"));
                            c.that("benefits enrolled", s.benefitsEnrolled().equals(Set.of("W-1001")));
                            c.that("no deferred actions scheduled", s.deferredActions().isEmpty());
                        }),

                new Scenario("contractor", "marcus.bell@acme.example", "lena.ortiz@acme.example", """
                        - Name: Marcus Bell
                        - Employee id: W-1002
                        - Work email: marcus.bell@acme.example
                        - Title: Account Executive
                        - Department: Sales
                        - Employment type: contractor
                        - Start date: 2026-10-01
                        - Termination date: 2026-12-31 (end of contract)
                        - Rehire: no
                        - Work location: on-site, New York office
                        - GitHub username: none
                        - Production AWS access requested: no
                        - Manager: lena.ortiz@acme.example""",
                        Set.of("github", "aws", "benefit", "reactivat", "all-staff", "ship", "obtain"),
                        (s, c) -> {
                            String email = "marcus.bell@acme.example";
                            OktaUser okta = s.oktaUser(email);
                            c.that("Okta account created", s.oktaCreated().contains(email));
                            c.that("Okta groups include sales and contractors, not all-staff", okta != null
                                    && okta.groups().containsAll(Set.of("sales", "contractors"))
                                    && !okta.groups().contains("all-staff"));
                            c.that("Salesforce seat assigned", s.salesforceSeats().equals(Set.of(email)));
                            c.that("no GitHub membership", s.githubMembers().isEmpty());
                            c.that("no Slack question sent", s.slackQuestions().isEmpty());
                            c.that("no AWS access", s.awsGrants().isEmpty());
                            slackIs(c, s.slackAccount(email), "guest", Set.of("#sales"));
                            c.that("laptop_pickup ticket names the New York office", pickupAt(s, "new york"));
                            c.that("no laptop shipped", s.shipments().isEmpty());
                            c.that("no benefits enrollment", s.benefitsEnrolled().isEmpty());
                            terminationDeferred(c, s, "W-1002", LocalDate.of(2026, 12, 31));
                        }),

                new Scenario("rehire", "maria.chen@acme.example", "sam.okafor@acme.example", """
                        - Name: Maria Chen
                        - Employee id: W-1003
                        - Work email: maria.chen@acme.example
                        - Title: Site Reliability Engineer
                        - Department: Engineering
                        - Employment type: full-time
                        - Termination date: none
                        - Rehire: yes (previous Okta account is deactivated)
                        - Work location: on-site, San Francisco office
                        - GitHub username: mchen-sre, team: sre
                        - Production AWS access requested: yes
                        - Manager: sam.okafor@acme.example""",
                        Set.of("salesforce", "guest", "contractors", "ship", "obtain", "deferred", "terminat"),
                        (s, c) -> {
                            String email = "maria.chen@acme.example";
                            OktaUser okta = s.oktaUser(email);
                            c.that("Okta account reactivated (not created)",
                                    s.oktaReactivated().contains(email) && s.oktaCreated().isEmpty());
                            c.that("Okta groups include engineering and all-staff", okta != null
                                    && "ACTIVE".equals(okta.status())
                                    && okta.groups().containsAll(Set.of("engineering", "all-staff")));
                            c.that("GitHub: mchen-sre on team sre", "sre".equals(s.githubMembers().get("mchen-sre")));
                            c.that("no Slack question sent (username was on file)", s.slackQuestions().isEmpty());
                            c.that("AWS: staging granted, production NOT granted",
                                    s.awsGrants().contains(new AwsGrant(email, "staging"))
                                            && !s.awsGrants().contains(new AwsGrant(email, "production")));
                            c.that("access_request ticket for production", s.tickets().stream()
                                    .anyMatch(t -> t.category().equals("access_request")
                                            && t.summary().toLowerCase(Locale.ROOT).contains("production")));
                            slackIs(c, s.slackAccount(email), "member", Set.of("#general", "#engineering"));
                            c.that("laptop_pickup ticket names the San Francisco office", pickupAt(s, "san francisco"));
                            c.that("no laptop shipped", s.shipments().isEmpty());
                            c.that("benefits enrolled", s.benefitsEnrolled().equals(Set.of("W-1003")));
                            c.that("no deferred actions scheduled", s.deferredActions().isEmpty());
                        }),

                // ---- GitHub username not on file: the agent has to obtain it.

                austinEngineer("github-found", "Noah Fischer", "W-1004", "noah.fischer@acme.example", "platform",
                        (s, c) -> {
                            String email = "noah.fischer@acme.example";
                            identityIs(c, s, email, "nfischer-code", "slack_profile");
                            c.that("did not ask the hire (their own profile settled it)", s.slackQuestions().isEmpty());
                            c.that("GitHub members are exactly {nfischer-code: platform}",
                                    s.githubMembers().equals(Map.of("nfischer-code", "platform")));
                        }),

                austinEngineer("github-asked", "Aisha Rahman", "W-1005", "aisha.rahman@acme.example", "data",
                        (s, c) -> {
                            String email = "aisha.rahman@acme.example";
                            c.that("looked it up with tools before asking", lookedUpBeforeAsking(s));
                            c.that("asked the hire exactly once", s.slackQuestions().size() == 1);
                            c.that("suggested no username (nothing plausible was found)", s.slackQuestions().stream()
                                    .allMatch(q -> q.suggestedUsername() == null));
                            identityIs(c, s, email, "ar-codes", "confirmed_by_hire");
                            c.that("GitHub members are exactly {ar-codes: data}",
                                    s.githubMembers().equals(Map.of("ar-codes", "data")));
                        }),

                austinEngineer("github-guessed", "Jordan Lee", "W-1006", "jordan.lee@acme.example", "frontend",
                        (s, c) -> {
                            String email = "jordan.lee@acme.example";
                            List<String> calls = s.toolCalls();
                            c.that("searched GitHub before asking", calls.contains("github_search_users")
                                    && calls.indexOf("github_search_users") < calls.indexOf("slack_request_github_username"));
                            c.that("asked the hire to confirm the search match jlee", s.slackQuestions().stream()
                                    .map(SlackQuestion::suggestedUsername)
                                    .anyMatch("jlee"::equals));
                            identityIs(c, s, email, "jl-builds", "confirmed_by_hire");
                            c.that("GitHub members are exactly {jl-builds: frontend} (no search hit added)",
                                    s.githubMembers().equals(Map.of("jl-builds", "frontend")));
                        }),

                austinEngineer("github-missing", "Alex Rivera", "W-1007", "alex.rivera@acme.example", "infra",
                        (s, c) -> {
                            String email = "alex.rivera@acme.example";
                            c.that("looked it up with tools before asking", lookedUpBeforeAsking(s));
                            c.that("asked the hire", !s.slackQuestions().isEmpty());
                            c.that("no GitHub account on record", s.githubIdentity(email) == null);
                            c.that("no GitHub membership", s.githubMembers().isEmpty());
                            c.that("manager told GitHub is still pending", s.slackMessages().stream()
                                    .filter(m -> m.to().equals("dana.kim@acme.example"))
                                    .map(m -> m.text().toLowerCase(Locale.ROOT))
                                    .anyMatch(t -> t.contains("github") && PENDING.matcher(t).find()));
                        }));
    }

    private static final Pattern PENDING = Pattern.compile(
            "pending|missing|unknown|outstanding|awaiting|not yet|not been|could not|couldn't|unable|no reply|not respond");

    /**
     * A full-time, on-site engineer in Austin whose GitHub username is not on file, with the
     * checks every such hire shares plus the ones about how the username was (or was not) obtained.
     */
    private static Scenario austinEngineer(String name, String fullName, String employeeId, String email,
                                           String team, BiConsumer<OnboardingSystems, Checks> github) {
        return new Scenario(name, email, "dana.kim@acme.example", """
                - Name: %s
                - Employee id: %s
                - Work email: %s
                - Title: Software Engineer
                - Department: Engineering
                - Employment type: full-time
                - Termination date: none
                - Rehire: no
                - Work location: on-site, Austin office
                - GitHub username: not on file, team: %s
                - Production AWS access requested: no
                - Manager: dana.kim@acme.example""".formatted(fullName, employeeId, email, team),
                Set.of("salesforce", "guest", "reactivat", "contractors", "ship", "production", "deferred", "terminat"),
                (s, c) -> {
                    OktaUser okta = s.oktaUser(email);
                    c.that("Okta account created with engineering and all-staff", s.oktaCreated().contains(email)
                            && okta != null && okta.groups().containsAll(Set.of("engineering", "all-staff")));
                    c.that("AWS: staging only", s.awsGrants().equals(List.of(new AwsGrant(email, "staging"))));
                    slackIs(c, s.slackAccount(email), "member", Set.of("#general", "#engineering"));
                    c.that("laptop_pickup ticket names the Austin office", pickupAt(s, "austin"));
                    c.that("benefits enrolled", s.benefitsEnrolled().equals(Set.of(employeeId)));
                    c.that("no deferred actions scheduled", s.deferredActions().isEmpty());
                    github.accept(s, c);
                });
    }

    /**
     * A termination date is scheduled, not acted on, so this checks what was recorded: exactly one
     * action 14 days before the termination date and one on it, for this worker; a reminder that
     * names the manager and the date; and a goal on the termination date that covers every system
     * onboarding actually set up — judged from the systems' own state, not from the grant record the
     * scheduler reported to the model.
     */
    private static void terminationDeferred(Checks c, OnboardingSystems s, String employeeId, LocalDate terminationDate) {
        List<DeferredAction> tasks = s.deferredActions();
        c.that("deferred actions are exactly on " + terminationDate.minusDays(14) + " and " + terminationDate,
                tasks.size() == 2 && tasks.stream().allMatch(t -> t.subjectKind().equals(OnboardingSystems.WORKER)
                        && t.subjectId().equals(employeeId))
                        && tasks.stream().map(DeferredAction::runOn).collect(java.util.stream.Collectors.toSet())
                                .equals(Set.of(terminationDate.minusDays(14), terminationDate)));

        OnboardingSystems.Worker worker = s.worker(employeeId);
        String email = worker.email();
        tasks.stream().filter(t -> t.runOn().equals(terminationDate.minusDays(14))).findFirst().ifPresent(reminder -> {
            String goal = reminder.goal().toLowerCase(Locale.ROOT);
            c.that("reminder goal names the manager and the termination date",
                    goal.contains(worker.manager()) && goal.contains(terminationDate.toString()));
        });

        List<String> provisioned = new ArrayList<>();
        if (s.oktaUser(email) != null) provisioned.add("okta");
        if (s.slackAccount(email) != null) provisioned.add("slack");
        if (s.salesforceSeats().contains(email)) provisioned.add("salesforce");
        if (s.githubIdentity(email) != null && s.githubMembers().containsKey(s.githubIdentity(email).username())) {
            provisioned.add("github");
        }
        if (s.awsGrants().stream().anyMatch(g -> g.email().equals(email))) provisioned.add("aws");
        if (s.tickets().stream().anyMatch(t -> t.forEmail().equals(email) && t.category().equals("laptop_pickup"))
                || s.shipments().stream().anyMatch(sh -> sh.email().equals(email))) provisioned.add("laptop");
        tasks.stream().filter(t -> t.runOn().equals(terminationDate)).findFirst().ifPresent(removal -> {
            String goal = removal.goal().toLowerCase(Locale.ROOT);
            c.that("termination-day goal covers every system onboarding set up " + provisioned,
                    provisioned.stream().allMatch(goal::contains));
            c.that("termination-day goal names the worker and notifies the manager",
                    goal.contains(email) && goal.contains(worker.manager()));
        });
    }

    private static boolean lookedUpBeforeAsking(OnboardingSystems s) {
        List<String> calls = s.toolCalls();
        int asked = calls.indexOf("slack_request_github_username");
        int profile = calls.indexOf("slack_get_profile");
        int search = calls.indexOf("github_search_users");
        return asked >= 0 && ((profile >= 0 && profile < asked) || (search >= 0 && search < asked));
    }

    private static void identityIs(Checks c, OnboardingSystems s, String email, String username, String source) {
        GithubIdentity identity = s.githubIdentity(email);
        c.that("GitHub account on record is " + username + " from " + source, identity != null
                && identity.username().equals(username) && identity.source().equals(source));
    }

    private static boolean pickupAt(OnboardingSystems s, String office) {
        return s.tickets().stream().anyMatch(t -> t.category().equals("laptop_pickup")
                && t.summary().toLowerCase(Locale.ROOT).contains(office));
    }

    private static boolean noTicket(OnboardingSystems s, String category) {
        return s.tickets().stream().map(ItTicket::category).noneMatch(category::equals);
    }

    private static void slackIs(Checks c, SlackAccount account, String type, Set<String> channels) {
        c.that("Slack " + type + " account in exactly " + channels, account != null
                && account.accountType().equals(type) && account.channels().equals(channels));
    }

    /** A named list of pass/fail checks. */
    static final class Checks {
        private final List<String> lines = new ArrayList<>();
        private boolean passed = true;

        void that(String description, boolean ok) {
            lines.add((ok ? "  PASS  " : "  FAIL  ") + description);
            passed &= ok;
        }

        boolean passed() {
            return passed;
        }

        void print() {
            lines.forEach(System.out::println);
            System.out.println(passed ? "  => scenario PASSED" : "  => scenario FAILED");
        }
    }

    /** Prints each tool call as the executor makes it. */
    private static final class ToolCallPrinter implements AgentObserver {
        @Override
        public void onToolResult(AgentRun run, int step, ToolInvocation proposed, ToolInvocation effective,
                                 ToolResult result, Disposition disposition) {
            System.out.println("  -> " + effective.name() + " " + effective.arguments()
                    + (result.isError() ? "  !! " : "  ok: ") + OneLine.of(result.content()));
        }
    }
}
