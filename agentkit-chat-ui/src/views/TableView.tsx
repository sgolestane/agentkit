import { useMemo, useState } from 'react'
import type { View } from '../lib/types'
import { columnsOf, isAbsent, order, rowsOf, shown, toCsv } from './cells'

/** How many rows are drawn before the reader has to ask for more. */
const PAGE = 100

/**
 * Rows under named columns.
 *
 * <h4>The widget that carries the most weight</h4>
 *
 * A group-by, a SQL result, a rule's coverage and the tool catalog are all tables, and until
 * `View` existed every one of them was flattened into markdown pipes — which cannot be sorted,
 * cannot be filtered, and stops being readable somewhere around thirty rows. This is what #336
 * built the second channel for.
 */
export function TableView({ view }: { view: View }) {
  const columns = useMemo(() => columnsOf(view.data.columns), [view.data.columns])
  const rows = useMemo(() => rowsOf(view.data.rows), [view.data.rows])
  const [sort, setSort] = useState<{ column: number; descending: boolean } | null>(null)
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState(1)

  const matching = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    if (!needle) {
      return rows
    }
    // Across every column, because a person filtering a table is looking for a value and does
    // not yet know which column it is in — that is usually the question.
    return rows.filter((row) =>
      row.some((cell) => !isAbsent(cell) && String(cell).toLowerCase().includes(needle)),
    )
  }, [rows, filter])

  const ordered = useMemo(() => {
    if (!sort) {
      return matching
    }
    const type = columns[sort.column]?.type ?? 'text'
    // Copied before sorting: `sort` is in place, and mutating the array a memo above produced
    // would reorder the unfiltered rows too — so clearing the filter would hand back a
    // different table than the one that went in.
    //
    // The direction is passed into the comparison rather than applied by reversing the result,
    // because reversing flips the absent-last rule along with everything else.
    return [...matching].sort((a, b) =>
      order(a[sort.column], b[sort.column], type, sort.descending),
    )
  }, [matching, sort, columns])

  const visible = ordered.slice(0, page * PAGE)

  if (columns.length === 0) {
    return <p className="my-2 text-xs text-muted">A table with no columns.</p>
  }

  return (
    <figure className="my-2 rounded-lg border border-line" data-testid="table-view">
      <figcaption className="flex flex-wrap items-center gap-2 border-b border-line px-2 py-1.5">
        <input
          type="search"
          aria-label="Filter rows"
          placeholder="Filter…"
          value={filter}
          onChange={(event) => {
            setFilter(event.target.value)
            setPage(1)
          }}
          className="min-w-0 flex-1 rounded border border-line bg-canvas px-2 py-0.5 text-xs outline-none focus:border-accent"
        />
        <span className="text-[11px] text-muted" data-testid="table-count">
          {matching.length === rows.length
            ? `${rows.length} ${rows.length === 1 ? 'row' : 'rows'}`
            : `${matching.length} of ${rows.length} rows`}
        </span>
        <DownloadCsv columns={columns} rows={ordered} />
      </figcaption>

      {/* The table scrolls inside this, not the page. A wide result that widened the
          transcript would make the whole conversation scroll sideways. */}
      <div className="max-h-[26rem] overflow-auto">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr>
              {columns.map((column, index) => {
                const active = sort?.column === index
                return (
                  <th
                    key={`${column.name}-${index}`}
                    scope="col"
                    aria-sort={active ? (sort.descending ? 'descending' : 'ascending') : 'none'}
                    className={`sticky top-0 z-10 border-b border-line bg-panel px-2 py-1 text-[11px] font-semibold uppercase tracking-wide text-muted ${
                      column.type === 'number' ? 'text-right' : 'text-left'
                    }`}
                  >
                    <button
                      type="button"
                      onClick={() =>
                        setSort(
                          active && !sort.descending
                            ? { column: index, descending: true }
                            : active && sort.descending
                              ? null
                              : { column: index, descending: false },
                        )
                      }
                      className="w-full text-inherit hover:text-ink"
                      style={{ textAlign: column.type === 'number' ? 'right' : 'left' }}
                    >
                      {column.name}
                      {active ? (sort.descending ? ' ↓' : ' ↑') : ''}
                    </button>
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {visible.map((row, rowIndex) => (
              <tr key={rowIndex} className="odd:bg-canvas/40">
                {columns.map((column, index) => {
                  const value = row[index]
                  return (
                    <td
                      key={index}
                      className={`border-b border-line px-2 py-1 align-top ${
                        column.type === 'number' ? 'text-right tabular-nums' : 'text-left'
                      } ${isAbsent(value) ? 'text-muted' : ''}`}
                    >
                      {shown(value, column.type)}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
        {visible.length === 0 ? (
          <p className="px-2 py-3 text-xs text-muted">Nothing matches that.</p>
        ) : null}
      </div>

      {ordered.length > visible.length ? (
        <button
          type="button"
          onClick={() => setPage((current) => current + 1)}
          className="w-full border-t border-line px-2 py-1.5 text-xs text-muted hover:text-ink"
        >
          Show {Math.min(PAGE, ordered.length - visible.length)} more of {ordered.length}
        </button>
      ) : null}
    </figure>
  )
}

/**
 * The rows, as a file.
 *
 * Built at click time rather than held: a table of a few thousand rows is a string of a few
 * hundred kilobytes, and keeping one per table in a long transcript is memory nobody asked for.
 * What is downloaded is what is on screen — filtered and sorted — because that is the table the
 * person is looking at and meant.
 */
function DownloadCsv({
  columns,
  rows,
}: {
  columns: ReturnType<typeof columnsOf>
  rows: unknown[][]
}) {
  return (
    <button
      type="button"
      onClick={() => {
        const blob = new Blob([toCsv(columns, rows)], { type: 'text/csv;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = 'table.csv'
        link.click()
        URL.revokeObjectURL(url)
      }}
      className="rounded border border-line px-2 py-0.5 text-[11px] text-muted hover:text-ink"
    >
      CSV
    </button>
  )
}
