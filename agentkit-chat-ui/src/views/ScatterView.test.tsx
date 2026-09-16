import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { View } from '../lib/types'
import { colouredSoFar, foldedColour, forgetSeriesColours, seriesColour } from './palette'
import { ScatterView } from './ScatterView'
import { asTable, scatterOf, SCATTER_CLOUDS, span } from './scatterData'

function cloud(name: string, points: [number, number, string?][]) {
  return { name, points: points.map(([x, y, label]) => ({ x, y, label: label ?? '' })) }
}

function view(data: Record<string, unknown>): View {
  return { kind: 'scatter', data }
}

const TICKETS = view({
  xTitle: 'age in days',
  yTitle: 'comments',
  clouds: [cloud('open', [[1, 2, 'OPS-1'], [5, 9, 'OPS-2'], [12, 3, 'OPS-3']])],
})

describe('a scatter is its own shape', () => {
  beforeEach(() => forgetSeriesColours())

  it('draws a point per member and names both axes', () => {
    render(<ScatterView view={TICKETS} />)

    expect(screen.getByTestId('scatter-view')).toBeInTheDocument()
    expect(screen.getByText('age in days')).toBeInTheDocument()
    expect(screen.getByText('comments')).toBeInTheDocument()
    // Three points, three circles. The count is the assertion: a renderer that dropped one
    // silently would still look like a scatter.
    expect(document.querySelectorAll('circle')).toHaveLength(3)
  })

  it('refuses to draw a single point rather than implying a correlation', () => {
    render(<ScatterView view={view({
      xTitle: 'age', yTitle: 'comments', clouds: [cloud('open', [[3, 4]])],
    })} />)

    // A lone dot between two axes reads as a measurement of something. The number survives.
    expect(screen.getByTestId('scatter-refused')).toBeInTheDocument()
    expect(screen.getByText(/not a correlation/)).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('refuses a scatter whose axes are unnamed', () => {
    render(<ScatterView view={view({
      clouds: [cloud('open', [[1, 2], [3, 4]])],
    })} />)

    expect(screen.getByTestId('scatter-refused')).toBeInTheDocument()
    expect(screen.getByText(/did not name both of its axes/)).toBeInTheDocument()
  })
})

describe('the colour cap is the measured one', () => {
  beforeEach(() => forgetSeriesColours())

  it('folds past three clouds into Other and says how many', () => {
    // Three is where both light and dark clear every all-pairs check in the palette
    // validator; four fails the normal-vision floor. See SCATTER_CLOUDS.
    const scatter = scatterOf(view({
      xTitle: 'x',
      yTitle: 'y',
      clouds: [
        cloud('a', [[1, 1], [2, 2]]),
        cloud('b', [[1, 2], [2, 3]]),
        cloud('c', [[1, 3], [2, 4]]),
        cloud('d', [[1, 4], [2, 5]]),
        cloud('e', [[1, 5], [2, 6]]),
      ],
    }))

    expect(scatter.clouds.map((one) => one.name)).toEqual(['a', 'b', 'c', 'Other'])
    expect(scatter.folded).toBe(2)
    // Nothing is dropped — the folded points are still drawn, just not separately coloured.
    expect(scatter.clouds[3]?.points).toHaveLength(4)
  })

  it('says on the figure what it folded, rather than leaving a grey nobody can name', () => {
    render(<ScatterView view={view({
      xTitle: 'x',
      yTitle: 'y',
      clouds: Array.from({ length: 6 }, (_, i) =>
        cloud(`s${i}`, [[i, i], [i + 1, i + 1]])),
    })} />)

    expect(screen.getByText(/3 further groups folded into/)).toBeInTheDocument()
    expect(screen.getByText('Other')).toBeInTheDocument()
  })

  it('gives Other the neutral rather than a fourth hue, and does not spend a slot on it', () => {
    // The first draft of this asked for `distinct.size === 4`, which passes whether Other
    // gets the neutral or a fourth palette hue — the two outcomes it exists to tell apart.
    // It has to name the colour.
    render(<ScatterView view={view({
      xTitle: 'x',
      yTitle: 'y',
      clouds: Array.from({ length: 4 }, (_, i) =>
        cloud(`s${i}`, [[i, i], [i + 1, i + 1]])),
    })} />)

    const fills = [...document.querySelectorAll('circle')].map((one) =>
      one.getAttribute('fill'))
    const neutral = foldedColour(false)
    const hues = [seriesColour('s0', false), seriesColour('s1', false), seriesColour('s2', false)]

    expect(new Set(fills.slice(0, 6))).toEqual(new Set(hues))
    expect(new Set(fills.slice(6))).toEqual(new Set([neutral]))
    expect(hues).not.toContain(neutral)
    expect(SCATTER_CLOUDS).toBe(3)
  })

  it('does not let the fold consume a palette slot a real series would have had', () => {
    // Naming the fold through seriesColour would enter it in the slot map, so the next
    // genuine series would be pushed a slot along and every earlier chart of the same data
    // would disagree with the new one. Colour follows the entity, never its rank.
    render(<ScatterView view={view({
      xTitle: 'x',
      yTitle: 'y',
      clouds: Array.from({ length: 5 }, (_, i) =>
        cloud(`s${i}`, [[i, i], [i + 1, i + 1]])),
    })} />)

    // Three clouds were coloured. Two were folded and 'Other' is not one of them.
    expect(colouredSoFar()).toBe(3)
  })
})

describe('a point with half a coordinate has no position', () => {
  beforeEach(() => forgetSeriesColours())

  it('drops it rather than placing it at zero', () => {
    // Zero is somewhere real. A reader cannot tell a missing x from a measured 0, and the
    // mark would sit on the axis looking like a reading.
    const scatter = scatterOf(view({
      xTitle: 'x',
      yTitle: 'y',
      clouds: [{ name: 'a', points: [
        { x: 1, y: 2, label: 'kept' },
        { x: null, y: 5, label: 'no x' },
        { x: 3, y: undefined, label: 'no y' },
        { x: 'nonsense', y: 1, label: 'not a number' },
        { x: 4, y: 6, label: 'kept too' },
      ] }],
    }))

    expect(scatter.clouds[0]?.points.map((one) => one.label)).toEqual(['kept', 'kept too'])
  })
})

describe('the numbers are always reachable', () => {
  beforeEach(() => forgetSeriesColours())

  it('shows them on request, which is the relief the contrast warning obliges', () => {
    render(<ScatterView view={TICKETS} />)

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    return userEvent.click(screen.getByText('Show the numbers')).then(() => {
      const table = screen.getByRole('table')
      expect(table).toBeInTheDocument()
      expect(screen.getByText('OPS-2')).toBeInTheDocument()
    })
  })

  it('names the axes as the table columns, not x and y', () => {
    const table = asTable(scatterOf(TICKETS))
    const columns = (table.data.columns as { name: string }[]).map((one) => one.name)
    expect(columns).toContain('age in days')
    expect(columns).toContain('comments')
  })
})

describe('the scale', () => {
  it('gives a cloud that shares one coordinate a width rather than dividing by zero', () => {
    // Every point on one vertical line. A zero-width span would stack every mark on an edge
    // at best and produce NaN positions at worst.
    const flat = span([cloud('a', [[5, 1], [5, 2], [5, 3]])], 'x')

    expect(flat.min).toBeLessThan(5)
    expect(flat.max).toBeGreaterThan(5)
    expect(Number.isFinite(flat.min) && Number.isFinite(flat.max)).toBe(true)
  })

  it('pads so a mark at the extreme is inside the plot rather than half outside it', () => {
    const padded = span([cloud('a', [[0, 0], [10, 10]])], 'x')

    expect(padded.min).toBeLessThan(0)
    expect(padded.max).toBeGreaterThan(10)
  })
})
