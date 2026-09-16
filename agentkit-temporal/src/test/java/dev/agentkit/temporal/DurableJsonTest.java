package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.reliability.ModelPricing;
import dev.agentkit.core.reliability.TokenBudget;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the sealed {@code ContentBlock} hierarchy round-trips through the
 * durable converter's object mapper — the one type the default converter cannot
 * handle on its own.
 */
class DurableJsonTest {

    private final ObjectMapper mapper = DurableJson.objectMapper();

    private Message roundTrip(Message message) throws Exception {
        String json = mapper.writeValueAsString(message);
        return mapper.readValue(json, Message.class);
    }

    @Test
    void roundTripsEveryContentBlockType() throws Exception {
        Message original = Message.of(Role.ASSISTANT, List.of(
                TextBlock.of("hello"),
                new ThinkingBlock("pondering", "sig"),
                new ToolUseBlock("t1", "search", Map.of("q", "cats")),
                new ToolResultBlock("t1", "found", true)));

        Message restored = roundTrip(original);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void preservesToolUseInputMap() throws Exception {
        Message original = Message.of(Role.ASSISTANT,
                new ToolUseBlock("t1", "calc", Map.of("a", 2, "b", "three")));

        Message restored = roundTrip(original);

        ToolUseBlock block = (ToolUseBlock) restored.content().get(0);
        assertThat(block.input()).containsEntry("b", "three");
        assertThat(block.name()).isEqualTo("calc");
    }

    @Test
    void preservesNestedAndTypedToolInputValues() throws Exception {
        Message original = Message.of(Role.ASSISTANT,
                new ToolUseBlock("t1", "query", Map.of(
                        "limit", 5,
                        "enabled", true,
                        "filter", Map.of("field", "name", "op", "eq"),
                        "tags", List.of("a", "b"))));

        ToolUseBlock block = (ToolUseBlock) roundTrip(original).content().get(0);

        // JSON has no int/long distinction, so an integer re-hydrates as Integer 5.
        assertThat(block.input()).containsEntry("limit", 5);
        assertThat(block.input()).containsEntry("enabled", true);
        assertThat(block.input().get("filter")).isEqualTo(Map.of("field", "name", "op", "eq"));
        assertThat(block.input().get("tags")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void toolSpecPayloadWithoutExamplesStillDeserializes() throws Exception {
        // A ToolSpec serialized before `examples` existed has no such field. Durable
        // payloads (DurableAgentRun.tools, LlmCallSpec.tools) are re-deserialized on
        // every workflow replay, so this MUST NOT throw — it defaults to empty.
        String legacyJson = "{\"name\":\"search\",\"description\":\"d\","
                + "\"inputSchema\":{\"type\":\"object\"}}";

        ToolSpec restored = mapper.readValue(legacyJson, ToolSpec.class);

        assertThat(restored.name()).isEqualTo("search");
        assertThat(restored.examples()).isEmpty();
    }

    @Test
    void toolResultPayloadWithoutProvenanceStillDeserializes() throws Exception {
        // ToolResult is an activity return type, so it is memoized in history and re-read
        // by the workflow on EVERY replay. A run in flight during a rolling deploy replays
        // payloads written before `provenance` existed; a canonical constructor that
        // required it turned that into a workflow task failure, which Temporal retries
        // forever — the run stalls rather than fails, which is worse than either.
        String legacyJson = "{\"content\":\"a page\",\"isError\":false}";

        dev.agentkit.core.tool.ToolResult restored =
                mapper.readValue(legacyJson, dev.agentkit.core.tool.ToolResult.class);

        assertThat(restored.content()).isEqualTo("a page");
        assertThat(restored.provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.UNKNOWN);
    }

    @Test
    void toolResultPayloadWithoutViewsStillDeserializes() throws Exception {
        // The same rule one component later (#336), and it has to be re-measured rather than
        // assumed: `provenance` is an enum and `views` is a list, and a canonical constructor
        // that called List.copyOf on what Jackson passes would throw on exactly this payload
        // instead of refusing it — a different failure with the same stalled run behind it.
        String legacyJson = "{\"content\":\"a page\",\"isError\":false,"
                + "\"provenance\":\"THIRD_PARTY\"}";

        dev.agentkit.core.tool.ToolResult restored =
                mapper.readValue(legacyJson, dev.agentkit.core.tool.ToolResult.class);

        assertThat(restored.content()).isEqualTo("a page");
        assertThat(restored.views()).isEmpty();
        assertThat(restored.provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
    }

    @Test
    void toolOutcomePayloadWithoutViewsStillDeserializes() throws Exception {
        // ToolOutcome is the activity return type itself, so this is the payload actually in
        // history for every run that started before #336. Written out in full rather than
        // built from the record, because building it from the record would test today's code
        // against itself and pass however the shape moved.
        String legacyJson = "{\"content\":\"published\",\"isError\":false,"
                + "\"provenance\":\"FIRST_PARTY\",\"awaiting\":null,\"lowersTrust\":false,"
                + "\"effective\":null,\"disposition\":\"RAN\",\"brokeAnInvariant\":false}";

        ToolOutcome restored = mapper.readValue(legacyJson, ToolOutcome.class);

        assertThat(restored.content()).isEqualTo("published");
        assertThat(restored.views()).isEmpty();
        assertThat(restored.result().views()).isEmpty();
        assertThat(restored.settledAs()).contains(dev.agentkit.core.tool.Disposition.RAN);
    }

    @Test
    void aViewSurvivesTheActivityBoundary() throws Exception {
        // Carried, not merely tolerated. The durable and in-process runners must agree about
        // what a console is shown, or the same tool renders one way on Temporal and another
        // in process — which is the shape of divergence #122 already had to be fixed once.
        dev.agentkit.core.tool.ToolResult withView = dev.agentkit.core.tool.ToolResult
                .ok("2 categories")
                .withView(dev.agentkit.core.tool.View.table(
                        dev.agentkit.core.tool.View.Column.texts("category", "open"),
                        java.util.List.of(java.util.List.of("access", 12),
                                java.util.List.of("laptop", 4))));

        ToolOutcome restored = mapper.readValue(
                mapper.writeValueAsString(ToolOutcome.of(withView)), ToolOutcome.class);

        assertThat(restored.views()).hasSize(1);
        assertThat(restored.views().get(0).kind()).isEqualTo("table");
        assertThat(restored.result().views().get(0).data().get("rows"))
                .isEqualTo(java.util.List.of(java.util.List.of("access", 12),
                        java.util.List.of("laptop", 4)));
    }

    @Test
    void toolResultBlockPayloadWithoutProvenanceStillDeserializes() throws Exception {
        // The other half, and it breaks on the other worker: LlmCallSpec.conversation is an
        // activity *input* carrying these, so an old workflow worker feeding a new activity
        // worker would fail every LLM call in a tool-using run.
        String legacyJson = "{\"@type\":\"tool_result\",\"toolUseId\":\"t1\","
                + "\"content\":\"a page\",\"isError\":false}";

        ContentBlock restored = mapper.readValue(legacyJson, ContentBlock.class);

        assertThat(restored).isInstanceOf(ToolResultBlock.class);
        assertThat(((ToolResultBlock) restored).provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.UNKNOWN);
    }

    @Test
    void toolResultProvenanceSurvivesTheActivityBoundary() throws Exception {
        // And the label is carried, not merely tolerated. A durable transcript attributed
        // differently from an in-process one would make the two runners disagree about a
        // fact #122 is meant to derive a policy from.
        dev.agentkit.core.tool.ToolResult labelled = dev.agentkit.core.tool.ToolResult
                .from(dev.agentkit.core.tool.Provenance.THIRD_PARTY, "a page");

        assertThat(mapper.readValue(mapper.writeValueAsString(labelled),
                dev.agentkit.core.tool.ToolResult.class).provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
        ContentBlock block = new ToolResultBlock("t1", "a page", false,
                dev.agentkit.core.tool.Provenance.THIRD_PARTY);
        assertThat(((ToolResultBlock) mapper.readValue(mapper.writeValueAsString(block),
                ContentBlock.class)).provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
    }

    @Test
    void toolSpecWithExamplesRoundTrips() throws Exception {
        ToolSpec original = new ToolSpec("search", "search the web",
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))),
                List.of(Map.of("q", "weather in Paris")));

        ToolSpec restored = mapper.readValue(mapper.writeValueAsString(original), ToolSpec.class);

        assertThat(restored.examples()).containsExactly(Map.of("q", "weather in Paris"));
    }

    @Test
    void durableRunPayloadWithoutBudgetStillDeserializes() throws Exception {
        // Workflow input persisted before `budget` existed has no such field. Temporal
        // re-deserializes the input on every replay, so an in-flight run started before
        // this feature MUST NOT break — an absent budget means uncapped.
        String legacyJson = "{\"goal\":{\"description\":\"g\",\"parameters\":{}},"
                + "\"config\":{\"model\":\"m\",\"systemPrompt\":null,\"maxSteps\":5,"
                + "\"maxTokens\":4096,\"options\":{}},"
                + "\"tools\":[],"
                + "\"options\":{\"llmStartToCloseSeconds\":120,\"llmMaxAttempts\":4,"
                + "\"toolStartToCloseSeconds\":60,\"toolMaxAttempts\":3}}";

        DurableAgentRun restored = mapper.readValue(legacyJson, DurableAgentRun.class);

        assertThat(restored.budget()).isNull();
        assertThat(restored.config().maxSteps()).isEqualTo(5);
    }

    @Test
    void anUncappedRunSerializesWithNoBudgetFieldAtAll() throws Exception {
        // Forward compatibility: a worker deployed before `budget` existed rejects
        // unknown fields, and failing input deserialization fails the WORKFLOW TASK,
        // which Temporal retries forever — stalling the run. Omitting the field when
        // it is null keeps an uncapped payload byte-identical to the pre-budget shape,
        // so a rolling deploy is safe for every run that does not opt in.
        String json = mapper.writeValueAsString(DurableAgentRun.of(
                dev.agentkit.core.agent.Goal.of("g"),
                dev.agentkit.core.agent.AgentConfig.builder("m").build()));

        assertThat(json).doesNotContain("budget");
    }

    @Test
    void unknownFieldsAreIgnoredSoInputCanGrowAdditively() throws Exception {
        // The mirror of the test above, for the next component someone adds: a payload
        // from newer code must not blow up this (older) reader.
        String futureJson = "{\"goal\":{\"description\":\"g\",\"parameters\":{}},"
                + "\"config\":{\"model\":\"m\",\"maxSteps\":5,\"maxTokens\":4096,\"options\":{}},"
                + "\"tools\":[],"
                + "\"options\":{\"llmStartToCloseSeconds\":120,\"llmMaxAttempts\":4,"
                + "\"toolStartToCloseSeconds\":60,\"toolMaxAttempts\":3},"
                + "\"somethingAddedLater\":{\"x\":1}}";

        DurableAgentRun restored = mapper.readValue(futureJson, DurableAgentRun.class);

        assertThat(restored.config().maxSteps()).isEqualTo(5);
    }

    @Test
    void theBudgetWireShapeIsPinnedAgainstARenameInCore() throws Exception {
        // A budgeted run stores its TokenBudget in the workflow input and re-reads it on
        // every replay, so the record's component names ARE a persisted wire format. A
        // self-round-trip cannot catch a rename (it writes and reads with the same code);
        // this literal payload can. Renaming a component in agentkit-core — which has no
        // Temporal dependency, so nothing else would object — silently drops the cap from
        // in-flight runs, because the converter ignores unknown fields. Fix the rename,
        // don't update this string.
        String golden = "{\"maxInputTokens\":10,\"maxOutputTokens\":20,\"maxTotalTokens\":30,"
                + "\"maxCostUsd\":2.5,"
                + "\"pricing\":{\"inputPerMillionUsd\":5.0,\"outputPerMillionUsd\":25.0}}";

        TokenBudget restored = mapper.readValue(golden, TokenBudget.class);

        assertThat(restored.maxInputTokens()).isEqualTo(10);
        assertThat(restored.maxOutputTokens()).isEqualTo(20);
        assertThat(restored.maxTotalTokens()).isEqualTo(30);
        assertThat(restored.maxCostUsd()).isEqualTo(2.5);
        assertThat(restored.pricing()).isEqualTo(ModelPricing.of(5.00, 25.00));
        // And the shape we emit still matches the shape we accept.
        assertThat(mapper.readTree(mapper.writeValueAsString(restored)))
                .isEqualTo(mapper.readTree(golden));
    }

    @Test
    void aBudgetedRunRoundTripsIncludingACostCap() throws Exception {
        DurableAgentRun original = DurableAgentRun.of(
                        dev.agentkit.core.agent.Goal.of("g"),
                        dev.agentkit.core.agent.AgentConfig.builder("m").build(), List.of())
                .withBudget(dev.agentkit.core.reliability.TokenBudget.ofCostUsd(2.50,
                        dev.agentkit.core.reliability.ModelPricing.of(5.00, 25.00)));

        DurableAgentRun restored =
                mapper.readValue(mapper.writeValueAsString(original), DurableAgentRun.class);

        assertThat(restored.budget()).isEqualTo(original.budget());
        // The budget still enforces its cap after the round-trip — it crosses the wire
        // as the very type the loop checks, with no rebuild step in between.
        assertThat(restored.budget()
                .isExhausted(new dev.agentkit.core.llm.TokenUsage(1_000_000, 0))).isTrue();
    }

    @Test
    void discriminatorIsWrittenIntoTheJson() throws Exception {
        String json = mapper.writeValueAsString(
                Message.of(Role.USER, List.of(TextBlock.of("hi"))));
        assertThat(json).contains("\"@type\":\"text\"");
    }

    @Test
    void anUnknownDiscriminatorIsRejectedWhereAnUnknownFieldIsTolerated() throws Exception {
        // The asymmetry the rolling-deploy rule rests on, and the reason
        // FAIL_ON_UNKNOWN_PROPERTIES being off is not the general reassurance it reads as.
        // That setting is about unknown *properties*. An unknown *subtype id* is resolved
        // before any property is bound, so nothing tolerates it: this reader fails, and on
        // a durable path a failed read fails the WORKFLOW TASK, which Temporal retries
        // forever — stalling the run rather than failing it.
        //
        // Not about tool_use_unusable in particular. It is written with a discriminator no
        // release has ever used, so it keeps measuring the general fact for whoever adds
        // the next @type: adding a content-block subtype is not a rolling-deploy-safe
        // change the way adding a field is.
        String fromANewerWorker = "{\"role\":\"ASSISTANT\",\"content\":"
                + "[{\"@type\":\"something_added_later\",\"text\":\"hello\"}]}";

        assertThatThrownBy(() -> mapper.readValue(fromANewerWorker, Message.class))
                .as("an old worker cannot resolve a discriminator it has never heard of")
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidTypeIdException.class);

        // Same payload, same reader, with the novelty demoted to a field: tolerated. The
        // two lines together are the rule — grow a payload by adding a field, not a type.
        String sameNoveltyAsAField = "{\"role\":\"ASSISTANT\",\"content\":"
                + "[{\"@type\":\"text\",\"text\":\"hello\","
                + "\"somethingAddedLater\":{\"x\":1}}]}";

        Message tolerated = mapper.readValue(sameNoveltyAsAField, Message.class);

        assertThat(tolerated.content()).hasSize(1);
        assertThat(((TextBlock) tolerated.content().get(0)).text()).isEqualTo("hello");
    }

    @Test
    void anImageSurvivesTheRoundTripAndIsTheDiscriminatorTheRuleWasWrittenAbout()
            throws Exception {
        // #374 added the second @type this file has ever gained, so it is the first real
        // exercise of the rule the test above states in the abstract. Both halves are
        // asserted: that it works, and that shipping it was a deployment change.
        dev.agentkit.core.message.ImageBlock image = dev.agentkit.core.message.ImageBlock.of(
                "image/png", new byte[] {1, 2, 3, 4});

        String json = mapper.writeValueAsString(Message.of(Role.USER, List.of(image)));
        assertThat(json).contains("\"@type\":\"image\"");

        Message back = mapper.readValue(json, Message.class);
        assertThat(back.content()).singleElement()
                .isInstanceOf(dev.agentkit.core.message.ImageBlock.class)
                .satisfies(block -> {
                    dev.agentkit.core.message.ImageBlock read =
                            (dev.agentkit.core.message.ImageBlock) block;
                    assertThat(read.mediaType()).isEqualTo("image/png");
                    // Base64 rather than a byte[], which is what makes this comparison mean
                    // something: a record with an array component has identity equality, so
                    // the round trip would "pass" while comparing two different objects.
                    assertThat(read.base64()).isEqualTo(image.base64());
                    assertThat(read).isEqualTo(image);
                });

        // And the half an operator has to act on: a worker from before this release cannot
        // read it. Every-worker-first, or a drained fleet — not a rolling deploy. Simulated
        // by a reader that has not been taught the subtype, which is exactly what an older
        // worker is.
        com.fasterxml.jackson.databind.ObjectMapper older =
                new com.fasterxml.jackson.databind.ObjectMapper();
        assertThatThrownBy(() -> older.readValue(json, Message.class))
                .as("an older worker resolves @type before any property, so nothing saves it")
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    void aToolResultRecordedBeforeParkingExistedReadsBackAsACompletedCall() throws Exception {
        // The claim ToolOutcome's whole shape rests on. It replaced ToolResult as the tool
        // activity's return type, and an activity return value is written into history and
        // re-read by the workflow on every replay — so a run in flight during the deploy
        // that adds parking is holding payloads with no `awaiting` field at all.
        //
        // Its first three components are ToolResult's, in order, so those payloads land
        // correctly. Wrapping — a nested `result` object — would have made `content` an
        // unknown field and every in-flight tool result come back empty.
        String legacyJson = "{\"content\":\"published:x\",\"isError\":false,"
                + "\"provenance\":\"THIRD_PARTY\"}";

        ToolOutcome restored = mapper.readValue(legacyJson, ToolOutcome.class);

        assertThat(restored.parked())
                .as("nothing was parked, because nothing could be")
                .isEmpty();
        assertThat(restored.result().content()).isEqualTo("published:x");
        assertThat(restored.result().provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
    }

    @Test
    void aParkedOutcomeReadBackByCodeThatDoesNotKnowAboutParkingStillSaysNo() throws Exception {
        // The other direction of the same deploy: a new worker returns this, an old one
        // reads it as a ToolResult. It must not look like the tool answered.
        ToolOutcome parked = ToolOutcome.parked(new dev.agentkit.core.reliability.PendingApproval(
                new dev.agentkit.core.tool.ToolInvocation("t1", "publish", Map.of()),
                dev.agentkit.core.reliability.ApprovalNeeded.because("needs a person")));

        dev.agentkit.core.tool.ToolResult asOldCodeSeesIt = mapper.readValue(
                mapper.writeValueAsString(parked), dev.agentkit.core.tool.ToolResult.class);

        assertThat(asOldCodeSeesIt.isError()).isTrue();
        assertThat(asOldCodeSeesIt.content()).contains("needs a person");
    }

    @Test
    void aRunStartedBeforeAnApprovalCouldBeWaitedOutGetsTheDefaultDeadline() throws Exception {
        // DurableAgentOptions travels in the workflow input, which is likewise re-read from
        // history on every replay. approvalTimeoutSeconds is a primitive, so Jackson passes
        // 0 rather than null for a payload that predates it — and a zero-second deadline
        // would expire the moment a parked run reached it.
        String legacyJson = "{\"llmStartToCloseSeconds\":120,\"llmMaxAttempts\":4,"
                + "\"toolStartToCloseSeconds\":60,\"toolMaxAttempts\":3}";

        DurableAgentOptions restored = mapper.readValue(legacyJson, DurableAgentOptions.class);

        assertThat(restored.approvalTimeoutSeconds())
                .isEqualTo(DurableAgentOptions.DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    @Test
    void anOutcomeRecordedBeforeTrustFloorsExistedLowersNothing() throws Exception {
        // lowersTrust is a Boolean, not a boolean, so a payload written before it existed
        // deserializes to null rather than to a value Jackson invented. A run in flight
        // during that deploy has no floor applied to it at all, so "this result lowered
        // nothing" is both the safe reading and the true one.
        String legacyJson = "{\"content\":\"the page said something\",\"isError\":false,"
                + "\"provenance\":\"THIRD_PARTY\"}";

        ToolOutcome restored = mapper.readValue(legacyJson, ToolOutcome.class);

        assertThat(restored.lowered()).isFalse();
        assertThat(restored.result().provenance())
                .as("and the provenance still arrives, so the run is not left blind")
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
    }

    @Test
    void anOutcomeRecordedBeforeErrorsEndedARunProvesNoBrokenInvariant() throws Exception {
        // #129's schema half, and the thing that makes AgentWorkflowImpl's new branch safe
        // for a run already in flight. brokeAnInvariant is a Boolean like lowersTrust, so a
        // payload from any worker before this change deserializes with it absent and the
        // constructor coerces it to false — and false is the FACT here rather than a safe
        // guess: the code that wrote that payload could not end a run, so the run it
        // describes was not ended.
        String legacyJson = "{\"content\":\"Tool 'publish' failed with"
                + " java.lang.AssertionError.\",\"isError\":true,"
                + "\"provenance\":\"FIRST_PARTY\",\"disposition\":\"THREW\"}";

        ToolOutcome restored = mapper.readValue(legacyJson, ToolOutcome.class);

        assertThat(restored.brokeAnInvariant())
                .as("an old payload was read as proof that a worker invariant broke, which"
                        + " would end a run in flight on a fact its history does not carry")
                .isFalse();
        assertThat(restored.settledAs())
                .as("and the components it does carry still arrive")
                .contains(dev.agentkit.core.tool.Disposition.THREW);
    }

    @Test
    void aBrokenInvariantSaysSoOnTheWire() throws Exception {
        // The other direction: the component has to survive the converter, or the workflow
        // reads false on replay and the run it stopped carries on the next time its history
        // is replayed. Round-tripped rather than asserted on the in-process object, which
        // is the mistake #179's own history test was written to catch.
        ToolOutcome broke = mapper.readValue(mapper.writeValueAsString(
                ToolOutcome.of(
                        new dev.agentkit.core.tool.ToolInvocation("t1", "publish", Map.of()),
                        dev.agentkit.core.tool.ToolResult.from(
                                dev.agentkit.core.tool.Provenance.FIRST_PARTY,
                                "Tool 'publish' failed with java.lang.AssertionError.")
                                .asError(), false,
                        dev.agentkit.core.tool.Disposition.THREW, true)),
                ToolOutcome.class);

        assertThat(broke.brokeAnInvariant()).isTrue();
    }

    @Test
    void aLoweringOutcomeSaysSoOnTheWire() throws Exception {
        ToolOutcome lowering = mapper.readValue(mapper.writeValueAsString(
                ToolOutcome.of(
                        new dev.agentkit.core.tool.ToolInvocation("t1", "fetch", Map.of()),
                        dev.agentkit.core.tool.ToolResult.from(
                                dev.agentkit.core.tool.Provenance.THIRD_PARTY, "a page"), true,
                        dev.agentkit.core.tool.Disposition.RAN, false)),
                ToolOutcome.class);

        assertThat(lowering.lowered()).isTrue();
    }

    @Test
    void anOutcomeRecordedBeforeTheExecutedCallWasWrittenDownFallsBackToTheProposal()
            throws Exception {
        // #179 added `effective` to ToolOutcome, and an activity's return value is re-read
        // by the workflow on every replay — so a run in flight during that deploy is
        // holding payloads with no such field. Nullable and never coerced, for the reason
        // ApprovalNeeded's constructor spells out: refusing a partial payload throws inside
        // a workflow task, which Temporal retries forever, stalling a live run rather than
        // failing it. This is the test that catches that stall before it ships.
        String legacyJson = "{\"content\":\"published:x\",\"isError\":false,"
                + "\"provenance\":\"THIRD_PARTY\",\"lowersTrust\":true}";
        dev.agentkit.core.tool.ToolInvocation proposed =
                new dev.agentkit.core.tool.ToolInvocation("t1", "publish", Map.of("text", "x"));

        ToolOutcome restored = mapper.readValue(legacyJson, ToolOutcome.class);

        assertThat(restored.effective()).isNull();
        assertThat(restored.settled(proposed))
                .as("an old record cannot say a gate narrowed anything, and the proposal is "
                        + "what its history holds")
                .isEqualTo(proposed);
        assertThat(restored.lowered())
                .as("and the components that were already there still arrive")
                .isTrue();
    }

    @Test
    void aParkRecordedBeforeThatFieldExistedStillNamesTheCallTheReviewerSaw() throws Exception {
        // The narrower half of the same deploy, and the reason `effective` is left null on
        // a park rather than written twice: the gate's settled call already travels inside
        // `awaiting`, so a payload from either side of #179 answers through one accessor.
        String legacyJson = "{\"content\":\"a person must look\",\"isError\":true,"
                + "\"provenance\":\"FIRST_PARTY\",\"awaiting\":{\"invocation\":{\"id\":\"t1\","
                + "\"name\":\"publish\",\"arguments\":{\"text\":\"narrowed\"}},"
                + "\"why\":{\"reason\":\"a person must look\"},\"ticket\":\"park-1\"}}";

        ToolOutcome restored = mapper.readValue(legacyJson, ToolOutcome.class);

        assertThat(restored.settled(
                new dev.agentkit.core.tool.ToolInvocation("t1", "publish",
                        Map.of("text", "the proposal"))).stringArgument("text"))
                .isEqualTo("narrowed");
    }

    @Test
    void theExecutedCallSurvivesTheWire() throws Exception {
        // The positive control: a component nothing reads back is a component that does not
        // exist, and history is the only audit trail this path has.
        dev.agentkit.core.tool.ToolInvocation proposed =
                new dev.agentkit.core.tool.ToolInvocation("t1", "publish",
                        Map.of("text", "/etc/shadow"));
        dev.agentkit.core.tool.ToolInvocation narrowed =
                new dev.agentkit.core.tool.ToolInvocation("t1", "publish",
                        Map.of("text", "/tmp/harmless.txt"));

        ToolOutcome restored = mapper.readValue(mapper.writeValueAsString(
                ToolOutcome.of(narrowed, dev.agentkit.core.tool.ToolResult.ok("published"),
                        false, dev.agentkit.core.tool.Disposition.RAN, false)),
                ToolOutcome.class);

        assertThat(restored.settled(proposed)).isEqualTo(narrowed);
    }

    @Test
    void aPendingApprovalKeepsItsArgumentsAndTicketOnTheWire() throws Exception {
        dev.agentkit.core.reliability.PendingApproval parked =
                new dev.agentkit.core.reliability.PendingApproval(
                        new dev.agentkit.core.tool.ToolInvocation("t2", "publish",
                                Map.of("text", "just the summary")),
                        dev.agentkit.core.reliability.ApprovalNeeded.because("ask"), "park-1");

        dev.agentkit.core.reliability.PendingApproval back = mapper.readValue(
                mapper.writeValueAsString(parked),
                dev.agentkit.core.reliability.PendingApproval.class);

        assertThat(back.ticket()).isEqualTo("park-1");
        assertThat(back.invocation().stringArgument("text")).isEqualTo("just the summary");
    }
}
