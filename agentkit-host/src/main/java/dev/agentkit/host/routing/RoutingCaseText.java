package dev.agentkit.host.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Objects;

/**
 * A misroute written as a {@code routing.yaml} case, added to the end of the file so the rest of it — its comments and
 * its order — is left as it was: the message, who said it, the turns before it, and the agent it should have gone to.
 */
public final class RoutingCaseText {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RoutingCaseText() {
    }

    /** The case's name: from what was said, and the misroute's own id so that two alike are two cases. */
    public static String name(RoutingLog.Misroute misroute) {
        String words = misroute.said().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String[] parts = words.split("-");
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < parts.length && i < 5; i++) {
            slug.append(slug.isEmpty() ? "" : "-").append(parts[i]);
        }
        String id = misroute.id().replaceAll("[^a-z0-9]", "");
        return (slug.isEmpty() ? "sent-again" : slug.toString()) + "-" + id.substring(Math.max(0, id.length() - 6));
    }

    /**
     * {@code existing} (a {@code routing.yaml}, or empty for none) with the case added.
     *
     * @param as who said it: their email
     */
    public static String appended(String existing, RoutingLog.Misroute misroute, String as) {
        Objects.requireNonNull(misroute, "misroute");
        String text = existing == null || existing.isBlank() ? "cases:\n" : existing.stripTrailing() + "\n";
        StringBuilder added = new StringBuilder("\n  # Sent again to ").append(misroute.chosen()).append(" after the router sent it to ")
                .append(misroute.routedTo() == null ? "no agent" : misroute.routedTo()).append(", ")
                .append(misroute.at()).append(".\n");
        added.append("  - name: ").append(name(misroute)).append('\n');
        added.append("    as: ").append(quoted(as)).append('\n');
        if (!misroute.before().isEmpty()) {
            added.append("    before:\n");
            for (RoutingLog.Before turn : misroute.before()) {
                added.append("      - say: ").append(quoted(turn.said())).append('\n');
                if (turn.agent() != null) {
                    added.append("        agent: ").append(turn.agent()).append('\n');
                }
                added.append("        answer: ").append(quoted(turn.answer())).append('\n');
            }
        }
        added.append("    say: ").append(quoted(misroute.said())).append('\n');
        added.append("    expect: {agent: ").append(misroute.chosen()).append("}\n");
        return text + added;
    }

    /** A YAML double-quoted scalar, which is a JSON string. */
    private static String quoted(String value) {
        try {
            return JSON.writeValueAsString(value == null ? "" : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
