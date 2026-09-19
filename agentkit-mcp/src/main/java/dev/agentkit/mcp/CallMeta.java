package dev.agentkit.mcp;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code _meta} of a {@code tools/call}: what travels with one call beside its arguments, such as a signed statement
 * of who is making it ({@code dev.agentkit/caller}).
 *
 * <p>On the client, {@link #sending} attaches {@code _meta} to the calls a piece of code makes — a tool call made
 * through an {@link McpTool} inside it carries the meta to the server. On the server, {@link #received} is the
 * {@code _meta} of the call being served, for the tool handling it. Both are per thread, for as long as the call runs.
 */
public final class CallMeta {

    private static final ThreadLocal<Map<String, Object>> SENDING = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> RECEIVED = new ThreadLocal<>();

    private CallMeta() {
    }

    /** Runs {@code work} with {@code meta} attached to every {@code tools/call} it makes through an {@link McpTool}. */
    public static <T> T sending(Map<String, Object> meta, Supplier<T> work) {
        Map<String, Object> before = SENDING.get();
        SENDING.set(Map.copyOf(meta));
        try {
            return work.get();
        } finally {
            if (before == null) {
                SENDING.remove();
            } else {
                SENDING.set(before);
            }
        }
    }

    /** What calls made on this thread now carry; empty when nothing is attached. */
    public static Map<String, Object> toSend() {
        Map<String, Object> meta = SENDING.get();
        return meta == null ? Map.of() : meta;
    }

    /** The {@code _meta} of the {@code tools/call} being served on this thread; empty when none came, or none is. */
    public static Map<String, Object> received() {
        Map<String, Object> meta = RECEIVED.get();
        return meta == null ? Map.of() : meta;
    }

    /** Serves a call whose {@code _meta} is {@code meta}: for a server, or a connection that runs tools in process. */
    public static <T> T receiving(Map<String, Object> meta, Supplier<T> work) {
        Map<String, Object> before = RECEIVED.get();
        RECEIVED.set(meta == null ? Map.of() : Map.copyOf(meta));
        try {
            return work.get();
        } finally {
            if (before == null) {
                RECEIVED.remove();
            } else {
                RECEIVED.set(before);
            }
        }
    }
}
