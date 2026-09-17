package dev.agentkit.accessdesk.web;

import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.desk.CompanyClient.Person;
import dev.agentkit.accessdesk.desk.CompanyClient;
import dev.agentkit.accessdesk.desk.DeskAgent;
import dev.agentkit.accessdesk.desk.DeskConfig;
import dev.agentkit.accessdesk.desk.DeskTools;
import dev.agentkit.accessdesk.desk.ExpiryBackstop;
import dev.agentkit.accessdesk.mcp.Connectors;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.store.FileChatStore;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.deferred.DeferredActionScheduler;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.deferred.DeferredRunner;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Access Desk: temporary access by chat, with every grant ending on its own.
 *
 * <p>One process runs everything: the company systems as an MCP server (a subprocess, through
 * {@code connectors.json}); the access ledger and the deferred action store, as files; a chat console per demo
 * person, each on its own port and each its own tenant, which is how a person is identified; the desk server
 * with the landing page, the demo clock and Access Desk's own MCP endpoint; and the deferred runner.
 *
 * <pre>
 * ./mvnw -q -DskipTests -pl agentkit-examples-access-desk -am install
 * OPENROUTER_API_KEY=sk-or-... ./mvnw -q -pl agentkit-examples-access-desk exec:exec
 * # then open http://localhost:8100
 * </pre>
 *
 * <p>Configuration: {@code OPENROUTER_API_KEY}; {@code ACCESS_DESK_MODEL} (default {@value #DEFAULT_MODEL});
 * {@code ACCESS_DESK_PORT} (the desk server, default 8100; consoles take the following ports);
 * {@code ACCESS_DESK_DATA_DIR} (default {@code data/access-desk}); {@code ACCESS_DESK_USERS} (comma-separated emails
 * with a console); {@code ACCESS_DESK_SWEEP_SECONDS} (default 15); and the files in {@link DeskConfig}. Without a
 * model key the consoles still start and say what is missing.
 */
public final class AccessDeskApp {

    public static final String DEFAULT_MODEL = "anthropic/claude-sonnet-5";
    static final List<String> DEFAULT_USERS = List.of("priya.natarajan@acme.example", "dana.kim@acme.example",
            "sam.okafor@acme.example");

    private AccessDeskApp() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        DeskConfig config = DeskConfig.fromEnv();
        Path dataDir = Path.of(env.getOrDefault("ACCESS_DESK_DATA_DIR", "data/access-desk")).toAbsolutePath();
        Files.createDirectories(dataDir);
        int basePort = Integer.parseInt(env.getOrDefault("ACCESS_DESK_PORT", "8100").strip());
        String model = env.getOrDefault("ACCESS_DESK_MODEL", DEFAULT_MODEL);
        String key = env.get(OpenRouterLlmClient.API_KEY_ENV);
        Optional<LlmClient> llm = key == null || key.isBlank() ? Optional.empty()
                : Optional.of(OpenRouterLlmClient.builder(key).title("agentkit access desk").build());
        List<String> problems = llm.isPresent() ? List.of()
                : List.of("No model is configured. Set OPENROUTER_API_KEY (and optionally ACCESS_DESK_MODEL), then restart.");

        DemoClock clock = new DemoClock();
        Connectors.Connected connected = Connectors.connect(config.connectors(), Map.of(
                "java", Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "classpath", System.getProperty("java.class.path"),
                "dataDir", dataDir.toString()));
        CompanyClient company = new CompanyClient(connected.client("company"));
        DeclaredTools companyTools = connected.catalog();
        AccessLedger ledger = AccessLedger.open(dataDir.resolve("ledger.json"));
        DeferredActionStore store = DeferredActionStore.inDirectory(dataDir.resolve("deferred"));
        DeferredActionScheduler scheduler = new DeferredActionScheduler(DeskTools.grantSubjects(ledger), store, clock,
                DeskTools::holdings);

        // Who has a console: the demo users the directory knows.
        List<Person> people = new ArrayList<>();
        for (String email : users(env)) {
            company.person(email).ifPresentOrElse(people::add,
                    () -> System.err.println("Access Desk: " + email + " is not in the directory, so has no console"));
        }

        // The conversational agent, rebuilt per turn as the person the console belongs to.
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        ChatRuntime runtime = new ChatRuntime(new FileChatStore(dataDir.resolve("chat")), new ChatEvents(),
                DeskChat.agents(config, llm, model, company, companyTools, ledger, scheduler, clock, problems, self));
        self.set(runtime);

        // Deferred actions: run by the desk, as the desk, with only what DeferredActions allows.
        DeskTools asDesk = new DeskTools(DeskTools.DESK, ledger, company, null, clock);
        ExpiryBackstop backstop = new ExpiryBackstop(ledger, asDesk);
        DeferredRunner runner = new DeferredRunner(store, DeskTools.grantSubjects(ledger),
                DeskAgent.conversationTools(asDesk, companyTools),
                (goal, tools, gate) -> llm.map(client -> Agent.builder(client, tools.registry(),
                                AgentConfig.builder(model).systemPrompt(config.deferredPrompt()).maxSteps(12).maxTokens(1024).build())
                        .name("access-desk-deferred")
                        .toolGate(gate)
                        .build()
                        .run(goal))
                        .orElseThrow(() -> new IllegalStateException("No model is configured to run deferred actions")),
                clock,
                now -> backstop.revokeOverdue(now).forEach(id ->
                        System.err.println("Access Desk backstop: revoked " + id + ", which had outlived its expiry")));
        runner.start(Duration.ofSeconds(Long.parseLong(env.getOrDefault("ACCESS_DESK_SWEEP_SECONDS", "15").strip())));

        // A console per person.
        List<ChatServer> servers = new ArrayList<>();
        List<DeskServer.Console> consoles = new ArrayList<>();
        Map<String, String> consoleUrls = new LinkedHashMap<>();
        for (int i = 0; i < people.size(); i++) {
            Person person = people.get(i);
            int port = basePort + 1 + i;
            ChatServer server = new ChatServer(port, runtime, person.email(), () -> overview(person, model, problems));
            server.start();
            servers.add(server);
            String url = "http://localhost:" + port;
            consoles.add(new DeskServer.Console(person.email(), person.name(), person.title(), url));
            consoleUrls.put(person.email(), url);
        }

        McpBridge bridge = new McpBridge(runtime, consoleUrls.keySet(),
                caller -> new DeskTools(caller, ledger, company, scheduler, clock),
                caller -> consoleUrls.getOrDefault(caller, "http://localhost:" + basePort), Duration.ofMinutes(3));
        DeskServer desk = new DeskServer(basePort, clock, runner, store, consoles, bridge);
        desk.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            desk.close();
            servers.forEach(ChatServer::close);
            runner.close();
            runtime.close();
            connected.close();
        }));

        StringBuilder banner = new StringBuilder("\nAccess Desk is running on http://localhost:" + basePort + "\n");
        consoles.forEach(c -> banner.append("  ").append(c.name()).append(" (").append(c.title()).append("): ")
                .append(c.url()).append('\n'));
        banner.append("  MCP: http://localhost:").append(basePort).append("/mcp (header X-Access-Desk-User: <email>)\n");
        banner.append("  model: ").append(llm.isPresent() ? model : "not configured — set OPENROUTER_API_KEY").append('\n');
        banner.append("  data: ").append(dataDir).append('\n');
        System.out.println(banner);
        Thread.currentThread().join();
    }

    static Set<String> users(Map<String, String> env) {
        String configured = env.get("ACCESS_DESK_USERS");
        if (configured == null || configured.isBlank()) {
            return new LinkedHashSet<>(DEFAULT_USERS);
        }
        Set<String> users = new LinkedHashSet<>();
        for (String email : configured.split(",")) {
            if (!email.isBlank()) {
                users.add(email.strip().toLowerCase(Locale.ROOT));
            }
        }
        return users;
    }

    static Map<String, Object> overview(Person person, String model, List<String> problems) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("product", "Access Desk");
        described.put("user", person.email());
        described.put("userName", person.name());
        described.put("model", model);
        described.put("ready", problems.isEmpty());
        described.put("problems", problems);
        return described;
    }
}
