package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CutTest {

    @Test
    void textThatFitsIsReturnedUntouched() {
        assertThat(Cut.to("short", 5)).isEqualTo("short");
        assertThat(Cut.to("short", 500)).isEqualTo("short");
        assertThat(Cut.to("", 1)).isEmpty();
    }

    @Test
    void aCutSaysThatItCut() {
        // Text that simply stops reads to a model as text that ended, so a cut plan looks
        // like a complete one. The marker is inside the cut span rather than in a header
        // because a reader of one entry among many needs to know which entry lost its tail.
        assertThat(Cut.to("abcdef", 3)).isEqualTo("abc" + Cut.MARKER);
    }

    @Test
    void aCutNeverLandsBetweenASurrogatePair() {
        // Not theoretical: a working note of emoji cut at a fixed index ended on a lone high
        // surrogate, and the UTF-8 round trip of that string is not the identity — it is not
        // text any more, and an audit or a JSON encoder reading it next is holding something
        // malformed. Spotlight.label may cut without this guard only because its allowlist
        // has already replaced every astral character with ASCII.
        String emoji = new String(Character.toChars(0x1F600));
        for (int max = 1; max <= 8; max++) {
            String cut = Cut.to(emoji.repeat(8), max);
            assertThat(new String(cut.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .as("a cut at %d split a surrogate pair", max).isEqualTo(cut);
        }
        // An odd ceiling lands mid-pair, so the pair goes rather than half of it.
        assertThat(Cut.to(emoji.repeat(8), 3)).isEqualTo(emoji + Cut.MARKER);
        assertThat(Cut.to(emoji.repeat(8), 4)).isEqualTo(emoji.repeat(2) + Cut.MARKER);
    }

    @Test
    void aLoneLowSurrogateIsNotAPairAndIsNotStepppedOver() {
        // The guard asks about the character before the cut, not after it, so a cut that
        // lands after an unpaired low surrogate keeps it — it was already lone text, and
        // dropping a further character would be the wrong repair for the wrong problem.
        assertThat(Cut.to("a\uDC00bc", 2)).isEqualTo("a\uDC00" + Cut.MARKER);
    }

    @Test
    void aCeilingOfNothingIsARefusal() {
        // Zero would mean "keep nothing and say so", which is a marker and no text — a
        // caller asking for it has computed a budget wrong, and silently returning the
        // marker alone would hide that.
        assertThatThrownBy(() -> Cut.to("abc", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cut.to("abc", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cut.to(null, 5)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("what a cut appends survives being sized as it will be fenced")
    void theMarkerIsStableUnderTheNormalisationTheCallersApplyNext() {
        // #108. Cut.MARKER was "\u2026 [truncated]", and U+2026 has an NFKC compatibility
        // decomposition to three periods — so the marker this class appends was itself the
        // one thing Spotlight.sizedAsFenced would go on expanding, in text that had just
        // been sized precisely so the number would hold.
        assertThat(dev.agentkit.core.prompt.Spotlight.sizedAsFenced(Cut.MARKER))
                .as("the cut marker expanded under the pass its callers apply next")
                .isEqualTo(Cut.MARKER);
    }

    @Test
    @DisplayName("cut, size, and the number cut to is the number that arrives")
    void theSeamHoldsForEveryShapeTheFuzzFound() {
        // The invariant two callers are written against — MessagingTools.fenced and
        // BlackboardTools.render both do cut -> sizedAsFenced -> cut *because* that
        // ordering is supposed to make the emitted length knowable — and nothing asserted
        // it. The review that found #108 fuzzed 40,000 cases and reported the worst growth
        // anywhere was 2, with the cut marker as the only cause; these are the shapes it
        // named. The list is not load-bearing — #108 is a property of the marker, so most
        // of these shapes catch it on their own and the fixed-point assertion below is
        // what does the work. They are here so the seam is exercised against the inputs
        // that are known to expand, not to claim each one is necessary.
        //
        // Cut twice, which is the seam. A first draft of this asserted that one cut then
        // sizing stays within the bound, and that is not the claim and never was: a body
        // holding its own ellipsis, or U+FDFA, expands under NFKC — this class's own
        // javadoc says "a ceiling here is a ceiling on the text, not on what it costs once
        // fenced". The second cut is what bounds it. What #108 broke is narrower and worse:
        // the marker *the cut itself appends* was the one thing that went on expanding
        // after the sizing, so the number the caller cut to was never the number that
        // arrived.
        List<String> shapes = List.of(
                "g",                          // the fuzz's own worst case, once cut
                "\u2026",                     // an ellipsis the *body* supplied
                "\uFDFA",                     // the 18x NFKC expander
                "\uFF1C",                     // fullwidth '<', a marker look-alike
                "<untrusted id=\"deadbeef\"", // a marker fragment
                "a\u200Bb",                   // U+200B ZERO WIDTH SPACE, which neutralise removes
                "e\u0301",                    // a combining mark
                "\uD83D\uDE00",               // astral, so the cut must not split a pair
                "plain text");

        for (String shape : shapes) {
            for (int max : new int[] {1, 2, 3, 7, 13, 14, 40}) {
                String sized = dev.agentkit.core.prompt.Spotlight.sizedAsFenced(
                        Cut.to(shape.repeat(20), max));
                String emitted = Cut.to(sized, max);
                // The fixed-point form, which is the claim. A first draft asserted
                // emitted.length() <= max + MARKER.length() and that is a *tautology*:
                // Cut.to returns either its input or prefix(<= max) + MARKER, so it holds
                // for any marker whatsoever. Measured — reverting the marker to the
                // ellipsis left this test green while only its four-line sibling failed.
                //
                // What wrap() actually does to the body is run neutralise over it again.
                // So the question is whether the emitted text is a fixed point of that,
                // and under the old marker it was not: 4.75 million of 8 million fuzzed
                // cases failed this, every one of them by exactly the two characters the
                // ellipsis expands to.
                assertThat(dev.agentkit.core.prompt.Spotlight.sizedAsFenced(emitted))
                        .as("what the fence emits is not what was cut to:"
                                + " shape %s at max %d", shape, max)
                        .isEqualTo(emitted);
                // And the marker the cut appended is still last, rather than half-expanded
                // into the bound.
                if (emitted.length() > max) {
                    assertThat(emitted).endsWith(Cut.MARKER);
                }
            }
        }
    }

    @Test
    @DisplayName("a cut says it cut, at the length where no comparison can tell")
    void boundedAnswersWhereLengthsCannot() {
        // #155. Every caller that reported a cut re-derived it from lengths, and both
        // spellings are wrong somewhere. '!=' is wrong here: at exactly max + MARKER.length()
        // the result is the length its input was, so equality reads "nothing was dropped"
        // over a body that lost a marker's worth of text.
        String exactlyAmbiguous = "y".repeat(40 + Cut.MARKER.length());
        Cut.Bounded bounded = Cut.bounded(exactlyAmbiguous, 40);

        assertThat(bounded.text()).hasSize(exactlyAmbiguous.length());
        assertThat(bounded.cut())
                .as("the one length at which a comparison cannot tell, and it told wrong")
                .isTrue();

        // The plain cases, and to() is the same cut so the two cannot drift.
        assertThat(Cut.bounded("short", 40).cut()).isFalse();
        assertThat(Cut.bounded("short", 40).text()).isEqualTo("short");
        assertThat(Cut.bounded("z".repeat(90), 40).cut()).isTrue();
        assertThat(Cut.bounded("z".repeat(90), 40).text()).isEqualTo(Cut.to("z".repeat(90), 40));
        // Including the surrogate guard, which is the other thing to() promises.
        String astral = "\uD83D\uDE00".repeat(30);
        assertThat(Cut.bounded(astral, 41).text()).isEqualTo(Cut.to(astral, 41));
        assertThatThrownBy(() -> Cut.bounded("abc", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cut.bounded(null, 5)).isInstanceOf(NullPointerException.class);
    }
}
