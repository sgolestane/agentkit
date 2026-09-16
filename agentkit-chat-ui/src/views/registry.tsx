import type { ComponentType } from 'react'
import type { View } from '../lib/types'
import { CardsView } from './CardsView'
import { ChartView } from './ChartView'
import { DiffView } from './DiffView'
import { FileView } from './FileView'
import { MarkdownView } from './MarkdownView'
import { McpAppView } from './McpAppView'
import { ScatterView } from './ScatterView'
import { StatView } from './StatView'
import { TableView } from './TableView'
import { TimelineView } from './TimelineView'

/**
 * Which component draws which kind of view.
 *
 * <h4>A registry, because `kind` is open on purpose</h4>
 *
 * `View.of` on the Java side lets a deployment agree a kind between a tool and a renderer
 * without changing the framework, and this is the other half of that door. Adding a widget is
 * adding an entry here; nothing else in the console has to know.
 *
 * <p>An unrecognised kind is <strong>shown, not swallowed</strong>. A tool that produced
 * something this build has no drawing for has still produced something, and rendering nothing
 * would make a missing renderer look like a missing result — which is the same failure as an
 * answer that silently does not appear.
 */
export const RENDERERS: Record<string, ComponentType<{ view: View }>> = {
  markdown: MarkdownView,
  table: TableView,
  chart: ChartView,
  scatter: ScatterView,
  stat: StatView,
  cards: CardsView,
  diff: DiffView,
  file: FileView,
  timeline: TimelineView,
  // Not a built-in View kind and deliberately not in VIEW_KINDS: `mcp-app` is produced by
  // agentkit-mcp through `View.of`, which is the open door the registry's own note describes.
  // A deployment without the MCP module never sees one, and the wire-types test — which pairs
  // VIEW_KINDS with View's own factories — would be wrong to demand a factory for it.
  'mcp-app': McpAppView,
}

export function renderable(kind: string): boolean {
  return kind in RENDERERS
}

export function ViewOf({ view }: { view: View }) {
  const Renderer = RENDERERS[view.kind]
  if (Renderer) {
    return <Renderer view={view} />
  }
  return <UnknownView view={view} />
}

/**
 * A kind nothing here draws.
 *
 * Collapsed, because it is raw data and would otherwise dominate the answer it belongs to, but
 * present — with its kind named, which is what tells somebody whether the tool is wrong or the
 * console is out of date.
 */
function UnknownView({ view }: { view: View }) {
  return (
    <details className="my-2 rounded-lg border border-line px-2 py-1.5 text-xs">
      <summary className="cursor-pointer text-muted">
        A “{view.kind}” this console has no drawing for
      </summary>
      <pre className="mt-1 overflow-x-auto text-[11px]">
        {JSON.stringify(view.data, null, 2)}
      </pre>
    </details>
  )
}

/** Everything a turn produced for a person to look at, in the order the tools produced it. */
export function Views({ views }: { views: View[] }) {
  if (views.length === 0) {
    return null
  }
  return (
    <div data-testid="views">
      {views.map((view, index) => (
        <ViewOf key={index} view={view} />
      ))}
    </div>
  )
}
