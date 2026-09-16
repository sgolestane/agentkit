package dev.agentkit.chat;

import java.time.Instant;
import java.util.Objects;

/**
 * A file a person handed the agent.
 *
 * <p><strong>The bytes are not here, and that is the design rather than an omission.</strong>
 * What crosses to the model is a digest — a header, a shape, a handful of rows, the matching
 * lines — never the file. An earlier prototype console states that rule in as many words
 * and it is the one thing about uploads that is not negotiable: a four-thousand-row CSV is a thing to query, not a thing to paste into a
 * context window. So an attachment is a handle. Tools address it by {@link #id()} and ask the
 * store for what they need.
 *
 * <p><strong>It is also somebody else's content.</strong> An uploaded log is exactly as much
 * a stranger's text as a ticket description, so anything a tool reads out of one travels
 * fenced through {@link dev.agentkit.core.prompt.Spotlight} and is declared
 * {@link dev.agentkit.core.tool.Provenance#THIRD_PARTY}. The uploader is usually the operator
 * themselves, which is exactly why this is easy to get wrong: the file they are forwarding
 * was written by whoever filed the ticket.
 *
 * @param id             minted by the store; what a tool names to reach the bytes
 * @param tenantId       who this belongs to
 * @param conversationId the conversation it was handed to
 * @param name           what the person called it; their text, never a path
 * @param mediaType      what it claims to be
 * @param bytes          how big it is
 * @param uploadedAt     when it arrived
 */
public record Attachment(String id, String tenantId, String conversationId, String name,
                         String mediaType, long bytes, Instant uploadedAt) {

    /** How much of a filename is kept. A name longer than this is not a name. */
    public static final int MAX_NAME_CHARS = 255;

    public Attachment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationId, "conversationId");
        name = fileNameOf(name);
        mediaType = mediaType == null || mediaType.isBlank()
                ? "application/octet-stream" : mediaType;
        Objects.requireNonNull(uploadedAt, "uploadedAt");
    }

    /**
     * The last component of whatever the uploader called this, bounded.
     *
     * <p>A browser sends the name the user chose, and a caller may send anything at all.
     * Nothing here builds a path out of it — {@link #id()} is what the store uses, and it is
     * minted — but the name is displayed, logged and echoed to the model, and
     * {@code ../../etc/passwd} displayed as a filename is a claim about where a file is that
     * is not true. Separators are dropped rather than escaped, so what is shown is the part
     * that was ever a name.
     */
    private static String fileNameOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "attachment";
        }
        String last = raw;
        for (String separator : new String[] {"/", "\\"}) {
            int at = last.lastIndexOf(separator);
            if (at >= 0) {
                last = last.substring(at + separator.length());
            }
        }
        last = last.strip();
        // "." and ".." survive the split above and are not names.
        if (last.isEmpty() || last.equals(".") || last.equals("..")) {
            return "attachment";
        }
        return dev.agentkit.core.util.Cut.to(last, MAX_NAME_CHARS);
    }
}
