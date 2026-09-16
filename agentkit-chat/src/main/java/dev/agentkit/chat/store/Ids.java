package dev.agentkit.chat.store;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The ids this module mints, and the one check that keeps them usable as filenames.
 *
 * <p>Shaped after {@code WorkbenchStore.Ids} in the Workbench example — a prefix and a counter,
 * readable in a log and in a URL. What is added here is {@link #isSafe}, because
 * {@link FileChatStore} puts an id in a path.
 *
 * <p><strong>Minting an id safely is not the same as reading one safely.</strong> Every id
 * this class produces is {@code [a-z]+-[0-9]+} and could not traverse anything. But a store
 * is also asked for ids it did not mint — an HTTP path parameter, a tool argument, a
 * hand-edited file — and {@code conversations/../../etc/passwd.json} is a read of an
 * arbitrary file. So the check is at the point of use rather than the point of creation,
 * which is the only place it can be true.
 */
public final class Ids {

    private static final AtomicLong COUNTER = new AtomicLong();

    /**
     * What may appear in an id used as a filename.
     *
     * <p>No dot, deliberately. Dropping it costs nothing — nothing this module mints has one —
     * and it removes {@code .} and {@code ..} from the alphabet entirely rather than
     * special-casing them, which is the difference between a rule and a list of exceptions.
     */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Ids() {
    }

    public static String next(String prefix) {
        return prefix + '-' + COUNTER.incrementAndGet();
    }

    /**
     * Moves the counter past an id that already exists, so a restart cannot mint it again.
     *
     * <h4>The bug this exists to prevent, which is data loss and not a collision warning</h4>
     *
     * <p>{@link #COUNTER} is a process-local {@code AtomicLong} starting at zero. A file store
     * loading {@code conv-1 … conv-5} from disk left it at zero, so the first conversation
     * created after a restart was minted {@code conv-1} — the id of one that already existed.
     * It replaced it in the map and then {@code changed()} wrote over its file. Not a
     * duplicate: the older conversation, its turns and its trace were gone, and nothing said
     * so.
     *
     * <p>Called for every id read back off disk, before anything can be created. The numeric
     * suffix is what is compared; an id with none is ignored, since nothing here will mint a
     * clashing one.
     */
    public static void observe(String id) {
        if (id == null) {
            return;
        }
        int dash = id.lastIndexOf('-');
        if (dash < 0 || dash == id.length() - 1) {
            return;
        }
        long seen;
        try {
            seen = Long.parseLong(id.substring(dash + 1));
        } catch (NumberFormatException notOurs) {
            return;
        }
        COUNTER.accumulateAndGet(seen, Math::max);
    }

    /** Whether {@code id} may be used to build a path. */
    public static boolean isSafe(String id) {
        return id != null && SAFE.matcher(id).matches();
    }

    /**
     * {@code id} if it may be used to build a path, or a refusal naming what is wrong.
     *
     * <p>Throws rather than returning empty because every caller is about to touch a file
     * with it: a store that quietly returned "not found" for a traversal attempt would give
     * the same answer as a missing conversation, and the two want different treatment at the
     * edge — one is a 404 and the other is somebody trying something.
     */
    public static String requireSafe(String id, String what) {
        if (isSafe(id)) {
            return id;
        }
        throw new IllegalArgumentException(what + " must be 1-64 characters of letters, digits,"
                + " '_' or '-', because it names a file: "
                + dev.agentkit.core.util.Quoted.of(
                        dev.agentkit.core.util.Cut.to(String.valueOf(id), 120)));
    }
}
