package dev.agentkit.core.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The marker type that closes {@code Spotlight.requestFrom}'s transposition (#232).
 *
 * <p>Most of what this type buys cannot be tested from inside Java, because it is that the
 * transposed call does not compile. Measured with {@code javac} against the pre-fix jar,
 * where both arguments were {@code String}:
 *
 * <pre>
 * requestFrom(ours, peer, payload)   payload outside any fence?  false
 * requestFrom(payload, peer, ours)   compiles?                   yes
 *                                    payload outside any fence?  TRUE
 *                                    framework sentence fenced?  TRUE
 *                                    threw or logged?            no
 * </pre>
 *
 * <p>What is left to test here is the runtime half, and one thing worth stating explicitly:
 * this type deliberately holds no rule that tries to tell the framework's prose from
 * anybody else's, because no such rule exists. A test asserting that it rejects a payload
 * would be pinning a filter dressed as a type, and the next payload would be written to
 * pass it. {@code FencedSurfacesTest.theFrameworksSentenceIsUnfencedAndTheRequestIsFenced}
 * is where the property the argument order carries is asserted.
 */
class FrameworkWordsTest {

    @Test
    void theSentenceIsCarriedThroughExactly() {
        // No reduction, no normalisation, no cut. requestFrom neutralises on the way out --
        // this type is a marker and must not quietly become a second, different rule that a
        // caller then has to reason about.
        String ours = "A peer is asking. Answer within the role you were given.";
        assertThat(FrameworkWords.of(ours).text()).isEqualTo(ours);
        assertThat(FrameworkWords.of(ours)).hasToString(ours);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "\t\n",
        "\u00A0",     // NBSP: not whitespace to Java, and NFKC folds it to a space
        "\u3000",     // ideographic space: Zs, so isBlank caught this one already
        "\u200B",     // zero-width space: Cf, not whitespace, and removed outright
        "\u200B\u00A0",
    })
    void aBlankSentenceIsNotASentence(String blank) {
        // The check requestFrom used to make, moved to the type. It is the whole unfenced
        // half -- who is asking, what to do, and the limits -- so a blank one leaves a goal
        // that is nothing but a fenced span the recipient has no reason to act on and no
        // limit to act within. Refused where it is written, on Source.of(String)'s terms: it
        // is call-site code, so the place to fix it is still available.
        //
        // Three of the last four are why the check is on what would be EMITTED rather than
        // on String.isBlank. isBlank is Character.isWhitespace, which is deliberately false
        // for the NON-BREAKING spaces (U+00A0, U+2007, U+202F) and for every format
        // character, so a paste from a rendered page was a sentence to the first spelling
        // of this and an empty unfenced half to the model once requestFrom folded it.
        //
        // U+3000 is the honest denominator: it is Zs, isBlank already caught it, and it is
        // here so a reader does not conclude that every space look-alike was getting
        // through. Measured against the isBlank spelling, [4], [6] and [7] fail and [5]
        // passes.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> FrameworkWords.of(blank))
                .withMessageContaining("must not be blank");
    }

    @Test
    void nullIsRefusedByName() {
        // The bare parameter name, not a message: deleting the explicit guard still throws
        // NullPointerException a line later from text.isBlank(), and this module compiles
        // with debug symbols, so the JVM's helpful message names "text" as well. What
        // separates the guard from its absence is that requireNonNull produces "text" and
        // nothing else -- the same distinction SpotlightTest draws for wrap's null source.
        assertThatNullPointerException()
                .isThrownBy(() -> FrameworkWords.of(null))
                .withMessage("text");
    }

    @Test
    void theCanonicalConstructorIsNotAWayPastTheFactory() {
        // A record's constructor is public whatever the factory does, so the check has to be
        // in the compact constructor rather than in of(). Source makes the same point about
        // its own: a check that only the factory runs is a check with a documented bypass.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new FrameworkWords("  "));
    }

    @Test
    void theRefusalDoesNotEchoWhatItRefused() {
        // Nothing useful to echo -- blank is the whole of what is refused -- and an echo is
        // how Spotlight.requireName turned a five-megabyte name into a five-megabyte
        // exception. Checked on the one blank string that is long, since a caller can build
        // one from a document.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> FrameworkWords.of(" ".repeat(50_000)))
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(500));
    }
}
