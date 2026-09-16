package dev.agentkit.temporal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.message.ContentBlock;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;

/**
 * Builds the Temporal {@link DataConverter} the durable agent uses on both the
 * client and the worker.
 *
 * <p>It starts from Temporal's default JSON object mapper (records, {@code
 * Optional}, and java.time are already handled) and adds the one thing the
 * default cannot infer: polymorphic (de)serialization of the sealed {@code
 * ContentBlock} hierarchy, via {@link ContentBlockMixin}. Keeping this here means
 * the core message types carry no serialization annotations.
 *
 * <p><strong>Both ends must share this converter.</strong> Configure the
 * {@code WorkflowClient} with it (so inputs/results serialize) and the worker's
 * client too (so activities see the same payloads); a mismatch surfaces as a
 * deserialization error at the boundary.
 *
 * <h2>A payload may gain a field on a rolling deploy. It may not gain a type (#284)</h2>
 *
 * <p>{@link #objectMapper()} disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, and the comment
 * there says why: an old worker handed a newer payload ignores the component it does not
 * know and carries on. Read on its own that is a general reassurance about rolling
 * deploys, and it is not one. <strong>It is about unknown <em>properties</em>.</strong>
 *
 * <p>An unknown <em>subtype id</em> — a {@code "@type"} in {@link ContentBlockMixin} that
 * this reader has never heard of — is resolved before any property is bound, so nothing
 * tolerates it. The read throws, and on this path a failed read fails the workflow
 * <em>task</em>, which Temporal retries indefinitely: the run neither finishes nor errors.
 * It is the same stall the setting above exists to avoid, through a door the setting does
 * not reach.
 *
 * <p>So adding a {@code ContentBlock} subtype is not a rolling-deploy-safe change the way
 * adding a component is, and the ordering rule is stricter and the operator's rather than
 * the client's: every worker onto the new code before any run can produce the new block, or
 * a drained fleet. A mixed fleet is the window. {@code ContentBlockMixin} carries why the
 * one such type shipped so far is a type rather than a component; the README's durable
 * section carries the rule for an operator. {@code DurableJsonTest} pins both halves — the
 * tolerated field and the rejected discriminator — against a discriminator no release has
 * used, so it measures the general fact rather than that one type.
 */
public final class DurableJson {

    private DurableJson() {
    }

    /** The object mapper used by the durable converter, with ContentBlock wired up. */
    public static ObjectMapper objectMapper() {
        ObjectMapper mapper = JacksonJsonPayloadConverter.newDefaultObjectMapper();
        mapper.addMixIn(ContentBlock.class, ContentBlockMixin.class);
        // Tolerate unknown fields so payload types can gain components additively. A
        // durable run outlives the code that started it in both directions: an OLD
        // worker can be handed a NEW payload during a rolling deploy, and failing there
        // fails the workflow *task*, which Temporal retries indefinitely — stalling the
        // run rather than failing it. Ignoring the unknown field lets that worker carry
        // on with the semantics it knows. (The reverse — new code reading an old
        // payload — is handled by keeping added components nullable.)
        //
        // Fields only. An unknown @type is resolved before any field is bound and this
        // setting never sees it, so a new ContentBlock subtype stalls an old reader
        // whatever this says — see the class javadoc (#284).
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /** A data converter that can round-trip every type crossing the agent boundary. */
    public static DataConverter dataConverter() {
        return DefaultDataConverter.newDefaultInstance()
                .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(objectMapper()));
    }
}
