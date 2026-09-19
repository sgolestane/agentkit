import type {
  AdminAgent,
  AdminDeferredAction,
  AdminOverview,
  AdminRouting,
  AdminUsage,
  AgentFile,
  AgentInfo,
  Attachment,
  Conversation,
  ConversationDetail,
  Overview,
  PendingDecision,
  ProposalOutcome,
  RehearsalReport,
  Turn,
} from './types'

/**
 * Everything the page asks the Java side for.
 *
 * Same-origin and relative, always. The console is served by the process it talks to, so there
 * is no base URL to configure and nothing to get wrong in a deployment; in development Vite
 * proxies `/api` to the running server, which is what keeps the two indistinguishable from
 * here.
 */

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }

  /**
   * Whether this is the console explaining itself rather than failing.
   *
   * The Java side answers a stated limitation — no model configured, a conversation already
   * working — with a 409 carrying a sentence written for a person. Those are worth showing
   * verbatim; a 500 is not, and says so.
   */
  get isStated(): boolean {
    return this.status === 409
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  return at<T>(`/api${path}`, init)
}

/** {@link call}, at a path of the application's own beside the console's `/api`, such as the host's `/host`. */
async function at<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  const text = await response.text()
  const body: unknown = text ? JSON.parse(text) : null
  if (!response.ok) {
    // Nobody is signed in and the console says where to: go there rather than show a page that
    // can do nothing.
    if (response.status === 401 && body && typeof body === 'object' && 'signIn' in body) {
      globalThis.location?.assign(String((body as { signIn: unknown }).signIn))
    }
    const stated =
      body && typeof body === 'object' && 'error' in body
        ? String((body as { error: unknown }).error)
        : `The console answered ${response.status}.`
    throw new ApiError(response.status, stated)
  }
  return body as T
}

export const api = {
  overview: () => call<Overview>('/overview'),

  /** The organization's admin view: read-only, for the admins its org.yaml names. */
  admin: {
    overview: () => at<AdminOverview>('/host/admin'),
    agent: (id: string, version?: string) =>
      at<AdminAgent>(
        `/host/admin/agents/${encodeURIComponent(id)}${version ? `?version=${encodeURIComponent(version)}` : ''}`,
      ),
    deferred: () =>
      at<{ agents: { id: string; name: string; actions: AdminDeferredAction[] }[] }>('/host/admin/deferred'),
    rehearsals: () => at<{ reports: RehearsalReport[] }>('/host/admin/rehearsals'),
    usage: () => at<AdminUsage>('/host/admin/usage'),
    routing: () => at<AdminRouting>('/host/admin/routing'),
    files: (id: string) =>
      at<{ version: string; files: AgentFile[] }>(`/host/admin/agents/${encodeURIComponent(id)}/files`),
    /** A refused proposal is an answer, not a failure: its reasons come back to be shown. */
    propose: async (proposal: {
      title: string
      description: string
      files: Record<string, string>
    }): Promise<ProposalOutcome> => {
      const response = await fetch('/host/admin/proposals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(proposal),
      })
      const body = (await response.json().catch(() => null)) as (ProposalOutcome & { error?: string }) | null
      if (response.status === 201 || response.status === 422) {
        return body ?? { opened: false, problems: ['The host gave no answer.'] }
      }
      throw new ApiError(response.status, body?.error ?? `The host answered ${response.status}.`)
    },
  },

  conversations: () => call<Conversation[]>('/conversations'),

  agents: () => call<AgentInfo[]>('/agents'),

  conversation: (id: string) => call<ConversationDetail>(`/conversations/${encodeURIComponent(id)}`),

  create: (title = '', agent?: string) =>
    call<Conversation>('/conversations', {
      method: 'POST',
      body: JSON.stringify(agent ? { title, agent } : { title }),
    }),

  /** With `agent`, sent to that agent, in a conversation where each message otherwise finds its own. */
  say: (id: string, text: string, attachments: string[] = [], input?: Record<string, unknown>, agent?: string) =>
    call<Turn>(`/conversations/${encodeURIComponent(id)}/messages`, {
      method: 'POST',
      body: JSON.stringify({ text, attachments, ...(input ? { input } : {}), ...(agent ? { agent } : {}) }),
    }),

  cancel: (id: string) =>
    call<{ stopped: boolean }>(`/conversations/${encodeURIComponent(id)}/cancel`, {
      method: 'POST',
      body: '{}',
    }),

  /** Leaves a finished turn out of what the agents read next, or puts it back. */
  leaveOut: (id: string, turnId: string, leftOut: boolean) =>
    call<Turn>(`/conversations/${encodeURIComponent(id)}/turns/${encodeURIComponent(turnId)}`, {
      method: 'PATCH',
      body: JSON.stringify({ leftOut }),
    }),

  rename: (id: string, title: string) =>
    call<Conversation>(`/conversations/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    }),

  forget: (id: string) =>
    call<{ deleted: boolean }>(`/conversations/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    }),

  pending: () => call<PendingDecision[]>('/approvals'),

  /**
   * Hands a file over.
   *
   * Not through `call`: the body is the file's own bytes rather than JSON, and the name and
   * type ride in headers. Multipart would mean a parser on the Java side for one field.
   */
  attach: async (conversationId: string, file: File): Promise<Attachment> => {
    const response = await fetch(
      `/api/conversations/${encodeURIComponent(conversationId)}/attachments`,
      {
        method: 'POST',
        headers: {
          'Content-Type': file.type || 'application/octet-stream',
          // Encoded, because a header may only carry Latin-1 and a filename may carry
          // anything. The Java side decodes it back.
          'X-Filename': encodeURIComponent(file.name),
        },
        body: file,
      },
    )
    const text = await response.text()
    const body: unknown = text ? JSON.parse(text) : null
    if (!response.ok) {
      throw new ApiError(
        response.status,
        body && typeof body === 'object' && 'error' in body
          ? String((body as { error: unknown }).error)
          : `That file was refused (${response.status}).`,
      )
    }
    return body as Attachment
  },

  decide: (
    id: string,
    verdict: 'approve' | 'reject' | 'edit' | 'answer',
    body: Record<string, unknown> = {},
  ) =>
    call<{ decided: boolean }>(`/approvals/${encodeURIComponent(id)}/${verdict}`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
}
