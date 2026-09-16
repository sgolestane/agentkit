package dev.agentkit.anthropic;

import java.util.Map;
import java.util.Objects;

/**
 * Translates the logical model id carried on an {@code LlmRequest} into the
 * identifier the provider actually expects on the wire.
 *
 * <p>On the first-party API these are the same string, so the default
 * {@link #IDENTITY} passes it through. On Amazon Bedrock they diverge: the wire
 * id is a foundation-model id (e.g. {@code anthropic.claude-opus-4-8}) or, when
 * the caller uses <em>application inference profiles</em>, an account-specific
 * profile ARN that can only be known at runtime. This seam lets the application
 * keep using stable logical model ids everywhere while the adapter substitutes
 * the concrete wire id per request.
 */
@FunctionalInterface
public interface ModelResolver {

    /** Passes the model id through unchanged (first-party API default). */
    ModelResolver IDENTITY = model -> model;

    /** Returns the wire model id for {@code logicalModel}; never {@code null}. */
    String resolve(String logicalModel);

    /**
     * A resolver backed by a fixed map. Ids present in {@code mapping} resolve to
     * their mapped value; ids absent from it pass through unchanged, so a partial
     * map (e.g. only the models you run) is safe. The map is copied defensively.
     */
    static ModelResolver ofMap(Map<String, String> mapping) {
        Map<String, String> copy = Map.copyOf(mapping);
        return model -> copy.getOrDefault(model, model);
    }

    /**
     * A resolver backed by a fixed map that <em>requires</em> a mapping: an id
     * absent from {@code mapping} raises {@link IllegalArgumentException} rather
     * than passing through. Use when an unmapped id would otherwise reach the
     * provider as an invalid model.
     */
    static ModelResolver ofMapStrict(Map<String, String> mapping) {
        Map<String, String> copy = Map.copyOf(mapping);
        return model -> {
            String resolved = copy.get(model);
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "No wire model mapping for logical model '" + model + "'; known: " + copy.keySet());
            }
            return resolved;
        };
    }

    /** Applies {@code this} then {@code next} — useful to normalise before mapping. */
    default ModelResolver andThen(ModelResolver next) {
        Objects.requireNonNull(next, "next");
        return model -> next.resolve(resolve(model));
    }
}
