package dev.agentkit.host;

import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.host.routing.Router;
import dev.agentkit.host.models.ModelAccounts;
import dev.agentkit.host.plans.PlanBook;
import dev.agentkit.host.plans.PlanReuse;
import dev.agentkit.host.plans.PlanTask;
import dev.agentkit.host.repo.OrgRepo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every organization's agents behind one chat runtime and one console.
 *
 * <p>A tenant is a person in an organization ({@link Tenant}). The console offers them the agents of their
 * organization's current version that they are in the audience of ({@link #available}), and a conversation they
 * start is pinned to the agent they chose at that version ({@link #pin}). Each turn is then built from the pin, not
 * from whatever is current ({@link #agentFor}): a conversation goes on with the agent it started with until that
 * version is let go, and is then told to start again rather than silently moved to a different one.
 */
public final class HostChat implements ChatRuntime.Agents, ChatServer.AgentCatalog {

    private final Map<String, OrgHost> orgs;
    private final Map<String, DeferredWork> deferred;
    private final ModelAccounts models;
    private final PlanReuse plans;
    private final Supplier<Instant> clock;
    /** The input of each form sent lately, by tenant and the request it made, until its turn runs. */
    private final Map<String, Map<String, Object>> forms = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 1_000;
                }
            });
    private final Supplier<ChatRuntime> runtime;

    /**
     * @param orgs    each organization's agents, by organization id
     * @param llm     the model client, or empty when none is configured, which every turn then says
     * @param runtime the runtime being built around this, for asking a person a question
     */
    public HostChat(Map<String, OrgHost> orgs, Optional<LlmClient> llm, Supplier<Instant> clock,
                    Supplier<ChatRuntime> runtime) {
        this(orgs, Map.of(), llm, clock, runtime);
    }

    /** @param deferred each organization's deferred work, for agents that schedule it */
    public HostChat(Map<String, OrgHost> orgs, Map<String, DeferredWork> deferred, Optional<LlmClient> llm,
                    Supplier<Instant> clock, Supplier<ChatRuntime> runtime) {
        this(orgs, deferred, ModelAccounts.shared(llm), clock, runtime);
    }

    /** @param models each organization's model account: whose, how many calls at once, and its budgets */
    public HostChat(Map<String, OrgHost> orgs, Map<String, DeferredWork> deferred, ModelAccounts models,
                    Supplier<Instant> clock, Supplier<ChatRuntime> runtime) {
        this(orgs, deferred, models, PlanBook.inMemory(), clock, runtime);
    }

    /** @param plans the plans plan-execute agents carried out from their forms, which settled ones are reused from */
    public HostChat(Map<String, OrgHost> orgs, Map<String, DeferredWork> deferred, ModelAccounts models,
                    PlanBook plans, Supplier<Instant> clock, Supplier<ChatRuntime> runtime) {
        this.plans = new PlanReuse(plans);
        this.orgs = Map.copyOf(orgs);
        this.deferred = Map.copyOf(deferred);
        this.models = Objects.requireNonNull(models, "models");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    // ---------------------------------------------------------------- the catalog

    @Override
    public List<Map<String, Object>> available(String tenantId) {
        return principal(tenantId).map(found -> found.host().agentsFor(found.principal()).stream()
                .map(agent -> {
                    Map<String, Object> described = new LinkedHashMap<>();
                    described.put("id", agent.definition().id());
                    described.put("name", agent.definition().name());
                    described.put("description", agent.definition().description());
                    agent.unavailable().ifPresent(why -> described.put("unavailable", why));
                    if (agent.definition().input() != null) {
                        described.put("input", agent.definition().input().jsonSchema());
                    }
                    return described;
                }).toList()).orElse(List.of());
    }

    @Override
    public Conversation.Pin pin(String tenantId, String agentId) {
        Found found = principal(tenantId).orElseThrow(() ->
                new ChatUnavailable("You are not in this organization's directory."));
        List<HostedAgent> offered = found.host().agentsFor(found.principal());
        HostedAgent chosen;
        if ((agentId == null || agentId.isBlank()) && routes(found.host(), offered)) {
            // No agent chosen, and more than one to choose from: each message finds its own.
            return null;
        }
        if (agentId == null || agentId.isBlank()) {
            if (offered.size() != 1) {
                throw new ChatUnavailable(offered.isEmpty() ? "No agent is available to you."
                        : "Choose which agent to talk to.");
            }
            chosen = offered.get(0);
        } else {
            chosen = offered.stream().filter(a -> a.definition().id().equals(agentId.strip())).findFirst()
                    .orElseThrow(() -> new ChatUnavailable("There is no agent " + agentId.strip() + " for you."));
        }
        return new Conversation.Pin(chosen.definition().id(), found.host().repo().version());
    }

    /** A form's input, checked against the pinned agent's schema and made into its request. */
    @Override
    public String message(String tenantId, Conversation conversation, Map<String, Object> input) {
        Tenant tenant = Tenant.parse(tenantId)
                .orElseThrow(() -> new ChatUnavailable("This conversation belongs to nobody the host knows."));
        HostedAgent agent = pinned(tenant, conversation);
        Principal principal = principal(tenantId).map(Found::principal)
                .orElseThrow(() -> new ChatUnavailable("You are not in this organization's directory."));
        String request = request(agent, input, principal);
        formSent(tenantId, request, input);
        return request;
    }

    /** A form for {@code agentId}, sent in a conversation pinned to no agent: checked and made into its request. */
    @Override
    public String message(String tenantId, Conversation conversation, String agentId, Map<String, Object> input) {
        Found found = principal(tenantId).orElseThrow(() ->
                new ChatUnavailable("You are not in this organization's directory."));
        HostedAgent agent = offered(found, agentId);
        String request = request(agent, input, found.principal());
        formSent(tenantId, request, input);
        return request;
    }

    /** The agent a person chose for one message in a conversation pinned to no agent, at its version now. */
    @Override
    public Conversation.Pin forMessage(String tenantId, Conversation conversation, String agentId) {
        Found found = principal(tenantId).orElseThrow(() ->
                new ChatUnavailable("You are not in this organization's directory."));
        return new Conversation.Pin(offered(found, agentId).definition().id(), found.host().repo().version());
    }

    private static HostedAgent offered(Found found, String agentId) {
        String id = agentId == null ? "" : agentId.strip();
        return found.host().agentsFor(found.principal()).stream().filter(a -> a.definition().id().equals(id)).findFirst()
                .orElseThrow(() -> new ChatUnavailable("There is no agent " + id + " for you."));
    }

    /** Whether a message nobody sent to a particular agent is routed: the router is on and there is a choice. */
    public static boolean routes(AgentHost host, List<HostedAgent> offered) {
        return host.repo().router().enabled() && offered.size() > 1;
    }

    /** That {@code tenantId} sent {@code input} as a form, which made {@code request}: its turn is that task. */
    void formSent(String tenantId, String request, Map<String, Object> input) {
        forms.put(tenantId + '\n' + request, Map.copyOf(input));
    }

    /** The plans plan-execute agents carried out from their forms. */
    public PlanReuse plans() {
        return plans;
    }

    /** {@code input} as {@code agent}'s request from {@code principal}, or every reason it is not one. */
    static String request(HostedAgent agent, Map<String, Object> input, Principal principal) {
        dev.agentkit.host.repo.TaskInput form = agent.definition().input();
        if (form == null) {
            throw new ChatUnavailable(agent.definition().name() + " takes no form; say what you need instead.");
        }
        List<String> problems = form.problems(input);
        if (!problems.isEmpty()) {
            throw new ChatUnavailable(String.join(". ", problems) + ".");
        }
        return form.render(input, principal::value);
    }

    // ---------------------------------------------------------------- turns

    @Override
    public Agent agentFor(ChatRuntime.Session session) {
        Turn turn = turnFor(session);
        return turn.agent().turn(session, turn.llm(), turn.principal(), clock.get(), runtime.get(), turn.scheduler());
    }

    /**
     * The agent's way of carrying out the turn: its own loop, or a plan carried out step by step. In a conversation
     * pinned to no agent, the turn is routed first — to the agent the person chose for it, or the one the router picks
     * — or the router answers it itself.
     */
    @Override
    public ChatRuntime.Runner runnerFor(ChatRuntime.Session session) {
        Tenant tenant = Tenant.parse(session.tenantId())
                .orElseThrow(() -> new ChatUnavailable("This conversation belongs to nobody the host knows."));
        Conversation conversation = session.store().conversation(session.tenantId(), session.conversationId())
                .orElseThrow(() -> new ChatUnavailable("There is no such conversation."));
        if (conversation.agent() == null && orgs.containsKey(tenant.org()) && routesFor(session, tenant)) {
            return (goal, alsoSent) -> routed(session, tenant, goal, alsoSent);
        }
        return runnerOf(session, turnFor(session));
    }

    private ChatRuntime.Runner runnerOf(ChatRuntime.Session session, Turn turn) {
        Optional<PlanExecuteTurn.FormTask> form = Optional.ofNullable(
                        forms.remove(session.tenantId() + '\n' + session.userText()))
                .filter(input -> turn.agent().definition().planReuse() != null)
                .map(input -> new PlanExecuteTurn.FormTask(plans, new PlanBook.Agent(Tenant.parse(session.tenantId())
                        .orElseThrow().org(), turn.agent().definition().id(), turn.version()),
                        PlanTask.of(turn.agent().definition(), turn.version(), input, turn.principal()::value),
                        session.userText()));
        return turn.agent().runner(session, turn.llm(), turn.principal(), clock.get(), runtime.get(), turn.scheduler(),
                form);
    }

    private record Turn(HostedAgent agent, Principal principal, List<dev.agentkit.core.tool.Tool> scheduler,
                        LlmClient llm, String version) {
    }

    private Turn turnFor(ChatRuntime.Session session) {
        Tenant tenant = Tenant.parse(session.tenantId())
                .orElseThrow(() -> new ChatUnavailable("This conversation belongs to nobody the host knows."));
        Conversation conversation = session.store().conversation(session.tenantId(), session.conversationId())
                .orElseThrow(() -> new ChatUnavailable("There is no such conversation."));
        HostedAgent agent = pinned(tenant, conversation);
        AgentHost version = orgs.get(tenant.org()).serving(conversation.agent().version()).orElseThrow();
        return turnOf(tenant, version, agent);
    }

    /** A turn of {@code agent}, at {@code version}, for the person {@code tenant} names. */
    private Turn turnOf(Tenant tenant, AgentHost version, HostedAgent agent) {
        Principal principal = version.principal(tenant.email())
                .orElseThrow(() -> new ChatUnavailable("You are not in this organization's directory."));
        List<dev.agentkit.core.tool.Tool> scheduler = Optional.ofNullable(deferred.get(tenant.org()))
                .flatMap(work -> work.schedulerFor(agent, principal)).stream().toList();
        if (scheduler.isEmpty() && agent.subjects().isPresent()) {
            throw new ChatUnavailable(agent.definition().name() + " schedules work for later, and this host is not "
                    + "running deferred work for your organization.");
        }
        // The organization's account as it is now — its current org.yaml's provider and budget, not the pinned
        // version's: a budget lowered today holds for conversations begun yesterday.
        OrgRepo account = orgs.get(tenant.org()).current().repo();
        models.unavailable(account, agent.model()).or(() -> models.refusal(account)).ifPresent(why -> {
            throw new ChatUnavailable(why);
        });
        return new Turn(agent, principal, scheduler,
                models.client(account, agent.definition().id(), ModelAccounts.Purpose.TURN), version.repo().version());
    }

    // ---------------------------------------------------------------- routed turns

    /**
     * One turn of a conversation pinned to no agent: to the agent the person chose for it, or the one the router picks
     * — on its own terms, as if the conversation were with it — or answered by the router itself. Which it was is on
     * the turn ({@code Turn.agent}) and in its trace.
     */
    private AgentResult routed(ChatRuntime.Session session, Tenant tenant, Goal goal,
                               List<dev.agentkit.core.message.ContentBlock> alsoSent) {
        OrgHost org = orgs.get(tenant.org());
        AgentHost current = org.current();
        Principal principal = current.principal(tenant.email())
                .orElseThrow(() -> new ChatUnavailable("You are not in this organization's directory."));
        dev.agentkit.chat.Turn turn = session.store().turn(session.tenantId(), session.conversationId(), session.turnId())
                .orElseThrow(() -> new ChatUnavailable("There is no such turn."));
        if (turn.agent() != null) {
            // The person chose the agent for this message.
            AgentHost version = org.serving(turn.agent().version()).orElseThrow(() -> new ChatUnavailable(
                    "That version of " + turn.agent().id() + " is no longer served. Send it again."));
            HostedAgent chosen = version.agent(turn.agent().id()).filter(a -> a.admits(principal))
                    .orElseThrow(() -> new ChatUnavailable("There is no agent " + turn.agent().id() + " for you."));
            note(session, chosen.definition().id(), version.repo().version(), "chosen by you");
            return runnerOf(session, turnOf(tenant, version, chosen)).run(goal, withYou(chosen, alsoSent));
        }
        List<HostedAgent> offered = current.agentsFor(principal);
        OrgRepo account = current.repo();
        String model = account.router().model() != null ? account.router().model() : account.defaultModel();
        models.unavailable(account, model).or(() -> models.refusal(account)).ifPresent(why -> {
            throw new ChatUnavailable(why);
        });
        java.util.concurrent.atomic.AtomicReference<TokenUsage> spent = new java.util.concurrent.atomic.AtomicReference<>(
                TokenUsage.ZERO);
        LlmClient routerLlm = models.client(account, "router", ModelAccounts.Purpose.TURN);
        LlmClient counted = request -> {
            dev.agentkit.core.llm.LlmResponse response = routerLlm.generate(request);
            spent.set(spent.get().plus(response.usage()));
            return response;
        };
        long started = System.nanoTime();
        Router.Decision decision = Router.decide(counted, model, account.router().prompt(),
                offered.stream().map(agent -> new Router.Offered(agent.definition().id(), agent.definition().name(),
                        agent.unavailable().map(why -> agent.definition().description() + " (unavailable now: " + why + ")")
                                .orElse(agent.definition().description()),
                        agent.definition().input() == null ? "" : agent.definition().input().describe())).toList(),
                earlier(session, turn), session.userText());
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (decision instanceof Router.ToAgent to) {
            HostedAgent chosen = offered.stream().filter(a -> a.definition().id().equals(to.agent())).findFirst()
                    .orElseThrow();
            Conversation.Pin pin = new Conversation.Pin(chosen.definition().id(), current.repo().version());
            session.store().route(session.tenantId(), session.conversationId(), session.turnId(), pin);
            note(session, chosen.definition().id(), pin.version(), to.why(), millis);
            AgentResult result = runnerOf(session, turnOf(tenant, current, chosen)).run(goal, withYou(chosen, alsoSent));
            return withUsage(result, spent.get());
        }
        if (decision instanceof Router.OfferForm form) {
            // Shown because they asked for it: the console puts the agent's form in front of them.
            HostedAgent offeredAgent = offered.stream().filter(a -> a.definition().id().equals(form.agent())).findFirst()
                    .orElseThrow();
            session.store().show(session.tenantId(), session.conversationId(), session.turnId(),
                    dev.agentkit.core.tool.View.of("form", Map.of("agent", form.agent(),
                            "name", offeredAgent.definition().name())));
            note(session, null, current.repo().version(), form.why(), millis);
            return AgentResult.completed(form.text(), 1, spent.get());
        }
        String text = decision instanceof Router.Answer answer ? answer.text() : ((Router.Ask) decision).text();
        note(session, null, current.repo().version(), decision.why(), millis);
        return AgentResult.completed(text, 1, spent.get());
    }

    /** Whether this turn is routed: the person chose its agent, or the router is on and they have a choice. */
    private boolean routesFor(ChatRuntime.Session session, Tenant tenant) {
        boolean chosen = session.store().turn(session.tenantId(), session.conversationId(), session.turnId())
                .map(turn -> turn.agent() != null).orElse(false);
        AgentHost current = orgs.get(tenant.org()).current();
        return chosen || current.principal(tenant.email())
                .map(principal -> routes(current, current.agentsFor(principal))).orElse(false);
    }

    /**
     * What a routed turn's agent is sent with the message: who it is, so that in a conversation several agents answer,
     * it knows which earlier answers were its own.
     */
    private static List<dev.agentkit.core.message.ContentBlock> withYou(HostedAgent agent,
                                                                       List<dev.agentkit.core.message.ContentBlock> alsoSent) {
        List<dev.agentkit.core.message.ContentBlock> blocks = new java.util.ArrayList<>(alsoSent);
        blocks.add(dev.agentkit.core.message.TextBlock.of("You are " + agent.definition().name() + " (agent "
                + agent.definition().id() + "). In the earlier turns above, answers marked \"Agent "
                + agent.definition().id() + "\" are yours."));
        return blocks;
    }

    /** The earlier turns of a routed turn's conversation, for the router: said, answered by whom, and the answer. */
    private static List<Router.Earlier> earlier(ChatRuntime.Session session, dev.agentkit.chat.Turn turn) {
        Conversation conversation = session.store().conversation(session.tenantId(), session.conversationId())
                .orElseThrow();
        return session.store().turns(session.tenantId(), session.conversationId()).stream()
                .filter(one -> one.ordinal() < turn.ordinal() && one.state().isTerminal() && !one.leftOut())
                .map(one -> new Router.Earlier(one.userText(),
                        one.agent() != null ? one.agent().id()
                                : conversation.agent() != null ? conversation.agent().id() : null,
                        one.answer()))
                .toList();
    }

    /** Who a routed turn went to, on its trace. */
    private static void note(ChatRuntime.Session session, String agent, String version, String why) {
        note(session, agent, version, why, 0);
    }

    private static void note(ChatRuntime.Session session, String agent, String version, String why, long millis) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to", agent == null ? "router" : agent);
        detail.put("version", version);
        detail.put("why", why == null ? "" : why);
        session.store().addStep(session.tenantId(), session.conversationId(), session.turnId(),
                dev.agentkit.chat.Step.Kind.NOTE, "routed", detail, millis, false);
    }

    private static AgentResult withUsage(AgentResult result, TokenUsage routing) {
        return new AgentResult(result.stopReason(), result.output(), result.steps(), result.usage().plus(routing),
                result.error(), result.awaiting());
    }

    /** The agent, at the version, a conversation is pinned to — or a sentence saying why there is none. */
    private HostedAgent pinned(Tenant tenant, Conversation conversation) {
        OrgHost org = Optional.ofNullable(orgs.get(tenant.org()))
                .orElseThrow(() -> new ChatUnavailable("Your organization has no agents here."));
        Conversation.Pin pin = Optional.ofNullable(conversation.agent())
                .orElseThrow(() -> new ChatUnavailable("This conversation is not with any agent. Start a new one."));
        AgentHost version = org.serving(pin.version()).orElseThrow(() -> new ChatUnavailable(
                "This conversation was with a version of " + pin.id() + " this host no longer runs. Start a new "
                        + "conversation to talk to the current one."));
        return version.agent(pin.id()).orElseThrow(() -> new ChatUnavailable(
                "There is no agent " + pin.id() + " in that version."));
    }

    // ---------------------------------------------------------------- helpers

    private record Found(AgentHost host, Principal principal) {
    }

    /** The tenant's organization at its current version, and who they are in it. */
    private Optional<Found> principal(String tenantId) {
        return Tenant.parse(tenantId).flatMap(tenant -> Optional.ofNullable(orgs.get(tenant.org()))
                .map(OrgHost::current)
                .flatMap(host -> host.principal(tenant.email()).map(p -> new Found(host, p))));
    }
}
