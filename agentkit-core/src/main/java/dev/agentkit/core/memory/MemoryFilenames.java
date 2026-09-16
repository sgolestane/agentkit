package dev.agentkit.core.memory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * What a memory key is called on disk.
 *
 * <p>{@link MemoryKeys} settles what a key <em>is</em>; this settles what a filesystem is
 * asked to hold for it. They are different questions because a key is a logical name and a
 * filename is bytes in a directory entry, encoded through {@code sun.jnu.encoding} — which
 * is ASCII under a C or POSIX locale, and a bare container sets no locale at all. Naming
 * the file after the key directly therefore made {@code facts/café.md} a key the
 * in-memory store accepted and the durable one refused, on the default configuration
 * (#96).
 *
 * <p>So the key is percent-encoded on the way to the filesystem: every code point outside
 * printable ASCII becomes its UTF-8 bytes as {@code %XX}, and {@code %} itself is escaped
 * so the mapping stays reversible. {@code /} is left alone, because it separates rather
 * than names. What lands on disk is pure ASCII on every host, so what the store can hold
 * no longer depends on the locale the JVM happened to start in.
 *
 * <h2>Why not hash, and why not simply document the locale</h2>
 *
 * <p>Hashing would cost the property that makes a memory directory worth having: an
 * operator can read it. Encoding keeps ASCII keys byte-for-byte unchanged — nearly all of
 * them, though not the ones carrying a {@code %}, which is the migration this costs — and
 * leaves {@code notes/caf%C3%A9.md} legible enough to recognise.
 *
 * <p>Documenting a required locale was the cheaper option and does not hold: it fixes the
 * deployment that reads the note, and the failure it leaves behind is silent until the day
 * a model names a file in a language other than English. Refusing to start under an ASCII
 * locale would be loud, but it would also stop the differential fuzzer from covering the
 * durable store in CI, which is a bare container — and that fuzzer is what found this.
 *
 * <p>Encoding only where the host cannot carry the name would avoid the migration, and
 * loses for a worse reason than it saves: the layout would then depend on the locale at
 * <em>write</em> time, so a root written under a UTF-8 locale and reopened in a bare
 * container goes half-invisible. That turns a refused write into documents the store wrote
 * and can no longer see, which is the wrong trade for a class whose whole purpose is state
 * outliving the process. It would not even avoid escaping {@code %}, since a raw
 * {@code caf%C3%A9} written under one locale would collide with the encoded spelling of
 * {@code café} written under another.
 *
 * <p>WTF-8 would keep the mapping total — {@code U+D800} as {@code %ED%A0%80} — and leave
 * {@link MemoryKeys} untouched. Rejected because the decoder would then have to refuse
 * WTF-8 written by anything else to stay canonical, and a key that cannot survive a JSON
 * round-trip is a landmine wherever it is stored. {@link MemoryKeys} refuses the halves
 * instead, for both stores at once.
 *
 * <h2>What this costs, and who enforces it</h2>
 *
 * <p>Length. Encoding costs three characters per UTF-8 byte, so against the 255-byte
 * component limit a segment holds about 42 accented characters, 28 CJK, or 21 emoji —
 * against 127, 85 and 63 for the raw name. {@link MemoryKeys} enforces that budget for
 * every store rather than letting the durable one discover it from {@code open} (#83), and
 * asks this class for the numbers, since a limit belongs with the encoding that spends
 * it.
 *
 * <h2>One name per key, in both directions</h2>
 *
 * <p>{@link #keyOf} answers only for names {@link #onDisk} would itself produce. That is
 * what keeps {@code list} reporting names {@code read} can find: a planted
 * {@code a%2Fb.md}, a lowercase {@code %c3%a9}, or a raw non-ASCII filename written by
 * something else all name no key, and are reported as ignored rather than handed to the
 * model as keys guaranteed to miss.
 */
final class MemoryFilenames {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * The longest a key, and any one segment of it, may be once written down.
     *
     * <p>Bytes rather than characters, and measured on the encoded name, because that is
     * what a directory entry holds: an accented character costs six here and an emoji
     * twelve, so a key well inside any character limit can be well past the byte one.
     *
     * <p>255 is the per-component limit on ext4, XFS, APFS and NTFS alike — not a portable
     * guarantee, but the number every filesystem this is likely to run on agrees about.
     * 1,024 for the whole key leaves a durable store room to sit under a deep root without
     * approaching {@code PATH_MAX}; a root path long enough to exhaust the rest is an
     * operator's configuration rather than something a model can name its way out of.
     *
     * <p>They live here rather than in {@link MemoryKeys} because this is the code that
     * decides what a name costs. {@code MemoryKeys} enforces the budget for every store —
     * that is the point, and why an in-memory store refuses an 86-character key of nothing
     * but {@code %} — but a budget stated apart from the encoding that spends it drifts the
     * first time the encoding changes.
     */
    static final int MAX_SEGMENT_BYTES = 255;
    static final int MAX_KEY_BYTES = 1_024;

    private MemoryFilenames() {
    }

    /**
     * The relative path {@code key} names on disk, for a key already in normal form.
     *
     * @throws IllegalArgumentException if {@code key} carries an unpaired surrogate
     */
    static String onDisk(String key) {
        StringBuilder out = new StringBuilder(key.length());
        key.codePoints().forEach(cp -> {
            if (cp == '/' || (cp >= 0x20 && cp <= 0x7E && cp != '%')) {
                out.appendCodePoint(cp);
                return;
            }
            if (cp <= 0xFFFF && Character.isSurrogate((char) cp)) {
                // Enforced here as well as in MemoryKeys, because this is the code that
                // depends on it: UTF-8 has no encoding for a lone surrogate, so String
                // .getBytes would substitute '?' and two different keys would land on one
                // file — the exact divergence between the stores that encoding exists to
                // remove.
                throw MemoryRefusal.NOT_A_KEY.of(
                        "key carries an unpaired surrogate and cannot be named on disk");
            }
            for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        });
        return out.toString();
    }

    /**
     * The key {@code name} holds, or empty if this store did not write it.
     *
     * <p>Empty rather than an exception: the root is a directory and anything may put a
     * file in it, so a name that is not one of ours is an ordinary fact about the
     * filesystem rather than an error.
     */
    static Optional<String> keyOf(String name) {
        if (name.isEmpty()) {
            // No key encodes to nothing, and this method answers only for names onDisk
            // produces. A directory entry cannot be empty either, so nothing reaches it
            // today — but a method whose contract is "the inverse of onDisk" should not
            // hand back a string that is not a key.
            return Optional.empty();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '%') {
                if (c > 0x7F) {
                    // A raw non-ASCII filename, from an older layout or another writer:
                    // read() looks for the encoded spelling, so reporting it as a key would
                    // hand out a name that cannot be resolved. Redundant with the canonical
                    // check at the end, which refuses it too — kept because deciding it here
                    // says what the loop means, and because the alternative is writing a
                    // truncated byte and relying on the decoder to find it malformed. No
                    // mutant can kill this line; it is not a second control.
                    return Optional.empty();
                }
                bytes.write(c);
                continue;
            }
            if (i + 2 >= name.length()) {
                return Optional.empty();
            }
            int high = Character.digit(name.charAt(i + 1), 16);
            int low = Character.digit(name.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                return Optional.empty();
            }
            bytes.write((high << 4) | low);
            i += 2;
        }
        String key;
        try {
            key = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return Optional.empty();
        }
        // Canonical or nothing. Decoding alone would accept several spellings of one key —
        // '%c3%a9' beside '%C3%A9', '%2F' beside '/', '%61' beside 'a' — and a store with
        // two names for a key is one where list() reports a file that read() then misses.
        return onDisk(key).equals(name) ? Optional.of(key) : Optional.empty();
    }
}
