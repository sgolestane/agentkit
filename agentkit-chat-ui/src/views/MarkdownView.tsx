import { Markdown } from '../components/Markdown'
import type { View } from '../lib/types'

/**
 * Prose a tool wants rendered.
 *
 * <h4>The one place markdown from a tool is right</h4>
 *
 * Everything else a tool hands back goes through {@link Verbatim}, because it is usually
 * somebody else's words. `View.markdown` is the explicit opposite: a tool saying "I wrote this
 * and I meant the formatting" — a summary with a list in it, a report with headings. The
 * distinction is the tool's to make and it makes it by choosing this kind.
 *
 * <p>It is the same {@link Markdown} the model's own answers use, so it inherits the sanitizing
 * schema from #342 rather than a second, looser copy of it. That matters here more than there:
 * a tool's markdown may be quoting a document, and the schema is what stops a quoted
 * `<img onerror>` from being anything but text.
 */
export function MarkdownView({ view }: { view: View }) {
  const text = String(view.data.text ?? '')
  if (!text.trim()) {
    return null
  }
  return (
    <div className="my-2" data-testid="markdown-view">
      <Markdown text={text} />
    </div>
  )
}
