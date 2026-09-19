package dev.agentkit.host;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.deferred.DeferredActionScheduler;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.eval.Args;
import dev.agentkit.eval.Check;
import dev.agentkit.eval.CheckOutcome;
import dev.agentkit.eval.Checks;
import dev.agentkit.eval.EvalRun;
import dev.agentkit.eval.ToolCall;
import dev.agentkit.host.repo.EvalCase;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RoutingCase;
import dev.agentkit.host.routing.Router;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * An agent's eval cases, rehearsed: each a real conversation with the agent — its prompt, policy, model and
 * connectors, as the person the case names — with every tool that could change something refused and recorded
 * ({@link HostedAgent#rehearsing()}). Reads run, so the agent sees what is there; nothing is granted, sent or scheduled.
 *
 * <p>What is scored is what the agent set out to do — the calls it made, whether it asked the person, what it answered
 * — which is what a change to a prompt, a policy or a tool selection changes. The person's side is played from the
 * case: its answers, in order, to the agent's questions ("I don't know." once they run out), and a yes to any read
 * that asks for confirmation. An agent that asks in its reply, ending its turn with a question, is answered in the
 * next message, for up to {@link #MAX_TURNS} turns.
 */
public final class Rehearsal {

    /** What the person says when the case has no answer left. */
    static final String NO_ANSWER = "I don't know.";

    /** How many turns one case's conversation may take. */
    static final int MAX_TURNS = 3;

    private final AgentHost host;
    private final LlmClient llm;
    private final Supplier<Instant> clock;
    private final Duration patience;

    /**
     * @param patience how long one case may take before it is scored as not finished
     */
    public Rehearsal(AgentHost host, LlmClient llm, Supplier<Instant> clock, Duration patience) {
        this.host = Objects.requireNonNull(host, "host");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.patience = Objects.requireNonNull(patience, "patience");
    }

    /**
     * A routing case rehearsed: who the router said should answer, and whether that is who the case expected. Only the
     * router runs; nothing is carried out.
     *
     * @param got   the agent chosen, or "the router answers" / "the router asks"; null when it could not decide
     * @param error why it could not decide, if it could not
     */
    public record RoutingResult(RoutingCase routingCase, String got, String why, String text,
                                String error) {
        public boolean passed() {
            return error == null && routingCase.expect().describe().equals(describe());
        }

        /** Who answered, in the words {@link RoutingCase.Expect#describe()} uses. */
        public String describe() {
            return got == null ? "nobody" : got.startsWith("the router") ? got : "goes to " + got;
        }
    }

    /** Asks the router the case's message, as the case's person, after its earlier turns. */
    public RoutingResult route(RoutingCase routingCase) {
        Optional<Principal> principal = host.principal(routingCase.as());
        if (principal.isEmpty()) {
            return new RoutingResult(routingCase, null, "", "", routingCase.as() + " is not in the directory");
        }
        OrgRepo repo = host.repo();
        String model = repo.router().model() != null ? repo.router().model() : repo.defaultModel();
        try {
            Router.Decision decision = Router.decide(llm, model,
                    repo.router().prompt(),
                    host.agentsFor(principal.get()).stream().map(agent -> new Router.Offered(
                            agent.definition().id(), agent.definition().name(), agent.definition().description(),
                            agent.definition().input() == null ? "" : agent.definition().input().describe())).toList(),
                    routingCase.before().stream().map(one -> new Router.Earlier(one.say(),
                            one.agent(), one.answer())).toList(),
                    routingCase.say());
            return switch (decision) {
                case Router.ToAgent to -> new RoutingResult(routingCase, to.agent(), to.why(),
                        "", null);
                case Router.Answer answer -> new RoutingResult(routingCase,
                        "the router answers", answer.why(), answer.text(), null);
                case Router.Ask ask -> new RoutingResult(routingCase, "the router asks",
                        ask.why(), ask.text(), null);
            };
        } catch (RuntimeException e) {
            return new RoutingResult(routingCase, null, "", "", String.valueOf(e.getMessage()));
        }
    }

    /** A call the agent made: what, with which arguments, and — when it was refused — what it would have done. */
    public record Call(String tool, Map<String, Object> arguments, boolean refused, String would) {
    }

    /**
     * One case, rehearsed.
     *
     * @param state     how the turn ended: a {@link Turn.State}, or {@code NOT_RUN} when it could not start
     * @param plan      for a plan-and-execute agent, the plan it made
     * @param questions what the agent asked the person
     */
    public record Result(String agent, EvalCase evalCase, String state, String answer, List<String> plan,
                         List<Call> calls, List<String> questions, List<CheckOutcome> outcomes, long millis) {

        public boolean passed() {
            return outcomes.stream().allMatch(CheckOutcome::passed);
        }

        /** The calls a rehearsal stopped: what the agent would have done. */
        public List<Call> wouldHave() {
            return calls.stream().filter(Call::refused).toList();
        }
    }

    /** Every case of {@code agent}, in order. */
    public List<Result> rehearse(HostedAgent agent) {
        return agent.definition().evals().stream().map(c -> rehearse(agent, c)).toList();
    }

    /** One case of {@code agent}. */
    public Result rehearse(HostedAgent agent, EvalCase evalCase) {
        long started = System.nanoTime();
        String id = agent.definition().id();
        if (agent.unavailable().isPresent()) {
            return notRun(id, evalCase, agent.definition().name() + " is unavailable: " + agent.unavailable().get());
        }
        Optional<Principal> found = host.principal(evalCase.as());
        if (found.isEmpty()) {
            return notRun(id, evalCase, evalCase.as() + " is not in the directory");
        }
        Principal principal = found.get();
        if (!agent.admits(principal)) {
            return notRun(id, evalCase, evalCase.as() + " is not in " + agent.definition().name() + "'s audience");
        }
        HostedAgent rehearsing = agent.rehearsing();
        List<Tool> alsoGiven = scheduler(agent, principal).map(List::of).orElse(List.of());
        Map<String, String> changing = rehearsing.changing(alsoGiven);
        String request = evalCase.say() != null ? evalCase.say()
                : agent.definition().input().render(evalCase.input(), principal::value);

        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        ChatRuntime.Agents agents = new ChatRuntime.Agents() {
            @Override
            public Agent agentFor(ChatRuntime.Session session) {
                return rehearsing.turn(session, llm, principal, clock.get(), self.get(), alsoGiven);
            }

            @Override
            public ChatRuntime.Runner runnerFor(ChatRuntime.Session session) {
                return rehearsing.runner(session, llm, principal, clock.get(), self.get(), alsoGiven);
            }
        };
        String tenant = new Tenant(principal.org(), principal.email()).id();
        List<String> questions = new ArrayList<>();
        List<Turn> turns = new ArrayList<>();
        try (ChatRuntime runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), agents)) {
            self.set(runtime);
            Conversation conversation = runtime.store().create(tenant, evalCase.name());
            Deque<String> answers = new ArrayDeque<>(evalCase.answers());
            String message = request;
            while (message != null && turns.size() < MAX_TURNS) {
                Turn ended = converse(runtime, tenant, conversation, message, answers, questions);
                turns.add(ended);
                boolean asked = ended.state() == Turn.State.COMPLETED && ended.answer() != null
                        && ended.answer().strip().endsWith("?") && !answers.isEmpty();
                message = asked ? answers.poll() : null;
                if (asked) {
                    questions.add(ended.answer().strip());
                }
            }
        }
        Turn turn = turns.get(turns.size() - 1);

        List<Call> calls = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (Step step : turns.stream().flatMap(t -> t.stepsOf(Step.Kind.TOOL_CALL).stream()).toList()) {
            Map<String, Object> arguments = arguments(step.detail().get("arguments"));
            Disposition disposition = disposition(step.detail().get("disposition"));
            calls.add(new Call(step.name(), arguments, disposition == Disposition.REFUSED
                    && changing.containsKey(step.name()), changing.get(step.name())));
            toolCalls.add(new ToolCall(new ToolInvocation("c" + step.sequence(), step.name(), arguments),
                    Boolean.TRUE.equals(step.detail().get("isError")), disposition));
        }
        AgentResult result = turn.state() == Turn.State.COMPLETED
                ? AgentResult.completed(turn.answer() == null ? "" : turn.answer(), turn.steps().size())
                : AgentResult.failed(new IllegalStateException(turn.state() + (turn.detail() == null ? ""
                        : ": " + turn.detail())), turn.steps().size());
        EvalRun run = new EvalRun(Goal.of(request), result, toolCalls);

        // Named as the case wrote them, so the report reads in the author's words rather than the checks'.
        List<CheckOutcome> outcomes = new ArrayList<>();
        outcomes.add(named("the conversation finished", score(Checks.completed(), run)));
        for (EvalCase.Expectation expectation : evalCase.expect()) {
            outcomes.add(named(expectation.describe(), score(check(expectation, questions, agent), run)));
        }
        List<String> plan = turns.stream().map(Rehearsal::plan).filter(p -> !p.isEmpty()).findFirst().orElse(List.of());
        return new Result(id, evalCase, turn.state().name(), turn.answer() == null ? "" : turn.answer(), plan,
                calls, questions, outcomes, (System.nanoTime() - started) / 1_000_000);
    }

    /** Sends the request and plays the person until the turn ends, or the case's time runs out. */
    private Turn converse(ChatRuntime runtime, String tenant, Conversation conversation, String request,
                          Deque<String> answers, List<String> questions) {
        Turn turn = runtime.say(tenant, conversation.id(), request, List.of());
        long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            for (ChatRuntime.PendingDecision pending : runtime.pending(tenant)) {
                if (pending.kind() == ChatRuntime.PendingDecision.Kind.QUESTION) {
                    questions.add(pending.question());
                    String answer = answers.isEmpty() ? NO_ANSWER : answers.poll();
                    runtime.decide(tenant, pending.id(), ApprovalDecision.approveWithArguments(Map.of("answer", answer)),
                            tenant, "rehearsal", false);
                } else {
                    runtime.decide(tenant, pending.id(), ApprovalDecision.approve(), tenant, "rehearsal", false);
                }
            }
            Optional<Turn> now = runtime.store().turn(tenant, conversation.id(), turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Turn unfinished = runtime.store().turn(tenant, conversation.id(), turn.id()).orElse(turn);
        runtime.cancel(tenant, conversation.id());
        return unfinished;
    }

    /** The scheduler the host would give the agent, so it is offered and its call recorded; refused like the rest. */
    private Optional<Tool> scheduler(HostedAgent agent, Principal principal) {
        return agent.subjects().map(subjects -> new DeferredActionScheduler(subjects, DeferredActionStore.inMemory(),
                clock, subject -> List.of(), (by, subject) -> true).tool(principal.email()));
    }

    private Check check(EvalCase.Expectation expectation, List<String> questions, HostedAgent agent) {
        return switch (expectation.kind()) {
            case CALLS -> expectation.with().isEmpty() ? Checks.attemptedTool(expectation.tool())
                    : Checks.attemptedTool(expectation.tool(), args(expectation.with()));
            case NEVER -> expectation.with().isEmpty() ? Checks.didNotAttemptTool(expectation.tool())
                    : Checks.didNotAttemptTool(expectation.tool(), args(expectation.with()));
            case ASKS -> run -> expectation.asks() == !questions.isEmpty()
                    ? CheckOutcome.pass(expectation.describe())
                    : CheckOutcome.fail(expectation.describe(), questions.isEmpty() ? "it asked nothing"
                    : "it asked: " + String.join(" / ", questions));
            case ANSWER_CONTAINS -> run -> {
                String output = run.result().output() == null ? "" : run.result().output();
                return output.toLowerCase(Locale.ROOT).contains(expectation.text().toLowerCase(Locale.ROOT))
                        ? CheckOutcome.pass(expectation.describe())
                        : CheckOutcome.fail(expectation.describe(), "the answer does not say it");
            };
            case JUDGE -> Checks.judge(llm, agent.model(), expectation.text());
        };
    }

    /**
     * Arguments a call must carry: each value equal, ignoring case — or, for a list, every item of it in the call's
     * list.
     */
    static Args args(Map<String, Object> with) {
        return Args.matching(with.toString(), invocation -> with.entrySet().stream()
                .allMatch(e -> matches(e.getValue(), invocation.arguments().get(e.getKey()))));
    }

    private static boolean matches(Object expected, Object actual) {
        if (actual == null) {
            return false;
        }
        if (expected instanceof Collection<?> wanted) {
            if (!(actual instanceof Collection<?> held)) {
                return false;
            }
            List<String> have = held.stream().map(Rehearsal::normalized).toList();
            return wanted.stream().map(Rehearsal::normalized).allMatch(have::contains);
        }
        return normalized(expected).equals(normalized(actual));
    }

    private static String normalized(Object value) {
        return String.valueOf(value).strip().toLowerCase(Locale.ROOT);
    }

    private static CheckOutcome named(String name, CheckOutcome outcome) {
        return new CheckOutcome(name, outcome.passed(), outcome.detail());
    }

    private static CheckOutcome score(Check check, EvalRun run) {
        try {
            return check.check(run);
        } catch (RuntimeException e) {
            return CheckOutcome.fail("check", "threw: " + e.getMessage());
        }
    }

    private Result notRun(String agent, EvalCase evalCase, String why) {
        return new Result(agent, evalCase, "NOT_RUN", "", List.of(), List.of(), List.of(),
                List.of(CheckOutcome.fail("started", why)), 0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> plan(Turn turn) {
        return turn.stepsOf(Step.Kind.NOTE).stream().filter(step -> step.name().equals("plan")).findFirst()
                .map(step -> (List<String>) step.detail().get("steps")).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Object recorded) {
        return recorded instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private static Disposition disposition(Object recorded) {
        try {
            return Disposition.valueOf(String.valueOf(recorded));
        } catch (IllegalArgumentException e) {
            return Disposition.UNKNOWN_TOOL;
        }
    }
}
