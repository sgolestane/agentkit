package dev.agentkit.chat;

/**
 * A stated limitation, not a fault.
 *
 * <p>A console with no model configured, or no connector, should boot and say exactly what is
 * missing — not refuse to start, and not report a 500. That is the degradation rule the rest
 * of this repository already follows: the Workbench example's {@code WebServer} has the same type under
 * another name, and its whole value is that the sentence a person reads and the sentence the
 * endpoint returns are the same sentence.
 *
 * <p>So the message is written for a person and names the fix:
 *
 * <pre>{@code
 * throw new ChatUnavailable("No model is configured. Set WORKBENCH_LLM=anthropic (plus "
 *         + "ANTHROPIC_API_KEY) or WORKBENCH_LLM=openrouter (plus OPENROUTER_API_KEY and "
 *         + "WORKBENCH_MODEL), then restart.");
 * }</pre>
 *
 * <p>{@code ChatServer} renders it as a 409 with that text. Anything else is a 500 with a
 * log line, which is the distinction: this is the console telling you something true about
 * its configuration, not the console failing.
 */
public class ChatUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChatUnavailable(String message) {
        super(message);
    }

    public ChatUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
