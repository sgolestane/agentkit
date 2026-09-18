package dev.agentkit.host.web;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.store.FileChatStore;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.host.AgentHost;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.HostChat;
import dev.agentkit.host.HostMcp;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.Tenant;
import dev.agentkit.host.RehearsalLog;
import dev.agentkit.host.VersionLog;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.store.Database;
import dev.agentkit.host.store.PostgresChatStore;
import dev.agentkit.host.store.PostgresDeferredActionStore;
import dev.agentkit.host.store.PostgresRehearsalLog;
import dev.agentkit.host.store.PostgresVersionLog;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The agent host: every organization's agents, in one console, from their repositories.
 *
 * <pre>
 * AGENTKIT_HOST_ORGS=$PWD/orgs AGENTKIT_HOST_DEV_SIGN_IN=true OPENROUTER_API_KEY=sk-or-... \
 *   ./mvnw -q -pl agentkit-host exec:exec
 * # then open http://localhost:8400
 * </pre>
 *
 * <p>{@code AGENTKIT_HOST_ORGS} is a directory with one checkout per organization. Each is loaded at the commit it
 * holds and looked at again every {@code AGENTKIT_HOST_RELOAD_SECONDS} (default 30): a new commit becomes the version
 * new conversations start on, and a commit that does not load is logged and left unserved. Keeping the checkouts up
 * to date — a pull on merge — is the deployment's.
 *
 * <p>Configuration: {@code OPENROUTER_API_KEY}; {@code AGENTKIT_HOST_PORT} (default 8400);
 * {@code AGENTKIT_HOST_DATA_DIR} (default {@code data/agentkit-host}); {@code AGENTKIT_SECRET_<ORG>_<NAME>} for each
 * {@code ${secret:NAME}}; {@code AGENTKIT_HOST_ALLOW_LOCAL_CONNECTORS=true} for connectors run as local commands;
 * {@code AGENTKIT_HOST_DEV_SIGN_IN=true} for {@link DevSignIn}, which is the only sign-in so far;
 * {@code AGENTKIT_HOST_DEFERRED_SECONDS} (default 30), how often due deferred actions are run.
 *
 * <p><strong>Where it keeps things.</strong> With {@code AGENTKIT_HOST_DATABASE_URL} ({@code jdbc:postgresql://...},
 * and {@code AGENTKIT_HOST_DATABASE_USER} and {@code AGENTKIT_HOST_DATABASE_PASSWORD} if the url does not carry them),
 * conversations, deferred actions and the versions each organization was loaded at are in Postgres, keyed by
 * organization, and a restart serves again the versions conversations are pinned to. Without it they are files under
 * the data directory, for one process, and a restart serves only each checkout's current version.
 */
public final class AgentHostApp {

    private AgentHostApp() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        Path orgsDir = Path.of(env.getOrDefault("AGENTKIT_HOST_ORGS", "orgs")).toAbsolutePath();
        Path dataDir = Path.of(env.getOrDefault("AGENTKIT_HOST_DATA_DIR", "data/agentkit-host")).toAbsolutePath();
        int port = Integer.parseInt(env.getOrDefault("AGENTKIT_HOST_PORT", "8400").strip());
        long reloadSeconds = Long.parseLong(env.getOrDefault("AGENTKIT_HOST_RELOAD_SECONDS", "30").strip());
        boolean allowLocal = "true".equalsIgnoreCase(env.get("AGENTKIT_HOST_ALLOW_LOCAL_CONNECTORS"));
        boolean devSignIn = "true".equalsIgnoreCase(env.get("AGENTKIT_HOST_DEV_SIGN_IN"));
        String key = env.get(OpenRouterLlmClient.API_KEY_ENV);
        Optional<LlmClient> llm = key == null || key.isBlank() ? Optional.empty()
                : Optional.of(OpenRouterLlmClient.builder(key).title("agentkit host").build());

        if (!Files.isDirectory(orgsDir)) {
            System.err.println("AGENTKIT_HOST_ORGS is " + orgsDir + ", which is not a directory. Point it at a "
                    + "directory holding one checkout per organization, as an absolute path.");
            System.exit(2);
        }
        String databaseUrl = env.get("AGENTKIT_HOST_DATABASE_URL");
        Optional<Database> database = databaseUrl == null || databaseUrl.isBlank() ? Optional.empty()
                : Optional.of(Database.open(databaseUrl.strip(), env.get("AGENTKIT_HOST_DATABASE_USER"),
                        env.get("AGENTKIT_HOST_DATABASE_PASSWORD")));
        VersionLog versions = database.<VersionLog>map(PostgresVersionLog::new).orElseGet(VersionLog::inMemory);
        Map<String, OrgHost> orgs = openOrgs(orgsDir, allowLocal, versions);
        if (orgs.isEmpty()) {
            System.err.println("No organization loaded from " + orgsDir + "; nothing to serve.");
            System.exit(2);
        }
        if (!devSignIn) {
            System.err.println("No sign-in is configured, so every request will be refused. Set "
                    + "AGENTKIT_HOST_DEV_SIGN_IN=true for development.");
        }

        Files.createDirectories(dataDir);
        long deferredSeconds = Long.parseLong(env.getOrDefault("AGENTKIT_HOST_DEFERRED_SECONDS", "30").strip());
        Map<String, DeferredWork> deferred = new LinkedHashMap<>();
        orgs.forEach((name, org) -> {
            DeferredWork work = new DeferredWork(org, agent -> database
                    .<DeferredActionStore>map(db -> new PostgresDeferredActionStore(db, name, agent))
                    .orElseGet(() -> DeferredActionStore.inDirectory(dataDir.resolve("deferred").resolve(name).resolve(agent))),
                    Instant::now, llm);
            work.start(java.time.Duration.ofSeconds(deferredSeconds));
            deferred.put(name, work);
        });
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(orgs, deferred, llm, Instant::now, self::get);
        ChatStore store = database.<ChatStore>map(PostgresChatStore::new)
                .orElseGet(() -> new FileChatStore(dataDir.resolve("chat")));
        ChatRuntime runtime = new ChatRuntime(store, new ChatEvents(), chat);
        self.set(runtime);

        DevSignIn signIn = new DevSignIn(orgs);
        ChatServer.Tenants tenants = devSignIn ? signIn : exchange -> Optional.empty();
        ChatServer server = new ChatServer(port, runtime, tenants, tenant -> overview(tenant, orgs, llm), chat, null);
        if (devSignIn) {
            server.mount("/sign-in", signIn);
            server.mount("/sign-out", signIn);
        }
        // The admin view reads; a report of a pull request's rehearsal comes in with the org's REHEARSAL_TOKEN.
        RehearsalLog rehearsals = database.<RehearsalLog>map(PostgresRehearsalLog::new).orElseGet(RehearsalLog::inMemory);
        AdminApi admin = new AdminApi(orgs, deferred, tenants, rehearsals, org -> secretsFor(org).get("REHEARSAL_TOKEN"),
                Instant::now);
        server.mount("/host/admin", admin.admin());
        server.mount("/host/rehearsals/", admin.reports());
        HostMcp mcp = new HostMcp(orgs, chat, self::get,
                conversation -> "http://localhost:" + port + "/c/" + conversation, java.time.Duration.ofMinutes(5));
        server.mount("/mcp", new HttpMcpEndpoint(new McpServer("agentkit-host", "0.1.0",
                "Your organization's agents. Use ask_<agent> to ask one in plain language, as you would in the console."),
                devSignIn ? DevSignIn.MCP_CALLERS : headers -> Optional.empty(), mcp::toolsFor));
        server.start();

        ScheduledExecutorService reloader = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agentkit-host-reload");
            thread.setDaemon(true);
            return thread;
        });
        reloader.scheduleWithFixedDelay(() -> orgs.values().forEach(AgentHostApp::reload), reloadSeconds,
                reloadSeconds, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            reloader.shutdownNow();
            deferred.values().forEach(DeferredWork::close);
            server.close();
            runtime.close();
            orgs.values().forEach(OrgHost::close);
            database.ifPresent(Database::close);
        }));

        StringBuilder banner = new StringBuilder("\nAgentKit host is running on http://localhost:" + port + "\n");
        orgs.forEach((name, org) -> banner.append("  ").append(name).append(" @ ").append(org.current().repo().version())
                .append(": ").append(String.join(", ", org.current().agents().keySet())).append('\n'));
        banner.append("  model: ").append(llm.isPresent() ? "OpenRouter" : "not configured — set OPENROUTER_API_KEY")
                .append('\n');
        banner.append("  sign-in: ").append(devSignIn ? "DEVELOPMENT (unauthenticated) at /sign-in" : "none").append('\n');
        banner.append("  MCP: http://localhost:").append(port).append("/mcp").append(devSignIn
                ? " (header " + DevSignIn.MCP_HEADER + ": <org>/<email>)" : " (no sign-in configured)").append('\n');
        banner.append("  data: ").append(database.isPresent() ? "Postgres (schema version "
                + database.get().schemaVersion() + ")" : dataDir.toString()).append('\n');
        System.out.println(banner);
        Thread.currentThread().join();
    }

    static Map<String, OrgHost> openOrgs(Path orgsDir, boolean allowLocal, VersionLog versions) throws IOException {
        Map<String, OrgHost> orgs = new LinkedHashMap<>();
        List<Path> checkouts;
        try (Stream<Path> entries = Files.list(orgsDir)) {
            checkouts = entries.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted().toList();
        }
        for (Path checkout : checkouts) {
            String name = checkout.getFileName().toString();
            try {
                OrgHost org = OrgHost.open(checkout, new AgentHost.Options(secretsFor(name), Map.of(), allowLocal),
                        versions);
                if (!org.org().equals(name)) {
                    System.err.println("Skipping " + checkout + ": its org.yaml says " + org.org()
                            + ", and the directory must be named for the organization.");
                    org.close();
                    continue;
                }
                orgs.put(name, org);
            } catch (DefinitionException e) {
                System.err.println("Not serving " + name + ", whose repository has " + e.getMessage());
            }
        }
        return orgs;
    }

    /** {@code ${secret:NAME}} for organization {@code org} is the variable {@code AGENTKIT_SECRET_<ORG>_<NAME>}. */
    static Secrets secretsFor(String org) {
        return Secrets.fromEnv("AGENTKIT_SECRET_" + org.toUpperCase(Locale.ROOT).replace('-', '_') + "_");
    }

    private static void reload(OrgHost org) {
        try {
            org.reload();
        } catch (DefinitionException e) {
            System.err.println("A new version of " + org.org() + " was not loaded; still serving "
                    + org.current().repo().version() + ". It has " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("Reloading " + org.org() + " failed: " + e.getMessage());
        }
    }

    static Map<String, Object> overview(String tenantId, Map<String, OrgHost> orgs, Optional<LlmClient> llm) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("product", "AgentKit");
        Optional<Tenant> tenant = Tenant.parse(tenantId);
        tenant.ifPresent(t -> {
            described.put("user", t.email());
            described.put("org", t.org());
            Optional.ofNullable(orgs.get(t.org())).ifPresent(org -> {
                described.put("version", org.current().repo().version());
                described.put("admin", org.current().principal(t.email()).map(org.current()::isAdmin).orElse(false));
            });
        });
        List<String> problems = new ArrayList<>();
        if (llm.isEmpty()) {
            problems.add("No model is configured. Set OPENROUTER_API_KEY, then restart.");
        }
        described.put("ready", problems.isEmpty());
        described.put("problems", problems);
        return described;
    }
}
