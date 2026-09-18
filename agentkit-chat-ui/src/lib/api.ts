import type {
  AgentInfo,
  Attachment,
  Conversation,
  ConversationDetail,
  Overview,
  PendingDecision,
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
  const response = await fetch(`/api${path}`, {
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

  conversations: () => call<Conversation[]>('/conversations'),

  agents: () => call<AgentInfo[]>('/agents'),

  conversation: (id: string) => call<ConversationDetail>(`/conversations/${encodeURIComponent(id)}`),

  create: (title = '', agent?: string) =>
    call<Conversation>('/conversations', {
      method: 'POST',
      body: JSON.stringify(agent ? { title, agent } : { title }),
    }),

  say: (id: string, text: string, attachments: string[] = []) =>
    call<Turn>(`/conversations/${encodeURIComponent(id)}/messages`, {
      method: 'POST',
      body: JSON.stringify({ text, attachments }),
    }),

  cancel: (id: string) =>
    call<{ stopped: boolean }>(`/conversations/${encodeURIComponent(id)}/cancel`, {
      method: 'POST',
      body: '{}',
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
