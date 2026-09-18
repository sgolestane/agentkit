package dev.agentkit.host;

import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.llm.LlmClient;
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
    private final Optional<LlmClient> llm;
    private final Supplier<Instant> clock;
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
        this.orgs = Map.copyOf(orgs);
        this.deferred = Map.copyOf(deferred);
        this.llm = Objects.requireNonNull(llm, "llm");
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
        return request(agent, input, principal);
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
        return turn.agent().turn(session, llm.get(), turn.principal(), clock.get(), runtime.get(), turn.scheduler());
    }

    /** The pinned agent's way of carrying out the turn: its own loop, or a plan carried out step by step. */
    @Override
    public ChatRuntime.Runner runnerFor(ChatRuntime.Session session) {
        Turn turn = turnFor(session);
        return turn.agent().runner(session, llm.get(), turn.principal(), clock.get(), runtime.get(), turn.scheduler());
    }

    private record Turn(HostedAgent agent, Principal principal, List<dev.agentkit.core.tool.Tool> scheduler) {
    }

    private Turn turnFor(ChatRuntime.Session session) {
        if (llm.isEmpty()) {
            throw new ChatUnavailable("No model is configured.");
        }
        Tenant tenant = Tenant.parse(session.tenantId())
                .orElseThrow(() -> new ChatUnavailable("This conversation belongs to nobody the host knows."));
        Conversation conversation = session.store().conversation(session.tenantId(), session.conversationId())
                .orElseThrow(() -> new ChatUnavailable("There is no such conversation."));
        HostedAgent agent = pinned(tenant, conversation);
        AgentHost version = orgs.get(tenant.org()).version(conversation.agent().version()).orElseThrow();
        Principal principal = version.principal(tenant.email())
                .orElseThrow(() -> new ChatUnavailable("You are not in this organization's directory."));
        List<dev.agentkit.core.tool.Tool> scheduler = Optional.ofNullable(deferred.get(tenant.org()))
                .flatMap(work -> work.schedulerFor(agent, principal)).stream().toList();
        if (scheduler.isEmpty() && agent.subjects().isPresent()) {
            throw new ChatUnavailable(agent.definition().name() + " schedules work for later, and this host is not "
                    + "running deferred work for your organization.");
        }
        return new Turn(agent, principal, scheduler);
    }

    /** The agent, at the version, a conversation is pinned to — or a sentence saying why there is none. */
    private HostedAgent pinned(Tenant tenant, Conversation conversation) {
        OrgHost org = Optional.ofNullable(orgs.get(tenant.org()))
                .orElseThrow(() -> new ChatUnavailable("Your organization has no agents here."));
        Conversation.Pin pin = Optional.ofNullable(conversation.agent())
                .orElseThrow(() -> new ChatUnavailable("This conversation is not with any agent. Start a new one."));
        AgentHost version = org.version(pin.version()).orElseThrow(() -> new ChatUnavailable(
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
