import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from './api'
import { applied, asTurn, emptyTranscript, isWorking, loaded, withLeftOut, type Transcript } from './transcript'
import type { Attachment, ChatEvent, PendingDecision } from './types'

/**
 * How long to wait before reopening a stream that failed.
 *
 * Long enough that a server which is down is not hammered by every open tab, short enough that
 * a person watching a run does not notice. Exported so a test can wait for it rather than guess.
 */
export const RECONNECT_AFTER_MS = 1000

/**
 * One conversation, live.
 *
 * Loads the transcript, then keeps it current from the event stream. The two are ordered
 * deliberately: the GET reports the sequence it was consistent as of, and the stream subscribes
 * from exactly that point — so there is no window in which an event is in neither, which is the
 * hole the polling consoles papered over by re-reading everything on every tick.
 */
export interface Conversation {
  transcript: Transcript
  working: boolean
  /** The tool the agent is running right now, if any. Live only; never stored. */
  runningTool: string | null
  /** A stated problem, written for a person — a 409 from the runtime, or a dead stream. */
  problem: string | null
  /** True when the stream dropped us for falling behind and has not caught up. */
  reconnecting: boolean
  /** What a person owes this conversation an answer to, oldest first. */
  pending: PendingDecision[]
  /** Every file handed to this conversation, so a turn can name the ones it carried. */
  attachments: Attachment[]
  /** What this conversation has spent so far. */
  spent: { tokens: number; costUsd?: number }
  /** The server's own title, which it sets from the first thing said. */
  title: string
  /** Says something; with `input`, the agent's form filled in, which the server makes into the request. */
  say: (text: string, attachments?: string[], input?: Record<string, unknown>, agent?: string) => Promise<void>
  stop: () => Promise<void>
  /** Leaves a finished turn out of what the agents read next, or puts it back. */
  leaveOut: (turnId: string, left: boolean) => Promise<void>
  decide: (
    id: string,
    verdict: 'approve' | 'reject' | 'edit' | 'answer',
    body?: Record<string, unknown>,
  ) => Promise<void>
}

export function useConversation(conversationId: string | null): Conversation {
  const [transcript, setTranscript] = useState<Transcript>(emptyTranscript)
  const [runningTool, setRunningTool] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [reconnecting, setReconnecting] = useState(false)
  const [pending, setPending] = useState<PendingDecision[]>([])
  const [attachments, setAttachments] = useState<Attachment[]>([])
  // The title AND whose it is. A bare string is a value captured at render time, and the reset
  // below only takes effect on the next one — so a consumer's effect, running in the same
  // commit as the switch, read the title of the conversation just left and applied it to the
  // one just opened. Measured: a brand-new conversation was stamped with the previous one's
  // name. Carrying the id makes that unrepresentable rather than merely unlikely.
  const [named, setNamed] = useState<{ id: string; title: string }>({ id: '', title: '' })

  // The sequence the stream should resume from, kept in a ref because the EventSource is set up
  // once and must not be torn down every time a delta lands.
  const cursor = useRef(0)

  useEffect(() => {
    // Everything about the previous conversation goes before anything about this one arrives.
    //
    // Without this, switching threads leaves the old transcript, the old pending decisions, the
    // old attachments and the old title on screen until the new load resolves — which is not
    // merely a flicker. Measured: opening a brand-new conversation stamped it with the previous
    // one's title, because the effect that learns a title from the server read a value that
    // belonged to the thread just left. A person would also, for a moment, be offered an
    // approval card belonging to a conversation they are no longer looking at.
    setTranscript(emptyTranscript)
    setPending([])
    setAttachments([])
    setNamed({ id: '', title: '' })
    setRunningTool(null)
    setProblem(null)
    cursor.current = 0
    if (!conversationId) {
      return
    }
    let live = true
    let source: EventSource | null = null

    const apply = (event: ChatEvent) => {
      if (!live) {
        return
      }
      cursor.current = Math.max(cursor.current, event.sequence)
      if (event.type === 'TOOL_STARTED') {
        setRunningTool(String(event.data.tool ?? ''))
      }
      if (event.type === 'TOOL_FINISHED' || event.type === 'TURN_FINISHED') {
        setRunningTool(null)
      }
      if (event.type === 'ERROR') {
        setProblem(String(event.data.message ?? 'Something went wrong.'))
      }
      if (event.type === 'APPROVAL_REQUESTED') {
        setPending((current) =>
          current.some((one) => one.id === String(event.data.approvalId))
            ? current
            : [...current, asPending(event)],
        )
      }
      if (event.type === 'APPROVAL_DECIDED') {
        // Settled here rather than only where the button was pressed, which is what makes a
        // decision taken in one tab close the card in every other one — and what keeps a
        // reloaded page from offering a question somebody already answered.
        setPending((current) =>
          current.filter((one) => one.id !== String(event.data.approvalId)),
        )
      }
      setTranscript((current) => applied(current, event))
    }

    let retry: ReturnType<typeof setTimeout> | undefined

    const listen = () => {
      source?.close()
      source = new EventSource(
        `/api/conversations/${encodeURIComponent(conversationId)}/events?after=${cursor.current}`,
      )
      source.onmessage = (message) => {
        try {
          apply(JSON.parse(message.data) as ChatEvent)
          setReconnecting(false)
        } catch {
          // A frame we cannot read is not a reason to tear down a working stream.
        }
      }
      // The server says so explicitly when it abandons a subscriber for falling behind, rather
      // than leaving a silently truncated stream. Reconnecting from the cursor replays what was
      // missed, which is why being dropped is an inconvenience and not a loss.
      source.addEventListener('dropped', () => {
        setReconnecting(true)
        reconnect()
      })
      source.onerror = () => {
        setReconnecting(true)
        // Reconnected here rather than left to the browser, and that is the whole point. An
        // EventSource retries by itself — to the URL it was constructed with, which carries the
        // cursor as it was when the stream opened. After ten minutes of a live conversation
        // that cursor is far behind, so every automatic retry asks the server to replay from
        // where the tab STARTED. The replay buffer is bounded, so past its size the browser's
        // own recovery quietly stops recovering anything.
        reconnect()
      }
    }

    /** Reopens from the current cursor, after a pause so a dead server is not hammered. */
    const reconnect = () => {
      source?.close()
      clearTimeout(retry)
      retry = setTimeout(() => {
        if (live) {
          listen()
        }
      }, RECONNECT_AFTER_MS)
    }

    api
      .conversation(conversationId)
      .then(async (detail) => {
        if (!live) {
          return
        }
        cursor.current = detail.lastSequence
        setTranscript(loaded(detail.turns, detail.lastSequence, detail.working))
        setAttachments(Array.isArray(detail.attachments) ? detail.attachments : [])
        setNamed({ id: conversationId, title: detail.title ?? '' })
        // Read before the stream opens, because a question raised before this tab existed is
        // not on the stream at all — it is waiting, and a reloaded page that did not ask would
        // show a running turn with nothing to do about it.
        const outstanding = await api.pending().catch(() => [])
        if (!live) {
          return
        }
        // Guarded, because everything after this depends on the stream opening. A server that
        // answered this with something unexpected used to throw inside the promise, skip
        // `listen()`, and leave a console that looked connected and received nothing —
        // measured, against a stub that returned the wrong shape.
        setPending(
          Array.isArray(outstanding)
            ? outstanding.filter((one) => one?.conversationId === conversationId)
            : [],
        )
        listen()
      })
      .catch((error: unknown) => {
        if (live) {
          setProblem(error instanceof ApiError ? error.message : 'That conversation would not load.')
        }
      })

    return () => {
      live = false
      clearTimeout(retry)
      source?.close()
    }
  }, [conversationId])

  const say = useCallback(
    async (text: string, attachments: string[] = [], input?: Record<string, unknown>, agent?: string) => {
      // A message carrying files and no words is a real message — "here, look at this". So is a
      // filled-in form.
      if (!conversationId || (!text.trim() && attachments.length === 0 && !input)) {
        return
      }
      setProblem(null)
      try {
        // Shown at once, from the POST's own answer, rather than waiting for TURN_STARTED to
        // come back round the event stream. That round trip is a whole request in which a
        // person who has just pressed Enter sees nothing happen — which reads as the console
        // having missed the keystroke, and is how you get the same message sent twice.
        //
        // Safe against the duplicate this invites: the reducer's TURN_STARTED arm returns the
        // transcript unchanged when a turn of that id is already there.
        const started = await api.say(conversationId, text, attachments, input, agent)
        setTranscript((current) =>
          current.turns.some((turn) => turn.id === started.id)
            ? current
            : { ...current, turns: [...current.turns, asTurn(started)], working: true },
        )
      } catch (error: unknown) {
        // A stated refusal is the console explaining itself and is shown verbatim. Anything
        // else is this console failing, and says so in its own words rather than the server's.
        setProblem(
          error instanceof ApiError && error.isStated
            ? error.message
            : 'That message could not be sent.',
        )
      }
    },
    [conversationId],
  )

  const decide = useCallback(
    async (
      id: string,
      verdict: 'approve' | 'reject' | 'edit' | 'answer',
      body: Record<string, unknown> = {},
    ) => {
      // Removed at once rather than on the event coming back. The run continues the moment the
      // server has it, and a card that lingered would invite a second press — which the runtime
      // ignores, but which reads as the console having missed the first.
      setPending((current) => current.filter((one) => one.id !== id))
      try {
        await api.decide(id, verdict, body)
      } catch (error: unknown) {
        setProblem(
          error instanceof ApiError && error.isStated ? error.message : 'That decision failed.',
        )
      }
    },
    [],
  )

  const leaveOut = useCallback(
    async (turnId: string, left: boolean) => {
      if (!conversationId) {
        return
      }
      // Shown at once, and taken back if the server did not keep it.
      setTranscript((current) => withLeftOut(current, turnId, left))
      try {
        await api.leaveOut(conversationId, turnId, left)
      } catch (error: unknown) {
        setTranscript((current) => withLeftOut(current, turnId, !left))
        setProblem(error instanceof ApiError && error.isStated ? error.message : 'That could not be changed.')
      }
    },
    [conversationId],
  )

  const stop = useCallback(async () => {
    if (!conversationId) {
      return
    }
    try {
      await api.cancel(conversationId)
    } catch {
      // Stopping something that already stopped is not worth telling anybody about.
    }
  }, [conversationId])

  return {
    transcript,
    working: isWorking(transcript),
    runningTool,
    problem,
    reconnecting,
    pending,
    attachments,
    title: named.id === conversationId ? named.title : '',
    // Summed from the turns rather than tracked separately: two counters over one fact drift,
    // and the one that is wrong is always the one nobody is looking at.
    spent: {
      tokens: transcript.turns.reduce(
        (total, turn) => total + turn.inputTokens + turn.outputTokens,
        0,
      ),
      costUsd: transcript.turns.some((turn) => typeof turn.costUsd === 'number')
        ? transcript.turns.reduce((total, turn) => total + (turn.costUsd ?? 0), 0)
        : undefined,
    },
    say,
    stop,
    leaveOut,
    decide,
  }
}

/** An APPROVAL_REQUESTED event, as the thing a card draws. */
function asPending(event: ChatEvent): PendingDecision {
  const data = event.data
  return {
    id: String(data.approvalId ?? ''),
    conversationId: event.conversationId,
    turnId: event.turnId,
    kind: data.kind === 'QUESTION' ? 'QUESTION' : 'ACTION',
    tool: String(data.tool ?? ''),
    arguments:
      data.arguments && typeof data.arguments === 'object'
        ? (data.arguments as Record<string, unknown>)
        : {},
    capability: String(data.capability ?? ''),
    reason: String(data.reason ?? ''),
    effect: String(data.effect ?? ''),
    reversible: data.reversible !== false,
    question: String(data.question ?? ''),
    askedAt: event.at,
  }
}
