package dev.agentkit.core.prompt;

import java.util.Objects;

/**
 * A sentence <em>this framework</em> wrote, so it cannot be swapped with somebody else's
 * prose (#232).
 *
 * <h2>The transposition, measured</h2>
 *
 * <p>{@code Spotlight.requestFrom(String instruction, Source, String request, int)} put the
 * framework's own words unfenced and somebody else's inside the fence. Both were
 * {@code String}, so swapping them compiled. Measured on the pre-fix branch, with an
 * 81-character payload and the instruction {@code "A peer is asking. Answer within your
 * configured role."}:
 *
 * <pre>
 * requestFrom(ours, peer, payload)   payload outside any fence?  false
 * requestFrom(payload, peer, ours)   compiles?                   yes
 *                                    payload outside any fence?  TRUE
 *                                    framework sentence fenced?  TRUE
 *                                    threw or logged?            no
 * </pre>
 *
 * <p>That is a total inversion of the control: the payload lands on the line
 * {@link Spotlight#INSTRUCTION} tells the model is the framework's, the framework's own
 * sentence — the one carrying the recipient's limits — lands inside a
 * {@link Spotlight.Kind#PROCEDURE} fence the recipient is told to carry out, and nothing
 * throws and nothing logs. It is the same defect and the same magnitude as the one #69
 * closed on {@code Spotlight.wrap}; #69 could not close this one, because it typed the
 * label and these two arguments are neither of them a label.
 *
 * <h2>This type carries no validation rule, and that is not a shortcut</h2>
 *
 * <p>{@code wrap} was tractable because its two arguments are <em>different kinds of
 * thing</em> — a label is a short identifier, a body is arbitrary text — so a type that
 * admits one and refuses the other exists, and it is {@link Source}. Here both arguments
 * are prose written for a model to read. No rule separates "the framework's sentence naming
 * who is asking and what the limits are" from "a peer's request"; anything that admits the
 * first admits the second. Inventing a rule that happened to reject today's payloads would
 * be a filter dressed as a type, and the next payload would be written to pass it.
 *
 * <p>So this checks only what is genuinely checkable — that there <em>is</em> a sentence —
 * and closes the transposition the way {@code Source} actually closes its own: with the
 * type, not the rule. {@code Source}'s validation is not what stops
 * {@code wrap(text, source)} either; that a {@code String} is not a {@code Source} is.
 * Being honest that this is a marker is better than a rule that reads as a defence.
 *
 * <p>What it buys, exactly: the swap does not compile, and the call site says in its own
 * text which half is ours. What it does not buy: nothing stops a caller passing
 * {@code FrameworkWords.of(somethingAModelWrote)}, and {@code requestFrom} therefore still
 * neutralises this text before emitting it unfenced.
 *
 * <h2>Alternatives measured and rejected</h2>
 *
 * <ul>
 *   <li><em>A builder</em> — {@code Request.from(source).asking(request).under(instruction)
 *       .boundedTo(n)} — which names each argument at the call site. Rejected on cost per
 *       benefit: it closes exactly what this closes, on two call sites, in four calls
 *       instead of one, and it puts a partially-built request in reach of a caller that
 *       forgets {@code boundedTo} — a bound that can be omitted is the defect
 *       {@code fenceBounded} was written for.</li>
 *   <li><em>Reorder so the two prose arguments are not adjacent.</em> Rejected: it lowers
 *       the chance of a slip without removing it, and the whole argument #69 was accepted
 *       on is that a transposition nothing can detect is not a thing to make less likely.</li>
 *   <li><em>Leave a {@code String} overload beside this one.</em> Rejected for #69's
 *       reason: an overload that still compiles when transposed is a longer spelling of the
 *       same defect. The old form is removed, not deprecated.</li>
 *   <li><em>Fold it into {@code Source}.</em> Rejected: a {@code Source} says <em>where
 *       content came from</em> and is routinely built from outside data; this says
 *       <em>we wrote this</em>. Giving one type both meanings would put an
 *       attacker-influenceable value in the type whose whole claim is that it is not one.</li>
 * </ul>
 */
public record FrameworkWords(String text) {

    /**
     * Checks the only thing about framework words that is checkable: that there are some.
     *
     * <p>Throwing rather than reducing, and on the same terms as {@link Source#of(String)}:
     * these are written at the call site by whoever wrote the call site, so a bad one is a
     * programming error and the place it was written is still available to fix. This is
     * <em>not</em> the {@code Source} qualifier case, where a value arrives from outside and
     * throwing would let a hostile document crash the run.
     *
     * <p><strong>Blank is measured on what would be emitted, not on what was passed.</strong>
     * {@code String.isBlank} was the first spelling and it let two shapes through, both
     * found by a test written against it:
     *
     * <ul>
     *   <li>{@code isBlank} is {@code Character.isWhitespace}, which is deliberately false
     *       for the <em>non-breaking</em> spaces — U+00A0, U+2007, U+202F. A U+00A0, which
     *       is what a paste from a rendered page yields, was therefore a sentence to the old
     *       check, and {@code Spotlight.requestFrom} then NFKC-folded it to an ordinary
     *       space and emitted a goal that was nothing but a fenced span after all. (The
     *       <em>breaking</em> look-alikes are not this bug: U+3000 is {@code Zs} and
     *       {@code isBlank} already catches it.)</li>
     *   <li>A format character is not whitespace at all. U+200B is {@code Cf}, so
     *       {@code isBlank} says no and {@code neutralise} deletes it outright.</li>
     * </ul>
     *
     * <p>Checking {@link Spotlight#sizedAsFenced}, which is the same pass
     * {@code requestFrom} applies before emitting, refuses exactly the strings that would
     * emit nothing rather than the strings one predicate happens to call blank. It also
     * costs the fold twice on the way to a fence; that is paid on call-site literals, which
     * is where this text comes from.
     *
     * <p>The echo carries none of the text. There is nothing worth echoing — what is refused
     * is invisible by construction — and a refusal that quoted its argument would be the
     * five-megabyte exception {@code Spotlight.requireName} records against itself.
     */
    public FrameworkWords {
        Objects.requireNonNull(text, "text");
        if (Spotlight.sizedAsFenced(text).isBlank()) {
            // Blank is refused where it is written rather than at the point of use. In
            // requestFrom the instruction is the whole unfenced half — who is asking, what
            // to do, and the limits — so a blank one leaves a goal that is nothing but a
            // fenced span the recipient has been given no reason to act on and no limit to
            // act within. That check used to live in requestFrom; it belongs to the type,
            // because a blank sentence is not a sentence wherever it is passed.
            throw new IllegalArgumentException(
                    "framework words must not be blank: they are the unfenced half of what"
                    + " the recipient reads, naming who is asking and what the limits are");
        }
    }

    /** The framework's own sentence, written at the call site. */
    public static FrameworkWords of(String text) {
        return new FrameworkWords(text);
    }

    /** The sentence, so it can be printed where one is expected. */
    @Override
    public String toString() {
        return text;
    }
}
