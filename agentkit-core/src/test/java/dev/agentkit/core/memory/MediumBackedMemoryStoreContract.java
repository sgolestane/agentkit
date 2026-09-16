package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The extra clauses a {@link MemoryStore} owes when its keys live in a namespace something
 * other than the store can write to — a directory, a bucket, a table.
 *
 * <p>Every clause in {@link MemoryStoreContract} still applies; this adds the ones that only
 * mean anything once there is a medium. They are stated without naming a symbolic link, an
 * inode or a FIFO, because those are one filesystem's spelling of a question every shared
 * namespace has: <em>what does the store do when the thing at a key is not the thing it put
 * there?</em> #115 answered it for {@link FileMemoryStore} and for nobody else.
 *
 * <h2>Two answers, and which one applies is decided by one question</h2>
 *
 * <p>The question is whether the store could have written it.
 *
 * <ul>
 *   <li><strong>A document at a key-shaped name is a memory</strong>, whoever put it there.
 *       A durable root is shared across runs and across processes: a restored backup, an
 *       unpacked archive, a mounted volume of notes. Refusing those would make "durable"
 *       mean "written by this process", which is the opposite of the point.</li>
 *   <li><strong>Anything else at a key names nothing.</strong> The store answers for
 *       documents; a thing that is not one is not a memory it holds, so the four asking
 *       operations answer absence and the two writing ones refuse rather than destroying
 *       it. Refusing is not deleting — a store that did not create a thing has no business
 *       removing it — and the refusal is an {@link IllegalArgumentException} carrying a
 *       reason, because the caller can choose another key and a medium error tells it
 *       nothing it can act on.</li>
 * </ul>
 *
 * <p>That pair is what makes a planted entry safe to fuzz against a store with no medium at
 * all: absence is exactly what a map answers for a key it does not hold, so the two agree
 * on all four asking operations with nothing exempted. Only {@code write} and
 * {@code append} diverge, and there the divergence is <em>predictable</em> rather than
 * excused — see {@code MemoryStoreDifferentialTest} (#147).
 *
 * <h2>What an implementor has to supply</h2>
 *
 * <p>Two hooks, both about the medium rather than about the store: put something there that
 * the store did not put there, and say whether it is still there. A store whose medium
 * nothing else can write to should extend {@link MemoryStoreContract} directly instead —
 * these clauses would be vacuous for it, and a vacuous clause that passes is worse than one
 * that is absent.
 *
 * <h2>No exemption for a medium that cannot replace (#273)</h2>
 *
 * <p>{@link MemoryStore}'s SPI section states the one requirement a medium-backed store has
 * of its medium: it must be able to put a document at a key that already holds one in a
 * single step. A medium that cannot lets a key take a document once and refuses every
 * write that would replace it — so the clauses that put a document at a key already
 * holding one fail, and only those. Measured against a Jimfs 1.3.0
 * {@code Configuration.unix()} root: 24 of these 26 clauses pass, and the two that do not
 * are {@link #aDocumentTheStoreDidNotWriteIsStillAMemory} and
 * {@code MemoryStoreContract.appendConcatenates}, both arriving as
 * {@code FileAlreadyExistsException} out of {@code JimfsSecureDirectoryStream.move}.
 *
 * <p>There is no documented exemption for that, and the decision is to keep it so. A suite
 * that ships one teaches it: an implementor reading "these two clauses do not apply to a
 * medium without atomic replace" learns that revising a memory is a corner of this
 * contract, when it is the middle of it. A memory an agent can record and never revise is
 * not this store with a caveat, it is a different thing — and the two clauses that fail are
 * a fair sample of that rather than an unlucky one: one of them is plain {@code append},
 * the other is the promise that a document restored from a backup can be written to like
 * any other.
 *
 * <p>The other half of the reason is that no implementor could place themselves in such an
 * exemption honestly, because the property is not the medium's alone. Measured the same
 * way, the same Jimfs release passes all 26 on {@code Configuration.osX()} and all 26 on
 * {@code Configuration.windows()}: those configurations hand back a plain directory stream
 * rather than a {@link java.nio.file.SecureDirectoryStream}, so {@link FileMemoryStore}
 * takes its path-based fallback, whose {@code Files.move} asks for
 * {@code REPLACE_EXISTING} explicitly and gets it. The provider is the same one either
 * way; what differs is which of the store's two branches the medium steers it into — and
 * it is the branch with <em>less</em> containment that passes. So an exemption would have
 * to be keyed on a medium and a code path together, which is not something the author of
 * {@link #plant} knows about their own store.
 *
 * <p>The alternative that would be honest is a second, weaker contract interface — stores
 * that can be written to once per key, as a named thing with its own clauses. That is a
 * real commitment, and this question alone does not pay for it: no shipped medium fails the
 * requirement, so nothing would implement it.
 */
public abstract class MediumBackedMemoryStoreContract extends MemoryStoreContract {

    /** What can stand at a key that the store did not put there. */
    protected enum Foreign {

        /**
         * Something at the key itself that is not a document — for a filesystem, a symbolic
         * link, a FIFO, a socket, a device node, or a folder holding only those.
         */
        NOT_A_DOCUMENT_AT_THE_KEY,

        /**
         * The same, but at a component on the way to the key: {@code a} when the key is
         * {@code a/b.md}. Its own clause because #115 answered the first and left this one
         * open for three issues, and because it is the shape that costs most — the key
         * itself looks perfectly free.
         */
        NOT_A_DOCUMENT_ON_THE_WAY,

        /**
         * A readable document at the key's own name, put there by something other than this
         * store: a restored backup, a shared volume, an operator with an editor.
         */
        A_DOCUMENT_THE_STORE_DID_NOT_WRITE
    }

    /**
     * Puts {@code what} at {@code key} in the medium under {@link #store}, behind its back.
     *
     * @param content what {@link Foreign#A_DOCUMENT_THE_STORE_DID_NOT_WRITE} should hold;
     *     ignored by the other two kinds
     */
    protected abstract void plant(String key, Foreign what, String content) throws Exception;

    /** Whether whatever {@link #plant} put at {@code key} is still there. */
    protected abstract boolean stillPlantedAt(String key) throws Exception;

    @Test
    protected void somethingThatIsNotADocumentNamesNothing() throws Exception {
        plant("a/b.md", Foreign.NOT_A_DOCUMENT_AT_THE_KEY, null);

        assertThat(store.read("a/b.md")).as("read answered for a thing that is not a document").isEmpty();
        assertThat(store.exists("a/b.md")).isFalse();
        assertThat(store.delete("a/b.md")).as("delete claimed to forget what was never held").isFalse();
        assertThat(store.list("")).doesNotContain("a/b.md");

        // Asking did not remove it. delete answering false and then removing it anyway
        // would be the worst of both: the caller told nothing happened, and the operator's
        // alias gone.
        assertThat(stillPlantedAt("a/b.md")).as("an asking operation destroyed it").isTrue();
    }

    @Test
    protected void writingRefusesAKeyThatIsNotFreeAndDestroysNothing() throws Exception {
        plant("a/b.md", Foreign.NOT_A_DOCUMENT_AT_THE_KEY, null);

        // IllegalArgumentException and not a medium error: another key works, and that is
        // an answer the caller can act on. MemoryTools hands this message to the model and
        // reduces everything else to "Memory operation failed."
        assertThatThrownBy(() -> store.write("a/b.md", "mine"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append("a/b.md", "mine"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(stillPlantedAt("a/b.md")).as("a refused write removed what it refused").isTrue();
        assertThat(store.read("a/b.md")).isEmpty();

        // A sibling key is unaffected, which is what makes "choose another key" true.
        store.write("a/c.md", "mine");
        assertThat(store.read("a/c.md")).contains("mine");
    }

    @Test
    protected void somethingOnTheWayToAKeyRefusesTheWriteAndHidesNothing() throws Exception {
        store.write("kept.md", "kept");
        plant("a", Foreign.NOT_A_DOCUMENT_ON_THE_WAY, null);

        assertThat(store.read("a/b.md")).isEmpty();
        assertThat(store.exists("a/b.md")).isFalse();
        assertThat(store.delete("a/b.md")).isFalse();
        assertThatThrownBy(() -> store.write("a/b.md", "mine"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.append("a/b.md", "mine"))
                .isInstanceOf(IllegalArgumentException.class);

        // Said of the WHOLE key, not of its last name. A store that only looked at the last
        // name would answer for 'a/b.md' through whatever 'a' really is — the shape that
        // makes read answer, list stay silent, and delete unlink a document a different key
        // names (#99).
        assertThat(store.list("")).doesNotContain("a/b.md").contains("kept.md");
        assertThat(stillPlantedAt("a")).isTrue();
    }

    @Test
    protected void aDocumentTheStoreDidNotWriteIsStillAMemory() throws Exception {
        plant("found.md", Foreign.A_DOCUMENT_THE_STORE_DID_NOT_WRITE, "restored from backup");

        // All four asking operations see it, and list reports it, so the list-then-read
        // loop in MemoryStoreContract#everyListedKeyCanBeRead holds over it too.
        assertThat(store.read("found.md")).contains("restored from backup");
        assertThat(store.exists("found.md")).isTrue();
        assertThat(store.list("")).contains("found.md");

        // Writable and deletable like any other memory: "durable" cannot mean "written by
        // this process" or a store would go blind after every restart it is meant to
        // survive.
        store.append("found.md", " plus mine");
        assertThat(store.read("found.md")).contains("restored from backup plus mine");
        assertThat(store.delete("found.md")).isTrue();
        assertThat(store.exists("found.md")).isFalse();
    }
}
