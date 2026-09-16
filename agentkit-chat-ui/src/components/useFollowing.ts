import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Scrolls to the newest content, but only while the reader is already there.
 *
 * The rule an earlier prototype console got right and most chat UIs get wrong: a person who has
 * scrolled up to read a tool result must not be yanked back to the bottom every time a token
 * lands. Following is a state you leave by scrolling away and rejoin by scrolling back — never
 * something the page decides on your behalf.
 *
 * @param dependency changes whenever there is new content to follow
 */
export function useFollowing(dependency: unknown) {
  const bottom = useRef<HTMLDivElement>(null)
  const [following, setFollowing] = useState(true)

  const onScroll = useCallback((event: React.UIEvent<HTMLElement>) => {
    const element = event.currentTarget
    // A tolerance, not an equality: a fractional scroll height and a trackpad's momentum both
    // leave you a pixel or two short of the bottom, and a reader who is visually at the end
    // should be following.
    const atBottom =
      element.scrollHeight - element.scrollTop - element.clientHeight < 60
    setFollowing(atBottom)
  }, [])

  useEffect(() => {
    if (following) {
      bottom.current?.scrollIntoView({ block: 'end' })
    }
  }, [dependency, following])

  const jumpToEnd = useCallback(() => {
    setFollowing(true)
    bottom.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [])

  return { bottom, following, onScroll, jumpToEnd }
}
