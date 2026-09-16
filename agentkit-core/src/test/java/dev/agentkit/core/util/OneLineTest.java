package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariant five call sites depend on, tested where it lives.
 *
 * <p>It was only ever tested through them, which is the wrong way round for a class that
 * exists to be the one place this is decided: a gap here is a gap in every listing at once.
 */
class OneLineTest {

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029"})
    void everyUnicodeLineTerminatorCollapses(String terminator) {
        // \R is the whole set. \n alone leaves five of these standing, and \s leaves two —
        // which is why neither is what this uses.
        String collapsed = OneLine.of("first" + terminator + "second");

        assertThat(collapsed).isEqualTo("first second");
        assertThat(java.util.regex.Pattern.compile("\\R").matcher(collapsed).find()).isFalse();
    }

    @Test
    void runsOfWhitespaceBecomeOneSpaceAndTheEndsGo() {
        assertThat(OneLine.of("  a \t\t b  ")).isEqualTo("a b");
        assertThat(OneLine.of("a\n\n\nb")).isEqualTo("a b");
    }

    @Test
    void whatItDoesNotTouch() {
        // \s is ASCII-only, so these survive — and that is fine here, because none of them
        // can end a line. A caller that needs "is there anything to read" has to ask a
        // wider question; BlackboardTools and WorkingMemory both do.
        assertThat(OneLine.of("a\u00A0b")).isEqualTo("a\u00A0b");
        assertThat(OneLine.of("\u00A0")).isEqualTo("\u00A0");
        // Not a line terminator either, but it does render as one in some viewers — worth
        // pinning so a future reader knows it was considered rather than missed.
        assertThat(OneLine.of("a\u001Cb")).isEqualTo("a\u001Cb");
    }

    @Test
    void nullIsAProgrammingErrorRatherThanAnEmptyLine() {
        // Every call site passes non-null. Quietly returning "" would put a blank entry in
        // a listing instead of failing where the bug is.
        assertThatThrownBy(() -> OneLine.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void thisFileCarriesNoRawSeparator() throws Exception {
        // The assertion an earlier PR description claimed existed and did not. A raw
        // U+2028 in a source file is invisible in review and survives a green build — the
        // same shape as the NUL byte that made a test file a binary blob two PRs ago.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/java/dev/agentkit/core/util/OneLineTest.java"));

        assertThat(source.codePoints().filter(cp -> cp != '\n' && cp != '\r')
                .filter(cp -> Character.isISOControl(cp)
                        || Character.getType(cp) == Character.LINE_SEPARATOR
                        || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR)
                .mapToObj(Integer::toHexString).toList())
                .as("a separator is embedded raw rather than escaped").isEmpty();
    }

    @Test
    void collapsingIsLinearInTheLengthOfAWhitespaceRun() {
        // The single expression this replaces, '\s*\R\s*', rescans a break-free run from
        // every position: 64 KB took ten seconds, on text a model chose.
        String spaces = "x" + " ".repeat(400_000) + "x";
        long start = System.nanoTime();

        assertThat(OneLine.of(spaces)).isEqualTo("x x");

        assertThat((System.nanoTime() - start) / 1_000_000)
                .as("collapsing went quadratic again").isLessThan(2_000);
    }
}
