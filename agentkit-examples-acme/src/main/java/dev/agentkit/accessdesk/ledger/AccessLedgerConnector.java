package dev.agentkit.accessdesk.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.desk.CompanyClient;
import dev.agentkit.acme.HttpConnector;
import dev.agentkit.accessdesk.desk.DeskTools;
import dev.agentkit.accessdesk.desk.ExpiryBackstop;
import dev.agentkit.core.deferred.SubjectRecord;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.mcp.HttpMcpConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Access Desk's rules as a connector: the access ledger and the desk's tools, served over MCP to the agent host.
 *
 * <p><strong>The policy is text; the rules are here.</strong> Everything {@link DeskTools} enforces — only
 * low-sensitivity access without an approver, no longer than a resource's maximum, the approver is the owner or the
 * requester's manager and never the requester, only the approver decides and may only shorten — is enforced here,
 * whatever any model concludes, because the tools a model is given are these.
 *
 * <p><strong>Who is asking.</strong> Every tool takes {@code acting_as}: the person the desk acts for, or
 * {@value DeskTools#DESK} for the desk itself (a deferred action). The host binds it from the person it
 * authenticated and hides it from the model, and this connector answers only a caller with its bearer token, which
 * only the host holds. That is the whole of the trust for now; the signed caller assertion in
 * {@code docs/MCP-CONNECTORS.md} will replace it.
 *
 * <p><strong>Deferred work.</strong> {@code get_grant} answers with a grant as a subject record, which is how the
 * host bounds a reminder or a revocation it runs later. And because access that outlives its expiry is the one
 * failure this desk exists to prevent, {@link ExpiryBackstop} runs here on a timer, whatever the host does.
 */
public final class AccessLedgerConnector implements AutoCloseable {

    /** The argument every desk tool takes naming who it acts for. */
    public static final String ACTING_AS = "acting_as";

    static final String INSTRUCTIONS = "Access Desk's ledger: requests, grants and decisions under the company's access "
            + "rules. Every tool acts as the person named in acting_as.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpConnector server;
    private final ScheduledExecutorService backstop;

    private AccessLedgerConnector(HttpConnector server, ScheduledExecutorService backstop) {
        this.server = server;
        this.backstop = backstop;
    }

    /**
     * Serves the ledger's tools on {@code port} (0 for any free one), and runs the expiry backstop every
     * {@code backstopSeconds}.
     */
    public static AccessLedgerConnector serve(int port, String token, AccessLedger ledger, CompanyClient company,
                                              Supplier<Instant> clock, long backstopSeconds) throws java.io.IOException {
        HttpConnector server = HttpConnector.serve(port, "access-ledger", INSTRUCTIONS, token,
                catalog(ledger, company, clock));
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "access-ledger-backstop");
            thread.setDaemon(true);
            return thread;
        });
        ExpiryBackstop expiry = new ExpiryBackstop(ledger, new DeskTools(DeskTools.DESK, ledger, company, clock));
        timer.scheduleWithFixedDelay(() -> {
            try {
                expiry.revokeOverdue(clock.get()).forEach(id ->
                        System.err.println("access-ledger backstop: revoked " + id + ", which had outlived its expiry"));
            } catch (RuntimeException e) {
                System.err.println("access-ledger backstop failed: " + e.getMessage());
            }
        }, backstopSeconds, backstopSeconds, TimeUnit.SECONDS);
        return new AccessLedgerConnector(server, timer);
    }

    public String url() {
        return server.url();
    }

    @Override
    public void close() {
        backstop.shutdownNow();
        server.close();
    }

    /**
     * The desk's tools, each acting as whoever {@code acting_as} names, with the declarations the desk gives them; and
     * {@code get_grant}, a grant as a subject record.
     */
    public static DeclaredTools catalog(AccessLedger ledger, CompanyClient company, Supplier<Instant> clock) {
        DeclaredTools catalog = new DeclaredTools();
        for (DeclaredTools.Entry entry : new DeskTools(DeskTools.DESK, ledger, company, clock).catalog().entries()) {
            catalog.add(new ActingAs(entry.tool(), who -> new DeskTools(who, ledger, company, clock).catalog()
                    .entry(entry.tool().name()).orElseThrow().tool()), entry.declaration());
        }
        catalog.add(FunctionTool.builder("get_grant", "A grant as it stands now, as a subject record: who holds it, who "
                                + "may be told about it, and its fields.")
                        .schema(Map.of("type", "object", "properties", Map.of("grant_id", Map.of("type", "string",
                                "description", "Grant id, e.g. GR-1001")), "required", List.of("grant_id")))
                        .sideEffects(SideEffects.NONE)
                        .provenance(Provenance.FIRST_PARTY)
                        .handler(inv -> subject(ledger, inv.stringArgument("grant_id")))
                        .build(),
                new ToolDeclaration("access-desk", ToolEffect.READ, "grant_id"));
        return catalog;
    }

    private static ToolResult subject(AccessLedger ledger, String grantId) {
        Optional<SubjectRecord> found = DeskTools.grantSubjects(ledger).resolve(DeskTools.GRANT,
                grantId == null ? "" : grantId.strip());
        if (found.isEmpty()) {
            return ToolResult.error("There is no grant " + grantId + ".");
        }
        SubjectRecord record = found.get();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", record.id());
        json.put("identifiers", List.copyOf(record.identifiers()));
        json.put("contacts", List.copyOf(record.contacts()));
        json.put("facts", record.facts());
        json.put("holdings", DeskTools.holdings(record));
        try {
            return ToolResult.ok(JSON.writeValueAsString(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One desk tool, taking {@code acting_as} as well as its own arguments, and run as that person. The argument is
     * required: a call that does not say who it is for is refused, not run as anybody.
     */
    private static final class ActingAs extends ForwardingTool {

        private final Tool described;
        private final java.util.function.Function<String, Tool> as;
        private final Map<String, Object> schema;

        ActingAs(Tool described, java.util.function.Function<String, Tool> as) {
            this.described = described;
            this.as = as;
            this.schema = withActingAs(described.inputSchema());
        }

        @Override
        protected Tool delegate() {
            return described;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema;
        }

        @Override
        public dev.agentkit.core.tool.ToolSpec spec() {
            return new dev.agentkit.core.tool.ToolSpec(name(), description(), schema, inputExamples());
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            String who = Objects.toString(invocation.argument(ACTING_AS), "").strip();
            if (who.isEmpty()) {
                return ToolResult.error(name() + " needs " + ACTING_AS + ": who it acts for.");
            }
            Map<String, Object> arguments = new HashMap<>(invocation.arguments());
            arguments.remove(ACTING_AS);
            return as.apply(who).execute(new ToolInvocation(invocation.id(), invocation.name(), arguments));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> withActingAs(Map<String, Object> schema) {
            Map<String, Object> copy = new LinkedHashMap<>(schema);
            Map<String, Object> properties = new LinkedHashMap<>(
                    (Map<String, Object>) schema.getOrDefault("properties", Map.of()));
            properties.put(ACTING_AS, Map.of("type", "string",
                    "description", "Work email of the person this is done for, or " + DeskTools.DESK + " for the desk"));
            copy.put("properties", properties);
            List<Object> required = new ArrayList<>((List<Object>) schema.getOrDefault("required", List.of()));
            required.add(ACTING_AS);
            copy.put("required", required);
            return copy;
        }
    }

    /**
     * Runs the ledger connector.
     *
     * <pre>
     * LEDGER_TOKEN=... COMPANY_MCP_URL=http://127.0.0.1:8130/mcp COMPANY_MCP_TOKEN=... \
     *   java -cp ... dev.agentkit.accessdesk.ledger.AccessLedgerConnector
     * </pre>
     * {@code LEDGER_PORT} (default 8120), {@code LEDGER_DATA_DIR} (default {@code data/access-ledger}),
     * {@code LEDGER_BACKSTOP_SECONDS} (default 60).
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String token = Objects.requireNonNull(env.get("LEDGER_TOKEN"), "Set LEDGER_TOKEN");
        Path dataDir = Path.of(env.getOrDefault("LEDGER_DATA_DIR", "data/access-ledger")).toAbsolutePath();
        Files.createDirectories(dataDir);
        HttpMcpConnection company = HttpMcpConnection.builder(URI.create(Objects.requireNonNull(env.get("COMPANY_MCP_URL"),
                        "Set COMPANY_MCP_URL")))
                .header("Authorization", "Bearer " + Objects.requireNonNull(env.get("COMPANY_MCP_TOKEN"), "Set COMPANY_MCP_TOKEN"))
                .connect();
        AccessLedgerConnector served = serve(Integer.parseInt(env.getOrDefault("LEDGER_PORT", "8120")), token,
                AccessLedger.open(dataDir.resolve("ledger.json")), new CompanyClient(company), Instant::now,
                Long.parseLong(env.getOrDefault("LEDGER_BACKSTOP_SECONDS", "60")));
        System.out.println("access-ledger at " + served.url());
        Thread.currentThread().join();
    }
}
