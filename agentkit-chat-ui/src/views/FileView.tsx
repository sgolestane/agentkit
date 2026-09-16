import { sized } from '../components/Attachments'
import type { View } from '../lib/types'

/**
 * Something a tool produced that is a file rather than a paragraph.
 *
 * A generated report, a captured eval case, an exported CSV. The point is that it is
 * downloadable: an answer that says "I wrote the file" and does not hand it over is one the
 * person then has to go and find on a server they may not have.
 *
 * <p>The href is built here from the id rather than sent as a URL by the tool, which is what
 * makes it safe to render as a link at all — a tool cannot point this at another origin,
 * because the only thing it supplies is an identifier this console appends to its own path.
 */
export function FileView({ view }: { view: View }) {
  const id = String(view.data.id ?? '')
  const name = String(view.data.name ?? '') || id
  const mediaType = String(view.data.mediaType ?? '')
  const bytes = typeof view.data.bytes === 'number' ? view.data.bytes : 0

  if (!id) {
    return <p className="my-2 text-xs text-muted">A file with no identifier.</p>
  }
  return (
    <a
      href={`/api/attachments/${encodeURIComponent(id)}`}
      download={name}
      className="my-2 inline-flex items-center gap-2 rounded-lg border border-line px-3 py-2 text-sm hover:bg-panel"
      data-testid="file-view"
    >
      <span className="truncate max-w-64" title={name}>
        {name}
      </span>
      <span className="shrink-0 text-xs text-muted">
        {mediaType ? `${mediaType} · ` : ''}
        {sized(bytes)}
      </span>
    </a>
  )
}
