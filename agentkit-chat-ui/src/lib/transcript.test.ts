import { describe, expect, it } from 'vitest'
import { applied, asTurn, emptyTranscript, isWorking, lastUserText, loaded } from './transcript'
import type { ChatEvent } from './types'

let sequence = 0
function event(type: ChatEvent['type'], data: Record<string, unknown>, turnId = 'turn-1'): ChatEvent {
  return {
    sequence: ++sequence,
    conversationId: 'conv-1',
    turnId,
    type,
    runId: 'run-1',
    runName: 'analyst',
    data,
    at: '2026-09-02T00:00:00Z',
  }
}

/**
 * The transcript, as events arrive.
 *
 * Driven with no DOM, because these are the rules that matter and none of them is about pixels:
 * what a duplicate does, what a delta does, and whether the stream or the accumulated text wins
 * when a turn ends.
 */
describe('applied', () => {
  it('builds a turn from the stream, a fragment at a time', () => {
    let transcript = emptyTranscript
    for (const next of [
      event('TURN_STARTED', { userText: 'how many are open?', ordinal: 1 }),
      event('TEXT_DELTA', { text: 'Twelve ' }),
      event('TEXT_DELTA', { text: 'are open.' }),
      event('TURN_FINISHED', { state: 'COMPLETED', inputTokens: 120, outputTokens: 40 }),
    ]) {
      transcript = applied(transcript, next)
    }

    expect(transcript.turns).toHaveLength(1)
    expect(transcript.turns[0]?.userText).toBe('how many are open?')
    expect(transcript.turns[0]?.answer).toBe('Twelve are open.')
    expect(transcript.turns[0]?.state).toBe('COMPLETED')
    expect(transcript.turns[0]?.inputTokens).toBe(120)
    expect(isWorking(transcript)).toBe(false)
  })

  it('ignores an event it has already applied', () => {
    // ChatEvents.subscribe replays the buffer and registers the subscriber under one lock, and
    // says in its own comment that an event may legitimately arrive twice — once from the
    // buffer and once live — because being wrong in that direction is better than losing one.
    // This is the side that makes that trade safe: applied twice, the answer would read
    // "Twelve Twelve are open."
    const started = event('TURN_STARTED', { userText: 'go', ordinal: 1 })
    const delta = event('TEXT_DELTA', { text: 'Twelve ' })

    let transcript = applied(applied(emptyTranscript, started), delta)
    transcript = applied(transcript, delta)
    transcript = applied(transcript, started)

    expect(transcript.turns).toHaveLength(1)
    expect(transcript.turns[0]?.answer).toBe('Twelve ')
  })

  it('keeps a cancelled turn as the person`s decision rather than a failure', () => {
    let transcript = applied(emptyTranscript, event('TURN_STARTED', { userText: 'go' }))
    transcript = applied(transcript, event('TEXT_DELTA', { text: 'half an ans' }))
    transcript = applied(
      transcript,
      event('TURN_FINISHED', { state: 'CANCELLED', answer: '', detail: 'You stopped this.' }),
    )

    expect(transcript.turns[0]?.state).toBe('CANCELLED')
    expect(transcript.turns[0]?.detail).toBe('You stopped this.')
  })

  it('takes the answer from the stream when nothing was ever streamed', () => {
    // A run whose text never came through as deltas — a non-streaming adapter, or a resumed
    // turn whose deltas were published before this tab connected. The finish carries the whole
    // answer and it has to win, or the bubble stays empty on a turn that completed.
    //
    // The first spelling of this test used an EMPTY answer on the finish, so `answer` was ''
    // whether the stream won or the accumulated text did. It passed with the rule removed.
    let transcript = applied(emptyTranscript, event('TURN_STARTED', { userText: 'go' }))
    transcript = applied(
      transcript,
      event('TURN_FINISHED', { state: 'COMPLETED', answer: 'Twelve are open.' }),
    )

    expect(transcript.turns[0]?.answer).toBe('Twelve are open.')
    expect(transcript.turns[0]?.state).toBe('COMPLETED')
  })

  it('keeps what was streamed when the finish carries no answer of its own', () => {
    // The other half, and the reason the rule is `?? then ||` rather than a plain take: a
    // cancelled turn finishes with an empty answer, and the half-sentence the person watched
    // arrive is what they saw.
    let transcript = applied(emptyTranscript, event('TURN_STARTED', { userText: 'go' }))
    transcript = applied(transcript, event('TEXT_DELTA', { text: 'half an ans' }))
    transcript = applied(transcript, event('TURN_FINISHED', { state: 'COMPLETED', answer: '' }))

    expect(transcript.turns[0]?.answer).toBe('half an ans')
  })

  it('fills in turns that the conversation itself returned short', () => {
    // loaded() normalises too, not just the optimistic insert. A GET from an older server — or
    // one that grows a field this page has not learned about — must not reach a component that
    // is about to read `.length` off it.
    const transcript = loaded(
      [{ id: 'turn-1' } as unknown as Parameters<typeof loaded>[0][number]],
      3,
      false,
    )

    expect(transcript.turns[0]?.answer).toBe('')
    expect(transcript.turns[0]?.views).toEqual([])
    expect(transcript.turns[0]?.state).toBe('RUNNING')
  })

  it('records a settled tool call and not the proposal', () => {
    // TOOL_STARTED has no settled disposition and the model's proposal is what a narrowing
    // gate overrules. The trace row is the finished one.
    let transcript = applied(emptyTranscript, event('TURN_STARTED', { userText: 'go' }))
    transcript = applied(transcript, event('TOOL_STARTED', { tool: 'files.read' }))
    transcript = applied(
      transcript,
      event('TOOL_FINISHED', { name: 'files.read', tool: 'files.read', millis: 31 }),
    )

    expect(transcript.turns[0]?.steps).toHaveLength(1)
    expect(transcript.turns[0]?.steps[0]?.name).toBe('files.read')
  })

  it('collects the views a tool produced', () => {
    let transcript = applied(emptyTranscript, event('TURN_STARTED', { userText: 'go' }))
    transcript = applied(
      transcript,
      event('VIEW', { kind: 'table', data: { columns: [], rows: [] } }),
    )

    expect(transcript.turns[0]?.views).toHaveLength(1)
    expect(transcript.turns[0]?.views[0]?.kind).toBe('table')
  })

  it('says a conversation is working while its last turn runs', () => {
    const running = loaded(
      [
        {
          id: 'turn-1', ordinal: 1, userText: 'go', attachments: [], answer: '',
          state: 'RUNNING', detail: '', views: [], steps: [], inputTokens: 0,
          outputTokens: 0, startedAt: '2026-09-02T00:00:00Z', endedAt: null,
        },
      ],
      7,
      true,
    )

    expect(isWorking(running)).toBe(true)
    expect(lastUserText(running)).toBe('go')
  })

  it('fills in a turn that arrived missing fields', () => {
    // A payload short one key used to take the whole console down: `turn.answer.length` on an
    // undefined answer throws inside render, React unmounts the tree, and the person gets a
    // white page. Coerced here so nothing downstream has to check.
    const sparse = asTurn({ id: 'turn-1' })

    expect(sparse.answer).toBe('')
    expect(sparse.views).toEqual([])
    expect(sparse.steps).toEqual([])
    expect(sparse.state).toBe('RUNNING')
    expect(sparse.endedAt).toBeNull()
  })
})
