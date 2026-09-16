import { useEffect, useRef, useState } from 'react'
import type { Conversation } from '../lib/types'

/**
 * Every conversation, and which one is open.
 *
 * <p>The knowledge base and the lesson book are both built on work accumulating, and a console
 * that forgets on restart makes that a claim nobody can check. This is the other half of the
 * file-backed store: somewhere to find yesterday's conversation.
 */
export function Threads({
  threads,
  current,
  filter,
  working,
  onFilter,
  onOpen,
  onCreate,
  onRename,
  onForget,
}: {
  threads: Conversation[]
  current: string | null
  filter: string
  working: boolean
  onFilter: (value: string) => void
  onOpen: (id: string) => void
  onCreate: () => void
  onRename: (id: string, title: string) => void
  onForget: (id: string) => void
}) {
  return (
    <aside className="hidden w-60 shrink-0 flex-col border-r border-line bg-panel md:flex">
      <div className="flex items-center gap-2 border-b border-line px-3 py-2">
        <h1 className="text-sm font-semibold">AgentKit</h1>
        <button
          type="button"
          onClick={onCreate}
          aria-label="Start a new conversation"
          title="Start a new conversation"
          className="ml-auto rounded border border-line px-2 py-0.5 text-sm text-muted hover:text-ink"
        >
          +
        </button>
      </div>

      <div className="px-2 pt-2">
        <input
          type="search"
          aria-label="Search conversations"
          placeholder="Search…"
          value={filter}
          onChange={(event) => onFilter(event.target.value)}
          className="w-full rounded border border-line bg-canvas px-2 py-1 text-xs outline-none focus:border-accent"
        />
      </div>

      <ul className="flex-1 overflow-y-auto p-2" data-testid="threads">
        {threads.map((thread) => (
          <ThreadRow
            key={thread.id}
            thread={thread}
            open={thread.id === current}
            working={working && thread.id === current}
            onOpen={() => onOpen(thread.id)}
            onRename={(title) => onRename(thread.id, title)}
            onForget={() => onForget(thread.id)}
          />
        ))}
        {threads.length === 0 ? (
          <li className="px-3 py-2 text-xs text-muted">Nothing matches that.</li>
        ) : null}
      </ul>
    </aside>
  )
}

function ThreadRow({
  thread,
  open,
  working,
  onOpen,
  onRename,
  onForget,
}: {
  thread: Conversation
  open: boolean
  working: boolean
  onOpen: () => void
  onRename: (title: string) => void
  onForget: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(thread.title)
  const [armed, setArmed] = useState(false)
  const disarm = useRef<ReturnType<typeof setTimeout>>(undefined)

  useEffect(() => setTitle(thread.title), [thread.title])
  useEffect(() => () => clearTimeout(disarm.current), [])

  if (editing) {
    return (
      <li>
        <input
          autoFocus
          value={title}
          aria-label="Conversation name"
          onChange={(event) => setTitle(event.target.value)}
          onBlur={() => {
            setEditing(false)
            onRename(title)
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              setEditing(false)
              onRename(title)
            }
            if (event.key === 'Escape') {
              setEditing(false)
              setTitle(thread.title)
            }
          }}
          className="w-full rounded border border-accent bg-canvas px-2 py-1 text-sm outline-none"
        />
      </li>
    )
  }

  return (
    <li className="group flex items-center gap-1">
      <button
        type="button"
        onClick={onOpen}
        onDoubleClick={() => setEditing(true)}
        aria-current={open ? 'true' : undefined}
        className={`min-w-0 flex-1 truncate rounded-lg px-2 py-1.5 text-left text-sm ${
          open ? 'bg-canvas text-ink' : 'text-muted hover:text-ink'
        }`}
      >
        {working ? (
          <span
            aria-label="working"
            className="mr-1.5 inline-block h-1.5 w-1.5 rounded-full bg-accent align-middle"
          />
        ) : null}
        {thread.title || 'New conversation'}
      </button>

      <button
        type="button"
        aria-label={armed ? `Really delete ${thread.title || 'this conversation'}?` : `Delete ${thread.title || 'this conversation'}`}
        title={armed ? 'Click again to delete' : 'Delete'}
        onClick={() => {
          // Two clicks, no blocking dialog. A confirm() stops the whole page — including the
          // event stream — and a delete that cannot be undone should cost a second press
          // rather than a modal nobody reads. It disarms itself, so a stray first click is
          // not a trap left lying about.
          if (armed) {
            clearTimeout(disarm.current)
            onForget()
            return
          }
          setArmed(true)
          disarm.current = setTimeout(() => setArmed(false), 4000)
        }}
        className={`shrink-0 rounded px-1.5 py-1 text-xs ${
          armed ? 'text-bad' : 'text-muted opacity-0 group-hover:opacity-100 focus:opacity-100'
        }`}
      >
        {armed ? 'sure?' : '×'}
      </button>
    </li>
  )
}
