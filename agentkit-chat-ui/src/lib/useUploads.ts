import { useCallback, useState } from 'react'
import { api, ApiError } from './api'
import type { Upload } from '../components/Attachments'

/**
 * Files on their way to the server, and what happened to them.
 *
 * <h4>One at a time, and that is deliberate</h4>
 *
 * Six files dropped at once are six requests, and the console bounds a body at 32 MB — six of
 * those in flight together is 192 MB of heap on a demo server that has said it accepts 32.
 * Sequential is also the only order in which the failures make sense: a person who drops six
 * and sees the fourth refused knows which one.
 */
export function useUploads(conversationId: string | null) {
  const [uploads, setUploads] = useState<Upload[]>([])

  const add = useCallback(
    async (files: File[]) => {
      if (!conversationId || files.length === 0) {
        return
      }
      const queued: Upload[] = files.map((file, index) => ({
        key: `${Date.now()}-${index}-${file.name}`,
        name: file.name,
        bytes: file.size,
        mediaType: file.type || 'application/octet-stream',
      }))
      setUploads((current) => [...current, ...queued])

      for (const [index, file] of files.entries()) {
        const key = queued[index]!.key
        try {
          const stored = await api.attach(conversationId, file)
          setUploads((current) =>
            current.map((one) => (one.key === key ? { ...one, id: stored.id } : one)),
          )
        } catch (error: unknown) {
          // Kept, marked, rather than dropped. A file that vanished from the composer with no
          // explanation is a person wondering whether they attached it at all.
          setUploads((current) =>
            current.map((one) =>
              one.key === key
                ? {
                    ...one,
                    failed:
                      error instanceof ApiError ? error.message : 'could not be sent',
                  }
                : one,
            ),
          )
        }
      }
    },
    [conversationId],
  )

  const remove = useCallback((key: string) => {
    setUploads((current) => current.filter((one) => one.key !== key))
  }, [])

  /** The ids to send with the next message — only the ones that actually arrived. */
  const ready = uploads.filter((one) => one.id).map((one) => one.id as string)

  const clear = useCallback(() => setUploads([]), [])

  return { uploads, add, remove, ready, clear }
}
