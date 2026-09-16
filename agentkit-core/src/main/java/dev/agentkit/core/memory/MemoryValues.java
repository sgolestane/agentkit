package dev.agentkit.core.memory;

import dev.agentkit.core.util.Quoted;
import java.nio.charset.CharacterCodingException;

/**
 * Why a {@link MemoryStore} value UTF-8 cannot carry is refused, said once for both stores.
 *
 * <p>{@link MemoryKeys} is this class's opposite number, and the pair is the distinction
 * {@code MemoryStore} draws between the two arguments of {@code write}: a key is a name, a
 * value is a body. Almost everything {@code MemoryKeys} does to a name — strip it,
 * normalise it, refuse it for a line terminator — is exactly what must not happen to a
 * body. One rule crosses over, and it is the same rule for the same reason: a value has to
 * survive being written down, so half a surrogate pair is refused rather than substituted.
 *
 * <h2>Why this is a refused argument rather than a failed write (#245)</h2>
 *
 * <p>{@code MemoryStore} names two failure channels and says what each is for.
 * {@link IllegalArgumentException} carries a reason the caller can act on by choosing
 * differently, and is the only thing {@code MemoryTools} shows the model; everything else is
 * the medium refusing and reaches the model as "Memory operation failed."
 *
 * <p>An unpaired surrogate in a value is squarely the first kind. The model chose the
 * string and can choose another. Both stores used to raise {@code UncheckedIOException} for
 * it — {@link FileMemoryStore} because the encoder throws a {@link CharacterCodingException}
 * and every other {@code IOException} on that path really is the medium, and
 * {@code InMemoryMemoryStore} because copying its sibling exactly is what let the
 * differential fuzzer assert plain agreement rather than an exemption. Copying the wrong
 * channel is still the wrong channel, and a store with no medium under it announcing that
 * its medium refused was the clearest sign of it.
 *
 * <h2>Why the message is built here rather than twice</h2>
 *
 * <p>Naming the offending code point is more than a string constant — it is a scan, and two
 * spellings of one scan can drift. That is {@code MemoryNamespace}'s argument for the
 * hierarchy rule and {@code MemoryKeys}' for the key rule, applied to the third thing both
 * stores owe. The encoders themselves stay where they are: {@link FileMemoryStore} needs the
 * bytes it produces, and {@code InMemoryMemoryStore} needs only the refusal.
 */
final class MemoryValues {

    private MemoryValues() {
    }

    /**
     * The refusal for a value UTF-8 cannot carry, in the vocabulary the caller wrote in.
     *
     * <p>Which code point, escaped, and what a value has to be — the shape
     * {@link MemoryKeys}' length refusal uses, for the same audience. A model told only
     * that an encoding failed has been refused in units it never used; told
     * {@code \uD800}, it can find the character it chose and drop it.
     *
     * <p><strong>The code point, not the value.</strong> A value is a body — the largest and
     * least trustworthy thing the store holds — and this message goes straight to the model
     * through {@code MemoryTools}. Echoing the value would put an unbounded, model-chosen
     * string into a tool result on the failure path, which is what a store never does with a
     * body. Escaping it is not optional either: raw, the refusal would carry the very
     * unpaired surrogate it exists to refuse into whatever serialises the tool result.
     *
     * @param value the string that could not be encoded
     * @param why   what the strict encoder reported
     */
    static IllegalArgumentException cannotBeWrittenDown(String value,
                                                        CharacterCodingException why) {
        int offending = firstUnpairedSurrogate(value);
        if (offending < 0) {
            // UTF-8 encodes every code point that is not half a surrogate pair, so nothing
            // reaches this today: the scan below finds whatever the encoder refused. It is
            // here because the alternative is a message that names a cause it did not
            // check, and a refusal that says the wrong thing is worse than one that says
            // less. No mutant can kill this line; it is not a second control.
            //
            // Escaped like everything else that reaches this channel. What the encoder
            // reports is "Input length = 1" and nothing a caller chose, so there is nothing
            // to escape today either -- but the rule this class states one line down is
            // that a model-facing message is escaped, and a branch exempting itself from it
            // is how the rule stops being one.
            return MemoryRefusal.VALUE_IS_NOT_TEXT.of(
                    "value must be text UTF-8 can carry: " + Quoted.of(String.valueOf(why)),
                    why);
        }
        return MemoryRefusal.VALUE_IS_NOT_TEXT.of(
                "value must not contain an unpaired surrogate: '"
                        + Quoted.of(new String(Character.toChars(offending)))
                        + "' — a value must be text UTF-8 can carry", why);
    }

    /**
     * The first code point of {@code value} that is half a surrogate pair, or {@code -1}.
     *
     * <p>{@code codePoints()} is what makes this answer at all: a legal pair arrives from it
     * as one code point above {@code U+FFFF} and never as its two halves, so only a half
     * with no other half is ever seen as a surrogate.
     */
    private static int firstUnpairedSurrogate(String value) {
        return value.codePoints().filter(Quoted::isUnpairedSurrogate).findFirst().orElse(-1);
    }
}
