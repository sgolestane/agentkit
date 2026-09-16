package dev.agentkit.temporal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.View;
import java.util.Optional;

/**
 * What one tool activity produced: the result the model sees, and — when a gate stopped the
 * call pending somebody's decision — what is outstanding.
 *
 * <h2>Why it repeats {@code ToolResult}'s fields instead of holding one</h2>
 *
 * <p>This is the activity's return type, and an activity's return value is written into
 * Temporal history and read back on every replay. So the shape has to survive both
 * directions of a rolling deploy, and the components are laid out to make that automatic
 * rather than careful:
 *
 * <ul>
 *   <li><strong>New code, old payload.</strong> A run already in flight has {@code
 *       {"content":…,"isError":…,"provenance":…}} recorded from before this type existed.
 *       Those are exactly this record's first three components, so it deserializes into a
 *       {@code ToolOutcome} with {@code awaiting} null — which is the truth: nothing was
 *       parked, because nothing could be. This is the rule {@code DurableJson} states and
 *       {@code ToolResult.provenance} already follows; getting it wrong stalls a live run
 *       forever rather than failing it.</li>
 *   <li><strong>Old code, new payload.</strong> A worker still running the previous version
 *       reads this as a {@code ToolResult} and ignores {@code awaiting} — {@code
 *       DurableJson} disables {@code FAIL_ON_UNKNOWN_PROPERTIES} for precisely this. It
 *       then sees {@link #content()}, which for a parked call says the call was stopped
 *       pending a person, marked as an error. It cannot arrange the waiting, and it does
 *       not silently run the tool either.</li>
 * </ul>
 *
 * <p>Holding a nested {@code ToolResult} would have broken the first of those: an old
 * payload has no {@code result} object, so that component would arrive null. Wrapping is
 * the obvious design and the wire is why it is not the one. That much is reasoning rather
 * than measurement — what <em>is</em> measured is the layout that shipped, both ways, in
 * {@code DurableJsonTest}: a legacy {@code ToolResult} payload reads back here as a
 * completed call, and a parked outcome read as a {@code ToolResult} by older code comes
 * back marked an error that says why.
 *
 * <h2>{@code effective}, and why history needed it (#179)</h2>
 *
 * <p><strong>The durable path has no {@code AgentObserver}</strong> — {@code
 * AgentWorkflowImpl}'s javadoc says so — so Temporal history <em>is</em> the audit trail.
 * History records an activity's input and its output; the input is the call the model
 * proposed, and until this component the output said nothing about which call ran. A gate
 * that narrows arguments left a history in which every trace of the call was the proposal
 * it had overruled. Measured on the in-memory test server, with a gate that narrows:
 *
 * <pre>
 * history activity input  = {"id":"t1","name":"publish","arguments":{"text":"/etc/shadow"}}
 * history activity result = {"content":"published","isError":false,…}
 * tool actually ran       = [/tmp/harmless.txt]
 * </pre>
 *
 * <p>That is #131's defect on the third runner: an audit record must report the call the
 * gate <em>settled on</em>, not the call the model proposed. {@link #settled(ToolInvocation)}
 * is the one spelling of that rule here, and {@code AgentObserver.onToolResult} carries the
 * argument for why every path can keep it — including the ones where nothing ran.
 *
 * <p><strong>Nullable, and that is the schema decision.</strong> Following {@code awaiting}
 * and {@code lowersTrust}: a payload written before this component existed comes back with
 * it null, and the constructor coerces nothing, because refusing it would throw inside a
 * workflow task — which Temporal retries forever, stalling a live run rather than failing
 * it. {@code ApprovalNeeded}'s constructor spells out that consequence at length.
 *
 * <p><strong>Null on a park, deliberately.</strong> A parked outcome already carries the
 * gate's settled call, one field away, inside {@code awaiting.invocation()} — {@code
 * ToolActivitiesImpl} builds that {@code PendingApproval} from {@code effectiveFor} for
 * exactly the reason this component exists. Writing it twice would put a large argument map
 * into history a fifth time on the one path this class already calls out as costing double,
 * and #137 is the issue about that growth being the failure that stalls a run. So
 * {@link #settled(ToolInvocation)} reads the park's copy rather than a duplicate being
 * stored, and the fallback chain it walks is the same one an old payload needs.
 *
 * <p>The <em>proposal</em> is not stored here at all: Temporal already records it as the
 * activity's input, which no code in this repository writes and none can get wrong. That is
 * what keeps this change strictly additive.
 *
 * <p>A parked call's arguments are written into history four times — the assistant turn,
 * the {@code executeTool} input, this result, and the {@code resumeTool} input — where an
 * unparked one pays two. Nothing new is unbounded (the arguments already travel in the
 * assistant message), but a tool with a large argument map costs roughly double once
 * parked, against Temporal's blob limit.
 *
 * <p>{@code effective} adds a third write to an <em>executed</em> call, since that path had
 * only the two. Said rather than glossed over, because it is the same currency the paragraph
 * above spends: the price of an audit trail that names the call that ran is one more copy of
 * its arguments, on the calls that ran. The park keeps its four for the same reason it is
 * left null there.
 *
 * <h2>{@code disposition}, and why history needed that too (#181)</h2>
 *
 * <p>{@code effective} answers <em>which call</em> was settled on and says outright that it
 * answers nothing about whether it ran. This is the other half. Until it existed, a reader
 * of history had {@code isError} and nothing else, and it is true for a denial, a park, an
 * unknown tool, a gate that threw, a tool that returned an error and a tool that threw —
 * six states on one bit, with the two that may have landed a side effect on the same side
 * of it as the four that could not have. {@link Disposition} holds the measured table.
 *
 * <p><strong>Written on every path, including the park</strong>, where {@code effective} is
 * deliberately not. The two components are treated differently because they cost
 * differently: {@code effective} is a whole argument map, which is why the park reads its
 * copy out of {@code awaiting} rather than storing a fifth (#137). A disposition is an enum
 * name — a dozen bytes against a blob limit measured in megabytes — so there is nothing to
 * save by deriving it, and everything to lose: a derived value is a second spelling of the
 * rule, and this class already carries the one place the repository states what a park
 * means.
 *
 * <p><strong>Nullable all the same</strong>, on the rule the three components before it
 * follow: a payload written before it existed comes back null and the constructor coerces
 * nothing, because refusing it fails a workflow task, which Temporal retries forever. What
 * {@link #settledAs()} makes of that null is the interesting half — an old payload can
 * still prove a park, and cannot prove anything else.
 *
 * <h2>{@code brokeAnInvariant}, and why it is a component and not a {@link Disposition}
 * (#129)</h2>
 *
 * <p>{@code disposition} says how far the call got and {@code THREW} is one value of it,
 * covering everything a tool body throws. The workflow needs one distinction inside that:
 * whether what it threw was a {@code RuntimeException} — an ordinary failure the model can
 * route around — or something outside it, which means the worker's own invariant broke.
 * Measured, before this, on all three runners with one tool that records a side effect and
 * then throws an {@code AssertionError}:
 *
 * <pre>
 * in-process Agent : ran=1  the AssertionError escapes Agent.run — no AgentResult, no onFinish
 * in-process Goap  : ran=1  run ends, GoapStop.ACTION_THREW, trace kept, onFinish called
 * durable          : ran=1  run continues, stopReason=COMPLETED, output="done", errorMessage=""
 * </pre>
 *
 * <p>A durable run reporting {@code COMPLETED} for a turn in which the worker had a broken
 * invariant is the failure this repository keeps naming as the worse one: the model
 * answered around a defect nobody was told about, and the result says the objective was
 * met. So the fact travels back and {@code AgentWorkflowImpl} ends the run on it.
 *
 * <p><strong>Not a new {@code Disposition} constant.</strong> {@code Disposition} lives in
 * core and every in-process caller switches over it; a constant added there for a durable
 * distinction would have to be handled by runners that can never produce it. A component
 * here is additive on the wire, invisible to a worker that has not heard of it, and
 * confined to the reader that acts on it — the same argument {@code effective} and
 * {@code disposition} already make for living here.
 *
 * <p><strong>Nullable, coerced to false, like {@code lowersTrust}.</strong> An old payload
 * comes back with it absent, and "this result did not prove a broken invariant" is both the
 * safe reading and the true one — an old worker's {@code Error} was turned into an error
 * result and the run continued, which is what its history records. It is also what makes
 * the workflow's new branch replay-safe on its own; see {@code AgentWorkflowImpl}.
 */
public record ToolOutcome(String content, boolean isError, Provenance provenance,
                          PendingApproval awaiting, Boolean lowersTrust,
                          ToolInvocation effective, Disposition disposition,
                          Boolean brokeAnInvariant, java.util.List<View> views) {

    public ToolOutcome {
        // Coerced rather than required, for the reason ToolResult's own constructor gives:
        // Jackson deserializes a record through its canonical constructor and passes null
        // for anything the payload does not carry, so a requireNonNull here would refuse
        // every payload written before a component existed — and on this path that is a run
        // in flight, whose workflow re-reads its own activity results on every replay.
        content = content == null ? "" : content;
        provenance = provenance == null ? Provenance.UNKNOWN : provenance;
        // Boolean rather than boolean, and absent means no. A payload recorded before this
        // component existed comes back null, and a run in flight during that deploy has not
        // had a floor applied to it at all — so "this result did not lower anything" is both
        // the safe reading and the true one.
        lowersTrust = lowersTrust != null && lowersTrust;
        // effective is left exactly as it arrived, null included, and that is the point:
        // null means "this payload does not record which call the gate settled on", which
        // is true of every payload written before #179 and of a park, whose copy lives in
        // `awaiting`. Coercing it to the proposal would be inventing an audit fact — and
        // this constructor is not handed the proposal to invent it from. Reading it goes
        // through settled(ToolInvocation), which is where the fallback is decided once.
        //
        // disposition likewise: null means "this payload does not record how far the call
        // got", which is true of every payload written before #181 and of nothing since.
        // Coercing it to RAN would be the one wrong guess available — it is the value an
        // auditor acts on, and inventing "the action happened" is the direction that costs
        // an incident review its answer. settledAs() decides what an absent one is worth.
        //
        // brokeAnInvariant follows lowersTrust rather than those two, and the difference is
        // that absent has a true reading here: a payload written before #129 records a run
        // that was NOT ended by an Error, because the code that wrote it could not end one.
        // So false is the fact rather than a guess, and it is coerced here so the one
        // reader — AgentWorkflowImpl's version branch — cannot be handed a null it would
        // unbox. There is no settledAs()-style Optional for it for the same reason: absent
        // and false are the same state, which is not true of a disposition.
        brokeAnInvariant = brokeAnInvariant != null && brokeAnInvariant;
        // views follows lowersTrust and brokeAnInvariant rather than effective and
        // disposition, and for their reason: absent has a true reading. A payload written
        // before #336 records a call whose result carried no view, because nothing could
        // carry one. Empty is the fact, not a guess.
        // Null elements dropped rather than refused, for the reason ToolResult's own
        // constructor gives: copyOf throws on one, and a throw here fails the workflow task.
        views = views == null ? java.util.List.of()
                : views.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The shape this record had before {@code views}, for an outcome with nothing to show.
     *
     * <p>Kept for the reason {@code ToolResult} keeps its three-argument constructor: adding
     * a component to a record rewrites its canonical constructor, and every caller that
     * builds one positionally — which here is the durable tests, standing in for a worker —
     * would otherwise have to be edited to say "and no views" in a change that is not about
     * views. Jackson still binds the canonical one, so the wire is unaffected.
     */
    public ToolOutcome(String content, boolean isError, Provenance provenance,
            PendingApproval awaiting, Boolean lowersTrust, ToolInvocation effective,
            Disposition disposition, Boolean brokeAnInvariant) {
        this(content, isError, provenance, awaiting, lowersTrust, effective, disposition,
                brokeAnInvariant, java.util.List.of());
    }

    /**
     * Whether this result moved the run onto the tightened policy (#122).
     *
     * <p>Decided <strong>here</strong>, by the activity, rather than by the workflow reading
     * the provenance itself — because the {@code TrustFloor} that says which provenances
     * count lives on the worker beside the gates it chooses between, and a workflow that
     * made the same judgement from its own copy would be a second spelling of one policy.
     * That split is how a control comes to mean two things; the workflow carries the bit and
     * decides nothing.
     */
    @JsonIgnore
    public boolean lowered() {
        return Boolean.TRUE.equals(lowersTrust);
    }

    /**
     * A completed call whose settled invocation is not recorded.
     *
     * <p>For a caller with no gate in the picture — a test double standing in for the
     * activity, say. {@link #settled(ToolInvocation)} then falls back to the proposal, which
     * is the truth when nothing gated the call. A runner that <em>does</em> gate must use
     * {@link #of(ToolInvocation, ToolResult, boolean, Disposition, boolean)}, or history is
     * back to reporting a narrowing gate's proposal as its execution (#179).
     *
     * <p>It records no {@code disposition} either, and {@link #settledAs()} therefore reports
     * absent rather than {@code RAN} (#181). Same reason: this factory is not told, and a
     * double standing in for the activity is exactly the caller that should not be believed
     * about whether a tool ran.
     */
    public static ToolOutcome of(ToolResult result) {
        return new ToolOutcome(result.content(), result.isError(), result.provenance(), null,
                false, null, null, false, result.views());
    }

    /**
     * A completed call: which invocation the gate settled on, what it produced, whether
     * what it brought back lowers the run's trust floor, and whether the tool body threw
     * outside {@code RuntimeException} (#129).
     *
     * <p><strong>One factory rather than an arity per component.</strong> A four-argument
     * overload defaulting {@code brokeAnInvariant} to {@code false} was written first and
     * removed by the mutation pass: no production caller was left on it — {@code
     * ToolActivitiesImpl.outcomeOf} carries the default now — so flipping its default to
     * {@code true} survived the whole module. A default nothing in production reads is a
     * line no test can kill, and this repository's rule is that such a line is worse than
     * the argument it saves.
     *
     * @param settled the call as the gate settled it — what the tool was handed, or on a
     *     path where nothing ran, the proposal, since there was no second invocation
     * @param disposition how far the call got. Passed in rather than read off
     *     {@code result}, because a {@code ToolResult} cannot say it: an error result is
     *     what a refused call, a broken gate and a tool that threw all produce, which is
     *     the whole of #181
     * @param brokeAnInvariant whether the <em>tool body</em> was entered and threw outside
     *     {@code RuntimeException}. Not "the activity threw an Error": a gate that throws
     *     one is the deployment's policy code failing before anything ran, and this runner
     *     has a stated, tested decision that such a failure does not end a run
     */
    static ToolOutcome of(ToolInvocation settled, ToolResult result, boolean lowersTrust,
                          Disposition disposition, boolean brokeAnInvariant) {
        return new ToolOutcome(result.content(), result.isError(), result.provenance(), null,
                lowersTrust, settled, disposition, brokeAnInvariant, result.views());
    }

    /**
     * A call a gate stopped pending somebody's decision.
     *
     * <p>{@code content} is set as well, and marked an error, because it is what any reader
     * that does not know about {@code awaiting} will see — an older worker mid-deploy, or
     * anything reading the history payload directly. It has to be true on its own.
     */
    static ToolOutcome parked(PendingApproval awaiting) {
        // A parked call ran nothing, so it brought nothing back and lowers nothing.
        //
        // `effective` is null and `awaiting.invocation()` is the gate's settled call — see
        // the class javadoc for why it is not written a second time, and settled() for the
        // read that joins the two.
        return new ToolOutcome(awaiting.why().reason(), true, Provenance.FIRST_PARTY, awaiting,
                false, null, Disposition.PARKED, false, java.util.List.of());
    }

    /** The result to hand the model; meaningful whether or not the call was parked. */
    @JsonIgnore
    public ToolResult result() {
        return new ToolResult(content, isError, provenance, views);
    }

    /** What a person still has to decide, if a gate stopped this call to ask. */
    @JsonIgnore
    public Optional<PendingApproval> parked() {
        return Optional.ofNullable(awaiting);
    }

    /**
     * The call the gate settled on, given the one the model proposed.
     *
     * <p><strong>Not "the call the tool received".</strong> That sentence is false on the
     * paths where no tool received anything, and {@code AgentObserver.onToolResult} works
     * through all of them: a denied call and a gate that threw report the proposal because
     * there is literally no second invocation; a <strong>park</strong> reports the gate's
     * narrowed call even though nothing ran, because that is what a reviewer is shown and
     * what runs when they approve. The contract the value can keep everywhere is the call as
     * the <em>gate</em> settled it.
     *
     * <p>Three sources, in the order a reader should trust them:
     *
     * <ol>
     *   <li>{@code effective}, when this payload records it — every executed call written by
     *       a runner since #179.</li>
     *   <li>{@code awaiting.invocation()}, for a park, which is where the same fact already
     *       lived and still does.</li>
     *   <li>{@code proposed}, for a payload from before either existed. The honest reading:
     *       an old record cannot say a gate narrowed anything, and the proposal is what its
     *       history holds.</li>
     * </ol>
     *
     * @param proposed the call the model emitted — Temporal's own record of it is the
     *     activity input, which is why it is passed in rather than stored
     */
    @JsonIgnore
    public ToolInvocation settled(ToolInvocation proposed) {
        if (effective != null) {
            return effective;
        }
        return awaiting != null ? awaiting.invocation() : proposed;
    }

    /**
     * How far the call got, when this payload records it (#181).
     *
     * <p>{@code Optional} rather than a value with an {@code UNRECORDED} constant, and that
     * is the schema decision rather than a style one. The absent case belongs to one narrow
     * thing — a history payload written before the component existed — and putting it in
     * {@link Disposition} would spread it to every in-process caller, none of which can ever
     * see it, as a constant they must handle to be exhaustive. An empty {@code Optional} is
     * the honest reading and it is confined to the reader that can produce one.
     *
     * <p>One fallback, and only one: {@code awaiting} present proves a park, on any payload
     * of any age, because a park is the only thing that fills it. Nothing else is
     * recoverable — an old completed payload cannot say whether it was a denial, an unknown
     * tool, a broken gate or a tool that ran, which is exactly the state of affairs #181
     * describes. It is reported as absent rather than guessed at, for the reason the
     * constructor gives: the one available guess is the wrong one to make.
     */
    @JsonIgnore
    public Optional<Disposition> settledAs() {
        if (disposition != null) {
            return Optional.of(disposition);
        }
        return awaiting != null ? Optional.of(Disposition.PARKED) : Optional.empty();
    }
}
