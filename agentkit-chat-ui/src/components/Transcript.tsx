import { useState } from 'react'
import type { Attachment, PendingDecision, Turn } from '../lib/types'
import { Activity } from './Activity'
import { ApprovalCard } from './ApprovalCard'
import { TurnAttachments } from './Attachments'
import { Markdown, copy } from './Markdown'
import { Trace } from './Trace'
import { Views } from '../views/registry'
import { useFollowing } from './useFollowing'

/**
 * How many turns are drawn without being asked.
 *
 * <h4>Windowing, and why not pixel virtualization</h4>
 *
 * The goal is that a long conversation stays smooth. A windowed list gets there by bounding
 * what is in the DOM, with an explicit control for the rest — the reader always knows what is
 * being withheld and can ask for it.
 *
 * True virtualization would be better for seamless scrolling through thousands of turns, and it
 * is a poor trade here: a chat's rows have wildly variable heights that change as content
 * streams in and traces expand, which is the case dynamic-measurement virtualizers handle worst,
 * and jsdom reports every height as zero — so every behavioural test in this file would be
 * asserting against a list that had decided to render one row. Bounded DOM without either cost.
 */
const WINDOW = 200

export function Transcript({
  turns,
  working,
  runningTool,
  pending = [],
  attachments = [],
  onStop,
  onDecide,
  onRegenerate,
  onEdit,
}: {
  turns: Turn[]
  working: boolean
  runningTool: string | null
  pending?: PendingDecision[]
  attachments?: Attachment[]
  onStop?: () => void
  onDecide?: (
    id: string,
    verdict: 'approve' | 'reject' | 'edit' | 'answer',
    body?: Record<string, unknown>,
  ) => void
  onRegenerate: () => void
  onEdit: (text: string) => void
}) {
  const [showAll, setShowAll] = useState(false)
  const hidden = showAll ? 0 : Math.max(0, turns.length - WINDOW)
  const shown = hidden > 0 ? turns.slice(hidden) : turns
  // Following has to react to the answer growing, not just to a turn arriving, or a streaming
  // reply scrolls once and then runs off the bottom of the screen.
  const { bottom, following, onScroll, jumpToEnd } = useFollowing(
    `${turns.length}:${turns[turns.length - 1]?.answer.length ?? 0}`,
  )
  const last = turns[turns.length - 1]

  return (
    <div className="relative flex-1 overflow-hidden">
      <div
        className="h-full overflow-y-auto px-4 py-4"
        onScroll={onScroll}
        data-testid="transcript"
      >
        {hidden > 0 ? (
          <button
            type="button"
            onClick={() => setShowAll(true)}
            className="mx-auto mb-4 block rounded-full border border-line px-3 py-1 text-xs text-muted"
          >
            {hidden} earlier {hidden === 1 ? 'turn' : 'turns'} — show them
          </button>
        ) : null}

        <ol className="mx-auto flex max-w-3xl flex-col gap-5">
          {shown.map((turn) => (
            <li key={turn.id} className="flex flex-col gap-2">
              {turn.userText ? (
                <div className="self-end max-w-[85%] whitespace-pre-wrap rounded-2xl bg-accent px-4 py-2 text-white">
                  {turn.userText}
                </div>
              ) : null}
              {turn.attachments.length > 0 ? (
                <TurnAttachments
                  attachments={turn.attachments.map(
                    (id) => attachments.find((one) => one.id === id) ?? {
                      id, name: id, mediaType: '', bytes: 0, uploadedAt: '',
                    },
                  )}
                />
              ) : null}
              {!turn.state.match(/QUEUED|RUNNING/) ? <TurnAnswer turn={turn} /> : null}
              {turn.state === 'QUEUED' || turn.state === 'RUNNING' ? (
                <>
                  {turn.answer ? <TurnAnswer turn={turn} /> : null}
                  <Activity
                    turn={turn}
                    runningTool={turn.state === 'RUNNING' ? runningTool : null}
                    queuedBehind={
                      turns.filter(
                        (earlier) =>
                          earlier.ordinal < turn.ordinal && !earlier.endedAt,
                      ).length
                    }
                    onStop={() => onStop?.()}
                  />
                </>
              ) : null}
              {/* At the point the run stopped, with everything that led to it above. A side
                  panel separates the question from its reasoning: the operator reads
                  "approve identity.reset for alice?" with no sight of the ticket that asked
                  for it or the tool calls that established who alice is. */}
              {pending
                .filter((decision) => decision.turnId === turn.id)
                .map((decision) => (
                  <ApprovalCard
                    key={decision.id}
                    decision={decision}
                    onDecide={(verdict, body) => onDecide?.(decision.id, verdict, body)}
                  />
                ))}
            </li>
          ))}
        </ol>

        {!working && last && last.state === 'COMPLETED' ? (
          <div className="mx-auto mt-4 flex max-w-3xl gap-2">
            <button
              type="button"
              onClick={onRegenerate}
              className="rounded-lg border border-line px-3 py-1 text-xs text-muted hover:text-ink"
            >
              Regenerate
            </button>
            <button
              type="button"
              onClick={() => onEdit(last.userText)}
              className="rounded-lg border border-line px-3 py-1 text-xs text-muted hover:text-ink"
            >
              Edit and resend
            </button>
          </div>
        ) : null}

        <div ref={bottom} />
      </div>

      {!following ? (
        <button
          type="button"
          onClick={jumpToEnd}
          className="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-full border border-line bg-panel px-3 py-1 text-xs shadow"
        >
          Jump to the end
        </button>
      ) : null}
    </div>
  )
}

/**
 * What the agent said, and how the turn ended.
 *
 * The four terminal states read differently on purpose. A cancelled turn is a decision the
 * person made, not a failure; a turn waiting on somebody is neither finished nor failed, and a
 * console that drew it as an error would show a person an error to dismiss where there is a
 * decision to make.
 */
/**
 * Copies the whole answer as the markdown it was written in.
 *
 * The source, not the rendered text: somebody copying an answer with a table in it wants the
 * table, and `textContent` off the DOM would hand them the cells run together on one line.
 */
function CopyAnswer({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      type="button"
      aria-label="Copy answer"
      onClick={() => {
        void copy(text).then(() => {
          setCopied(true)
          setTimeout(() => setCopied(false), 1500)
        })
      }}
      className="absolute right-2 top-2 rounded border border-line bg-canvas px-2 py-0.5 text-[11px] text-muted opacity-0 transition group-hover:opacity-100 focus:opacity-100"
    >
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

function TurnAnswer({ turn }: { turn: Turn }) {
  const streaming = turn.state === 'RUNNING' && turn.answer.length > 0
  return (
    <div className="w-full max-w-[92%] self-start">
      {/* Above the answer, not below it. A tool's table is what the sentence underneath is
          about, and a reader who has to scroll past the prose to find the numbers reads the
          prose without them. */}
      <Views views={turn.views} />
      {turn.answer ? (
        <div className="group relative rounded-2xl bg-panel px-4 py-2" data-testid="answer">
          <Markdown text={turn.answer} />
          {streaming ? <span className="ml-0.5 animate-pulse text-muted">▍</span> : null}
          {!streaming ? <CopyAnswer text={turn.answer} /> : null}
        </div>
      ) : null}

      {turn.state === 'CANCELLED' ? (
        <p className="mt-1 text-xs text-muted">{turn.detail || 'You stopped this.'}</p>
      ) : null}
      {turn.state === 'FAILED' ? (
        <p className="mt-1 text-xs text-bad">{turn.detail || 'That did not work.'}</p>
      ) : null}
      {turn.state === 'WAITING_FOR_HUMAN' ? (
        <p className="mt-1 text-xs text-warn">{turn.detail || 'This needs a decision.'}</p>
      ) : null}
      <Trace turn={turn} />
    </div>
  )
}
