import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { VIEW_KINDS } from '../lib/types'
import { RENDERERS, renderable, ViewOf } from './registry'

describe('the view registry', () => {
  it('draws every kind the Java side can produce', () => {
    // `TheWireTypesMatchTheJavaOnesTest` already keeps VIEW_KINDS and `View`'s factories in
    // step. What nothing checked is the step after: four of the seven declared kinds —
    // markdown, cards, diff and file — were listed here and had no renderer, so a tool that
    // produced one had its result drawn as a collapsed JSON blob. Matching the wire and
    // never drawing it is the same failure as not matching it, one layer later.
    for (const kind of VIEW_KINDS) {
      expect(renderable(kind), `${kind} has no renderer`).toBe(true)
    }
  })

  it('has no renderer for a built-in kind nothing declares', () => {
    // The reverse drift: a renderer left behind after its kind was removed is dead code that
    // reads as a feature.
    //
    // `mcp-app` is exempt and is the reason this list exists rather than the check being
    // symmetric. It is produced through `View.of` by agentkit-mcp — the open door the
    // registry's note describes — so it has no factory on View and demanding one would be
    // demanding that the framework know about an integration.
    const throughTheOpenDoor = ['mcp-app']
    for (const kind of Object.keys(RENDERERS)) {
      if (throughTheOpenDoor.includes(kind)) {
        continue
      }
      expect(VIEW_KINDS as readonly string[], `${kind} is drawn but not declared`).toContain(
        kind,
      )
    }
  })

  it('still shows a kind it has never heard of rather than swallowing it', () => {
    // The open door `View.of` exists for. A deployment's own kind against an older console is
    // a result that arrived, and rendering nothing would make a missing renderer look like a
    // missing answer.
    render(<ViewOf view={{ kind: 'acme.gauge', data: { value: 3 } }} />)

    expect(screen.getByText(/acme.gauge/)).toBeInTheDocument()
    expect(screen.getByText(/"value": 3/)).toBeInTheDocument()
  })
})
