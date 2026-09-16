import { useEffect, useState } from 'react'
import type { Turn } from '../lib/types'

/**
 * What a run is doing, while it does it.
 *
 * <h4>The gap this fills</h4>
 *
 * A workbench run takes a while, may stop to ask, and may be one of several. Until this the console
 * said "Thinking…" for as long as it took, which is indistinguishable from a hang — and the
 * commonest thing an operator does when a console looks hung is press the button again.
 *
 * <p>So the card says what is happening and for how long: the tool it is on, how many calls it
 * has made, and a clock. A run that is thirty seconds into a tool call looks different from one
 * that has stopped.
 */
export function Activity({
  turn,
  runningTool,
  queuedBehind,
  onStop,
}: {
  turn: Turn
  runningTool: string | null
  queuedBehind: number
  onStop: () => void
}) {
  const waiting = turn.state === 'QUEUED'
  const elapsed = useElapsed(turn.startedAt)
  const tools = turn.steps.filter((step) => step.kind === 'TOOL_CALL').length

  return (
    <div
      className="my-1 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-line px-3 py-1.5 text-xs text-muted"
      role="status"
      data-testid="activity"
    >
      <span className="relative flex h-2 w-2 shrink-0">
        {!waiting ? (
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-accent opacity-60" />
        ) : null}
        <span
          className={`relative inline-flex h-2 w-2 rounded-full ${waiting ? 'bg-muted' : 'bg-accent'}`}
        />
      </span>

      <span className="text-ink">
        {waiting
          ? queuedBehind > 0
            ? `Waiting — ${queuedBehind} ahead of it`
            : 'Waiting to start'
          : runningTool
            ? `Running ${runningTool}`
            : 'Thinking'}
      </span>

      {!waiting && tools > 0 ? (
        <span>
          {tools} tool {tools === 1 ? 'call' : 'calls'}
        </span>
      ) : null}

      <span className="tabular-nums">{seconds(elapsed)}</span>

      <button
        type="button"
        onClick={onStop}
        className="ml-auto rounded border border-line px-2 py-0.5 hover:border-bad hover:text-bad"
      >
        Stop
      </button>
    </div>
  )
}

function seconds(ms: number): string {
  if (ms < 1000) {
    return '0s'
  }
  const total = Math.floor(ms / 1000)
  return total < 60 ? `${total}s` : `${Math.floor(total / 60)}m ${total % 60}s`
}

/**
 * How long a turn has been going.
 *
 * <p>Ticks once a second rather than on every frame: this is a number a person glances at, and
 * a re-render per frame in a transcript that may hold a hundred bubbles is a cost nobody asked
 * for.
 *
 * <p>There is no "stopped" guard, and there was one. It could not fire: this card is rendered
 * only for a turn that is queued or running, so the flag was always false and the branch was
 * one no test could reach. What stops the clock is the card going away when the turn ends.
 */
function useElapsed(startedAt: string): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const tick = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(tick)
  }, [])
  const began = Date.parse(startedAt)
  return Number.isNaN(began) ? 0 : Math.max(0, now - began)
}
