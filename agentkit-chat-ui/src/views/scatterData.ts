import type { View } from '../lib/types'

export interface Point {
  x: number
  y: number
  label: string
}

export interface Cloud {
  name: string
  points: Point[]
}

export interface Scatter {
  xTitle: string
  yTitle: string
  clouds: Cloud[]
  /** How many clouds were folded into the last one, if any. */
  folded: number
  /** Why this cannot be drawn, if it cannot. */
  refusal: string | null
}

/**
 * How many clouds get their own colour before the rest fold into one.
 *
 * <h4>Three, measured — not the eight the other charts get</h4>
 *
 * A bar chart's marks are adjacent and separated by a surface gap, so its palette only has to
 * clear the colour-vision floors for *neighbouring* slots. A scatter's marks are interleaved by
 * the data: any two clouds can land on top of each other, so every pair has to clear, and the
 * eight-slot palette does not survive that. Run against the same validator the palette was
 * chosen with, `--pairs all`:
 *
 * ```
 * light  8 slots  FAIL  CVD ΔE 3.2 (#008300↔#eb6834)  normal ΔE 7.1 (#e34948↔#eb6834)
 * light  4 slots  FAIL  normal ΔE 13.7 (#eda100↔#eb6834) — below the 15 floor
 * light  3 slots  PASS  CVD ΔE 9.2   normal ΔE 24.0
 * dark   4 slots  FAIL  CVD ΔE 4.8 · normal ΔE 10.6 (#c98500↔#d95926)
 * dark   3 slots  PASS  CVD ΔE 9.4   normal ΔE 20.9
 * ```
 *
 * Three is where both modes pass every check, so three is the cap. The fourth cloud onward
 * shares the neutral the palette already uses for a ninth series, and the count of what was
 * folded is stated on the figure rather than left for a reader to notice.
 */
export const SCATTER_CLOUDS = 3

/** The most points drawn before the picture is refused in favour of the numbers. */
const MAX_POINTS = 2000

/**
 * The value as a coordinate, or null if it is not one.
 *
 * <p>The empty cases are checked before `Number`, because `Number` gives several of them a
 * position. `Number(null)`, `Number('')` and `Number([])` are all **0** — a real place on the
 * axis — so a point with a missing x was being drawn on the y axis looking like a reading of
 * zero. Only `undefined` and a non-numeric string produce NaN, which is why this was half
 * right and therefore worse than obviously wrong.
 */
function finite(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null
  }
  // A number that travelled as a string is still a number — some producers quote them — but
  // nothing else is, and in particular nothing empty is.
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/**
 * What a scatter view is asking for, and whether it can be honoured.
 *
 * <h4>Refusing is a rendering, not an error</h4>
 *
 * The same rule the categorical charts follow. A scatter of one point is not a correlation, and
 * drawing it as though it were is worse than showing the pair of numbers — a single dot in the
 * middle of two axes reads as a measurement of something.
 */
export function scatterOf(view: View): Scatter {
  const xTitle = String(view.data.xTitle ?? '')
  const yTitle = String(view.data.yTitle ?? '')
  const raw = Array.isArray(view.data.clouds) ? view.data.clouds : []

  const all: Cloud[] = raw.map((entry, index) => {
    const record = (entry ?? {}) as Record<string, unknown>
    const rawPoints = Array.isArray(record.points) ? record.points : []
    const points: Point[] = []
    for (const one of rawPoints) {
      const point = (one ?? {}) as Record<string, unknown>
      const x = finite(point.x)
      const y = finite(point.y)
      // A point with half a coordinate has no position. Dropped rather than placed at zero,
      // which would put it somewhere real and let a reader take it for a measurement.
      if (x !== null && y !== null) {
        points.push({ x, y, label: String(point.label ?? '') })
      }
    }
    return { name: String(record.name ?? `series ${index + 1}`), points }
  })

  const drawn = all.slice(0, SCATTER_CLOUDS)
  const rest = all.slice(SCATTER_CLOUDS)
  if (rest.length > 0) {
    drawn.push({
      name: 'Other',
      points: rest.flatMap((one) => one.points),
    })
  }

  const total = drawn.reduce((sum, one) => sum + one.points.length, 0)
  return {
    xTitle,
    yTitle,
    clouds: drawn,
    folded: rest.length,
    refusal: refusalFor(total, xTitle, yTitle),
  }
}

function refusalFor(total: number, xTitle: string, yTitle: string): string | null {
  if (!xTitle.trim() || !yTitle.trim()) {
    return 'This scatter did not name both of its axes, so its points cannot be read.'
  }
  if (total === 0) {
    return 'There are no points to plot.'
  }
  if (total === 1) {
    return 'One point is not a correlation, so the number is shown instead of a picture.'
  }
  if (total > MAX_POINTS) {
    return `That is ${total.toLocaleString()} points, past the ${MAX_POINTS.toLocaleString()}`
      + ' this draws; at that density the marks overlap into a shape rather than a reading.'
  }
  return null
}

/** The smallest and largest of one coordinate across every cloud, padded so marks are inside. */
export function span(clouds: Cloud[], axis: 'x' | 'y'): { min: number; max: number } {
  const values = clouds.flatMap((one) => one.points.map((point) => point[axis]))
  if (values.length === 0) {
    return { min: 0, max: 1 }
  }
  const low = Math.min(...values)
  const high = Math.max(...values)
  if (low === high) {
    // Every point shares this coordinate. A zero-width axis would divide by zero and stack
    // every mark on one edge; a unit either side puts them down the middle, which is what
    // the data actually says.
    return { min: low - 1, max: high + 1 }
  }
  const pad = (high - low) * 0.05
  return { min: low - pad, max: high + pad }
}

/** The scatter's numbers, as a table view can take them. */
export function asTable(scatter: Scatter): View {
  const named = scatter.clouds.length > 1
  return {
    kind: 'table',
    data: {
      columns: [
        ...(named ? [{ name: '', type: 'text' }] : []),
        { name: scatter.xTitle, type: 'number' },
        { name: scatter.yTitle, type: 'number' },
        { name: '', type: 'text' },
      ],
      rows: scatter.clouds.flatMap((cloud) =>
        cloud.points.map((point) => [
          ...(named ? [cloud.name] : []),
          point.x,
          point.y,
          point.label,
        ]),
      ),
    },
  }
}
