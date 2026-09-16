import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { EventSchemas, verifyEvents } from '@ag-ui/client'
import { of } from 'rxjs'
import { catchError, toArray } from 'rxjs/operators'
import { describe, expect, it } from 'vitest'

/**
 * The AG-UI adapter's own stream, checked by the SDK a frontend would use.
 *
 * ## Why this file is here and not in the Java module
 *
 * `AFrontendBuiltForAgUiCanDriveThisRuntimeTest` checks the event names, the required fields
 * and the ordering — against the rules *this repository wrote down*. That is the gap #389 was
 * filed for: a schema can be right while a client chokes on something the spec left implicit.
 *
 * The gap was real. Nobody had written down that a `STEP_FINISHED` needs a matching
 * `STEP_STARTED`, so the adapter sent `STEP_FINISHED "model"` alone and every Java test agreed
 * with it. `@ag-ui/client`'s `verifyEvents` — the protocol's own state machine — rejects that,
 * and rejecting means the client **stops reading the stream**, so the answer arriving after it
 * never reaches the screen. A second fault of the same kind: `STEP_STARTED "running"` was
 * never closed, so `RUN_FINISHED` was refused and the run never completed for the client.
 *
 * Both are fixed in `AgUiEvents`. This runs on every build so the next one is caught the same
 * way, by the same third party, rather than by a rule we thought to write down.
 *
 * ## What it reads
 *
 * Recordings of real runs, produced by `TheRecordedRunsAreWhatThisServerStillSendsTest` from
 * the actual `AgUiServer` over a real socket. That test also re-checks them against the live
 * server, so a hand-edited or stale fixture fails there. Neither half is worth much alone: a
 * fixture nobody regenerates measures last year's server, and a stream nobody validates
 * measures nothing at all.
 */
const RECORDED = join(__dirname, '../../../agentkit-agui/src/test/resources/recorded')

function recordings(): [string, Record<string, unknown>[]][] {
  return readdirSync(RECORDED)
    .filter((name) => name.endsWith('.json'))
    .map((name) => [
      name.replace(/\.json$/, ''),
      JSON.parse(readFileSync(join(RECORDED, name), 'utf8')),
    ])
}

/** What the SDK's state machine makes of a stream, or the reason it gave up on it. */
function accepted(events: unknown[]): Promise<{ kept: number; refusal: string | null }> {
  return new Promise((done) => {
    let refusal: string | null = null
    of(...(events as never[]))
      .pipe(
        verifyEvents(false),
        catchError((failure: Error) => {
          refusal = failure.message
          return of()
        }),
        toArray(),
      )
      .subscribe((out) => done({ kept: out.length, refusal }))
  })
}

describe('every recorded run is one a real AG-UI client can follow', () => {
  const all = recordings()

  it('found the recordings the Java side produces', () => {
    // A glob that matches nothing makes every test below pass by having nothing to run,
    // which is the failure mode of every directory-driven suite.
    expect(all.length).toBeGreaterThan(0)
  })

  it.each(all)('%s survives the protocol state machine intact', async (_name, events) => {
    const { kept, refusal } = await accepted(events)

    // Not "did not throw" — every event, in order. A client that rejects halfway has stopped
    // reading, and everything after the rejection is invisible to it however valid it was.
    expect(refusal).toBeNull()
    expect(kept).toBe(events.length)
  })

  it.each(all)('%s carries fields the SDK schemas accept', (_name, events) => {
    // `EventSchemas` is the SDK's discriminated union over every event it knows, so this
    // checks two things at once: that the `type` is one the protocol defines — an invented
    // one fails to discriminate — and that the fields under it are what that type requires.
    for (const event of events) {
      const parsed = EventSchemas.safeParse(event)
      expect(
        parsed.success,
        `${String(event.type)}: ${parsed.success ? '' : JSON.stringify(parsed.error.issues)}`,
      ).toBe(true)
    }
  })
})

describe('the four things the adapter was least sure of', () => {
  const answering = recordings().find(([name]) => name === 'answers-after-a-tool')?.[1] ?? []
  const types = answering.map((one) => String(one.type))

  it('streams the text rather than delivering it in one piece', () => {
    // If a client shows nothing until the end, the message framing is wrong somewhere. Three
    // deltas inside one START/END pair is what streaming looks like on this wire.
    const deltas = types.filter((one) => one === 'TEXT_MESSAGE_CONTENT')
    expect(deltas.length).toBeGreaterThan(1)
    expect(types.indexOf('TEXT_MESSAGE_START')).toBeLessThan(
      types.indexOf('TEXT_MESSAGE_CONTENT'))
    expect(types.lastIndexOf('TEXT_MESSAGE_CONTENT')).toBeLessThan(
      types.indexOf('TEXT_MESSAGE_END'))
  })

  it('sends tool arguments as one whole JSON delta, which parses', () => {
    // Legal, and the first thing a client written against a fragment-by-fragment backend
    // might trip on: this runtime learns the arguments all at once, so splitting them into
    // fragments would be ceremony around the same total.
    const args = answering.find((one) => one.type === 'TOOL_CALL_ARGS')
    expect(args).toBeDefined()
    expect(() => JSON.parse(String(args?.delta))).not.toThrow()
    expect(JSON.parse(String(args?.delta))).toEqual({ limit: 5 })
  })

  it('ends the run without an outcome field, which the schema allows', () => {
    // Optional in the schema, and clients may not treat it as optional. If one does, this is
    // the recording that says what it was given.
    const finished = answering.at(-1) as Record<string, unknown>
    expect(finished.type).toBe('RUN_FINISHED')
    expect(finished).not.toHaveProperty('outcome')
    expect(finished.result).toBeDefined()
  })

  it('carries what AG-UI has no word for as CUSTOM, which a client may ignore', () => {
    // A frontend that does not know the name skips it; one that errors on an unknown CUSTOM
    // name is a problem worth knowing about, and this is what it would be given.
    const custom = answering.filter((one) => one.type === 'CUSTOM')
    expect(custom.length).toBeGreaterThan(0)
    expect(custom.map((one) => String(one.name))).toContain('agentkit.view')
    for (const one of custom) {
      expect(one).toHaveProperty('value')
    }
  })
})
