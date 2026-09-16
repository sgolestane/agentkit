import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TableView } from './TableView'
import { ViewOf, Views } from './registry'
import type { View } from '../lib/types'

function table(rows: unknown[][], columns = [
  { name: 'category', type: 'text' },
  { name: 'open', type: 'number' },
]): View {
  return { kind: 'table', data: { columns, rows } }
}

function bodyRows() {
  const grid = screen.getByRole('table')
  return within(grid).getAllByRole('row').slice(1)
}

describe('TableView', () => {
  it('draws the rows under their columns', () => {
    render(<TableView view={table([['access', 12], ['laptop', 4]])} />)

    expect(screen.getByRole('columnheader', { name: /category/ })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'access' })).toBeInTheDocument()
    expect(screen.getByTestId('table-count')).toHaveTextContent('2 rows')
  })

  it('sorts ascending, then descending, then not at all', async () => {
    render(<TableView view={table([['b', 2], ['a', 3], ['c', 1]])} />)
    const header = screen.getByRole('button', { name: /category/ })

    await userEvent.click(header)
    expect(bodyRows()[0]).toHaveTextContent('a')
    expect(screen.getByRole('columnheader', { name: /category/ })).toHaveAttribute('aria-sort', 'ascending')

    await userEvent.click(header)
    expect(bodyRows()[0]).toHaveTextContent('c')
    expect(screen.getByRole('columnheader', { name: /category/ })).toHaveAttribute('aria-sort', 'descending')

    // A third click returns the table to the order the tool produced, which is information —
    // a group-by is usually already sorted by the thing that mattered.
    await userEvent.click(header)
    expect(bodyRows()[0]).toHaveTextContent('b')
    expect(screen.getByRole('columnheader', { name: /category/ })).toHaveAttribute('aria-sort', 'none')
  })

  it('sorts a number column as numbers', async () => {
    render(<TableView view={table([['a', 9], ['b', 10], ['c', 100]])} />)

    await userEvent.click(screen.getByRole('button', { name: /open/ }))

    expect(bodyRows().map((row) => row.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('9')]),
    )
    expect(bodyRows()[0]).toHaveTextContent('9')
    expect(bodyRows()[2]).toHaveTextContent('100')
  })

  it('filters across every column, because the question is usually which one', async () => {
    render(<TableView view={table([['access', 12], ['laptop', 4]])} />)

    await userEvent.type(screen.getByLabelText('Filter rows'), 'lap')

    expect(bodyRows()).toHaveLength(1)
    expect(screen.getByTestId('table-count')).toHaveTextContent('1 of 2 rows')
  })

  it('filters on a column that is not the first one', async () => {
    // The case that proves "every column". Filtering only column 0 passes the test above,
    // because the term happened to be in it — measured, by planting exactly that.
    render(<TableView view={table([['access', 12], ['laptop', 4]])} />)

    await userEvent.type(screen.getByLabelText('Filter rows'), '12')

    expect(bodyRows()).toHaveLength(1)
    expect(bodyRows()[0]).toHaveTextContent('access')
  })

  it('says so when nothing matches, rather than showing an empty grid', async () => {
    render(<TableView view={table([['access', 12]])} />)

    await userEvent.type(screen.getByLabelText('Filter rows'), 'zzz')

    expect(screen.getByText('Nothing matches that.')).toBeInTheDocument()
  })

  it('filtering does not permanently reorder the rows underneath', async () => {
    // `sort` is in place. Sorting the filtered array without copying it would reorder the
    // unfiltered rows too, so clearing the filter would hand back a different table.
    render(<TableView view={table([['b', 2], ['a', 3], ['c', 1]])} />)
    await userEvent.click(screen.getByRole('button', { name: /category/ }))
    await userEvent.click(screen.getByRole('button', { name: /category/ }))
    await userEvent.click(screen.getByRole('button', { name: /category/ }))

    expect(bodyRows()[0]).toHaveTextContent('b')
    expect(bodyRows()[2]).toHaveTextContent('c')
  })

  it('shows a page at a time and offers the rest', async () => {
    const many = Array.from({ length: 250 }, (_, i) => [`row-${i}`, i])
    render(<TableView view={table(many)} />)
    expect(bodyRows()).toHaveLength(100)

    await userEvent.click(screen.getByRole('button', { name: /Show 100 more of 250/ }))

    expect(bodyRows()).toHaveLength(200)
  })

  it('keeps its header in view while the rows scroll', () => {
    render(<TableView view={table([['access', 12]])} />)

    expect(screen.getByRole('columnheader', { name: /category/ })).toHaveClass('sticky')
  })

  it('scrolls inside itself rather than widening the conversation', () => {
    render(<TableView view={table([['access', 12]])} />)

    expect(screen.getByRole('table').parentElement).toHaveClass('overflow-auto')
  })

  it('right-aligns numbers and leaves text alone', () => {
    render(<TableView view={table([['access', 12]])} />)

    expect(screen.getByRole('cell', { name: '12' })).toHaveClass('text-right')
    expect(screen.getByRole('cell', { name: 'access' })).toHaveClass('text-left')
  })

  it('downloads what is on screen, filtered and sorted', async () => {
    // Blob is captured at construction rather than read back: jsdom's Blob has no text().
    const written: string[] = []
    class CapturingBlob {
      constructor(parts: string[]) {
        written.push(parts.join(''))
      }
    }
    vi.stubGlobal('Blob', CapturingBlob)
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:x', revokeObjectURL: () => {} })
    render(<TableView view={table([['access', 12], ['laptop', 4]])} />)
    await userEvent.type(screen.getByLabelText('Filter rows'), 'lap')

    await userEvent.click(screen.getByRole('button', { name: 'CSV' }))

    expect(written).toHaveLength(1)
    expect(written[0]).toContain('laptop')
    // The person is looking at one row and meant that one.
    expect(written[0]).not.toContain('access')
    vi.unstubAllGlobals()
  })

  it('says so when the table has no columns at all', () => {
    render(<TableView view={{ kind: 'table', data: { columns: [], rows: [] } }} />)

    expect(screen.getByText('A table with no columns.')).toBeInTheDocument()
  })
})

describe('the view registry', () => {
  it('draws a kind it knows', () => {
    render(<ViewOf view={table([['access', 12]])} />)

    expect(screen.getByTestId('table-view')).toBeInTheDocument()
  })

  it('draws every kind the Java side can produce', () => {
    // One assertion per registered kind, because removing an entry is silent: the view falls
    // through to the unknown renderer and shows its raw JSON, which looks like a tool problem
    // rather than a console one. Measured — deleting `chart` from the registry left every other
    // test in the suite green.
    const { rerender } = render(
      <ViewOf
        view={{
          kind: 'chart',
          data: {
            type: 'bar',
            categories: ['mon', 'tue'],
            series: [{ name: 'opened', values: [1, 2] }],
          },
        }}
      />,
    )
    expect(screen.getByTestId('chart-view')).toBeInTheDocument()

    rerender(<ViewOf view={{ kind: 'stat', data: { label: 'open', value: 12 } }} />)
    expect(screen.getByTestId('stat-view')).toBeInTheDocument()

    rerender(<ViewOf view={table([['access', 12]])} />)
    expect(screen.getByTestId('table-view')).toBeInTheDocument()
  })

  it('shows a kind it does not know rather than swallowing it', async () => {
    // A tool that produced something this build has no drawing for has still produced
    // something, and rendering nothing makes a missing renderer look like a missing result.
    render(<ViewOf view={{ kind: 'sankey', data: { nodes: 3 } }} />)

    expect(screen.getByText(/no drawing for/)).toBeInTheDocument()
    await userEvent.click(screen.getByText(/no drawing for/))
    expect(screen.getByText(/"nodes": 3/)).toBeInTheDocument()
  })

  it('draws nothing at all when a turn produced nothing to look at', () => {
    const { container } = render(<Views views={[]} />)

    expect(container.querySelector('[data-testid="views"]')).toBeNull()
  })

  it('draws several views in the order the tools produced them', () => {
    render(<Views views={[table([['a', 1]]), { kind: 'sankey', data: {} }]} />)

    expect(screen.getByTestId('table-view')).toBeInTheDocument()
    expect(screen.getByText(/no drawing for/)).toBeInTheDocument()
  })
})
