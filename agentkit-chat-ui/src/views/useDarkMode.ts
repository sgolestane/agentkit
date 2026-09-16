import { useEffect, useState } from 'react'

/**
 * Whether the page is being read in dark mode.
 *
 * <p>Asked rather than assumed, because the chart palette has two selected sets and a chart
 * drawn with the light one on a dark surface fails the contrast check the palette was validated
 * against. Everything else in this console styles itself with CSS custom properties and needs
 * no such question; an SVG fill is the one thing that cannot.
 */
export function useDarkMode(): boolean {
  const [dark, setDark] = useState(
    () => globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false,
  )
  useEffect(() => {
    const query = globalThis.matchMedia?.('(prefers-color-scheme: dark)')
    if (!query) {
      return
    }
    const update = (event: MediaQueryListEvent) => setDark(event.matches)
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  return dark
}
