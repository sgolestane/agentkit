package dev.agentkit.core.memory;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A non-persistent {@link MemoryStore} backed by a map. Useful for tests and for
 * a supervisor to hand a subagent scratch memory that vanishes with the process.
 * Not thread-safe.
 *
 * <p>Keys go through {@link MemoryKeys}, as they do in {@link FileMemoryStore}, so the
 * store a test runs against answers the same way as the one a deployment runs against.
 * That is worth stating because this is the one usually under test: a divergence here
 * shows up in production and nowhere else.
 *
 * <p>Values go through {@link #requireItCanBeWrittenDown} for the same reason, which is
 * newer and was found the way divergences here are supposed to be found — see there.
 */
public final class InMemoryMemoryStore implements MemoryStore {

    private final TreeMap<String, String> store = new TreeMap<>();

    private static String normalize(String path) {
        return MemoryKeys.normalize(path);
    }

    /**
     * Refuses a value no store could write down, rather than being the one store that can.
     *
     * <p><strong>The defect.</strong> A {@code TreeMap} holds any {@code String}, including
     * one carrying half a surrogate pair; UTF-8 cannot encode one, so
     * {@link FileMemoryStore} refuses it. Measured on main, with nothing planted and no
     * race:
     *
     * <pre>
     *   write("a.md", U+D800)                in-memory ok   file UncheckedIOException
     *   append("a.md", U+D800)               in-memory ok   file UncheckedIOException
     *   append(U+D800) onto an existing key  in-memory ok   file UncheckedIOException
     * </pre>
     *
     * <p>This is #83's defect in the value domain instead of the key domain, and pointing
     * the more dangerous way: the store <em>under test</em> accepted what the store in
     * production refuses, so a memory an agent was told it had written existed only on the
     * developer's machine. {@code FileMemoryStore.encodeStrictly} named this exact hole
     * three issues before it was closed — "exactly the divergence
     * {@code MemoryStoreDifferentialTest} exists to catch and does not, since it fuzzes
     * keys rather than content" — and the fuzzer stayed green because its whole content
     * vocabulary was {@code "v" + a number}. Closing that is #147; this is what #147 then
     * found.
     *
     * <p><strong>Why refusing, rather than substituting or accepting.</strong> This is
     * {@link MemoryKeys}' argument for keys, applied to values. Substituting is what
     * {@code String.getBytes(UTF_8)} does — {@code 0x3F}, a literal {@code '?'}, silently —
     * so a store that persisted the value would lose it and report success. Accepting is
     * what this class did, and it makes the map the only store in existence that can hold
     * the value, which is worse than either: nothing that reads a memory back can rely on
     * the value having survived, and the failure appears on the first deployment rather
     * than in any test.
     *
     * <p>The cost is one encoder allocation and one encode of the value per write, on a
     * class that is explicitly not thread-safe so the encoder cannot be shared.
     * {@code CharsetEncoder.canEncode} would avoid the buffer but answers a boolean, so the
     * cause would have to be invented rather than reported; {@link FileMemoryStore} pays
     * the same encode and keeps the exception, and matching it is the point.
     *
     * <p><strong>The channel is chosen now, not copied</strong> (#245). This used to raise
     * {@link java.io.UncheckedIOException}, and said so as a named residual: it was what
     * {@link FileMemoryStore} raised, and matching it exactly was what let
     * {@code MemoryStoreDifferentialTest} assert plain agreement rather than an exemption.
     * It was the wrong channel for both, and visibly wrong here — a store with no medium
     * announcing that its medium refused. Both raise {@link IllegalArgumentException} now,
     * through {@link MemoryValues}, which is one spelling of the message rather than two;
     * they moved together, because a store refusing in a different channel from its sibling
     * would have been a second divergence introduced to fix the first.
     */
    private static void requireItCanBeWrittenDown(String content) {
        CharsetEncoder strict = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            strict.encode(CharBuffer.wrap(content));
        } catch (CharacterCodingException cannotBeWrittenDown) {
            throw MemoryValues.cannotBeWrittenDown(content, cannotBeWrittenDown);
        }
    }

    /** {@code prefix} in the vocabulary the keys are in — see {@code FileMemoryStore}. */
    private static String scopeOf(String prefix) {
        if (prefix.isBlank()) {
            return "";
        }
        try {
            String key = MemoryKeys.normalize(prefix);
            return prefix.strip().endsWith("/") ? key + "/" : key;
        } catch (IllegalArgumentException notAKey) {
            return prefix.strip();
        }
    }

    @Override
    public Optional<String> read(String path) {
        return Optional.ofNullable(store.get(normalize(path)));
    }

    @Override
    public void write(String path, String content) {
        Objects.requireNonNull(content, "content");
        String key = normalize(path);
        requireTheKeyIsFree(key);
        // Last, because FileMemoryStore encodes last: it validates the key, then the
        // namespace, and only reaches encodeStrictly inside the write itself. A check
        // placed earlier here would make write(badKey, badValue) refuse for the VALUE in
        // this store and for the KEY in that one -- a new divergence introduced by the fix
        // for a divergence, which is the shape #83 warns about.
        //
        // That used to be a divergence in CHANNEL, which is what the differential fuzzer
        // compared, and since #245 it is one in the reason only. This paragraph used to end
        // "and the fuzzer would not report it", which was true when #256 wrote it and is
        // not true now: #257 gave every refusal in this package a MemoryRefusal and the
        // fuzzer compares it alongside the channel. Measured, by moving this line above the
        // normalize() two lines up and running theTwoStoresAgree:
        //
        //   WRITE("C:", "before\uD800after")   in-memory refused:VALUE_IS_NOT_TEXT
        //                                      file      refused:NOT_A_KEY
        //
        // sequence 2 of 300, and the same mutant under the channel-only classification the
        // fuzzer used before #257 leaves all 300 sequences green. So the ordering is
        // checked rather than merely written down, which is why the sentence is corrected
        // here rather than deleted. It would matter either way, because the reason is the
        // whole point of that channel: a model told to fix its value when what is wrong is
        // its key has been sent the wrong way.
        requireItCanBeWrittenDown(content);
        store.put(key, content);
    }

    @Override
    public void append(String path, String content) {
        Objects.requireNonNull(content, "content");
        String key = normalize(path);
        requireTheKeyIsFree(key);
        requireItCanBeWrittenDown(content);   // last, for the reason write() gives
        store.merge(key, content, String::concat);
    }

    /**
     * Refuses a key that collides with the hierarchy the keys already form.
     *
     * <p>A map has no directories, so without this the store accepted {@code "a"} and
     * {@code "a/b"} side by side while the durable one refused the pair — and this is the
     * store under test, so the divergence surfaced only in production (#83). The check is
     * the same rule {@link MemoryNamespace} states for both.
     */
    private void requireTheKeyIsFree(String key) {
        // One lookup rather than a scan for the second question: keys sort
        // lexicographically, so the first key at or after "key/" is the only candidate for
        // living under it.
        MemoryNamespace.requireFree(key, store::containsKey, folder -> {
            String under = store.ceilingKey(folder + "/");
            return under != null && under.startsWith(folder + "/");
        });
    }

    @Override
    public boolean delete(String path) {
        return store.remove(normalize(path)) != null;
    }

    @Override
    public boolean exists(String path) {
        return store.containsKey(normalize(path));
    }

    @Override
    public List<String> list(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String scope = scopeOf(prefix);
        List<String> keys = new ArrayList<>();
        for (String key : store.keySet()) {
            if (key.startsWith(scope)) {
                keys.add(key);
            }
        }
        return keys;
    }
}
