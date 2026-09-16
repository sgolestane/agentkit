package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.util.Quoted;
import java.util.Objects;
import java.util.Optional;

/**
 * The decision of a {@link ToolGate}: whether a tool invocation may proceed, and
 * if not, the reason returned to the model.
 *
 * <p>Three outcomes, three types. {@link Allowed} runs the call, optionally with edited
 * arguments; {@link Denied} ends it with a reason the model reads; {@link NeedsAPerson}
 * ends nothing — it asks, and the runner arranges the waiting. A gate returns one of the
 * three and a reader may {@code switch} over them without a default arm.
 *
 * <h2>Why this is a hierarchy and not a boolean with two Optionals</h2>
 *
 * <p>It was {@code (boolean allowed, String reason, Optional replacement, Optional
 * awaiting)} plus two cross-field checks in the constructor, and those checks are the
 * argument. {@code !allowed && replacement.isPresent() && awaiting.isEmpty()} was refused at
 * run time, and {@code awaiting.isPresent() && allowed} was refused at run time — a type
 * with a state it has to talk itself out of, once per construction, forever.
 *
 * <p>The cost was not the checks. It was that a reader could ask {@link #allowed()} and
 * stop, and every defect this area has shipped is somebody doing exactly that. Two agents
 * rebuilt a result as {@code error().isPresent() ? failed(...) : stopped(...)}, which
 * enumerates outcomes by exclusion and threw {@code IllegalArgumentException} the first
 * time a real parking gate ran inside one. And {@code ToolGates.allOf} short-circuited on
 * {@code !allowed} and returned the parking member's result, discarding both the
 * accumulated call and every member after it: measured, {@code allOf(park, denyWires)} on a
 * proposal of 5,000,000 parked, was approved, and <strong>ran</strong>.
 *
 * <p><strong>And the shape was still costing, in this file's own combinator.</strong> The
 * repair for that measurement kept a {@code boolean edited} beside the accumulated call —
 * a second encoding of "is there a replacement" — and the park branch set the call and
 * forgot the flag. Measured on {@code allOf} with a gate that narrows and then parks
 * through {@link #needsAPerson(ApprovalNeeded, ToolInvocation)}:
 *
 * <pre>
 *   the gate alone settled on      : {amount=100000}
 *   the same gate inside allOf     : {amount=5000000}
 *   the member after it was judged : {amount=100000}
 * </pre>
 *
 * <p>So the later member cleared a narrowed call, the reviewer was shown the un-narrowed
 * one, and on approval the un-narrowed one runs — the exact failure {@code needsAPerson}'s
 * two-argument factory exists to prevent, reached through the one door nothing composed.
 * With three types there is no flag to forget: {@code combine} switches, and the call it
 * settled on is the call it carries, whichever arm it came through.
 *
 * <p>Against the change, honestly: it breaks any {@code new GateResult(...)}, which is a
 * real migration for anyone outside this repository. Every <em>reader</em> keeps compiling
 * — {@link #allowed()}, {@link #reason()}, {@link #replacement()} and {@link #awaiting()}
 * are still here and still mean what they meant, so a runner that has never heard of a
 * park still refuses one and says why. That is deliberate and it is also the half-measure
 * #158 warns about: keeping the accessors keeps {@code !allowed} spellable, so a reader
 * that wants javac's help has to ask for it by switching. The accessors stay because two
 * of the four readers in this repository are not this change's to edit, and a boundary
 * half-migrated in one commit is worse than one migrated on purpose in two.
 *
 * <h2>Edited arguments, and only those</h2>
 *
 * <p>An {@link Allowed} result may carry a {@code replacement} invocation — the same tool
 * with edited arguments — which the agent loop runs in place of the original. This lets an
 * {@link Approver} approve an action while adjusting its parameters.
 *
 * <p>A replacement names the same tool and the same call; {@link #effectiveFor} enforces
 * that rather than leaving it to the sentence above. It was only the sentence for a while,
 * and the gap ran the permissive way: both runners resolve the tool from the
 * <em>proposed</em> invocation and then execute that tool with the replacement's payload,
 * so a gate written to downgrade a public {@code publish} to a private {@code draft} let
 * the public publish happen, with the draft's arguments. Measured on both runners:
 * {@code publish} ran once, {@code draft} never. The gate's author reads {@code allowWith}
 * as "run this instead"; the loop implemented "run what I resolved, with these arguments".
 *
 * <p>Refused rather than honoured, which is the narrower of the two repairs available.
 * Re-resolving on a rename would make {@code allowWith} genuinely mean "run this instead",
 * and it is a bigger change than it looks: the redirected call has been gated as the tool
 * it was not, so it would have to go back through the gate — and through every later member
 * of an {@link ToolGates#allOf}, which the first one has already passed. That is a feature
 * to design, not a bug to patch, and until it exists a redirect that silently does not
 * redirect is the failure worth removing.
 *
 * <p>A {@link NeedsAPerson} result may carry one too, which is the single case where a
 * result that is not allowed has a replacement — and it is now a property of that type
 * rather than a constructor check every other case has to be excused from. A park is not
 * the end of the story — somebody is shown the call and the call then runs — so an edit
 * made before the park has to survive it, or the reviewer and the runner are looking at two
 * different calls.
 *
 * <h2>A third answer</h2>
 *
 * <p>{@link #needsAPerson} is neither allow nor deny: the policy has reached its limit and
 * somebody has to decide. It reports {@code allowed() == false}, so a runner that has never
 * heard of it refuses the call and says why — the safe direction, and the reason this
 * component could be added without auditing every reader of a {@code GateResult}. A runner
 * that does know arranges the waiting in whatever way it can; see {@link ApprovalNeeded}
 * for why that is the runner's job and not the gate's.
 *
 * <p>That safety is about readers of <em>this</em> type, and it does not extend to readers
 * of the <em>outcome</em>. {@code StopReason.AWAITING_APPROVAL} travels further, and two
 * agents that rebuild a result by enumerating stop reasons crashed on it — see
 * {@code AgentResult.withTotals}. A fail-closed default at one boundary says nothing about
 * the next one.
 */
public sealed interface GateResult
        permits GateResult.Allowed, GateResult.Denied, GateResult.NeedsAPerson {

    /**
     * Whether execution is permitted.
     *
     * <p>True for {@link Allowed} and false for both of the others, which is the fail-closed
     * answer a reader that predates {@link NeedsAPerson} needs. A reader that wants javac to
     * name the arms it has not handled should {@code switch} over the three types instead;
     * this method cannot tell a refusal from a question, and every defect in this area was a
     * reader that did not need to.
     */
    boolean allowed();

    /**
     * When not allowed, an explanation surfaced to the model; empty when allowed.
     *
     * <p>A {@link NeedsAPerson} result reports {@link ApprovalNeeded#reason()} here too, so
     * a runner that only understands allow/deny still says something true.
     */
    String reason();

    /**
     * A substitute invocation to run instead of the one the model proposed; empty to run
     * the original.
     *
     * <p>Present only on {@link Allowed} and {@link NeedsAPerson}. Prefer
     * {@link #effectiveFor}, which applies it and enforces what a replacement may change.
     */
    Optional<ToolInvocation> replacement();

    /**
     * When a person must decide, the gate's reason for saying so; empty otherwise.
     *
     * <p>Present only on {@link NeedsAPerson}, where it is the {@code why} component.
     */
    Optional<ApprovalNeeded> awaiting();

    /** Allows the call exactly as the model proposed it. */
    static GateResult allow() {
        return Allowed.UNCHANGED;
    }

    /**
     * Allows execution, but substitutes {@code replacement} for the proposed invocation.
     *
     * <p><strong>Edited arguments only.</strong> {@code replacement} must carry the same
     * {@code name} and {@code id} as the invocation the gate was given; those identify which
     * call this is, and only the arguments are substitutable. A replacement that renames is
     * refused at the runner by {@link #effectiveFor} — it used to be honoured as far as the
     * arguments and ignored as far as the tool, so a gate written to downgrade a public
     * {@code publish} to a private {@code draft} let the publish happen.
     *
     * <p>Making the rename unrepresentable — {@code allowWith(Map)} rather than
     * {@code allowWith(ToolInvocation)} — is the cleaner shape and is what the framework's
     * own approver API already does: {@code ApprovalDecision.approveWithArguments} takes a
     * map, and {@code toGateResult} rebuilds the invocation. Every replacement built in this
     * repository preserves the name and the id, so an additive {@code allowWith(Map)} with
     * this overload deprecated would cost no expressiveness. It is not done here because
     * this change is about the outcome hierarchy and that is a separate API decision;
     * {@link #effectiveFor} is the backstop either way, since a deprecated overload is still
     * callable.
     *
     * @see #effectiveFor
     */
    static GateResult allowWith(ToolInvocation replacement) {
        return new Allowed(Optional.of(Objects.requireNonNull(replacement, "replacement")));
    }

    /** Refuses the call, with a reason the model reads and can adapt to. */
    static GateResult deny(String reason) {
        return new Denied(reason);
    }

    /**
     * Stops the call and says a person must decide before it can proceed.
     *
     * <p>Not a denial and not an allowance — see {@link ApprovalNeeded}, which carries the
     * argument for why the three are different and why the waiting belongs to the runner.
     *
     * <p>{@code why.reason()} is reported by {@link #reason()} as well as being carried in
     * {@link #awaiting()}. That is deliberate duplication: {@link #allowed()} is
     * {@code false}, so a runner that predates this — or one outside this repository —
     * refuses the call and surfaces the reason, which is a true thing to say. It just does
     * not do the second half, which is asking.
     *
     * <p>A gate saying this must not also block. {@link ToolGate#waitsForAHuman()} is about
     * a gate that <em>waits</em>, and the whole point of parking is that it returns at once,
     * so a parking gate answers {@code false} there and stays usable on the durable runner
     * that refuses a waiting one. {@code ToolGates.parkForApproval} is the built one.
     */
    static GateResult needsAPerson(ApprovalNeeded why) {
        return new NeedsAPerson(why, Optional.empty());
    }

    /**
     * The same, for a call an earlier gate has already edited.
     *
     * <p>A parked result is the one kind of stop that carries a replacement, and since the
     * hierarchy that is a fact about the type rather than an exception carved out of a
     * constructor. The reason is that a park is not the end of the story: somebody is shown
     * this call and the call then runs, so "which call" has to survive the park or the two
     * are different calls.
     *
     * <p>It was not there at first and {@link ToolGates#allOf} dropped the edit. Measured
     * with {@code allOf(narrowAmountTo100k, parkForApproval)} on a proposal of 5,000,000:
     * the reviewer was shown 5,000,000 and the tool ran with 5,000,000. Both halves wrong,
     * and both in the permissive direction — a reviewer approving something other than what
     * policy had reduced it to, and then the un-reduced version running. That is #104's
     * failure ("a gate written to downgrade a public {@code publish} to a private
     * {@code draft} let the publish happen") arriving again through a new door, and the
     * root cause is the same: a decision meaning "run this instead" dropped by a path that
     * looked only at {@link #allowed()}.
     *
     * <p>And it was dropped a second time, by the repair for that measurement, when the
     * narrowing was the parking member's <em>own</em> rather than an earlier member's — see
     * this interface's javadoc for the numbers. Both drops were a flag beside the value
     * disagreeing with the value; neither is expressible now.
     *
     * @param replacement the call as it stands after the members that ran before the park;
     *                    must name the same tool and the same call, which
     *                    {@link #effectiveFor} enforces
     */
    static GateResult needsAPerson(ApprovalNeeded why, ToolInvocation replacement) {
        return new NeedsAPerson(why,
                Optional.of(Objects.requireNonNull(replacement, "replacement")));
    }

    /**
     * The invocation to actually run, given the one that was proposed.
     *
     * <p>One spelling of the rule, because there are two runners and an authorization
     * boundary they disagree about is worse than either behaviour. {@code Agent.runTool} and
     * {@code ToolActivitiesImpl} both call this; neither decides anything itself.
     *
     * <p>{@code name} and {@code id} identify <em>which call this is</em> — the first
     * chooses the tool that was resolved and gated, the second correlates the result with
     * the model's request — so a replacement may change neither. Only the arguments are
     * substitutable, and that is what a replacement is for.
     *
     * <p><strong>Upgrading.</strong> The id refusal breaks a gate that worked before, and it
     * is not a hostile one: "stamp every gated call with an audit id" was legal while the id
     * was ignored, and now disables the call. That is deliberate — a replacement meaning
     * exactly one thing is worth more than the pattern — but it fails at run time with no
     * compile-time signal, so it is worth grepping for before upgrading. The id is not a
     * security property in its own right: nothing requires a model's tool-call ids to be
     * unique within a turn, so two calls can share one and this cannot tell them apart.
     * What it buys is that a replacement changes arguments and nothing else.
     *
     * <p><strong>Identity is load bearing.</strong> With no replacement this returns
     * {@code proposed} itself, not a copy, and {@link ToolGates#allOf} reads exactly that to
     * decide whether any member substituted anything. A future implementation that rebuilt
     * an equal invocation would still satisfy every assertion about arguments and would
     * silently make every composite report an edit.
     *
     * @throws IllegalArgumentException if the replacement names a different tool or call.
     *     Thrown rather than returned, and inside the runners' {@code try}: a gate's author
     *     has made a mistake at an authorization boundary, so the tool must not run, and the
     *     runner turns it into a tool error the way it does for a gate that throws. Loud and
     *     closed, where it used to be silent and open.
     *     <p>The message names the <em>proposed</em> tool and not the replacement's, and
     *     carries no other detail. It reaches the model through the runners' catch, so
     *     naming what the policy wanted instead would disclose the control's intent to the
     *     party it is applied to — and a gate that builds its replacement's name from a
     *     model argument, which is the mistake this is written to catch, would be handing
     *     the model a pen: an unbounded, unfenced string in the framework's voice. The
     *     operator gets the gate's own frame in the stack trace, which is what finds the
     *     gate anyway.
     */
    default ToolInvocation effectiveFor(ToolInvocation proposed) {
        Objects.requireNonNull(proposed, "proposed");
        ToolInvocation substitute = replacement().orElse(null);
        if (substitute == null) {
            return proposed;
        }
        if (!substitute.name().equals(proposed.name())) {
            // Names the proposed tool and not the replacement's. The model already knows
            // what it asked for; the tool a gate wanted to redirect to is the policy's
            // business, and this message reaches the model through the runners' catch. The
            // operator gets the gate's own frame in the stack trace, which is more use than
            // a name for finding the gate that did it.
            throw refusal("redirect to another tool", proposed.name());
        }
        if (!substitute.id().equals(proposed.id())) {
            throw refusal("renumber the call", proposed.id());
        }
        return substitute;
    }

    private static RefusedSubstitution refusal(String what, String proposed) {
        // The quote is escaped as well as the rest, because adding a delimiter and not
        // escaping it lets the value end its own quotes — the defect Quoted.each fixes.
        return new RefusedSubstitution("a gate's replacement may edit arguments but not "
                + what + ", so the call was refused: '"
                + Quoted.of(proposed).replace("'", "\\u0027") + "'");
    }

    /**
     * The call runs.
     *
     * <p>Carries a {@code replacement} when policy edited the arguments, and nothing else:
     * there is no reason on an allowance and no {@link ApprovalNeeded}, so
     * "allowed and also awaiting a person" — which the old constructor refused with a
     * sentence about the two contradicting each other at the one place a runner reads to
     * decide whether the tool runs — is not a state this type has.
     *
     * @param replacement the call to run instead of the proposal, or empty to run the
     *                    proposal; never {@code null}
     */
    record Allowed(Optional<ToolInvocation> replacement) implements GateResult {

        /**
         * The overwhelmingly common answer, allocated once.
         *
         * <p>{@link GateResult#allow()} returned a shared instance before the hierarchy and
         * still does. Records compare by value, so nothing can tell this from a fresh
         * {@code new Allowed(Optional.empty())} except {@code ==}, and nothing should.
         */
        private static final Allowed UNCHANGED = new Allowed(Optional.empty());

        public Allowed {
            Objects.requireNonNull(replacement, "replacement");
        }

        @Override
        public boolean allowed() {
            return true;
        }

        /** Empty: an allowance has nothing to explain to the model. */
        @Override
        public String reason() {
            return "";
        }

        /** Empty: nobody is being asked. */
        @Override
        public Optional<ApprovalNeeded> awaiting() {
            return Optional.empty();
        }
    }

    /**
     * The call does not run, and that is the end of it.
     *
     * <p>No replacement, which the old constructor spent a check refusing: a denied call has
     * nothing to substitute into, and a gate that meant "run this narrower thing instead"
     * was reaching for {@link GateResult#allowWith}. The model is told {@code reason} and adapts.
     *
     * @param reason what to tell the model; never {@code null}. The deployment's own words,
     *               so it is not fenced — see {@link ApprovalNeeded} on what that costs a
     *               gate that builds one out of a model argument
     */
    record Denied(String reason) implements GateResult {

        public Denied {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public boolean allowed() {
            return false;
        }

        /** Empty: a denial substitutes nothing, because nothing runs. */
        @Override
        public Optional<ToolInvocation> replacement() {
            return Optional.empty();
        }

        /** Empty: a denial is an answer, not a question. */
        @Override
        public Optional<ApprovalNeeded> awaiting() {
            return Optional.empty();
        }
    }

    /**
     * The call does not run <em>yet</em>: a person must decide, and the runner arranges it.
     *
     * <p>The one stop that carries a replacement. {@link ToolGates#allOf} and both agent
     * loops treat that call as what the reviewer is shown and what runs on approval, so a
     * narrowing made before the park survives it.
     *
     * @param why         the gate's reason for asking, and what it says will change; never
     *                    {@code null}
     * @param replacement the call as policy has settled it, or empty when nothing was
     *                    edited; never {@code null}
     */
    record NeedsAPerson(ApprovalNeeded why, Optional<ToolInvocation> replacement)
            implements GateResult {

        public NeedsAPerson {
            Objects.requireNonNull(why, "why");
            Objects.requireNonNull(replacement, "replacement");
        }

        @Override
        public boolean allowed() {
            return false;
        }

        /**
         * {@link ApprovalNeeded#reason()}, so a runner that only understands allow and deny
         * still tells the model something true about why its call did not run.
         */
        @Override
        public String reason() {
            return why.reason();
        }

        /** {@link #why()}, for a reader that has only the {@code GateResult}. */
        @Override
        public Optional<ApprovalNeeded> awaiting() {
            return Optional.of(why);
        }
    }

    /**
     * What {@link #effectiveFor} throws when a replacement renames or renumbers.
     *
     * <p>A type of its own, rather than a bare {@code IllegalArgumentException}, because
     * three things want to tell this apart from any other argument complaint and none of
     * them could. {@code TracingToolGate} labels it {@code refused} rather than "the gate
     * blew up" — and had to catch every {@code RuntimeException} to do it, which would
     * silently relabel a future validation failure. {@code ToolGates.allOf} raises it from
     * inside {@code evaluate}, where a decorator sees a throw and cannot know what kind. And
     * a runner that wanted to log a gate-author mistake differently from a gate that threw
     * had nothing to switch on.
     *
     * <p>Still an {@link IllegalArgumentException}, so every existing {@code catch} keeps
     * working: the four runners turn it into a tool error exactly as before, and that is
     * the behaviour, not an implementation detail.
     */
    final class RefusedSubstitution extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        RefusedSubstitution(String message) {
            super(message);
        }
    }
}
