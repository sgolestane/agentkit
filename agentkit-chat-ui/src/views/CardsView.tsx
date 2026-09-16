import { plain } from '../lib/text'
import type { View } from '../lib/types'

/**
 * One record, or a few, as labelled fields.
 *
 * <h4>The shape a table is wrong for</h4>
 *
 * A table answers "how do these compare"; a card answers "what is this one". A ticket with a
 * key, a status, an assignee and a four-paragraph description is a one-row table with a column
 * nobody can read — and the workbench's ticket, its triage verdict and its automation rule are all that
 * shape. This is what `View.cards` has been producing since #336, into a console that drew it
 * as a collapsed JSON blob because nothing here claimed the kind.
 *
 * <h4>Field values are verbatim, always</h4>
 *
 * A field is somebody else's words far more often than not — a summary, a description, a plan a
 * model wrote about a stranger's request. So values render the way {@link Verbatim} renders,
 * for the reasons written there: markdown is wrong about what they are, and it hands whoever
 * wrote them a formatting vocabulary inside this console. `whitespace-pre-wrap` rather than a
 * `pre` block, because a card is a definition list and a description that keeps its paragraphs
 * is the point; a `pre` would also keep the leading indentation of every wrapped line.
 *
 * <h4>The link is a link and nothing else</h4>
 *
 * `url` is where this record actually lives — the ticket in Jira. It is rendered only when it
 * is `http:` or `https:`; a `javascript:` URL in a card is the same attack as one in a
 * markdown link, and the card's data comes from a tool that may be quoting a stranger.
 */
export function CardsView({ view }: { view: View }) {
  const cards = Array.isArray(view.data.cards) ? view.data.cards : []
  if (cards.length === 0) {
    return <p className="my-2 text-xs text-muted">Nothing to show.</p>
  }
  return (
    <div className="my-2 flex flex-col gap-2" data-testid="cards-view">
      {cards.map((raw, index) => (
        <Card key={index} card={(raw ?? {}) as Record<string, unknown>} />
      ))}
    </div>
  )
}

function Card({ card }: { card: Record<string, unknown> }) {
  // Made honest before it is drawn: a card is what somebody reads instead of the record.
  const title = plain(String(card.title ?? ''))
  const subtitle = plain(String(card.subtitle ?? ''))
  const fields = card.fields && typeof card.fields === 'object' ? card.fields : {}
  const entries = Object.entries(fields as Record<string, unknown>)
  const href = linkable(card.url)

  return (
    <figure className="rounded-lg border border-line" data-testid="card">
      <figcaption className="flex flex-wrap items-baseline gap-x-2 border-b border-line px-3 py-2">
        {title ? <span className="font-semibold">{title}</span> : null}
        {subtitle ? (
          <span className="min-w-0 flex-1 whitespace-pre-wrap break-words text-sm text-muted">
            {subtitle}
          </span>
        ) : null}
        {href ? (
          <a
            href={href}
            target="_blank"
            rel="noreferrer noopener"
            className="shrink-0 text-xs text-muted underline hover:text-ink"
          >
            open
          </a>
        ) : null}
      </figcaption>
      {entries.length > 0 ? (
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 px-3 py-2 text-sm">
          {entries.map(([name, value]) => (
            <div key={name} className="contents">
              <dt className="whitespace-nowrap text-[11px] uppercase tracking-wide text-muted">
                {name}
              </dt>
              <dd className="min-w-0 whitespace-pre-wrap break-words">{shown(value)}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </figure>
  )
}

/** A field, as a person should read it — never as markdown, and never as `[object Object]`. */
function shown(value: unknown): string {
  return plain(rendered(value))
}

function rendered(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  if (typeof value === 'number') {
    return new Intl.NumberFormat().format(value)
  }
  if (typeof value === 'boolean') {
    return value ? 'yes' : 'no'
  }
  if (typeof value === 'object') {
    // A tool put something structured in a field. Showing its JSON is ugly and honest; the
    // default `String(...)` would have shown `[object Object]`, which tells a person nothing
    // and tells whoever wrote the tool nothing either.
    return JSON.stringify(value)
  }
  return String(value)
}

/**
 * The URL, if it is one a browser should follow.
 *
 * Parsed rather than prefix-matched: `URL` normalises the scheme, so ` JavaScript:alert(1)`
 * and `jAvAsCrIpT:alert(1)` both come back as `javascript:` and are refused, where a
 * `startsWith('http')` check passes the first one and a `javascript:` one that begins with a
 * newline.
 */
export function linkable(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.trim() === '') {
    return null
  }
  try {
    const url = new URL(raw, window.location.origin)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null
  } catch {
    return null
  }
}
