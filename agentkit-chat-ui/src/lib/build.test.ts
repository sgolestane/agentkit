import { describe, expect, it } from 'vitest'
import { isStale } from './build'

/**
 * The staleness rule, which is small and has one arm that is easy to get wrong.
 *
 * A tab left open across a restart runs yesterday's JavaScript against today's API, and the
 * failures that produces read as bugs in the console rather than as staleness. The check that
 * catches it must not also fire in development, where the page is served by Vite and never
 * stamped — a reload on every poll makes hot reload unusable, which is the one thing that would
 * get this deleted rather than fixed.
 */
describe('isStale', () => {
  it('reloads when the page is older than the server', () => {
    expect(isStale('abc123', 'def456')).toBe(true)
  })

  it('does not reload when they agree', () => {
    expect(isStale('abc123', 'abc123')).toBe(false)
  })

  it('does not reload a page nobody stamped', () => {
    // Vite serves index.html verbatim, so the placeholder survives. An unstamped page has made
    // no claim about which build it is, and treating that as stale reloads forever.
    expect(isStale('%UI_BUILD%', 'def456')).toBe(false)
    expect(isStale('', 'def456')).toBe(false)
  })

  it('does not reload when the server has not said', () => {
    // An overview without a build — an older server, or a hand-written stub — is not evidence
    // that this page is wrong.
    expect(isStale('abc123', '')).toBe(false)
  })
})
