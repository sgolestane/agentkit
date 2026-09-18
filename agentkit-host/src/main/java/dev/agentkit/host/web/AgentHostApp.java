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
import dev.agentkit.host.auth.CallerSigner;
import dev.agentkit.host.auth.Oidc;
import dev.agentkit.host.auth.OidcSignIn;
import dev.agentkit.host.auth.OrgMcp;
import dev.agentkit.host.auth.SessionStore;
import dev.agentkit.host.change.GitHubProposer;
import dev.agentkit.host.change.LocalBranchProposer;
import dev.agentkit.host.change.Proposals;
import dev.agentkit.host.models.HostLimits;
import dev.agentkit.host.models.ModelAccounts;
import dev.agentkit.host.models.UsageLedger;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.store.Database;
import dev.agentkit.host.store.Instances;
import dev.agentkit.host.store.PostgresChatStore;
import dev.agentkit.host.store.PostgresDeferredActionStore;
import dev.agentkit.host.store.PostgresRehearsalLog;
import dev.agentkit.host.store.PostgresSessionStore;
import dev.agentkit.host.store.PostgresUsageLedger;
import dev.agentkit.host.store.PostgresVersionLog;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.io.IOException;
import java.net.URI;
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
 * {@code AGENTKIT_HOST_PUBLIC_URL} (default {@code http://localhost:<port>}), the address people reach the host at;
 * {@code AGENTKIT_HOST_DEV_SIGN_IN=true} for {@link DevSignIn}, which listens on this machine only;
 * {@code AGENTKIT_HOST_DEFERRED_SECONDS} (default 30), how often due deferred actions are run.
 *
 * <p><strong>Models.</strong> An organization runs on the host's OpenRouter account unless its {@code org.yaml} names
 * a {@code provider}, with its {@code MODEL_API_KEY} secret. {@code AGENTKIT_HOST_LIMITS} is the operator's file of
 * model prices, and of how many calls each organization may have running at once and what it may spend on the host's
 * account ({@link HostLimits}); what each spends is recorded with its conversations. See {@link ModelAccounts}.
 *
 * <p><strong>Where it keeps things.</strong> With {@code AGENTKIT_HOST_DATABASE_URL} ({@code jdbc:postgresql://...},
 * and {@code AGENTKIT_HOST_DATABASE_USER} and {@code AGENTKIT_HOST_DATABASE_PASSWORD} if the url does not carry them),
 * conversations, deferred actions and the versions each organization was loaded at are in Postgres, keyed by
 * organization, and a restart serves again the versions conversations are pinned to. Without it they are files under
 * the data directory, for one process, and a restart serves only each checkout's current version.
 *
 * <p><strong>Signing in.</strong> Each organization's people sign in with the identity provider its {@code org.yaml}
 * names under {@code signIn} ({@link OidcSignIn}), with the org's {@code OIDC_CLIENT_SECRET} secret if its client has
 * one; its MCP clients present that provider's access tokens at {@code /orgs/<org>/mcp} ({@link OrgMcp}).
 *
 * <p><strong>Who is calling a connector.</strong> Every connector call carries a caller assertion ({@link CallerSigner}),
 * signed with {@code AGENTKIT_HOST_SIGNING_KEY} (a private EC JWK, for instances that must share it) or a key kept in
 * the data directory; the public half is at {@code /.well-known/jwks.json}.
 *
 * <p><strong>Proposed changes.</strong> An admin's change from the admin view is opened as a pull request on the
 * repository the organization's {@code org.yaml} names, with its {@code GITHUB_TOKEN} secret. For development,
 * {@code AGENTKIT_HOST_PROPOSALS=local} opens it as a branch in the checkout's own repository instead.
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
        URI publicUrl = URI.create(env.getOrDefault("AGENTKIT_HOST_PUBLIC_URL", "http://localhost:" + port).strip());
        Files.createDirectories(dataDir);
        // Every connector call carries an assertion of who it is for, signed with this key; connectors check it against
        // the public half the host publishes at /.well-known/jwks.json.
        String signingKey = env.get("AGENTKIT_HOST_SIGNING_KEY");
        CallerSigner signer = signingKey != null && !signingKey.isBlank()
                ? CallerSigner.fromJwk(publicUrl.toString(), signingKey.strip())
                : CallerSigner.inFile(publicUrl.toString(), dataDir.resolve("caller-signing-key.json"));
        Map<String, OrgHost> orgs = openOrgs(orgsDir, allowLocal, versions, signer);
        if (orgs.isEmpty()) {
            System.err.println("No organization loaded from " + orgsDir + "; nothing to serve.");
            System.exit(2);
        }
        // Each org's identity provider, from its org.yaml, made again when that changes.
        Map<String, Oidc> providersByOrg = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.function.Function<OrgHost, Optional<Oidc>> providers = org -> org.current().repo().signIn()
                .map(spec -> providersByOrg.compute(org.org(), (name, known) -> known != null && known.spec().equals(spec)
                        ? known : new Oidc(spec, secretsFor(name).get("OIDC_CLIENT_SECRET"))));
        if (!devSignIn && orgs.values().stream().noneMatch(org -> org.current().repo().signIn().isPresent())) {
            System.err.println("No organization names an identity provider (signIn in org.yaml), so nobody can sign in. "
                    + "For development, set AGENTKIT_HOST_DEV_SIGN_IN=true.");
        }

        // Each organization's model account: the host's unless its org.yaml names its own provider; how many calls it
        // may have running at once; and its budgets, kept against what every instance of the host records.
        String limitsFile = env.get("AGENTKIT_HOST_LIMITS");
        HostLimits limits = limitsFile == null || limitsFile.isBlank() ? HostLimits.none()
                : HostLimits.load(Path.of(limitsFile.strip()));
        ModelAccounts models = new ModelAccounts(llm, limits,
                database.<UsageLedger>map(PostgresUsageLedger::new).orElseGet(UsageLedger::inMemory),
                AgentHostApp::secretsFor, ModelAccounts.PROVIDERS_OF_RECORD, Instant::now, ModelAccounts.DEFAULT_PATIENCE);

        long deferredSeconds = Long.parseLong(env.getOrDefault("AGENTKIT_HOST_DEFERRED_SECONDS", "30").strip());
        Map<String, DeferredWork> deferred = new LinkedHashMap<>();
        orgs.forEach((name, org) -> {
            DeferredWork work = new DeferredWork(org, agent -> database
                    .<DeferredActionStore>map(db -> new PostgresDeferredActionStore(db, name, agent))
                    .orElseGet(() -> DeferredActionStore.inDirectory(dataDir.resolve("deferred").resolve(name).resolve(agent))),
                    Instant::now, models);
            work.start(java.time.Duration.ofSeconds(deferredSeconds));
            deferred.put(name, work);
        });
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(orgs, deferred, models, Instant::now, self::get);
        // With a database, several instances may share it: each notes the turns it runs, says it is running, and ends
        // the turns an instance that stopped left behind.
        Optional<Instances> instances = database.map(db -> new Instances(db, Instant::now));
        ChatStore store = database.<ChatStore>map(db -> new PostgresChatStore(db, java.time.Clock.systemUTC(),
                        instances.get().id()))
                .orElseGet(() -> new FileChatStore(dataDir.resolve("chat")));
        ChatRuntime runtime = new ChatRuntime(store, new ChatEvents(), chat);
        self.set(runtime);
        instances.ifPresent(i -> i.start(store));

        // Development sign-in trusts whoever says who they are, so a host running it answers this machine only.
        DevSignIn devSignInPage = new DevSignIn(orgs);
        OidcSignIn oidcSignIn = new OidcSignIn(orgs, providers, publicUrl, Instant::now,
                database.<SessionStore>map(PostgresSessionStore::new).orElseGet(SessionStore::inMemory));
        ChatServer.Tenants tenants = devSignIn ? devSignInPage : oidcSignIn;
        java.net.InetSocketAddress address = devSignIn
                ? new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port)
                : new java.net.InetSocketAddress(port);
        ChatServer server = new ChatServer(address, runtime, tenants, tenant -> overview(tenant, orgs, models), chat, null);
        server.mount("/sign-in", devSignIn ? devSignInPage : oidcSignIn);
        server.mount("/sign-out", devSignIn ? devSignInPage : oidcSignIn);
        // The admin view reads; a report of a pull request's rehearsal comes in with the org's REHEARSAL_TOKEN.
        RehearsalLog rehearsals = database.<RehearsalLog>map(PostgresRehearsalLog::new).orElseGet(RehearsalLog::inMemory);
        boolean localProposals = "local".equalsIgnoreCase(env.get("AGENTKIT_HOST_PROPOSALS"));
        Proposals proposals = new Proposals(org -> localProposals
                ? Optional.of(new LocalBranchProposer(org.checkout()))
                : org.current().repo().repository().flatMap(spec -> secretsFor(org.org()).get("GITHUB_TOKEN")
                        .map(token -> new GitHubProposer(spec, token))),
                name -> new AgentHost.Options(secretsFor(name), Map.of(), allowLocal).signedBy(signer));
        AdminApi admin = new AdminApi(orgs, deferred, tenants, rehearsals, org -> secretsFor(org).get("REHEARSAL_TOKEN"),
                Instant::now, proposals, models);
        server.mount("/host/admin", admin.admin());
        server.mount("/host/rehearsals/", admin.reports());
        HostMcp mcp = new HostMcp(orgs, chat, self::get,
                conversation -> publicUrl + "/c/" + conversation, java.time.Duration.ofMinutes(5));
        java.util.function.Supplier<McpServer> mcpServer = () -> new McpServer("agentkit-host", "0.1.0",
                "Your organization's agents. Use ask_<agent> to ask one in plain language, as you would in the console.");
        OrgMcp orgMcp = new OrgMcp(orgs, providers, publicUrl, mcp::toolsFor, mcpServer);
        server.mount("/orgs/", orgMcp.endpoints());
        server.mount("/.well-known/jwks.json", exchange -> {
            try (exchange) {
                byte[] keys = signer.jwks().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/jwk-set+json");
                exchange.getResponseHeaders().set("Cache-Control", "max-age=300");
                exchange.sendResponseHeaders(200, keys.length);
                exchange.getResponseBody().write(keys);
            }
        });
        server.mount("/.well-known/oauth-protected-resource/", orgMcp.metadata());
        if (devSignIn) {
            server.mount("/mcp", new HttpMcpEndpoint(mcpServer.get(), DevSignIn.MCP_CALLERS, mcp::toolsFor));
        }
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
            instances.ifPresent(Instances::close);
            deferred.values().forEach(DeferredWork::close);
            server.close();
            runtime.close();
            orgs.values().forEach(OrgHost::close);
            database.ifPresent(Database::close);
        }));

        StringBuilder banner = new StringBuilder("\nAgentKit host is running on http://localhost:" + port + "\n");
        orgs.forEach((name, org) -> banner.append("  ").append(name).append(" @ ").append(org.current().repo().version())
                .append(": ").append(String.join(", ", org.current().agents().keySet())).append('\n'));
        orgs.forEach((name, org) -> {
            OrgRepo repo = org.current().repo();
            banner.append("  model for ").append(name).append(": ").append(repo.defaultModel()).append(" on ")
                    .append(repo.provider().map(p -> "its own " + p + " account").orElse("the host's account"));
            models.unavailable(repo, repo.defaultModel()).ifPresent(why -> banner.append(" — ").append(why));
            HostLimits.Limits own = limits.of(name);
            banner.append("; ").append(own.concurrentCalls()).append(" calls at once");
            if (!own.budget().isNone()) {
                banner.append("; host budget ").append(String.join(", ", own.budget().described()));
            }
            banner.append('\n');
        });
        if (devSignIn) {
            banner.append("  sign-in: DEVELOPMENT (unauthenticated) at /sign-in; listening on this machine only\n");
            banner.append("  MCP: http://localhost:").append(port).append("/mcp (header ").append(DevSignIn.MCP_HEADER)
                    .append(": <org>/<email>)\n");
        } else {
            banner.append("  sign-in: each organization's identity provider; register ").append(oidcSignIn.redirect())
                    .append(" as its redirect URI\n");
            orgs.values().stream().filter(org -> org.current().repo().signIn().isPresent()).forEach(org -> banner
                    .append("  MCP for ").append(org.org()).append(": ").append(orgMcp.resource(org.org()))
                    .append(" (OAuth with ").append(org.current().repo().signIn().get().issuer()).append(")\n"));
        }
        banner.append("  data: ").append(database.isPresent() ? "Postgres (schema version "
                + database.get().schemaVersion() + "), shared with any other instance; this one is "
                + instances.get().id() : dataDir.toString() + ", for this instance alone").append('\n');
        System.out.println(banner);
        Thread.currentThread().join();
    }

    static Map<String, OrgHost> openOrgs(Path orgsDir, boolean allowLocal, VersionLog versions, CallerSigner signer)
            throws IOException {
        Map<String, OrgHost> orgs = new LinkedHashMap<>();
        List<Path> checkouts;
        try (Stream<Path> entries = Files.list(orgsDir)) {
            checkouts = entries.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted().toList();
        }
        for (Path checkout : checkouts) {
            String name = checkout.getFileName().toString();
            try {
                OrgHost org = OrgHost.open(checkout, new AgentHost.Options(secretsFor(name), Map.of(), allowLocal)
                                .signedBy(signer),
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
            org.reconnect();
        } catch (DefinitionException e) {
            System.err.println("A new version of " + org.org() + " was not loaded; still serving "
                    + org.current().repo().version() + ". It has " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("Reloading " + org.org() + " failed: " + e.getMessage());
        }
    }

    static Map<String, Object> overview(String tenantId, Map<String, OrgHost> orgs, ModelAccounts models) {
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
        tenant.map(t -> orgs.get(t.org())).map(org -> org.current().repo())
                .flatMap(repo -> models.unavailable(repo, repo.defaultModel())).ifPresent(problems::add);
        described.put("ready", problems.isEmpty());
        described.put("problems", problems);
        return described;
    }
}
