import { useEffect, useRef, useState } from 'react'
import type { View } from '../lib/types'

/**
 * A tool that brought its own interface.
 *
 * <h4>MCP Apps, and what a host actually signs up for</h4>
 *
 * SEP-1865: an MCP server predeclares a `ui://` resource holding a self-contained page, a tool
 * points at it, and a host that understands the extension renders it beside the result. The
 * server side is `agentkit-mcp` — it refuses anything that is not a `ui://` uri and anything
 * whose type is not `text/html;profile=mcp-app`, so what arrives here has already been through
 * two gates. This is the third and it is the one that matters, because this is where somebody
 * else's HTML meets a page that can approve tool calls.
 *
 * <h4>Three things stand between that HTML and this console</h4>
 *
 * 1. **`sandbox` without `allow-same-origin`.** The frame gets an opaque origin, so it cannot
 *    touch this document, its storage, or its cookies — the same-origin policy does the work
 *    and it does it whatever the page inside tries. `allow-scripts` is granted because an app
 *    that cannot run is not an app; the two together are safe *only* because the second is
 *    absent, and a future edit that adds `allow-same-origin` alongside `allow-scripts` would
 *    hand the frame this console's origin. That pair is the single most common way an iframe
 *    sandbox is silently undone, and `escaping.test.tsx` fails on it.
 * 2. **A policy inside the document.** `default-src 'none'` prepended as a meta tag, so the
 *    page cannot fetch, cannot load an image, cannot phone home. The spec expects a host to
 *    block external requests and says apps must be self-contained for that reason.
 *
 *    Delivered as `<meta http-equiv>` rather than the `csp` iframe attribute, which was
 *    proposed and never shipped — no browser honours it, so using it would have been a
 *    control that looked present and did nothing. The residual is real and stated: a policy
 *    from a meta tag applies from where it appears, so a request the resource made *before*
 *    that tag would not be covered. What bounds it is the sandbox above, which is why that
 *    one is first: without `allow-same-origin` the frame has an opaque origin, so whatever it
 *    reaches for, it reaches with no credentials and can read nothing of this console's.
 * 3. **Every request from the frame is refused.** The extension lets an app call
 *    `tools/call`, `ui/open-link` and more back into the host. None of that is implemented,
 *    and *not implementing it is the safe state*: an app that can ask this console to run a
 *    tool is an app that can ask it to run any tool. It gets a JSON-RPC error naming the
 *    method, which is what the protocol says to do with something a host does not support.
 *
 *    **This was decided, not deferred (#390).** A consent prompt would put a person in the
 *    position of judging a call they did not initiate, described in words the app's server
 *    chose — the prompt-injection surface this repository spends its effort closing, with a
 *    button next to it. `ui/open-link` is not the safe subset it looks like either: the app
 *    picks the URL, and a transcript is full of things worth putting in a query string. An
 *    app that needs to act should expose a tool on its own MCP server, where this console's
 *    gate, provenance and approval rules already govern it. See `docs/STANDARDS.md`.
 *
 * <h4>What the app does get</h4>
 *
 * The two notifications its data flow is built on — `ui/notifications/tool-input` and
 * `ui/notifications/tool-result` — posted once the frame has loaded. Without them a rendered
 * app has nothing to draw, which is the difference between supporting the extension and
 * displaying an empty box.
 */
/** The app's own page, with this host's policy in front of it. */
export function sealed(html: string): string {
  const policy =
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:"
  return `<meta http-equiv="Content-Security-Policy" content="${policy}">${html}`
}

export function McpAppView({ view }: { view: View }) {
  const html = String(view.data.html ?? '')
  const tool = String(view.data.tool ?? 'a tool')
  const uri = String(view.data.uri ?? '')
  const frame = useRef<HTMLIFrameElement>(null)
  const [refused, setRefused] = useState<string[]>([])

  useEffect(() => {
    const element = frame.current
    if (!element) {
      return
    }

    function post(method: string, params: unknown) {
      // Targeted at "*", which is correct and is worth the sentence: the frame has an opaque
      // origin because it is sandboxed without allow-same-origin, so there is no origin to
      // name. What makes that safe is the direction — this is what we are willing to tell it,
      // not something we are trusting it with.
      element?.contentWindow?.postMessage(
        { jsonrpc: '2.0', method, params },
        '*',
      )
    }

    function onLoad() {
      post('ui/notifications/tool-input', { arguments: view.data.arguments ?? {} })
      post('ui/notifications/tool-result', {
        content: [{ type: 'text', text: String(view.data.result ?? '') }],
        isError: view.data.isError === true,
      })
    }

    function onMessage(event: MessageEvent) {
      // Only from this frame. Any other window posting at this page is somebody else's tab.
      if (event.source !== element?.contentWindow) {
        return
      }
      const message = event.data as { id?: unknown; method?: unknown } | null
      const method = typeof message?.method === 'string' ? message.method : ''
      if (!method || message?.id === undefined) {
        return
      }
      // -32601 is JSON-RPC's "method not found", which is what a host says about something it
      // does not implement. Refused rather than ignored: an app waiting forever on a reply it
      // will never get is a spinner nobody can explain.
      element?.contentWindow?.postMessage(
        {
          jsonrpc: '2.0',
          id: message.id,
          error: {
            code: -32601,
            message: `This console does not implement ${method}.`,
          },
        },
        '*',
      )
      setRefused((seen) => (seen.includes(method) ? seen : [...seen, method]))
    }

    element.addEventListener('load', onLoad)
    window.addEventListener('message', onMessage)
    return () => {
      element.removeEventListener('load', onLoad)
      window.removeEventListener('message', onMessage)
    }
  }, [view.data.arguments, view.data.result, view.data.isError])

  if (!html.trim()) {
    return <p className="my-2 text-xs text-muted">That app had no page in it.</p>
  }

  return (
    <figure className="my-2 rounded-lg border border-line" data-testid="mcp-app-view">
      <figcaption className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line px-3 py-2">
        <span className="text-sm font-semibold">{tool}</span>
        {/* Named, because a person should be able to tell whose interface they are looking
            at. It is not this console's. */}
        <span className="text-[11px] text-muted" title={uri}>
          an app from the tool&rsquo;s own server
        </span>
      </figcaption>
      <iframe
        ref={frame}
        title={`${tool} — an interface supplied by the tool's server`}
        srcDoc={sealed(html)}
        sandbox="allow-scripts"
        className="h-80 w-full rounded-b-lg bg-canvas"
      />
      {refused.length > 0 ? (
        <p className="border-t border-line px-3 py-1.5 text-[11px] text-muted">
          It asked this console to {refused.join(', ')} — refused.
        </p>
      ) : null}
    </figure>
  )
}
