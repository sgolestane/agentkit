package dev.agentkit.host.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.agentkit.core.reliability.ModelPricing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * What the host's operator allows each organization, from {@code AGENTKIT_HOST_LIMITS}: how many model calls it may
 * have running at once, however it pays for them, and how much it may spend on the host's own model account. Also the
 * price of each model, which a budget in dollars is kept with.
 *
 * <pre>
 * prices:                        # US dollars per million tokens
 *   anthropic/claude-sonnet-5: {input: 3, output: 15}
 * default:                       # every organization, unless it has its own below
 *   concurrentCalls: 4
 *   budget: {usdPerMonth: 100}
 * orgs:
 *   acme:
 *     concurrentCalls: 8
 *     budget: {usdPerMonth: 500, tokensPerDay: 5000000}
 * </pre>
 *
 * An organization's entry overrides the default's settings it names and keeps the rest. This file is the operator's,
 * not the organization's: what an organization may spend is not something its own pull request can raise.
 */
public record HostLimits(Map<String, ModelPricing> prices, Limits defaults, Map<String, Limits> orgs) {

    /** How many model calls an organization may have running at once when nothing says otherwise. */
    public static final int DEFAULT_CONCURRENT_CALLS = 8;

    private static final Set<String> TOP_KEYS = Set.of("prices", "default", "orgs");
    private static final Set<String> LIMIT_KEYS = Set.of("concurrentCalls", "budget");
    private static final Set<String> BUDGET_KEYS = Set.of("tokensPerDay", "tokensPerMonth", "usdPerDay", "usdPerMonth");
    private static final Set<String> PRICE_KEYS = Set.of("input", "output");

    /**
     * One organization's limits.
     *
     * @param concurrentCalls the most model calls it may have running at once; 0 to take the default's
     * @param budget          the most it may spend on the host's model account
     */
    public record Limits(int concurrentCalls, Budget budget) {
        public Limits {
            if (concurrentCalls < 0) {
                throw new IllegalArgumentException("concurrentCalls is not negative");
            }
            Objects.requireNonNull(budget, "budget");
        }

        Limits overriddenBy(Limits other) {
            return new Limits(other.concurrentCalls > 0 ? other.concurrentCalls : concurrentCalls,
                    budget.overriddenBy(other.budget));
        }
    }

    public HostLimits {
        prices = Map.copyOf(prices);
        Objects.requireNonNull(defaults, "defaults");
        orgs = Map.copyOf(orgs);
    }

    /** No budgets, no prices, and {@link #DEFAULT_CONCURRENT_CALLS} at once for everyone. */
    public static HostLimits none() {
        return new HostLimits(Map.of(), new Limits(DEFAULT_CONCURRENT_CALLS, Budget.NONE), Map.of());
    }

    /** {@code org}'s limits: its own entry over the default. */
    public Limits of(String org) {
        Limits base = new Limits(DEFAULT_CONCURRENT_CALLS, Budget.NONE).overriddenBy(defaults);
        return Optional.ofNullable(orgs.get(org)).map(base::overriddenBy).orElse(base);
    }

    /** What {@code model} costs, if the operator says. */
    public Optional<ModelPricing> price(String model) {
        return Optional.ofNullable(prices.get(model));
    }

    /** The limits in {@code file}; every problem with it is named at once. */
    public static HostLimits load(Path file) {
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the host's limits from " + file + ": " + e.getMessage(), e);
        }
        List<String> problems = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            return none();
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException(file + " must be a mapping of prices, default and orgs");
        }
        unknown(root, TOP_KEYS, "", problems);
        Map<String, ModelPricing> prices = new LinkedHashMap<>();
        root.path("prices").fields().forEachRemaining(entry -> {
            JsonNode price = entry.getValue();
            String where = "prices." + entry.getKey();
            if (!price.isObject()) {
                problems.add(where + " must be {input, output}, US dollars per million tokens");
                return;
            }
            unknown(price, PRICE_KEYS, where + ".", problems);
            double input = price(price.get("input"), where + ".input", problems);
            double output = price(price.get("output"), where + ".output", problems);
            prices.put(entry.getKey(), ModelPricing.of(input, output));
        });
        Limits defaults = root.has("default") ? limits(root.get("default"), "default", problems)
                : new Limits(0, Budget.NONE);
        Map<String, Limits> orgs = new LinkedHashMap<>();
        root.path("orgs").fields().forEachRemaining(entry ->
                orgs.put(entry.getKey(), limits(entry.getValue(), "orgs." + entry.getKey(), problems)));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + " has " + String.join("; ", problems));
        }
        return new HostLimits(prices, defaults, orgs);
    }

    private static Limits limits(JsonNode node, String where, List<String> problems) {
        if (!node.isObject()) {
            problems.add(where + " must be a mapping of concurrentCalls and budget");
            return new Limits(0, Budget.NONE);
        }
        unknown(node, LIMIT_KEYS, where + ".", problems);
        int concurrent = 0;
        if (node.has("concurrentCalls")) {
            JsonNode value = node.get("concurrentCalls");
            if (!value.canConvertToInt() || value.asInt() <= 0) {
                problems.add(where + ".concurrentCalls must be a positive whole number");
            } else {
                concurrent = value.asInt();
            }
        }
        return new Limits(concurrent, node.has("budget") ? budget(node.get("budget"), where + ".budget", problems)
                : Budget.NONE);
    }

    /** A budget as {@code org.yaml} and this file write one; each problem goes to {@code problem} (where, what). */
    public static Budget budget(JsonNode node, String where, BiConsumer<String, String> problem) {
        List<String> problems = new ArrayList<>();
        Budget budget = budget(node, where, problems);
        problems.forEach(p -> {
            int space = p.indexOf(' ');
            problem.accept(p.substring(0, space), p.substring(space + 1));
        });
        return budget;
    }

    private static Budget budget(JsonNode node, String where, List<String> problems) {
        if (!node.isObject()) {
            problems.add(where + " must be a mapping of tokensPerDay, tokensPerMonth, usdPerDay and usdPerMonth");
            return Budget.NONE;
        }
        unknown(node, BUDGET_KEYS, where + ".", problems);
        long tokensPerDay = whole(node.get("tokensPerDay"), where + ".tokensPerDay", problems);
        long tokensPerMonth = whole(node.get("tokensPerMonth"), where + ".tokensPerMonth", problems);
        double usdPerDay = number(node.get("usdPerDay"), where + ".usdPerDay", problems);
        double usdPerMonth = number(node.get("usdPerMonth"), where + ".usdPerMonth", problems);
        return new Budget(tokensPerDay, tokensPerMonth, usdPerDay, usdPerMonth);
    }

    private static long whole(JsonNode value, String where, List<String> problems) {
        if (value == null) {
            return 0;
        }
        if (!value.canConvertToLong() || !value.isIntegralNumber() || value.asLong() <= 0) {
            problems.add(where + " must be a positive whole number");
            return 0;
        }
        return value.asLong();
    }

    private static double number(JsonNode value, String where, List<String> problems) {
        if (value == null) {
            return 0;
        }
        if (!value.isNumber() || value.asDouble() <= 0) {
            problems.add(where + " must be a positive number");
            return 0;
        }
        return value.asDouble();
    }

    private static double price(JsonNode value, String where, List<String> problems) {
        if (value == null || !value.isNumber() || value.asDouble() < 0) {
            problems.add(where + " must be a number of dollars, zero or more");
            return 0;
        }
        return value.asDouble();
    }

    private static void unknown(JsonNode node, Set<String> known, String prefix, List<String> problems) {
        node.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                problems.add(prefix + name + " is not a field here; the fields are " + known.stream().sorted().toList());
            }
        });
    }
}
