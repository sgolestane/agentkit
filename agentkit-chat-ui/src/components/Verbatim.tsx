import { plain } from '../lib/text'

/**
 * Text exactly as somebody else wrote it.
 *
 * <h4>Why this is a component and not just "don't call Markdown"</h4>
 *
 * A ticket description, a comment, an uploaded log line and a tool's raw output are all
 * somebody else's words, and rendering them as markdown is wrong twice over. It is wrong about
 * what they are — a requester writing `**URGENT**` typed those asterisks and seeing them
 * matters when you are working out what they filed. And it hands whoever wrote them a
 * formatting vocabulary inside this console: a heading, a rule and a bold line are enough to
 * make a paragraph in a ticket look like something the framework said.
 *
 * <p>The workbench dashboard already made this choice — its comment reads "requester-authored text
 * deliberately does NOT come through here: it renders verbatim in a `pre`, because showing what
 * was filed matters more than making it pretty" — and it was one call site away from being
 * forgotten. Naming it makes it a decision somebody has to take rather than one they can drift
 * out of.
 *
 * <p>Nothing here is unsafe: React escapes text nodes. This does not use
 * `dangerouslySetInnerHTML`, and neither does {@link Markdown}.
 */
export function Verbatim({ text, label }: { text: string; label?: string }) {
  // Escaped by React, and made honest here: a bidi override renders inert text as something
  // other than what it says, which is the one attack that survives escaping. See lib/text.ts.
  const shown = plain(text)
  return (
    <figure className="my-2">
      {label ? (
        <figcaption className="mb-1 text-[11px] uppercase tracking-wide text-muted">
          {label}
        </figcaption>
      ) : null}
      <pre className="overflow-x-auto whitespace-pre-wrap break-words rounded-lg border border-line bg-canvas p-3 text-[12.5px]">
        {shown}
      </pre>
    </figure>
  )
}
