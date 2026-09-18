package dev.agentkit.host;

import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A connector a customer might run, over real HTTP: an IT helpdesk with a directory, tickets, MFA resets, messages and
 * account deletion. It records every call, so a test can say what reached it rather than what a model said.
 */
final class HelpdeskConnector implements AutoCloseable {

    static final String TOKEN = "helpdesk-token";
    static final String PRIYA = "priya.natarajan@acme.example";
    static final String DANA = "dana.kim@acme.example";
    static final String SAM = "sam.okafor@acme.example";

    /** One call that reached the connector. */
    /** A call that reached a tool, and who the caller assertion said it was from, when the connector is guarded. */
    record Call(String tool, Map<String, Object> arguments, dev.agentkit.mcp.server.CallerAssertion.Caller caller) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger tickets = new AtomicInteger(1000);
    private final HttpServer server;

    HelpdeskConnector() throws IOException {
        this(catalogName -> true);
    }

    /** @param serves which of the helpdesk's tools this instance serves */
    HelpdeskConnector(java.util.function.Predicate<String> serves) throws IOException {
        this(serves, 0, tools -> tools);
    }

    /**
     * A helpdesk that takes only calls carrying a caller assertion {@code callers} accepts, and whose {@code requester}
     * must be the caller.
     */
    static HelpdeskConnector guarded(dev.agentkit.mcp.server.CallerAssertion callers) throws IOException {
        return new HelpdeskConnector(name -> true, 0, tools -> callers.guard(tools, java.util.Set.of("requester")));
    }

    private HelpdeskConnector(java.util.function.Predicate<String> serves, int port) throws IOException {
        this(serves, port, tools -> tools);
    }

    private HelpdeskConnector(java.util.function.Predicate<String> serves, int port,
                              java.util.function.UnaryOperator<DeclaredTools> wrap) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        DeclaredTools all = catalog();
        DeclaredTools served = wrap.apply(new DeclaredTools(all.entries().stream()
                .filter(e -> serves.test(e.tool().name())).toList()));
        server.createContext("/mcp", new HttpMcpEndpoint(new McpServer("helpdesk", "1", ""),
                HttpMcpEndpoint.Callers.header("Authorization"),
                caller -> caller.equals("Bearer " + TOKEN) ? Optional.of(served) : Optional.empty()));
        server.start();
    }

    /** Serves the helpdesk on a fixed port until stopped, for trying the host by hand: {@code args[0]} is the port. */
    public static void main(String[] args) throws Exception {
        HelpdeskConnector helpdesk = new HelpdeskConnector(name -> true, Integer.parseInt(args[0]));
        System.out.println("helpdesk connector at " + helpdesk.url() + " (token " + TOKEN + ")");
        Thread.currentThread().join();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    List<Call> calls(String tool) {
        return calls.stream().filter(c -> c.tool().equals(tool)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private DeclaredTools catalog() {
        return new DeclaredTools()
                .add(tool("directory_lookup", "Look up a person in the company directory by work email.",
                        Map.of("email", str("Work email")), SideEffects.NONE, args -> {
                            String email = String.valueOf(args.get("email"));
                            return switch (email) {
                                case PRIYA -> ToolResult.ok("{\"email\":\"" + PRIYA + "\",\"name\":\"Priya Natarajan\","
                                        + "\"title\":\"Senior Backend Engineer\",\"department\":\"Payments\","
                                        + "\"manager\":\"" + DANA + "\",\"employee_id\":\"E-2231\","
                                        + "\"groups\":[\"engineering\",\"payments\"]}");
                                case DANA -> ToolResult.ok("{\"email\":\"" + DANA + "\",\"name\":\"Dana Kim\","
                                        + "\"title\":\"Engineering Manager\",\"department\":\"Payments\","
                                        + "\"manager\":\"\",\"groups\":[\"engineering\",\"managers\"]}");
                                case SAM -> ToolResult.ok("{\"email\":\"" + SAM + "\",\"name\":\"Sam Okafor\","
                                        + "\"title\":\"Head of Security\",\"department\":\"Security\","
                                        + "\"manager\":\"\",\"groups\":[\"security\"]}");
                                default -> ToolResult.error("Nobody in the directory has the email " + email);
                            };
                        }), new ToolDeclaration("directory", ToolEffect.READ, "email"))
                .add(tool("open_ticket", "Open an IT ticket for the person asking.",
                        Map.of("summary", str("What is wrong"), "requester", str("Who the ticket is for")),
                        SideEffects.EXTERNAL,
                        args -> ToolResult.ok("Opened TICKET-" + tickets.incrementAndGet() + " for " + args.get("requester")
                                + ": " + args.get("summary"))), new ToolDeclaration("itsm", ToolEffect.REQUEST, "requester"))
                .add(tool("reset_mfa", "Reset a person's multi-factor authentication so they can enroll a new device.",
                        Map.of("email", str("Whose MFA to reset")), SideEffects.EXTERNAL,
                        args -> ToolResult.ok("MFA reset for " + args.get("email") + "; they can enroll a new device now.")),
                        new ToolDeclaration("okta", ToolEffect.GRANT, "email"))
                .add(tool("send_message", "Send someone a direct message.",
                        Map.of("to_email", str("Recipient"), "text", str("The message")), SideEffects.EXTERNAL,
                        args -> ToolResult.ok("Sent to " + args.get("to_email"))),
                        new ToolDeclaration("slack", ToolEffect.NOTIFY, "to_email"))
                .add(tool("delete_account", "Delete a person's account everywhere.",
                        Map.of("email", str("Whose account")), SideEffects.EXTERNAL,
                        args -> ToolResult.ok("Deleted " + args.get("email"))),
                        new ToolDeclaration("okta", ToolEffect.REVOKE, "email"));
    }

    private FunctionTool tool(String name, String description, Map<String, Object> properties, SideEffects effects,
                              java.util.function.Function<Map<String, Object>, ToolResult> handler) {
        return FunctionTool.builder(name, description)
                .schema(Map.of("type", "object", "properties", new LinkedHashMap<>(properties),
                        "required", new ArrayList<>(properties.keySet())))
                .sideEffects(effects)
                .handler(inv -> {
                    calls.add(new Call(name, Map.copyOf(inv.arguments()),
                            dev.agentkit.mcp.server.CallerAssertion.caller().orElse(null)));
                    return handler.apply(inv.arguments());
                })
                .build();
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }
}
