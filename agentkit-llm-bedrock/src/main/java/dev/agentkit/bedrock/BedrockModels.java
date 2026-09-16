package dev.agentkit.bedrock;

import java.util.Set;

/**
 * Bedrock model identifiers and small helpers for normalising them.
 *
 * <p>On Bedrock, Anthropic model ids carry an {@code anthropic.} vendor prefix
 * (e.g. {@code anthropic.claude-opus-4-8}), and cross-region inference profiles
 * add a geography prefix on top ({@code us.anthropic.claude-opus-4-8}).
 *
 * <p><strong>Which id to use.</strong> Current Claude models generally cannot be
 * invoked on-demand by the bare foundation-model id — invocation requires a
 * <em>cross-region</em> inference-profile id (the geo-prefixed {@code US_*}
 * constants) or an application-inference-profile ARN (see
 * {@link InferenceProfiles}). The bare foundation-model constants are what
 * discovery keys resolve <em>from</em> — set one as your {@code AgentConfig}
 * model and let a resolver map it to your ARN — not ids to invoke directly.
 * {@link #baseModelId(String)} strips a geography prefix so ids from different
 * sources compare equal.
 */
public final class BedrockModels {

    /** Recognised cross-region inference-profile geography prefixes. */
    private static final Set<String> GEO_PREFIXES = Set.of("us", "eu", "apac", "us-gov");

    // Foundation-model ids — the keys a resolver maps FROM (not on-demand invokable).
    public static final String CLAUDE_OPUS_4_8 = "anthropic.claude-opus-4-8";
    public static final String CLAUDE_OPUS_4_7 = "anthropic.claude-opus-4-7";
    public static final String CLAUDE_OPUS_4_6 = "anthropic.claude-opus-4-6";
    public static final String CLAUDE_SONNET_4_6 = "anthropic.claude-sonnet-4-6";
    public static final String CLAUDE_HAIKU_4_5 = "anthropic.claude-haiku-4-5";

    // US cross-region inference-profile ids — on-demand invokable without an
    // application profile. Use the eu./apac. form (see crossRegion) in other geos.
    public static final String US_CLAUDE_OPUS_4_8 = "us.anthropic.claude-opus-4-8";
    public static final String US_CLAUDE_OPUS_4_7 = "us.anthropic.claude-opus-4-7";
    public static final String US_CLAUDE_OPUS_4_6 = "us.anthropic.claude-opus-4-6";
    public static final String US_CLAUDE_SONNET_4_6 = "us.anthropic.claude-sonnet-4-6";
    public static final String US_CLAUDE_HAIKU_4_5 = "us.anthropic.claude-haiku-4-5";

    private BedrockModels() {
    }

    /**
     * Builds a cross-region inference-profile id by prefixing a geography onto a
     * foundation-model id, e.g. {@code crossRegion("eu", CLAUDE_OPUS_4_8)} →
     * {@code eu.anthropic.claude-opus-4-8}. Any existing geography prefix on
     * {@code modelId} is replaced.
     */
    public static String crossRegion(String geography, String modelId) {
        return geography + "." + baseModelId(modelId);
    }

    /**
     * Strips a leading cross-region geography prefix, if present, leaving the bare
     * foundation-model id. {@code us.anthropic.claude-opus-4-8} →
     * {@code anthropic.claude-opus-4-8}; an id with no geography prefix is
     * returned unchanged.
     */
    public static String baseModelId(String modelId) {
        int dot = modelId.indexOf('.');
        if (dot > 0 && GEO_PREFIXES.contains(modelId.substring(0, dot))) {
            return modelId.substring(dot + 1);
        }
        return modelId;
    }

    /**
     * Extracts the model id from a Bedrock ARN's last path segment, for both
     * {@code .../foundation-model/<id>} and {@code .../inference-profile/<id>}
     * ARNs. A value that is not an ARN (no {@code /}) is returned unchanged.
     */
    public static String modelIdFromArn(String arn) {
        int slash = arn.lastIndexOf('/');
        return slash >= 0 ? arn.substring(slash + 1) : arn;
    }

    /**
     * Reduces a Bedrock model id to the stable logical id AgentKit uses as a
     * resolver key, dropping the parts that differ between the id you configure and
     * the id a profile reports: a cross-region geography prefix, a snapshot date
     * ({@code -YYYYMMDD}), a version ({@code -vN}), and a minor-version suffix
     * ({@code :M}).
     *
     * <p>So {@code us.anthropic.claude-opus-4-6-v1:0} and
     * {@code anthropic.claude-haiku-4-5-20251001-v1:0} both reduce to the bare
     * {@code anthropic.claude-<model>} form — letting an agent configured with a
     * clean constant such as {@link #CLAUDE_OPUS_4_6} resolve to a profile whose
     * underlying model carries the {@code -v1:0} suffix. A value with none of
     * those parts is returned unchanged (bar any geography prefix).
     */
    public static String logicalModelId(String modelId) {
        String id = baseModelId(modelId);
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(0, colon);
        }
        id = id.replaceFirst("-v\\d+$", "");   // drop a "-v1" version
        id = id.replaceFirst("-\\d{8}$", "");  // drop a "-20251001" snapshot date
        return id;
    }
}
