import { render, screen } from '@testing-library/react'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { Markdown } from '../components/Markdown'
import { Verbatim } from '../components/Verbatim'
import { ViewOf } from './registry'

/**
 * Text an outsider chose cannot become code on the page where a person approves privileged
 * actions.
 *
 * <h4>The ancestor, and why this one looks nothing like it</h4>
 *
 * `OperatorConsoleEscapingTest` in the itops example is the ancestor: #192, a ticket id that
 * became an `onmouseover` handler beside the intended one. It reads the page's *source*,
 * because that console builds markup in a template literal and assigns it to `innerHTML`, and
 * the sink is therefore lexical — the only defence is an escape table, and the test's job is to
 * pin that the table is applied and wide enough.
 *
 * This console has no such sink. React escapes text nodes by construction, so the equivalent
 * proof is *structural*: show that the sink does not exist, and that the two places markup can
 * still be produced deliberately are both closed.
 *
 * Three things, then:
 *
 * 1. **No source file reaches for raw HTML.** `dangerouslySetInnerHTML` and `innerHTML =` are
 *    the only ways past React's escaping, and neither appears outside a test asserting their
 *    absence. This is the check that would have caught #192 if #192 had been written here.
 * 2. **A hostile value in every renderer stays text.** Driven through the real components with
 *    the payload that broke the ancestor.
 * 3. **The two deliberate markup paths are closed.** `Markdown` sanitizes — that is #342's
 *    schema, tested there — and everything else goes through `Verbatim`, which is a decision
 *    somebody has to take rather than one they can drift out of.
 */

const PAYLOAD = 'INC1" onmouseover="alert(document.cookie)'
const SCRIPT = '<img src=x onerror="alert(1)"><script>alert(2)</script>'

function sources(dir: string): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) {
      out.push(...sources(path))
    } else if (/\.tsx?$/.test(name)) {
      out.push(path)
    }
  }
  return out
}

describe('nothing an outsider wrote becomes markup', () => {
  it('no component reaches past React to raw HTML', () => {
    // The structural half, and the one that keeps holding as the console grows: a future
    // widget that wants to "just render the HTML" fails here rather than in an incident.
    const offenders: string[] = []
    for (const path of sources(join(import.meta.dirname, '..'))) {
      if (/\.test\.tsx?$/.test(path)) {
        continue
      }
      const source = readFileSync(path, 'utf8')
      // Matched as a use rather than as a mention: `Verbatim.tsx`'s own doc comment says it
      // does not use `dangerouslySetInnerHTML`, and a substring search reported the file that
      // documents the rule as the file that breaks it. So the prop has to be assigned —
      // `dangerouslySetInnerHTML={` or `: ` — and `innerHTML` has to be written to.
      //
      // Lexical, so a sufficiently indirect spread would slip past it. That is the honest
      // limit of a source check and it is still worth having: the failure this guards against
      // is somebody reaching for the easy thing, not somebody hiding it.
      if (/dangerouslySetInnerHTML\s*[=:]/.test(source) || /\.innerHTML\s*=[^=]/.test(source)) {
        offenders.push(path)
      }
    }
    expect(offenders).toEqual([])
  })

  it('no frame is given this origin, whatever else it is given', () => {
    // `allow-scripts` with `allow-same-origin` is the single most common way an iframe
    // sandbox is silently undone: together they hand the frame this document's origin, and
    // scripts inside it can then reach this console's DOM, storage and cookies. MCP Apps
    // (SEP-1865) renders somebody else's HTML in exactly such a frame, so the pair matters
    // here in a way it would not in a page with no frames at all.
    for (const path of sources(join(import.meta.dirname, '..'))) {
      if (/\.test\.tsx?$/.test(path)) {
        continue
      }
      const source = readFileSync(path, 'utf8')
      for (const sandbox of source.matchAll(/sandbox="([^"]*)"/g)) {
        const granted = sandbox[1] ?? ''
        expect(
          granted.includes('allow-scripts') && granted.includes('allow-same-origin'),
          `${path} grants both allow-scripts and allow-same-origin, which is no sandbox`,
        ).toBe(false)
      }
    }
  })

  it('a frame with no sandbox attribute at all does not exist', () => {
    for (const path of sources(join(import.meta.dirname, '..'))) {
      if (/\.test\.tsx?$/.test(path)) {
        continue
      }
      const source = readFileSync(path, 'utf8')
      const frames = [...source.matchAll(/<iframe\b[\s\S]*?\/>/g)]
      for (const frame of frames) {
        expect(frame[0], `${path} has an unsandboxed iframe`).toContain('sandbox=')
      }
    }
  })

  it('the payload that broke the ancestor is text in every renderer that can hold it', () => {
    const cases: Record<string, unknown>[] = [
      { kind: 'cards', data: { cards: [{ title: PAYLOAD, subtitle: SCRIPT, fields: { a: SCRIPT }, url: PAYLOAD }] } },
      { kind: 'table', data: { columns: [{ name: PAYLOAD, type: 'text' }], rows: [[SCRIPT]] } },
      { kind: 'timeline', data: { title: PAYLOAD, moments: [{ kind: 'tool', label: SCRIPT, detail: PAYLOAD }] } },
      { kind: 'stat', data: { label: PAYLOAD, value: SCRIPT, note: PAYLOAD } },
      { kind: 'diff', data: { title: PAYLOAD, before: SCRIPT, after: PAYLOAD } },
      { kind: 'file', data: { id: PAYLOAD, name: SCRIPT, mediaType: PAYLOAD, bytes: 1 } },
      { kind: 'acme.unknown', data: { anything: SCRIPT } },
    ]

    for (const view of cases) {
      const { container, unmount } = render(<ViewOf view={view as never} />)
      expect(container.querySelector('script'), view.kind as string).toBeNull()
      expect(container.querySelector('img'), view.kind as string).toBeNull()
      expect(container.querySelector('[onmouseover]'), view.kind as string).toBeNull()
      // Nothing gained an event handler either — the specific shape of #192, where the
      // payload closed one attribute and opened another.
      for (const element of container.querySelectorAll('*')) {
        for (const attribute of element.attributes) {
          expect(attribute.name.startsWith('on'), `${view.kind}: ${attribute.name}`).toBe(false)
        }
      }
      unmount()
    }
  })

  it('a card url an outsider chose cannot be a handler or a script', () => {
    render(
      <ViewOf
        view={{
          kind: 'cards',
          data: { cards: [{ title: 'IT-1', subtitle: '', fields: {}, url: 'javascript:alert(1)' }] },
        }}
      />,
    )

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('the two deliberate markup paths are the sanitized one and the verbatim one', () => {
    const markdown = render(<Markdown text={SCRIPT} />)
    expect(markdown.container.querySelector('script')).toBeNull()
    expect(markdown.container.querySelector('img[onerror]')).toBeNull()
    markdown.unmount()

    const verbatim = render(<Verbatim text={SCRIPT} />)
    expect(verbatim.container.querySelector('script')).toBeNull()
    // And it shows what was actually written, which is the whole reason it exists: somebody
    // working out what a requester filed needs to see the characters they typed.
    expect(verbatim.container.textContent).toBe(SCRIPT)
  })
})
