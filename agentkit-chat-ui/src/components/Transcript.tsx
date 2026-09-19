import { useState } from 'react'
import type { AgentInfo, Attachment, PendingDecision, Turn } from '../lib/types'
import { Activity } from './Activity'
import { ApprovalCard } from './ApprovalCard'
import { TurnAttachments } from './Attachments'
import { Markdown, copy } from './Markdown'
import { PlanAnswer, isPlanTurn, planAnswer } from './PlanAnswer'
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
  onLeaveOut,
  onEdit,
  routed = false,
  agents = [],
  onSendTo,
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
  /** Leaves a finished turn out of what the agents read next, or puts it back. */
  onLeaveOut?: (turnId: string, left: boolean) => void
  onEdit: (text: string) => void
  /** Whether each message finds its own agent: then each answer says which agent gave it, and it can be asked of another. */
  routed?: boolean
  /** The agents on offer, for naming the one that answered and choosing another. */
  agents?: AgentInfo[]
  /** Sends a message to the agent named, in a conversation where each message otherwise finds its own. */
  onSendTo?: (text: string, agent: string) => void
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
        className="h-full overflow-y-auto px-4 pb-8 pt-6"
        onScroll={onScroll}
        data-testid="transcript"
      >
        {hidden > 0 ? (
          <button
            type="button"
            onClick={() => setShowAll(true)}
            className="mx-auto mb-6 block rounded-full bg-hover px-3 py-1.5 text-sm text-muted hover:text-ink"
          >
            {hidden} earlier {hidden === 1 ? 'turn' : 'turns'} — show them
          </button>
        ) : null}

        <ol className="mx-auto flex max-w-3xl flex-col gap-8">
          {shown.map((turn) => (
            <li key={turn.id} className="flex flex-col gap-3">
              {turn.userText ? (
                <div className="reading self-end max-w-[70%] whitespace-pre-wrap rounded-[var(--radius-bubble)] bg-bubble px-4 py-2.5 text-ink">
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
              {routed && !turn.state.match(/QUEUED|RUNNING/) ? (
                <AnsweredBy turn={turn} agents={agents} onSendTo={onSendTo} />
              ) : null}
              {!turn.state.match(/QUEUED|RUNNING/) ? (
                <TurnAnswer turn={turn} onLeaveOut={onLeaveOut ? (left) => onLeaveOut(turn.id, left) : undefined} />
              ) : null}
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
          <div className="mx-auto mt-2 flex max-w-3xl gap-1">
            <button
              type="button"
              onClick={onRegenerate}
              className="rounded-[var(--radius-item)] px-2.5 py-1.5 text-sm text-muted hover:bg-hover hover:text-ink"
            >
              Regenerate
            </button>
            <button
              type="button"
              onClick={() => onEdit(last.userText)}
              className="rounded-[var(--radius-item)] px-2.5 py-1.5 text-sm text-muted hover:bg-hover hover:text-ink"
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
          className="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-full bg-panel px-3 py-1.5 text-sm text-ink shadow-[var(--shadow-menu)]"
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
/**
 * Who answered a message, in a conversation where each message finds its own agent: the agent's name, or AgentKit
 * when the router answered itself. "Ask another agent" sends the same message to the one chosen.
 */
function AnsweredBy({
  turn,
  agents,
  onSendTo,
}: {
  turn: Turn
  agents: AgentInfo[]
  onSendTo?: (text: string, agent: string) => void
}) {
  const [choosing, setChoosing] = useState(false)
  const name = turn.agent ? agents.find((one) => one.id === turn.agent?.id)?.name ?? turn.agent.id : 'AgentKit'
  const others = agents.filter((one) => one.id !== turn.agent?.id)
  return (
    <div className="relative flex items-center gap-1 text-xs text-faint" data-testid="answered-by">
      <span className="rounded-full bg-hover px-2.5 py-0.5 text-muted">{name}</span>
      {onSendTo && turn.userText && others.length > 0 ? (
        <button
          type="button"
          onClick={() => setChoosing((open) => !open)}
          aria-expanded={choosing}
          aria-haspopup="menu"
          className="rounded-[var(--radius-item)] px-2 py-0.5 hover:bg-hover hover:text-ink"
        >
          Ask another agent
        </button>
      ) : null}
      {choosing ? (
        <ul
          role="menu"
          aria-label="Ask another agent"
          className="absolute left-0 top-full z-10 mt-1 w-64 rounded-[var(--radius-card)] bg-panel p-1.5 shadow-[var(--shadow-menu)]"
        >
          {others.map((agent) => (
            <li key={agent.id} role="none">
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setChoosing(false)
                  onSendTo?.(turn.userText, agent.id)
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
      className="mt-1 rounded-[var(--radius-item)] px-2 py-1 text-xs text-faint opacity-0 transition hover:bg-hover hover:text-ink group-hover:opacity-100 focus:opacity-100"
    >
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

/**
 * Leaves a finished answer out of the conversation, or puts it back. Left out, it stays on the page, faded, and no
 * agent reads it again as something said: for an answer that was wrong, which would otherwise be repeated.
 */
function LeaveOut({ turn, onLeaveOut }: { turn: Turn; onLeaveOut: (left: boolean) => void }) {
  return turn.leftOut ? (
    <p className="mt-2 flex items-center gap-2 text-xs text-faint" data-testid="left-out">
      Left out of the conversation: no agent reads it again.
      <button
        type="button"
        onClick={() => onLeaveOut(false)}
        className="rounded-[var(--radius-item)] px-2 py-0.5 text-muted hover:bg-hover hover:text-ink"
      >
        Put back
      </button>
    </p>
  ) : (
    <button
      type="button"
      onClick={() => onLeaveOut(true)}
      title="Leave this answer out of what the agents read next"
      className="mt-1 rounded-[var(--radius-item)] px-2 py-1 text-xs text-faint opacity-0 transition hover:bg-hover hover:text-ink group-hover:opacity-100 focus:opacity-100"
    >
      Leave out
    </button>
  )
}

function TurnAnswer({ turn, onLeaveOut }: { turn: Turn; onLeaveOut?: (left: boolean) => void }) {
  const streaming = turn.state === 'RUNNING' && turn.answer.length > 0
  return (
    <div className={`w-full self-start ${turn.leftOut ? 'opacity-60' : ''}`}>
      {/* Above the answer, not below it. A tool's table is what the sentence underneath is
          about, and a reader who has to scroll past the prose to find the numbers reads the
          prose without them. */}
      <Views views={turn.views} />
      {turn.answer ? (
        <div className="reading group relative" data-testid="answer">
          {(() => {
            // A carried-out plan's answer is a list of steps, each folded under how it ended, and
            // then anything the host says after them, such as what was already in place.
            const plan = !streaming && isPlanTurn(turn) ? planAnswer(turn.answer) : null
            return plan ? (
              <>
                <PlanAnswer steps={plan.steps} />
                {plan.after ? (
                  <div className="mt-4" data-testid="plan-after">
                    <Markdown text={plan.after} />
                  </div>
                ) : null}
              </>
            ) : (
              <Markdown text={turn.answer} />
            )
          })()}
          {streaming ? <span className="ml-0.5 animate-pulse text-muted">▍</span> : null}
          {!streaming ? <CopyAnswer text={turn.answer} /> : null}
          {!streaming && onLeaveOut && !turn.leftOut ? <LeaveOut turn={turn} onLeaveOut={onLeaveOut} /> : null}
        </div>
      ) : null}
      {onLeaveOut && turn.leftOut ? <LeaveOut turn={turn} onLeaveOut={onLeaveOut} /> : null}

      {turn.state === 'CANCELLED' ? (
        <p className="mt-1 text-sm text-muted">{turn.detail || 'You stopped this.'}</p>
      ) : null}
      {turn.state === 'FAILED' ? (
        <p className="mt-1 text-sm text-bad">{turn.detail || 'That did not work.'}</p>
      ) : null}
      {turn.state === 'WAITING_FOR_HUMAN' ? (
        <p className="mt-1 text-sm text-warn">{turn.detail || 'This needs a decision.'}</p>
      ) : null}
      <Trace turn={turn} />
    </div>
  )
}
