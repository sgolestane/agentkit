package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which refusal each store gives, named rather than left to the message (#257).
 *
 * <p>{@code MemoryStoreDifferentialTest} compares the two stores against each other over
 * thousands of generated calls and is where a drift would actually be caught. This is the
 * fast, named half of the same rule: it says what each reason <em>is</em>, so a new throw
 * site classified into the wrong constant fails here with the constant in the message
 * rather than showing up as one line of a fuzz report — and so that the three reasons both
 * stores owe are checked in the module that defines them, not only in the one downstream
 * that fuzzes them.
 *
 * <p>The clauses are single-hazard, which is the whole point of a reason code: each call
 * below is wrong in exactly one way, so the constant it produces is the constant that one
 * wrongness earns. The multi-hazard call — a key and a value both refusable — belongs in
 * the differential fuzz, where an oracle can say which of the two answered.
 */
class MemoryRefusalTest {

    @TempDir
    Path root;

    /** The reason behind what {@code call} threw, or empty if it did not carry one. */
    private static Optional<MemoryRefusal> reasonFrom(MemoryStore store,
                                                      Consumer<MemoryStore> call) {
        IllegalArgumentException refused =
                catchThrowableOfType(IllegalArgumentException.class, () -> call.accept(store));
        assertThat(refused)
                .as("the store did not refuse at all, so there is no reason to read back")
                .isNotNull();
        return MemoryRefusal.behind(refused);
    }

    private void bothStoresAnswer(MemoryRefusal expected, Consumer<MemoryStore> call) {
        assertThat(reasonFrom(new InMemoryMemoryStore(), call))
                .as("the map store refused for a different reason than %s", expected)
                .contains(expected);
        assertThat(reasonFrom(new FileMemoryStore(root, Containment.BEST_EFFORT), call))
                .as("the file store refused for a different reason than %s", expected)
                .contains(expected);
    }

    @Test
    void aStringThatIsNotAKeyIsNotAKeyInEitherStore() {
        bothStoresAnswer(MemoryRefusal.NOT_A_KEY, store -> store.write("   ", "v"));
    }

    @Test
    void aKeyThatIsAlsoAFolderCollidesWithTheHierarchyInEitherStore() {
        bothStoresAnswer(MemoryRefusal.HIERARCHY_COLLISION, store -> {
            store.write("a", "x");
            store.write("a/b.md", "y");
        });
    }

    @Test
    void aValueUtf8CannotCarryIsRefusedAsAValueInEitherStore() {
        bothStoresAnswer(MemoryRefusal.VALUE_IS_NOT_TEXT,
                store -> store.write("a.md", "\uD800"));
    }

    @Test
    void aKeyReachedThroughALinkSaysSoRatherThanBlamingTheKeyItself() throws Exception {
        Files.writeString(root.resolve("real.md"), "mine");
        Files.createSymbolicLink(root.resolve("alias.md"), root.resolve("real.md"));

        assertThat(reasonFrom(new FileMemoryStore(root, Containment.BEST_EFFORT), store -> store.write("alias.md", "v")))
                .contains(MemoryRefusal.REACHED_THROUGH_A_LINK);
    }

    @Test
    void aKeyThatLandsOutsideTheRootSaysThatRatherThanBlamingTheLinkItPassedThrough()
            throws Exception {
        Path outside = Files.createDirectories(root.resolveSibling("outside"));
        Files.writeString(outside.resolve("secret.md"), "not ours");
        Files.createSymbolicLink(root.resolve("a.md"), outside.resolve("secret.md"));

        // Distinct from REACHED_THROUGH_A_LINK above, and the order matters: containment is
        // settled in locate(), before the link question is asked at all, so a link that
        // leaves the root is refused as a containment failure rather than as a link.
        assertThat(reasonFrom(new FileMemoryStore(root, Containment.BEST_EFFORT), store -> store.write("a.md", "v")))
                .contains(MemoryRefusal.OUTSIDE_THE_STORE);
    }

    @Test
    void aFolderHoldingNothingThatIsADocumentIsNotAHierarchyCollision() throws Exception {
        // #115's distinction, and the reason NOT_A_DOCUMENT is its own constant: nothing
        // under 'a' is a key, so the hierarchy is not in collision -- and the folder still
        // cannot be got out of the way.
        Files.createDirectories(root.resolve("a"));
        Files.createSymbolicLink(root.resolve("a/x"), root.resolve("nothing-is-here"));

        assertThat(reasonFrom(new FileMemoryStore(root, Containment.BEST_EFFORT), store -> store.write("a", "v")))
                .contains(MemoryRefusal.NOT_A_DOCUMENT);
    }

    @Test
    void somethingThatIsNotADocumentAtTheKeyItselfIsAlsoNotAHierarchyCollision()
            throws Exception {
        // The other NOT_A_DOCUMENT branch, and it needed its own clause: the folder above
        // reaches Standing.SOMETHING_ELSE and this reaches Standing.NOT_A_DOCUMENT, so a
        // mutant reclassifying one of the two survived the other's test. Measured -- with
        // only the folder clause present, HIERARCHY_COLLISION planted at this line passed
        // every test in the repository including the differential fuzz, which cannot reach
        // it at all because a map has no device nodes to plant.
        //
        // A unix socket rather than a FIFO: it needs no external binary and no privileges,
        // and it is the same question. NonDocumentAtAKeyTest covers the shapes exhaustively;
        // what is asked here is only which constant the refusal carries.
        Path key = root.resolve("notes.md");
        try (java.nio.channels.ServerSocketChannel channel = java.nio.channels.ServerSocketChannel
                .open(java.net.StandardProtocolFamily.UNIX)) {
            channel.bind(java.net.UnixDomainSocketAddress.of(key));
        }

        assertThat(reasonFrom(new FileMemoryStore(root, Containment.BEST_EFFORT), store -> store.write("notes.md", "v")))
                .contains(MemoryRefusal.NOT_A_DOCUMENT);
    }

    @Test
    void somethingThatIsNotAFolderOnTheWayToTheKeyIsTheSameReasonAsAtTheKey()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dev.agentkit.core.util.Pinning.available(), dev.agentkit.core.util.Pinning.WHY);
        // #188's branch, and the third place NOT_A_DOCUMENT is raised. Its own clause for
        // the reason the one above has one: reclassifying this line alone survived every
        // other test in the repository. The differential fuzz cannot reach it either -- the
        // ancestor has to be something a map cannot hold.
        Path ancestor = root.resolve("a");
        try (java.nio.channels.ServerSocketChannel channel = java.nio.channels.ServerSocketChannel
                .open(java.net.StandardProtocolFamily.UNIX)) {
            channel.bind(java.net.UnixDomainSocketAddress.of(ancestor));
        }

        assertThat(reasonFrom(new FileMemoryStore(root, Containment.BEST_EFFORT), store -> store.write("a/b.md", "v")))
                .as("a component that is not a folder is a hierarchy collision, which it is "
                        + "not: nothing under 'a' is a key, so no key collides with it")
                .contains(MemoryRefusal.NOT_A_DOCUMENT);
    }

    @Test
    void aRefusalFromSomewhereElseCarriesNoReasonRatherThanTheWrongOne() {
        // What a store outside this repository raises, and the answer that keeps it
        // conforming: empty, so a differential oracle falls back to comparing channels
        // instead of reporting a divergence against a store that never claimed a reason.
        assertThat(MemoryRefusal.behind(new IllegalArgumentException("not from here")))
                .isEmpty();
        assertThat(MemoryRefusal.behind(null)).isEmpty();
        assertThat(MemoryRefusal.behind(new RuntimeException("not even the right channel")))
                .isEmpty();
    }

    @Test
    void aReasonCarriesTheMessageThroughUnchanged() {
        // The message is still the payload -- MemoryTools hands it to the model verbatim --
        // and a reason code that quietly rewrote it would have taken the model's only
        // actionable channel away in the act of making it comparable.
        IllegalArgumentException refused =
                MemoryRefusal.NOT_A_KEY.of("path must not be blank");

        assertThat(refused).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path must not be blank");
        assertThat(MemoryRefusal.behind(refused)).contains(MemoryRefusal.NOT_A_KEY);
    }
}
