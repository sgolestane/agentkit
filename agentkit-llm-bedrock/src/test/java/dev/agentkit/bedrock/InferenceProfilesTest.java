package dev.agentkit.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.anthropic.ModelResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileModel;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileType;

/**
 * Exercises the pure map-building logic against hand-built profile summaries (no
 * AWS calls).
 */
class InferenceProfilesTest {

    private static InferenceProfileSummary appProfile(String name, String arn, String... underlyingModelArns) {
        List<InferenceProfileModel> models = java.util.Arrays.stream(underlyingModelArns)
                .map(arnStr -> InferenceProfileModel.builder().modelArn(arnStr).build())
                .toList();
        return InferenceProfileSummary.builder()
                .inferenceProfileName(name)
                .inferenceProfileArn(arn)
                .type(InferenceProfileType.APPLICATION)
                .models(models)
                .build();
    }

    @Test
    void mapsUnderlyingModelIdAndItsBaseToTheProfileArn() {
        String arn = "arn:aws:bedrock:us-east-1:123:application-inference-profile/opus";
        InferenceProfileSummary profile = appProfile("prod-opus", arn,
                "arn:aws:bedrock:us-east-1:123:inference-profile/us.anthropic.claude-opus-4-8");

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(profile), s -> true);

        // Both the exact cross-region id and its base id resolve to the ARN — and only those.
        assertThat(mapping).hasSize(2);
        assertThat(mapping).containsEntry("us.anthropic.claude-opus-4-8", arn);
        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", arn);
    }

    @Test
    void versionSuffixedModelResolvesViaCleanLogicalId() {
        // Mirrors a real application inference profile whose underlying foundation
        // model carries a "-v1:0" version suffix.
        String arn = "arn:aws:bedrock:us-west-2:123:application-inference-profile/85kwasneck7g";
        InferenceProfileSummary profile = appProfile("eng-opus", arn,
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-6-v1:0");

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(profile), s -> true);

        // The clean constant an agent is configured with resolves to the ARN...
        assertThat(mapping).containsEntry(BedrockModels.CLAUDE_OPUS_4_6, arn);
        // ...as does the exact version-suffixed id.
        assertThat(mapping).containsEntry("anthropic.claude-opus-4-6-v1:0", arn);
    }

    @Test
    void skipsModelsWithNullArnButKeepsSiblings() {
        String arn = "arn:aws:bedrock:us-east-1:123:application-inference-profile/opus";
        InferenceProfileSummary profile = appProfile("prod-opus", arn,
                null, // a model with no modelArn must not crash the build
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8");

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(profile), s -> true);

        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", arn);
        assertThat(mapping).hasSize(1); // only the valid model contributed
    }

    @Test
    void handlesMultiModelAndEmptyModelSummaries() {
        String arn = "arn:app/multi";
        InferenceProfileSummary multi = appProfile("multi", arn,
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8",
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-haiku-4-5");
        InferenceProfileSummary empty = appProfile("empty", "arn:app/empty"); // no models

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(multi, empty), s -> true);

        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", arn);
        assertThat(mapping).containsEntry("anthropic.claude-haiku-4-5", arn);
        assertThat(mapping).hasSize(2); // the empty-models summary contributes nothing
    }

    @Test
    void resolverRewritesLogicalIdAndPassesUnknownThrough() {
        String arn = "arn:aws:bedrock:us-east-1:123:application-inference-profile/opus";
        InferenceProfileSummary profile = appProfile("prod-opus", arn,
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8");

        ModelResolver resolver = ModelResolver.ofMap(
                InferenceProfiles.mapFromSummaries(List.of(profile), s -> true));

        assertThat(resolver.resolve("anthropic.claude-opus-4-8")).isEqualTo(arn);
        assertThat(resolver.resolve("anthropic.claude-haiku-4-5")).isEqualTo("anthropic.claude-haiku-4-5");
    }

    @Test
    void filterSelectsAmongProfilesForTheSameModel() {
        String modelArn = "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8";
        InferenceProfileSummary dev = appProfile("dev-opus", "arn:dev", modelArn);
        InferenceProfileSummary prod = appProfile("prod-opus", "arn:prod", modelArn);

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(
                List.of(dev, prod), s -> s.inferenceProfileName().startsWith("prod"));

        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", "arn:prod");
    }

    @Test
    void collisionOnlyOnTheLogicalKeyStillKeepsExactKeysDistinct() {
        // Two profiles wrap different versions of the same model. Their exact and
        // base keys differ, but both normalize to the same logical id — so only the
        // logical key collides. First wins there; the exact keys stay separate.
        InferenceProfileSummary v1 = appProfile("opus-v1", "arn:v1",
                "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-opus-4-6-v1:0");
        InferenceProfileSummary v2 = appProfile("opus-v2", "arn:v2",
                "arn:aws:bedrock:us-west-2::foundation-model/anthropic.claude-opus-4-6-v2:0");

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(v1, v2), s -> true);

        // The clean constant collides on the logical key — first (v1) wins.
        assertThat(mapping).containsEntry(BedrockModels.CLAUDE_OPUS_4_6, "arn:v1");
        // ...but each exact version id still resolves to its own profile.
        assertThat(mapping).containsEntry("anthropic.claude-opus-4-6-v1:0", "arn:v1");
        assertThat(mapping).containsEntry("anthropic.claude-opus-4-6-v2:0", "arn:v2");
    }

    @Test
    void firstProfileWinsOnCollisionWithoutFilter() {
        String modelArn = "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8";
        InferenceProfileSummary first = appProfile("a-opus", "arn:first", modelArn);
        InferenceProfileSummary second = appProfile("b-opus", "arn:second", modelArn);

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(first, second), s -> true);

        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", "arn:first");
    }
}
