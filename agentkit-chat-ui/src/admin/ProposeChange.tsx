import { useEffect, useState } from 'react'
import { Markdown } from '../components/Markdown'
import { api, ApiError } from '../lib/api'
import type { AgentFile, ProposalOutcome } from '../lib/types'

/**
 * A change to one agent's files, proposed for review: edited here, checked by the host the way it
 * would load it, and opened as a pull request (or a branch, in development). What the host serves
 * changes when that is merged — never from here — so Git stays the one source of truth.
 */
export function ProposeChange({ agentId, agentName, where }: { agentId: string; agentName: string; where: string }) {
  const [files, setFiles] = useState<AgentFile[] | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [edits, setEdits] = useState<Record<string, string>>({})
  const [title, setTitle] = useState(`Change ${agentName}`)
  const [description, setDescription] = useState('')
  const [working, setWorking] = useState(false)
  const [outcome, setOutcome] = useState<ProposalOutcome | null>(null)

  useEffect(() => {
    api.admin
      .files(agentId)
      .then((read) => {
        setFiles(read.files)
        setSelected(read.files[0]?.path ?? null)
      })
      .catch((error: unknown) => setProblem(error instanceof ApiError ? error.message : 'The files did not load.'))
  }, [agentId])

  if (problem) {
    return <p className="text-sm text-bad">{problem}</p>
  }
  if (!files) {
    return <p className="text-sm text-muted">Loading the files…</p>
  }

  const original = (path: string) => files.find((file) => file.path === path)?.content ?? ''
  const current = selected ? (edits[selected] ?? original(selected)) : ''
  const changed = Object.entries(edits).filter(([path, content]) => content !== original(path))

  const submit = () => {
    setWorking(true)
    setOutcome(null)
    api.admin
      .propose({ title, description, files: Object.fromEntries(changed) })
      .then(setOutcome)
      .catch((error: unknown) =>
        setOutcome({ opened: false, problems: [error instanceof ApiError ? error.message : 'The proposal failed.'] }),
      )
      .finally(() => setWorking(false))
  }

  return (
    <section aria-label="Propose a change" className="space-y-3 rounded-lg border border-line bg-panel p-3">
      <p className="text-xs text-muted">
        The host checks the change the way it would load it, then opens it for review as {where}. Nothing changes for
        anyone until it is merged.
      </p>
      <div className="flex gap-3">
        <ul className="w-56 shrink-0 text-xs" aria-label="Files">
          {files.map((file) => {
            const edited = file.path in edits && edits[file.path] !== file.content
            return (
              <li key={file.path}>
                <button
                  type="button"
                  onClick={() => setSelected(file.path)}
                  aria-current={selected === file.path ? 'true' : undefined}
                  className={`block w-full truncate rounded px-2 py-1 text-left font-mono ${
                    selected === file.path ? 'bg-canvas text-ink' : 'text-muted hover:text-ink'
                  }`}
                >
                  {file.path.replace(`agents/${agentId}/`, '')}
                  {edited ? ' •' : ''}
                </button>
              </li>
            )
          })}
        </ul>
        {selected ? (
          <textarea
            aria-label={`Contents of ${selected}`}
            value={current}
            onChange={(event) => setEdits((all) => ({ ...all, [selected]: event.target.value }))}
            spellCheck={false}
            className="h-80 min-w-0 flex-1 rounded border border-line bg-canvas p-2 font-mono text-xs outline-none focus:border-accent"
          />
        ) : null}
      </div>
      <label className="block text-xs">
        Title
        <input
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          className="mt-1 block w-full rounded border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-accent"
        />
      </label>
      <label className="block text-xs">
        Why (optional)
        <textarea
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          className="mt-1 block h-16 w-full rounded border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-accent"
        />
      </label>
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={submit}
          disabled={working || changed.length === 0 || title.trim() === ''}
          className="rounded bg-accent px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          {working ? 'Checking…' : 'Check and open for review'}
        </button>
        <span className="text-xs text-muted">
          {changed.length === 0 ? 'Nothing edited yet.' : `${changed.length} file${changed.length === 1 ? '' : 's'} changed`}
        </span>
      </div>
      {outcome?.opened ? (
        <div role="status" className="rounded border border-good p-2 text-sm">
          <p className="text-good">
            Opened for review:{' '}
            {outcome.url ? (
              <a href={outcome.url} target="_blank" rel="noreferrer" className="underline">
                {outcome.url}
              </a>
            ) : (
              <code>{outcome.branch}</code>
            )}
          </p>
          <div className="mt-1 text-xs">
            <Markdown text={outcome.summary ?? ''} />
          </div>
        </div>
      ) : null}
      {outcome && !outcome.opened ? (
        <div role="alert" className="rounded border border-bad p-2 text-sm text-bad">
          <p>Not opened, because:</p>
          <ul className="mt-1 list-disc pl-5 text-xs">
            {(outcome.problems ?? []).map((one) => (
              <li key={one}>{one}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  )
}
