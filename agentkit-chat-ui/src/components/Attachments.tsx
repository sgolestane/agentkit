import type { Attachment } from '../lib/types'

/** How a file's size reads to a person. */
export function sized(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 102.4) / 10} kB`
  }
  return `${Math.round(bytes / (1024 * 102.4)) / 10} MB`
}

/**
 * A file that has been handed over, or is on its way.
 *
 * <p>Shown with its shape rather than its contents: the bytes stay server-side, and a preview
 * that fetched them back to draw would be doing the one thing this design exists to avoid.
 */
export interface Upload {
  key: string
  name: string
  bytes: number
  mediaType: string
  /** The id once the server has it; absent while it is still going. */
  id?: string
  failed?: string
}

export function UploadChip({ upload, onRemove }: { upload: Upload; onRemove?: () => void }) {
  return (
    <span
      className={`inline-flex max-w-full items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs ${
        upload.failed ? 'border-bad text-bad' : 'border-line text-muted'
      }`}
      data-testid="upload-chip"
    >
      <span className="truncate" title={upload.name}>
        {upload.name}
      </span>
      <span className="shrink-0 opacity-70">
        {upload.failed ? upload.failed : upload.id ? sized(upload.bytes) : 'sending…'}
      </span>
      {onRemove ? (
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Remove ${upload.name}`}
          className="shrink-0 opacity-70 hover:opacity-100"
        >
          ×
        </button>
      ) : null}
    </span>
  )
}

/** The files a turn was sent with, drawn under the message that carried them. */
export function TurnAttachments({ attachments }: { attachments: Attachment[] }) {
  if (attachments.length === 0) {
    return null
  }
  return (
    <div className="mt-1 flex flex-wrap justify-end gap-1" data-testid="turn-attachments">
      {attachments.map((one) => (
        <a
          key={one.id}
          href={`/api/attachments/${encodeURIComponent(one.id)}`}
          download={one.name}
          className="inline-flex items-center gap-1.5 rounded-full border border-line px-2 py-0.5 text-xs text-muted hover:text-ink"
        >
          <span className="truncate max-w-48" title={one.name}>
            {one.name}
          </span>
          <span className="opacity-70">{sized(one.bytes)}</span>
        </a>
      ))}
    </div>
  )
}
