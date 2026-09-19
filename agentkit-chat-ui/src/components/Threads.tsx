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
    <aside className="hidden w-[260px] shrink-0 flex-col bg-sidebar md:flex">
      <div className="flex h-[52px] items-center gap-2 px-3">
        <h1 className="px-1 text-lg font-semibold">AgentKit</h1>
        <NewConversation agents={agents} onCreate={onCreate} />
      </div>

      <div className="px-2 pb-2">
        <input
          type="search"
          aria-label="Search conversations"
          placeholder="Search…"
          value={filter}
          onChange={(event) => onFilter(event.target.value)}
          className="h-9 w-full rounded-[var(--radius-item)] bg-transparent px-2.5 text-sm text-ink outline-none placeholder:text-faint hover:bg-hover focus:bg-hover"
        />
      </div>

      <p className="px-4 pb-1 pt-3 text-sm font-medium text-faint">Conversations</p>
      <ul className="flex-1 overflow-y-auto px-2 pb-2" data-testid="threads">
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
          <li className="px-2.5 py-2 text-sm text-faint">Nothing matches that.</li>
        ) : null}
      </ul>
      {admin ? (
        <a href="/admin" className="m-2 rounded-[var(--radius-item)] px-2.5 py-2 text-sm text-muted hover:bg-hover hover:text-ink">
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
        className="ml-auto flex h-9 w-9 items-center justify-center rounded-[var(--radius-item)] text-lg text-muted hover:bg-hover hover:text-ink"
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
        className="flex h-9 w-9 items-center justify-center rounded-[var(--radius-item)] text-lg text-muted hover:bg-hover hover:text-ink"
      >
        +
      </button>
      {choosing ? (
        <ul
          role="menu"
          aria-label="Which agent"
          className="absolute right-0 z-10 mt-1 w-64 rounded-[var(--radius-card)] bg-panel p-1.5 shadow-[var(--shadow-menu)]"
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
                className="w-full rounded-[var(--radius-item)] px-2.5 py-2 text-left hover:bg-hover"
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
          className="h-9 w-full rounded-[var(--radius-item)] border border-line bg-panel px-2.5 text-sm outline-none focus:border-accent"
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
        className={`min-w-0 flex-1 truncate rounded-[var(--radius-item)] px-2.5 py-2 text-left text-sm ${
          open ? 'bg-selected text-ink' : 'text-ink hover:bg-hover'
        }`}
      >
        {working ? (
          <span
            aria-label="working"
            className="mr-1.5 inline-block h-1.5 w-1.5 rounded-full bg-accent align-middle"
          />
        ) : null}
        {thread.title || 'New conversation'}
        {agent ? <span className="block truncate text-xs text-faint">{agent}</span> : null}
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
        className={`shrink-0 rounded-[var(--radius-item)] px-2 py-2 text-xs ${
          armed ? 'text-bad' : 'text-faint opacity-0 hover:bg-hover hover:text-ink group-hover:opacity-100 focus:opacity-100'
        }`}
      >
        {armed ? 'sure?' : '×'}
      </button>
    </li>
  )
}
