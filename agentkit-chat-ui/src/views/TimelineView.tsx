import { plain } from '../lib/text'
import type { View } from '../lib/types'

/**
 * What happened, in the order it happened.
 *
 * <h4>Why a table is the wrong drawing for this</h4>
 *
 * A run is a sequence, and the two questions a person asks of one are "where did it stop" and
 * "what did it do before that" — both of which are about order. A table invites sorting, and a
 * timeline sorted by tool name is not a timeline. So the order is the data and nothing here
 * reorders it.
 *
 * <p>The other half is failure. A run that ended badly ended at a particular moment, and a
 * drawing that shows twelve identical rows makes the reader find it. A failed moment is marked,
 * so the eye goes there first.
 */
export function TimelineView({ view }: { view: View }) {
  const moments = Array.isArray(view.data.moments) ? view.data.moments : []
  const title = String(view.data.title ?? '')
  if (moments.length === 0) {
    return <p className="my-2 text-xs text-muted">Nothing happened yet.</p>
  }
  return (
    <figure className="my-2 rounded-lg border border-line" data-testid="timeline-view">
      {title ? (
        <figcaption className="border-b border-line px-3 py-2 text-sm font-semibold">
          {title}
        </figcaption>
      ) : null}
      <ol className="flex flex-col">
        {moments.map((raw, index) => (
          <Moment
            key={index}
            moment={(raw ?? {}) as Record<string, unknown>}
            last={index === moments.length - 1}
          />
        ))}
      </ol>
    </figure>
  )
}

function Moment({ moment, last }: { moment: Record<string, unknown>; last: boolean }) {
  const kind = plain(String(moment.kind ?? ''))
  const label = plain(String(moment.label ?? ''))
  const detail = plain(String(moment.detail ?? ''))
  const at = plain(String(moment.at ?? ''))
  const failed = moment.failed === true

  return (
    <li
      className={`flex gap-3 px-3 py-2 ${last ? '' : 'border-b border-line'}`}
      data-testid="moment"
    >
      <span
        aria-hidden="true"
        className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${failed ? 'bg-bad' : 'bg-line'}`}
      />
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-x-2">
          {kind ? (
            <span className="text-[11px] uppercase tracking-wide text-muted">{kind}</span>
          ) : null}
          <span className={`text-sm ${failed ? 'text-bad' : ''}`}>{label}</span>
          {/* Announced rather than only coloured: a red dot is not a signal to a reader who
              cannot see it, and "failed" is the single most important word on the line. */}
          {failed ? <span className="sr-only">failed</span> : null}
        </p>
        {/* Verbatim, for Verbatim's reasons: a moment's detail routinely quotes a ticket. */}
        {detail ? (
          <p className="whitespace-pre-wrap break-words text-xs text-muted">{detail}</p>
        ) : null}
      </div>
      {at ? <span className="shrink-0 text-[11px] tabular-nums text-muted">{at}</span> : null}
    </li>
  )
}
