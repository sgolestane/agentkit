import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from './api'
import type { Conversation } from './types'

/**
 * Which conversation is open, and every other one.
 *
 * <h4>The address bar is the source of truth for "which"</h4>
 *
 * A thread has a URL you can send to somebody, and the console reads it on load. Kept in
 * `history` rather than in state alone so Back works: a person who switches threads and presses
 * Back means "the one I was reading", and a console that ignored that would be the only tab in
 * their browser that did.
 */
export function useThreads() {
  const [threads, setThreads] = useState<Conversation[]>([])
  const [current, setCurrent] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [problem, setProblem] = useState<string | null>(null)

  /** The conversation the address bar names, if it names one. */
  const inTheUrl = useCallback(() => {
    const match = /^\/c\/([A-Za-z0-9_-]+)/.exec(globalThis.location?.pathname ?? '')
    return match?.[1] ?? null
  }, [])

  const open = useCallback(
    (id: string | null, push = true) => {
      setCurrent(id)
      if (!id || !globalThis.history) {
        return
      }
      if (push) {
        globalThis.history.pushState({ id }, '', `/c/${id}`)
      } else {
        // REPLACED, not left alone. Arriving at `/` and resolving to a conversation has to
        // stamp the address bar, or the first history entry names no conversation — and Back,
        // after switching threads once, lands on a console with nothing open. Replacing also
        // keeps that arrival out of the history as a separate step, which it is not.
        globalThis.history.replaceState({ id }, '', `/c/${id}`)
      }
    },
    [],
  )

  useEffect(() => {
    let live = true
    api
      .conversations()
      .then(async (existing) => {
        if (!live) {
          return
        }
        // A console that opens on nothing has nowhere to type. One conversation, made on
        // arrival, is the difference between a usable page and a page with a button on it —
        // with the first agent on offer, where there is a choice; the "+" starts the others.
        const list =
          existing.length > 0 ? existing : [await api.create('', (await api.agents())[0]?.id)]
        if (!live) {
          return
        }
        setThreads(list)
        const asked = inTheUrl()
        const found = asked && list.some((one) => one.id === asked) ? asked : list[0]?.id ?? null
        // Not pushed: arriving at a URL is not navigating to it, and pushing here would put
        // two identical entries in the history before the person has done anything.
        open(found, false)
      })
      .catch((error: unknown) => {
        if (live) {
          setProblem(error instanceof ApiError ? error.message : 'The console did not answer.')
        }
      })
    return () => {
      live = false
    }
  }, [inTheUrl, open])

  // Back and Forward.
  useEffect(() => {
    const onPop = () => setCurrent(inTheUrl())
    globalThis.addEventListener?.('popstate', onPop)
    return () => globalThis.removeEventListener?.('popstate', onPop)
  }, [inTheUrl])

  const create = useCallback(async (agent?: string) => {
    try {
      const made = await api.create('', agent)
      setThreads((existing) => [made, ...existing])
      open(made.id)
    } catch (error: unknown) {
      setProblem(error instanceof ApiError ? error.message : 'A new conversation could not be started.')
    }
  }, [open])

  const rename = useCallback(async (id: string, title: string) => {
    // Shown at once. A rename that waited for a round trip looks like a field that ignored you.
    setThreads((existing) =>
      existing.map((one) => (one.id === id ? { ...one, title } : one)),
    )
    try {
      await api.rename(id, title)
    } catch {
      setProblem('That could not be renamed.')
    }
  }, [])

  const forget = useCallback(
    async (id: string) => {
      setThreads((existing) => {
        const left = existing.filter((one) => one.id !== id)
        if (id === current) {
          open(left[0]?.id ?? null)
        }
        return left
      })
      try {
        await api.forget(id)
      } catch {
        setProblem('That could not be deleted.')
      }
    },
    [current, open],
  )

  /** Renames the open thread from the server's own copy, once it has been auto-titled. */
  const noteTitle = useCallback((id: string, title: string) => {
    setThreads((existing) => {
      // The SAME array back when nothing changed, not a fresh one with the same contents.
      // Returning a new array unconditionally makes every call a state change, and the effect
      // that calls this runs after every render — which is an infinite loop that presents as
      // the test suite hanging rather than as an error.
      const needsIt = existing.some((one) => one.id === id && !one.title)
      return needsIt
        ? existing.map((one) => (one.id === id ? { ...one, title } : one))
        : existing
    })
  }, [])

  const shown = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    if (!needle) {
      return threads
    }
    return threads.filter((one) => one.title.toLowerCase().includes(needle))
  }, [threads, filter])

  return { threads, shown, current, filter, setFilter, open, create, rename, forget, noteTitle, problem }
}
