package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.Provenance;
import java.util.Objects;
import java.util.Set;

/**
 * Two policies and the moment a run moves from one to the other: <em>after you read the
 * web, you cannot write</em> (#122).
 *
 * <h2>Why not per-argument taint</h2>
 *
 * <p>Because it degenerates. The model is opaque, so once one third-party result is in the
 * context every later token is potentially influenced by it — a gate keyed on "was this
 * argument derived from untrusted input" allows the first step and denies everything after.
 * {@link Provenance}'s javadoc says the same thing and calls it the honest reason this
 * framework spends its enforcement on capability rather than provenance.
 *
 * <p>Run-scoped is the tractable substitute. It is coarse, and coarse is the point: it
 * bounds what a run can still do after it has read somebody else's words, rather than
 * trying to trace which words went where.
 *
 * <h2>This is not a gate, and cannot be</h2>
 *
 * <p>A floor is <strong>run state</strong> — whether this particular run has read somebody
 * else's words yet — and {@code ToolActivitiesImpl} spells out at length why a durable gate
 * may not carry any: it is re-evaluated on every activity retry, and one instance serves
 * every run on a worker, so per-run state leaks between unrelated runs and tenants.
 *
 * <p>So this holds two gates, both pure, and the <em>runner</em> holds the one bit of state
 * and chooses between them. That is the same division #101 arrived at for a parked call:
 * the policy decides and returns, the runner does the part that needs to remember something.
 *
 * <h2>Monotonic, which is what makes it survive things</h2>
 *
 * <p>Once lowered, a floor stays lowered for the rest of the run. Not because raising it
 * again would be wrong in principle but because nothing could safely decide when: the
 * content is in the context and the framework cannot see what the model did with it.
 *
 * <p>Two consequences worth having:
 *
 * <ul>
 *   <li><strong>Compaction cannot lift it.</strong> A summary of third-party content is
 *       still third-party, and {@code SummarizingCompactor} renders a tool result into
 *       plain text with no provenance on it. A floor derived by <em>scanning the
 *       transcript</em> would silently rise the moment the transcript was rewritten. A
 *       monotonic flag is not derived from the transcript at all.</li>
 *   <li><strong>Replay reaches the same answer.</strong> The durable loop sets the flag in
 *       tool order, which is fixed, so a replayed run and a fresh one agree about which
 *       policy is in force. A transcript scan would have had to agree with history rather
 *       than with in-memory state, which is a determinism bug waiting to be written.</li>
 * </ul>
 *
 * <h2>Opt-in, and the reason is {@link Provenance#UNKNOWN}</h2>
 *
 * <p>There is no default floor and no framework-wide switch. A tool that has not declared
 * its provenance reports {@code UNKNOWN}, which today is most tools in most deployments —
 * so a floor that counted {@code UNKNOWN} and was on by default would engage on nearly
 * every run, and a control that always fires is a control nobody keeps.
 *
 * <p>Which of the two {@code UNKNOWN} counts as is therefore yours, and the two named
 * factories are the two honest answers:
 *
 * <ul>
 *   <li>{@link #afterThirdParty} — only a tool that <em>says</em> it returns somebody
 *       else's words lowers the floor.</li>
 *   <li>{@link #afterAnythingUndeclared} — undeclared counts too. The strict reading, for a
 *       deployment that treats not having thought about a tool as the answer that costs
 *       something.</li>
 * </ul>
 *
 * <h2>Which framework tools lower a floor, which is the fact you need</h2>
 *
 * <p>An earlier version of this javadoc said {@code afterThirdParty} was "useless before
 * you declare, because nothing declares anything". That was measurably false, and the
 * truth points the other way. These declare {@link Provenance#THIRD_PARTY} today, so all of
 * them lower a floor under <em>either</em> reading:
 *
 * <ul>
 *   <li>every MCP tool, by definition ({@code McpTool})</li>
 *   <li>{@code memory} and {@code recall} ({@code MemoryTools})</li>
 *   <li>{@code read_skill} and {@code read_skill_resource} ({@code SkillTools})</li>
 *   <li>{@code knowledge_search} ({@code KnowledgeTools})</li>
 *   <li>{@code delegate} ({@code SubagentTools})</li>
 *   <li>{@code search_tools} ({@code DisclosingToolRegistry})</li>
 *   <li>{@code send_message} ({@code MessagingTools})</li>
 *   <li>{@code read_board} ({@code BlackboardTools})</li>
 * </ul>
 *
 * <p>So an agent that uses skills, memory or subagents lowers its floor almost at once, and
 * a floor tightening to {@code readOnly()} makes it read-only from roughly step two. That
 * may be what you want; it is certainly not "the web".
 *
 * <p>The reason is worth knowing rather than working around. {@link Provenance} was
 * calibrated to answer <em>is this worth fencing?</em> — and by that measure {@code recall}
 * is right to declare {@code THIRD_PARTY}, since the model's own earlier notes are not the
 * deployment's words and may carry something the model read last week. Using the same label
 * as an <em>authorization</em> trigger asks a different question — <em>is this exposed to
 * adversarially controlled content?</em> — and the two answers come apart for a skill
 * bundle the deployment itself ships.
 *
 * <h2>#161 is settled, and the answer is that you narrow this yourself</h2>
 *
 * <p>There is no second per-tool declaration and there will not be one. Exposure is not a
 * property of a tool but of a tool <em>in a deployment</em> — {@code search_tools} over an
 * MCP-backed registry and {@code search_tools} over a catalogue of string literals are one
 * class with one declaration and opposite answers — so a third field on {@code Tool} would
 * be filled in by the only party who cannot know. {@link Provenance} carries the argument
 * in full.
 *
 * <p>What you get instead is two knobs, both of which were already here:
 *
 * <ul>
 *   <li>{@link #lowersOn} — which labels count. Choose the trigger set explicitly rather
 *       than by factory name.</li>
 *   <li>{@code Tools.withProvenance} — what <em>you</em> say about a tool you did not
 *       author. Wrapping a vendored {@code read_skill} as {@link Provenance#FIRST_PARTY} is
 *       how a deployment states that its own bundle is inside its trust boundary, and it is
 *       the answer to the {@code read_skill} case #161 opens with. Until #161 that wrapper
 *       could not do it: an undeclared result collapsed a {@code FIRST_PARTY} tool back to
 *       {@code UNKNOWN}, so the strict floor lowered on it anyway. See
 *       {@code ToolResult}'s {@code narrower} for the table.</li>
 * </ul>
 *
 * <p>Keying on these labels is safe in the direction that matters: every label that is
 * wrong for exposure is wrong by <em>over</em>-stating it, so a floor loses a capability it
 * could have kept and never keeps one it should have lost.
 *
 * <h2>What lowers it, and what does not</h2>
 *
 * <p>A <strong>tool result</strong> lowers it. Nothing else does. A goal a user typed, a
 * knowledge passage already in the system prompt, a fenced skill catalogue — all of those
 * may well be somebody else's words and none of them moves this, because the framework
 * learns their provenance nowhere. {@code Provenance} rides on a {@code ToolResult}, so
 * that is the only place a floor can honestly key on.
 *
 * <p><strong>That is a limit on where a floor is a control at all, and it is not
 * theoretical.</strong> A run whose adversarial input arrives <em>before</em> its first tool
 * call has no lenient phase to protect, so {@link #ordinarily} is dead and the wiring is
 * {@link #onceLowered} with extra steps. Measured on {@code agentkit-examples-itops}, whose
 * scheduled path fences the ticket into the goal and whose chat path reads it on the first
 * call:
 *
 * <pre>
 * itops path   the adversary's bytes are in context   calls ordinarily governs
 * scheduled    before the first tool call             1 — search_tools
 * chat         after the first tool call              1 — ticketing.get_ticket
 * workflow     only if a step reads a ticket          every step of both shipped graphs
 * </pre>
 *
 * <p>This type already refuses the one spelling of that shape it can detect — a trigger set
 * containing {@link Provenance#FIRST_PARTY}, refused below because it "lowers the floor on
 * the first tool call of every run, which is the same as wiring onceLowered directly". The
 * itops agent path is the same thing arriving by a route no constructor can see, which is
 * why the example wires a floor on its workflow runner and a plain gate on its agent runner
 * (#162).
 *
 * @param ordinarily  the policy in force until the floor lowers; never {@code null}
 * @param onceLowered the policy in force afterwards; never {@code null}. The framework does
 *                    not decide what "tighter" means — {@code ToolGates.readOnly()},
 *                    {@code parkForApproval(...)} for anything with side effects, or a
 *                    {@code denyTools} list are all reasonable and they are not the same
 *                    deployment's answer
 * @param lowersOn    which provenances count as somebody else's words; never {@code null}
 *                    or empty
 */
public record TrustFloor(ToolGate ordinarily, ToolGate onceLowered, Set<Provenance> lowersOn) {

    public TrustFloor {
        Objects.requireNonNull(ordinarily, "ordinarily");
        Objects.requireNonNull(onceLowered, "onceLowered");
        lowersOn = Set.copyOf(Objects.requireNonNull(lowersOn, "lowersOn"));
        if (!lowersOn.isEmpty() && ordinarily == onceLowered) {
            // The shape that shipped the bug, refused where it is built. A floor with a
            // trigger and nothing to switch to reports that it lowered — a false log line
            // at an authorization boundary, a wrong audit payload in history, and durably a
            // change of activity type mid-run — while changing nothing. The framework used
            // to build this itself to mean "no floor"; none(gate) is that, and says so.
            throw new IllegalArgumentException(
                    "this floor names a trigger but tightens to the same policy, so lowering"
                            + " it changes nothing while reporting that it did; use"
                            + " TrustFloor.none(gate) if you meant no floor");
        }
        if (lowersOn.isEmpty() && ordinarily != onceLowered) {
            // A floor nothing lowers is two gates and a dead branch, which reads at every
            // call site as a control that is in force. Refused where it is built.
            //
            // Except for one gate wired twice, which is how a runner represents "no floor"
            // — see none(ToolGate). That case had to be representable: the first version
            // faked it with afterThirdParty(gate, gate), so a deployment that had never
            // heard of a floor reported a lowering on its first third-party result, logged
            // that its floor had engaged, and — durably — switched activity method mid-run.
            // The invariant meant to protect callers was defeated by the only two callers
            // there are.
            throw new IllegalArgumentException(
                    "a trust floor that nothing lowers is not a floor; name at least one"
                            + " Provenance, or wire the gate directly");
        }
        if (lowersOn.contains(Provenance.UNKNOWN) && !lowersOn.contains(Provenance.THIRD_PARTY)) {
            // Counting silence but not an admission inverts the whole idea: a tool that
            // *says* it returns somebody else's words would not lower the floor, while one
            // that said nothing would. Whoever wrote this meant afterAnythingUndeclared.
            throw new IllegalArgumentException(
                    "a floor that counts UNKNOWN must also count THIRD_PARTY, or a tool that"
                            + " declares what it returns lowers it less than one that says"
                            + " nothing");
        }
        if (lowersOn.contains(Provenance.FIRST_PARTY)) {
            // Every run reads its own words on its first tool call, so this lowers the
            // floor immediately and always. Whoever wanted that wanted onceLowered as the
            // only gate, and should say so by wiring it.
            throw new IllegalArgumentException(
                    "FIRST_PARTY lowers the floor on the first tool call of every run, which"
                            + " is the same as wiring onceLowered directly");
        }
    }

    /**
     * Lowers only for a tool that declares {@link Provenance#THIRD_PARTY}.
     *
     * <p>Not inert: see the list above for the framework tools this already fires on. An
     * undeclared tool reports {@code UNKNOWN} and does not lower it, which is a real state
     * of affairs rather than a bug in this method — a floor cannot act on a fact nobody has
     * stated.
     */
    public static TrustFloor afterThirdParty(ToolGate ordinarily, ToolGate onceLowered) {
        return new TrustFloor(ordinarily, onceLowered, Set.of(Provenance.THIRD_PARTY));
    }

    /**
     * Lowers for anything not declared {@link Provenance#FIRST_PARTY}.
     *
     * <p>The strict reading. It fires on everything {@link #afterThirdParty} fires on, plus
     * every tool nobody has declared — which on most registries is nearly all of them, so
     * expect it to lower on the first tool call. That is the point of choosing it: an
     * undeclared tool is one nobody has thought about, and this treats not having thought
     * about it as the answer that costs you something.
     */
    public static TrustFloor afterAnythingUndeclared(ToolGate ordinarily, ToolGate onceLowered) {
        return new TrustFloor(ordinarily, onceLowered,
                Set.of(Provenance.THIRD_PARTY, Provenance.UNKNOWN));
    }

    /**
     * No floor at all: {@code gate} governs the whole run, whatever it reads.
     *
     * <p>What a runner holds when a caller wired a plain gate. It exists so "no floor" is a
     * state this type can be in rather than one a runner has to fake — the fake was
     * {@code afterThirdParty(gate, gate)}, which lowers, and so told an opted-out
     * deployment that its floor had engaged and switched what it did on the wire.
     */
    public static TrustFloor none(ToolGate gate) {
        Objects.requireNonNull(gate, "gate");
        return new TrustFloor(gate, gate, Set.of());
    }

    /**
     * Whether this is a real floor, or a single gate wearing the shape of one.
     *
     * <p>A question for a caller inspecting a floor — a decorator preserving its shape, a
     * console showing what policy a run is under. The runners do not need it: a floor that
     * does not exist has an empty trigger set, so {@link #lowersOn(Provenance)} already
     * answers false for everything and nothing engages.
     */
    public boolean exists() {
        return !lowersOn.isEmpty();
    }

    /** Whether a result written by {@code provenance} lowers this floor. */
    public boolean lowersOn(Provenance provenance) {
        return lowersOn.contains(provenance == null ? Provenance.UNKNOWN : provenance);
    }

    /** The policy in force, given whether this run has already read somebody else's words. */
    public ToolGate inForce(boolean lowered) {
        return lowered ? onceLowered : ordinarily;
    }

    /**
     * Whether <em>both</em> policies refuse every tool not declared side-effect-free.
     *
     * <p>AND, where {@link #waitsForAHuman()} is OR, and the asymmetry is the interesting
     * part. Blocking is a hazard: one policy that can block is enough to make the wiring
     * unusable on a runner that cannot host a wait. Read-only is a guarantee: it holds for
     * the run only if it holds whichever policy is in force, and a run reaches both.
     *
     * <p>{@code CodeExecutionTool} is the caller. It lets a script declare
     * {@code sideEffects = NONE} only on the strength of this, so a floor whose ordinary
     * policy lets writers through cannot support that declaration however strict it becomes
     * later — the writes happen before it becomes anything.
     */
    public boolean guaranteesReadOnly() {
        return ordinarily.guaranteesReadOnly() && onceLowered.guaranteesReadOnly();
    }

    /**
     * Whether either policy may block waiting for a person.
     *
     * <p>Asked of both, because a run reaches both. The durable runner refuses a gate that
     * waits, and it has to refuse a floor whose <em>second</em> gate waits just as surely —
     * otherwise the wiring is accepted and the run dies the first time somebody reads a web
     * page.
     */
    public boolean waitsForAHuman() {
        return ordinarily.waitsForAHuman() || onceLowered.waitsForAHuman();
    }

    /**
     * Whether either policy was built for one particular run.
     *
     * <p>OR, for {@link #waitsForAHuman()}'s reason and not
     * {@link #guaranteesReadOnly()}'s: this is a hazard, and one policy that belongs to a
     * single run makes the whole floor belong to it. A floor whose <em>tightened</em> gate
     * screens against one run's objective is exactly as unusable on a shared worker as one
     * whose ordinary gate does — and worse to find, because it engages only after something
     * has read somebody else's words.
     *
     * <p>The durable runner reads this at registration; see
     * {@link ToolGate#boundToOneRun()} for what it measured and why it refuses rather than
     * warns.
     */
    public boolean boundToOneRun() {
        return ordinarily.boundToOneRun() || onceLowered.boundToOneRun();
    }
}
