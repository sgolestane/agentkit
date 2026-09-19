import type { ChatEvent, Step, Turn, View } from './types'

/**
 * The transcript, as events arrive.
 *
 * <h4>Why a reducer and not state scattered through components</h4>
 *
 * A turn is written to by six different event types while it runs, from a stream that can
 * reconnect and replay. Spreading that across components means every one of them has to know
 * what a duplicate looks like, and the bug that produces — a delta applied twice after a
 * reconnect, so the answer reads "TwelveTwelve are open" — is invisible until the network is
 * bad. One function, one place to get it right, and a test that can drive it with no DOM.
 */

export interface Transcript {
  /** Every turn, oldest first. */
  turns: Turn[]
  /** The highest event sequence applied, which is where a reconnect resumes from. */
  lastSequence: number
  /** True while a turn of this conversation is running. */
  working: boolean
}

export const emptyTranscript: Transcript = { turns: [], lastSequence: 0, working: false }

/**
 * A turn with every field present, whatever arrived.
 *
 * <h4>Coerced at the boundary, not defended at every use</h4>
 *
 * A payload missing one field used to take the whole console down with it: `turn.answer.length`
 * on an undefined `answer` throws inside render, React unmounts the tree, and the person gets a
 * white page. Measured — the POST that starts a turn returns a Turn, and a stub that omitted
 * `answer` crashed `Transcript`.
 *
 * The alternative is `?.` on every read in every component, which is the same check written
 * fifteen times and forgotten on the sixteenth. This is the shape the Java side already uses:
 * one compact constructor that coerces, so everything downstream can trust what it holds.
 */
export function asTurn(raw: Partial<Turn> & { id: string }): Turn {
  return {
    id: raw.id,
    ordinal: raw.ordinal ?? 0,
    userText: raw.userText ?? '',
    attachments: raw.attachments ?? [],
    answer: raw.answer ?? '',
    state: raw.state ?? 'RUNNING',
    detail: raw.detail ?? '',
    views: raw.views ?? [],
    steps: raw.steps ?? [],
    inputTokens: raw.inputTokens ?? 0,
    outputTokens: raw.outputTokens ?? 0,
    startedAt: raw.startedAt ?? '',
    endedAt: raw.endedAt ?? null,
  }
}

/** The transcript a fresh GET of the conversation describes. */
export function loaded(turns: Turn[], lastSequence: number, working: boolean): Transcript {
  return { turns: turns.map(asTurn), lastSequence, working }
}

const RUNNING_TURN: Omit<Turn, 'id' | 'ordinal' | 'startedAt'> = {
  userText: '',
  attachments: [],
  answer: '',
  state: 'RUNNING',
  detail: '',
  views: [],
  steps: [],
  inputTokens: 0,
  outputTokens: 0,
  endedAt: null,
}

/**
 * The transcript after one event.
 *
 * Events older than what has already been applied are dropped. That is not belt-and-braces:
 * `ChatEvents.subscribe` registers the subscriber and replays the buffer under one lock, and
 * says in its own comment that an event may legitimately arrive twice — once from the buffer
 * and once live — because being wrong in that direction is better than losing one. This is the
 * side that makes that trade safe.
 */
export function applied(transcript: Transcript, event: ChatEvent): Transcript {
  if (event.sequence <= transcript.lastSequence) {
    return transcript
  }
  const next: Transcript = {
    turns: transcript.turns,
    lastSequence: event.sequence,
    working: transcript.working,
  }

  if (event.type === 'TURN_STARTED') {
    if (transcript.turns.some((turn) => turn.id === event.turnId)) {
      return { ...next, working: true }
    }
    return {
      ...next,
      working: true,
      turns: [
        ...transcript.turns,
        {
          ...RUNNING_TURN,
          id: event.turnId,
          ordinal: Number(event.data.ordinal ?? transcript.turns.length + 1),
          userText: String(event.data.userText ?? ''),
          attachments: Array.isArray(event.data.attachments)
            ? (event.data.attachments as string[])
            : [],
          startedAt: event.at,
        },
      ],
    }
  }

  return { ...next, turns: transcript.turns.map((turn) => change(turn, event)) }
}

function change(turn: Turn, event: ChatEvent): Turn {
  if (turn.id !== event.turnId) {
    return turn
  }
  switch (event.type) {
    case 'TURN_RUNNING':
      // A turn shown from the send's own answer arrives QUEUED; this is the stream saying its work
      // has begun. Without it the turn read "Waiting to start" until it ended, which a long turn —
      // a plan carried out step by step — made plain.
      return turn.state === 'QUEUED' ? { ...turn, state: 'RUNNING' } : turn

    case 'TEXT_DELTA':
      return { ...turn, answer: turn.answer + String(event.data.text ?? '') }

    case 'VIEW':
      return {
        ...turn,
        views: [...turn.views, { kind: String(event.data.kind), data: viewData(event) }],
      }

    case 'MODEL_CALL':
    case 'TOOL_FINISHED':
      return { ...turn, steps: [...turn.steps, stepOf(event)] }

    case 'TOOL_STARTED':
      // Not a step. It has no settled disposition yet and the TOOL_FINISHED row is the one
      // that is true — the proposal is what a narrowing gate overrules. What it is for is the
      // live line that says a tool is running, which `runningTool` reads off the stream
      // rather than off the transcript.
      return turn

    case 'TURN_FINISHED':
      return {
        ...turn,
        state: (event.data.state as Turn['state']) ?? 'COMPLETED',
        // The stream is authoritative about the answer: a turn that ends with text nobody
        // streamed — a refusal, a cancelled run — has an answer the deltas never carried.
        answer: String(event.data.answer ?? turn.answer) || turn.answer,
        detail: String(event.data.detail ?? ''),
        inputTokens: Number(event.data.inputTokens ?? 0),
        outputTokens: Number(event.data.outputTokens ?? 0),
        endedAt: event.at,
      }

    case 'ERROR':
      return { ...turn, detail: turn.detail || String(event.data.message ?? '') }

    default:
      return turn
  }
}

function viewData(event: ChatEvent): Record<string, unknown> {
  const data = event.data.data
  return data && typeof data === 'object' ? (data as Record<string, unknown>) : {}
}

function stepOf(event: ChatEvent): Step {
  return {
    sequence: event.sequence,
    kind: event.type === 'MODEL_CALL' ? 'MODEL_CALL' : 'TOOL_CALL',
    name: String(event.data.name ?? event.data.tool ?? ''),
    detail: event.data,
    millis: Number(event.data.millis ?? 0),
    failed: event.data.isError === true,
    at: event.at,
  }
}

/** Whether the transcript still has a turn in flight, which is what Stop acts on. */
export function isWorking(transcript: Transcript): boolean {
  const last = transcript.turns[transcript.turns.length - 1]
  return last ? last.state === 'RUNNING' : transcript.working
}

/** The last thing the person said, for regenerate and edit-and-resend. */
export function lastUserText(transcript: Transcript): string {
  for (let i = transcript.turns.length - 1; i >= 0; i--) {
    const text = transcript.turns[i]?.userText
    if (text) {
      return text
    }
  }
  return ''
}

export type { View }
