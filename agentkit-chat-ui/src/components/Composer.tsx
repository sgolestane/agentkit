import { useEffect, useRef, useState } from 'react'
import { UploadChip, type Upload } from './Attachments'

/**
 * Where a person says something.
 *
 * Multiline by default, because an operator pasting a log or writing a paragraph of context is
 * the normal case and a single-line input makes that a fight. Enter sends and Shift+Enter adds
 * a line, which is what every chat has trained everyone to expect.
 */
export function Composer({
  onSend,
  disabled,
  disabledReason,
  initialText = '',
  uploads = [],
  onAttach,
  onRemoveUpload,
}: {
  onSend: (text: string) => void
  disabled?: boolean
  disabledReason?: string
  initialText?: string
  uploads?: Upload[]
  onAttach?: (files: File[]) => void
  onRemoveUpload?: (key: string) => void
}) {
  const [text, setText] = useState(initialText)
  const [over, setOver] = useState(false)
  const box = useRef<HTMLTextAreaElement>(null)
  const picker = useRef<HTMLInputElement>(null)

  // Edit-and-resend puts the old message back in here, so the box has to follow that rather
  // than keep whatever it had.
  useEffect(() => {
    if (initialText) {
      setText(initialText)
      box.current?.focus()
    }
  }, [initialText])

  // Grows with the text, up to a point. A box that scrolls internally at three lines hides
  // what you just wrote; one that grows forever pushes the conversation off the screen.
  useEffect(() => {
    const element = box.current
    if (element) {
      element.style.height = 'auto'
      element.style.height = `${Math.min(element.scrollHeight, 240)}px`
    }
  }, [text])

  const send = () => {
    const trimmed = text.trim()
    // A message with files and no words is a real message — "here, look at this" — so it goes
    // as long as there is something to send.
    if ((!trimmed && uploads.length === 0) || disabled) {
      return
    }
    onSend(trimmed)
    setText('')
  }

  const take = (list: FileList | null) => {
    const files = Array.from(list ?? [])
    if (files.length > 0) {
      onAttach?.(files)
    }
  }

  return (
    <form
      className="bg-canvas px-4 pb-4 pt-2"
      onSubmit={(event) => {
        event.preventDefault()
        send()
      }}
      onDragOver={(event) => {
        if (onAttach && !disabled) {
          event.preventDefault()
          setOver(true)
        }
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(event) => {
        if (!onAttach || disabled) {
          return
        }
        event.preventDefault()
        setOver(false)
        take(event.dataTransfer?.files ?? null)
      }}
      data-testid="composer"
    >
      <div
        className={`mx-auto flex w-full max-w-3xl flex-col gap-2 rounded-[var(--radius-pill)] bg-panel px-2.5 py-2 shadow-[var(--shadow-composer)] ${
          over ? 'outline outline-2 outline-accent' : ''
        }`}
      >
      {uploads.length > 0 ? (
        <div className="flex flex-wrap gap-1.5 px-1.5 pt-1">
          {uploads.map((upload) => (
            <UploadChip
              key={upload.key}
              upload={upload}
              onRemove={onRemoveUpload ? () => onRemoveUpload(upload.key) : undefined}
            />
          ))}
        </div>
      ) : null}

      <div className="flex items-end gap-1.5">
      <textarea
        ref={box}
        rows={1}
        value={text}
        disabled={disabled}
        aria-label="Message"
        placeholder={disabled ? (disabledReason ?? 'Not available') : 'Say something…'}
        title={disabled ? disabledReason : undefined}
        className="reading order-2 max-h-52 min-h-9 flex-1 resize-none bg-transparent px-1.5 py-[5px] text-ink outline-none placeholder:text-faint disabled:opacity-60"
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault()
            send()
          }
        }}
        onPaste={(event) => {
          // A screenshot from the clipboard is the commonest attachment an operator has, and
          // it has no filename until something gives it one.
          const files = Array.from(event.clipboardData?.files ?? [])
          if (files.length > 0 && onAttach && !disabled) {
            event.preventDefault()
            onAttach(files)
          }
        }}
      />
      {onAttach ? (
        <>
          <input
            ref={picker}
            type="file"
            multiple
            className="hidden"
            aria-label="Attach files"
            onChange={(event) => {
              take(event.target.files)
              event.target.value = ''
            }}
          />
          <button
            type="button"
            disabled={disabled}
            aria-label="Attach a file"
            title="Attach a file"
            onClick={() => picker.current?.click()}
            className="order-1 flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xl text-muted hover:bg-hover hover:text-ink disabled:opacity-40"
          >
            +
          </button>
        </>
      ) : null}
      <button
        type="submit"
        disabled={disabled || (!text.trim() && uploads.length === 0)}
        className="order-3 h-9 shrink-0 rounded-full bg-primary px-4 text-sm font-medium text-on-primary hover:opacity-80 disabled:opacity-30"
      >
        Send
      </button>
      </div>
      </div>
    </form>
  )
}
