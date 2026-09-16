import { useMemo, useState } from 'react'
import type { View } from '../lib/types'
import { foldedColour, seriesColour } from './palette'
import { asTable, scatterOf, span, SCATTER_CLOUDS, type Cloud, type Scatter } from './scatterData'
import { TableView } from './TableView'
import { useDarkMode } from './useDarkMode'

/** The drawing area, in the SVG's own coordinates. Wider bottom and left than the bar chart's:
 *  both axes carry a title here, and a title needs a line of its own under the tick labels. */
const W = 640
const H = 260
const PAD = { top: 12, right: 14, bottom: 52, left: 62 }
const PLOT = { w: W - PAD.left - PAD.right, h: H - PAD.top - PAD.bottom }

/** Bigger than the 8px floor the guidance sets, because these overlap and need to be separable. */
const MARK = 4.5

/**
 * Two measures of one population, drawn as points.
 *
 * <h4>Its own component because it is its own shape</h4>
 *
 * `ChartView` draws categories-and-series: an x axis of named buckets, marks placed by index.
 * Nothing in it survives x becoming a measure — not the band arithmetic, not the tick labels,
 * not the tooltip's "which category". Adding a fifth branch to it would have been a second
 * chart wearing the first one's geometry.
 *
 * <h4>Three clouds, and the fourth folds</h4>
 *
 * A bar chart's marks are adjacent and separated by a surface gap, so only *neighbouring*
 * palette slots have to clear the colour-vision floors. A scatter's marks are interleaved by
 * the data — any two clouds can land on top of one another — so every pair must clear, and the
 * eight-slot palette does not survive that. Measured, not assumed: see `SCATTER_CLOUDS`.
 *
 * <h4>The same relief the other charts carry</h4>
 *
 * A legend with visible text for two or more clouds, a table of the same numbers one click
 * away, and a per-point tooltip. Identity never rests on colour alone, which matters more here
 * than anywhere else in the console: overlapping marks of two hues are the exact case a reader
 * with a colour-vision deficiency cannot separate.
 */
export function ScatterView({ view }: { view: View }) {
  const scatter = useMemo(() => scatterOf(view), [view])
  const dark = useDarkMode()
  const [showNumbers, setShowNumbers] = useState(false)
  const [hovered, setHovered] = useState<{ cloud: number; point: number } | null>(null)

  const colours = scatter.clouds.map((one, index) =>
    // 'Other' is the fold, and it wears the neutral rather than a fourth hue. Through
    // foldedColour, not by handing a made-up name to seriesColour: that would enter the fake
    // name in the slot map, give it a real hue as the next free slot, and shift every genuine
    // series behind it. The fold is not a series and must not be counted as one.
    index >= SCATTER_CLOUDS ? foldedColour(dark) : seriesColour(one.name, dark),
  )

  if (scatter.refusal) {
    // The picture is refused; the data is not. One point is not a correlation, and a lone dot
    // between two axes reads as a measurement of something.
    return (
      <figure className="my-2 rounded-lg border border-line p-2" data-testid="scatter-refused">
        <figcaption className="mb-1 text-xs text-muted">{scatter.refusal}</figcaption>
        {scatter.clouds.some((one) => one.points.length > 0) ? (
          <TableView view={asTable(scatter)} />
        ) : null}
      </figure>
    )
  }

  const x = span(scatter.clouds, 'x')
  const y = span(scatter.clouds, 'y')

  return (
    <figure className="my-2 rounded-lg border border-line p-2" data-testid="scatter-view">
      <figcaption className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        {scatter.clouds.length > 1
          ? scatter.clouds.map((one, index) => (
              <span key={one.name} className="flex items-center gap-1 text-[11px] text-muted">
                <span
                  aria-hidden="true"
                  className="inline-block h-2 w-2 rounded-full"
                  style={{ background: colours[index] }}
                />
                {one.name}
              </span>
            ))
          : null}
        {scatter.folded > 0 ? (
          // Said, not silently dropped. A reader looking at three colours and a grey should be
          // able to find out what the grey is without counting the tool's output.
          <span className="text-[11px] text-muted">
            {scatter.folded} further group{scatter.folded === 1 ? '' : 's'} folded into “Other”
            — past {SCATTER_CLOUDS} the colours stop being separable.
          </span>
        ) : null}
        <button
          type="button"
          onClick={() => setShowNumbers((current) => !current)}
          className="ml-auto rounded border border-line px-2 py-0.5 text-[11px] text-muted hover:text-ink"
        >
          {showNumbers ? 'Hide the numbers' : 'Show the numbers'}
        </button>
      </figcaption>

      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="h-auto w-full"
        role="img"
        aria-label={ariaLabel(scatter)}
        onMouseLeave={() => setHovered(null)}
      >
        <Axes scatter={scatter} x={x} y={y} />
        {scatter.clouds.map((cloud, cloudIndex) => (
          <g key={cloud.name}>
            {cloud.points.map((point, pointIndex) => {
              const active =
                hovered?.cloud === cloudIndex && hovered.point === pointIndex
              return (
                <circle
                  key={pointIndex}
                  cx={at(point.x, x)}
                  cy={up(point.y, y)}
                  r={active ? MARK + 1.5 : MARK}
                  fill={colours[cloudIndex]}
                  // A 2px surface ring, so two overlapping marks read as two marks. The
                  // guidance calls for it on any form where fills can cover each other, and
                  // this is the form where they always do.
                  className="stroke-canvas"
                  strokeWidth={2}
                  fillOpacity={0.85}
                  onMouseEnter={() => setHovered({ cloud: cloudIndex, point: pointIndex })}
                />
              )
            })}
          </g>
        ))}
      </svg>

      {hovered ? <Tooltip scatter={scatter} at={hovered} colours={colours} /> : null}
      {showNumbers ? <TableView view={asTable(scatter)} /> : null}
    </figure>
  )
}

function ariaLabel(scatter: Scatter): string {
  const points = scatter.clouds.reduce((sum, one) => sum + one.points.length, 0)
  return `scatter plot of ${scatter.yTitle} against ${scatter.xTitle}, ${points} points`
    + (scatter.clouds.length > 1
      ? ` in ${scatter.clouds.map((one) => one.name).join(', ')}`
      : '')
}

function at(value: number, x: { min: number; max: number }): number {
  return PAD.left + ((value - x.min) / (x.max - x.min)) * PLOT.w
}

function up(value: number, y: { min: number; max: number }): number {
  return PAD.top + PLOT.h - ((value - y.min) / (y.max - y.min)) * PLOT.h
}

function tick(value: number): string {
  if (Math.abs(value) >= 1000) {
    return `${Math.round(value / 100) / 10}k`
  }
  return String(Math.round(value * 100) / 100)
}

/** Both grids, both scales, and both titles — recessive, and the titles are the point. */
function Axes({
  scatter,
  x,
  y,
}: {
  scatter: Scatter
  x: { min: number; max: number }
  y: { min: number; max: number }
}) {
  const steps = Array.from({ length: 5 }, (_, i) => i / 4)
  return (
    <g>
      {steps.map((step) => {
        const value = y.min + (y.max - y.min) * step
        return (
          <g key={`y${step}`}>
            <line
              x1={PAD.left}
              x2={W - PAD.right}
              y1={up(value, y)}
              y2={up(value, y)}
              className="stroke-line"
              strokeWidth={1}
            />
            <text
              x={PAD.left - 6}
              y={up(value, y) + 3}
              textAnchor="end"
              className="fill-muted text-[10px]"
            >
              {tick(value)}
            </text>
          </g>
        )
      })}
      {steps.map((step) => {
        const value = x.min + (x.max - x.min) * step
        return (
          <text
            key={`x${step}`}
            x={at(value, x)}
            y={H - 26}
            textAnchor="middle"
            className="fill-muted text-[10px]"
          >
            {tick(value)}
          </text>
        )
      })}
      {/* Both axes named. A scatter without these is two numbers nobody can read — unlike a
          bar chart, whose categories say what its x axis is. */}
      <text
        x={PAD.left + PLOT.w / 2}
        y={H - 8}
        textAnchor="middle"
        className="fill-muted text-[11px]"
      >
        {scatter.xTitle}
      </text>
      <text
        x={-(PAD.top + PLOT.h / 2)}
        y={13}
        transform="rotate(-90)"
        textAnchor="middle"
        className="fill-muted text-[11px]"
      >
        {scatter.yTitle}
      </text>
    </g>
  )
}

function Tooltip({
  scatter,
  at: where,
  colours,
}: {
  scatter: Scatter
  at: { cloud: number; point: number }
  colours: string[]
}) {
  const cloud = scatter.clouds[where.cloud] as Cloud
  const point = cloud.points[where.point]
  if (!point) {
    return null
  }
  return (
    <div className="mt-1 flex items-center gap-2 text-[11px] text-muted" role="status">
      <span
        aria-hidden="true"
        className="inline-block h-2 w-2 rounded-full"
        style={{ background: colours[where.cloud] }}
      />
      {point.label ? <span className="text-ink">{point.label}</span> : null}
      <span>
        {scatter.xTitle} {point.x}, {scatter.yTitle} {point.y}
      </span>
      {scatter.clouds.length > 1 ? <span>· {cloud.name}</span> : null}
    </div>
  )
}
