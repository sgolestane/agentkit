import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

/**
 * jsdom implements no layout, so two things every scrolling component uses are simply absent.
 *
 * Stubbed here rather than guarded in the components, because a `typeof x === 'function'` check
 * around `scrollIntoView` would be a branch that exists only for the test environment — dead in
 * every browser, and the kind of line that outlives the reason for it. What these are is a
 * statement that the environment is not a browser, made once.
 */
Element.prototype.scrollIntoView = vi.fn()

afterEach(() => {
  cleanup()
})
