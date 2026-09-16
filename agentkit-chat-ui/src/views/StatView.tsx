import type { View } from '../lib/types'

/**
 * One headline number.
 *
 * <p>The guidance's first question is whether the data is even a chart, and a single current
 * value is the clearest case where it is not: a one-bar bar chart spends a whole axis saying
 * what a number says. The note underneath is what makes the number mean anything — a count with
 * no denominator and no comparison is a fact nobody can act on.
 */
export function StatView({ view }: { view: View }) {
  const label = String(view.data.label ?? '')
  const value = view.data.value
  const note = String(view.data.note ?? '')

  return (
    <figure className="my-2 inline-block rounded-lg border border-line px-4 py-3" data-testid="stat-view">
      {label ? (
        <figcaption className="text-[11px] uppercase tracking-wide text-muted">{label}</figcaption>
      ) : null}
      <p className="text-3xl font-semibold tabular-nums">
        {value === null || value === undefined || value === ''
          ? '—'
          : typeof value === 'number'
            ? new Intl.NumberFormat().format(value)
            : String(value)}
      </p>
      {note ? <p className="text-xs text-muted">{note}</p> : null}
    </figure>
  )
}
