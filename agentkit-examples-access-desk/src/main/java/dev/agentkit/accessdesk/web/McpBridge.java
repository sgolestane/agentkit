package dev.agentkit.accessdesk.web;

import dev.agentkit.accessdesk.desk.DeskTools;
import dev.agentkit.accessdesk.tools.Effect;
import dev.agentkit.accessdesk.tools.ToolCatalog;
import dev.agentkit.accessdesk.tools.ToolInfo;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Access Desk as tools for an MCP client such as Claude Code: the same desk, acting as the caller.
 *
 * <p>{@code ask_access_desk} is a turn of conversation with the desk's own agent, in the caller's
 * "Access Desk over MCP" conversation — so the policy, the rules and the approval cards are exactly the
 * console's, and the conversation shows up in the caller's console. The three reads are the desk's own
 * tools, answered directly.
 */
public final class McpBridge {

    static final String CONVERSATION_TITLE = "Access Desk over MCP";

    private final ChatRuntime runtime;
    private final Set<String> users;
    private final Function<String, DeskTools> deskFor;
    private final Function<String, String> consoleFor;
    private final Duration patience;

    /**
     * @param users      the callers the desk knows
     * @param deskFor    the desk's tools acting as a caller
     * @param consoleFor the caller's console URL, to point them at when a turn waits for them
     * @param patience   how long {@code ask_access_desk} waits for the desk to answer
     */
    public McpBridge(ChatRuntime runtime, Set<String> users, Function<String, DeskTools> deskFor,
                     Function<String, String> consoleFor, Duration patience) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.users = Set.copyOf(users);
        this.deskFor = Objects.requireNonNull(deskFor, "deskFor");
        this.consoleFor = Objects.requireNonNull(consoleFor, "consoleFor");
        this.patience = Objects.requireNonNull(patience, "patience");
    }

    /** The tools served to {@code user}, or empty if the desk does not know them. */
    public Optional<ToolCatalog> toolsFor(String user) {
        String caller = user == null ? "" : user.strip().toLowerCase(java.util.Locale.ROOT);
        if (!users.contains(caller)) {
            return Optional.empty();
        }
        ToolCatalog catalog = new ToolCatalog();
        catalog.add(FunctionTool.builder("ask_access_desk",
                        "Ask Access Desk, in plain language, for temporary access or anything about access: \"I need read "
                                + "access to the payments database for INC-4211 for 2 hours\", \"what access do I have?\", "
                                + "\"approve REQ-1002 for one hour\". Access Desk follows the company's access policy and answers "
                                + "as it would in its console.")
                        .schema(Map.of("type", "object", "properties", Map.of("message",
                                Map.of("type", "string", "description", "What to ask Access Desk")), "required", List.of("message")))
                        .sideEffects(SideEffects.EXTERNAL)
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(inv -> ask(caller, inv.stringArgument("message")))
                        .build(),
                new ToolInfo("access-desk", Effect.REQUEST, null));
        ToolCatalog desk = deskFor.apply(caller).catalog();
        for (String read : List.of("my_access", "my_requests", "pending_approvals")) {
            desk.entry(read).ifPresent(catalog::add);
        }
        return Optional.of(catalog);
    }

    private ToolResult ask(String caller, String message) {
        if (message == null || message.isBlank()) {
            return ToolResult.error("Say what you need.");
        }
        Conversation conversation = runtime.store().conversations(caller).stream()
                .filter(c -> CONVERSATION_TITLE.equals(c.title())).findFirst()
                .orElseGet(() -> runtime.store().create(caller, CONVERSATION_TITLE));
        Turn turn = runtime.say(caller, conversation.id(), message.strip(), List.of());
        long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> now = runtime.store().turn(caller, conversation.id(), turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                Turn done = now.get();
                return done.state() == Turn.State.COMPLETED
                        ? ToolResult.ok(done.answer())
                        : ToolResult.error("Access Desk could not finish: " + (done.detail() == null ? done.state() : done.detail()));
            }
            if (now.isPresent() && now.get().state() == Turn.State.WAITING_FOR_HUMAN) {
                return ToolResult.ok("Access Desk is waiting for your confirmation. Open your console at "
                        + consoleFor.apply(caller) + " (conversation \"" + CONVERSATION_TITLE + "\") to confirm or refuse.");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ToolResult.ok("Access Desk is still working on this, or waiting for your confirmation. Follow it in your "
                + "console at " + consoleFor.apply(caller) + " (conversation \"" + CONVERSATION_TITLE + "\").");
    }
}
