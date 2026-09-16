package dev.agentkit.core.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileMemoryStore} against the contract, including the clauses that only mean
 * something once a medium is under the store.
 *
 * <p>The planted entry is a dangling symbolic link, which is the cheapest thing a
 * filesystem offers that is reachable by name and is not a document. It is one shape of
 * several: a link to a document, a link to a folder, a link out of the root, a
 * self-referencing link, a folder holding only links, a FIFO, a socket and a device node
 * all have to answer the same way. Those are exercised exhaustively in
 * {@code MemoryStoreTest.everyShapeOfLinkAnswersTheSameOnEveryOperation},
 * {@code NonDocumentAtAKeyTest} and — planted at random, against the map store as an oracle
 * — {@code MemoryStoreDifferentialTest}. What belongs <em>here</em> is the clause a third
 * party has to satisfy, stated once and in terms their medium can answer, rather than this
 * repository's catalogue of filesystem shapes.
 */
class FileMemoryStoreContractTest extends MediumBackedMemoryStoreContract {

    @TempDir
    Path tempDir;

    private Path root;

    @Override
    protected MemoryStore newStore() throws IOException {
        root = Files.createDirectories(tempDir.resolve("mem"));
        return new FileMemoryStore(root, Containment.BEST_EFFORT);
    }

    /**
     * The on-disk name a key is spelled as, so a plant lands where the store will look.
     *
     * <p>Through {@link MemoryFilenames} rather than {@code root.resolve(key)}: since #96 a
     * key is percent-encoded on the way to the filesystem, and a plant that skipped the
     * encoding would sit at a name the store never consults — a clause that passes because
     * nothing was ever planted.
     */
    private Path onDisk(String key) {
        return root.resolve(MemoryFilenames.onDisk(MemoryKeys.normalize(key)));
    }

    @Override
    protected void plant(String key, Foreign what, String content) throws IOException {
        Path at = onDisk(key);
        Files.createDirectories(at.getParent());
        switch (what) {
            case NOT_A_DOCUMENT_AT_THE_KEY, NOT_A_DOCUMENT_ON_THE_WAY ->
                    Files.createSymbolicLink(at, Path.of("nothing-is-here"));
            case A_DOCUMENT_THE_STORE_DID_NOT_WRITE -> Files.writeString(at, content);
        }
    }

    @Override
    protected boolean stillPlantedAt(String key) {
        // NOFOLLOW_LINKS, because the question is whether the NAME is still there. Asked
        // without it, a dangling link answers "gone" while it is sitting exactly where it
        // was — so the clause about a refusal not deleting anything would pass over a store
        // that deleted it.
        return Files.exists(onDisk(key), LinkOption.NOFOLLOW_LINKS);
    }
}
