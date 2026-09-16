import { useState } from 'react'
import type { PendingDecision } from '../lib/types'
import { Verbatim } from './Verbatim'
import { plain } from '../lib/text'

/**
 * A decision, where it was raised.
 *
 * <h4>Why inline and not in a panel</h4>
 *
 * Both consoles this replaces put pending decisions in a side panel — the workbench dashboard's "Waiting on you",
 * itops' approvals list — and a panel separates the question from everything that led to it. The
 * operator reads "approve identity.reset for alice?" with no sight of the ticket that asked for
 * it or the three tool calls that established who alice is. Here the card sits in the transcript
 * at the point the run stopped, with the reasoning above it.
 *
 * <h4>The buttons stay reachable</h4>
 *
 * The proposed arguments are the model's text and have no length limit — a drafted comment runs
 * to a paragraph. The workbench dashboard's stylesheet carries the bug this avoids in a comment of its own: left
 * unbounded, the arguments pushed Approve and Reject out of the panel's scroll area, which is
 * the one part of the card that must always be reachable. The arguments scroll inside
 * themselves; the actions do not move.
 */
export function ApprovalCard({
  decision,
  onDecide,
  busy,
}: {
  decision: PendingDecision
  onDecide: (
    verdict: 'approve' | 'reject' | 'edit' | 'answer',
    body?: Record<string, unknown>,
  ) => void
  busy?: boolean
}) {
  const [note, setNote] = useState('')
  const [answer, setAnswer] = useState('')
  const [standing, setStanding] = useState(false)
  const [edited, setEdited] = useState<string | null>(null)
  const question = decision.kind === 'QUESTION'
  // The card exists so a person can read a call before it happens, so it is the one place in
  // the console where text that renders as something other than what it says is not a
  // cosmetic problem. The arguments are already covered — they go through `Verbatim` — and
  // these are the rest of what somebody reads before they press Approve.
  const tool = plain(decision.tool)
  const why = plain(decision.reason)
  const effect = plain(decision.effect)
  const asked = plain(decision.question)

  return (
    <section
      className={`my-2 rounded-xl border px-3 py-2.5 ${question ? 'border-accent' : 'border-warn'}`}
      data-testid="approval-card"
      aria-label={question ? 'A question for you' : 'A decision for you'}
    >
      <h3 className="text-sm font-semibold">
        {question ? 'The agent is asking you something' : `Approve ${tool}?`}
      </h3>

      {question ? (
        <p className="mt-1 whitespace-pre-wrap">{asked}</p>
      ) : (
        <>
          <dl className="mt-2 grid grid-cols-[6rem_1fr] gap-x-3 gap-y-1 text-[13px]">
            <dt className="text-muted">Why</dt>
            <dd>{why}</dd>
            {effect ? (
              <>
                <dt className="text-muted">Effect</dt>
                <dd>{effect}</dd>
              </>
            ) : null}
            <dt className="text-muted">Reversible</dt>
            <dd className={decision.reversible ? '' : 'text-warn'}>
              {decision.reversible ? 'yes' : 'no — this cannot be undone'}
            </dd>
            {decision.capability ? (
              <>
                <dt className="text-muted">Capability</dt>
                <dd>{decision.capability}</dd>
              </>
            ) : null}
          </dl>

          {/* The model's own words, shown as its own words. It scrolls inside itself so the
              actions below never move out of reach. */}
          <div className="mt-2 max-h-40 overflow-auto">
            <Verbatim
              label="What it would run"
              text={
                edited ?? JSON.stringify(decision.arguments, null, 2)
              }
            />
          </div>
        </>
      )}

      {question ? (
        <textarea
          rows={2}
          value={answer}
          aria-label="Your answer"
          placeholder="Answer them…"
          onChange={(event) => setAnswer(event.target.value)}
          className="mt-2 w-full resize-y rounded-lg border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-accent"
        />
      ) : (
        <input
          type="text"
          value={note}
          aria-label="Note"
          placeholder="Why? (optional, and read by the next run)"
          onChange={(event) => setNote(event.target.value)}
          className="mt-2 w-full rounded-lg border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-accent"
        />
      )}

      {!question && decision.capability ? (
        <label className="mt-2 flex items-center gap-2 text-xs text-muted">
          <input
            type="checkbox"
            checked={standing}
            onChange={(event) => setStanding(event.target.checked)}
          />
          {/* Named, because the operator is deciding about a family and not about this call. */}
          Apply this to every “{decision.capability}” from now on
        </label>
      ) : null}

      <div className="mt-2 flex flex-wrap gap-2">
        {question ? (
          <>
            <button
              type="button"
              disabled={busy || !answer.trim()}
              onClick={() => onDecide('answer', { answer: answer.trim() })}
              className="rounded-lg bg-accent px-3 py-1 text-sm font-medium text-white disabled:opacity-40"
            >
              Answer
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => onDecide('answer', { answer: '' })}
              className="rounded-lg border border-line px-3 py-1 text-sm disabled:opacity-40"
            >
              I do not know
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                edited === null
                  ? onDecide('approve', { note, standing })
                  : onDecide('edit', { arguments: parsed(edited), note, standing })
              }
              className="rounded-lg bg-accent px-3 py-1 text-sm font-medium text-white disabled:opacity-40"
            >
              {edited === null ? 'Approve' : 'Approve as edited'}
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => onDecide('reject', { note, standing })}
              className="rounded-lg border border-bad px-3 py-1 text-sm text-bad disabled:opacity-40"
            >
              Reject
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                setEdited((current) =>
                  current === null ? JSON.stringify(decision.arguments, null, 2) : null,
                )
              }
              className="rounded-lg border border-line px-3 py-1 text-sm disabled:opacity-40"
            >
              {edited === null ? 'Edit' : 'Cancel edit'}
            </button>
          </>
        )}
      </div>

      {edited !== null ? (
        <textarea
          rows={5}
          value={edited}
          aria-label="Arguments to run instead"
          onChange={(event) => setEdited(event.target.value)}
          className="mt-2 w-full resize-y rounded-lg border border-line bg-canvas px-2 py-1 font-mono text-[12px] outline-none focus:border-accent"
        />
      ) : null}
    </section>
  )
}

/**
 * The edited arguments, or the text as one field if it is not JSON.
 *
 * Somebody editing a call is under time pressure and a JSON parse error that silently discards
 * their edit is the worst thing this could do. The far side judges the arguments anyway — a
 * gate's rename-and-renumber refusal applies to a person's edit exactly as it does to a gate's.
 */
function parsed(text: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(text)
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : { value }
  } catch {
    return { value: text }
  }
}
