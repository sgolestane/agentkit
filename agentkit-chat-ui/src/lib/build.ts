/**
 * Whether this page is older than the server it is talking to.
 *
 * `ChatServer` stamps `index.html` with the process it was served from and reports the same
 * value on `/api/overview`. A tab left open across a restart is running yesterday's JavaScript
 * against today's API, and the failures that produces — a field that is suddenly absent, an
 * endpoint that moved — read as bugs in the console rather than as staleness.
 *
 * An earlier prototype console learned this the hard way and its comment is worth keeping: "A
 * browser tab must never run yesterday's console against today's server."
 */
export function stampInThisPage(): string {
  const meta = document.querySelector('meta[name="ui-build"]')
  return meta?.getAttribute('content') ?? ''
}

/**
 * True when the page should reload rather than carry on.
 *
 * Deliberately not "the stamps differ". In development the page is served by Vite and never
 * stamped, so the placeholder survives; treating that as stale would reload on every poll and
 * make hot reload unusable. An unstamped page is a page nobody has made a claim about.
 */
export function isStale(pageStamp: string, serverStamp: string): boolean {
  if (!pageStamp || pageStamp === '%UI_BUILD%') {
    return false
  }
  return Boolean(serverStamp) && pageStamp !== serverStamp
}
