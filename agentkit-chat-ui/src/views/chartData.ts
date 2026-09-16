import type { View } from '../lib/types'

export interface Series {
  name: string
  values: (number | null)[]
}

export interface Chart {
  type: 'bar' | 'line' | 'area' | 'stacked'
  categories: string[]
  series: Series[]
  /** Why this cannot be drawn, if it cannot. */
  refusal: string | null
}

const DRAWN = ['bar', 'line', 'area', 'stacked'] as const

/**
 * What a chart view is asking for, and whether it can be honoured.
 *
 * <h4>Refusing is a rendering, not an error</h4>
 *
 * A chart of one point, of no data, or of a series that does not line up with its categories is
 * something a tool will produce — a group-by over an empty filter, a period with one bucket, a
 * hand-built payload. Drawing it anyway is the failure worth avoiding: a single bar occupying a
 * whole axis reads as a trend, and a series shifted by one category is a chart that is simply
 * wrong while looking fine.
 *
 * <p>So this returns a sentence instead, and the view shows the numbers as a table. The reader
 * loses the picture and keeps the data, which is the right way round.
 */
export function chartOf(view: View): Chart {
  const type = String(view.data.type ?? 'bar')
  const categories = Array.isArray(view.data.categories)
    ? view.data.categories.map((entry) => String(entry))
    : []
  const rawSeries = Array.isArray(view.data.series) ? view.data.series : []
  const series: Series[] = rawSeries.map((entry) => {
    const record = (entry ?? {}) as Record<string, unknown>
    const values = Array.isArray(record.values) ? record.values : []
    return {
      name: String(record.name ?? ''),
      // A gap is carried as null rather than coerced to zero. A category with no reading is not
      // a category reading zero, and a line drawn through a hole is a claim nobody made.
      values: values.map((value) =>
        value === null || value === undefined || value === '' ? null : Number(value),
      ),
    }
  })

  const drawn = (DRAWN as readonly string[]).includes(type) ? (type as Chart['type']) : 'bar'
  return { type: drawn, categories, series, refusal: refusalFor(categories, series) }
}

function refusalFor(categories: string[], series: Series[]): string | null {
  if (categories.length === 0 || series.length === 0) {
    return 'There is nothing to draw.'
  }
  const misaligned = series.find((one) => one.values.length !== categories.length)
  if (misaligned) {
    return `“${misaligned.name}” has ${misaligned.values.length} values for ${categories.length} categories, so it cannot be lined up.`
  }
  if (series.every((one) => one.values.every((value) => value === null))) {
    return 'Every value is missing.'
  }
  if (categories.length === 1) {
    // One bar across a whole axis reads as a trend. The number is the answer here.
    return 'A single point is a number, not a chart.'
  }
  return null
}

/** The highest and lowest a chart has to fit, including a stack's totals. */
export function extent(chart: Chart): { min: number; max: number } {
  const totals: number[] = []
  if (chart.type === 'stacked') {
    for (let i = 0; i < chart.categories.length; i++) {
      totals.push(
        chart.series.reduce((sum, one) => sum + (one.values[i] ?? 0), 0),
      )
    }
  } else {
    for (const one of chart.series) {
      for (const value of one.values) {
        if (value !== null) {
          totals.push(value)
        }
      }
    }
  }
  const max = totals.length ? Math.max(...totals) : 0
  const min = totals.length ? Math.min(...totals) : 0
  // Bars are read against zero. A bar chart whose axis starts at 900 makes a 1% difference look
  // like a doubling, which is the most-cited chart lie there is.
  return { min: Math.min(0, min), max: max === 0 ? 1 : max }
}

/** Round numbers to put on the value axis, and the count is deliberate. */
export function ticks(min: number, max: number, count = 4): number[] {
  const step = (max - min) / count
  return Array.from({ length: count + 1 }, (_, i) => min + step * i)
}

/** The chart's numbers, as a table view can take them. */
export function asTable(chart: Chart): View {
  return {
    kind: 'table',
    data: {
      columns: [
        { name: '', type: 'text' },
        ...chart.series.map((one) => ({ name: one.name, type: 'number' })),
      ],
      rows: chart.categories.map((category, index) => [
        category,
        ...chart.series.map((one) => one.values[index] ?? null),
      ]),
    },
  }
}
