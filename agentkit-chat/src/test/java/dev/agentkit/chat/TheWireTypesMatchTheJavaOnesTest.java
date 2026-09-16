package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.View;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The console's types and the Java ones cannot drift apart without this failing.
 *
 * <h2>Checked rather than generated, and why that is the right trade here</h2>
 *
 * <p>The obvious alternative is a code generator: derive {@code types.ts} from the Java types in
 * the Maven build. It is the correct answer at a certain size and this surface is well below it
 * — four enums and a list of view kinds — and it costs a generator, a decision about whether its
 * output is committed, and a build step that has to run before the frontend's own.
 *
 * <p>What actually goes wrong without <em>something</em> is specific and quiet: a new
 * {@code View} kind is added on the Java side, no renderer is registered for it on the other,
 * and the widget silently never appears. Nothing errors. The same for a new
 * {@code ChatEvent.Type}, which a client's switch simply ignores.
 *
 * <p>So this reads the TypeScript and compares the sets. Adding a kind means touching two files;
 * forgetting the second is a red test naming exactly what is missing, which is all a generator
 * would have bought.
 *
 * <h2>It fails rather than skips when the file is missing</h2>
 *
 * <p>Unlike the test that serves the built page, this one needs no build — only the source. A
 * missing {@code types.ts} means the workspace has been moved or deleted, which is a thing to
 * find out about.
 */
class TheWireTypesMatchTheJavaOnesTest {

    /**
     * The console's source, from this module.
     *
     * <p>A relative hop out of the module, which is the price of the frontend living beside it
     * rather than inside {@code src/main}. It is asserted to exist rather than assumed, so
     * moving the workspace fails here with a sentence instead of somewhere else with a
     * {@code NoSuchFileException}.
     */
    private static final Path TYPES =
            Path.of("..", "agentkit-chat-ui", "src", "lib", "types.ts");

    private String source() throws IOException {
        assertThat(TYPES)
                .as("The console's wire types should be at %s — has the workspace moved?",
                        TYPES.toAbsolutePath().normalize())
                .exists();
        return Files.readString(TYPES, StandardCharsets.UTF_8);
    }

    /** The string literals of an {@code export const NAME = [...] as const} array. */
    private Set<String> declared(String source, String name) {
        Matcher block = Pattern.compile(
                        "export const " + name + "\\s*=\\s*\\[(.*?)\\]\\s*as const",
                        Pattern.DOTALL)
                .matcher(source);
        assertThat(block.find())
                .as("types.ts should declare `export const %s = [...] as const`", name)
                .isTrue();
        Set<String> found = new LinkedHashSet<>();
        Matcher entry = Pattern.compile("'([^']+)'").matcher(block.group(1));
        while (entry.find()) {
            found.add(entry.group(1));
        }
        return found;
    }

    @Test
    void everyBuiltInViewKindHasANameTheConsoleKnows() throws IOException {
        // Built through the factories rather than read off a constant, so this measures what a
        // tool can actually produce. A kind reachable only by View.of is deliberately not here:
        // the whole point of that door is that a deployment can add one without the framework
        // knowing, and the console renders an unknown kind as its raw data.
        Set<String> fromJava = new LinkedHashSet<>();
        for (View built : List.of(
                View.markdown("x"),
                View.table(View.Column.texts("a"), List.of(List.of(1))),
                View.chart("bar", List.of("a"), List.of(View.Series.of("s", List.of(1)))),
                View.scatter("age", "comments",
                        List.of(View.Cloud.of("open", List.of(View.Point.at(1, 2))))),
                View.stat("a", 1, ""),
                View.cards(List.of(View.Card.of("a", Map.of()))),
                View.diff("a", "b", "c"),
                View.file("id", "a", "text/plain", 1),
                View.timeline("a", List.of(View.Moment.of("run", "started", ""))))) {
            fromJava.add(built.kind());
        }

        assertThat(declared(source(), "VIEW_KINDS"))
                .as("a View kind with no entry in types.ts renders as raw data and nobody "
                        + "notices; an entry with no factory is a renderer for nothing")
                .containsExactlyInAnyOrderElementsOf(fromJava);
    }

    @Test
    void everyEventTypeHasANameTheConsoleKnows() throws IOException {
        assertThat(declared(source(), "EVENT_TYPES"))
                .as("a client's switch silently ignores an event type it has never heard of")
                .containsExactlyInAnyOrderElementsOf(names(ChatEvent.Type.values()));
    }

    @Test
    void everyTurnStateHasANameTheConsoleKnows() throws IOException {
        assertThat(declared(source(), "TURN_STATES"))
                .as("WAITING_FOR_HUMAN is the one that matters: a console that does not know it "
                        + "draws a decision as an error")
                .containsExactlyInAnyOrderElementsOf(names(Turn.State.values()));
    }

    @Test
    void everyStepKindHasANameTheConsoleKnows() throws IOException {
        assertThat(declared(source(), "STEP_KINDS"))
                .containsExactlyInAnyOrderElementsOf(names(Step.Kind.values()));
    }

    private static Set<String> names(Enum<?>[] values) {
        List<String> found = new ArrayList<>();
        for (Enum<?> value : values) {
            found.add(value.name());
        }
        return new LinkedHashSet<>(found);
    }
}
