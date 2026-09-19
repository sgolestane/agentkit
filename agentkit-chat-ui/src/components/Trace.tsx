import { useState } from 'react'
import type { Step, Turn } from '../lib/types'

/**
 * What the agent did to get there.
 *
 * <h4>The best thing in the console this replaces, given a shape</h4>
 *
 * An earlier prototype console built its trace from two witnesses — a wrapper round the model
 * client and an observer on the loop — and rendered it as lines of text collapsed under the
 * bubble. It was the most useful thing in that UI and it was a `List<String>`: nothing could
 * sort it, filter it, total it, or tell a model call from a tool call. These are the same facts
 * with a shape, so the row that explains a wrong answer can be opened.
 *
 * <h4>Collapsed, and never competing with the answer</h4>
 *
 * A trace that is open by default turns every reply into a wall of machinery. It is one line
 * until somebody wants it.
 */
export function Trace({ turn }: { turn: Turn }) {
  const [open, setOpen] = useState(false)
  if (turn.steps.length === 0) {
    return null
  }
  const models = turn.steps.filter((step) => step.kind === 'MODEL_CALL').length
  const tools = turn.steps.filter((step) => step.kind === 'TOOL_CALL').length
  const failed = turn.steps.some((step) => step.failed)

  return (
    <details
      className="mt-2 text-sm"
      open={open}
      onToggle={(event) => setOpen((event.target as HTMLDetailsElement).open)}
      data-testid="trace"
    >
      <summary className={`cursor-pointer select-none ${failed ? 'text-warn' : 'text-muted'}`}>
        {models} model {models === 1 ? 'call' : 'calls'}, {tools} tool{' '}
        {tools === 1 ? 'call' : 'calls'}
        {turn.inputTokens + turn.outputTokens > 0 ? (
          <>
            {' · '}
            {turn.inputTokens.toLocaleString()} in / {turn.outputTokens.toLocaleString()} out
          </>
        ) : null}
        {typeof turn.costUsd === 'number' ? ` · ${money(turn.costUsd)}` : null}
        {failed ? ' · something failed' : null}
      </summary>
      <ol className="mt-1 flex flex-col gap-1">
        {turn.steps.map((step) => (
          <TraceRow key={step.sequence} step={step} />
        ))}
      </ol>
    </details>
  )
}

/**
 * A cost, at the scale these actually are.
 *
 * A turn costing $0.0031 shown as "$0.00" is a number that says the run was free, and a
 * conversation of two hundred of them is not.
 */
export function money(usd: number): string {
  if (usd === 0) {
    return '$0'
  }
  return usd < 0.01 ? `$${usd.toFixed(4)}` : `$${usd.toFixed(2)}`
}

function TraceRow({ step }: { step: Step }) {
  const [open, setOpen] = useState(false)
  const detail = step.detail
  const disposition = String(detail.disposition ?? '')

  return (
    <li className="rounded-[var(--radius-item)] border border-line-soft bg-panel px-3 py-1.5 text-xs">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-center gap-2 text-left"
      >
        <span className="shrink-0 font-mono text-[10px] uppercase text-muted">
          {step.kind === 'MODEL_CALL' ? 'model' : step.kind === 'TOOL_CALL' ? 'tool' : 'note'}
        </span>
        <span className={`truncate ${step.failed ? 'text-bad' : ''}`}>{step.name}</span>
        {disposition && disposition !== 'RAN' ? (
          // The word the framework uses, not a colour. A refused call, a narrowed one and a
          // parked one are three different things and a console that showed them all as "error"
          // would be answering the wrong question.
          <span className="shrink-0 rounded-full border border-warn px-1.5 text-[10px] text-warn">
            {said(disposition)}
          </span>
        ) : null}
        <span className="ml-auto shrink-0 tabular-nums text-muted">{step.millis} ms</span>
      </button>

      {open ? (
        <dl className="mt-1 grid grid-cols-[5.5rem_1fr] gap-x-2 gap-y-0.5 text-[11px]">
          {rows(step).map(([label, value]) => (
            <Row key={label} label={label} value={value} />
          ))}
        </dl>
      ) : null}
    </li>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0">
        <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words">{value}</pre>
      </dd>
    </>
  )
}

/** What is worth showing for one step, in the order it is worth reading. */
function rows(step: Step): [string, string][] {
  const detail = step.detail
  const out: [string, string][] = []
  const add = (label: string, value: unknown) => {
    if (value !== undefined && value !== null && String(value) !== '') {
      out.push([label, String(value)])
    }
  }
  if (step.kind === 'TOOL_CALL') {
    add('arguments', detail.arguments)
    // The digest the model was handed. Almost every "why did it answer that" question is
    // answered here rather than by what the tool did.
    add('what the model read', detail.digest)
    add('disposition', said(String(detail.disposition ?? '')))
    add('ran', detail.ran === true ? 'yes' : 'no')
    add('provenance', detail.provenance)
    add('result size', detail.resultChars === undefined ? '' : `${detail.resultChars} chars`)
  } else if (step.kind === 'MODEL_CALL') {
    add('stopped', detail.stopReason)
    add('said', detail.text)
    add('tokens', `${detail.inputTokens ?? 0} in / ${detail.outputTokens ?? 0} out`)
  } else {
    for (const [key, value] of Object.entries(detail)) {
      add(key, value)
    }
  }
  return out
}

/**
 * A disposition in words.
 *
 * The enum's own names are precise and are not sentences. An operator reading a trace at four in
 * the afternoon should not have to know what `SKIPPED_AFTER_PARK` means.
 */
function said(disposition: string): string {
  // These are the framework's own six, and each says a different thing an operator acts on
  // differently. REFUSED is a governance event; UNKNOWN_TOOL is a wiring or model problem and
  // reporting it as a denial would invent a decision nobody made; NOT_ATTEMPTED is the run
  // declining to start something. `Disposition`'s own javadoc makes those distinctions and a
  // console that collapsed them into "error" would be answering the wrong question.
  switch (disposition) {
    case 'RAN':
      return 'ran'
    case 'THREW':
      return 'the tool failed'
    case 'UNKNOWN_TOOL':
      return 'no such tool'
    case 'REFUSED':
      return 'a gate refused it'
    case 'PARKED':
      return 'stopped to ask somebody'
    case 'NOT_ATTEMPTED':
      return 'never started'
    default:
      return disposition.toLowerCase().replaceAll('_', ' ')
  }
}
