package dev.agentkit.core.tool;

/**
 * How far a proposed tool call actually got.
 *
 * <p>The companion question to {@link Provenance} and {@link SideEffects}, and the one an
 * audit trail is opened to answer: <em>did this action happen?</em> {@code Provenance}
 * declares who wrote what came back and {@code SideEffects} declares what a tool would
 * change if it ran — neither says whether it ran.
 *
 * <h2>The measured gap (#181)</h2>
 *
 * <p>Before this existed the only signal a caller had was {@code ToolResult.isError()}, and
 * every path that does not end in a successful tool sets it. Measured through
 * {@code AgentObserver.onToolResult}, printing every field the callback carries, for the
 * seven ways a proposed call can end:
 *
 * <pre>
 * 1 unknown tool          isError=true  proposed==effective  provenance=FIRST_PARTY
 * 2 gate denied           isError=true  proposed==effective  provenance=FIRST_PARTY
 * 3 gate parked           isError=true  proposed==effective  provenance=FIRST_PARTY
 * 4 sibling parked        isError=true  proposed==effective  provenance=FIRST_PARTY
 * 5 gate threw            isError=true  proposed==effective  provenance=UNKNOWN
 * 6 tool returned error   isError=true  proposed==effective  provenance=UNKNOWN
 * 7 tool threw            isError=true  proposed==effective  provenance=UNKNOWN
 * </pre>
 *
 * <p>Seven states, one bit. The first five never reached a tool; the last two did and may
 * have landed a side effect. {@code agentkit-examples-itops}'s {@code AuditObserver} wrote
 * {@code verdict = result.isError() ? "ERROR" : "OK"} from that bit, so a parked call and a
 * tool that blew up mid-write were the same row.
 *
 * <p><strong>Provenance is not a proxy for it</strong>, which the table above is printed in
 * full to show. It does split the seven — but along the wrong line: it puts a gate that
 * threw (nothing ran) in the same class as a tool that ran and threw, and a permanent denial
 * in the same class as a call waiting on a person. A caller deriving "did it run" from
 * {@code UNKNOWN} would be right about five of seven and wrong about the two that matter
 * most. Neither is {@code content} a proxy: it is a gate author's {@code reason()} and a
 * tool author's message, both written by the deployment, and no framework contract fixes
 * either string.
 *
 * <h2>Stamped by the runner, never by a tool</h2>
 *
 * <p>Which is why this is <em>not</em> a component of {@link ToolResult}, the shape #181
 * proposed and thought "probably more correct". Measured on the parent commit: the
 * repository constructs a {@code ToolResult} at 281 sites — 128 in main sources, 153 in
 * tests — and eleven of them are a runner on a path where the answer is not "it ran" (five
 * in {@code Agent}, six in {@code ToolActivitiesImpl}). The other 270 belong to callers that
 * cannot answer the question: from inside {@code Tool.execute} the answer is always "it
 * ran". So the component would take its value from a default at 96% of its call sites, and a
 * default that silently reads {@code RAN} is exactly the wrong direction for an audit field.
 * Worse, {@code ToolResult} is decomposed into a {@code ToolResultBlock} on the way to the
 * model at two of the runners, so the component would be dropped at the transcript boundary
 * or added to a wire format that has no business carrying it.
 *
 * <p>And it would not have saved the durable field either. {@code ToolOutcome} repeats
 * {@code ToolResult}'s components rather than nesting one — its javadoc gives the
 * rolling-deploy reason — so a {@code ToolResult} component still needs a {@code
 * ToolOutcome} component to survive Temporal history. Folding into {@code ToolResult} costs
 * both, plus 273 sites that must not care. This enum, carried explicitly by each runner,
 * costs the two places that know the answer.
 *
 * <p>#179's precedent points the same way and it is worth saying plainly, because it reads
 * at first like an argument for the other shape: when the durable path needed to record
 * <em>which call ran</em>, the field went onto {@code ToolOutcome} — the audit record —
 * and explicitly not onto {@code ToolResult}. This is the same question one step further on.
 *
 * <h2>Reading it</h2>
 *
 * <p>{@link #reachedTool()} is the one bit almost every consumer wants; the constants are
 * for a trail that has to say <em>why</em> not. A consumer must not assume the set is
 * closed — a fifth runner may add a way to not run — so prefer {@code reachedTool()} and a
 * {@code default} branch over an exhaustive switch.
 */
public enum Disposition {

    /**
     * The tool was entered and returned, whatever it made of the call.
     *
     * <p>Includes a tool that returned {@code ToolResult.error(...)}: it ran, it decided,
     * and it may have changed something before deciding. "Ran and failed" is the state
     * {@code isError()} was being asked to distinguish from the six below and cannot.
     */
    RAN,

    /**
     * The tool was entered and threw.
     *
     * <p>Kept apart from {@link #RAN} rather than folded into it because the two differ in
     * what a reviewer can conclude: a tool that returned an error chose to report one, and
     * a tool that threw part-way may have completed half its work and said nothing about
     * which half. It is the single most important row in an incident review and the one
     * state where the framework's own error text — {@code "Tool 'x' failed."} — was
     * previously the only clue, in a field no contract holds.
     */
    THREW,

    /**
     * No tool of that name was registered, so nothing was gated and nothing ran.
     *
     * <p>Distinct from {@link #REFUSED}: a policy did not decide anything here. The name
     * was wrong, which is a wiring or a model problem rather than a governance event, and
     * an audit trail that reports it as a denial invents a decision nobody made.
     */
    UNKNOWN_TOOL,

    /**
     * A gate refused the call outright. Nothing ran, and nothing is outstanding.
     *
     * <p>The terminal refusal, as against {@link #PARKED}: no later approval turns this into
     * a run.
     */
    REFUSED,

    /**
     * A gate stopped the call pending somebody's decision. Nothing ran <em>yet</em>.
     *
     * <p>The call that is waiting is the one {@code onToolResult}'s {@code effective}
     * argument and {@code ToolOutcome.settled} report — a gate may narrow the arguments and
     * then park, and what a reviewer is shown has to be what runs on approval (#104).
     */
    PARKED,

    /**
     * The call was never offered to a gate at all, so nothing decided anything about it.
     *
     * <p>Several shapes reach it and they are one state to an auditor, because the auditable
     * facts are identical — no policy saw it, no tool saw it, and no decision exists to
     * point at: a sibling call in the same turn parked, so the run is ending and this one is
     * not started on the way out; the model's turn reused a tool-call id, so no call in it
     * ran; or, on the durable path, an approval came back answering a different call, which
     * is refused before the gate is consulted.
     *
     * <p>Kept apart from {@link #REFUSED} on exactly that line. A refusal is a governance
     * event with an author and a reason a reviewer can be shown; this is the framework
     * declining to start something, and recording it as a denial would put a decision in the
     * trail that nobody made.
     */
    NOT_ATTEMPTED,

    /**
     * The gate itself threw, so nothing was decided and nothing ran.
     *
     * <p>The state most easily mistaken for {@link #THREW}, and the reason both exist: both
     * end in the framework's {@code "Tool 'x' failed."} result with the thrower's message
     * fenced inside it, so from the outside they were the same row. One is a tool that may
     * have acted; the other is a deployment's policy code that is broken, which is an
     * operational alert rather than an incident. Both runners already track the difference
     * internally to decide which invocation to report and both discarded it.
     */
    GATE_FAILED;

    /**
     * Whether a tool was entered — so whether a side effect may have landed.
     *
     * <p>True for {@link #RAN} and {@link #THREW} and false for everything else. The
     * question an audit trail is actually asked, and the reason it is a method here rather
     * than each caller's own set of constants: five states answer "no" and a caller
     * enumerating them is a caller that will miss the sixth.
     */
    public boolean reachedTool() {
        return this == RAN || this == THREW;
    }
}
