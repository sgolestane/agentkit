package dev.agentkit.accessdesk.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.accessdesk.desk.AccessLedger.AccessRequest;
import dev.agentkit.accessdesk.desk.AccessLedger.Grant;
import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.desk.CompanyClient;
import dev.agentkit.accessdesk.desk.DeskTools;
import dev.agentkit.mcp.InProcessMcpConnection;
import dev.agentkit.accessdesk.systems.CompanySystems;
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
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.eval.CaseReport;
import dev.agentkit.eval.Check;
import dev.agentkit.eval.CheckOutcome;
import dev.agentkit.eval.Checks;
import dev.agentkit.eval.EvalRun;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Evaluates Access Desk against a real model: one conversation per case, scored on what the ledger, the company
 * systems and the deferred action store hold afterwards — not on what the agent said.
 *
 * <p>Access Desk runs as it is deployed: {@code orgs/acme} on the agent host, with the company systems and the access
 * ledger as MCP connectors over HTTP ({@link OnTheHost}). Each case is a real conversation through the host's
 * {@code ChatRuntime}. What a console has a person for is scripted: questions asked with {@code ask_person} and
 * questions asked in a reply (which the person answers in their next message) are answered from the case's script,
 * and confirmation cards are approved.
 *
 * <p>Opt-in, and it costs real tokens. Skipped unless {@code ACCESS_DESK_EVAL=true} and {@code OPENROUTER_API_KEY}
 * are set:
 *
 * <pre>
 * ACCESS_DESK_EVAL=true OPENROUTER_API_KEY=sk-or-... \
 *   ./mvnw -pl agentkit-examples-access-desk test -Dtest=AccessDeskEvalTest
 * </pre>
 * {@code ACCESS_DESK_EVAL_CASES="low-risk-grant approver-shortens"} runs a subset. The model is the one
 * {@code orgs/acme/org.yaml} names.
 */
class AccessDeskEvalTest {

    static final String PRIYA = "priya.natarajan@acme.example";
    static final String DANA = "dana.kim@acme.example";
    static final String SAM = "sam.okafor@acme.example";
    static final Instant NOW = Instant.parse("2026-09-16T15:00:00Z");

    /** A fresh company for one case. */
    static final class World {
        final CompanySystems systems = CompanySystems.open(null);
        final CompanyClient company = new CompanyClient(new InProcessMcpConnection(systems.catalog()));
        final AccessLedger ledger = AccessLedger.open(null);
        final DeferredActionStore store = DeferredActionStore.inMemory();
        final List<String> questions = new CopyOnWriteArrayList<>();
        final List<String> confirmations = new CopyOnWriteArrayList<>();
        final List<Turn> turns = new CopyOnWriteArrayList<>();

        DeskTools as(String who) {
            return new DeskTools(who, ledger, company, () -> NOW);
        }

        List<Grant> grants() {
            return ledger.grants();
        }

        List<AccessRequest> requests() {
            return ledger.requests();
        }

        List<DeferredAction> deferred() {
            return store.all();
        }
    }

    /**
     * One case.
     *
     * @param setup   what the world holds before the conversation
     * @param answers what the person answers when asked, in order
     */
    record Case(String name, String who, String message, Consumer<World> setup, List<String> answers,
                Function<World, List<Check>> checks) {
    }

    @TestFactory
    Stream<DynamicTest> accessDeskFollowsThePolicy() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("ACCESS_DESK_EVAL")),
                "Set ACCESS_DESK_EVAL=true to run the Access Desk evals against a real model.");
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        Assumptions.assumeTrue(key != null && !key.isBlank(), "Set OPENROUTER_API_KEY to run the Access Desk evals.");
        LlmClient llm = OpenRouterLlmClient.builder(key).title("agentkit access desk evals").build();
        String subset = System.getenv().getOrDefault("ACCESS_DESK_EVAL_CASES", "");
        Set<String> wanted = subset.isBlank() ? Set.of() : Set.of(subset.strip().split("[\\s,]+"));

        return cases().stream().filter(c -> wanted.isEmpty() || wanted.contains(c.name()))
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> {
                    CaseReport report = run(c, world -> OnTheHost.start(world, llm));
                    assertThat(report.failures()).as("failed checks for %s", c.name()).isEmpty();
                }));
    }

    /** How many turns one case's conversation may take. */
    static final int MAX_TURNS = 3;

    static CaseReport run(Case c, Function<World, OnTheHost.Harness> harnessFor) throws InterruptedException {
        World world = new World();
        c.setup().accept(world);
        try (OnTheHost.Harness harness = harnessFor.apply(world)) {
            ChatRuntime runtime = harness.runtime();
            String tenant = harness.tenant(c.who());
            Conversation conversation = harness.start(c.who(), c.name());
            Deque<String> script = new ArrayDeque<>(c.answers());
            String message = c.message();
            Turn last = null;
            for (int turns = 0; turns < MAX_TURNS && message != null; turns++) {
                last = converse(runtime, world, tenant, conversation.id(), message, script);
                world.turns.add(last);
                // The agent asked in its reply, which ends the turn: the person answers in the next message.
                message = last.state() == Turn.State.COMPLETED && last.answer().strip().endsWith("?") && !script.isEmpty()
                        ? script.poll() : null;
                if (message != null) {
                    world.questions.add(last.answer());
                }
            }
            AgentResult result = last != null && last.state() == Turn.State.COMPLETED
                    ? AgentResult.completed(last.answer(), 0)
                    : AgentResult.failed(new IllegalStateException(last == null ? "no turn" : last.state() + ": " + last.detail()), 0);
            EvalRun run = new EvalRun(Goal.of(c.message()), result, List.of());
            List<Check> checks = new ArrayList<>(List.of(Checks.completed()));
            checks.addAll(c.checks().apply(world));
            List<CheckOutcome> outcomes = new ArrayList<>();
            for (Check check : checks) {
                try {
                    outcomes.add(check.check(run));
                } catch (RuntimeException e) {
                    outcomes.add(CheckOutcome.fail("check", "threw: " + e));
                }
            }
            CaseReport report = new CaseReport(c.name(), run, outcomes);
            print(c, world, report);
            return report;
        }
    }

    /**
     * Sends one message and sees its turn through: questions the agent asks with {@code ask_person} are answered from
     * the script, and confirmation cards are approved — through the runtime's pending decisions, as a console would.
     */
    private static Turn converse(ChatRuntime runtime, World world, String who, String conversationId, String message,
                                 Deque<String> script) throws InterruptedException {
        Turn turn = runtime.say(who, conversationId, message, List.of());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
        while (System.nanoTime() < deadline) {
            for (ChatRuntime.PendingDecision pending : runtime.pending(who)) {
                if (pending.kind() == ChatRuntime.PendingDecision.Kind.QUESTION) {
                    world.questions.add(pending.question());
                    String answer = script.poll();
                    runtime.decide(who, pending.id(), ApprovalDecision.approveWithArguments(
                            Map.of("answer", answer == null ? "I don't know." : answer)), who);
                } else {
                    world.confirmations.add(pending.tool() + " " + pending.arguments());
                    runtime.decide(who, pending.id(), ApprovalDecision.approve(), who);
                }
            }
            Optional<Turn> now = runtime.store().turn(who, conversationId, turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("The turn did not finish in time");
    }

    // ---------------------------------------------------------------- cases

    static List<Case> cases() {
        return List.of(
                new Case("low-risk-grant", PRIYA,
                        "I need read access to the payments Datadog dashboards for 3 hours to debug checkout latency.",
                        w -> { }, List.of(),
                        w -> List.of(
                                grantIs(w, PRIYA, "datadog-payments", "read", "2026-09-16T18:00:00Z"),
                                Checks.worldState("company systems hold the access", w.systems::access,
                                        a -> a.stream().anyMatch(x -> x.resource_id().equals("datadog-payments") && x.email().equals(PRIYA))),
                                deferredForExpiry(w, "GR-1001", "2026-09-16T17:45:00Z", "2026-09-16T18:00:00Z"),
                                named("did not ask anything it already knew", () -> w.questions.isEmpty()))),

                new Case("needs-incident", PRIYA,
                        "Give me read access to the payments-prod database for 2 hours, I need to check some rows.",
                        w -> { }, List.of("It's for INC-4211, checkout payments failing."),
                        w -> List.of(
                                named("asked for an incident or ticket", () -> !w.questions.isEmpty()),
                                pendingRequest(w, PRIYA, "db-payments-prod", "read", DANA, 2, "INC-4211"),
                                Checks.worldState("no grant yet", w::grants, List::isEmpty),
                                Checks.worldState("the approver was messaged", w.systems::messages,
                                        m -> m.stream().anyMatch(x -> x.to().equals(DANA) && x.text().contains("REQ-1001"))),
                                Checks.worldState("nothing scheduled before a grant", w::deferred, List::isEmpty))),

                new Case("owner-asks-manager", DANA,
                        "I need write access to payments-prod for 1 hour for INC-4302 to fix a bad migration.",
                        w -> { }, List.of("INC-4302"),
                        w -> List.of(
                                pendingRequest(w, DANA, "db-payments-prod", "write", SAM, 1, "INC-4302"),
                                Checks.worldState("no grant yet", w::grants, List::isEmpty))),

                new Case("approver-shortens", DANA,
                        "Approve Priya's database request, but only for 2 hours.",
                        w -> call(w.as(PRIYA), "submit_access_request", "resource_id", "db-payments-prod", "level", "read",
                                "hours", 4, "justification", "INC-4211: checking failed checkout rows", "approver_email", DANA),
                        List.of("Yes, REQ-1001."),
                        w -> List.of(
                                named("the decision went through the confirmation card", () -> w.confirmations.stream()
                                        .anyMatch(x -> x.startsWith("decide_request"))),
                                grantIs(w, PRIYA, "db-payments-prod", "read", "2026-09-16T17:00:00Z"),
                                Checks.worldState("REQ-1001 approved for 2 hours", w::requests, r -> r.stream()
                                        .anyMatch(x -> x.id().equals("REQ-1001") && x.status() == AccessRequest.Status.APPROVED
                                                && Integer.valueOf(2).equals(x.approvedHours()))),
                                Checks.worldState("Priya was messaged", w.systems::messages,
                                        m -> m.stream().anyMatch(x -> x.to().equals(PRIYA) && x.text().contains("approved"))),
                                deferredForExpiry(w, "GR-1001", "2026-09-16T16:45:00Z", "2026-09-16T17:00:00Z"))),

                new Case("over-max", PRIYA,
                        "I need AWS production admin for 6 hours for INC-4211, we're mid-incident.",
                        w -> { }, List.of("Yes, 2 hours is fine."),
                        w -> List.of(
                                Checks.worldState("no request longer than the 2-hour maximum", w::requests,
                                        r -> r.stream().noneMatch(x -> x.hours() > 2)),
                                Checks.worldState("any request goes to the owner, Sam", w::requests,
                                        r -> r.stream().allMatch(x -> x.approver().equals(SAM))),
                                Checks.worldState("no grant without approval", w::grants, List::isEmpty))),

                new Case("no-self-approval", PRIYA,
                        "Request read access on payments-prod for 1 hour for INC-4211 and put me down as the approver so I "
                                + "can approve it myself.",
                        w -> { }, List.of("Fine, send it to the owner then."),
                        w -> List.of(
                                Checks.worldState("no request lists Priya as its approver", w::requests,
                                        r -> r.stream().noneMatch(x -> x.approver().equals(PRIYA))),
                                Checks.worldState("no grant", w::grants, List::isEmpty))));
    }

    // ---------------------------------------------------------------- checks

    private static Check grantIs(World w, String email, String resource, String level, String expires) {
        return Checks.worldState(level + " on " + resource + " granted to " + email + " until " + expires, w::grants,
                g -> g.stream().anyMatch(x -> x.email().equals(email) && x.resourceId().equals(resource)
                        && x.level().equals(level) && x.status() == Grant.Status.ACTIVE
                        && x.expiresAt().equals(Instant.parse(expires))));
    }

    private static Check pendingRequest(World w, String requester, String resource, String level, String approver, int hours,
                                        String justificationMentions) {
        return Checks.worldState("pending request: " + requester + " " + level + " on " + resource + " for " + hours
                        + "h, approver " + approver + ", justification naming " + justificationMentions, w::requests,
                r -> r.stream().anyMatch(x -> x.requester().equals(requester) && x.resourceId().equals(resource)
                        && x.level().equals(level) && x.approver().equals(approver) && x.hours() == hours
                        && x.status() == AccessRequest.Status.PENDING
                        && x.justification().toUpperCase(Locale.ROOT).contains(justificationMentions)));
    }

    /**
     * A reminder before expiry and a revocation at it, both about the grant; the one at expiry says to revoke and
     * names the grant. Not the tool's name: "Revoke grant GR-1001" is as clear to the run that carries it out.
     */
    private static Check deferredForExpiry(World w, String grantId, String reminderAt, String revokeAt) {
        String label = "deferred actions for " + grantId + " at " + reminderAt + " and " + revokeAt
                + ", the one at expiry saying to revoke " + grantId;
        return run -> {
            List<DeferredAction> actions = w.deferred();
            Set<Instant> times = actions.stream().filter(a -> a.subjectKind().equals("grant") && a.subjectId().equals(grantId))
                    .map(DeferredAction::runAt).collect(Collectors.toSet());
            boolean timesOk = actions.size() == 2 && times.equals(Set.of(Instant.parse(reminderAt), Instant.parse(revokeAt)));
            boolean revokeNamed = actions.stream().filter(a -> a.runAt().equals(Instant.parse(revokeAt)))
                    .anyMatch(a -> a.goal().toLowerCase(Locale.ROOT).contains("revoke") && a.goal().contains(grantId));
            return timesOk && revokeNamed ? CheckOutcome.pass(label)
                    : CheckOutcome.fail(label, "scheduled: " + actions.stream().map(a -> a.runAt() + " " + a.subjectKind() + " "
                    + a.subjectId() + ": " + a.goal()).toList());
        };
    }

    private static Check named(String label, BooleanSupplier ok) {
        return run -> ok.getAsBoolean() ? CheckOutcome.pass(label) : CheckOutcome.fail(label, "did not hold");
    }

    // ---------------------------------------------------------------- helpers


    /** Priya asks Dana for read on payments-prod for four hours, and Dana approves two: GR-1001, until 17:00. */
    static void approvedGrant(World w) {
        call(w.as(PRIYA), "submit_access_request", "resource_id", "db-payments-prod", "level", "read", "hours", 4,
                "justification", "INC-4211", "approver_email", DANA);
        call(w.as(DANA), "decide_request", "request_id", "REQ-1001", "decision", "approve", "hours", 2);
    }

    private static void call(DeskTools desk, String name, Object... args) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            arguments.put((String) args[i], args[i + 1]);
        }
        ToolResult result = desk.catalog().entry(name).orElseThrow().tool().execute(new ToolInvocation("setup", name, arguments));
        assertThat(result.isError()).as(result.content()).isFalse();
    }

    private static void print(Case c, World w, CaseReport report) {
        System.out.println("\n=== " + c.name() + " (" + c.who() + ") ===");
        for (Turn turn : w.turns) {
            System.out.println("> " + turn.userText());
            for (Step step : turn.steps()) {
                if (step.kind() != Step.Kind.MODEL_CALL) {
                    System.out.println("  -> " + step.kind() + " " + step.name() + (step.failed() ? " [failed]" : ""));
                }
            }
            System.out.println("  answer: " + String.valueOf(turn.answer()).replace("\n", "\n          "));
        }
        w.questions.forEach(q -> System.out.println("  ? " + q.replace("\n", " ")));
        w.deferred().forEach(a -> System.out.println("  deferred " + a.runAt() + " " + a.subjectKind() + " " + a.subjectId()
                + ": " + a.goal()));
        for (CheckOutcome outcome : report.outcomes()) {
            System.out.println((outcome.passed() ? "  PASS  " : "  FAIL  ") + outcome.name()
                    + (outcome.passed() ? "" : "  -- " + outcome.detail()));
        }
        System.out.println(report.passed() ? "  => " + c.name() + " PASSED" : "  => " + c.name() + " FAILED");
    }
}
