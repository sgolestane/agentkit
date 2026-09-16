import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { McpAppView, sealed } from './McpAppView'
import { ViewOf } from './registry'

const PAGE = '<!doctype html><div id="root">a chart</div>'

const app = (over: Record<string, unknown> = {}) => ({
  kind: 'mcp-app',
  data: {
    uri: 'ui://tickets/chart',
    tool: 'tickets.chart',
    html: PAGE,
    arguments: { limit: 5 },
    result: '47 open across three families.',
    isError: false,
    ...over,
  },
})

describe('an MCP App', () => {
  it('is rendered in a frame that cannot reach this console', () => {
    render(<McpAppView view={app() as never} />)

    const frame = screen.getByTitle(/interface supplied by the tool/) as HTMLIFrameElement
    // The whole security story in one attribute. allow-scripts without allow-same-origin is
    // an opaque origin: the page inside cannot touch this document, its storage or its
    // cookies, whatever it tries. Adding allow-same-origin beside allow-scripts is the single
    // most common way an iframe sandbox is silently undone.
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts')
    expect(frame.getAttribute('sandbox')).not.toContain('allow-same-origin')
    expect(frame.getAttribute('srcdoc')).toContain('a chart')
  })

  it('puts this host’s policy in front of the app’s own page', () => {
    const out = sealed(PAGE)

    // default-src 'none', so a page that tried to phone home with the tool result cannot.
    // The spec expects a host to block external requests and says apps must be
    // self-contained for that reason.
    expect(out.indexOf('Content-Security-Policy')).toBeLessThan(out.indexOf('<!doctype'))
    expect(out).toContain("default-src 'none'")
    expect(out).not.toContain('connect-src')
    expect(out).toContain(PAGE)
  })

  it('hands the app the two notifications its data flow is built on', async () => {
    render(<McpAppView view={app() as never} />)
    const frame = screen.getByTitle(/interface supplied/) as HTMLIFrameElement
    const posted: unknown[] = []
    Object.defineProperty(frame, 'contentWindow', {
      value: { postMessage: (message: unknown) => posted.push(message) },
      configurable: true,
    })

    frame.dispatchEvent(new Event('load'))

    // Without these a rendered app has nothing to draw, which is the difference between
    // supporting the extension and displaying an empty box.
    await waitFor(() => expect(posted).toHaveLength(2))
    expect(posted[0]).toMatchObject({
      jsonrpc: '2.0',
      method: 'ui/notifications/tool-input',
      params: { arguments: { limit: 5 } },
    })
    expect(posted[1]).toMatchObject({
      method: 'ui/notifications/tool-result',
      params: { isError: false },
    })
  })

  it('refuses everything the app asks it to do, and says which', async () => {
    render(<McpAppView view={app() as never} />)
    const frame = screen.getByTitle(/interface supplied/) as HTMLIFrameElement
    const posted: Record<string, unknown>[] = []
    const contentWindow = { postMessage: (m: Record<string, unknown>) => posted.push(m) }
    Object.defineProperty(frame, 'contentWindow', { value: contentWindow, configurable: true })

    window.dispatchEvent(new MessageEvent('message', {
      source: contentWindow as unknown as Window,
      data: { jsonrpc: '2.0', id: 7, method: 'tools/call', params: { name: 'anything' } },
    }))

    // An app that can ask this console to run a tool is an app that can ask it to run ANY
    // tool. Not implementing the bridge is the safe state, and refusing loudly beats
    // ignoring: an app waiting forever on a reply is a spinner nobody can explain.
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toMatchObject({
      id: 7,
      error: { code: -32601 },
    })
    expect(await screen.findByText(/asked this console to tools\/call/)).toBeInTheDocument()
    expect(screen.getByText(/refused/)).toBeInTheDocument()
  })

  it('ignores a message from a window that is not its frame', async () => {
    render(<McpAppView view={app() as never} />)
    const frame = screen.getByTitle(/interface supplied/) as HTMLIFrameElement
    const posted: Record<string, unknown>[] = []
    Object.defineProperty(frame, 'contentWindow', {
      value: { postMessage: (m: Record<string, unknown>) => posted.push(m) },
      configurable: true,
    })

    // Somebody else's tab, posting at this page.
    window.dispatchEvent(new MessageEvent('message', {
      source: window,
      data: { jsonrpc: '2.0', id: 1, method: 'tools/call' },
    }))

    await new Promise((resolve) => setTimeout(resolve, 20))
    // No REPLY, which is the claim — not "nothing was posted". jsdom fires the frame's load
    // on its own, so the two tool notifications are already in there, and asserting an empty
    // list measured that rather than the check it was written for.
    expect(posted.filter((message) => 'error' in message)).toHaveLength(0)
    expect(posted.filter((message) => 'id' in message)).toHaveLength(0)
  })

  it('says so when the app had no page in it', () => {
    render(<McpAppView view={app({ html: '   ' }) as never} />)

    expect(screen.getByText('That app had no page in it.')).toBeInTheDocument()
    expect(screen.queryByTitle(/interface supplied/)).not.toBeInTheDocument()
  })

  it('is reached through the registry', () => {
    render(<ViewOf view={app() as never} />)

    expect(screen.getByTestId('mcp-app-view')).toBeInTheDocument()
    expect(screen.queryByText(/no drawing for/)).not.toBeInTheDocument()
  })
})
