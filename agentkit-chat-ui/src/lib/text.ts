/**
 * The one place text somebody else wrote is made safe to *look at*.
 *
 * <h4>Escaping is not the whole of it</h4>
 *
 * React escapes text nodes, so nothing here can become markup — that is settled, and
 * `escaping.test.tsx` holds it. This is the other half: text that is inert and still lies about
 * what it says.
 *
 * Unicode's explicit bidirectional formatting characters reorder the glyphs around them without
 * changing the string. `U+202E` (right-to-left override) makes `gpj.exe` render as `exe.jpg`;
 * `U+202A`…`U+2069` can move a whole clause somewhere else in the line. The string a person
 * approves and the string that runs are then different things, and the difference is invisible
 * — this is Trojan Source (CVE-2021-42574), and it is a display attack, not a parsing one, so
 * no amount of sanitizing markup addresses it.
 *
 * It matters most on the approval card. That card exists so a person can read a tool call
 * before it happens; a ticket key or a comment body that renders as something other than what
 * it is defeats the entire point of showing it to them.
 *
 * <h4>Removed, and only these</h4>
 *
 * The explicit formatting characters, and nothing else:
 *
 * - `U+202A`–`U+202E` — the embeddings and the two overrides
 * - `U+2066`–`U+2069` — the isolates
 * - `U+200E`, `U+200F` — the marks
 *
 * Not `\p{Cf}` wholesale, which is what `Spotlight` strips on the model's side and would be
 * wrong here: `U+200D` (zero-width joiner) and `U+200C` are how emoji sequences and several
 * Indic and Arabic scripts are written, and a console that stripped them would mangle
 * legitimate names to defend against a different character. The model's side can afford to be
 * blunt because it is normalising for a machine; a person is reading this one.
 *
 * Removed rather than escaped or replaced with a visible marker. A marker would be more
 * informative and would also be a thing the console draws inside somebody else's words, which
 * is its own small lie; and the overwhelmingly common case is that these characters are an
 * attack rather than an accident, because ordinary text has no use for an unbalanced override.
 */
const BIDI_SOURCE = '[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]'

/** `text`, with anything that would make it render as something else removed. */
export function plain(text: string): string {
  // A fresh regex per call. A `g` flag carries `lastIndex` between calls, and a shared one
  // would make the second call on the same string behave differently from the first — which is
  // the kind of bug that shows up as "it strips it sometimes".
  return text.replace(new RegExp(BIDI_SOURCE, 'gu'), '')
}

/** Whether a string carries any of them — for a test, and for anything that wants to say so. */
export function reorders(text: string): boolean {
  return new RegExp(BIDI_SOURCE, 'u').test(text)
}
