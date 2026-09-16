package dev.agentkit.core.memory;

import dev.agentkit.core.util.Quoted;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a {@link MemoryStore} key is, for every implementation of it.
 *
 * <p>Forward-slash separators, whitespace stripped from each segment, {@code ./} and
 * trailing slashes removed, {@code //} collapsed, {@code a/../b} folded to {@code b}.
 * Blank keys, absolute paths, the root, traversal above it, keys carrying characters
 * that would rewrite a listing, and keys carrying an unpaired surrogate are rejected with
 * {@link IllegalArgumentException}.
 *
 * <p>A key is one thing or implementations disagree about what the caller asked for, and
 * that disagreement is invisible where it matters: the in-memory store is what tests run
 * against, the file store is what deployments run against. {@code FileMemoryStore} then
 * hands the normalised key to {@code SafePaths} — this decides what a key <em>is</em>,
 * that decides whether it stays inside the root.
 *
 * <h2>Why segments are stripped before {@code .} and {@code ..} are folded</h2>
 *
 * <p>{@link #normalize} must be idempotent, because {@code list} relies on being able to
 * ask whether a key it is about to report is one a later {@code read} would resolve.
 * Stripping the whole string first does not give that: folding can <em>expose</em>
 * whitespace at a segment edge that the strip never saw, so the result needs stripping
 * again. {@code normalize("./  a.md")} returned {@code "  a.md"}, which normalises further
 * to {@code "a.md"} — so the in-memory store listed a key its own {@code read} missed, and
 * the file store wrote a document that nothing could afterwards reach.
 */
public final class MemoryKeys {

    private MemoryKeys() {
    }

    /** Whether {@code key} is already in normal form — what {@code list} may report. */
    public static boolean isNormalized(String key) {
        try {
            return normalize(key).equals(key);
        } catch (IllegalArgumentException notAKey) {
            return false;
        }
    }

    /** The normal form of {@code raw}, or {@link IllegalArgumentException} if it is not a key. */
    public static String normalize(String raw) {
        Objects.requireNonNull(raw, "path");
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw MemoryRefusal.NOT_A_KEY.of("path must not be blank");
        }
        if (isRooted(trimmed)) {
            throw MemoryRefusal.NOT_A_KEY.of("path must be relative: " + quoted(raw));
        }
        if (trimmed.codePoints().anyMatch(Quoted::restructuresALine)) {
            throw MemoryRefusal.NOT_A_KEY.of(
                    "path must not contain line or formatting characters: " + quoted(raw));
        }
        if (trimmed.codePoints().anyMatch(Quoted::isUnpairedSurrogate)) {
            throw MemoryRefusal.NOT_A_KEY.of(
                    "path must not contain an unpaired surrogate: " + quoted(raw));
        }
        // Segments are stripped before '.' and '..' are folded, not after. Folding collapses
        // neighbours away, so whitespace that was interior can become an edge — and a strip
        // that ran over the raw string would already have passed it. That is what made
        // normalize("./  a.md") return "  a.md", a key that normalises again; both isNormalized
        // and list() stand on this being a fixed point.
        List<String> folded = new ArrayList<>();
        for (String segment : trimmed.split("/", -1)) {
            String name = segment.strip();
            if (name.isEmpty() || name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                if (folded.isEmpty()) {
                    throw MemoryRefusal.NOT_A_KEY.of("path escapes memory: " + quoted(raw));
                }
                folded.remove(folded.size() - 1);
                continue;
            }
            folded.add(name);
        }
        if (folded.isEmpty()) {
            throw MemoryRefusal.NOT_A_KEY.of(
                    "path must denote a key inside memory: " + quoted(raw));
        }
        String key = String.join("/", folded);
        if (isRooted(key)) {
            // Asked again, of the folded result, because folding can *promote* a segment
            // into first position that the first check would have refused there:
            // 'a/../C:' folds to 'C:'. Without this, normalize returned a string normalize
            // itself rejects, so isNormalized said no to normalize's own output — and both
            // list() and the file store's skip rule stand on that being a fixed point. The
            // in-memory store listed 'C:' and threw on read('C:'); the durable one wrote a
            // file named 'C:' and listed nothing, and the model was told "Wrote memory
            // 'C:'" for a key it could never name again.
            throw MemoryRefusal.NOT_A_KEY.of("path must be relative: " + quoted(raw));
        }
        requireItFits(key, raw);
        return key;
    }

    /**
     * Rejects a key no store could durably hold, in the one place both stores ask.
     *
     * <p>The alternative was to let the durable store discover it: {@code Path.of} does not
     * check length, so the failure arrived from {@code open} as an I/O error, which
     * {@code MemoryTools} reports to the model as "Memory operation failed." The model
     * cannot act on that — the length that matters is not the length of the string it wrote,
     * and nothing in the failure says so. Here it is a refusal naming the limit and the
     * segment that broke it.
     *
     * <p>It follows the rule {@link Quoted#isUnpairedSurrogate} already sets: a key has to
     * survive
     * being written down, and deciding that in one place is what keeps the in-memory store —
     * the one tests run against — from accepting what the durable one cannot hold.
     */
    private static void requireItFits(String key, String raw) {
        // Length on disk, not in the key: MemoryFilenames emits pure ASCII, so its output
        // measures its own bytes. '/' is never escaped, so the segments still split on it.
        // The budget belongs to the class that spends it, so it is asked for rather than
        // restated here.
        String onDisk = MemoryFilenames.onDisk(key);
        if (onDisk.length() > MemoryFilenames.MAX_KEY_BYTES) {
            throw MemoryRefusal.NOT_A_KEY.of(
                    "path is too long: " + key.length() + " characters, which need "
                            + onDisk.length() + " bytes to store against a limit of "
                            + MemoryFilenames.MAX_KEY_BYTES + " — use a shorter name: "
                            + quoted(raw));
        }
        String[] keySegments = key.split("/");
        String[] storedSegments = onDisk.split("/");
        for (int i = 0; i < storedSegments.length; i++) {
            if (storedSegments[i].length() > MemoryFilenames.MAX_SEGMENT_BYTES) {
                // Counted in the characters the caller wrote as well as the bytes they cost.
                // A model told only that it spent "258 bytes once encoded" for a name it
                // wrote as 86 characters has been refused in a vocabulary it never used —
                // the very thing FileMemoryStore.inTheCallersVocabulary exists to prevent,
                // and this message is the only channel it has.
                throw MemoryRefusal.NOT_A_KEY.of(
                        "one part of the path is too long: " + keySegments[i].length()
                                + " characters, which need " + storedSegments[i].length()
                                + " bytes to store against a limit of "
                                + MemoryFilenames.MAX_SEGMENT_BYTES
                                + " — use a shorter name: " + quoted(raw));
            }
        }
    }

    /**
     * Whether {@code path} names a location rather than a key relative to one.
     *
     * <p>Asked of the string, not of {@link Path}, and that is the point. A key is a
     * logical name in a store that may not have a filesystem under it at all, but
     * {@code Path.of} answers in the host's {@code sun.jnu.encoding} — ASCII under a C
     * locale — so routing through it made {@code facts/café.md} a valid key on one machine
     * and an exception on the next, for the in-memory store as much as the durable one.
     * A durable store whose key vocabulary shifts with the locale is not one vocabulary.
     *
     * <p>Both spellings of a root, and the Windows drive prefix, since a key means the same
     * thing wherever it was written.
     */
    private static boolean isRooted(String path) {
        return path.startsWith("/") || path.startsWith("\\")
                || (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0)));
    }



    /**
     * {@code raw} rendered safe to put in a message.
     *
     * <p>These messages reach the model verbatim — {@code MemoryTools} returns
     * {@code getMessage()} as the tool result — so echoing a rejected key unescaped would
     * do the very thing the rejection is for, on the failure path.
     */
    private static String quoted(String raw) {
        // The same rule an operator's log needs, asked of the one place that states it.
        // It was written twice — here for the model, and nowhere for the log, which is how
        // a planted filename came to forge lines in an operator's warning (#98).
        //
        // The quote is escaped as well as the rest, because adding a delimiter and not
        // escaping it is the defect Quoted.each was written to fix one level down: a key
        // 'a'b' rendered as 'a'b', which reads as a key that ends after one character and
        // then some trailing text. Asked of Quoted so the two spellings cannot drift.
        return "'" + Quoted.of(raw).replace("'", "\\u0027") + "'";
    }
}
