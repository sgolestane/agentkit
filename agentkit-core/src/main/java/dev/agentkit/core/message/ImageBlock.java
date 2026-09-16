package dev.agentkit.core.message;

import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * An image the model can actually see.
 *
 * <p>An operator's commonest attachment is a screenshot. Before this, one reached the model as
 * <em>"there is a file called shot.png (image/png, 240 kB)"</em> — the file tools decode as
 * text and refuse anything that is not — so the agent answered about a filename while a person
 * believed it was answering about a picture. That gap is worse than no attachments at all,
 * because nothing about the answer says which of the two happened.
 *
 * <h2>Base64, not bytes</h2>
 *
 * <p>The wire form every vision API takes, and the form a record can hold honestly: a
 * {@code byte[]} component gives a record identity-based {@code equals} and a
 * {@code hashCode} that changes per instance, which for a value type that travels through a
 * durable history and gets compared in tests is a trap rather than an optimisation.
 *
 * <h2>Bounded, because a context window is not a disk</h2>
 *
 * <p>A 20 MB photograph is not a thing to put in a context window, and the digest rule this
 * repository applies to every other tool result does not stop applying because the bytes are
 * pixels. {@link #MAX_BYTES} is the decoded ceiling; over it, {@link #of} refuses rather than
 * truncating, because half an image is not a smaller image.
 *
 * <h2>Adding this type was a deployment change</h2>
 *
 * <p>See {@code ContentBlockMixin} in {@code agentkit-temporal}. A new {@code @type} on the
 * durable path is resolved by Jackson <em>before</em> any property is bound, so tolerating
 * unknown fields does not save an older worker: the read throws, the workflow task fails, and
 * Temporal retries it forever. Deploying this means every worker first, or a drained fleet —
 * it is not an additive change, and the rule is stated there and in the README rather than
 * discovered.
 *
 * @param mediaType the IANA type, e.g. {@code image/png}; one of {@link #SUPPORTED}
 * @param base64    the image, base64-encoded without line breaks
 */
public record ImageBlock(String mediaType, String base64) implements ContentBlock {

    /**
     * What the vision APIs this repository speaks to actually accept.
     *
     * <p>An allow-list rather than "anything starting with {@code image/}", for the reason
     * every allow-list here exists: the media type comes from whoever uploaded the file, and a
     * type a provider rejects is a request that fails at the far end with a message about
     * somebody else's API rather than a refusal here with a sentence about this one.
     */
    public static final Set<String> SUPPORTED =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    /**
     * The largest image that goes into a context window, decoded.
     *
     * <p>Five megabytes, which is Anthropic's own documented per-image ceiling and comfortably
     * under every other provider's. A bound this module chooses rather than inherits, so a
     * request that would be refused at the far end is refused here, where the message can say
     * what to do about it.
     */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public ImageBlock {
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(base64, "base64");
    }

    /**
     * An image block, or a refusal saying which rule it broke.
     *
     * @throws IllegalArgumentException if the type is not one a provider will take, or the
     *     image is larger than {@link #MAX_BYTES}
     */
    public static ImageBlock of(String mediaType, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        String type = mediaType == null ? "" : mediaType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SUPPORTED.contains(type)) {
            throw new IllegalArgumentException("A model can be shown " + SUPPORTED
                    + ", and this is " + (type.isEmpty() ? "untyped" : quoted(type)) + ".");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("That image is " + bytes.length
                    + " bytes and the ceiling is " + MAX_BYTES
                    + ". Half an image is not a smaller image, so it is refused rather than"
                    + " cut — resize it, or hand it over as a file the agent can download.");
        }
        return new ImageBlock(type, Base64.getEncoder().encodeToString(bytes));
    }

    /** Whether this media type is one a model can be shown, without building anything. */
    public static boolean canBeSeen(String mediaType) {
        return mediaType != null
                && SUPPORTED.contains(mediaType.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Roughly what this costs to send, in bytes on the wire.
     *
     * <p>For a caller deciding whether to attach it at all. Base64 is four characters per
     * three bytes, so this is larger than the image — which is the number that matters, since
     * it is what travels.
     */
    public int wireBytes() {
        return base64.length();
    }

    private static String quoted(String value) {
        return "'" + value + "'";
    }
}
