package dev.agentkit.bedrock;

import dev.agentkit.anthropic.ModelResolver;
import dev.agentkit.core.util.Quoted;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileModel;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileType;
import software.amazon.awssdk.services.bedrock.model.ListInferenceProfilesRequest;

/**
 * Discovers Amazon Bedrock <em>application inference profiles</em> at runtime and
 * builds a {@link ModelResolver} that maps logical model ids onto their
 * account-specific profile ARNs.
 *
 * <p>Application inference profiles wrap a foundation model (or a cross-region
 * system profile) with your own ARN, used for cost tracking and tagging. Because
 * the ARN is account-specific, it can't be a compile-time constant — this helper
 * lists your profiles via the Bedrock control-plane API and keys each one by the
 * underlying model id — the exact id, its geography-stripped
 * {@linkplain BedrockModels#baseModelId(String) base}, and its
 * {@linkplain BedrockModels#logicalModelId(String) version-normalized logical id}
 * — so an agent configured with a clean {@code anthropic.claude-opus-4-6} resolves
 * to a profile even when its underlying model reports
 * {@code anthropic.claude-opus-4-6-v1:0}.
 *
 * <p>Discovery is a one-off control-plane call; wrap the resulting resolver in
 * your application's lifecycle and reuse it. If you obtain ARNs another way
 * (config, SSM, your own discovery), skip this class and pass a
 * {@link ModelResolver#ofMap(Map)} built from your map instead.
 */
public final class InferenceProfiles {

    private static final Logger log = LoggerFactory.getLogger(InferenceProfiles.class);

    private InferenceProfiles() {
    }

    /** A resolver over every application inference profile in the account/region. */
    public static ModelResolver resolver(BedrockClient client) {
        return resolver(client, summary -> true);
    }

    /**
     * A resolver over the application inference profiles matching {@code filter}
     * (e.g. by name prefix or tag) — useful when several profiles wrap the same
     * model and you must pick one.
     */
    public static ModelResolver resolver(BedrockClient client, Predicate<InferenceProfileSummary> filter) {
        Map<String, String> mapping = discover(client, filter);
        if (mapping.isEmpty()) {
            // Nothing to rewrite — likely no matching profiles or a too-narrow filter.
            // The resolver will pass ids through, so an unmapped model id would only
            // fail at invoke time; warn now so the cause is clear.
            log.warn("No application inference profiles matched; model ids will pass through "
                    + "unchanged. Check the region, the filter, and bedrock:ListInferenceProfiles access.");
        }
        // Fallback to identity so ids without a profile (or non-Bedrock ids) pass through.
        return ModelResolver.ofMap(mapping);
    }

    /** Discovers all application inference profiles as a {@code modelId → profileArn} map. */
    public static Map<String, String> discover(BedrockClient client) {
        return discover(client, summary -> true);
    }

    /** Discovers the application inference profiles matching {@code filter}. */
    public static Map<String, String> discover(BedrockClient client, Predicate<InferenceProfileSummary> filter) {
        Objects.requireNonNull(client, "client");
        ListInferenceProfilesRequest request = ListInferenceProfilesRequest.builder()
                .typeEquals(InferenceProfileType.APPLICATION)
                .build();
        return mapFromSummaries(client.listInferenceProfilesPaginator(request).inferenceProfileSummaries(), filter);
    }

    /**
     * Builds a {@code modelId → profileArn} map from already-fetched profile
     * summaries. Each summary contributes its underlying model id, that id's
     * {@linkplain BedrockModels#baseModelId(String) base}, and its
     * {@linkplain BedrockModels#logicalModelId(String) version-normalized logical
     * id} as keys — so an agent configured with a clean constant (e.g.
     * {@code anthropic.claude-opus-4-6}) resolves to a profile whose underlying
     * model reports a version-suffixed id ({@code anthropic.claude-opus-4-6-v1:0}).
     * On a key collision the first profile wins and the conflict is logged, so a
     * {@code filter} should narrow ambiguous rosters ahead of this.
     */
    public static Map<String, String> mapFromSummaries(
            Iterable<InferenceProfileSummary> summaries, Predicate<InferenceProfileSummary> filter) {
        Objects.requireNonNull(summaries, "summaries");
        Objects.requireNonNull(filter, "filter");
        Map<String, String> mapping = new LinkedHashMap<>();
        for (InferenceProfileSummary summary : summaries) {
            if (!filter.test(summary)) {
                continue;
            }
            String arn = summary.inferenceProfileArn();
            for (InferenceProfileModel model : summary.models()) {
                if (model.modelArn() == null) {
                    continue;
                }
                String modelId = BedrockModels.modelIdFromArn(model.modelArn());
                putUnique(mapping, modelId, arn, summary);
                putUnique(mapping, BedrockModels.baseModelId(modelId), arn, summary);
                putUnique(mapping, BedrockModels.logicalModelId(modelId), arn, summary);
            }
        }
        return Map.copyOf(mapping);
    }

    private static void putUnique(Map<String, String> mapping, String key, String arn,
                                  InferenceProfileSummary summary) {
        String existing = mapping.putIfAbsent(key, arn);
        if (existing != null && !existing.equals(arn)) {
            // Every one of these is remote text: the ids and ARNs come back from the AWS
            // API, and the profile name is whatever whoever created it chose (#98).
            log.warn("Multiple application inference profiles map to model id '{}': keeping {}, ignoring {} ({}). "
                            + "Pass a filter to disambiguate.",
                    Quoted.of(key), Quoted.of(existing), Quoted.of(arn),
                    Quoted.of(summary.inferenceProfileName()));
        }
    }
}
