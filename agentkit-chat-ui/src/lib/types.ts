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
  /**
   * In a conversation pinned to no agent, the agent this turn went to — chosen by the person or by the router; absent
   * when the router answered it itself, or when the conversation is with one agent.
   */
  agent?: { id: string; version: string }
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
  /** Whether the person may see the organization's admin view. */
  admin?: boolean
  /** Whether a conversation needs no agent chosen: each message goes to the one that handles it. */
  routing?: boolean
  [key: string]: unknown
}

// --- the admin view -------------------------------------------------------------------------

export interface AdminAgentSummary {
  id: string
  name: string
  description: string
  pattern: string
  audience: string[]
  evals: number
  unavailable?: string
}

export interface AdminOverview {
  org: string
  current: string
  admins: string[]
  versions: { version: string; current: boolean; agents: AdminAgentSummary[] }[]
  connectors: { name: string; reached: boolean; failure?: string; tools: number }[]
  /** Whether an admin can propose a change from here, where it goes, and if not, why. */
  proposals?: { enabled: boolean; where?: string; why?: string }
  /** How a message sent to no agent in particular finds one. */
  router?: { enabled: boolean; model: string; instructions: boolean; cases: number }
}

export interface AgentFile {
  path: string
  content: string
}

/** What became of a proposal: opened for review, or refused with every reason. */
export interface ProposalOutcome {
  opened: boolean
  branch?: string
  url?: string | null
  where?: string
  summary?: string
  problems?: string[]
}

export interface AdminTool {
  connector: string
  name: string
  description: string
  effect: string
  system: string
  confirmed: boolean
  bound: Record<string, string>
  sideEffects: string
  refusedInRehearsal: boolean
}

export interface AdminEvalCase {
  name: string
  as: string
  say?: string
  input?: Record<string, unknown>
  answers: string[]
  expect: string[]
}

export interface AdminAgent {
  id: string
  name: string
  description: string
  version: string
  pattern: string
  model: string
  audience: string[]
  limits: { maxSteps: number; maxTokens: number }
  unavailable?: string
  prompts: Record<string, string>
  tools: AdminTool[]
  input: InputSchema | null
  deferred?: { actor: string; subjects: Record<string, string> }
  mcpDirect: string[]
  evals: AdminEvalCase[]
  /** For a plan-execute agent started from its form: when a settled plan is reused, and for which tasks. */
  planReuse?: {
    after: number
    recheckEvery: number
    sameWhen: string[]
    /** Each kind of task planned at this version: what makes it that kind, its runs, and its plan if settled. */
    kinds: { task: string[]; runs: number; settled?: string[] }[]
  }
}

/** What some model calls spent: how many, their tokens, and their estimated cost in US dollars. */
export interface ModelSpend {
  calls: number
  inputTokens: number
  outputTokens: number
  usd: number
}

/** An organization's model account: whose, how many calls at once, its budgets and what it spent. */
export interface AdminUsage {
  /** 'host' for the host's account, or the provider of the organization's own. */
  account: string
  unavailable?: string
  concurrentCalls: { limit: number; running: number }
  budgets: { setBy: string; hostAccountOnly: boolean; caps: string[]; reached?: string }[]
  today: ModelSpend
  month: ModelSpend
  byAgent: (ModelSpend & { agent: string; model: string; account: 'host' | 'own' })[]
  /** Models used this month the host has no price for, whose cost is not counted. */
  unpriced: string[]
}

export interface AdminDeferredAction {
  id: string
  subject: string
  runAt: string
  when: string
  status: string
  scheduledBy: string
  scheduledAt: string | null
  goal: string
  outcome: string
  finishedAt: string | null
}

export interface RehearsalCall {
  tool: string
  arguments: Record<string, unknown>
  refused: boolean
  would: string | null
}

export interface RehearsalResult {
  agent: string
  case: string
  as: string
  passed: boolean
  state: string
  millis: number
  plan: string[]
  questions: string[]
  calls: RehearsalCall[]
  checks: { name: string; passed: boolean; detail: string }[]
  answer: string
}

export interface RehearsalReport {
  org: string
  version: string
  at: string
  receivedAt: string
  pullRequest?: string
  title?: string
  ref?: string
  held: number
  cases: number
  untested: string[]
  results: RehearsalResult[]
  /** The routing cases, when the repository has them: who should answer, and who the router chose. */
  routing?: { case: string; as: string; say: string; expected: string; got: string; why: string; passed: boolean }[]
}
