package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;

/**
 * What is true of {@link FileMemoryStore} in particular, and of {@link MemoryKeys} on its
 * own.
 *
 * <p>The clauses that hold of <em>every</em> {@code MemoryStore} used to live here too, as
 * eighteen {@code @ParameterizedTest}s over a two-element {@code stores()} provider. They
 * are now in {@link MemoryStoreContract}, which is the same assertions with the provider
 * turned inside out: a third party extends it and supplies the store, which is the only
 * form of "we told them the rules" that gets checked (#153). Moved rather than copied —
 * one rule asserted in two places is the defect this repository keeps finding, and a test
 * file is not exempt from it.
 *
 * <p>What stayed is what is genuinely about one implementation: percent-encoded filenames,
 * owner-only modes, planted links, an unreadable directory, a rotated root, and the key
 * rules examined without a store around them.
 */
class MemoryStoreTest {

    @org.junit.jupiter.api.Test
    void normalisingTwiceIsNormalisingOnce() {
        // list() reports a key only if isNormalized says a later read would resolve it, so
        // normalize has to be a fixed point or the two disagree about the same string. It
        // was not: the rooted check ran on the raw string, and folding can *promote* a
        // segment into first position that the check would have refused there —
        // 'a/../C:' folds to 'C:', which normalize then rejects as an absolute path.
        //
        // The in-memory store listed 'C:' and threw from read('C:'). The file store wrote a
        // document named 'C:' and listed nothing, so the two disagreed about the same
        // sequence, and the model was told "Wrote memory 'C:'" for a key it could never
        // read back or delete. The javadoc claimed this class had fixed exactly that.
        for (String promoted : List.of("a/../C:", "a/../\\x", "x/y/../../C:/Windows",
                "./a/../ C: ", "a/b/../../\\\\server")) {
            String once;
            try {
                once = MemoryKeys.normalize(promoted);
            } catch (IllegalArgumentException refused) {
                continue;   // refusing outright is a fine answer; answering twice is not
            }
            assertThat(MemoryKeys.isNormalized(once))
                    .as("normalize(%s) = %s, which normalize itself will not accept",
                            promoted, once)
                    .isTrue();
            assertThat(MemoryKeys.normalize(once)).isEqualTo(once);
        }
    }

    @org.junit.jupiter.api.Test
    void whatAKeyNormalisesToDoesNotDependOnTheHostLocale() {
        // Path.of encodes in sun.jnu.encoding — ASCII under a C locale, which is what this
        // suite runs under — so deriving the key rules from it made 'facts/café.md' a valid
        // key on one machine and an exception on the next, for the in-memory store as much
        // as the durable one. Normalisation is a string question and is answered as one.
        assertThat(MemoryKeys.normalize("./facts/  café.md  ")).isEqualTo("facts/café.md");
        assertThat(MemoryKeys.isNormalized("facts/café.md")).isTrue();
        assertThat(new InMemoryMemoryStore()).satisfies(store -> {
            store.write("facts/café.md", "naïve");
            assertThat(store.read("facts/café.md")).contains("naïve");
            assertThat(store.list("")).containsExactly("facts/café.md");
        });
        // What a filesystem can hold used to be a separate question with a different
        // answer — see the durable store's own test below, which is where #96 was.
    }

    @org.junit.jupiter.api.Test
    void fileStoreHoldsANonAsciiKeyWhateverTheHostLocaleIs(@TempDir Path root) {
        // #96. Path.of encodes through sun.jnu.encoding, which is ASCII under the C locale
        // a bare container gives you, so naming the file after the key made this throw
        // InvalidPathException on the default configuration while the in-memory store
        // accepted it. The key is percent-encoded on the way to disk, so the vocabulary is
        // the same on every host.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        String key = "facts/café.md";

        store.write(key, "naïve");

        assertThat(store.read(key)).contains("naïve");
        assertThat(store.exists(key)).isTrue();
        assertThat(store.list("")).containsExactly(key);
        assertThat(store.list("facts/")).containsExactly(key);
        assertThat(store.delete(key)).isTrue();
        assertThat(store.exists(key)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void anEmptyFolderLeftBehindDoesNotOccupyTheName(@TempDir Path root) throws Exception {
        // Replaces a test that asserted delete prunes. It does not any more, and the
        // property that actually matters is the one below: whether a leftover folder can
        // keep a key from being used. It could, and getting there needed no delete at all —
        // write() creates the parents before writing the file, so any write that fails
        // afterwards leaves the folder standing. The model was then refused the parent key,
        // told other keys lived under it when none did, and had no way to free it.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        Files.createDirectories(root.resolve("facts/deep"));   // as a failed write would leave

        store.write("facts", "now a document");

        assertThat(store.read("facts")).contains("now a document");
        assertThat(store.list("")).containsExactly("facts");

        // And an empty folder from a delete is the same case, so it needs no special path.
        store.write("notes/a.md", "x");
        assertThat(store.delete("notes/a.md")).isTrue();
        store.write("notes", "reused");
        assertThat(store.read("notes")).contains("reused");

        // What is never cleared is a folder that still holds a document.
        store.write("live/a.md", "keep");
        assertThatThrownBy(() -> store.write("live", "should not land"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.read("live/a.md")).contains("keep");

        // The root is not a folder in anyone's way, and emptying the store leaves it.
        assertThat(root).exists().isDirectory();
    }

    @org.junit.jupiter.api.Test
    void aLinkIsNeverAMemoryThatCanBeForgotten(@TempDir Path tmp) throws Exception {
        // delete answers exactly what exists answers, or it reports a memory forgotten that
        // was never held. An earlier guard asked isDirectory with NOFOLLOW_LINKS, which
        // makes every symbolic link "not a directory" and so deletable: a link to a folder,
        // a dangling link and a self-referencing loop all answered true while exists
        // answered false — three ways to tell a model a fact was forgotten when nothing was.
        Path root = Files.createDirectories(tmp.resolve("mem"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("target/keep.md", "K");
        store.write("plain.md", "P");
        try {
            Files.createSymbolicLink(root.resolve("toFolder"), root.resolve("target"));
            Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nothing-here"));
            Files.createSymbolicLink(root.resolve("loop"), root.resolve("loop"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
        }

        for (String link : List.of("toFolder", "dangling", "loop")) {
            assertThat(store.exists(link)).as("exists('%s')", link).isFalse();
            assertThat(store.delete(link))
                    .as("delete('%s') claimed to forget something exists() denies", link)
                    .isEqualTo(store.exists(link));
        }
        // The documents behind them are untouched, and so are the links themselves.
        assertThat(store.read("target/keep.md")).contains("K");
        assertThat(root.resolve("toFolder")).exists();

        // And a link to a perfectly ordinary document is the same answer (#99). It used to
        // be the exception: list skipped it — the walk reads the name's own attributes, so
        // a link is not a regular file to it — while read, exists and delete followed it
        // and found the document. Three operations said yes and the fourth said no, so a
        // memory was reachable by key and invisible to any listing, which means an agent
        // would never look at it again.
        try {
            Files.createSymbolicLink(root.resolve("alias.md"), root.resolve("plain.md"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
        }
        assertThat(store.exists("alias.md")).isFalse();
        assertThat(store.read("alias.md")).isEmpty();
        assertThat(store.delete("alias.md")).isFalse();
        assertThat(store.list("")).doesNotContain("alias.md");
        // The link and its target are both left alone: refusing to treat it as a memory is
        // not the same as deciding it should not exist.
        assertThat(store.read("plain.md")).contains("P");
        assertThat(Files.exists(root.resolve("alias.md"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .isTrue();
    }

    @org.junit.jupiter.api.Test
    void everyShapeOfLinkAnswersTheSameOnEveryOperation(@TempDir Path tmp) throws IOException {
        // Written as an agreement over a table of shapes rather than as four assertions
        // about one of them, and that is the whole finding. The version above hard-codes
        // the answers for a link whose *last* name is the link, which is the easy half:
        // Files.isSymbolicLink on the lexical path traverses every component before the
        // last, so 'aliasDir/note.md' answered "not a link" while read returned the
        // document, exists said true, list did not report it, and delete destroyed what
        // 'real/note.md' named. Issue #99 unchanged, plus the data loss
        // deletingAKeyDoesNotDestroyWhateverItPointsAt exists to prevent.
        Path root = Files.createDirectories(tmp.resolve("mem"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("plain.md", "P");
        store.write("real/note.md", "N");
        Files.createDirectories(root.resolve("empty"));
        try {
            Files.createSymbolicLink(root.resolve("alias.md"), root.resolve("plain.md"));
            Files.createSymbolicLink(root.resolve("relative.md"), Path.of("plain.md"));
            Files.createSymbolicLink(root.resolve("chain.md"), root.resolve("alias.md"));
            Files.createSymbolicLink(root.resolve("aliasDir"), root.resolve("real"));
            Files.createSymbolicLink(root.resolve("aliasEmpty"), root.resolve("empty"));
            Files.createSymbolicLink(root.resolve("self"), root);
            Files.createSymbolicLink(root.resolve("dangling.md"), root.resolve("nothing"));
            // A link that names itself. SafePaths resolves a dangling link through its own
            // target, so for these the resolved path *is* the lexical one and the string
            // comparison agrees — the one blind spot, and the write path pays an lstat for
            // it. Both shapes were already built two tests above and only the harmless
            // question was asked of them, which is the mistake this table exists to stop
            // repeating.
            Files.createSymbolicLink(root.resolve("loop.md"), Path.of("loop.md"));
            Files.createSymbolicLink(root.resolve("loopDir"), root.resolve("loopDir"));
            // And a link whose target folds back onto its own name through another link.
            // The fold is lexical and the kernel's is not, so 'foldOut/../fold.md' looked
            // like the name itself while naming a file in a directory of somebody else's
            // choosing: write was accepted and the model's content landed outside the root.
            Files.createDirectories(tmp.resolve("elsewhere"));
            Files.createSymbolicLink(root.resolve("foldOut"), tmp.resolve("elsewhere"));
            Files.createSymbolicLink(root.resolve("fold.md"), Path.of("foldOut/../fold.md"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
        }

        List<String> throughALink = List.of(
                "alias.md",                    // the last name is the link
                "relative.md",                 // and it may be spelled relatively
                "chain.md",                    // a link to a link
                "aliasDir/note.md",            // the link is a *directory*, two above the name
                "self/plain.md",               // a link to the root gives every key an alias
                "self/self/self/plain.md",     // and unbounded spellings of it
                "self/aliasDir/note.md",       // in any combination
                "dangling.md",                 // aiming at nothing is still aiming
                "aliasEmpty/note.md",          // a link to a directory holding nothing yet
                "loop.md",                     // a link that names itself
                "loopDir/note.md",             // and one a component above the name
                "fold.md");                    // a target that lexically folds back

        for (String key : throughALink) {
            assertThat(store.read(key)).as("read('%s') followed a link", key).isEmpty();
            boolean existed = store.exists(key);
            assertThat(existed).as("exists('%s') followed a link", key).isFalse();
            assertThat(store.list("")).as("list('') reported '%s'", key).doesNotContain(key);
            // The agreement, which is the property. Read *before* the delete: asked after,
            // it would hold just as well for a delete that had destroyed the target, since
            // exists would have gone false along with it. That reading made the strongest-
            // sounding line in the loop the weakest one in it.
            assertThat(store.delete(key))
                    .as("delete('%s') and exists('%s') disagree", key, key)
                    .isEqualTo(existed);
            // A write must refuse rather than answer absent. Absence is an answer for the
            // four asking operations and never for these two, and following the link
            // instead was worse than the defect being fixed: the write was accepted, the
            // model was told "Wrote memory", a different key's document was overwritten,
            // and the key then read back empty.
            assertThatThrownBy(() -> store.write(key, "X"))
                    .as("write('%s') was accepted", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.append(key, "X"))
                    .as("append('%s') was accepted", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // Nothing reachable through a link was lost through one, on any operation — and
        // nothing was created outside the root by one either, which is the half a refusal
        // that merely answers "absent" would not have covered.
        assertThat(store.read("plain.md")).contains("P");
        assertThat(store.read("real/note.md")).contains("N");
        assertThat(store.list("")).containsExactly("plain.md", "real/note.md");
        assertThat(Files.list(tmp.resolve("elsewhere")).toList())
                .as("a write through a link created a file outside the root")
                .isEmpty();
        assertThat(Files.list(root.resolve("empty")).toList())
                .as("a write through a link to an empty folder landed in it")
                .isEmpty();
        for (String link : List.of("alias.md", "aliasDir", "self", "dangling.md",
                "loop.md", "fold.md")) {
            assertThat(Files.exists(root.resolve(link), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .as("the link '%s' itself was removed", link)
                    .isTrue();
        }
        // And an ordinary key is untouched by any of it — the cost of the rule is paid by
        // links and by nothing else.
        store.write("notes/2026.md", "fine");
        assertThat(store.read("notes/2026.md")).contains("fine");
        assertThat(store.delete("notes/2026.md")).isTrue();
    }

    @org.junit.jupiter.api.Test
    void aMemoryDocumentIsReadableByItsOwnerAndNobodyElse(@TempDir Path root) throws Exception {
        // A permission, not a containment property, and pinned here because #168 moved where
        // it comes from. Documents have been rw------- since #145, not by decision but
        // because Files.createTempFile makes an owner-only file and the rename that follows
        // keeps its mode. #168 replaced that with openat(dirfd, name, O_CREAT|O_EXCL|O_WRONLY),
        // whose default is 0666 & ~umask -- world-readable on a default umask. The mode is
        // now passed explicitly, and the mutant dropping it passed every other test in this
        // repository while quietly publishing every memory the store holds.
        //
        // Asserted for write AND append, because they take different routes to the same
        // temporary and only one of them was measured when this was written.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                        .contains("posix"),
                "this filesystem has no POSIX modes to assert");
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.write("notes/written.md", "W");
        store.append("notes/appended.md", "A");

        assertThat(Files.getPosixFilePermissions(root.resolve("notes/written.md")))
                .as("a written memory is readable by somebody other than its owner")
                .containsExactlyInAnyOrderElementsOf(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(root.resolve("notes/appended.md")))
                .as("an appended memory is readable by somebody other than its owner")
                .containsExactlyInAnyOrderElementsOf(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    @org.junit.jupiter.api.Test
    void onePathItCannotReadDoesNotCostTheWholeListing(@TempDir Path root) throws Exception {
        // list used Files.walk, whose terminal operation raises UncheckedIOException for any
        // directory it cannot open — outside the catch, so a single unreadable folder took
        // down *every* listing over that root, list("notes/") included, since the scope
        // filter runs after the walk. The message also carried the host path, which
        // SafePaths keeps out of anything a model may see.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("notes/a.md", "A");
        store.write("top.md", "T");
        Path locked = Files.createDirectories(root.resolve("locked"));
        Files.writeString(locked.resolve("hidden.md"), "H");
        Files.setPosixFilePermissions(locked, java.util.Set.of());
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !Files.isReadable(locked.resolve("hidden.md")),
                "this user can read a mode-000 directory (running as root), so the branch "
                        + "this test exists for cannot be reached here");

        assertThat(store.list("")).containsExactly("notes/a.md", "top.md");
        assertThat(store.list("notes/")).containsExactly("notes/a.md");

        // And a folder nobody can look into is not an empty one: the key stays occupied
        // rather than being handed out for a write that would land on top of documents.
        assertThatThrownBy(() -> store.write("locked", "over the top"))
                .isInstanceOf(IllegalArgumentException.class);

        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"));
    }

    @org.junit.jupiter.api.Test
    void aRefusalNamesTheKeyTheCallerAskedAbout(@TempDir Path tmp) throws Exception {
        // MemoryTools hands getMessage() to the model, so a refusal has to be in the
        // vocabulary the model wrote in. It asked about 'café.md'; being refused over
        // 'caf%C3%A9.md' would name a file it has never heard of.
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        Path root = Files.createDirectories(tmp.resolve("mem"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        try {
            Files.createSymbolicLink(root.resolve("caf%C3%A9.md"), outside.resolve("secret.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
        }

        // Through write, because reading is total now: a key that leaves the root is
        // "nothing here" to a reader and a refusal to a writer, which is the asymmetry the
        // contract states. A write landing outside must fail loudly.
        assertThatThrownBy(() -> store.write("café.md", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("café.md")
                        .doesNotContain("%C3%A9")
                        // And no host path: those belong in the cause, never in a message
                        // that reaches a model.
                        .doesNotContain(tmp.toString()));
    }

    @org.junit.jupiter.api.Test
    void whatLandsOnDiskIsAsciiWhateverTheKeyWas(@TempDir Path root) throws Exception {
        // The property that makes the test above hold on a host this suite may never run
        // on. Asserting the round-trip alone would pass on a UTF-8 machine with the
        // encoding removed; asserting the bytes is what pins the fix.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("facts/café.md", "x");
        store.write("notes/😀.md", "y");   // U+1F600, outside the BMP: a pair

        List<String> onDisk;
        try (Stream<Path> walk = Files.walk(root)) {
            onDisk = walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString())
                    .sorted()
                    .toList();
        }

        assertThat(onDisk).containsExactly("facts/caf%C3%A9.md", "notes/%F0%9F%98%80.md");
        assertThat(onDisk).allSatisfy(name ->
                assertThat(name.chars().allMatch(c -> c >= 0x20 && c <= 0x7E))
                        .as("'%s' is not nameable on an ASCII-only filesystem", name)
                        .isTrue());
    }

    @org.junit.jupiter.api.Test
    void encodingKeepsDistinctKeysDistinct(@TempDir Path root) {
        // An encoding that is not injective would trade #96 for something worse: two keys
        // silently sharing one document. '%' is escaped for exactly this reason, so the
        // literal key 'a%2Fb.md' cannot collide with the encoding of 'a/b.md'.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("a/b.md", "nested");
        store.write("a%2Fb.md", "literal");

        assertThat(store.read("a/b.md")).contains("nested");
        assertThat(store.read("a%2Fb.md")).contains("literal");
        assertThat(store.list("")).containsExactly("a%2Fb.md", "a/b.md");
    }

    @org.junit.jupiter.api.Test
    void fileStoreDoesNotAdvertiseANameItDidNotWrite(@TempDir Path root) throws Exception {
        // list() must only report keys read() can find, and after encoding that is a
        // sharper rule than "is it a key": a name is only ours if it is the *canonical*
        // encoding of one, since a read looks for that and nothing else. The raw 'café.md'
        // case cannot be planted from here — under this suite's own locale Path.of refuses
        // to name it, which is #96 seen from the other side — so what is plantable is
        // tested instead: a lowercase escape, an escaped separator, and a stray percent.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("real.md", "ok");
        Files.writeString(root.resolve("caf%c3%a9.md"), "lowercase escape");
        Files.writeString(root.resolve("%2Fetc%2Fpasswd"), "an escaped separator");
        Files.writeString(root.resolve("%zz.md"), "not hex");

        assertThat(store.list("")).containsExactly("real.md");
    }

    @org.junit.jupiter.api.Test
    void fileStorePersistsAcrossInstances(@TempDir Path root) {
        new FileMemoryStore(root, Containment.BEST_EFFORT).write("facts/user.md", "prefers dark mode");
        // A fresh store (simulating a later session) sees the persisted memory.
        assertThat(new FileMemoryStore(root, Containment.BEST_EFFORT).read("facts/user.md")).contains("prefers dark mode");
    }

    @org.junit.jupiter.api.Test
    void fileStoreRejectsTraversal(@TempDir Path root) {
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        assertThatThrownBy(() -> store.write("../escape.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    void fileStoreDoesNotAdvertiseAFileNoKeyCanName(@TempDir Path root) throws Exception {
        // The root is a directory, so something other than this store can put a file in it.
        // A name that is not a canonical key is unreachable — every key naming it normalises
        // to something else — so listing it would be a name guaranteed to miss.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("real.md", "reachable");
        java.nio.file.Files.writeString(root.resolve("  padded.md  "), "unreachable");

        assertThat(store.list("")).containsExactly("real.md");
        assertThat(root.resolve("  padded.md  ")).exists();
    }

    @org.junit.jupiter.api.Test
    void aPlantedNameCannotForgeALineInTheOperatorsLog(@TempDir Path root) throws Exception {
        // #98. The names in this warning come from whatever put a file in the root — an
        // unpacked archive, a shared volume, a restored backup — which is exactly the
        // population the warning exists for. Interpolated raw, a line terminator in one
        // wrote an entry of its own in the framework's voice, and an escape sequence drove
        // the terminal reading it.
        //
        // Asserted on what the logger actually emits rather than on the helper, because the
        // defect was never in the escaping — it was in this site not calling it.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("real.md", "ok");
        Files.writeString(root.resolve("a.md\nWARN nothing is wrong"), "planted");
        Files.writeString(root.resolve("b.md\u001B[2Kgone"), "planted");
        // The same name spelled out rather than acted out: six literal characters, no
        // escape character anywhere. '%zz' is what keeps it out of the listing, since
        // nothing else about it stops it being a perfectly good key.
        Files.writeString(root.resolve("c.md\\u001B[2Kgone%zz"), "planted");

        String sentinel = "operator-stream-probe-" + System.nanoTime();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.err;
        String logged;
        try {
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            org.slf4j.LoggerFactory.getLogger(MemoryStoreTest.class).warn(sentinel);
            assertThat(store.list("")).containsExactly("real.md");
        } finally {
            System.setErr(original);
            logged = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        // Assumed on the PLUMBING and not on the line under test (#225). This was
        // "assumeTrue(logged.contains(\"not memory keys\"))", which makes the mutant that
        // deletes the warning outright turn this test into a SKIP and the build stays
        // green -- measured, and it is the shape DurableHugeToolResultTest's own helper
        // was written to avoid: "a test that proves the operator is told must fail, not
        // skip, when nobody is told". A sentinel through the same backend separates
        // "capture does not work here" from "the code said nothing".
        org.junit.jupiter.api.Assumptions.assumeTrue(logged.contains(sentinel),
                "the test logger did not write to System.err, so this cannot observe the "
                        + "line the operator would read");
        // That the omission is reported at all, which is what the assumption used to
        // swallow. list() answers a MODEL with a shorter list and nothing in the answer
        // says anything was left out -- the three planted files are simply not there. This
        // line is the only place the omission reaches anybody who could go and look.
        assertThat(logged)
                .as("three files were dropped from a listing with nobody outside it told")
                .contains("Ignoring 3 file(s)")
                .contains("are not memory keys");
        // One line, not two. The phrase survives — escaped, on the same line — and that is
        // the point: the operator sees what the name really was rather than reading it as
        // the framework's own entry. Asserting the phrase were absent would be asserting
        // redaction, which this does not do and should not.
        assertThat(logged.lines().filter(line -> !line.isBlank() && !line.contains(sentinel)))
                .as("the warning was split into more than one entry")
                .hasSize(1);
        assertThat(logged.lines().filter(line -> line.startsWith("WARN nothing is wrong")))
                .as("a planted name wrote a log line of its own")
                .isEmpty();
        assertThat(logged).as("a planted name reached the terminal").doesNotContain("\u001B[2K");
        // And the operator can still see what was really there, which is why this escapes
        // rather than collapsing the way a model-facing listing does. Distinguishably so:
        // asserting only that the log contains the escape spelling is satisfied by a name
        // holding those six literal characters and no escape at all, so the two planted
        // names have to render differently or the escaping tells the operator nothing.
        assertThat(logged).contains("\\u000A");
        assertThat(logged).as("the acted-out escape was not escaped").contains("b.md\\u001B[2K");
        assertThat(logged).as("the spelled-out one was not distinguished from it")
                .contains("c.md\\\\u001B[2K");
    }

    @org.junit.jupiter.api.Test
    void fileStoreDoesNotAdvertiseAPlantedNameThatIsNotAKey(@TempDir Path root) throws Exception {
        // The one line keeping a planted control-character filename out of the model-facing
        // listing is isNormalized's catch. Nothing reached it: the padded-name test above
        // exercises the equals branch and never the throw. Make that catch return true and
        // this file is listed, joined into a tool result, and forges a second line.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("real.md", "ok");
        Files.writeString(root.resolve("a.md\nfacts-admin.md"), "planted");

        assertThat(store.list("")).containsExactly("real.md");
    }

    @org.junit.jupiter.api.Test
    void fileStoreDoesNotListALinkOutOfTheRoot(@TempDir Path tmp) throws Exception {
        // The documented reason list() asks NOFOLLOW: Files.walk does not follow links but
        // isRegularFile does, so without it a link out of the root is listed as a key that
        // read() then refuses — and it tells the model where the link points.
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secrets.txt"), "top secret");
        Path root = Files.createDirectories(tmp.resolve("mem"));
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("real.md", "ok");
        try {
            Files.createSymbolicLink(root.resolve("leak.md"), outside.resolve("secrets.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
        }

        assertThat(store.list("")).containsExactly("real.md");
        // Not an exception any more: a key that leaves the root names no memory this store
        // holds, so a reader is told what a reader is told for any absent key. The operator
        // hears about it in the log; the model learns nothing it could probe with.
        assertThat(store.read("leak.md")).isEmpty();
        assertThat(store.exists("leak.md")).isFalse();
        assertThat(store.delete("leak.md")).isFalse();
        assertThat(outside.resolve("secrets.txt")).exists();
    }

    @org.junit.jupiter.api.Test
    void fileStoreListsThroughASymlinkedRoot(@TempDir Path tmp) throws Exception {
        // The class javadoc offers this — point the root at a volume through a link — and
        // it answered every read while listing nothing at all: 'Memory is empty.' over a
        // full directory, because Files.walk does not follow the start path.
        Path real = Files.createDirectories(tmp.resolve("real"));
        try {
            Files.createSymbolicLink(tmp.resolve("link"), real);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
        }
        MemoryStore store = new FileMemoryStore(tmp.resolve("link"), Containment.BEST_EFFORT);
        store.write("a.md", "v");

        assertThat(store.read("a.md")).contains("v");
        assertThat(store.list("")).containsExactly("a.md");
    }

    @org.junit.jupiter.api.Test
    void deletingAKeyDoesNotDestroyWhateverItPointsAt(@TempDir Path root) throws Exception {
        // This began as a bug where delete followed a link: it removed the document a
        // *different* key named, left the link that was asked about, and returned true —
        // reporting success for losing the wrong memory. It was fixed by unlinking the name
        // rather than the target; #99 went further, and now the link is not a memory at
        // all. The property the test exists for is the same one and is stronger: what a
        // link points at cannot be lost through the link.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        store.write("real.md", "REAL CONTENT");
        try {
            Files.createSymbolicLink(root.resolve("alias.md"), root.resolve("real.md"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
        }

        assertThat(store.delete("alias.md")).isFalse();

        assertThat(store.read("real.md")).as("delete destroyed another key's document")
                .contains("REAL CONTENT");
        assertThat(Files.exists(root.resolve("alias.md"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .as("a refused delete removed the link anyway").isTrue();
        assertThat(store.list("")).containsExactly("real.md");
    }

    @org.junit.jupiter.api.Test
    void inMemoryStoreRejectsBlankPath() {
        assertThatThrownBy(() -> new InMemoryMemoryStore().write("  ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a root rotated under the store still answers")
    void aRotatedRootIsNotASymbolicLinkInEveryKey(@TempDir Path tmp) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dev.agentkit.core.util.Pinning.available(), dev.agentkit.core.util.Pinning.WHY);
        // #110 fixed this and shipped no test for it. It passes on `main` too — the guard
        // it pins was already there — so it is a regression guard rather than a control for
        // this change. It earns its place against a mutant that lets the walk include the
        // root, which nothing else catches. `mv mem mem2 && ln -s mem2 mem` is
        // the rotation this class's own constructor note invites, and before that fix it
        // moved every resolved path out from under the construction-time root, so the whole
        // store answered empty and every write was refused for a link that was not there.
        Path live = Files.createDirectory(tmp.resolve("mem"));
        MemoryStore store = new FileMemoryStore(live, Containment.BEST_EFFORT);
        store.write("notes/today.md", "N");

        try {
            Files.move(live, tmp.resolve("mem2"));
            Files.createSymbolicLink(live, tmp.resolve("mem2"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
            return;
        }

        assertThat(store.read("notes/today.md")).contains("N");
        assertThat(store.exists("notes/today.md")).isTrue();
        assertThat(store.list("")).contains("notes/today.md");
        store.write("notes/later.md", "L");
        assertThat(Files.readString(tmp.resolve("mem2/notes/later.md"))).isEqualTo("L");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("and rotating it does not make links followable again")
    void aLinkIsStillFoundWhenTheRootItselfIsALink(@TempDir Path tmp) throws IOException {
        // The hole the rotation fix opened, and the reason #112's answer is an lstat rather
        // than a looser string comparison. That fix was a `resolved.startsWith(root)` guard,
        // and after a rotation *nothing* starts with the construction-time root — so the
        // guard answered "no link" for every key, including the ones that were links. #99
        // came back in full: read followed the link, and delete would have destroyed what
        // the target named.
        Path live = Files.createDirectory(tmp.resolve("mem"));
        MemoryStore store = new FileMemoryStore(live, Containment.BEST_EFFORT);
        store.write("plain.md", "P");
        store.write("target/keep.md", "K");

        try {
            Files.createSymbolicLink(live.resolve("alias.md"), live.resolve("plain.md"));
            Files.createSymbolicLink(live.resolve("aliasDir"), live.resolve("target"));
            Files.move(live, tmp.resolve("mem2"));
            Files.createSymbolicLink(live, tmp.resolve("mem2"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
            return;
        }

        // delete first, and asserted on the *target*: on the unfenced code this returned
        // true and destroyed plain.md while leaving the link, so stating the harm before
        // the read means the control produces the data-loss evidence rather than aborting
        // one assertion earlier with only "the read followed it".
        assertThat(store.delete("alias.md")).isFalse();
        assertThat(Files.readString(tmp.resolve("mem2/plain.md")))
                .as("delete followed the link and destroyed what it pointed at")
                .isEqualTo("P");
        assertThat(store.read("alias.md"))
                .as("a rotated root made a link readable as a memory again")
                .isEmpty();
        assertThat(store.exists("alias.md")).isFalse();
        // A link in a directory component, which is the half the first attempt at #99
        // missed entirely.
        assertThat(store.read("aliasDir/keep.md")).isEmpty();
        assertThatThrownBy(() -> store.write("aliasDir/keep.md", "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
        // Nothing was destroyed to say no, and the ordinary keys still work.
        assertThat(Files.readString(tmp.resolve("mem2/target/keep.md"))).isEqualTo("K");
        assertThat(store.read("plain.md")).contains("P");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a link that names itself is refused with a reason")
    void aSelfNamingLinkIsRefusedRatherThanFailingOnELOOP(@TempDir Path tmp) throws IOException {
        // The case the two spellings cannot tell apart — x.md -> x.md resolves to its own
        // lexical path, so they agree — and it had no test on either version. Written
        // because a mutant removing the fast path's lstat survived the whole suite, and
        // then it turned out *why*: SafePaths refuses a path it cannot resolve to a real
        // location before this class ever asks about links. So the line that would catch a
        // self-loop is not the line that does, and the earlier javadoc claiming otherwise
        // was wrong.
        //
        // The behaviour is what matters and is what this pins: the refusal names something
        // the model can act on, rather than reaching Files.writeString, dying on ELOOP, and
        // arriving as MemoryTools' "Memory operation failed."
        Path root = Files.createDirectory(tmp.resolve("mem"));
        try {
            Files.createSymbolicLink(root.resolve("self.md"), Path.of("self.md"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
            return;
        }
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        assertThatThrownBy(() -> store.write("self.md", "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be resolved");
        assertThatThrownBy(() -> store.append("self.md", "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be resolved");
        // The four asking operations answer absent, which they did before by accident —
        // the kernel refuses to walk the loop — and now by asking.
        assertThat(store.read("self.md")).isEmpty();
        assertThat(store.exists("self.md")).isFalse();
        assertThat(store.delete("self.md")).isFalse();
        // Refusing to treat it as a memory is not deciding it should not exist.
        assertThat(Files.exists(root.resolve("self.md"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .isTrue();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the case-folding false positive, reproduced without a filesystem")
    void theOldComparisonCalledAFoldedSpellingALink() {
        // The PR said this case was not reproducible here for want of a case-folding
        // filesystem. That was overstated, and the review said so: the defective decision
        // was a pure function of two Path values, and the counterexample needs no
        // filesystem at all.
        //
        // This is what the store used to compute. On macOS or Windows a key asking for
        // Notes/a.md against a file stored as notes/a.md gives exactly these two paths —
        // toRealPath returns the spelling on disk — and the answer was "reached through a
        // symbolic link", with no link anywhere. read then answered empty, exists false,
        // delete false, and the write was refused for a link that did not exist.
        Path root = Path.of("/mem");
        Path asked = Path.of("/mem/Notes/a.md");
        Path onDisk = Path.of("/mem/notes/a.md");

        assertThat(oldComparison(root, asked, onDisk))
                .as("the comparison this replaced answered 'link' for a folded spelling")
                .isTrue();
        // And what makes the replacement right is that it does not ask this question at
        // all: it asks the filesystem whether a component is a link, which is false here
        // on every platform. The end-to-end version of that still needs a filesystem that
        // folds (#81); the mechanism did not.
        assertThat(oldComparison(root, asked, asked))
                .as("an exact spelling was never the failing case")
                .isFalse();
    }

    /** {@code FileMemoryStore.Located.reachedThroughALink} as it stood before #112. */
    private static boolean oldComparison(Path root, Path lexical, Path resolved) {
        return resolved.startsWith(root) && !resolved.equals(lexical);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a folder holding no documents still refuses with a reason")
    void aFolderThatCannotBeClearedIsRefusedRatherThanFailing(@TempDir Path tmp) throws IOException {
        // #115. holdsAnyDocument asks about *documents* on purpose — an empty directory is
        // easy to come by, since a write that fails after createDirectories leaves exactly
        // one, and calling that a collision would poison the key permanently. clearTheWay
        // then removes those. Neither covers a folder holding entries that are not
        // documents: it was reported free, could not be cleared, and the write walked into
        // Files.writeString and died on IsADirectoryException — which MemoryTools reduces
        // to "Memory operation failed.", a message the model cannot act on.
        //
        // That is the exact failure requireTheKeyIsFree exists to prevent, and its own
        // javadoc says so. The asking happened; it just asked a question whose answer did
        // not predict whether the write could proceed.
        Path root = Files.createDirectory(tmp.resolve("mem"));
        Path folder = Files.createDirectory(root.resolve("a"));
        Files.createDirectories(folder.resolve("keep/me"));
        try {
            // Pointed at a real file, not a dangling one. A dangling link is the single
            // target shape for which following and not following agree, so the mutant that
            // makes holdsAnyDocument follow links escaped a test built on one — and it is
            // not equivalent: it flips the message to "other keys already live under it",
            // which contradicts #99, since a link is not a key.
            Files.writeString(root.resolve("elsewhere.md"), "E");
            Files.createSymbolicLink(folder.resolve("link.md"), root.resolve("elsewhere.md"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable here: " + e);
            return;
        }
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        // The distinguishing half, not the suffix all three MemoryNamespace messages share
        // — a mutant swapping this reason for "other keys already live under it" left the
        // suite green, and *which* reason the model gets is the whole of #115.
        assertThatThrownBy(() -> store.write("a", "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holding entries that are not documents")
                .hasMessageNotContaining("other keys already live under it");
        assertThatThrownBy(() -> store.append("a", "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holding entries that are not documents");
        // Refusing is not deleting, and the folders are the half that was not checked: the
        // first version decided depth-first and discovered the obstruction on the way back
        // up, so a refused write had already pruned every empty folder below it. Nothing
        // was lost — an empty folder holds nothing — but an operation that refuses should
        // refuse rather than half-succeed.
        assertThat(Files.exists(folder.resolve("link.md"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .isTrue();
        assertThat(Files.isDirectory(folder.resolve("keep/me"))).isTrue();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an empty tree at a key is still cleared, however deep")
    void anEmptyTreeIsClearedRatherThanRefused(@TempDir Path tmp) throws IOException {
        // The behaviour the refusal must not break, in its own test: it needs no symlinks,
        // and sharing one with the case above meant an environment without them discarded
        // this too. Deeper than any existing case, and under a nested key as well — an
        // empty folder is what a write that fails after createDirectories leaves, so it
        // must never become a permanent collision.
        Path root = Files.createDirectory(tmp.resolve("mem"));
        Files.createDirectories(root.resolve("a/b/c/d/e"));
        Files.createDirectories(root.resolve("nested/key/x/y"));
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        store.write("a", "A");
        store.append("nested/key", "K");

        assertThat(store.read("a")).contains("A");
        assertThat(store.read("nested/key")).contains("K");
        assertThat(store.list("")).containsExactlyInAnyOrder("a", "nested/key");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("and something that is not a link does it too")
    void anyNonDocumentEntryIsTheSameAnswer(@TempDir Path tmp) throws Exception {
        // The comment on the code used to say the case is symbolic links "since #99". It is
        // any entry that is neither a document nor a directory, and a FIFO has nothing to do
        // with #99.
        Path root = Files.createDirectory(tmp.resolve("mem"));
        Files.createDirectory(root.resolve("a"));
        Process mkfifo = new ProcessBuilder("mkfifo", root.resolve("a/pipe").toString())
                .redirectErrorStream(true).start();
        if (!mkfifo.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || mkfifo.exitValue() != 0) {
            org.junit.jupiter.api.Assumptions.abort("mkfifo unavailable here");
            return;
        }
        MemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);

        assertThatThrownBy(() -> store.write("a", "X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("choose another key")
                // Both refusals end this way now (#145 added one for a node at the key), so
                // the suffix alone stopped discriminating. The folder wording is what this
                // test is about.
                .hasMessageContaining("a folder stands there");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a folder nobody can read refuses rather than escaping as I/O")
    void anUnreadableSubtreeRefusesWithAReason(@TempDir Path root) throws Exception {
        // The sibling door, and the same one list() was rewritten for — with a comment in
        // that method saying so, while these two walks were left as they were. Files.walk
        // raises UncheckedIOException from its *terminal* operation for a directory it
        // cannot open; that is a RuntimeException, so no catch (IOException) could see it,
        // and write() still reached the model as "Memory operation failed." — the precise
        // outcome #115 exists to remove.
        //
        // Skipped as root for the same reason the listing test beside it is, and recorded
        // here rather than left out: the environment cannot reach the branch, and the
        // structural guarantee is that walkFileTree reports a failure through
        // visitFileFailed instead of raising from a terminal operation at all.
        FileMemoryStore store = new FileMemoryStore(root, Containment.BEST_EFFORT);
        Path locked = Files.createDirectories(root.resolve("a/locked"));
        Files.writeString(locked.resolve("hidden.md"), "H");
        Files.setPosixFilePermissions(locked, java.util.Set.of());
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !Files.isReadable(locked.resolve("hidden.md")),
                "this user can read a mode-000 directory (running as root), so the branch "
                        + "this test exists for cannot be reached here");

        assertThatThrownBy(() -> store.write("a", "X"))
                .as("the walk escaped as an I/O error the model cannot act on")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
