import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Threads } from './Threads'
import { Console } from './Console'
import type { Conversation } from '../lib/types'

function thread(id: string, title: string): Conversation {
  return { id, title, createdAt: '', updatedAt: '' }
}

const noop = () => {}

describe('Threads', () => {
  it('lists them and marks the one that is open', () => {
    render(
      <Threads
        threads={[thread('conv-1', 'the queue'), thread('conv-2', 'access requests')]}
        current="conv-2" filter="" working={false}
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop}
      />,
    )

    expect(screen.getByRole('button', { name: 'access requests' }))
      .toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('button', { name: 'the queue' }))
      .not.toHaveAttribute('aria-current')
  })

  it('marks the conversations where a decision waits for the person', () => {
    render(<Threads threads={[thread('c1', 'MFA reset'), thread('c2', 'Laptop')]} current="c2" filter=""
      working={false} waiting={new Set(['c1'])} onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop}
      onForget={noop} />)

    expect(screen.getAllByTestId('needs-you')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'MFA reset Needs you' })).toBeInTheDocument()
  })

  it('calls a conversation nobody has named something rather than nothing', () => {
    render(
      <Threads threads={[thread('conv-1', '')]} current={null} filter="" working={false}
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />,
    )

    // The thread's own button, not the "+" — which is why that one is labelled differently.
    expect(screen.getByRole('button', { name: 'New conversation' })).toBeInTheDocument()
  })

  it('renames on a double click, and lets the person back out', async () => {
    const onRename = vi.fn()
    render(
      <Threads threads={[thread('conv-1', 'the queue')]} current="conv-1" filter="" working={false}
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={onRename} onForget={noop} />,
    )

    await userEvent.dblClick(screen.getByRole('button', { name: 'the queue' }))
    const box = screen.getByLabelText('Conversation name')
    await userEvent.clear(box)
    await userEvent.type(box, 'laptops{Escape}')

    expect(onRename).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'the queue' })).toBeInTheDocument()
  })

  it('renames on Enter', async () => {
    const onRename = vi.fn()
    render(
      <Threads threads={[thread('conv-1', 'the queue')]} current="conv-1" filter="" working={false}
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={onRename} onForget={noop} />,
    )

    await userEvent.dblClick(screen.getByRole('button', { name: 'the queue' }))
    const box = screen.getByLabelText('Conversation name')
    await userEvent.clear(box)
    await userEvent.type(box, 'laptops{Enter}')

    expect(onRename).toHaveBeenCalledWith('conv-1', 'laptops')
  })

  it('takes two clicks to delete, and no blocking dialog', async () => {
    // A confirm() stops the whole page — including the event stream — and a delete that cannot
    // be undone should cost a second press rather than a modal nobody reads.
    const onForget = vi.fn()
    render(
      <Threads threads={[thread('conv-1', 'the queue')]} current="conv-1" filter="" working={false}
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={onForget} />,
    )

    await userEvent.click(screen.getByRole('button', { name: /^Delete/ }))
    expect(onForget).not.toHaveBeenCalled()
    expect(screen.getByText('sure?')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Really delete/ }))
    expect(onForget).toHaveBeenCalledTimes(1)
  })

  it('disarms itself, so a stray first click is not a trap left lying about', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      render(
        <Threads threads={[thread('conv-1', 'the queue')]} current="conv-1" filter="" working={false}
          onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />,
      )
      await user.click(screen.getByRole('button', { name: /^Delete/ }))
      expect(screen.getByText('sure?')).toBeInTheDocument()

      await vi.advanceTimersByTimeAsync(5000)

      await waitFor(() => expect(screen.queryByText('sure?')).not.toBeInTheDocument())
    } finally {
      vi.useRealTimers()
    }
  })

  it('says so when a search matches nothing', () => {
    render(
      <Threads threads={[]} current={null} filter="zzz" working={false}
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />,
    )

    expect(screen.getByText('Nothing matches that.')).toBeInTheDocument()
  })

  it('marks the open conversation as working', () => {
    render(
      <Threads threads={[thread('conv-1', 'the queue')]} current="conv-1" filter="" working
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />,
    )

    expect(within(screen.getByTestId('threads')).getByLabelText('working')).toBeInTheDocument()
  })
})

describe('threads end to end', () => {
  class FakeEventSource {
    static last: FakeEventSource | null = null
    onmessage: ((event: MessageEvent) => void) | null = null
    onerror: (() => void) | null = null
    constructor(readonly url: string) {
      FakeEventSource.last = this
    }
    addEventListener() {}
    close() {}
  }

  let existing: Conversation[] = []
  let created = 0
  let deleted: string[] = []
  let renamed: string[] = []

  beforeEach(() => {
    existing = [thread('conv-1', 'the queue'), thread('conv-2', 'access requests')]
    created = 0
    deleted = []
    renamed = []
    FakeEventSource.last = null
    globalThis.history.replaceState({}, '', '/')
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.stubGlobal('fetch', (input: string, init?: RequestInit) => {
      const url = String(input)
      const ok = (body: unknown) =>
        Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) } as Response)
      if (url.endsWith('/api/overview'))
        return ok({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true })
      if (url.endsWith('/api/approvals')) return ok([])
      if (url.endsWith('/api/conversations') && init?.method === 'POST') {
        created += 1
        return ok(thread(`conv-new-${created}`, ''))
      }
      if (url.endsWith('/api/conversations')) return ok(existing)
      if (init?.method === 'DELETE') {
        deleted.push(url.split('/').pop()!)
        return ok({ deleted: true })
      }
      if (init?.method === 'PATCH') {
        renamed.push(`${url.split('/').pop()}=${JSON.parse(String(init.body)).title}`)
        return ok(thread('conv-1', JSON.parse(String(init.body)).title))
      }
      const id = url.split('/api/conversations/')[1] ?? 'conv-1'
      return ok({
        id, title: existing.find((one) => one.id === id)?.title ?? '', createdAt: '', updatedAt: '',
        turns: [], attachments: [], working: false, lastSequence: 0,
      })
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('opens the conversation the address bar names', async () => {
    // A thread has a URL you can send to somebody.
    globalThis.history.replaceState({}, '', '/c/conv-2')
    render(<Console />)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'access requests' }))
        .toHaveAttribute('aria-current', 'true'),
    )
  })

  it('puts the open conversation in the address bar when you switch', async () => {
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.click(screen.getByRole('button', { name: 'access requests' }))

    await waitFor(() => expect(globalThis.location.pathname).toBe('/c/conv-2'))
  })

  it('goes back to the one you were reading', async () => {
    // A person who switches threads and presses Back means "the one I was reading", and a
    // console that ignored that would be the only tab in their browser that did.
    render(<Console />)
    await screen.findByText('the queue')
    await userEvent.click(screen.getByRole('button', { name: 'access requests' }))
    await waitFor(() => expect(globalThis.location.pathname).toBe('/c/conv-2'))

    globalThis.history.back()

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'the queue' }))
        .toHaveAttribute('aria-current', 'true'),
    )
  })

  it('starts a new conversation rather than emptying the open one', async () => {
    // "Start over" that destroyed the old thread was an earlier prototype console's behaviour, and
    // it is the wrong one: the work is supposed to accumulate.
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.click(screen.getByRole('button', { name: 'Start a new conversation' }))

    await waitFor(() => expect(created).toBe(1))
    // Scoped to the list, and counted: the old threads are still there and the new one is on
    // top. `getByRole` over the whole page would also see the delete buttons, whose accessible
    // names deliberately contain the title.
    const list = within(screen.getByTestId('threads'))
    expect(list.getAllByRole('button', { name: 'the queue' })).toHaveLength(1)
    expect(list.getAllByRole('button', { name: 'access requests' })).toHaveLength(1)
    expect(list.getAllByRole('button', { name: 'New conversation' })).toHaveLength(1)
  })

  it('leaves nothing of the previous conversation behind when you switch', async () => {
    // Not a flicker. The title effect reads whatever the hook last held, so a brand-new
    // conversation was stamped with the name of the thread just left — and for a moment a
    // person is offered an approval card belonging to a conversation they are not looking at.
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.click(screen.getByRole('button', { name: 'Start a new conversation' }))

    await waitFor(() => expect(created).toBe(1))
    const list = within(screen.getByTestId('threads'))
    expect(list.getAllByRole('button', { name: 'New conversation' })).toHaveLength(1)
    expect(list.getAllByRole('button', { name: 'the queue' })).toHaveLength(1)
  })

  it('does not overwrite a name a person chose', async () => {
    // The server titles a conversation after the first thing said in it, and a person who
    // renames a thread has said what it is about. A later auto-title must not overrule them.
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.dblClick(screen.getByRole('button', { name: 'the queue' }))
    const box = screen.getByLabelText('Conversation name')
    await userEvent.clear(box)
    await userEvent.type(box, 'laptops{Enter}')

    await waitFor(() => expect(renamed).toEqual(['conv-1=laptops']))

    // Now leave and come back. That is when the auto-title effect fires again with the
    // server's own title for the thread — and a guard that only fills an EMPTY name is what
    // stops it putting "the queue" back over what the person typed.
    await userEvent.click(screen.getByRole('button', { name: 'access requests' }))
    await userEvent.click(screen.getByRole('button', { name: 'laptops' }))

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'laptops' })).toBeInTheDocument(),
    )
    expect(screen.queryByRole('button', { name: 'the queue' })).not.toBeInTheDocument()
  })

  it('shows nothing of the previous conversation`s transcript after a switch', async () => {
    // A person switching threads must not read the last thread's answer under this thread's
    // name, even for the moment before the new one loads.
    //
    // The second conversation's load is DEFERRED here, deliberately. With a stub that resolves
    // at once the window does not exist — the new transcript has replaced the old one by the
    // time any assertion runs, so the test passes whether or not the switch clears anything.
    // Held open, the stale transcript is either on screen or it is not.
    let releaseSecond = () => {}
    const secondLoad = new Promise<void>((resolve) => {
      releaseSecond = resolve
    })
    vi.stubGlobal('fetch', (input: string, init?: RequestInit) => {
      const url = String(input)
      const body = (value: unknown) =>
        ({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(value)) }) as Response
      if (url.endsWith('/api/overview'))
        return Promise.resolve(body({ build: 'b', tenant: 'acme', maxUploadBytes: 1, ready: true }))
      if (url.endsWith('/api/approvals')) return Promise.resolve(body([]))
      if (url.endsWith('/api/conversations') && init?.method === 'POST')
        return Promise.resolve(body(thread('conv-new', '')))
      if (url.endsWith('/api/conversations')) return Promise.resolve(body(existing))
      const id = url.split('/api/conversations/')[1] ?? 'conv-1'
      const detail = {
        id, title: existing.find((one) => one.id === id)?.title ?? '', createdAt: '', updatedAt: '',
        attachments: [], working: false, lastSequence: 0,
        turns: id === 'conv-1'
          ? [{
              id: 't1', ordinal: 1, userText: 'only in the queue', attachments: [],
              answer: 'an old answer', state: 'COMPLETED', detail: '', views: [], steps: [],
              inputTokens: 0, outputTokens: 0, startedAt: '', endedAt: '',
            }]
          : [],
      }
      return id === 'conv-2'
        ? secondLoad.then(() => body(detail))
        : Promise.resolve(body(detail))
    })
    render(<Console />)
    expect(await screen.findByText('only in the queue')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'access requests' }))

    // The second conversation has not loaded yet. The first one's transcript must already
    // be gone.
    expect(screen.queryByText('only in the queue')).not.toBeInTheDocument()
    releaseSecond()
  })

  it('searches the list', async () => {
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.type(screen.getByLabelText('Search conversations'), 'access')

    expect(screen.queryByRole('button', { name: 'the queue' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'access requests' })).toBeInTheDocument()
  })

  it('deletes one and opens whatever is left', async () => {
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.click(screen.getAllByRole('button', { name: /^Delete/ })[0]!)
    await userEvent.click(screen.getByRole('button', { name: /Really delete/ }))

    await waitFor(() => expect(deleted).toEqual(['conv-1']))
    expect(screen.queryByRole('button', { name: 'the queue' })).not.toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'access requests' }))
        .toHaveAttribute('aria-current', 'true'),
    )
  })
})
