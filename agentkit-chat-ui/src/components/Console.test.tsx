import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Console } from './Console'
import { RECONNECT_AFTER_MS } from '../lib/useConversation'

/**
 * The shell, against a stubbed server.
 *
 * The two behaviours worth pinning here are the ones a person notices immediately: a console
 * that cannot work says why instead of silently swallowing what you type, and Stop is reachable
 * whenever there is something to stop.
 */

class FakeEventSource {
  static last: FakeEventSource | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: (() => void) | null = null
  private listeners = new Map<string, () => void>()

  constructor(readonly url: string) {
    FakeEventSource.last = this
  }

  addEventListener(type: string, listener: () => void) {
    this.listeners.set(type, listener)
  }

  close() {}

  /** Delivers one event, the way the server would. */
  emit(payload: Record<string, unknown>) {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent)
  }
}

function respond(body: unknown, status = 200) {
  return Promise.resolve({
    ok: status < 400,
    status,
    text: () => Promise.resolve(JSON.stringify(body)),
  } as Response)
}

describe('Console', () => {
  let sent: string[] = []
  let cancelled = 0
  let decided: string[] = []

  beforeEach(() => {
    sent = []
    cancelled = 0
    decided = []
    outstanding = []
    // Reset, and this is not tidiness. `last` is static, so without it a test that waits for a
    // stream to appear matches the PREVIOUS test's — one belonging to an unmounted component,
    // whose handlers no-op. That made the reconnect test pass on its own and fail in company,
    // which reads exactly like a bug in the code it is testing.
    FakeEventSource.last = null
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  let outstanding: Record<string, unknown>[] = []

  function server(overview: Record<string, unknown>) {
    vi.stubGlobal('fetch', (input: string, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/overview')) return respond(overview)
      if (url.endsWith('/api/conversations') && init?.method === 'POST')
        return respond({ id: 'conv-1', title: '', createdAt: '', updatedAt: '' })
      if (url.endsWith('/api/conversations'))
        return respond([{ id: 'conv-1', title: 'the queue', createdAt: '', updatedAt: '' }])
      if (url.endsWith('/api/approvals')) return respond(outstanding)
      if (url.includes('/approvals/')) {
        decided.push(url.split('/approvals/')[1] + ' ' + String(init?.body))
        return respond({ decided: true })
      }
      if (url.includes('/cancel')) {
        cancelled += 1
        return respond({ stopped: true })
      }
      if (url.includes('/messages')) {
        sent.push(String(JSON.parse(String(init?.body)).text))
        // Deliberately partial. A real server sends the whole Turn, but an older one — or one
        // that grows a field this page has not learned about — does not, and a console that
        // white-screens on a missing key is a console one deploy away from being unusable.
        return respond({ id: 'turn-1', state: 'RUNNING', userText: 'how many are open?' })
      }
      if (url.includes('/api/conversations/conv-1'))
        return respond({
          id: 'conv-1', title: 'the queue', createdAt: '', updatedAt: '',
          turns: [], attachments: [], working: false, lastSequence: 0,
        })
      return respond({ error: 'unexpected ' + url }, 404)
    })
  }

  it('says what is missing rather than accepting a message it cannot answer', async () => {
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: false,
      problems: ['No model is configured. Set CHAT_LLM, then restart.'] })

    render(<Console />)

    expect(await screen.findByRole('alert')).toHaveTextContent('CHAT_LLM')
    expect(screen.getByLabelText('Message')).toBeDisabled()
  })

  it('sends what a person types', async () => {
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.type(await screen.findByLabelText('Message'), 'how many are open?{Enter}')

    await waitFor(() => expect(sent).toEqual(['how many are open?']))
  })

  it('offers Stop only while something is running, and stopping reaches the server', async () => {
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    render(<Console />)
    await screen.findByText('the queue')
    expect(screen.queryByText('Stop')).not.toBeInTheDocument()

    // A turn begins on the stream, which is how the console finds out about work it did not
    // start itself — another tab, or a run resumed after a reload.
    await waitFor(() => expect(FakeEventSource.last).not.toBeNull())
    FakeEventSource.last?.emit({
      sequence: 1, conversationId: 'conv-1', turnId: 'turn-1', type: 'TURN_STARTED',
      runId: '', runName: '', data: { userText: 'go', ordinal: 1 }, at: '2026-09-02T00:00:00Z',
    })

    const stop = await screen.findByText('Stop')
    await userEvent.click(stop)

    expect(cancelled).toBe(1)
  })

  it('draws an answer as it streams in', async () => {
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    render(<Console />)
    await screen.findByText('the queue')
    await waitFor(() => expect(FakeEventSource.last).not.toBeNull())
    const stream = FakeEventSource.last!

    stream.emit({ sequence: 1, conversationId: 'conv-1', turnId: 'turn-1', type: 'TURN_STARTED',
      runId: '', runName: '', data: { userText: 'go', ordinal: 1 }, at: '2026-09-02T00:00:00Z' })
    stream.emit({ sequence: 2, conversationId: 'conv-1', turnId: 'turn-1', type: 'TEXT_DELTA',
      runId: '', runName: '', data: { text: 'Twelve ' }, at: '2026-09-02T00:00:00Z' })
    stream.emit({ sequence: 3, conversationId: 'conv-1', turnId: 'turn-1', type: 'TEXT_DELTA',
      runId: '', runName: '', data: { text: 'are open.' }, at: '2026-09-02T00:00:00Z' })

    expect(await screen.findByTestId('answer')).toHaveTextContent('Twelve are open.')
  })

  it('resumes the stream from where the transcript ended', async () => {
    // No gap between what the GET reported and what the stream carries: the subscribe cursor is
    // the sequence the transcript was consistent as of.
    vi.stubGlobal('fetch', (input: string) => {
      const url = String(input)
      if (url.endsWith('/api/overview'))
        return respond({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
      if (url.endsWith('/api/conversations'))
        return respond([{ id: 'conv-1', title: 'the queue', createdAt: '', updatedAt: '' }])
      if (url.endsWith('/api/approvals')) return respond([])
      return respond({
        id: 'conv-1', title: 'the queue', createdAt: '', updatedAt: '',
        turns: [], attachments: [], working: false, lastSequence: 41,
      })
    })

    render(<Console />)
    await screen.findByText('the queue')

    await waitFor(() => expect(FakeEventSource.last?.url).toContain('after=41'))
  })

  it('shows what a person sent before the stream says anything', async () => {
    // The POST answers with the turn; waiting for TURN_STARTED to come back round the stream is
    // a whole round trip in which someone who just pressed Enter sees nothing happen — which
    // reads as a missed keystroke and is how the same message gets sent twice.
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.type(await screen.findByLabelText('Message'), 'how many are open?{Enter}')

    // Present without any event having been emitted on the fake stream.
    expect(await screen.findByText('how many are open?')).toBeInTheDocument()
    expect(await screen.findByText('Stop')).toBeInTheDocument()
  })

  it('reopens a dropped stream from where it got to, not from where it started', async () => {
    // An EventSource retries by itself, to the URL it was built with — which carries the cursor
    // as it was when the tab opened. After a long conversation that asks the server to replay
    // from the beginning every time, and past the replay buffer's size it recovers nothing.
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    render(<Console />)
    {
      // Waiting for `last` alone is not enough: it is assigned in the constructor, and
      // `onmessage` one statement later. Emitting into the gap is an optional call on null,
      // which does nothing at all and looks exactly like a cursor that failed to advance.
      await waitFor(() => expect(FakeEventSource.last?.onmessage).toBeTruthy())
      const first = FakeEventSource.last!
      expect(first.url).toContain('after=0')

      first.emit({ sequence: 41, conversationId: 'conv-1', turnId: 'turn-1', type: 'TURN_STARTED',
        runId: '', runName: '', data: { userText: 'go', ordinal: 1 }, at: '2026-09-02T00:00:00Z' })
      // Real timers, deliberately. Faking them made this pass on its own and fail when the
      // file ran in order — a test that is right in isolation and wrong in company is worse
      // than one that takes an extra second, because the failure looks like the code.
      await act(async () => {
        first.onerror?.()
      })

      await waitFor(() => expect(FakeEventSource.last?.url).toContain('after=41'), {
        timeout: RECONNECT_AFTER_MS * 3,
      })
    }
  })

  const ASKED = {
    id: 'ask-1',
    conversationId: 'conv-1',
    turnId: 'turn-1',
    kind: 'ACTION',
    tool: 'identity.reset',
    arguments: { user: 'alice' },
    capability: 'identity',
    reason: 'Resetting a password locks the person out.',
    effect: "alice's password is replaced",
    reversible: false,
    question: '',
    askedAt: '2026-09-02T00:00:00Z',
  }

  it('raises a decision in the transcript when the run stops to ask', async () => {
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    render(<Console />)
    await screen.findByText('the queue')
    await waitFor(() => expect(FakeEventSource.last?.onmessage).toBeTruthy())
    const stream = FakeEventSource.last!

    stream.emit({ sequence: 1, conversationId: 'conv-1', turnId: 'turn-1', type: 'TURN_STARTED',
      runId: '', runName: '', data: { userText: 'reset it', ordinal: 1 }, at: '2026-09-02T00:00:00Z' })
    stream.emit({ sequence: 2, conversationId: 'conv-1', turnId: 'turn-1',
      type: 'APPROVAL_REQUESTED', runId: '', runName: '',
      data: { approvalId: 'ask-1', kind: 'ACTION', tool: 'identity.reset',
        arguments: { user: 'alice' }, capability: 'identity',
        reason: 'Resetting a password locks the person out.', effect: 'replaced',
        reversible: false, question: '' },
      at: '2026-09-02T00:00:00Z' })

    expect(await screen.findByTestId('approval-card')).toBeInTheDocument()
    expect(screen.getByText('Approve identity.reset?')).toBeInTheDocument()
  })

  it('closes the card when somebody decides in another tab', async () => {
    // The event is what settles it, not the button press — otherwise two tabs each show a card
    // and the second person presses a button on a decision that was made minutes ago.
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    outstanding = [ASKED]
    render(<Console />)
    await screen.findByText('the queue')
    await waitFor(() => expect(FakeEventSource.last?.onmessage).toBeTruthy())
    // The turn the card hangs off has to exist for it to be drawn.
    FakeEventSource.last!.emit({ sequence: 1, conversationId: 'conv-1', turnId: 'turn-1',
      type: 'TURN_STARTED', runId: '', runName: '',
      data: { userText: 'reset it', ordinal: 1 }, at: '2026-09-02T00:00:00Z' })
    expect(await screen.findByTestId('approval-card')).toBeInTheDocument()

    FakeEventSource.last!.emit({ sequence: 2, conversationId: 'conv-1', turnId: 'turn-1',
      type: 'APPROVAL_DECIDED', runId: '', runName: '',
      data: { approvalId: 'ask-1', decision: 'APPROVE', by: 'someone else' },
      at: '2026-09-02T00:00:00Z' })

    await waitFor(() => expect(screen.queryByTestId('approval-card')).not.toBeInTheDocument())
  })

  it('finds a question that was raised before this tab existed', async () => {
    // It is not on the stream — it is waiting. A reloaded page that did not ask would show a
    // running turn with nothing to do about it.
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    outstanding = [ASKED]
    render(<Console />)
    await screen.findByText('the queue')
    await waitFor(() => expect(FakeEventSource.last?.onmessage).toBeTruthy())
    FakeEventSource.last!.emit({ sequence: 1, conversationId: 'conv-1', turnId: 'turn-1',
      type: 'TURN_STARTED', runId: '', runName: '',
      data: { userText: 'reset it', ordinal: 1 }, at: '2026-09-02T00:00:00Z' })

    expect(await screen.findByTestId('approval-card')).toBeInTheDocument()
  })

  it('sends the decision, with the note and the standing choice', async () => {
    server({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
    outstanding = [ASKED]
    render(<Console />)
    await screen.findByText('the queue')
    await waitFor(() => expect(FakeEventSource.last?.onmessage).toBeTruthy())
    FakeEventSource.last!.emit({ sequence: 1, conversationId: 'conv-1', turnId: 'turn-1',
      type: 'TURN_STARTED', runId: '', runName: '',
      data: { userText: 'reset it', ordinal: 1 }, at: '2026-09-02T00:00:00Z' })
    await screen.findByTestId('approval-card')

    await userEvent.type(screen.getByLabelText('Note'), 'she asked in the ticket')
    await userEvent.click(screen.getByLabelText(/Apply this to every/))
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    await waitFor(() => expect(decided).toHaveLength(1))
    expect(decided[0]).toContain('ask-1/approve')
    expect(decided[0]).toContain('she asked in the ticket')
    expect(decided[0]).toContain('"standing":true')
    // And the card goes at once: the run continues the moment the server has it, and a card
    // that lingered would invite a second press.
    await waitFor(() => expect(screen.queryByTestId('approval-card')).not.toBeInTheDocument())
  })
})
