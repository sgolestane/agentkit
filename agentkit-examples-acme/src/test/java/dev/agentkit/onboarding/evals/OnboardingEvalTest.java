package dev.agentkit.onboarding.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.systems.CompanySystems;
import dev.agentkit.acme.AcmeOnTheHost;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.deferred.DeferredAction;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.eval.CaseReport;
import dev.agentkit.eval.Check;
import dev.agentkit.eval.CheckOutcome;
import dev.agentkit.eval.Checks;
import dev.agentkit.eval.EvalRun;
import dev.agentkit.eval.ToolCall;
import dev.agentkit.onboarding.OnboardingSystems;
import dev.agentkit.onboarding.OnboardingSystems.AwsGrant;
import dev.agentkit.onboarding.OnboardingSystems.GithubIdentity;
import dev.agentkit.onboarding.OnboardingSystems.OktaUser;
import dev.agentkit.onboarding.OnboardingSystems.SlackAccount;
import dev.agentkit.onboarding.OnboardingSystems.SlackQuestion;
import dev.agentkit.onboarding.OnboardingSystems.Worker;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Evaluates Onboarding as it runs — {@code orgs/acme}'s {@code onboarding} agent on the agent host, with the onboarding
 * systems as an MCP connector over HTTP — against a real model: one case per hire in {@code onboarding/hr.json}, each
 * started by the hire's manager from the agent's form, filled in from the hire's HRIS record, with every grant confirmed
 * as the manager would in the console. Each is scored on its plan and on what the systems hold afterwards.
 *
 * <p>Two kinds of check, on purpose. The <b>plan</b> checks score planning: every step unconditional,
 * nothing that only notes a skipped branch, and no mention of branches that do not apply to this hire.
 * The <b>world-state</b> checks score the outcome, read from the fake systems rather than from what the
 * model said it did — accounts, groups, grants, tickets, questions asked, deferred actions scheduled.
 *
 * <p><b>Opt-in, and it costs real tokens</b> (roughly 80k input per hire). Skipped unless
 * {@code ONBOARDING_EVAL=true} and {@code OPENROUTER_API_KEY} are set, so an ordinary build never calls a
 * model:
 *
 * <pre>
 * ONBOARDING_EVAL=true OPENROUTER_API_KEY=sk-or-... \
 *   ONBOARDING_SCENARIOS="contractor rehire" \        # optional subset
 *   ./mvnw -pl agentkit-examples-acme test -Dtest=OnboardingEvalTest
 * </pre>
 *
 * <p>The model is {@code orgs/acme}'s, as the host runs it. Model output varies between runs. World-state checks have
 * held on every run so far; plan-wording checks occasionally catch the planner writing a hedge or a skip note, which is
 * what they are for.
 */
class OnboardingEvalTest {

    /** The eval's "now": fixed, so scheduled dates and checks never drift with the calendar. */
    static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    /** Conditional wording that must not survive planning. */
    static final Pattern CONDITIONAL = Pattern.compile("\\b(if|otherwise|unless|else|whether|depending on)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A step that only notes a branch was skipped, rather than doing anything. */
    static final Pattern NO_OP = Pattern.compile(
            "\\b(not applicable|n/a|skip|skipped|does not apply|doesn't apply|no action|not needed)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Wording that says something is still outstanding. */
    static final Pattern PENDING = Pattern.compile(
            "pending|missing|unknown|outstanding|awaiting|not yet|not been|could not|couldn't|unable|no reply|not respond");

    /** What an HRIS field holds when it holds nothing: left out of the form. */
    private static final Set<String> NOT_ON_FILE = Set.of("", "none", "not on file");

    static final String RAVI = "W-1001";
    static final String MARCUS = "W-1002";
    static final String MARIA = "W-1003";
    static final String NOAH = "W-1004";
    static final String AISHA = "W-1005";
    static final String JORDAN = "W-1006";
    static final String ALEX = "W-1007";

    /**
     * One hire to onboard and what must be true afterwards.
     *
     * @param planMustNotMention word prefixes of branches that do not apply to this hire
     * @param expectations       checks on the world after the run
     */
    record Scenario(String name, String employeeId, Set<String> planMustNotMention,
                    Function<World, List<Check>> expectations) {
    }

    /** One case's systems, and what the manager was asked along the way. */
    static final class World {
        final OnboardingSystems s = OnboardingSystems.open("onboarding", 1_000);
        final DeferredActionStore store = DeferredActionStore.inMemory();
        final List<String> confirmed = new CopyOnWriteArrayList<>();
        final List<String> questions = new CopyOnWriteArrayList<>();

        List<DeferredAction> deferredActions() {
            return store.all();
        }
    }

    @TestFactory
    Stream<DynamicTest> everyHireIsOnboardedAsThePolicySays() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("ONBOARDING_EVAL")),
                "Set ONBOARDING_EVAL=true to run the onboarding evals against a real model.");
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        Assumptions.assumeTrue(key != null && !key.isBlank(), "Set OPENROUTER_API_KEY to run the onboarding evals.");

        LlmClient llm = OpenRouterLlmClient.builder(key).title("agentkit onboarding evals").build();
        String subset = System.getenv().getOrDefault("ONBOARDING_SCENARIOS", "");
        Set<String> wanted = subset.isBlank() ? Set.of() : Set.of(subset.strip().split("[\\s,]+"));

        return scenarios().stream()
                .filter(scenario -> wanted.isEmpty() || wanted.contains(scenario.name()))
                .map(scenario -> DynamicTest.dynamicTest(scenario.name(), () -> {
                    CaseReport report = run(scenario, llm);
                    assertThat(report.failures()).as("failed checks for %s", scenario.name()).isEmpty();
                }));
    }

    private static CaseReport run(Scenario scenario, LlmClient llm) throws InterruptedException {
        System.out.println("\n=== " + scenario.name() + " (" + scenario.employeeId() + ") ===");
        World w = new World();
        Worker hire = w.s.worker(scenario.employeeId());
        String manager = hire.manager();
        try (AcmeOnTheHost acme = AcmeOnTheHost.start(CompanySystems.open(null), AccessLedger.open(null), w.s,
                AcmeOnTheHost.storeFor("onboarding", w.store), () -> NOW, llm)) {
            ChatRuntime runtime = acme.runtime();
            String tenant = acme.tenant(manager);
            Conversation conversation = acme.start(manager, "onboarding", scenario.name());
            String request = acme.chat().message(tenant, conversation, form(hire));
            Turn turn = converse(runtime, w, tenant, conversation.id(), request);

            List<String> plan = plan(turn);
            List<ToolCall> calls = toolCalls(turn);
            printRun(turn, plan, w);
            AgentResult result = turn.state() == Turn.State.COMPLETED ? AgentResult.completed(turn.answer(), 0)
                    : AgentResult.failed(new IllegalStateException(turn.state() + ": " + turn.detail()), 0);
            EvalRun run = new EvalRun(Goal.of(request), result, calls);

            List<Check> checks = new ArrayList<>();
            checks.add(Checks.completed());
            checks.addAll(planChecks(plan, scenario, manager));
            checks.addAll(scenario.expectations().apply(w));
            checks.add(Checks.worldState("manager was messaged",
                    () -> w.s.slackMessages().stream().map(m -> m.to()).toList(), to -> to.contains(manager)));
            checks.add(named("every grant was confirmed by the manager", () -> w.s.grants().stream()
                    .allMatch(g -> w.confirmed.stream().anyMatch(c -> c.startsWith(g.tool() + " ")))));
            checks.add(named("did not ask the manager what the hire's record or the hire could say",
                    () -> w.questions.isEmpty()));

            List<CheckOutcome> outcomes = new ArrayList<>();
            for (Check check : checks) {
                try {
                    outcomes.add(check.check(run));
                } catch (RuntimeException e) {
                    outcomes.add(CheckOutcome.fail("check", "threw: " + e));
                }
            }
            return print(new CaseReport(scenario.name(), run, outcomes));
        }
    }

    /** The agent's form, filled in from the hire's HRIS record as the manager would: nothing where nothing is on file. */
    static Map<String, Object> form(Worker hire) {
        Map<String, Object> input = new LinkedHashMap<>();
        hire.fields().forEach((field, value) -> {
            String v = value.strip();
            switch (field) {
                case "manager" -> { }
                case "rehire", "production_aws_access_requested" ->
                        input.put(field, v.toLowerCase(Locale.ROOT).startsWith("yes"));
                default -> {
                    if (!NOT_ON_FILE.contains(v.toLowerCase(Locale.ROOT))) {
                        input.put(field, v);
                    }
                }
            }
        });
        return input;
    }

    /**
     * Sends the request and sees its turn through as the manager: each grant's confirmation approved, as they would in
     * the console, and any question put to them answered with "I don't know" — and recorded, since the record or the hire
     * should have answered it.
     */
    private static Turn converse(ChatRuntime runtime, World w, String tenant, String conversationId, String request)
            throws InterruptedException {
        Turn turn = runtime.say(tenant, conversationId, request, List.of());
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (ChatRuntime.PendingDecision pending : runtime.pending(tenant)) {
                if (pending.kind() == ChatRuntime.PendingDecision.Kind.QUESTION) {
                    w.questions.add(pending.question());
                    System.out.println("  ? " + pending.question());
                    runtime.decide(tenant, pending.id(), ApprovalDecision.approveWithArguments(
                            Map.of("answer", "I don't know.")), tenant);
                } else {
                    w.confirmed.add(pending.tool() + " " + pending.arguments());
                    System.out.println("  ✓ confirmed " + pending.tool() + " " + pending.arguments());
                    runtime.decide(tenant, pending.id(), ApprovalDecision.approve(), tenant);
                }
            }
            Optional<Turn> now = runtime.store().turn(tenant, conversationId, turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("The onboarding did not finish in time");
    }

    /** The plan, as the turn recorded it. */
    @SuppressWarnings("unchecked")
    private static List<String> plan(Turn turn) {
        return turn.stepsOf(Step.Kind.NOTE).stream().filter(step -> step.name().equals("plan")).findFirst()
                .map(step -> (List<String>) step.detail().get("steps")).orElse(List.of());
    }

    /** Every tool call of the turn, as the trace recorded it. */
    @SuppressWarnings("unchecked")
    private static List<ToolCall> toolCalls(Turn turn) {
        List<ToolCall> calls = new ArrayList<>();
        for (Step step : turn.stepsOf(Step.Kind.TOOL_CALL)) {
            Object arguments = step.detail().get("arguments");
            calls.add(new ToolCall(new ToolInvocation("c" + step.sequence(), step.name(),
                    arguments instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of()),
                    Boolean.TRUE.equals(step.detail().get("isError")),
                    Disposition.valueOf(String.valueOf(step.detail().getOrDefault("disposition", "RAN")))));
        }
        return calls;
    }

    private static void printRun(Turn turn, List<String> plan, World w) {
        System.out.println("\nPlan:");
        for (int i = 0; i < plan.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + plan.get(i));
        }
        System.out.println("\nTurn " + turn.state() + ":\n" + turn.answer());
        for (DeferredAction action : w.deferredActions()) {
            System.out.println("\nDeferred action " + action.id() + " runs " + action.runAt() + " (" + action.when()
                    + "):");
            action.goal().lines().forEach(line -> System.out.println("    | " + line));
        }
    }

    // ---------------------------------------------------------------- plan checks

    private static List<Check> planChecks(List<String> steps, Scenario scenario, String managerEmail) {
        List<Check> checks = new ArrayList<>();
        checks.add(named("plan is not empty", () -> !steps.isEmpty()));
        checks.add(offending("every step is unconditional", steps, step -> CONDITIONAL.matcher(step).find()));
        checks.add(offending("no step is a skip / not-applicable note", steps, step -> NO_OP.matcher(step).find()));
        // The manager summary may legitimately say what was skipped, so pruning is checked on action steps only.
        List<String> actions = steps.stream().filter(s -> !s.toLowerCase(Locale.ROOT).contains(managerEmail)).toList();
        for (String word : new java.util.TreeSet<>(scenario.planMustNotMention())) {
            // Word-prefix match, so "ship" catches "shipping" but not "membership".
            Pattern prefix = Pattern.compile("\\b" + Pattern.quote(word), Pattern.CASE_INSENSITIVE);
            checks.add(offending("plan prunes branches that do not apply (no \"" + word + "\")", actions,
                    step -> prefix.matcher(step).find()));
        }
        return checks;
    }

    // ---------------------------------------------------------------- scenarios

    static List<Scenario> scenarios() {
        return List.of(
                new Scenario("engineer", RAVI,
                        Set.of("salesforce", "guest", "reactivat", "contractors", "pickup", "pick up", "obtain", "deferred", "terminat"),
                        w -> {
                            OnboardingSystems s = w.s;
                            String email = "ravi.menon@acme.example";
                            return List.of(
                                    named("Okta account created (not reactivated)",
                                            () -> s.oktaCreated().contains(email) && s.oktaReactivated().isEmpty()),
                                    oktaGroups(s, email, Set.of("engineering", "all-staff"), Set.of("contractors")),
                                    Checks.worldState("GitHub members", s::githubMembers, m -> "backend".equals(m.get("rmenon-dev"))),
                                    Checks.worldState("no Slack question sent (username was on file)", s::slackQuestions, List::isEmpty),
                                    awsStagingOnly(s, email),
                                    named("no access_request ticket", () -> noTicket(s, "access_request")),
                                    Checks.worldState("no Salesforce seat", s::salesforceSeats, Set::isEmpty),
                                    slackIs(s, email, "member", Set.of("#general", "#engineering")),
                                    named("laptop shipped to Lisbon", () -> s.shipments().size() == 1
                                            && s.shipments().get(0).address().contains("Lisbon")),
                                    named("no laptop_pickup ticket", () -> noTicket(s, "laptop_pickup")),
                                    Checks.worldState("benefits enrolled", s::benefitsEnrolled, b -> b.equals(Set.of("W-1001"))),
                                    Checks.worldState("no deferred actions scheduled", w::deferredActions, List::isEmpty));
                        }),

                new Scenario("contractor", MARCUS,
                        Set.of("github", "aws", "benefit", "reactivat", "all-staff", "ship", "obtain"),
                        w -> {
                            OnboardingSystems s = w.s;
                            String email = "marcus.bell@acme.example";
                            List<Check> checks = new ArrayList<>(List.of(
                                    named("Okta account created", () -> s.oktaCreated().contains(email)),
                                    oktaGroups(s, email, Set.of("sales", "contractors"), Set.of("all-staff")),
                                    Checks.worldState("Salesforce seats", s::salesforceSeats, seats -> seats.equals(Set.of(email))),
                                    Checks.worldState("no GitHub membership", s::githubMembers, Map::isEmpty),
                                    Checks.worldState("no Slack question sent", s::slackQuestions, List::isEmpty),
                                    Checks.worldState("no AWS access", s::awsGrants, List::isEmpty),
                                    slackIs(s, email, "guest", Set.of("#sales")),
                                    named("laptop_pickup ticket names the New York office", () -> pickupAt(s, "new york")),
                                    Checks.worldState("no laptop shipped", s::shipments, List::isEmpty),
                                    Checks.worldState("no benefits enrollment", s::benefitsEnrolled, Set::isEmpty)));
                            checks.addAll(terminationDeferred(w, MARCUS, LocalDate.of(2026, 12, 31)));
                            return checks;
                        }),

                new Scenario("rehire", MARIA,
                        Set.of("salesforce", "guest", "contractors", "ship", "obtain", "deferred", "terminat"),
                        w -> {
                            OnboardingSystems s = w.s;
                            String email = "maria.chen@acme.example";
                            return List.of(
                                    named("Okta account reactivated (not created)",
                                            () -> s.oktaReactivated().contains(email) && s.oktaCreated().isEmpty()),
                                    oktaGroups(s, email, Set.of("engineering", "all-staff"), Set.of()),
                                    Checks.worldState("GitHub members", s::githubMembers, m -> "sre".equals(m.get("mchen-sre"))),
                                    Checks.worldState("no Slack question sent (username was on file)", s::slackQuestions, List::isEmpty),
                                    awsStagingOnly(s, email),
                                    named("access_request ticket for production", () -> s.tickets().stream()
                                            .anyMatch(t -> t.category().equals("access_request")
                                                    && t.summary().toLowerCase(Locale.ROOT).contains("production"))),
                                    slackIs(s, email, "member", Set.of("#general", "#engineering")),
                                    named("laptop_pickup ticket names the San Francisco office", () -> pickupAt(s, "san francisco")),
                                    Checks.worldState("no laptop shipped", s::shipments, List::isEmpty),
                                    Checks.worldState("benefits enrolled", s::benefitsEnrolled, b -> b.equals(Set.of("W-1003"))),
                                    Checks.worldState("no deferred actions scheduled", w::deferredActions, List::isEmpty));
                        }),

                // ---- GitHub username not on file: the agent has to obtain it.

                austinEngineer("github-found", NOAH, "noah.fischer@acme.example", s -> List.of(
                        identityIs(s, "noah.fischer@acme.example", "nfischer-code", "slack_profile"),
                        Checks.worldState("did not ask the hire (their own profile settled it)", s::slackQuestions, List::isEmpty),
                        Checks.worldState("GitHub members", s::githubMembers, m -> m.equals(Map.of("nfischer-code", "platform"))))),

                austinEngineer("github-asked", AISHA, "aisha.rahman@acme.example", s -> List.of(
                        lookedUpBeforeAsking(),
                        Checks.worldState("asked the hire exactly once", s::slackQuestions, q -> q.size() == 1),
                        Checks.worldState("suggested no username (nothing plausible was found)", s::slackQuestions,
                                q -> q.stream().allMatch(question -> question.suggestedUsername() == null)),
                        identityIs(s, "aisha.rahman@acme.example", "ar-codes", "confirmed_by_hire"),
                        Checks.worldState("GitHub members", s::githubMembers, m -> m.equals(Map.of("ar-codes", "data"))))),

                austinEngineer("github-guessed", JORDAN, "jordan.lee@acme.example", s -> List.of(
                        Checks.inOrder("github_search_users", "slack_request_github_username"),
                        Checks.worldState("asked the hire to confirm the search match jlee", s::slackQuestions,
                                q -> q.stream().map(SlackQuestion::suggestedUsername).anyMatch("jlee"::equals)),
                        identityIs(s, "jordan.lee@acme.example", "jl-builds", "confirmed_by_hire"),
                        Checks.worldState("GitHub members (no search hit added)", s::githubMembers,
                                m -> m.equals(Map.of("jl-builds", "frontend"))))),

                austinEngineer("github-missing", ALEX, "alex.rivera@acme.example", s -> List.of(
                        lookedUpBeforeAsking(),
                        Checks.worldState("asked the hire", s::slackQuestions, q -> !q.isEmpty()),
                        named("no GitHub account on record", () -> s.githubIdentity("alex.rivera@acme.example") == null),
                        Checks.worldState("no GitHub membership", s::githubMembers, Map::isEmpty),
                        named("manager told GitHub is still pending", () -> s.slackMessages().stream()
                                .filter(m -> m.to().equals("dana.kim@acme.example"))
                                .map(m -> m.text().toLowerCase(Locale.ROOT))
                                .anyMatch(t -> t.contains("github") && PENDING.matcher(t).find())))));
    }

    /**
     * A full-time, on-site engineer in Austin whose GitHub username is not on file: the checks every such
     * hire shares, plus the ones about how the username was, or was not, obtained.
     */
    private static Scenario austinEngineer(String name, String employeeId, String email,
                                           Function<OnboardingSystems, List<Check>> github) {
        return new Scenario(name, employeeId,
                Set.of("salesforce", "guest", "reactivat", "contractors", "ship", "production", "deferred", "terminat"),
                w -> {
                    OnboardingSystems s = w.s;
                    List<Check> checks = new ArrayList<>(List.of(
                            named("Okta account created", () -> s.oktaCreated().contains(email)),
                            oktaGroups(s, email, Set.of("engineering", "all-staff"), Set.of()),
                            Checks.worldState("AWS grants", s::awsGrants, g -> g.equals(List.of(new AwsGrant(email, "staging")))),
                            slackIs(s, email, "member", Set.of("#general", "#engineering")),
                            named("laptop_pickup ticket names the Austin office", () -> pickupAt(s, "austin")),
                            Checks.worldState("benefits enrolled", s::benefitsEnrolled, b -> b.equals(Set.of(employeeId))),
                            Checks.worldState("no deferred actions scheduled", w::deferredActions, List::isEmpty)));
                    checks.addAll(github.apply(s));
                    return checks;
                });
    }

    /**
     * A termination date is scheduled, not acted on, so this checks what was recorded: exactly one action
     * 14 days before the termination date and one on it, for this worker; a reminder naming the manager and
     * the date; and a termination-day goal covering every system onboarding set up — judged from the systems'
     * own state, not from the grant record the scheduler reported to the model.
     */
    private static List<Check> terminationDeferred(World w, String employeeId, LocalDate terminationDate) {
        OnboardingSystems s = w.s;
        Worker worker = s.worker(employeeId);
        String email = worker.email();
        LocalDate reminderDate = terminationDate.minusDays(14);
        return List.of(
                Checks.worldState("deferred actions are exactly on " + reminderDate + " and " + terminationDate,
                        w::deferredActions, actions -> actions.size() == 2
                                && actions.stream().allMatch(a -> a.subjectKind().equals(OnboardingSystems.WORKER)
                                        && a.subjectId().equals(employeeId))
                                && actions.stream().map(DeferredAction::runAt).collect(Collectors.toSet())
                                        .equals(Set.of(startOf(reminderDate), startOf(terminationDate)))),
                named("reminder goal names the manager and the termination date", () -> goalOn(w, reminderDate)
                        .map(goal -> goal.contains(worker.manager()) && goal.contains(terminationDate.toString()))
                        .orElse(false)),
                run -> {
                    List<String> provisioned = provisioned(s, email);
                    String label = "termination-day goal covers every system onboarding set up " + provisioned;
                    String goal = goalOn(w, terminationDate).orElse("");
                    List<String> missing = provisioned.stream().filter(system -> !goal.contains(system)).toList();
                    return missing.isEmpty() ? CheckOutcome.pass(label) : CheckOutcome.fail(label, "does not name " + missing);
                },
                named("termination-day goal names the worker and notifies the manager", () -> goalOn(w, terminationDate)
                        .map(goal -> goal.contains(email) && goal.contains(worker.manager())).orElse(false)));
    }

    // ---------------------------------------------------------------- helpers

    private static java.time.Instant startOf(LocalDate date) {
        return date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
    }

    private static Optional<String> goalOn(World w, LocalDate date) {
        return w.deferredActions().stream().filter(a -> a.runAt().equals(startOf(date))).findFirst()
                .map(a -> a.goal().toLowerCase(Locale.ROOT));
    }

    private static List<String> provisioned(OnboardingSystems s, String email) {
        List<String> systems = new ArrayList<>();
        if (s.oktaUser(email) != null) systems.add("okta");
        if (s.slackAccount(email) != null) systems.add("slack");
        if (s.salesforceSeats().contains(email)) systems.add("salesforce");
        GithubIdentity identity = s.githubIdentity(email);
        if (identity != null && s.githubMembers().containsKey(identity.username())) systems.add("github");
        if (s.awsGrants().stream().anyMatch(g -> g.email().equals(email))) systems.add("aws");
        if (s.tickets().stream().anyMatch(t -> t.forEmail().equals(email) && t.category().equals("laptop_pickup"))
                || s.shipments().stream().anyMatch(sh -> sh.email().equals(email))) systems.add("laptop");
        return systems;
    }

    /** The profile or a search was consulted before the hire was asked. */
    private static Check lookedUpBeforeAsking() {
        String label = "looked it up with tools before asking";
        return run -> {
            List<String> calls = run.toolNames();
            int asked = calls.indexOf("slack_request_github_username");
            int profile = calls.indexOf("slack_get_profile");
            int search = calls.indexOf("github_search_users");
            boolean ok = asked >= 0 && ((profile >= 0 && profile < asked) || (search >= 0 && search < asked));
            return ok ? CheckOutcome.pass(label) : CheckOutcome.fail(label, "tool calls were " + calls);
        };
    }

    private static Check oktaGroups(OnboardingSystems s, String email, Set<String> required, Set<String> forbidden) {
        return Checks.worldState("Okta account for " + email + " is active in " + required
                        + (forbidden.isEmpty() ? "" : ", not in " + forbidden),
                () -> s.oktaUser(email),
                (OktaUser okta) -> okta != null && "ACTIVE".equals(okta.status())
                        && okta.groups().containsAll(required) && forbidden.stream().noneMatch(okta.groups()::contains));
    }

    private static Check awsStagingOnly(OnboardingSystems s, String email) {
        return Checks.worldState("AWS: staging granted, production not", s::awsGrants,
                g -> g.contains(new AwsGrant(email, "staging")) && !g.contains(new AwsGrant(email, "production")));
    }

    private static Check slackIs(OnboardingSystems s, String email, String type, Set<String> channels) {
        return Checks.worldState("Slack " + type + " account in exactly " + channels, () -> s.slackAccount(email),
                (SlackAccount account) -> account != null && account.accountType().equals(type)
                        && account.channels().equals(channels));
    }

    private static Check identityIs(OnboardingSystems s, String email, String username, String source) {
        return Checks.worldState("GitHub account on record is " + username + " from " + source,
                () -> s.githubIdentity(email),
                (GithubIdentity identity) -> identity != null && identity.username().equals(username)
                        && identity.source().equals(source));
    }

    private static boolean pickupAt(OnboardingSystems s, String office) {
        return s.tickets().stream().anyMatch(t -> t.category().equals("laptop_pickup")
                && t.summary().toLowerCase(Locale.ROOT).contains(office));
    }

    private static boolean noTicket(OnboardingSystems s, String category) {
        return s.tickets().stream().noneMatch(t -> t.category().equals(category));
    }

    private static Check named(String label, BooleanSupplier ok) {
        return run -> ok.getAsBoolean() ? CheckOutcome.pass(label) : CheckOutcome.fail(label, "did not hold");
    }

    /** Passes when no item matches; a failure lists the ones that did. */
    private static Check offending(String label, List<String> items, java.util.function.Predicate<String> bad) {
        return run -> {
            List<String> found = items.stream().filter(bad).toList();
            return found.isEmpty() ? CheckOutcome.pass(label) : CheckOutcome.fail(label, "offending: " + found);
        };
    }

    private static CaseReport print(CaseReport report) {
        System.out.println("\nVerification:");
        for (CheckOutcome outcome : report.outcomes()) {
            System.out.println((outcome.passed() ? "  PASS  " : "  FAIL  ") + outcome.name()
                    + (outcome.passed() ? "" : "  -- " + outcome.detail()));
        }
        System.out.println(report.passed() ? "  => " + report.caseId() + " PASSED" : "  => " + report.caseId() + " FAILED");
        return report;
    }
}
