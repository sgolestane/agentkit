package dev.agentkit.core.message;

import java.util.Map;
import java.util.Objects;

/**
 * A request from the model to invoke a tool, with arguments this framework can carry.
 *
 * <p>One of the two {@link ProposedCall} shapes, and the only one a runner may gate or run.
 * A call whose arguments are refused becomes an {@link UnusableToolUseBlock} instead, so
 * this type's guarantee is unconditional: if you are holding one, {@link #input()} is a
 * frozen, bounded snapshot of what the model sent. Build one through
 * {@link ProposedCall#of} rather than catching {@link UnusableArguments} at the call site.
 *
 * @param id    unique identifier for this invocation, echoed back on the
 *              matching {@link ToolResultBlock}; never {@code null}
 * @param name  the name of the tool to invoke; never {@code null}
 * @param input the parsed tool arguments as a JSON-like map; never {@code null}.
 *              A defensive, order-preserving, unmodifiable copy is stored, <em>deep</em>
 *              through the JSON shapes — a nested map or list is a snapshot rather than a
 *              shared reference. Null values are permitted (JSON {@code null} arguments).
 *              A value that is not a JSON shape is refused, and every nested
 *              {@link java.util.Collection} is stored as a {@link java.util.List} — see
 *              {@link dev.agentkit.core.util.Frozen} for both.
 */
public record ToolUseBlock(String id, String name, Map<String, Object> input)
        implements ProposedCall {

    public ToolUseBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(input, "input");
        try {
            input = dev.agentkit.core.util.Frozen.deeply(input);
        } catch (IllegalArgumentException refused) {
            // Rethrown naming the call, not repaired. See UnusableArguments (#246).
            throw new UnusableArguments(id, name, refused);
        }
    }

    /**
     * The refusal a tool call earns for arguments this framework will not carry — nesting
     * past {@link dev.agentkit.core.util.Frozen#MAX_DEPTH}, a value that is not a JSON
     * shape, or a structure that exhausts the node budget.
     *
     * <h2>The measured defect: fail-closed, and mute (#246)</h2>
     *
     * <p>An argument tree one level past the depth cap, on a run whose gate denies
     * everything with a reason:
     *
     * <pre>
     *  99 levels  -&gt;  gate consulted, denied, the run finished normally
     * 101 levels  -&gt;  no gate consulted, no tool ran, the run ended ERROR
     * </pre>
     *
     * <p>Nothing escaped and nothing ran unauthorised — the cap is right, and #193 sized it
     * where it is because the recursive walk was losing its race with the stack. What was
     * wrong is <em>where</em> the refusal landed: in this constructor, while a provider's
     * turn was being parsed, before any gate existed to be asked. The in-process loop's
     * catch-all then reported it as "Model call failed" carrying an
     * {@code IllegalArgumentException} that named no tool and no call — so an operator
     * reading the run learned that a hundred-level limit had been hit somewhere, and could
     * not tell which of a turn's calls hit it.
     *
     * <h2>What this fixes, and the half it does not</h2>
     *
     * <p>It fixes the naming. The refusal now carries {@link #id()} and {@link #name()} and
     * a {@link #refusal()} written to be read by a model, so every runner that already
     * catches a {@link RuntimeException} out of the parse reports <em>which</em> call it
     * refused and why, and a runner that wants to do better can catch this type exactly.
     * Nothing is weakened: this is an {@link IllegalArgumentException}, so every existing
     * catch still catches it, and the arguments are still refused rather than carried.
     *
     * <h2>The half that is now closed, and a claim this javadoc had wrong</h2>
     *
     * <p><strong>Corrected rather than deleted, because the reasoning is still worth
     * having.</strong> This section used to say that putting the refusal in front of the
     * model was not available "from inside this constructor", and then reasoned from that to
     * "so it is not available at all". The first half is true and still is — nothing here
     * can correlate a result to a call. The second half does not follow, and it was the
     * step that kept #246 open: it assumed the only two ways to build the turn were to hand
     * a runner an emptied {@code ToolUseBlock} (a gate would judge arguments the tool never
     * received) or a live one over an unfrozen tree (worse). There is a third, and it is
     * what {@link ProposedCall} now does — build the turn with an
     * {@link UnusableToolUseBlock} in this call's place, which is not a call at all: it has
     * no arguments to gate and none to run with, so no gate is asked and no tool is entered,
     * and the refusal travels as an ordinary error result the model can act on. Strictly
     * more fail-closed than throwing here was, because the runner asks one component fewer
     * rather than one more.
     *
     * <p>What survives unchanged is why the throw stays. This constructor still refuses, and
     * still refuses everything it refused before; {@link ProposedCall#of} is the only
     * approved catcher, and what it produces cannot be mistaken for a call. The duplicate-id
     * argument {@link #refusalForRepeatedIds} makes is genuinely different and still holds:
     * there the ids the provider chose have destroyed the correlation itself, so no result
     * block can be written that the next request would accept. Here the id is fine and only
     * the arguments are not.
     *
     * <p>{@link #refusal()} is delivered by four runners — {@code Agent}, {@code
     * AgentWorkflowImpl}, {@code ToolBridges} and itops' {@code WorkflowRunner} — through
     * the one door, which is the shape {@code GateResult.effectiveFor} and
     * {@link #refusalForRepeatedIds} were each written once for.
     */
    public static final class UnusableArguments extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final String why;
        private final String remedy;

        /**
         * The refusal {@code cause} earns, named as a call.
         *
         * <p>Public for the runners that never hold a {@link ToolUseBlock}: a script's
         * arguments and a workflow graph's are refused by {@code ToolInvocation}'s own
         * constructor, which throws {@code Frozen}'s bare {@link IllegalArgumentException}
         * with no call attached. Rather than let those two invent a second sentence, they
         * reach the same wording through {@link ProposedCall#of}, which reaches it through
         * here.
         *
         * @param id    the tool-call id the refused arguments belonged to
         * @param name  the tool the refused call named
         * @param cause {@code Frozen}'s own refusal, whose message names which of the three
         *              bounds fired and what its number is
         */
        public static UnusableArguments of(String id, String name,
                                           IllegalArgumentException cause) {
            return new UnusableArguments(Objects.requireNonNull(id, "id"),
                    Objects.requireNonNull(name, "name"),
                    Objects.requireNonNull(cause, "cause"));
        }

        /**
         * The refusal a call earns when its arguments never became a map at all (#271).
         *
         * <h4>The measured defect: fail-open, one layer above {@link #of}</h4>
         *
         * <p>{@link #of} is raised by {@code Frozen}, which is handed a map and refuses its
         * shape. This one is raised <em>before</em> a map exists: a provider adapter has to
         * turn a wire payload into one, and that conversion can fail. Both adapters used to
         * answer a failed conversion with {@code Map.of()} — {@code AnthropicLlmClient}
         * caught {@link RuntimeException} out of {@code JsonValue.convert}, and
         * {@code OpenRouterLlmClient} caught Jackson out of {@code readValue} under a test
         * named {@code malformedToolArgumentsBecomeEmptyRatherThanCrashing}. So a call whose
         * arguments could not be read ran <strong>with no arguments rather than the
         * model's</strong>: a gate judged {@code {}}, which is not what the model sent, a
         * tool received {@code {}}, which is not what the model asked for, and nobody was
         * told. Empty is not a no-op for a {@code list}, a {@code search}, a {@code read}
         * with a defaulted path, or a delete whose selector defaults to everything.
         *
         * <p>That is the fail-open {@link ProposedCall}'s javadoc rejected when it refused a
         * fourth component on {@link ToolUseBlock} — "an old worker would drop it and run a
         * call with empty arguments — fail-open at an authorization boundary" — still
         * reachable through a conversion one layer up. It goes through the same door now,
         * and the door hands back a block with no arguments to gate and none to run with.
         *
         * <h4>Why the wording differs from {@link #of}'s, and how far</h4>
         *
         * <p>{@link #refusal()} keeps its opening and its middle verbatim — what happened,
         * and that nothing ran — and varies only the remedy, because the two facts ask for
         * different things back. "Flatten the nesting and send only values a JSON document
         * could hold" is advice about a tree that arrived; it is no advice at all to a model
         * whose arguments never parsed. That is one wording with two remedies, not the fifth
         * wording {@link ProposedCall} refuses.
         *
         * <p><strong>The reason the model is told is the framework's own, not the
         * parser's.</strong> Jackson's message quotes the malformed input back and the SDK's
         * names its own types; both are text the far side chose, and this sentence reaches a
         * model unfenced. What a model needs is the fact — its arguments were not readable
         * as a JSON object — and the parser's words add nothing to that it could act on. The
         * cause is still attached for {@link #getCause()}, and {@link #getMessage()} carries
         * a bounded, flattened slice of it for the operator, who is the reader that wants it.
         *
         * @param id    the tool-call id the unreadable arguments belonged to
         * @param name  the tool the refused call named
         * @param cause whatever the adapter's conversion threw
         */
        public static UnusableArguments unreadable(String id, String name, Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            String detail = cause.getMessage();
            return new UnusableArguments(Objects.requireNonNull(id, "id"),
                    Objects.requireNonNull(name, "name"),
                    // Flattened and cut, which the sibling constructor does not need to do:
                    // its cause is Frozen's own one-line sentence, and this one's is a
                    // parser's, which quotes the malformed input back across several lines.
                    // An operator's log line and an audit row are not the place to discover
                    // how long a provider's tool-call arguments were.
                    dev.agentkit.core.util.Cut.to(dev.agentkit.core.util.OneLine.of(
                                    detail == null ? cause.getClass().getName() : detail),
                            MAX_ECHOED_CAUSE_CHARS),
                    cause);
        }

        UnusableArguments(String id, String name, IllegalArgumentException cause) {
            // Quoted on both, for the reason refusalForRepeatedIds quotes the id: the id is
            // the model's own and the tool name is whatever the provider's turn named, and
            // this message reaches a log line and an AgentResult unfenced. Cut for the same
            // reason, so one malformed turn cannot buy an unbounded log record. The cause's
            // message is Frozen's own words and carries the limit.
            super("Tool call " + dev.agentkit.core.util.Cut.to(
                            dev.agentkit.core.util.Quoted.of(id), MAX_ECHOED_ID_CHARS)
                    + " for tool " + dev.agentkit.core.util.Cut.to(
                            dev.agentkit.core.util.Quoted.of(name), MAX_ECHOED_ID_CHARS)
                    + " has arguments this framework will not carry: " + cause.getMessage(),
                    cause);
            this.id = id;
            this.name = name;
            // Frozen's own sentence, kept apart from the operator-facing message so the
            // model-facing one does not have to re-derive which of the three refusals fired.
            this.why = cause.getMessage();
            this.remedy = CANNOT_CARRY;
        }

        /** The unreadable-arguments case; see {@link #unreadable}. */
        private UnusableArguments(String id, String name, String detail, Throwable cause) {
            super("Tool call " + dev.agentkit.core.util.Cut.to(
                            dev.agentkit.core.util.Quoted.of(id), MAX_ECHOED_ID_CHARS)
                    + " for tool " + dev.agentkit.core.util.Cut.to(
                            dev.agentkit.core.util.Quoted.of(name), MAX_ECHOED_ID_CHARS)
                    + " has arguments this framework could not read: " + detail, cause);
            this.id = id;
            this.name = name;
            // The framework's own words rather than the parser's, and the whole of why is
            // in unreadable()'s javadoc: the parser quotes the far side's bytes back, and a
            // model reads this sentence unfenced.
            this.why = "its arguments could not be read as a JSON object";
            this.remedy = CANNOT_READ;
        }

        /** The tool-call id the refused arguments belonged to; never {@code null}. */
        public String id() {
            return id;
        }

        /** The tool the refused call named; never {@code null}. */
        public String name() {
            return name;
        }

        /**
         * The refusal as a model would need to read it: what was refused, that nothing ran,
         * and what to do instead.
         *
         * <p>Separate from {@link #getMessage()} because the two have different readers. The
         * message is an operator's, and names the call so a log line is traceable. This one
         * is a model's, and names neither id nor tool — a model reads it as the result of
         * the call it just made, and echoing its own id back at it spends characters saying
         * what the correlation already said. It is a plain sentence for the same reason
         * every other refusal in this framework is: a model that is told what it did wrong
         * can do something else, and a model that is told "invalid" cannot.
         *
         * <p>Delivered by every runner, through {@link ProposedCall#of} and the
         * {@link UnusableToolUseBlock} it hands back (#246). Written here, once, so that
         * four runners do not invent four wordings — which is why nothing downstream
         * rewrites it, not even the factory that copies it onto the block.
         *
         * <p><strong>One sentence, two remedies (#271).</strong> {@link #unreadable} added
         * a second way to be refused — arguments a provider adapter could not convert into
         * a map at all — and it varies only the tail. What happened, and that nothing ran,
         * is the half every reader of this method depends on and is identical for both;
         * what to send instead is the half that has to differ, because "flatten the
         * nesting" is no advice to a model whose arguments never parsed. See
         * {@link #CANNOT_CARRY} and {@link #CANNOT_READ}.
         */
        public String refusal() {
            // On the CANNOT_CARRY path the reason is Frozen's, verbatim: it is the only
            // place that knows which of the three bounds fired and what its number is, and
            // restating it here would be a second copy to keep in step. It is
            // framework-written text — the one variable part is a class name, from the
            // deployment's own code, and a parsed turn cannot reach that branch at all,
            // since a parser produces nothing but JSON shapes.
            //
            // Narrowed rather than repeated now that this text is delivered (#246). Two of
            // the four runners are not fed by a parse — a sandboxed script in ToolBridges
            // and a resolved workflow variable in itops — and both CAN reach the non-JSON
            // branch, so the class name genuinely appears in what a script and an audit row
            // are shown. It is still the deployment's own code naming itself, which is the
            // half of the sentence that was load-bearing; what is no longer true is the
            // implication that nothing reaches it. The model-facing path is unchanged: a
            // provider's turn is JSON and cannot produce a value with a class to name.
            //
            // On the CANNOT_READ path neither paragraph above applies: the reason
            // is this class's own words and never the parser's, which is the one place the
            // two doors differ in kind rather than in wording. unreadable()'s javadoc says
            // why, and it is why no bound is needed on this half of the sentence.
            return "That call was refused before anything ran, because " + why
                    + ". No tool ran, no gate was asked, and nothing was decided about the"
                    + " call." + remedy;
        }

        /**
         * What to send instead when {@code Frozen} refused the shape of a map that did
         * arrive.
         *
         * <p>The {@code MAX_DEPTH} figure is read here rather than restated, for the same
         * reason the reason is: {@code Frozen} owns the number.
         */
        private static final String CANNOT_CARRY =
                " Reissue it with arguments this framework can carry: flatten the nesting —"
                + " a tool argument has no use for nesting anywhere near "
                + dev.agentkit.core.util.Frozen.MAX_DEPTH + " levels deep — and send only"
                + " values a JSON document could hold.";

        /**
         * What to send instead when no map arrived at all (#271).
         *
         * <p>Separate from {@link #CANNOT_CARRY} because that one is advice about a tree,
         * and a model whose arguments never parsed has no tree to flatten. Naming the shape
         * — one JSON object, keyed by parameter name — is the only thing here a model can
         * act on, and it is what both failing conversions were asked for and did not get:
         * an unterminated fragment, a bare scalar, an array.
         */
        private static final String CANNOT_READ =
                " Reissue it with arguments this framework can read: a single JSON object"
                + " whose keys are the tool's parameter names.";

        /**
         * How much of a conversion failure's own message {@link #getMessage()} echoes.
         *
         * <p>Bounded because the message is a parser's and a parser quotes back what it was
         * handed. Not the model's business either way — see {@link #unreadable} — so this
         * bound is about an operator's log line and an audit row rather than about a
         * context window, which is why it is smaller than anything measured in tokens.
         */
        private static final int MAX_ECHOED_CAUSE_CHARS = 400;
    }

    public static ToolUseBlock of(String id, String name, Map<String, Object> input) {
        return new ToolUseBlock(id, name, input);
    }

    /**
     * The refusal a turn earns for naming one call twice, or empty when its ids are
     * distinct.
     *
     * <p>"One call twice" is decided by {@link #confusableAs} rather than by
     * {@code String.equals}, because what this protects is not itself — it is whatever else
     * keys on the id, and that is usually looser.
     *
     * <p>Nothing requires a model to make them distinct, and nothing checked. Two
     * {@code publish} calls in one turn, both with id {@code t1}, both executed — and a
     * gate implementing the ordinary "do not ask the human twice about the same call"
     * dedupe saw one call where there were two, so the second ran with the model's own
     * arguments and was never reviewed. Measured; the tool ran, and both result blocks came
     * back carrying {@code t1}, so the transcript could not tell them apart either.
     *
     * <p>Refused rather than repaired, because there is nothing to repair with. The wire
     * format correlates a result to a call <em>by id</em>, so a turn that reuses one has
     * already destroyed the correlation: running the first and refusing the rest would hand
     * back two blocks the caller cannot distinguish, and renumbering would invent a
     * correlation the provider did not send.
     *
     * <p><strong>And the run stops, because it cannot continue.</strong> The first version
     * of this said the run would carry on so the model could reissue, which was true
     * against the fakes the tests use and false against a provider: the framework does not
     * own the assistant turn and echoes it back verbatim, duplicate ids and all, so the
     * next request is invalid however carefully the results are written. Anthropic rejects
     * it, and {@code RetryingLlmClient} has no status classification, so a permanently
     * invalid request would be retried to exhaustion and the run would die reporting
     * something else. The argument against partial execution — "the caller cannot
     * distinguish them" — condemns a partial repair just as squarely, which the first
     * version asserted past.
     *
     * <p>Stated once because there are two agent loops. An authorization-shaped rule they
     * come to disagree about is what #58 was, and this is the third time that shape has come
     * up — after {@code GateResult.effectiveFor} and {@code ToolResult.attributedTo}.
     *
     * <p><strong>Every proposed call in the turn, not only the runnable ones (#246).</strong>
     * A call whose arguments were refused still owns an id, still appears in the assistant
     * turn that is echoed back, and still gets exactly one result block carrying that id —
     * so a turn pairing a runnable {@code t1} with a refused {@code t1} would put two blocks
     * with one id in front of a provider, which is the whole defect this method exists to
     * refuse. Taking {@link ProposedCall} rather than {@link ToolUseBlock} is what makes
     * that unmissable: a runner cannot pass the list it happens to have kept.
     */
    public static java.util.Optional<String> refusalForRepeatedIds(
            java.util.List<? extends ProposedCall> uses) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ProposedCall use : uses) {
            if (!seen.add(confusableAs(use.id()))) {
                // The id, not the tool name: the id is the model's own and is what it must
                // change. Escaped by distinguishably() so that it survives being read, and
                // cut so one turn cannot buy a hundred megabytes of transcript.
                return java.util.Optional.of(
                        "This turn used the tool-call id '" + distinguishably(use.id())
                                + "' more than once. Each tool call needs its own id, because"
                                + " a result is matched to a call by it. No tool was run;"
                                + " reissue the calls with distinct ids.");
            }
        }
        return java.util.Optional.empty();
    }

    /** How much of a rejected id the refusal echoes back. */
    private static final int MAX_ECHOED_ID_CHARS = 120;

    /**
     * The rejected id, in a spelling that still names it after a fence has been over it
     * (#152).
     *
     * <p><strong>The measured defect.</strong> This refusal is the framework's own
     * diagnostic about characters — {@link #confusableAs} refuses two ids <em>because</em>
     * some looser reader would fold them together — and it reached a model with the folding
     * already applied to it. {@code Agent} turns it into
     * {@code AgentResult.failed(IllegalStateException(refusal))}; where that agent is a
     * subagent, {@code SubagentTools.delegate} fences the message, and
     * {@code Spotlight.neutralise} runs NFKC over the fenced body. Driven end to end with
     * the ids {@code t1} and {@code U+FF54 U+FF11}:
     *
     * <pre>
     * refusal as thrown : This turn used the tool-call id 'ｔ１' more than once...
     * as the model reads: This turn used the tool-call id 't1' more than once...
     * </pre>
     *
     * <p>So the supervisor was shown a sentence that is <em>false as read</em>: it used
     * {@code t1} once and {@code ｔ１} once, and was told it used {@code t1} twice. The one
     * fact the diagnostic exists to convey — that two ids collided under normalisation —
     * was destroyed by the normalisation. Exactly the pair {@code cafe\\u0301} /
     * {@code caf\\u00E9}, which {@code AgentTest} already refuses, fails the same way.
     *
     * <p><strong>Escaped here rather than exempted at the fence.</strong> #152 weighed an
     * escaping mode inside {@code Spotlight} and it does not survive: an escaper over a
     * <em>body</em> has no character it can double the way {@code Quoted} doubles
     * {@code \}, so a detail that already spells {@code U+FF08} in ASCII is indistinguishable
     * from the escaper's rendering of {@code （} — a pre-encoded payload masquerading as the
     * escaper's own output, which is the defect {@code Spotlight.neutralise}'s javadoc
     * records and, being ASCII, a fixed point that then survives every later pass. The fence
     * stays unconditional and the party that knows its content is about characters says so
     * before the fence sees it, which is this method.
     *
     * <p><strong>The same shape as its two siblings, and it is the difference that
     * matters.</strong> {@code MemoryKeys.normalize} refuses a key naming the line or
     * formatting character in it, and {@code MemoryValues.cannotBeWrittenDown} refuses a
     * value naming the unpaired surrogate; both echo through {@code Quoted.of} and both
     * come through a fence intact, because {@code Quoted.of}'s alphabet is exactly the class
     * of character each of them is complaining about. This one complains about a folding
     * that is NFKC's, which that alphabet does not cover — so escaping at the source was
     * already the rule here and this method is what makes it cover the right pass.
     *
     * <p><strong>The mechanics are {@code Quoted.distinguishably}'s now (#278), and nothing
     * about them changed.</strong> Four runners echo a proposer-chosen tool name into an
     * unknown-tool refusal that lands inside single quotes and travels through the same
     * fence, so they need this exact pass and there is no reason for a second copy of it. What
     * stays here is why an <em>id</em> needs it, which is the half a general helper cannot
     * say. Escaping to ASCII rather than to "what NFKC would change", bounding on both
     * sides of an escaping that never shrinks, the surrogate-pair residual at the cut, and
     * why the apostrophe is escaped last and separately, are all documented there.
     */
    private static String distinguishably(String id) {
        return dev.agentkit.core.util.Quoted.distinguishably(id, MAX_ECHOED_ID_CHARS);
    }

    /**
     * {@code id} reduced to the form two ids have to differ in to be different calls.
     *
     * <p>Wider than {@code equals}, deliberately, because the thing being protected is not
     * this check — it is whatever <em>else</em> keys on the id, and that is usually looser.
     * A dedupe that trims, or folds case, or normalises before comparing is defeated by two
     * ids equal under its relation and distinct under this one: {@code "call_1"} and
     * {@code "call_1 "} both run, and the reviewer is asked once. That is #119 again, one
     * equality looser, so the check has to be at least as loose as any consumer plausibly
     * is rather than exactly as strict as {@code String}.
     *
     * <p>NFKC, then format characters out, then stripped, then case-folded. Refusing a pair
     * that some consumer would have told apart costs a retry with genuinely distinct ids,
     * which a model can always produce; missing a pair that some consumer conflates costs a
     * call nobody reviewed. Only the first of those is recoverable.
     */
    private static String confusableAs(String id) {
        String normalised = java.text.Normalizer.normalize(id, java.text.Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(normalised.length());
        normalised.codePoints().forEach(cp -> {
            if (Character.getType(cp) != Character.FORMAT) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString().strip().toLowerCase(java.util.Locale.ROOT);
    }
}
