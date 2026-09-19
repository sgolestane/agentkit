package dev.agentkit.host.models;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.agentkit.anthropic.AnthropicLlmClient;
import dev.agentkit.anthropic.CachePolicy;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.reliability.BudgetExceededException;
import dev.agentkit.core.reliability.ModelPricing;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each organization's model account: which provider its agents' model calls go to and with whose key, how many may
 * run at once, and how much they may spend.
 *
 * <ul>
 *   <li><b>Whose account.</b> Unless its {@code org.yaml} names a {@code provider}, an organization runs on the host's
 *       account ({@code OPENROUTER_API_KEY}) and the host pays. With {@code provider: openrouter} or
 *       {@code provider: anthropic}, it runs on its own, with its {@code MODEL_API_KEY} secret.</li>
 *   <li><b>At once.</b> Each organization may have {@link HostLimits.Limits#concurrentCalls()} model calls running;
 *       one more waits for a free place, so one customer's load queues behind itself rather than in front of
 *       everyone else's.</li>
 *   <li><b>Budgets.</b> Every call is recorded in the {@link UsageLedger}. The organization's own budget
 *       ({@code budget} in {@code org.yaml}) caps everything it spends; the host's budget for it
 *       ({@link HostLimits}) caps what it spends on the host's account. A turn is refused once either is reached,
 *       with a sentence saying which and when it starts again. Deferred actions are recorded but never refused: a
 *       revocation that is due is not held back by a spent budget.</li>
 * </ul>
 */
public final class ModelAccounts {

    private static final Logger LOG = LoggerFactory.getLogger(ModelAccounts.class);

    /** The secret an organization's own model key is in. */
    public static final String KEY_SECRET = "MODEL_API_KEY";

    /** The providers an organization may name. */
    public static final Set<String> PROVIDERS = Set.of("openrouter", "anthropic");

    /** How long a call waits for a free place before it fails. */
    public static final Duration DEFAULT_PATIENCE = Duration.ofMinutes(2);

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    /** What a call is made for. */
    public enum Purpose {
        /** A person's turn, or a tool offered to an MCP caller: refused once a budget is reached. */
        TURN,
        /** A deferred action, which runs whatever has been spent. */
        DEFERRED
    }

    /** Opens a client for a provider with a key: the seam tests replace. */
    @FunctionalInterface
    public interface Providers {
        LlmClient open(String provider, String key);
    }

    /** OpenRouter and Anthropic, which takes the same model ids with or without OpenRouter's {@code anthropic/}. */
    public static final Providers PROVIDERS_OF_RECORD = (provider, key) -> switch (provider) {
        case "openrouter" -> OpenRouterLlmClient.builder(key).title("agentkit host").build();
        case "anthropic" -> new AnthropicLlmClient(AnthropicOkHttpClient.builder().apiKey(key).build(),
                model -> model.startsWith("anthropic/") ? model.substring("anthropic/".length()) : model,
                CachePolicy.NONE);
        default -> throw new IllegalArgumentException("No model provider " + provider);
    };

    private final Optional<LlmClient> hostModel;
    private final HostLimits limits;
    private final UsageLedger ledger;
    private final Function<String, Secrets> secrets;
    private final Providers providers;
    private final Supplier<Instant> clock;
    private final Duration patience;
    private final Map<String, Semaphore> running = new ConcurrentHashMap<>();
    private final Map<String, LlmClient> own = new ConcurrentHashMap<>();

    /**
     * @param hostModel the host's own account, or empty when it has none
     * @param secrets   each organization's secrets, by organization id
     */
    public ModelAccounts(Optional<LlmClient> hostModel, HostLimits limits, UsageLedger ledger,
                         Function<String, Secrets> secrets, Providers providers, Supplier<Instant> clock,
                         Duration patience) {
        this.hostModel = Objects.requireNonNull(hostModel, "hostModel");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.patience = Objects.requireNonNull(patience, "patience");
    }

    /** Every organization on {@code hostModel}, with no budgets and the default number of calls at once. */
    public static ModelAccounts shared(Optional<LlmClient> hostModel) {
        return new ModelAccounts(hostModel, HostLimits.none(), UsageLedger.inMemory(), org -> Secrets.NONE,
                PROVIDERS_OF_RECORD, Instant::now, DEFAULT_PATIENCE);
    }

    public UsageLedger ledger() {
        return ledger;
    }

    // ---------------------------------------------------------------- whether and how a call may be made

    /** Why {@code repo}'s agents cannot call {@code model} at all, if they cannot. */
    public Optional<String> unavailable(OrgRepo repo, String model) {
        Account account = account(repo);
        if (account.unavailable().isPresent()) {
            return account.unavailable();
        }
        if (caps(repo, account).stream().anyMatch(cap -> cap.budget().inUsd()) && limits.price(model).isEmpty()) {
            return Optional.of("The host has no price for the model " + model + ", so it cannot keep "
                    + repo.org() + " to a budget in dollars. The host's operator adds it to the host's limits.");
        }
        return Optional.empty();
    }

    /** Why a turn for {@code repo} is refused now — a budget reached — if it is. */
    public Optional<String> refusal(OrgRepo repo) {
        Account account = account(repo);
        LocalDate today = today();
        LocalDate monthStart = today.withDayOfMonth(1);
        for (Cap cap : caps(repo, account)) {
            Spend day = ledger.spent(repo.org(), today, today, cap.hostPaidOnly());
            Spend month = ledger.spent(repo.org(), monthStart, today, cap.hostPaidOnly());
            Optional<String> reached = cap.budget().reached(day, month);
            if (reached.isPresent()) {
                boolean daily = reached.get().startsWith("for today");
                return Optional.of("Your organization has used its model budget " + reached.get() + ", set "
                        + cap.setBy() + ". It starts again " + (daily ? "at midnight UTC"
                        : "on " + monthStart.plusMonths(1).format(MONTH_DAY)) + ".");
            }
        }
        return Optional.empty();
    }

    /**
     * The client {@code agent} of {@code repo}'s organization calls its model through: on the organization's account,
     * waiting for a free place among its calls at once, refused for a {@link Purpose#TURN} once a budget is reached,
     * and recorded.
     */
    public LlmClient client(OrgRepo repo, String agent, Purpose purpose) {
        Account account = account(repo);
        LlmClient model = account.client().orElseThrow(() -> new LlmException(account.unavailable().orElse("No model")));
        return new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                return call(repo, agent, purpose, account.hostPaid(), request, () -> model.generate(request));
            }

            @Override
            public LlmResponse generate(LlmRequest request, StreamHandler handler) {
                return call(repo, agent, purpose, account.hostPaid(), request, () -> model.generate(request, handler));
            }
        };
    }

    private LlmResponse call(OrgRepo repo, String agent, Purpose purpose, boolean hostPaid, LlmRequest request,
                             Supplier<LlmResponse> call) {
        if (purpose == Purpose.TURN) {
            refusal(repo).ifPresent(why -> {
                throw new BudgetExceededException(why);
            });
        }
        int allowed = limits.of(repo.org()).concurrentCalls();
        Semaphore places = running.computeIfAbsent(repo.org(), org -> new Semaphore(allowed, true));
        try {
            if (!places.tryAcquire(patience.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new LlmException(repo.org() + " already has " + allowed + " model calls running, the most the "
                        + "host runs for it at once, and none finished within " + (patience.toSeconds() >= 1
                        ? patience.toSeconds() + "s" : patience.toMillis() + "ms") + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Stopped while waiting for a place among " + repo.org() + "'s model calls", e);
        }
        LlmResponse response;
        try {
            response = call.get();
        } finally {
            places.release();
        }
        String model = request.model() == null ? "" : request.model();
        double usd = limits.price(model).map(price -> price.costOf(response.usage())).orElse(0.0);
        try {
            ledger.add(new UsageLedger.Key(repo.org(), today(), agent, model, hostPaid), Spend.of(response.usage(), usd));
        } catch (RuntimeException e) {
            LOG.warn("Could not record a model call of {} ({})", repo.org(), agent, e);
        }
        return response;
    }

    // ---------------------------------------------------------------- what the admin view shows

    /** {@code repo}'s organization's account, limits, budgets and what it has spent today and this month. */
    public Map<String, Object> report(OrgRepo repo) {
        Account account = account(repo);
        LocalDate today = today();
        LocalDate monthStart = today.withDayOfMonth(1);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("account", account.hostPaid() ? "host" : repo.provider().orElseThrow());
        account.unavailable().ifPresent(why -> report.put("unavailable", why));
        HostLimits.Limits org = limits.of(repo.org());
        Semaphore places = running.get(repo.org());
        report.put("concurrentCalls", Map.of("limit", org.concurrentCalls(),
                "running", places == null ? 0 : org.concurrentCalls() - places.availablePermits()));
        List<Map<String, Object>> budgets = new ArrayList<>();
        for (Cap cap : caps(repo, account)) {
            Map<String, Object> budget = new LinkedHashMap<>();
            budget.put("setBy", cap.setBy());
            budget.put("hostAccountOnly", cap.hostPaidOnly());
            budget.put("caps", cap.budget().described());
            cap.budget().reached(ledger.spent(repo.org(), today, today, cap.hostPaidOnly()),
                    ledger.spent(repo.org(), monthStart, today, cap.hostPaidOnly())).ifPresent(r -> budget.put("reached", r));
            budgets.add(budget);
        }
        report.put("budgets", budgets);
        report.put("today", spend(ledger.spent(repo.org(), today, today, false)));
        report.put("month", spend(ledger.spent(repo.org(), monthStart, today, false)));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> unpriced = new ArrayList<>();
        for (UsageLedger.Row row : ledger.rows(repo.org(), monthStart, today)) {
            Map<String, Object> described = new LinkedHashMap<>(spend(row.spend()));
            described.put("agent", row.agent());
            described.put("model", row.model());
            described.put("account", row.hostPaid() ? "host" : "own");
            rows.add(described);
            if (limits.price(row.model()).isEmpty() && !unpriced.contains(row.model())) {
                unpriced.add(row.model());
            }
        }
        report.put("byAgent", rows);
        report.put("unpriced", unpriced);
        return report;
    }

    private static Map<String, Object> spend(Spend spend) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("calls", spend.calls());
        described.put("inputTokens", spend.inputTokens());
        described.put("outputTokens", spend.outputTokens());
        described.put("usd", Math.round(spend.usd() * 1_000_000) / 1_000_000.0);
        return described;
    }

    // ---------------------------------------------------------------- helpers

    private record Account(boolean hostPaid, Optional<LlmClient> client, Optional<String> unavailable) {
    }

    private record Cap(Budget budget, boolean hostPaidOnly, String setBy) {
    }

    private Account account(OrgRepo repo) {
        if (repo.provider().isEmpty()) {
            return new Account(true, hostModel, hostModel.isPresent() ? Optional.empty()
                    : Optional.of("No model is configured. Set OPENROUTER_API_KEY, then restart."));
        }
        String provider = repo.provider().get();
        Optional<String> key = secrets.apply(repo.org()).get(KEY_SECRET);
        if (key.isEmpty()) {
            return new Account(false, Optional.empty(), Optional.of(repo.org() + "'s org.yaml names " + provider
                    + " as its model provider, and its " + KEY_SECRET + " secret is not set."));
        }
        LlmClient client = own.computeIfAbsent(repo.org() + '/' + provider + '/' + fingerprint(key.get()),
                ignored -> providers.open(provider, key.get()));
        return new Account(false, Optional.of(client), Optional.empty());
    }

    /** The budgets that apply: the organization's own on all it spends, and the host's on what the host pays for. */
    private List<Cap> caps(OrgRepo repo, Account account) {
        List<Cap> caps = new ArrayList<>();
        if (!repo.budget().isNone()) {
            caps.add(new Cap(repo.budget(), false, "in its org.yaml"));
        }
        Budget host = limits.of(repo.org()).budget();
        if (account.hostPaid() && !host.isNone()) {
            caps.add(new Cap(host, true, "by the host"));
        }
        return caps;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.get(), ZoneOffset.UTC);
    }

    private static String fingerprint(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What {@code model} costs, if the host's operator says; for tests and reports. */
    public Optional<ModelPricing> price(String model) {
        return limits.price(model);
    }
}
