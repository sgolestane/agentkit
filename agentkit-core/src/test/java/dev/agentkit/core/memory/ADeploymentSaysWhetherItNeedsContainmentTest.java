package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.util.Pinning;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A store that cannot contain what it holds says so before it holds anything.
 *
 * <h2>The failure this closes is not the escape</h2>
 *
 * <p>It is not knowing. {@code FileMemoryStore} closes a family of TOCTOU races with an
 * {@code openat} descent through {@code SecureDirectoryStream}, and only Linux's JDK returns
 * one. Everywhere else the fallback runs and the races reopen — this repository's own pentest
 * suite measured 6,713 escaping writes in 20,000 attempts on macOS against a README reporting
 * zero.
 *
 * <p>It stood for two years because both classes disclosed the fallback and both named the
 * wrong platform, and the tests that contradicted them were red for an unrelated fixture bug
 * and had been written off. A silent default would reproduce that exactly. So would a warning:
 * an ignorable disclosure is precisely what produced it.
 *
 * <p>Hence a constructor argument with no overload that omits it. This file is about the
 * argument being load-bearing rather than decorative — that {@code PINNED_OR_FAIL} actually
 * refuses, that the refusal says what to do, and that neither value changes anything on a
 * platform where the descent works.
 */
class ADeploymentSaysWhetherItNeedsContainmentTest {

    @Test
    void neitherChoiceChangesAnythingWhereTheDescentWorks(@TempDir Path tmp) {
        // The choice is only ever about what to do where containment is unavailable. On a
        // platform that has it, asking for it and not insisting on it are the same store —
        // and a reader should not have to take that on trust.
        org.junit.jupiter.api.Assumptions.assumeTrue(Pinning.available(), Pinning.WHY);

        MemoryStore insisted = new FileMemoryStore(tmp.resolve("a"),
                Containment.PINNED_OR_FAIL);
        MemoryStore accepted = new FileMemoryStore(tmp.resolve("b"), Containment.BEST_EFFORT);

        insisted.write("facts/user.md", "prefers dark mode");
        accepted.write("facts/user.md", "prefers dark mode");
        assertThat(insisted.read("facts/user.md")).isEqualTo(accepted.read("facts/user.md"));
        assertThat(insisted.list("")).isEqualTo(accepted.list(""));
    }

    @Test
    void insistingOnContainmentThatIsNotThereRefusesToStart(@TempDir Path tmp) {
        // The whole point of the argument. Where the descent is unavailable, a store built
        // with PINNED_OR_FAIL holds nothing rather than holding it under a guarantee it
        // cannot keep.
        org.junit.jupiter.api.Assumptions.assumeFalse(Pinning.available(),
                "this platform has the pinned descent, so there is nothing here to refuse");

        assertThatThrownBy(() -> new FileMemoryStore(tmp, Containment.PINNED_OR_FAIL))
                .isInstanceOf(IllegalStateException.class)
                // What is wrong, on which platform, and what to write instead. A refusal that
                // says only "unavailable" gets worked around by whoever hits it first, and the
                // working-around is the thing this exists to make deliberate.
                .hasMessageContaining("INACTIVE")
                .hasMessageContaining("SecureDirectoryStream")
                .hasMessageContaining(System.getProperty("os.name"))
                .hasMessageContaining("BEST_EFFORT");
    }

    @Test
    void acceptingBestEffortStartsAndStillRefusesEveryNameRule(@TempDir Path tmp) {
        // BEST_EFFORT gives up one thing: containment against a concurrent writer already
        // inside the root. Everything that is a question about a NAME still holds, and a
        // reader who takes "best effort" to mean "no rules" would be badly wrong.
        MemoryStore store = new FileMemoryStore(tmp, Containment.BEST_EFFORT);

        store.write("facts/user.md", "prefers dark mode");
        assertThat(store.read("facts/user.md")).contains("prefers dark mode");

        for (String outside : java.util.List.of("../escape.md", "/etc/passwd", "a/../../b.md")) {
            assertThatThrownBy(() -> store.write(outside, "x"))
                    .as("%s", outside)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void acceptingBestEffortSaysSoOutLoudExactlyOnce(@TempDir Path tmp) {
        // The README and the Containment javadoc both promise a WARN. A promised disclosure
        // that nothing verifies is how this whole issue happened — two javadocs disclosed the
        // fallback for two years and nobody read either — so the promise is measured, on the
        // stream a person actually reads.
        org.junit.jupiter.api.Assumptions.assumeFalse(Pinning.available(),
                "this platform has the pinned descent, so there is nothing to warn about");

        java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        java.io.PrintStream was = System.err;
        try {
            System.setErr(new java.io.PrintStream(said, true));
            new FileMemoryStore(tmp, Containment.BEST_EFFORT).write("a.md", "v");
        } finally {
            System.setErr(was);
        }

        String output = said.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(output).contains("INACTIVE").contains("BEST_EFFORT");
        // Once. At construction, not per operation — the write above would have made it two.
        // A line on every write is a line nobody reads, which is the failure mode being
        // avoided rather than a tidiness preference.
        assertThat(output.split("INACTIVE", -1)).hasSize(2);
    }

    @Test
    void thereIsNoWayToBuildOneWithoutChoosing() throws Exception {
        // The load-bearing claim of the whole change, and the one a later convenience
        // overload would silently undo. Asked of the class rather than of a call site,
        // because a call site only proves the overload was not used HERE.
        assertThat(FileMemoryStore.class.getConstructors())
                .as("a constructor that omits the containment decision would let a caller"
                        + " back onto the wrong side of it without ever considering it")
                // Not empty, and only then the per-constructor rule. allSatisfy over an empty
                // list passes, so without this the assertion would go green on a class with no
                // public constructors at all — which is the one shape it cannot detect.
                .isNotEmpty()
                .allSatisfy(one -> assertThat(one.getParameterTypes())
                        .contains(Containment.class));
        assertThat(MemoryStore.class.getMethod("file", Path.class, Containment.class))
                .isNotNull();
        assertThatThrownBy(() -> MemoryStore.class.getMethod("file", Path.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void aNullChoiceIsRefusedRatherThanReadAsBestEffort(@TempDir Path tmp) {
        // null is the one value that would mean "I did not decide", and reading it as the
        // permissive option is how a required decision becomes an optional one.
        assertThatThrownBy(() -> new FileMemoryStore(tmp, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("containment");
    }

    @Test
    void theProbeAsksTheDirectoryRatherThanThePlatform(@TempDir Path tmp) throws Exception {
        // Whether a SecureDirectoryStream comes back is a property of the filesystem provider
        // serving that path, not of the JVM. A root on a mounted volume or an in-memory
        // filesystem can answer differently on the same machine, and a platform-wide probe
        // would report the wrong one for exactly the deployment that cared.
        assertThatCode(() -> Containment.isAvailableFor(tmp)).doesNotThrowAnyException();
        assertThat(Containment.isAvailableFor(tmp)).isEqualTo(Pinning.available());
    }
}
