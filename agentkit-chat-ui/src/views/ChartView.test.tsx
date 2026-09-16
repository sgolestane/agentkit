import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ChartView } from './ChartView'
import { StatView } from './StatView'
import { chartOf, extent, asTable } from './chartData'
import { forgetSeriesColours, seriesColour } from './palette'
import type { View } from '../lib/types'

function chart(over: Record<string, unknown> = {}): View {
  return {
    kind: 'chart',
    data: {
      type: 'bar',
      categories: ['mon', 'tue', 'wed'],
      series: [{ name: 'opened', values: [3, 5, 2] }],
      ...over,
    },
  }
}

beforeEach(() => {
  forgetSeriesColours()
})

describe('ChartView', () => {
  it('draws a bar per category', () => {
    render(<ChartView view={chart()} />)

    expect(screen.getByTestId('chart-view')).toBeInTheDocument()
    expect(screen.getByTestId('bar-0-0')).toBeInTheDocument()
    expect(screen.getByTestId('bar-2-0')).toBeInTheDocument()
  })

  it('draws a line with a marker per point', () => {
    render(<ChartView view={chart({ type: 'line' })} />)

    expect(screen.getByTestId('point-0-0')).toBeInTheDocument()
    expect(screen.getByTestId('point-2-0')).toBeInTheDocument()
  })

  it('names itself for a reader who cannot see it', () => {
    render(<ChartView view={chart({ type: 'line' })} />)

    expect(screen.getByRole('img')).toHaveAccessibleName(
      'line chart of opened across 3 categories',
    )
  })

  it('carries a legend once there is more than one series', () => {
    const { rerender } = render(<ChartView view={chart()} />)
    expect(screen.queryByText('opened')).not.toBeInTheDocument()

    rerender(
      <ChartView
        view={chart({
          series: [
            { name: 'opened', values: [3, 5, 2] },
            { name: 'closed', values: [1, 2, 4] },
          ],
        })}
      />,
    )

    // Text labels, not colour swatches a reader has to name for themselves. Three of the eight
    // light slots sit below 3:1 contrast, and the palette validator calls that a warning that
    // obligates relief rather than one to dismiss.
    expect(screen.getByText('opened')).toBeInTheDocument()
    expect(screen.getByText('closed')).toBeInTheDocument()
  })

  it('always offers its own numbers', async () => {
    // The other half of the relief rule: a reader who cannot separate two hues can read figures.
    render(<ChartView view={chart()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Show the numbers' }))

    expect(screen.getByTestId('table-view')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'mon' })).toBeInTheDocument()
  })

  it('says what is under the pointer', async () => {
    render(<ChartView view={chart()} />)

    await userEvent.hover(screen.getByTestId('bar-1-0'))

    const tooltip = screen.getByTestId('chart-tooltip')
    expect(tooltip).toHaveTextContent('tue')
    expect(tooltip).toHaveTextContent('opened')
    expect(tooltip).toHaveTextContent('5')
  })

  // --- refusing to draw something misleading -------------------------------------

  it('refuses a single point, because that is a number', () => {
    render(<ChartView view={chart({ categories: ['mon'], series: [{ name: 'opened', values: [3] }] })} />)

    expect(screen.getByTestId('chart-refused')).toBeInTheDocument()
    expect(screen.getByText('A single point is a number, not a chart.')).toBeInTheDocument()
    // The picture is refused; the data is not.
    expect(screen.getByTestId('table-view')).toBeInTheDocument()
  })

  it('refuses a series that does not line up with its categories', () => {
    // A chart that is wrong while looking fine is the worst outcome here: nothing about a
    // shifted series looks broken.
    render(<ChartView view={chart({ series: [{ name: 'opened', values: [3, 5] }] })} />)

    expect(screen.getByTestId('chart-refused')).toBeInTheDocument()
    expect(screen.getByText(/cannot be lined up/)).toBeInTheDocument()
  })

  it('refuses an empty chart', () => {
    render(<ChartView view={chart({ categories: [], series: [] })} />)

    expect(screen.getByText('There is nothing to draw.')).toBeInTheDocument()
  })

  it('refuses a chart whose every value is missing', () => {
    render(<ChartView view={chart({ series: [{ name: 'opened', values: [null, null, null] }] })} />)

    expect(screen.getByText('Every value is missing.')).toBeInTheDocument()
  })

  it('leaves a gap where a value is missing rather than drawing it as zero', () => {
    // A category with no reading is not a category reading zero, and a line drawn through the
    // hole is a claim nobody made.
    render(<ChartView view={chart({ type: 'line', series: [{ name: 'opened', values: [3, null, 2] }] })} />)

    expect(screen.getByTestId('point-0-0')).toBeInTheDocument()
    expect(screen.queryByTestId('point-1-0')).not.toBeInTheDocument()
    // And the marker after the gap is still the category it belongs to. Numbering the survivors
    // instead made the tooltip name `tue` while pointing at `wed`.
    expect(screen.getByTestId('point-2-0')).toBeInTheDocument()
  })

  it('names the right category when a gap comes before the pointer', async () => {
    render(<ChartView view={chart({ type: 'line', series: [{ name: 'opened', values: [3, null, 2] }] })} />)

    await userEvent.hover(screen.getByTestId('point-2-0'))

    expect(screen.getByTestId('chart-tooltip')).toHaveTextContent('wed')
  })

  it('scales with the bubble rather than forcing a width', () => {
    render(<ChartView view={chart()} />)

    expect(screen.getByRole('img')).toHaveClass('w-full')
  })
})

describe('the value axis', () => {
  it('includes zero, so a bar is read against it', () => {
    // A bar chart whose axis starts at 900 makes a 1% difference look like a doubling, which is
    // the most-cited chart lie there is.
    const { min } = extent(chartOf(chart({ series: [{ name: 'x', values: [900, 910, 905] }] })))

    expect(min).toBe(0)
  })

  it('fits a stack`s totals, not its tallest segment', () => {
    const { max } = extent(
      chartOf(
        chart({
          type: 'stacked',
          series: [
            { name: 'a', values: [3, 5, 2] },
            { name: 'b', values: [4, 1, 6] },
          ],
        }),
      ),
    )

    expect(max).toBe(8)
  })
})

describe('series colour', () => {
  it('follows the entity, not its position', () => {
    // The same series must be the same colour in every chart of a conversation, or a reader
    // compares two charts by matching legends instead of by looking.
    const opened = seriesColour('opened', false)
    const closed = seriesColour('closed', false)
    const parked = seriesColour('parked', false)

    expect(opened).not.toBe(closed)
    expect(seriesColour('opened', false)).toBe(opened)

    // And a chart drawn WITHOUT the first series does not repaint the survivors. If colour came
    // from position, dropping 'opened' would move 'closed' and 'parked' up a slot, and every
    // earlier chart of the same data would disagree with the new one.
    expect(seriesColour('closed', false)).toBe(closed)
    expect(seriesColour('parked', false)).toBe(parked)
  })

  it('has a different set for a dark surface', () => {
    // The dark column is the same hues re-stepped for a dark surface, validated as a set —
    // not an automatic flip of the light one.
    expect(seriesColour('opened', true)).not.toBe(seriesColour('opened', false))
  })

  it('stops colouring past the eighth series rather than inventing a hue', () => {
    // A generated ninth hue is indistinguishable from an existing one under colour-vision
    // deficiency and breaks every check the palette passed.
    const eight = Array.from({ length: 8 }, (_, i) => seriesColour(`s${i}`, false))
    const ninth = seriesColour('s8', false)
    const tenth = seriesColour('s9', false)

    expect(new Set(eight).size).toBe(8)
    expect(eight).not.toContain(ninth)
    expect(ninth).toBe(tenth)
  })
})

describe('StatView', () => {
  it('shows one number, which a one-bar chart would have spent an axis on', () => {
    render(<StatView view={{ kind: 'stat', data: { label: 'open', value: 1234, note: 'up 3' } }} />)

    expect(screen.getByText('open')).toBeInTheDocument()
    expect(screen.getByText(new Intl.NumberFormat().format(1234))).toBeInTheDocument()
    expect(screen.getByText('up 3')).toBeInTheDocument()
  })

  it('shows an absent value as absent', () => {
    render(<StatView view={{ kind: 'stat', data: { label: 'open', value: null } }} />)

    expect(screen.getByText('—')).toBeInTheDocument()
  })
})

describe('asTable', () => {
  it('turns a chart into the numbers behind it', () => {
    const table = asTable(chartOf(chart()))

    expect(table.kind).toBe('table')
    expect(table.data.rows).toEqual([['mon', 3], ['tue', 5], ['wed', 2]])
  })
})
