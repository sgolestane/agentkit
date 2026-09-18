package dev.agentkit.host;

import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.mcp.server.McpCall;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's agents as an MCP server, for a client such as Claude Code: each agent a caller may use is a tool.
 *
 * <p>{@code ask_<agent>} is a turn of conversation with that agent, as the caller, in their "&lt;Agent&gt; over MCP"
 * conversation — pinned to the agent's version like any other, and in their console too — so the prompt, the policy,
 * the tools and the confirmations are exactly the console's. An agent's {@code mcp.direct} tools are offered as well,
 * bound to the caller as in a conversation; they are reads, so nothing about them needs a person's word. An agent with
 * an input is also {@code run_<agent>}, whose arguments are its form: checked, made into the request, and then a turn
 * like any other.
 *
 * <p><strong>A confirmation over MCP.</strong> When the turn stops for the person — a confirmed tool, or a question
 * from the agent — and the client can be asked ({@link McpCall}), the person is asked there, directly: the client
 * shows it to them, not to its model, and their answer decides. A client that cannot be asked, or a person who
 * dismisses the question, gets a reply saying what is waiting and where in the console to decide it.
 */
public final class HostMcp {

    private static final Logger LOG = LoggerFactory.getLogger(HostMcp.class);

    private static final Map<String, Object> APPROVAL = Map.of("type", "object",
            "properties", Map.of(
                    "approve", Map.of("type", "boolean", "title", "Approve", "description", "Run it as described"),
                    "note", Map.of("type", "string", "title", "Note", "description", "Optional; said to the agent")),
            "required", List.of("approve"));

    private static final Map<String, Object> QUESTION = Map.of("type", "object",
            "properties", Map.of("answer", Map.of("type", "string", "title", "Your answer")),
            "required", List.of("answer"));

    private final Map<String, OrgHost> orgs;
    private final HostChat chat;
    private final Supplier<ChatRuntime> runtime;
    private final Function<String, String> consoleFor;
    private final Duration patience;

    /**
     * @param consoleFor the console's address for a conversation id, to point a caller at
     * @param patience   how long {@code ask_<agent>} waits for the agent, and for the person when it asks them
     */
    public HostMcp(Map<String, OrgHost> orgs, HostChat chat, Supplier<ChatRuntime> runtime,
                   Function<String, String> consoleFor, Duration patience) {
        this.orgs = Map.copyOf(orgs);
        this.chat = Objects.requireNonNull(chat, "chat");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.consoleFor = Objects.requireNonNull(consoleFor, "consoleFor");
        this.patience = Objects.requireNonNull(patience, "patience");
    }

    /** The tool asking {@code agentId}. */
    public static String askTool(String agentId) {
        return "ask_" + agentId.replace('-', '_');
    }

    /** The tool starting {@code agentId}'s task from its input. */
    public static String runTool(String agentId) {
        return "run_" + agentId.replace('-', '_');
    }

    /** The conversation an MCP caller talks to an agent in. */
    static String conversationTitle(HostedAgent agent) {
        return agent.definition().name() + " over MCP";
    }

    /** The tools served to a tenant: empty for someone the host does not know, which the endpoint refuses. */
    public Optional<DeclaredTools> toolsFor(String tenantId) {
        Optional<Tenant> tenant = Tenant.parse(tenantId);
        Optional<OrgHost> org = tenant.map(t -> orgs.get(t.org()));
        if (tenant.isEmpty() || org.isEmpty()) {
            return Optional.empty();
        }
        AgentHost current = org.get().current();
        Optional<Principal> principal = current.principal(tenant.get().email());
        if (principal.isEmpty()) {
            return Optional.empty();
        }
        DeclaredTools tools = new DeclaredTools();
        for (HostedAgent agent : current.agentsFor(principal.get())) {
            String id = agent.definition().id();
            tools.add(FunctionTool.builder(askTool(id), "Ask " + agent.definition().name() + ": "
                                    + agent.definition().description() + " Say what you need in plain language; it follows "
                                    + current.repo().org() + "'s policy and answers as it does in the console. When it "
                                    + "needs the person's confirmation it asks them directly where this client allows.")
                            .schema(Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string",
                                    "description", "What to ask " + agent.definition().name())), "required", List.of("message")))
                            .sideEffects(SideEffects.EXTERNAL)
                            .provenance(Provenance.FIRST_PARTY)
                            .handler(inv -> ask(tenant.get(), id, inv.stringArgument("message")))
                            .build(),
                    new ToolDeclaration(id, ToolEffect.REQUEST, null));
            if (agent.definition().input() != null) {
                tools.add(FunctionTool.builder(runTool(id), "Start " + agent.definition().name() + " with its input, the "
                                        + "same fields as its form in the console: " + agent.definition().description()
                                        + " It carries the task out as the person, following " + current.repo().org()
                                        + "'s policy, and asks them directly for any confirmation it needs.")
                                .schema(agent.definition().input().jsonSchema())
                                .sideEffects(SideEffects.EXTERNAL)
                                .provenance(Provenance.FIRST_PARTY)
                                .handler(inv -> run(tenant.get(), agent, inv.arguments()))
                                .build(),
                        new ToolDeclaration(id, ToolEffect.REQUEST, null));
            }
            for (DeclaredTools.Entry direct : agent.directTools(principal.get()).entries()) {
                if (tools.entry(direct.tool().name()).isPresent()) {
                    LOG.warn("{} offers {} directly, and another agent already does; offering the first", id,
                            direct.tool().name());
                    continue;
                }
                tools.add(direct);
            }
        }
        return Optional.of(tools);
    }

    private ToolResult run(Tenant tenant, HostedAgent agent, Map<String, Object> input) {
        String request;
        try {
            request = HostChat.request(agent, input);
        } catch (ChatUnavailable refused) {
            return ToolResult.error(refused.getMessage());
        }
        return ask(tenant, agent.definition().id(), request);
    }

    private ToolResult ask(Tenant tenant, String agentId, String message) {
        if (message == null || message.isBlank()) {
            return ToolResult.error("Say what you need.");
        }
        ChatRuntime chats = runtime.get();
        Conversation conversation;
        try {
            conversation = conversationWith(chats, tenant, agentId);
        } catch (ChatUnavailable refused) {
            return ToolResult.error(refused.getMessage());
        }
        Turn turn = chats.say(tenant.id(), conversation.id(), message.strip(), List.of());
        long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ChatRuntime.PendingDecision> owed = chats.pending(tenant.id()).stream()
                    .filter(d -> d.conversationId().equals(conversation.id()) && d.turnId().equals(turn.id()))
                    .findFirst();
            if (owed.isPresent()) {
                Optional<ToolResult> stillWaiting = decide(chats, tenant, conversation, owed.get());
                if (stillWaiting.isPresent()) {
                    return stillWaiting.get();
                }
                continue;
            }
            Optional<Turn> now = chats.store().turn(tenant.id(), conversation.id(), turn.id());
            if (now.isPresent() && now.get().state() == Turn.State.WAITING_FOR_HUMAN) {
                return waiting(conversation, "your decision");
            }
            if (now.isPresent() && now.get().state().isTerminal()) {
                Turn done = now.get();
                return done.state() == Turn.State.COMPLETED ? ToolResult.ok(done.answer())
                        : ToolResult.error("It could not finish: " + (done.detail() == null ? done.state() : done.detail()));
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ToolResult.ok("Still working on this. Follow it in the console at " + consoleFor.apply(conversation.id()) + ".");
    }

    /**
     * Puts what the turn is waiting for to the person, if the client can be asked, and settles it with their
     * answer. Empty when it was settled and the turn goes on; otherwise the reply to give the caller.
     */
    private Optional<ToolResult> decide(ChatRuntime chats, Tenant tenant, Conversation conversation,
                                        ChatRuntime.PendingDecision pending) {
        boolean question = pending.kind() == ChatRuntime.PendingDecision.Kind.QUESTION;
        String what = question ? "an answer to: " + pending.question()
                : "your confirmation to run " + pending.tool() + " " + pending.arguments();
        Optional<McpCall> call = McpCall.current();
        if (call.isEmpty()) {
            return Optional.of(waiting(conversation, what));
        }
        McpCall.Answer answer = question
                ? call.get().elicit(pending.question(), QUESTION, patience)
                : call.get().elicit(confirmation(pending), APPROVAL, patience);
        if (answer.action() == McpCall.Action.CANCEL) {
            return Optional.of(waiting(conversation, what));
        }
        ApprovalDecision decision;
        if (question) {
            Object text = answer.content().get("answer");
            decision = answer.accepted() && text != null && !String.valueOf(text).isBlank()
                    ? ApprovalDecision.approveWithArguments(Map.of("answer", String.valueOf(text).strip()))
                    : ApprovalDecision.deny("The person declined to answer.");
        } else {
            String note = Objects.toString(answer.content().get("note"), "").strip();
            decision = answer.accepted() && Boolean.TRUE.equals(answer.content().get("approve"))
                    ? ApprovalDecision.approve()
                    : ApprovalDecision.deny(note.isEmpty() ? "Refused over MCP." : note);
        }
        chats.decide(tenant.id(), pending.id(), decision, tenant.email(), "over MCP", false);
        return Optional.empty();
    }

    private static String confirmation(ChatRuntime.PendingDecision pending) {
        StringBuilder text = new StringBuilder("Allow ").append(pending.tool()).append("?");
        pending.arguments().forEach((k, v) -> text.append("\n- ").append(k).append(": ").append(v));
        if (pending.reason() != null && !pending.reason().isBlank()) {
            text.append("\n\n").append(pending.reason());
        }
        return text.toString();
    }

    private ToolResult waiting(Conversation conversation, String what) {
        return ToolResult.ok("Waiting for " + what + ". Decide it in the console at " + consoleFor.apply(conversation.id())
                + " (conversation \"" + conversation.title() + "\").");
    }

    /** The caller's conversation with the agent over MCP, at a version the host still runs; a new one otherwise. */
    private Conversation conversationWith(ChatRuntime chats, Tenant tenant, String agentId) {
        OrgHost org = orgs.get(tenant.org());
        HostedAgent agent = org.current().agent(agentId)
                .orElseThrow(() -> new ChatUnavailable("There is no agent " + agentId + "."));
        String title = conversationTitle(agent);
        return chats.store().conversations(tenant.id()).stream()
                .filter(c -> title.equals(c.title()) && c.agent() != null && c.agent().id().equals(agentId)
                        && org.version(c.agent().version()).isPresent())
                .findFirst()
                .orElseGet(() -> chats.store().create(tenant.id(), title, chat.pin(tenant.id(), agentId)));
    }
}
