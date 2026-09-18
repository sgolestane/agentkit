import { useEffect, useRef, useState } from 'react'
import type { AgentInfo, Conversation } from '../lib/types'

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
  agents = [],
  admin = false,
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
  /** The agents on offer. With more than one, "+" asks which; with one or none it just starts. */
  agents?: AgentInfo[]
  /** Whether the person may see the organization's admin view, which is then linked at the foot. */
  admin?: boolean
  onFilter: (value: string) => void
  onOpen: (id: string) => void
  onCreate: (agent?: string) => void
  onRename: (id: string, title: string) => void
  onForget: (id: string) => void
}) {
  return (
    <aside className="hidden w-60 shrink-0 flex-col border-r border-line bg-panel md:flex">
      <div className="flex items-center gap-2 border-b border-line px-3 py-2">
        <h1 className="text-sm font-semibold">AgentKit</h1>
        <NewConversation agents={agents} onCreate={onCreate} />
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
            agent={agents.length > 1 ? agents.find((one) => one.id === thread.agent?.id)?.name ?? thread.agent?.id : undefined}
            onOpen={() => onOpen(thread.id)}
            onRename={(title) => onRename(thread.id, title)}
            onForget={() => onForget(thread.id)}
          />
        ))}
        {threads.length === 0 ? (
          <li className="px-3 py-2 text-xs text-muted">Nothing matches that.</li>
        ) : null}
      </ul>
      {admin ? (
        <a href="/admin" className="border-t border-line px-3 py-2 text-xs text-muted hover:text-ink">
          Admin: agents, versions and rehearsals
        </a>
      ) : null}
    </aside>
  )
}

/**
 * "+", which starts a conversation — with the one agent there is, or, when there is a choice, with
 * the one picked from a short list. A list rather than a dialog: it is one decision, and the
 * names and one-line descriptions are all it takes to make it.
 */
function NewConversation({
  agents,
  onCreate,
}: {
  agents: AgentInfo[]
  onCreate: (agent?: string) => void
}) {
  const [choosing, setChoosing] = useState(false)
  if (agents.length <= 1) {
    return (
      <button
        type="button"
        onClick={() => onCreate(agents[0]?.id)}
        aria-label="Start a new conversation"
        title="Start a new conversation"
        className="ml-auto rounded border border-line px-2 py-0.5 text-sm text-muted hover:text-ink"
      >
        +
      </button>
    )
  }
  return (
    <div className="relative ml-auto">
      <button
        type="button"
        onClick={() => setChoosing((open) => !open)}
        aria-label="Start a new conversation"
        aria-expanded={choosing}
        aria-haspopup="menu"
        title="Start a new conversation"
        className="rounded border border-line px-2 py-0.5 text-sm text-muted hover:text-ink"
      >
        +
      </button>
      {choosing ? (
        <ul
          role="menu"
          aria-label="Which agent"
          className="absolute right-0 z-10 mt-1 w-56 rounded-lg border border-line bg-panel p-1 shadow-lg"
        >
          {agents.map((agent) => (
            <li key={agent.id} role="none">
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setChoosing(false)
                  onCreate(agent.id)
                }}
                className="w-full rounded px-2 py-1.5 text-left hover:bg-canvas"
              >
                <span className="block text-sm text-ink">{agent.name}</span>
                {agent.unavailable ?? agent.description ? (
                  <span className="block text-xs text-muted">{agent.unavailable ?? agent.description}</span>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}

function ThreadRow({
  thread,
  open,
  working,
  agent,
  onOpen,
  onRename,
  onForget,
}: {
  thread: Conversation
  open: boolean
  working: boolean
  /** Which agent it is with, when the console offers more than one. */
  agent?: string
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
        {agent ? <span className="block truncate text-xs text-muted">{agent}</span> : null}
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
