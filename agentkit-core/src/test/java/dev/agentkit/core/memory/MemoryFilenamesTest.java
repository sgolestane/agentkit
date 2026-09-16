package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The key-to-filename mapping on its own, rather than through the store.
 *
 * <p>{@code MemoryStoreTest} covers what an operator sees — a key with an accent in it
 * round-trips, and the directory holds ASCII. This covers the properties the store
 * <em>stands on</em>, which its own tests reach only where the store happens to exercise
 * them: that the mapping is injective, that it is a fixed point in one direction, and that
 * it refuses what it cannot represent even when the caller has already been checked.
 */
class MemoryFilenamesTest {

    @Test
    void asciiKeysAreTheirOwnFilenames() {
        // The reason encoding is affordable at all: nearly every key a model writes is
        // already ASCII, and a directory of them is unchanged by this.
        for (String key : List.of("a.md", "facts/user.md", "notes/2026-08-17.md",
                "a b.md", "x/y/z-1_2.txt")) {
            assertThat(MemoryFilenames.onDisk(key)).isEqualTo(key);
            assertThat(MemoryFilenames.keyOf(key)).contains(key);
        }
    }

    @Test
    void everyKeyComesBackAsItself() {
        for (String key : List.of("facts/café.md", "notes/😀.md", "100%", "%C3%A9",
                "a%2Fb.md", "ｆｕｌｌ.md", "é.md", "é.md")) {
            String name = MemoryFilenames.onDisk(key);
            assertThat(name.chars().allMatch(c -> c >= 0x20 && c <= 0x7E))
                    .as("'%s' encoded to something a C-locale filesystem still cannot name", key)
                    .isTrue();
            assertThat(MemoryFilenames.keyOf(name))
                    .as("key %s did not survive the round trip through '%s'", key, name)
                    .contains(key);
        }
    }

    @Test
    void twoKeysNeverShareAFilename() {
        // The property the whole fix rests on. A mapping that collides would trade a store
        // that refuses a key for one that silently overwrites another key's document —
        // strictly worse than the bug being fixed. The pairs here are the ones that collide
        // if '%' is left unescaped, or if a decomposed and a precomposed letter are folded.
        List<String> keys = List.of("a/b.md", "a%2Fb.md", "a%2fb.md", "é.md", "é.md",
                "100%.md", "100%25.md", "%.md");
        assertThat(keys.stream().map(MemoryFilenames::onDisk).distinct().count())
                .as("two distinct keys encoded to one filename")
                .isEqualTo(keys.size());
    }

    @Test
    void onlyTheCanonicalSpellingNamesAKey() {
        // list() reports what keyOf answers, and read() looks for what onDisk produces. A
        // second accepted spelling is therefore a listed key that cannot be read.
        assertThat(MemoryFilenames.keyOf("caf%c3%a9.md")).isEmpty();   // lowercase hex
        assertThat(MemoryFilenames.keyOf("%61.md")).isEmpty();         // an escaped 'a'
        // An escaped separator is not a spelling of 'a/b.md', which encodes to itself.
        assertThat(MemoryFilenames.keyOf("a%2Fb.md")).isEmpty();
        // The key that *does* contain a literal '%2F' is named with the '%' escaped, and
        // that name is canonical — so the two live side by side without colliding.
        assertThat(MemoryFilenames.onDisk("a%2Fb.md")).isEqualTo("a%252Fb.md");
        assertThat(MemoryFilenames.keyOf("a%252Fb.md")).contains("a%2Fb.md");
        assertThat(MemoryFilenames.keyOf("%zz.md")).isEmpty();         // not hex at all
        assertThat(MemoryFilenames.keyOf("truncated%C")).isEmpty();    // a cut-off escape
        assertThat(MemoryFilenames.keyOf("%C3%28")).isEmpty();         // valid hex, invalid UTF-8
    }

    @Test
    void aNameFromTheOlderLayoutIsNotAKey() {
        // The migration branch, and the one with the most deployments behind it. Both of
        // these were reachable keys before the encoding: a raw non-ASCII name wherever the
        // locale carried it, and a '%' name on every host without exception. Neither can be
        // planted through the filesystem under this suite's own locale — Path.of refuses the
        // first, which is #96 seen from the other side — but neither needs a filesystem to
        // test, because deciding it is a string question.
        assertThat(MemoryFilenames.keyOf("facts/café.md")).isEmpty();
        assertThat(MemoryFilenames.keyOf("50%.md")).isEmpty();
        assertThat(MemoryFilenames.keyOf("100%")).isEmpty();

        // And they are orphaned rather than silently rebound: the key still resolves, to the
        // encoded name, which is a different file from the one already sitting there.
        assertThat(MemoryFilenames.onDisk("50%.md")).isEqualTo("50%25.md");
    }

    @Test
    void aKeyThatUtf8CannotCarryIsRefusedHereToo() {
        // MemoryKeys rejects unpaired surrogates, so the store never reaches this — which is
        // exactly why it is worth pinning directly. Without a test at this level the guard
        // can be deleted with every suite still green, and the next caller that skips
        // MemoryKeys gets '?' substituted for the surrogate and two keys sharing one file.
        assertThatThrownBy(() -> MemoryFilenames.onDisk("a\uD800.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate");
        assertThatThrownBy(() -> MemoryFilenames.onDisk("\uDC00"))
                .isInstanceOf(IllegalArgumentException.class);
        // The pair is a character and encodes normally; only the halves alone do not.
        assertThat(MemoryFilenames.onDisk("😀")).isEqualTo("%F0%9F%98%80");
    }

    @Test
    void aStagingNameIsNotSomethingThisMappingCanReadBackAsAKey() {
        // The property the store's temporary names stand on, pinned at the level it is
        // decided rather than through a store that has to be caught mid-write to show it.
        //
        // FileMemoryStore stages every write, every append and every folder it creates at a
        // name beginning "%.write-", which is a MALFORMED percent-escape on purpose: keyOf
        // cannot decode it, so list() skips it, an operator hears about it as a file that is
        // not a key, and a read never resolves to it. A leftover from a crash is therefore
        // inert rather than a memory the model can be handed.
        //
        // Pinned here because it was not pinned anywhere: the mutant giving PinnedDirectory
        // a decodable prefix -- "tmpname-" -- passed every core and pentest test, because a
        // temporary only exists between two syscalls and no listing test ever looks during
        // one. NonDocumentAtAKeyTest plants a "%.write-" file, but it types the prefix out
        // rather than asking for one, so it holds the skipping and not the naming.
        assertThat(MemoryFilenames.keyOf(PinnedDirectory.freshName()))
                .as("a staging name decodes to a key, so list() would offer the model a name"
                        + " that is a half-written file or a folder this store is in the"
                        + " middle of making")
                .isEmpty();
        assertThat(PinnedDirectory.freshName())
                .isNotEqualTo(PinnedDirectory.freshName());
    }
}
