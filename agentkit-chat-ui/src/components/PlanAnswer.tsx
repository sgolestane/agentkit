import type { Turn } from '../lib/types'
import { Markdown } from './Markdown'

/** One step of a carried-out plan, as the host reports it. */
export interface PlanStep {
  step: string
  /**
   * 'done'; 'failed' when its agent finished but a change it made reported an error; 'stopped' when its agent did
   * not finish; or 'not-started'
   */
  outcome: 'done' | 'failed' | 'stopped' | 'not-started'
  /** Why it stopped ("failed", "budget exhausted", …), or which tools failed */
  reason?: string
  /** What the step's agent said it did; empty for a step that did not start. */
  said: string
}

const STEP = /^(\d+)\. (.*)$/
const DONE = /^ {3}- Done: (.*)$/
const FAILED = /^ {3}- Failed \(([^)]*)\): (.*)$/
const STOPPED = /^ {3}- Stopped \(([^)]*)\): (.*)$/
const NOT_STARTED = /^ {3}- Not started\.$/

/**
 * A plan-and-execute turn's answer, read back into its steps: the host writes it as a numbered
 * list, each step followed by one line saying how it ended (`PlanExecuteTurn.answer`). Null for
 * anything else — an answer still streaming, or one a person could have written — which is then
 * shown as the markdown it is.
 */
export function planSteps(answer: string): PlanStep[] | null {
  const lines = answer.split('\n')
  if (lines.length < 2 || lines.length % 2 !== 0) {
    return null
  }
  const steps: PlanStep[] = []
  for (let i = 0; i < lines.length; i += 2) {
    const step = STEP.exec(lines[i] ?? '')
    if (!step || Number(step[1]) !== steps.length + 1) {
      return null
    }
    const text = step[2] ?? ''
    const outcome = lines[i + 1] ?? ''
    const done = DONE.exec(outcome)
    const stopped = STOPPED.exec(outcome)
    const failed = FAILED.exec(outcome)
    if (done) {
      steps.push({ step: text, outcome: 'done', said: done[1] ?? '' })
    } else if (failed) {
      steps.push({ step: text, outcome: 'failed', reason: failed[1] ?? '', said: failed[2] ?? '' })
    } else if (stopped) {
      steps.push({ step: text, outcome: 'stopped', reason: stopped[1] ?? '', said: stopped[2] ?? '' })
    } else if (NOT_STARTED.test(outcome)) {
      steps.push({ step: text, outcome: 'not-started', said: '' })
    } else {
      return null
    }
  }
  return steps
}

/** Whether a turn carried out a plan: its trace has the plan it made. */
export function isPlanTurn(turn: Turn): boolean {
  return turn.steps.some((step) => step.kind === 'NOTE' && step.name === 'plan')
}

/**
 * The steps of a carried-out plan, each with what came of it folded away under how it ended, so
 * the plan reads as a list and a step's account is one click away. A step that failed or stopped
 * says so in the fold's label, in colour, so nothing that went wrong is hidden by folding.
 */
export function PlanAnswer({ steps }: { steps: PlanStep[] }) {
  return (
    <ol className="list-decimal space-y-2 pl-6" data-testid="plan-answer">
      {steps.map((one, index) => (
        <li key={index} className="pl-1">
          <Markdown text={one.step} />
          {one.outcome === 'not-started' ? (
            <p className="text-sm text-faint">Not started</p>
          ) : (
            <details className="group" data-testid="plan-step-outcome">
              <summary
                className={`inline-flex cursor-pointer select-none list-none items-center gap-1 rounded-[var(--radius-item)] px-1.5 py-0.5 text-sm hover:bg-hover [&::-webkit-details-marker]:hidden ${
                  one.outcome === 'failed' ? 'text-bad' : one.outcome === 'stopped' ? 'text-warn' : 'text-muted'
                }`}
              >
                <span className="inline-block transition-transform group-open:rotate-90" aria-hidden="true">
                  ›
                </span>
                {one.outcome === 'failed' ? `Failed: ${one.reason}`
                  : one.outcome === 'stopped' ? `Stopped (${one.reason})` : 'Done'}
              </summary>
              <div className="mt-1 border-l-2 border-line pl-4 text-muted">
                <Markdown text={one.said || '(nothing said)'} />
              </div>
            </details>
          )}
        </li>
      ))}
    </ol>
  )
}
