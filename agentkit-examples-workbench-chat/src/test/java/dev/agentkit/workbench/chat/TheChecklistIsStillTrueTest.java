package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A written checklist rots the moment somebody adds an endpoint.
 *
 * <p>{@code PARITY.md} is #353's first acceptance criterion: one line per dashboard capability,
 * each marked with how the chat achieves it, and anything that cannot be done written down as a
 * deliberate omission with a reason. A document like that is worth exactly as much as its
 * freshness, and nothing keeps a document fresh except a test.
 *
 * <p>So both halves are read from the source rather than from the document: the dashboard's
 * route table out of {@code WebServer}, and the chat's tool names out of {@link ConsoleTools}.
 * Adding a route to the dashboard fails this until somebody says what the chat does about it —
 * which is the moment to decide, rather than six months later in front of a customer.
 */
class TheChecklistIsStillTrueTest {

    /** {@code case "GET /runs" ->} and friends, which is how the dashboard's table reads. */
    private static final Pattern ROUTE =
            Pattern.compile("case \"(GET|POST) (/[a-z/]*)\"");

    /** The sub-routes, dispatched on a path segment: {@code case "preview" ->}. */
    private static final Pattern ACTION = Pattern.compile("case \"([a-z]+)\" ->");

    private static Path repoRoot() {
        // The module directory when surefire runs, whichever module that is.
        return Path.of("").toAbsolutePath().getParent();
    }

    private static String read(Path path) throws IOException {
        assertThat(Files.exists(path)).as("%s is gone", path).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String checklist() throws IOException {
        return read(repoRoot().resolve("agentkit-examples-workbench-chat/PARITY.md"));
    }

    private static String dashboard() throws IOException {
        return read(repoRoot().resolve("agentkit-examples-workbench/src/main/java/dev/agentkit/"
                + "workbench/web/WebServer.java"));
    }

    @Test
    void everyDashboardRouteIsOnTheChecklist() throws IOException {
        String checklist = checklist();
        Set<String> routes = new LinkedHashSet<>();
        Matcher found = ROUTE.matcher(dashboard());
        while (found.find()) {
            routes.add(found.group(2));
        }

        assertThat(routes).as("the route table moved; this test is reading the wrong thing")
                .hasSizeGreaterThan(10);
        for (String route : routes) {
            assertThat(checklist)
                    .as("PARITY.md says nothing about %s — either the chat reaches it and the "
                            + "row is missing, or it does not and that is an omission somebody "
                            + "has to justify", route)
                    .contains(route);
        }
    }

    @Test
    void everyThingADashboardButtonDoesToATicketIsOnTheChecklist() throws IOException {
        String checklist = checklist();
        Set<String> actions = new LinkedHashSet<>();
        Matcher found = ACTION.matcher(dashboard());
        while (found.find()) {
            actions.add(found.group(1));
        }

        // preview, execute, comment, transition, triage, approve, reject, answer — the verbs
        // the dashboard dispatches on a path segment rather than a whole route, and the ones a
        // route-only sweep would miss entirely.
        assertThat(actions).contains("preview", "execute", "comment", "transition", "triage",
                "approve", "reject", "answer");
        for (String action : actions) {
            assertThat(checklist)
                    .as("PARITY.md says nothing about the dashboard's '%s'", action)
                    .containsIgnoringCase(action);
        }
    }

    @Test
    void everyChatToolIsOnTheChecklist() throws IOException {
        String checklist = checklist();
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        List<String> tools = ConsoleTools.of(Console.DEPLOYMENT, console.alm, console.workbench,
                        null, console.learnings, console.store, null)
                .stream().map(Tool::name).toList();

        // The other direction, and the one that rots quietly: a tool added to the surface and
        // never mentioned is a capability nobody knows the chat has.
        for (String tool : tools) {
            assertThat(checklist).as("PARITY.md does not mention %s", tool).contains(tool);
        }
    }

    @Test
    void theChecklistSaysWhatIsNotDoneRatherThanImplyingEverythingIs() throws IOException {
        String checklist = checklist();

        // The two things it would be easiest to leave out, and the reason a checklist is worth
        // writing at all: a list of green ticks that quietly omits the red ones is worse than
        // no list, because it is evidence somebody will cite.
        // The headings, not a mention of them. The first version of this matched
        // "Deliberate omissions" anywhere in the file, and a row above the sections says
        // "See *Deliberate omissions*" — so deleting the whole section left the test green.
        assertThat(checklist).contains("\n## Deliberate omissions\n");
        assertThat(checklist).contains("\n## Not covered here\n");

        // That the section has something IN it, rather than a phrase it happens to use.
        //
        // This asserted "has not been run", which was the eval of #383. That eval has now been
        // run, so the sentence had to go — and a test pinned to it turns closing a gap into a
        // red build, which teaches the next person to delete the assertion rather than to
        // think about it. What is worth holding is that the section is not an empty heading:
        // the moment it is, the file is a list of green ticks again, which is the thing this
        // whole class exists to prevent.
        String uncovered = checklist.substring(
                checklist.indexOf("\n## Not covered here\n") + "\n## Not covered here\n".length());
        assertThat(uncovered.strip())
                .as("'Not covered here' is an empty heading, so PARITY.md now reads as though"
                        + " everything is done — which is exactly the shape a checklist is"
                        + " dangerous in, because it is evidence somebody will cite")
                .isNotEmpty()
                .contains("**");
    }
}
