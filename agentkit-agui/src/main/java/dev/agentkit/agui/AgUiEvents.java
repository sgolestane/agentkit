package dev.agentkit.agui;

import dev.agentkit.chat.ChatEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * This runtime's event stream, said in AG-UI's words.
 *
 * <h2>A translation, and the two places it is not one</h2>
 *
 * <p>AG-UI is an event protocol between an agent backend and a frontend built for it —
 * CopilotKit, assistant-ui — and most of what it carries, {@link ChatEvent} already carries:
 * a run starting, text arriving in fragments, a tool being called and settling, the run
 * ending. Those map across cleanly and this class is a lookup table for them.
 *
 * <p>Two things do not map, and pretending they do would be the interesting failure:
 *
 * <ul>
 *   <li><strong>An approval.</strong> AG-UI's own answer to "the agent needs a person" is that
 *       the frontend declares a tool and the backend calls it; there is no
 *       {@code APPROVAL_REQUESTED}. So it goes across as {@link #CUSTOM} named
 *       {@code agentkit.approval}, with everything a card needs inside it. A frontend that
 *       does not know the name ignores it — which is the honest outcome, because a frontend
 *       that does not know about approvals cannot draw one, and inventing a
 *       {@code TOOL_CALL_START} for a decision nobody can answer would put a spinner on the
 *       screen forever.</li>
 *   <li><strong>A view.</strong> Same shape, and the same reason. {@code agentkit.view} carries
 *       the kind and the data. AG-UI's nearest neighbour is state synchronisation, and a view
 *       is not state — it is a thing produced once at a moment in the transcript, and putting
 *       it in a {@code STATE_SNAPSHOT} would make every later snapshot either repeat it or
 *       appear to delete it.</li>
 * </ul>
 *
 * <h2>Message ids, which the protocol needs and this runtime does not have</h2>
 *
 * <p>AG-UI frames assistant text as a message with an id: {@code TEXT_MESSAGE_START} with a
 * {@code messageId}, then {@code TEXT_MESSAGE_CONTENT} deltas carrying the same id, then
 * {@code TEXT_MESSAGE_END}. This runtime has no message id — a turn's answer is the turn's
 * answer. So the turn id is the message id, and {@link Stream} opens and closes the message
 * around the deltas, because a {@code TEXT_MESSAGE_CONTENT} whose {@code START} never arrived
 * is a client-side error rather than a rendering choice.
 *
 * <p>That bookkeeping is why translating is a {@link Stream} and not a static function of one
 * event: the shape of the protocol is stateful even though ours is not.
 */
public final class AgUiEvents {

    /** The wire values, which are SCREAMING_SNAKE_CASE where the docs' prose is PascalCase. */
    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String RUN_FINISHED = "RUN_FINISHED";
    public static final String RUN_ERROR = "RUN_ERROR";
    public static final String TEXT_MESSAGE_START = "TEXT_MESSAGE_START";
    public static final String TEXT_MESSAGE_CONTENT = "TEXT_MESSAGE_CONTENT";
    public static final String TEXT_MESSAGE_END = "TEXT_MESSAGE_END";
    public static final String TOOL_CALL_START = "TOOL_CALL_START";
    public static final String TOOL_CALL_ARGS = "TOOL_CALL_ARGS";
    public static final String TOOL_CALL_END = "TOOL_CALL_END";
    public static final String TOOL_CALL_RESULT = "TOOL_CALL_RESULT";
    public static final String STEP_STARTED = "STEP_STARTED";
    public static final String STEP_FINISHED = "STEP_FINISHED";
    public static final String CUSTOM = "CUSTOM";

    /** What a {@link #CUSTOM} event is called when it carries something AG-UI has no word for. */
    public static final String APPROVAL = "agentkit.approval";
    public static final String APPROVAL_DECIDED = "agentkit.approval.decided";
    public static final String VIEW = "agentkit.view";

    private AgUiEvents() {
    }

    /**
     * One conversation's worth of translation.
     *
     * <p>Stateful on purpose — see the class note on message ids. Not thread-safe, and it does
     * not need to be: this runtime works one turn of a conversation at a time.
     */
    public static final class Stream {

        private final String threadId;
        private String openMessage;
        private String openToolCall;
        private String openStep;

        public Stream(String threadId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId");
        }

        /**
         * What AG-UI should be told about this event — nothing, one event, or several.
         *
         * <p>Several, because opening and closing a text message is bookkeeping the protocol
         * requires and the source event does not carry. A list rather than an event and a
         * flag, so a caller cannot forget the second one.
         */
        public List<Map<String, Object>> translate(ChatEvent event) {
            Objects.requireNonNull(event, "event");
            List<Map<String, Object>> out = new ArrayList<>();
            switch (event.type()) {
                case TURN_STARTED -> out.add(base(RUN_STARTED, event, Map.of(
                        "threadId", threadId, "runId", event.turnId())));
                // Not a run event: the run started when the turn did. A queued turn being
                // picked up is a step, which is what STEP_STARTED is for — and a step that is
                // started must be finished, which is why openStep exists. See closeStep.
                case TURN_RUNNING -> {
                    if (openStep == null) {
                        openStep = "running";
                        out.add(base(STEP_STARTED, event, Map.of("stepName", openStep)));
                    }
                }
                case TEXT_DELTA -> {
                    String text = string(event, "text");
                    if (text.isEmpty()) {
                        break;
                    }
                    if (openMessage == null) {
                        openMessage = messageIdFor(event);
                        out.add(base(TEXT_MESSAGE_START, event, Map.of(
                                "messageId", openMessage, "role", "assistant")));
                    }
                    out.add(base(TEXT_MESSAGE_CONTENT, event, Map.of(
                            "messageId", openMessage, "delta", text)));
                }
                case TOOL_STARTED -> {
                    // The id AG-UI wants is per call and this runtime does not mint one until
                    // the call settles, so the turn and the tool name make one. Two calls of
                    // the same tool in a turn would share it — which is a real limitation and
                    // is stated on toolCallId rather than hidden.
                    openToolCall = event.turnId() + ":" + string(event, "tool");
                    out.add(base(TOOL_CALL_START, event, Map.of(
                            "toolCallId", openToolCall,
                            "toolCallName", string(event, "tool"))));
                    out.add(base(TOOL_CALL_ARGS, event, Map.of(
                            "toolCallId", openToolCall,
                            "delta", json(event.data().get("arguments")))));
                }
                case TOOL_FINISHED -> {
                    String id = openToolCall == null
                            ? event.turnId() + ":" + string(event, "tool") : openToolCall;
                    openToolCall = null;
                    out.add(base(TOOL_CALL_END, event, Map.of("toolCallId", id)));
                    // The DIGEST, which is what the model was handed — not the view. A
                    // frontend showing a tool result should show what the model read, and the
                    // view goes across separately as what a person looks at.
                    out.add(base(TOOL_CALL_RESULT, event, Map.of(
                            "messageId", messageIdFor(event) + ":" + id,
                            "toolCallId", id,
                            "content", string(event, "digest"))));
                }
                // A matched pair, back to back. This runtime learns of a model call when it
                // COMES BACK — the event carries the stop reason and what it cost — so there
                // is no earlier moment at which a start could honestly be sent. A lone
                // STEP_FINISHED is not the alternative: a conforming client rejects a step it
                // never saw start, and rejecting means it stops reading the stream, so the
                // answer that arrives after this never reaches the screen. Measured against
                // @ag-ui/client's own verifyEvents, which is how this was found.
                case MODEL_CALL -> {
                    out.add(base(STEP_STARTED, event, Map.of("stepName", "model")));
                    out.add(base(STEP_FINISHED, event, Map.of("stepName", "model")));
                }
                case VIEW -> out.add(custom(VIEW, event, event.data()));
                case APPROVAL_REQUESTED -> out.add(custom(APPROVAL, event, event.data()));
                case APPROVAL_DECIDED -> out.add(custom(APPROVAL_DECIDED, event, event.data()));
                case TURN_FINISHED -> {
                    closeMessage(event, out);
                    closeStep(event, out);
                    Map<String, Object> finished = new LinkedHashMap<>();
                    finished.put("threadId", threadId);
                    finished.put("runId", event.turnId());
                    // AG-UI's RUN_FINISHED has no required outcome, and a turn that was
                    // cancelled or is waiting on a person has finished as far as the protocol
                    // is concerned — the reason is ours and travels in `result`.
                    finished.put("result", Map.of(
                            "state", string(event, "state"),
                            "answer", string(event, "answer"),
                            "detail", string(event, "detail")));
                    out.add(base(RUN_FINISHED, event, finished));
                }
                case ERROR -> {
                    closeMessage(event, out);
                    closeStep(event, out);
                    out.add(base(RUN_ERROR, event, Map.of(
                            "message", string(event, "message"))));
                }
                default -> {
                    // A type this translation has not been taught. Dropped rather than
                    // guessed: an event a frontend cannot interpret is noise, and an event
                    // this mapped to the wrong AG-UI type is a lie about what happened.
                }
            }
            return out;
        }

        /**
         * Ends the assistant message if one is open.
         *
         * <p>A turn can end without a delta ever arriving — a refusal, an unconfigured model, a
         * run that only called tools — and in that case there is no message to close. Closing
         * one that was never opened would be the mirror of the error this exists to prevent.
         */
        private void closeMessage(ChatEvent event, List<Map<String, Object>> out) {
            if (openMessage != null) {
                out.add(base(TEXT_MESSAGE_END, event, Map.of("messageId", openMessage)));
                openMessage = null;
            }
        }

        /**
         * Ends the run's step if one is open.
         *
         * <p>A conforming client refuses a {@code RUN_FINISHED} while a step is still active
         * — measured, not assumed — and refusing means it stops reading, so the run never
         * completes on the screen even though it completed here. The turn ending is what ends
         * the step: there is no separate "stopped running" event, because there is nothing
         * after it.
         *
         * <p>Guarded on {@code openStep} for the turn that never ran: a run refused before it
         * was picked up goes straight from started to finished, and closing a step nobody
         * opened is the mirror of the fault this exists to fix.
         */
        private void closeStep(ChatEvent event, List<Map<String, Object>> out) {
            if (openStep != null) {
                out.add(base(STEP_FINISHED, event, Map.of("stepName", openStep)));
                openStep = null;
            }
        }

        private String messageIdFor(ChatEvent event) {
            return event.turnId().isEmpty() ? threadId : event.turnId();
        }
    }

    // --- shaping ----------------------------------------------------------------------

    private static Map<String, Object> base(String type, ChatEvent event,
            Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        // Milliseconds since the epoch, which is what AG-UI's optional timestamp is.
        out.put("timestamp", event.at().toEpochMilli());
        out.putAll(fields);
        return out;
    }

    private static Map<String, Object> custom(String name, ChatEvent event,
            Map<String, Object> value) {
        return base(CUSTOM, event, Map.of("name", name, "value", value));
    }

    private static String string(ChatEvent event, String key) {
        Object value = event.data().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * A tool's arguments as the {@code delta} of {@code TOOL_CALL_ARGS}, which the protocol
     * defines as a fragment of the arguments <em>as JSON text</em>.
     *
     * <p>Sent whole rather than in fragments. AG-UI supports either, and this runtime does not
     * stream arguments — it learns them when the model has finished writing them — so
     * pretending otherwise would be one event per call carrying the same total with more
     * ceremony.
     */
    private static String json(Object arguments) {
        try {
            return MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            // A preview map that will not serialise. An empty object is a truthful "no
            // arguments to show" where a thrown exception would end somebody's stream.
            return "{}";
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
