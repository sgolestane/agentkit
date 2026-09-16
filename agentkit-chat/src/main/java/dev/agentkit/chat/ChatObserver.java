package dev.agentkit.chat;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.View;
import dev.agentkit.core.util.Cut;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place a run becomes something a person can watch.
 *
 * <p>It writes to two things and they answer different questions. The {@link ChatStore} keeps
 * the {@link Step}s, which is what "what happened in this turn" means after the fact and
 * survives a restart. {@link ChatEvents} carries the same facts live, to whoever is watching
 * right now. Both from one callback, so a console and a transcript cannot disagree about a
 * run — which they did in the consoles this replaces, where the live feed and the stored
 * summary were written by different code.
 *
 * <h2>Whose run it is</h2>
 *
 * <p>Every callback carries an {@link AgentRun}, and it is passed through rather than
 * flattened. A supervisor and its subagents publish to the same conversation and their steps
 * interleave — delegation is synchronous, so a child's calls arrive before the
 * {@code delegate} that caused them — and a console that could not tell them apart would
 * present a subagent's work as the supervisor's own.
 *
 * <h2>Text deltas are not stored</h2>
 *
 * <p>{@link #onTextDelta} publishes and does not write a step. A thousand fragments of one
 * answer are a thousand rows that say nothing the finished answer does not, and on the file
 * store each one would rewrite the conversation. The answer is stored once, when the turn
 * ends. The deltas exist to be watched, not to be kept.
 *
 * <h2>What happens if this throws</h2>
 *
 * <p>Nothing, to the run: the framework guards every observer callback and absorbs a throw.
 * That is a safety net rather than a design, though, and an observer that relied on it would
 * silently stop recording. So the two things this calls are both total —
 * {@link ChatEvents#publish} never throws by construction, and a store write that fails
 * fails the console's own thread rather than the agent's. What this class must not do is
 * decide anything: it is observability, and a control that must be able to say no is a
 * {@code ToolGate}.
 */
public final class ChatObserver implements AgentObserver {

    /** How much of a tool's arguments or a model's text a step and an event carry. */
    private static final int MAX_PREVIEW_CHARS = 2_000;

    /**
     * How much of a tool's digest the trace keeps.
     *
     * <p>Larger than a preview because this is the thing an operator opens when the answer is
     * wrong, and a digest cut to two thousand characters usually loses the row that explains
     * it. Smaller than {@code ToolResult}'s own third-party bound, because every step of every
     * turn of every conversation is written to a file on the demo's path.
     */
    private static final int MAX_DIGEST_CHARS = 8_000;

    private final ChatStore store;
    private final ChatEvents events;
    private final String tenantId;
    private final String conversationId;
    private final String turnId;

    /**
     * When the last thing in each run finished, so a step can say how long it took.
     *
     * <p>Per run rather than per observer: a supervisor's timer must not be reset by its
     * subagent's model call, or every one of the supervisor's own steps would be reported as
     * taking no time at all.
     */
    private final Map<String, Long> lastEventNanos = new ConcurrentHashMap<>();

    public ChatObserver(ChatStore store, ChatEvents events, String tenantId,
            String conversationId, String turnId) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.turnId = Objects.requireNonNull(turnId, "turnId");
    }

    @Override
    public void onStart(AgentRun run, dev.agentkit.core.agent.Goal goal) {
        lastEventNanos.put(run.id(), System.nanoTime());
    }

    @Override
    public void onTextDelta(AgentRun run, int step, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        events.publish(conversationId, turnId, ChatEvent.Type.TEXT_DELTA, run.id(), run.name(),
                Map.of("text", delta));
    }

    @Override
    public void onModelResponse(AgentRun run, int step, LlmResponse response) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("step", step);
        detail.put("stopReason", String.valueOf(response.stopReason()));
        detail.put("inputTokens", response.usage().inputTokens());
        detail.put("outputTokens", response.usage().outputTokens());
        // What the model said in this call, apart from any tool it asked for. A turn whose
        // answer is empty and whose model calls all say TOOL_USE is a run that never got to
        // speak, and that is a different problem from one that answered badly.
        detail.put("text", preview(response.message().text()));
        publish(run, ChatEvent.Type.MODEL_CALL,
                record(run, Step.Kind.MODEL_CALL, run.name(), detail, false));
    }

    @Override
    public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
        // Published and not stored. The proposal's own row would be a second line per tool
        // call saying what the settled row already says, and the settled row is the one that
        // is true — #131's whole point. Live, though, it is the difference between a console
        // that shows "reading the inbox…" while it happens and one that shows nothing for
        // eleven seconds and then a result.
        events.publish(conversationId, turnId, ChatEvent.Type.TOOL_STARTED, run.id(),
                run.name(), Map.of(
                        "tool", invocation.name(),
                        "arguments", arguments(invocation.arguments())));
    }

    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
            ToolInvocation effective, ToolResult result, Disposition disposition) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("step", step);
        // The call as the GATE settled it, not as the model proposed it. A narrowing gate is
        // a supported feature, and a trace showing the proposal is wrong about the only
        // question anybody asks it — see AgentObserver.onToolResult, where this cost a
        // reviewer the call that actually ran.
        detail.put("tool", effective.name());
        detail.put("arguments", arguments(effective.arguments()));
        detail.put("disposition", disposition.name());
        detail.put("ran", disposition.reachedTool());
        detail.put("resultChars", result.content().length());
        // The digest the model was actually handed, which is the thing you need when an answer
        // is wrong: the question is almost never "what did the tool do" and almost always "what
        // did the model read". Bounded, because a tool result has no length limit and this goes
        // into a file — a fuller copy is in the transcript's own store if a deployment keeps
        // one, and #343 deferred this bullet to here for exactly this reason.
        detail.put("digest", Cut.to(result.content(), MAX_DIGEST_CHARS));
        detail.put("provenance", result.provenance().name());
        // isError is not the same question as "did this happen": a refused call, a broken
        // gate and a tool that threw all produce an error result, which is why disposition
        // exists. Both are carried so a console does not have to choose.
        detail.put("isError", result.isError());
        publish(run, ChatEvent.Type.TOOL_FINISHED,
                record(run, Step.Kind.TOOL_CALL, effective.name(), detail, result.isError()));

        // The views the tool produced, onto the turn and onto the stream. This is the seam
        // #336 was built for: the model got result.content(), the person gets these.
        //
        // Onto the TRACE as well, which for a while it was not. Step.Kind.VIEW is documented
        // as "a tool result carried something for a person to look at" and nothing produced
        // one — so a reader filtering the trace on it saw an empty list forever, and the
        // turn's views could not be tied back to the tool that made them. The step carries
        // only the kind and the producing tool: the view's own data is on the turn, and
        // copying it here would double what a transcript holds for no reader's benefit.
        for (View view : result.views()) {
            store.show(tenantId, conversationId, turnId, view);
            events.publish(conversationId, turnId, ChatEvent.Type.VIEW, run.id(), run.name(),
                    Map.of("kind", view.kind(), "data", view.data()));
            record(run, Step.Kind.VIEW, effective.name(),
                    Map.of("kind", view.kind()), false);
        }
    }

    @Override
    public void onFinish(AgentRun run, AgentResult result) {
        for (PendingApproval pending : result.awaiting()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("tool", pending.toolName());
            detail.put("invocationId", pending.invocationId());
            detail.put("arguments", preview(pending.invocation().arguments()));
            detail.put("reason", pending.why().reason());
            detail.put("effect", pending.why().effect());
            detail.put("reversible", pending.why().reversible());
            detail.put("ticket", pending.ticket());
            publish(run, ChatEvent.Type.APPROVAL_REQUESTED,
                    record(run, Step.Kind.APPROVAL_REQUESTED, pending.toolName(), detail,
                            false));
        }
        lastEventNanos.remove(run.id());
    }

    /**
     * Writes the step and hands back what the matching event should carry.
     *
     * <p>Split from {@link #publish} rather than doing both, because the event type is the
     * caller's to choose and folding it in here meant a mapping from {@code Step.Kind} to
     * {@code ChatEvent.Type} — which was wrong the moment a kind had no event of its own, and
     * published a model call as a tool starting.
     *
     * @return the step's detail, plus how long it took, for the event to carry
     */
    private Map<String, Object> record(AgentRun run, Step.Kind kind, String name,
            Map<String, Object> detail, boolean failed) {
        long now = System.nanoTime();
        Long previous = lastEventNanos.put(run.id(), now);
        // Absent means this is the run's first recorded step and onStart never fired, which
        // happens to a run built without this observer attached from the beginning. Zero is
        // the honest answer: nothing was measured, rather than "it took since the epoch".
        long millis = previous == null ? 0 : (now - previous) / 1_000_000;
        store.addStep(tenantId, conversationId, turnId, kind, name, detail, millis, failed);
        Map<String, Object> published = new LinkedHashMap<>(detail);
        published.put("name", name);
        published.put("millis", millis);
        return published;
    }

    private void publish(AgentRun run, ChatEvent.Type type, Map<String, Object> data) {
        events.publish(conversationId, turnId, type, run.id(), run.name(), data);
    }

    /**
     * A bounded, printable rendering of something the model wrote.
     *
     * <p>Arguments are model-authored and have no length limit — a drafted comment runs to a
     * paragraph, and #151 is the case where one record took 37 seconds to emit at 200,000
     * characters. Bounded here rather than at the renderer, because this is also what goes
     * into the step, which goes into a file.
     */
    private static String preview(Object value) {
        return Cut.to(String.valueOf(value), MAX_PREVIEW_CHARS);
    }

    /**
     * A tool's arguments, kept as a map rather than flattened to a string.
     *
     * <p>They arrived as JSON and anything reading this event wants them as JSON. The first
     * version ran them through {@link #preview}, which is {@code String.valueOf} — so a call
     * with {@code limit: 5} went onto the stream as the seven characters {@code {limit=5}},
     * Java's {@code Map.toString}. That is not JSON, nothing can parse it, and the shape of
     * the values is gone: {@code 5} and {@code "5"} are the same seven characters.
     *
     * <p>It went unnoticed because the console's trace only ever showed the string to a
     * person, who can read {@code {limit=5}} perfectly well. It was found by writing an
     * adapter that had to hand the arguments to somebody else's client, which needs them as
     * {@code TOOL_CALL_ARGS.delta} — a fragment of the arguments as JSON text.
     *
     * <p>Bounded per value rather than over the whole thing, because the bound has to leave
     * valid JSON behind: cutting a rendered map in half leaves a string that is neither the
     * arguments nor parseable. A long string value is cut and says so; everything else is the
     * value the model actually sent.
     */
    private static Map<String, Object> arguments(Map<String, Object> raw) {
        Map<String, Object> bounded = new LinkedHashMap<>();
        raw.forEach((name, value) -> bounded.put(name,
                value instanceof String text ? Cut.to(text, MAX_PREVIEW_CHARS) : value));
        return bounded;
    }
}
