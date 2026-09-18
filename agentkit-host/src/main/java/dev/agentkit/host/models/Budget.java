package dev.agentkit.host.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How much an organization's agents may spend on models: tokens and estimated US dollars, per UTC day and per UTC
 * month. A cap of zero is no cap. The spend is checked before each call, so the call that crosses a cap completes and
 * the next one is refused.
 */
public record Budget(long tokensPerDay, long tokensPerMonth, double usdPerDay, double usdPerMonth) {

    /** No cap at all. */
    public static final Budget NONE = new Budget(0, 0, 0, 0);

    public Budget {
        if (tokensPerDay < 0 || tokensPerMonth < 0 || usdPerDay < 0 || usdPerMonth < 0) {
            throw new IllegalArgumentException("A budget is not negative");
        }
    }

    /** Whether nothing is capped. */
    public boolean isNone() {
        return tokensPerDay == 0 && tokensPerMonth == 0 && usdPerDay == 0 && usdPerMonth == 0;
    }

    /** Whether a cap is in dollars, which needs the model's price to keep. */
    public boolean inUsd() {
        return usdPerDay > 0 || usdPerMonth > 0;
    }

    /** This budget with {@code other}'s caps where they are set, for an organization's own over the host's default. */
    public Budget overriddenBy(Budget other) {
        return new Budget(other.tokensPerDay > 0 ? other.tokensPerDay : tokensPerDay,
                other.tokensPerMonth > 0 ? other.tokensPerMonth : tokensPerMonth,
                other.usdPerDay > 0 ? other.usdPerDay : usdPerDay,
                other.usdPerMonth > 0 ? other.usdPerMonth : usdPerMonth);
    }

    /** The cap {@code today} or {@code month} has reached, in words — {@code "for today (2,000,000 tokens)"} — if any. */
    public Optional<String> reached(Spend today, Spend month) {
        if (tokensPerDay > 0 && today.tokens() >= tokensPerDay) {
            return Optional.of("for today (" + tokens(tokensPerDay) + ")");
        }
        if (usdPerDay > 0 && today.usd() >= usdPerDay) {
            return Optional.of("for today (" + usd(usdPerDay) + ")");
        }
        if (tokensPerMonth > 0 && month.tokens() >= tokensPerMonth) {
            return Optional.of("for this month (" + tokens(tokensPerMonth) + ")");
        }
        if (usdPerMonth > 0 && month.usd() >= usdPerMonth) {
            return Optional.of("for this month (" + usd(usdPerMonth) + ")");
        }
        return Optional.empty();
    }

    /** Each cap in words, for showing a budget: {@code ["2,000,000 tokens a day", "$500.00 a month"]}. */
    public List<String> described() {
        List<String> caps = new ArrayList<>();
        if (tokensPerDay > 0) {
            caps.add(tokens(tokensPerDay) + " a day");
        }
        if (usdPerDay > 0) {
            caps.add(usd(usdPerDay) + " a day");
        }
        if (tokensPerMonth > 0) {
            caps.add(tokens(tokensPerMonth) + " a month");
        }
        if (usdPerMonth > 0) {
            caps.add(usd(usdPerMonth) + " a month");
        }
        return caps;
    }

    static String tokens(long tokens) {
        return String.format(Locale.ROOT, "%,d tokens", tokens);
    }

    static String usd(double usd) {
        return String.format(Locale.ROOT, "$%,.2f", usd);
    }
}
