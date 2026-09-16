import { useMemo, useState } from 'react'
import type { View } from '../lib/types'
import { asTable, chartOf, extent, ticks, type Chart } from './chartData'
import { seriesColour } from './palette'
import { TableView } from './TableView'
import { useDarkMode } from './useDarkMode'

/** The drawing area, in the SVG's own coordinates. It scales to whatever width it is given. */
const W = 640
const H = 220
const PAD = { top: 12, right: 12, bottom: 34, left: 48 }
const PLOT = { w: W - PAD.left - PAD.right, h: H - PAD.top - PAD.bottom }

/**
 * A chart, drawn rather than fenced.
 *
 * <h4>Hand-drawn SVG, and why that is not stubbornness</h4>
 *
 * The four forms a `View.chart` can express — bar, line, area, stacked — are a few dozen lines
 * of geometry each. A charting library is 90–150 kB gzipped for that, on a console whose whole
 * bundle is 135 kB, and it brings its own colour opinions that then have to be overridden slot
 * by slot to keep the validated palette. What it would buy is the forms this data shape cannot
 * express anyway.
 *
 * <h4>Every chart carries its own numbers</h4>
 *
 * Three of the eight light-mode slots sit below 3:1 against the light surface, which the palette
 * validator reports as a warning that <em>obligates relief</em> rather than one to dismiss. The
 * relief is here: a legend with visible text labels, and a table of the same numbers one click
 * away. Identity never rests on colour alone, and a reader who cannot separate two hues can read
 * the figures.
 */
export function ChartView({ view }: { view: View }) {
  const chart = useMemo(() => chartOf(view), [view])
  const dark = useDarkMode()
  const [showNumbers, setShowNumbers] = useState(false)
  const [hovered, setHovered] = useState<{ category: number; series: number } | null>(null)

  const colours = chart.series.map((one) => seriesColour(one.name, dark))

  if (chart.refusal) {
    // The picture is refused; the data is not. Losing the chart and keeping the numbers is the
    // right way round — a single bar across a whole axis reads as a trend, and a series shifted
    // by one category is a chart that is wrong while looking fine.
    return (
      <figure className="my-2 rounded-lg border border-line p-2" data-testid="chart-refused">
        <figcaption className="mb-1 text-xs text-muted">{chart.refusal}</figcaption>
        {chart.categories.length > 0 && chart.series.length > 0 ? (
          <TableView view={asTable(chart)} />
        ) : null}
      </figure>
    )
  }

  return (
    <figure className="my-2 rounded-lg border border-line p-2" data-testid="chart-view">
      <figcaption className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1">
        {/* A legend is always present for two or more series, and it is text — not a colour
            swatch a reader has to name for themselves. */}
        {chart.series.length > 1
          ? chart.series.map((one, index) => (
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
        // Scales with the bubble rather than forcing a width. A fixed-width chart is how a
        // conversation ends up scrolling sideways on a laptop.
        className="h-auto w-full"
        role="img"
        aria-label={ariaLabel(chart)}
        onMouseLeave={() => setHovered(null)}
      >
        <Axes chart={chart} />
        {chart.type === 'bar' || chart.type === 'stacked' ? (
          <Bars chart={chart} colours={colours} hovered={hovered} onHover={setHovered} />
        ) : (
          <Lines chart={chart} colours={colours} hovered={hovered} onHover={setHovered} />
        )}
      </svg>

      {hovered ? <Tooltip chart={chart} at={hovered} colours={colours} /> : null}
      {showNumbers ? <TableView view={asTable(chart)} /> : null}
    </figure>
  )
}

function ariaLabel(chart: Chart): string {
  return `${chart.type} chart of ${chart.series.map((one) => one.name).join(', ')} across ${chart.categories.length} categories`
}

function x(index: number, count: number): number {
  return PAD.left + (PLOT.w / count) * (index + 0.5)
}

function y(value: number, min: number, max: number): number {
  return PAD.top + PLOT.h - ((value - min) / (max - min)) * PLOT.h
}

/** The grid and the two axes, deliberately recessive. */
function Axes({ chart }: { chart: Chart }) {
  const { min, max } = extent(chart)
  const everyOther = Math.ceil(chart.categories.length / 8)
  return (
    <g>
      {ticks(min, max).map((tick) => (
        <g key={tick}>
          <line
            x1={PAD.left}
            x2={W - PAD.right}
            y1={y(tick, min, max)}
            y2={y(tick, min, max)}
            className="stroke-line"
            strokeWidth={1}
          />
          <text
            x={PAD.left - 6}
            y={y(tick, min, max) + 3}
            textAnchor="end"
            className="fill-muted text-[10px]"
          >
            {Math.abs(tick) >= 1000 ? `${Math.round(tick / 100) / 10}k` : Math.round(tick * 10) / 10}
          </text>
        </g>
      ))}
      {chart.categories.map((category, index) =>
        // Every label when they fit, every nth when they do not. Overlapping labels are worse
        // than absent ones: they read as a smudge and hide the ones that survived.
        index % everyOther === 0 ? (
          <text
            key={category + index}
            x={x(index, chart.categories.length)}
            y={H - 12}
            textAnchor="middle"
            className="fill-muted text-[10px]"
          >
            {category.length > 12 ? `${category.slice(0, 11)}…` : category}
          </text>
        ) : null,
      )}
    </g>
  )
}

function Bars({
  chart,
  colours,
  hovered,
  onHover,
}: {
  chart: Chart
  colours: string[]
  hovered: { category: number; series: number } | null
  onHover: (at: { category: number; series: number } | null) => void
}) {
  const { min, max } = extent(chart)
  const band = PLOT.w / chart.categories.length
  const stacked = chart.type === 'stacked'
  const width = stacked ? band * 0.6 : (band * 0.7) / chart.series.length

  return (
    <g>
      {chart.categories.map((category, categoryIndex) => {
        let stackTop = 0
        return chart.series.map((one, seriesIndex) => {
          const value = one.values[categoryIndex]
          if (value === null || value === undefined) {
            return null
          }
          const left = stacked
            ? PAD.left + band * categoryIndex + (band - width) / 2
            : PAD.left + band * categoryIndex + band * 0.15 + width * seriesIndex
          const top = stacked ? y(stackTop + value, min, max) : y(value, min, max)
          const bottom = stacked ? y(stackTop, min, max) : y(0, min, max)
          stackTop += value
          const active = hovered?.category === categoryIndex && hovered.series === seriesIndex
          return (
            <rect
              key={`${category}-${one.name}`}
              x={left}
              // A 2px gap between adjacent fills, so two touching segments never read as one.
              y={Math.min(top, bottom) + (stacked && seriesIndex > 0 ? 1 : 0)}
              width={Math.max(1, width - 2)}
              height={Math.max(0, Math.abs(bottom - top) - (stacked ? 2 : 0))}
              rx={3}
              fill={colours[seriesIndex]}
              opacity={hovered && !active ? 0.45 : 1}
              onMouseEnter={() => onHover({ category: categoryIndex, series: seriesIndex })}
              data-testid={`bar-${categoryIndex}-${seriesIndex}`}
            />
          )
        })
      })}
    </g>
  )
}

function Lines({
  chart,
  colours,
  hovered,
  onHover,
}: {
  chart: Chart
  colours: string[]
  hovered: { category: number; series: number } | null
  onHover: (at: { category: number; series: number } | null) => void
}) {
  const { min, max } = extent(chart)
  return (
    <g>
      {chart.series.map((one, seriesIndex) => {
        // The category index travels with the point. Filtering the nulls out first and then
        // numbering what survived made every marker after a gap report the wrong category —
        // the tooltip named `tue` while pointing at `wed`, which is a chart that lies quietly.
        const points = one.values
          .map((value, index) =>
            value === null
              ? null
              : { at: index, x: x(index, chart.categories.length), y: y(value, min, max) },
          )
          .filter((point): point is { at: number; x: number; y: number } => point !== null)
        if (points.length === 0) {
          return null
        }
        const path = points.map((point, i) => `${i === 0 ? 'M' : 'L'}${point.x},${point.y}`).join(' ')
        return (
          <g key={one.name}>
            {chart.type === 'area' ? (
              <path
                d={`${path} L${points[points.length - 1]!.x},${y(Math.max(0, min), min, max)} L${points[0]!.x},${y(Math.max(0, min), min, max)} Z`}
                fill={colours[seriesIndex]}
                opacity={0.18}
              />
            ) : null}
            <path d={path} fill="none" stroke={colours[seriesIndex]} strokeWidth={2} />
            {points.map((point) => (
              <circle
                key={point.at}
                cx={point.x}
                cy={point.y}
                r={hovered?.series === seriesIndex && hovered.category === point.at ? 6 : 4}
                fill={colours[seriesIndex]}
                // A 2px ring in the surface colour, so overlapping markers stay countable.
                className="stroke-panel"
                strokeWidth={2}
                onMouseEnter={() => onHover({ category: point.at, series: seriesIndex })}
                data-testid={`point-${point.at}-${seriesIndex}`}
              />
            ))}
          </g>
        )
      })}
    </g>
  )
}

/** What is under the pointer, in words. */
function Tooltip({
  chart,
  at,
  colours,
}: {
  chart: Chart
  at: { category: number; series: number }
  colours: string[]
}) {
  const series = chart.series[at.series]
  const value = series?.values[at.category]
  return (
    <p
      role="status"
      className="mt-1 flex items-center gap-1.5 text-xs text-muted"
      data-testid="chart-tooltip"
    >
      <span
        aria-hidden="true"
        className="inline-block h-2 w-2 rounded-full"
        style={{ background: colours[at.series] }}
      />
      <span className="text-ink">{chart.categories[at.category]}</span>
      <span>· {series?.name}</span>
      <span className="tabular-nums text-ink">
        {value === null || value === undefined ? '—' : new Intl.NumberFormat().format(value)}
      </span>
    </p>
  )
}
