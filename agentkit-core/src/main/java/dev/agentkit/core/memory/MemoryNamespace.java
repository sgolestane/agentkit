package dev.agentkit.core.memory;

/**
 * Whether a key can hold a document, given the keys already holding one.
 *
 * <p>Memory keys are a hierarchy — that is what {@code list("notes/")} means — and in a
 * hierarchy a name is a document or a place documents live, never both. A filesystem
 * enforces that for free and a map does not, which is where the two stores parted company:
 * {@code write("a")} then {@code write("a/b")} left the in-memory store holding both and
 * threw {@code FileAlreadyExistsException} on disk (#83).
 *
 * <p>Deciding it here rather than in each store is what makes the rule the same rule. The
 * alternative considered was to let the durable store keep discovering it and translate
 * whatever the filesystem threw, which fails twice over: the exception a platform picks for
 * this is not portable, and translating it still leaves the in-memory store — the one tests
 * run against — silently accepting what production refuses.
 *
 * <h2>Why this is a refused argument rather than a failed write</h2>
 *
 * <p>The model chose the key and can choose another, so the refusal is worth a reason it
 * can act on: {@code MemoryTools} passes an {@link IllegalArgumentException} message
 * through to the model and reduces everything else to "Memory operation failed." A
 * collision reported as an I/O error is a dead end; reported as "'notes' already holds a
 * document" it is a retry.
 */
final class MemoryNamespace {

    private MemoryNamespace() {
    }

    /**
     * Refuses {@code key} if it collides with the hierarchy the stored keys already form.
     *
     * <p>Both directions, and both asked of <em>keys</em>. That is the whole predicate
     * rather than half of it: an earlier version left "does anything live under this" to
     * each store, and they answered differently — the map asked whether a key lived there,
     * the durable store asked whether a directory existed. An empty directory made them
     * disagree, and one is easy to come by, since a write that fails after
     * {@code createDirectories} leaves exactly that. Asking both stores the same question
     * about keys is what makes an empty folder harmless.
     *
     * @param holdsDocument whether that exact key holds a document
     * @param hasKeysUnder  whether any key lives below the given one
     */
    static void requireFree(String key,
                            java.util.function.Predicate<String> holdsDocument,
                            java.util.function.Predicate<String> hasKeysUnder) {
        int cut = key.indexOf('/');
        while (cut >= 0) {
            String ancestor = key.substring(0, cut);
            if (holdsDocument.test(ancestor)) {
                throw MemoryRefusal.HIERARCHY_COLLISION.of(
                        "cannot write '" + key + "': '" + ancestor + "' already holds a"
                                + " document, and a key cannot be both a document and a folder");
            }
            cut = key.indexOf('/', cut + 1);
        }
        if (hasKeysUnder.test(key)) {
            throw MemoryRefusal.HIERARCHY_COLLISION.of(
                    "cannot write '" + key + "': other keys already live under it,"
                            + " and a key cannot be both a document and a folder");
        }
    }
}
