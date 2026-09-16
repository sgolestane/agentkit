package dev.agentkit.core.message;

import java.util.Map;

/**
 * One tool call as proposed: either a {@link ToolUseBlock} a runner can act on, or the
 * {@link UnusableToolUseBlock} that stands in its place when the arguments are ones this
 * framework will not carry.
 *
 * <h2>Why the pair exists (#246)</h2>
 *
 * <p>{@link ToolUseBlock}'s constructor refuses arguments past
 * {@link dev.agentkit.core.util.Frozen#MAX_DEPTH}, past the node budget, or holding a value
 * that is not a JSON shape. That refusal is right and stays. What was wrong is that it was
 * the <em>only</em> thing that happened: it fired while a provider's turn was being parsed,
 * so the turn could not be built, so no assistant message existed to echo back, so no tool
 * result could be correlated to the call, so the model was told nothing and the run ended.
 * Measured against a gate that denies everything with a reason:
 *
 * <pre>
 *  99 levels  -&gt;  gate consulted, denied, the run finished normally
 * 101 levels  -&gt;  no gate consulted, no tool ran, the run ended ERROR
 * </pre>
 *
 * <p>This type is what lets the turn be built anyway. A call whose arguments were refused
 * becomes an {@link UnusableToolUseBlock}, which carries the id, the tool name and the
 * refusal — and <strong>not</strong> the arguments, because nothing froze them.
 *
 * <h2>The third shape, and why the two obvious ones are fail-open</h2>
 *
 * <p>#246 asks for the invocation to be "constructed with the deep tree and refused at the
 * gate". Neither way of doing that literally survives contact:
 *
 * <ul>
 *   <li>Hand a runner a {@code ToolUseBlock} whose {@code input()} is <em>not</em> what the
 *       model sent — a truncated or emptied copy — and a gate judges arguments the tool
 *       never received. An authorization boundary deciding about a call that does not exist
 *       is the defect {@code Frozen}'s own javadoc opens with, one step earlier.</li>
 *   <li>Hand it one it can run with arguments nothing froze, and every downstream recursion
 *       over that map — {@code Goal.render}'s {@code String.valueOf}, Jackson's serializer —
 *       is back on the stack hazard #193 sized {@code MAX_DEPTH} against. Worse, and for a
 *       gain of nothing: the gate would be asked about a tree the framework has already
 *       decided it cannot hold.</li>
 * </ul>
 *
 * <p>The third shape is to <strong>ask nobody</strong>. The decision has already been taken
 * — by {@code Frozen}, at the only place that can see the structure — so there is nothing
 * left for a gate to decide. What was missing was never a judgement; it was a
 * <em>delivery</em>. So an {@link UnusableToolUseBlock} is not a call a runner may make: it
 * has no {@code input()} to run with and no {@code input()} to gate, and a runner's only
 * move on one is to hand back {@link UnusableToolUseBlock#refusal()} as an error result and
 * carry on. That is strictly more fail-closed than before — one fewer component is asked,
 * not one more — and the model gets a sentence it can act on.
 *
 * <h2>Two types rather than a flag on one</h2>
 *
 * <p>A fourth component on {@code ToolUseBlock} — {@code Optional<String> unusable}, input
 * empty when it is present — was written first and rejected for two reasons, one of them
 * measured.
 *
 * <p><strong>It is fail-open across a rolling deploy.</strong> {@code DurableJson} disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} on purpose, so that a payload type can gain components
 * additively and an old worker handed a new payload carries on rather than stalling the run.
 * An old worker handed a {@code tool_use} carrying a new {@code unusable} field therefore
 * <em>drops the field</em> and is left with a block whose input is {@code {}} — a call it
 * will gate and run with arguments the model never sent. A separate {@code @type} is refused
 * by that same worker instead, which stalls the run: bad, and the direction this repository
 * takes at an authorization boundary every time.
 *
 * <p><strong>And a flag is a warning, which is what a type resorts to when it has not made
 * the thing true.</strong> {@code Frozen}'s class javadoc says exactly that about the
 * "treat argument values as read-only" note it replaced. With two types, no
 * {@code ToolUseBlock} anywhere can hold arguments the framework refused, so no gate and no
 * tool can be handed one; the compiler makes every {@code ContentBlock} switch say what it
 * does with the other case rather than defaulting into running it.
 *
 * <h2>One rule, four runners</h2>
 *
 * <p>{@link #of} is that rule. {@code Agent}, {@code AgentWorkflowImpl}, {@code ToolBridges}
 * and itops' {@code WorkflowRunner} each ask it and each act on the answer the same way;
 * none of them decides for itself what "arguments this framework will not carry" means or
 * what a model is told about it. That is the shape {@code GateResult.effectiveFor} and
 * {@code ToolUseBlock.refusalForRepeatedIds} were each written once for, and the defect
 * #131, #179, #163 and #63 all landed on.
 *
 * <p>Two of the four are handed a map by something that is not a model — a sandboxed script
 * in {@code ToolBridges}, a workflow graph plus this run's scope in itops — and mint the id
 * themselves. They go through the same door regardless: "proposed" here is about the call
 * being a proposal that has not been gated yet, not about who proposed it.
 *
 * <h2>And every stand-in for a provider (#277)</h2>
 *
 * <p>A test's scripted {@code LlmClient} is a provider adapter for the purposes of this
 * rule, and one that calls {@link ToolUseBlock}'s constructor directly <strong>throws where
 * every shipped adapter refuses</strong>. #269's hostile pass found the first instance and
 * stated the failure mode plainly: the pentest suite's stand-in went on throwing after
 * every real adapter had stopped, and its {@code UnusualArgumentTest} stayed green against
 * a fake that no longer modelled anything. Not a broken test — a <em>passing</em> one that
 * had quietly stopped testing the thing it names. #277 swept the rest of the suite: 47
 * direct constructions across 24 files in seven modules, against the eleven stand-ins the
 * issue could name. Seven of those 47, in four files, were spelled with the type
 * fully qualified and were missed by the first sweep of this change too — which is the
 * issue's own point about a list, one level down.
 *
 * <p><strong>The factory cannot be made the only door, and that is the language's decision
 * rather than a choice left open.</strong> #277 asks whether {@code new ToolUseBlock(...)}
 * should be reachable from test code at all. It cannot not be: a record's canonical
 * constructor may not be less accessible than the record itself, so {@code javac} refuses a
 * {@code public record} with a hidden one —
 *
 * <pre>
 * error: invalid canonical constructor in record ToolUseBlock
 *   (attempting to assign stronger access privileges; was public)
 * </pre>
 *
 * <p>— and hiding it would in any case break the two callers that must have it: the tests
 * of the constructor's own refusal, and {@code DurableJson}, which deserializes the record
 * through it.
 *
 * <p><strong>What followed from that was wrong, and it is corrected rather than deleted
 * because it is the sentence that would have stopped the next person writing the check
 * (#301).</strong> The paragraph went on:
 *
 * <blockquote>So the enforcement is documentary rather than structural, and it sits here
 * because this is the sentence a future stand-in's author will read.</blockquote>
 *
 * <p>The premise is measured and stands; the conclusion does not follow from it. Java
 * forbids <em>hiding</em> the constructor. It does not forbid <em>detecting</em> a call to
 * it, and the sweep #277 ran by hand — twice, because its own first grep missed seven sites
 * in four files spelled with the type fully qualified — is a walk of the source tree and a
 * regex. {@code EveryStandInGoesThroughTheDoorTest} in {@code agentkit-pentest} runs it on
 * every build, against a list of twelve files whose role is to construct the record
 * directly — this one and the record's own factory, the tests of what the constructor
 * freezes and what it refuses, and history built as <em>input</em> to a component under test
 * rather than produced by a fake in place of a provider's turn — and each entry carries the
 * reason it is on the list. So the enforcement is <em>structural after the fact</em>: the
 * constructor is still reachable, and reaching for it outside that list is a red build
 * rather than a note nobody read.
 *
 * <p>The rest of that paragraph was right and is kept. What makes the drift catchable rather
 * than invisible is the other half of #277: every scripted client in every module now
 * reaches for a {@code toolUse} factory that goes through this door, and the two fakes that
 * kept a second, non-conforming door — temporal's {@code ScriptedLlm} had three — have one
 * again. Writing the constructor by hand is a deliberate act rather than the path of least
 * resistance.
 */
public sealed interface ProposedCall extends ContentBlock
        permits ToolUseBlock, UnusableToolUseBlock {

    /**
     * The tool-call id, echoed back on the matching {@link ToolResultBlock} so a result can
     * be correlated to a call; never {@code null}.
     *
     * <p>Present on both cases, and that is the point of the pair: a refused call still owns
     * an id, still appears in the assistant turn that gets echoed back, and still needs
     * exactly one result block carrying that id.
     */
    String id();

    /** The tool the call named; never {@code null}. */
    String name();

    /**
     * The call {@code input} can be made as, or the refusal it earned instead.
     *
     * <p>The one door for a caller that has a map. A caller that catches
     * {@link ToolUseBlock.UnusableArguments} itself and writes its own sentence is a fifth
     * wording of a rule that has one. A provider adapter that could not build the map is
     * the one caller this signature cannot serve, and it has {@link #unreadable} rather
     * than a sentence of its own (#271).
     *
     * @param id    the tool-call id — the model's own where a provider sent one, or the
     *              runner's where the caller is a script or a workflow graph
     * @param name  the tool named
     * @param input the arguments as proposed; never {@code null}
     */
    static ProposedCall of(String id, String name, Map<String, Object> input) {
        try {
            return new ToolUseBlock(id, name, input);
        } catch (ToolUseBlock.UnusableArguments refused) {
            return UnusableToolUseBlock.of(refused);
        }
    }

    /**
     * The refusal a call earns when the adapter could not build {@code input} at all (#271).
     *
     * <p>The same door for the case that never reaches {@link #of}, because there is no map
     * to hand it. A provider sends tool arguments as bytes — a JSON-encoded string on the
     * OpenAI-compatible wire, an SDK {@code JsonValue} on Anthropic's — and turning those
     * into a {@code Map} can fail: an unterminated fragment out of a truncated stream, a
     * bare scalar, an array where an object was required.
     *
     * <p><strong>What both adapters used to do with that is run the tool with no
     * arguments.</strong> {@code AnthropicLlmClient.toArgumentMap} caught
     * {@link RuntimeException} and returned {@code Map.of()};
     * {@code OpenRouterLlmClient.parseArguments} caught Jackson and did the same, under a
     * test named {@code malformedToolArgumentsBecomeEmptyRatherThanCrashing}. So a gate
     * judged {@code {}}, a tool received {@code {}}, and neither was what the model sent —
     * fail-open at an authorization boundary, and not a no-op for a {@code list}, a
     * {@code search}, a {@code read} with a defaulted path, or a delete whose selector
     * defaults to everything.
     *
     * <p>It is the same fact as a bound failure — <em>this framework cannot carry what you
     * sent</em> — so it comes back the same way: an {@link UnusableToolUseBlock} with no
     * {@code input()} for anything to gate or run, carrying the one wording, which
     * {@link ToolUseBlock.UnusableArguments#unreadable} varies only in the remedy.
     *
     * @param id    the tool-call id the provider sent, or the runner's own where it minted
     *              one
     * @param name  the tool the call named
     * @param cause whatever the adapter's conversion threw; never {@code null}
     */
    static UnusableToolUseBlock unreadable(String id, String name, Throwable cause) {
        return UnusableToolUseBlock.of(
                ToolUseBlock.UnusableArguments.unreadable(id, name, cause));
    }
}
