import { useEffect, useRef, useState } from 'react'
import type { AgentInfo, Conversation } from '../lib/types'
import { AdminIcon, AgentIcon, NewIcon, SearchIcon, SidebarIcon, SignOutIcon } from './Icons'

/**
 * The sidebar: starting a conversation, finding one, the agents on offer, every conversation, and
 * who is signed in.
 *
 * <p>The knowledge base and the lesson book are both built on work accumulating, and a console
 * that forgets on restart makes that a claim nobody can check. This is the other half of the
 * file-backed store: somewhere to find yesterday's conversation.
 *
 * <p>It folds to a rail of icons, remembered per browser; nothing about a conversation depends on
 * it being open.
 */
export function Threads({
  threads,
  current,
  filter,
  working,
  agents = [],
  admin = false,
  routing = false,
  user,
  org,
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
  /** The agents on offer. With more than one, "New conversation" asks which; with one or none it just starts. */
  agents?: AgentInfo[]
  /** Whether the person may see the organization's admin view, which is then linked from the sidebar. */
  admin?: boolean
  /** Whether a new conversation needs no agent chosen, each message finding its own; then "New conversation" just starts one. */
  routing?: boolean
  /** Who is signed in, as the console knows them: an email, or a tenant id. */
  user?: string
  /** The organization they are signed in to, if the console serves several. */
  org?: string
  onFilter: (value: string) => void
  onOpen: (id: string) => void
  onCreate: (agent?: string) => void
  onRename: (id: string, title: string) => void
  onForget: (id: string) => void
}) {
  const [folded, fold] = useFolded()
  const search = useRef<HTMLInputElement>(null)

  if (folded) {
    return (
      <aside className="hidden w-[52px] shrink-0 flex-col items-center gap-1 bg-sidebar py-2 md:flex">
        <RailButton label="Open the sidebar" onClick={() => fold(false)}>
          <SidebarIcon />
        </RailButton>
        <NewConversation agents={routing ? [] : agents} onCreate={onCreate} rail />
        <RailButton
          label="Search conversations"
          onClick={() => {
            fold(false)
            setTimeout(() => search.current?.focus(), 0)
          }}
        >
          <SearchIcon />
        </RailButton>
        {admin ? (
          <a href="/admin" aria-label="Admin" title="Admin" className={RAIL}>
            <AdminIcon />
          </a>
        ) : null}
        <div className="flex-1" />
        {user ? <Account user={user} org={org} rail /> : null}
      </aside>
    )
  }

  return (
    <aside className="hidden w-[260px] shrink-0 flex-col bg-sidebar md:flex">
      <div className="flex h-[52px] shrink-0 items-center gap-2 pl-4 pr-2">
        <h1 className="text-lg font-semibold">AgentKit</h1>
        <div className="ml-auto">
          <RailButton label="Close the sidebar" onClick={() => fold(true)}>
            <SidebarIcon />
          </RailButton>
        </div>
      </div>

      <div className="flex shrink-0 flex-col px-2">
        <NewConversation agents={routing ? [] : agents} onCreate={onCreate} />
        <label className={ROW}>
          <SearchIcon />
          <input
            ref={search}
            type="search"
            aria-label="Search conversations"
            placeholder="Search conversations"
            value={filter}
            onChange={(event) => onFilter(event.target.value)}
            className="min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink"
          />
        </label>
        {admin ? (
          <a href="/admin" className={ROW}>
            <AdminIcon />
            Admin
          </a>
        ) : null}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {agents.length > 1 ? (
          <section aria-label="Agents">
            <h2 className="px-2.5 pb-1 pt-5 text-sm font-medium text-faint">Agents</h2>
            <ul>
              {agents.map((agent) => (
                <li key={agent.id}>
                  <button
                    type="button"
                    onClick={() => onCreate(agent.id)}
                    title={agent.unavailable ?? agent.description}
                    className={`${ROW} w-full text-left ${agent.unavailable ? 'text-faint' : ''}`}
                  >
                    <AgentIcon />
                    <span className="truncate">{agent.name}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <section aria-label="Conversations">
          <h2 className="px-2.5 pb-1 pt-5 text-sm font-medium text-faint">Conversations</h2>
          <ul data-testid="threads">
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
        </section>
      </div>

      {user ? (
        <div className="shrink-0 p-2">
          <Account user={user} org={org} />
        </div>
      ) : null}
    </aside>
  )
}

/** A row of the sidebar: an icon and a label, 36px tall. */
export const ROW = 'flex h-9 items-center gap-2.5 rounded-[var(--radius-item)] px-2.5 text-sm text-ink hover:bg-hover'

/** A square icon button, for the header and the folded rail. */
export const RAIL = 'flex h-9 w-9 items-center justify-center rounded-[var(--radius-item)] text-muted hover:bg-hover hover:text-ink'

const FOLDED_KEY = 'agentkit.sidebar.folded'

function remembered(): boolean {
  try {
    return localStorage.getItem(FOLDED_KEY) === '1'
  } catch {
    return false
  }
}

/** Whether the sidebar is folded to a rail, remembered per browser for every page that has one. */
export function useFolded(): [boolean, (folded: boolean) => void] {
  const [folded, setFolded] = useState(() => remembered())
  return [
    folded,
    (value: boolean) => {
      setFolded(value)
      try {
        localStorage.setItem(FOLDED_KEY, value ? '1' : '0')
      } catch {
        // A browser that keeps nothing still folds; it just forgets.
      }
    },
  ]
}

export function RailButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" aria-label={label} title={label} onClick={onClick} className={RAIL}>
      {children}
    </button>
  )
}

/**
 * Who is signed in, at the foot of the sidebar: their initials, their name as their email
 * suggests it, and their organization. It opens upwards onto signing out.
 */
export function Account({ user, org, rail = false }: { user: string; org?: string; rail?: boolean }) {
  const [open, setOpen] = useState(false)
  const email = user.includes('/') ? user.slice(user.indexOf('/') + 1) : user
  const name = nameOf(email)
  const initials = name.split(' ').map((part) => part[0] ?? '').join('').slice(0, 2).toUpperCase()
  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((was) => !was)}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={`${name}, signed in${org ? ` to ${org}` : ''}`}
        title={email}
        className={rail ? RAIL : `${ROW} h-12 w-full text-left`}
      >
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-raised text-xs font-medium text-muted">
          {initials || '?'}
        </span>
        {rail ? null : (
          <span className="min-w-0">
            <span className="block truncate text-sm text-ink">{name}</span>
            {org ? <span className="block truncate text-xs text-faint">{org}</span> : null}
          </span>
        )}
      </button>
      {open ? (
        <div
          role="menu"
          aria-label="Account"
          className={`absolute bottom-full z-10 mb-1 w-60 rounded-[var(--radius-card)] bg-panel p-1.5 shadow-[var(--shadow-menu)] ${
            rail ? 'left-0' : 'left-0 right-0 w-auto'
          }`}
        >
          <p className="truncate px-2.5 py-2 text-sm text-muted">{email}</p>
          <a href="/sign-out" role="menuitem" className={ROW}>
            <SignOutIcon />
            Sign out
          </a>
        </div>
      ) : null}
    </div>
  )
}

/** "priya.natarajan@acme.example" as "Priya Natarajan": what an email's first half usually is. */
export function nameOf(email: string): string {
  const local = email.split('@')[0] ?? email
  const words = local.split(/[._-]+/).filter(Boolean)
  return words.length === 0 ? email : words.map((word) => word[0]!.toUpperCase() + word.slice(1)).join(' ')
}

/**
 * "New conversation", which starts one — with the one agent there is, or, when there is a choice,
 * with the one picked from a short list. A list rather than a dialog: it is one decision, and the
 * names and one-line descriptions are all it takes to make it.
 */
function NewConversation({
  agents,
  onCreate,
  rail = false,
}: {
  agents: AgentInfo[]
  onCreate: (agent?: string) => void
  /** As an icon on the folded rail, rather than a row. */
  rail?: boolean
}) {
  const [choosing, setChoosing] = useState(false)
  const trigger = rail ? RAIL : `${ROW} w-full text-left`
  const content = rail ? <NewIcon /> : (
    <>
      <NewIcon />
      New conversation
    </>
  )
  if (agents.length <= 1) {
    return (
      <button
        type="button"
        onClick={() => onCreate(agents[0]?.id)}
        aria-label="Start a new conversation"
        title="Start a new conversation"
        className={trigger}
      >
        {content}
      </button>
    )
  }
  return (
    <div className={rail ? 'relative' : 'relative w-full'}>
      <button
        type="button"
        onClick={() => setChoosing((open) => !open)}
        aria-label="Start a new conversation"
        aria-expanded={choosing}
        aria-haspopup="menu"
        title="Start a new conversation"
        className={trigger}
      >
        {content}
      </button>
      {choosing ? (
        <ul
          role="menu"
          aria-label="Which agent"
          className={`absolute z-10 mt-1 w-64 rounded-[var(--radius-card)] bg-panel p-1.5 shadow-[var(--shadow-menu)] ${
            rail ? 'left-full top-0 ml-1 mt-0' : 'left-0'
          }`}
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
        className={`flex h-9 min-w-0 flex-1 items-center rounded-[var(--radius-item)] px-2.5 text-left text-sm ${
          open ? 'bg-selected text-ink' : 'text-ink hover:bg-hover'
        }`}
      >
        {working ? (
          <span
            aria-label="working"
            className="mr-1.5 inline-block h-1.5 w-1.5 rounded-full bg-accent align-middle"
          />
        ) : null}
        <span className="truncate">{thread.title || 'New conversation'}</span>
        {agent ? <span className="ml-2 shrink-0 text-xs text-faint">{agent}</span> : null}
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
