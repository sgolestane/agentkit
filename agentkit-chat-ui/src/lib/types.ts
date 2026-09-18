/**
 * The wire, as the Java side writes it.
 *
 * Hand-written and *checked* rather than generated. Generating them would mean a code
 * generator in the Maven build whose output has to be committed or regenerated, and for a
 * surface this small that is more machinery than the drift it prevents. What stops the drift
 * instead is `TheWireTypesMatchTheJavaOnesTest` on the Java side: it reads this file and fails
 * if a `View` kind exists there and not here, or here and not there.
 *
 * So: adding a kind means touching two files, and forgetting the second one is a red test
 * rather than a widget that silently never renders.
 */

/** Every built-in `View` kind. Must match `View`'s factories, one for one. */
export const VIEW_KINDS = [
  'markdown',
  'table',
  'chart',
  'scatter',
  'stat',
  'cards',
  'diff',
  'file',
  'timeline',
] as const

export type ViewKind = (typeof VIEW_KINDS)[number]

/**
 * Something a tool wants a person to look at.
 *
 * `kind` is deliberately `string` and not `ViewKind`: the Java side's `View.of` is open, so a
 * deployment can agree a kind between a tool and a renderer without changing the framework.
 * A renderer that does not know a kind shows the raw data rather than nothing.
 */
export interface View {
  kind: string
  data: Record<string, unknown>
}

/** Every event type the chat runtime publishes. Must match `ChatEvent.Type`. */
export const EVENT_TYPES = [
  'TURN_STARTED',
  'TURN_RUNNING',
  'TEXT_DELTA',
  'MODEL_CALL',
  'TOOL_STARTED',
  'TOOL_FINISHED',
  'VIEW',
  'APPROVAL_REQUESTED',
  'APPROVAL_DECIDED',
  'TURN_FINISHED',
  'ERROR',
] as const

export type EventType = (typeof EVENT_TYPES)[number]

export interface ChatEvent {
  sequence: number
  conversationId: string
  turnId: string
  type: EventType
  runId: string
  runName: string
  data: Record<string, unknown>
  at: string
}

/** Every terminal state a turn can reach, plus the one it starts in. Must match `Turn.State`. */
export const TURN_STATES = [
  'QUEUED',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'WAITING_FOR_HUMAN',
  'CANCELLED',
] as const

export type TurnState = (typeof TURN_STATES)[number]

/** What a step is. Must match `Step.Kind`. */
export const STEP_KINDS = [
  'MODEL_CALL',
  'TOOL_CALL',
  'VIEW',
  'APPROVAL_REQUESTED',
  'NOTE',
] as const

export type StepKind = (typeof STEP_KINDS)[number]

export interface Step {
  sequence: number
  kind: StepKind
  name: string
  detail: Record<string, unknown>
  millis: number
  failed: boolean
  at: string
}

export interface Turn {
  id: string
  ordinal: number
  userText: string
  attachments: string[]
  answer: string
  state: TurnState
  detail: string
  views: View[]
  steps: Step[]
  inputTokens: number
  outputTokens: number
  /** What this turn cost, when the deployment said what its model charges. */
  costUsd?: number
  startedAt: string
  endedAt: string | null
}

export interface Conversation {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  /** The agent, at a version, this conversation is with; absent in a single-agent console. */
  agent?: { id: string; version: string }
}

/**
 * An agent this person may start a conversation with, in a console that offers more than one.
 * `unavailable` says why it cannot answer right now, when it cannot.
 */
export interface AgentInfo {
  id: string
  name: string
  description: string
  unavailable?: string
  /** The form that starts its task, when it has one. */
  input?: InputSchema
}

/** One field of a task's form: flat, as the Java side's `TaskInput` allows. */
export interface InputField {
  type: 'string' | 'integer' | 'number' | 'boolean'
  title?: string
  description?: string
  enum?: string[]
  format?: 'date' | 'email'
}

/** A task's form, as a JSON Schema object of flat fields. */
export interface InputSchema {
  type: 'object'
  properties: Record<string, InputField>
  required?: string[]
}

export interface ConversationDetail extends Conversation {
  turns: Turn[]
  attachments: Attachment[]
  working: boolean
  lastSequence: number
  inputTokens?: number
  outputTokens?: number
  costUsd?: number
}

export interface Attachment {
  id: string
  name: string
  mediaType: string
  bytes: number
  uploadedAt: string
}

export interface PendingDecision {
  id: string
  conversationId: string
  turnId: string
  /** An ACTION is a call a gate stopped. A QUESTION is the agent asking the person something. */
  kind: 'ACTION' | 'QUESTION'
  tool: string
  arguments: Record<string, unknown>
  /** The family a standing decision would apply to; empty when the deployment names none. */
  capability: string
  reason: string
  effect: string
  reversible: boolean
  question: string
  askedAt: string
}

/**
 * What the console says about itself.
 *
 * `problems` is the part that matters: a deployment with no model configured boots and puts
 * its missing environment variables here, and the page shows them. Everything else on this is
 * whatever the application chose to describe.
 */
export interface Overview {
  build: string
  tenant: string
  maxUploadBytes: number
  ready?: boolean
  model?: string
  problems?: string[]
  /** The agents on offer; absent in a single-agent console. */
  agents?: AgentInfo[]
  [key: string]: unknown
}
