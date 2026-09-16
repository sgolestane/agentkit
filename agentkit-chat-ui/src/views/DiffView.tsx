import { useMemo } from 'react'
import type { View } from '../lib/types'

/**
 * What it was, and what it would be.
 *
 * <h4>Line by line, because "before and after" is what a person is checking</h4>
 *
 * The motivating case is an approval: a person is about to allow a change and the only question
 * that matters is what exactly changes. Two paragraphs side by side make them find it
 * themselves; marked lines do not.
 *
 * <p>The comparison is deliberately naive — a positional line-by-line walk, not a diff
 * algorithm. It is honest about that: an insertion near the top marks everything after it as
 * changed, which over-reports and never under-reports. Under-reporting is the failure that
 * matters here, because it is the one where somebody approves a change they were not shown.
 */
export function DiffView({ view }: { view: View }) {
  const title = String(view.data.title ?? '')
  const before = String(view.data.before ?? '')
  const after = String(view.data.after ?? '')

  const rows = useMemo(() => {
    const was = before.split('\n')
    const is = after.split('\n')
    const lines = Math.max(was.length, is.length)
    const out: { was: string | null; is: string | null; changed: boolean }[] = []
    for (let i = 0; i < lines; i++) {
      const a = i < was.length ? (was[i] ?? '') : null
      const b = i < is.length ? (is[i] ?? '') : null
      out.push({ was: a, is: b, changed: a !== b })
    }
    return out
  }, [before, after])

  const changed = rows.filter((row) => row.changed).length

  return (
    <figure className="my-2 rounded-lg border border-line" data-testid="diff-view">
      <figcaption className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line px-3 py-2">
        <span className="text-sm font-semibold">{title || 'Change'}</span>
        <span className="text-xs text-muted">
          {changed === 0 ? 'nothing changes' : `${changed} line(s) differ`}
        </span>
      </figcaption>
      <div className="grid grid-cols-2 text-[12.5px]">
        <Side label="before" lines={rows.map((row) => ({ text: row.was, changed: row.changed }))} />
        <Side
          label="after"
          lines={rows.map((row) => ({ text: row.is, changed: row.changed }))}
          bordered
        />
      </div>
    </figure>
  )
}

function Side({
  label,
  lines,
  bordered,
}: {
  label: string
  lines: { text: string | null; changed: boolean }[]
  bordered?: boolean
}) {
  return (
    <div className={bordered ? 'border-l border-line' : ''}>
      <p className="border-b border-line px-3 py-1 text-[11px] uppercase tracking-wide text-muted">
        {label}
      </p>
      <pre className="overflow-x-auto px-3 py-2">
        {lines.map((line, index) => (
          <span
            key={index}
            className={`block whitespace-pre-wrap break-words ${
              line.changed ? 'bg-panel' : ''
            }`}
          >
            {line.text ?? ''}
          </span>
        ))}
      </pre>
    </div>
  )
}
