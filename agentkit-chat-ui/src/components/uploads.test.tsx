import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Composer } from './Composer'
import { sized, UploadChip, TurnAttachments } from './Attachments'
import { Console } from './Console'

function file(name: string, text = 'key,summary\nINC-1,laptop', type = 'text/csv') {
  return new File([text], name, { type })
}

describe('the composer takes files', () => {
  it('takes them from the picker', async () => {
    const onAttach = vi.fn()
    render(<Composer onSend={vi.fn()} onAttach={onAttach} />)

    await userEvent.upload(screen.getByLabelText('Attach files'), file('tickets.csv'))

    expect(onAttach).toHaveBeenCalledTimes(1)
    expect(onAttach.mock.calls[0]![0][0].name).toBe('tickets.csv')
  })

  it('takes them from a drop', () => {
    const onAttach = vi.fn()
    render(<Composer onSend={vi.fn()} onAttach={onAttach} />)

    fireEvent.drop(screen.getByTestId('composer'), {
      dataTransfer: { files: [file('dropped.csv')] },
    })

    expect(onAttach).toHaveBeenCalledTimes(1)
    expect(onAttach.mock.calls[0]![0][0].name).toBe('dropped.csv')
  })

  it('takes them from a paste, which is how a screenshot arrives', () => {
    const onAttach = vi.fn()
    render(<Composer onSend={vi.fn()} onAttach={onAttach} />)

    fireEvent.paste(screen.getByLabelText('Message'), {
      clipboardData: { files: [file('image.png', 'x', 'image/png')] },
    })

    expect(onAttach).toHaveBeenCalledTimes(1)
  })

  it('takes several at once', async () => {
    const onAttach = vi.fn()
    render(<Composer onSend={vi.fn()} onAttach={onAttach} />)

    await userEvent.upload(screen.getByLabelText('Attach files'), [
      file('one.csv'),
      file('two.csv'),
    ])

    expect(onAttach.mock.calls[0]![0]).toHaveLength(2)
  })

  it('sends a message that is only files', async () => {
    // "Here, look at this" is a real message.
    const onSend = vi.fn()
    render(
      <Composer
        onSend={onSend}
        onAttach={vi.fn()}
        uploads={[{ key: 'k', name: 'a.csv', bytes: 10, mediaType: 'text/csv', id: 'att-1' }]}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(onSend).toHaveBeenCalledWith('')
  })

  it('will not send an empty message with nothing attached', () => {
    render(<Composer onSend={vi.fn()} onAttach={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
  })

  it('does not take a drop when the console cannot accept one', () => {
    const onAttach = vi.fn()
    render(<Composer onSend={vi.fn()} onAttach={onAttach} disabled disabledReason="no model" />)

    fireEvent.drop(screen.getByTestId('composer'), {
      dataTransfer: { files: [file('dropped.csv')] },
    })

    expect(onAttach).not.toHaveBeenCalled()
  })
})

describe('an upload chip', () => {
  it('says it is going, then how big it was', () => {
    const { rerender } = render(
      <UploadChip upload={{ key: 'k', name: 'a.csv', bytes: 2048, mediaType: 'text/csv' }} />,
    )
    expect(screen.getByText('sending…')).toBeInTheDocument()

    rerender(
      <UploadChip
        upload={{ key: 'k', name: 'a.csv', bytes: 2048, mediaType: 'text/csv', id: 'att-1' }}
      />,
    )
    expect(screen.getByText('2 kB')).toBeInTheDocument()
  })

  it('keeps a refused file with its reason instead of dropping it', () => {
    // A file that vanished from the composer with no explanation is a person wondering
    // whether they attached it at all.
    render(
      <UploadChip
        upload={{
          key: 'k', name: 'big.bin', bytes: 1, mediaType: '',
          failed: 'That is larger than this console accepts (32 MB).',
        }}
      />,
    )

    expect(screen.getByText(/larger than this console accepts/)).toBeInTheDocument()
  })

  it('reads a size the way a person would', () => {
    expect(sized(512)).toBe('512 B')
    expect(sized(2048)).toBe('2 kB')
    expect(sized(5 * 1024 * 1024)).toBe('5 MB')
  })
})

describe('a turn`s attachments', () => {
  it('offers each as a download rather than showing its contents', () => {
    // The bytes stay server-side. A preview that fetched them back to draw would be doing the
    // one thing the design exists to avoid.
    render(
      <TurnAttachments
        attachments={[
          { id: 'att-1', name: 'tickets.csv', mediaType: 'text/csv', bytes: 2048, uploadedAt: '' },
        ]}
      />,
    )

    const link = screen.getByRole('link', { name: /tickets\.csv/ })
    expect(link).toHaveAttribute('href', '/api/attachments/att-1')
    expect(link).toHaveAttribute('download', 'tickets.csv')
  })
})

describe('uploading end to end', () => {
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

  let uploaded: string[] = []
  let headers: string[] = []
  let sentWith: string[][] = []
  let refuseSecond = false

  beforeEach(() => {
    uploaded = []
    headers = []
    sentWith = []
    refuseSecond = false
    FakeEventSource.last = null
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.stubGlobal('fetch', (input: string, init?: RequestInit) => {
      const url = String(input)
      const ok = (body: unknown) =>
        Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) } as Response)
      if (url.endsWith('/api/overview'))
        return ok({ build: 'b', tenant: 'acme', maxUploadBytes: 33554432, ready: true })
      if (url.endsWith('/api/conversations'))
        return ok([{ id: 'conv-1', title: 'the queue', createdAt: '', updatedAt: '' }])
      if (url.endsWith('/api/approvals')) return ok([])
      if (url.endsWith('/attachments')) {
        const raw = String((init?.headers as Record<string, string>)['X-Filename'])
        headers.push(raw)
        const name = decodeURIComponent(raw)
        if (refuseSecond && headers.length === 2) {
          return Promise.resolve({
            ok: false,
            status: 409,
            text: () =>
              Promise.resolve(
                JSON.stringify({ error: 'That is larger than this console accepts (32 MB).' }),
              ),
          } as Response)
        }
        uploaded.push(name)
        return ok({ id: `att-${uploaded.length}`, name, mediaType: 'text/csv', bytes: 24, uploadedAt: '' })
      }
      if (url.includes('/messages')) {
        sentWith.push(JSON.parse(String(init?.body)).attachments)
        return ok({ id: 'turn-1', state: 'RUNNING', userText: 'look' })
      }
      return ok({
        id: 'conv-1', title: 'the queue', createdAt: '', updatedAt: '',
        turns: [], attachments: [], working: false, lastSequence: 0,
      })
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends the message with the ids of the files that arrived', async () => {
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.upload(await screen.findByLabelText('Attach files'), [
      file('one.csv'),
      file('two.csv'),
    ])
    await waitFor(() => expect(uploaded).toEqual(['one.csv', 'two.csv']))
    await userEvent.type(screen.getByLabelText('Message'), 'look at these{Enter}')

    await waitFor(() => expect(sentWith).toEqual([['att-1', 'att-2']]))
  })

  it('sends a name that a header could not otherwise carry', async () => {
    // An HTTP header value may only be Latin-1, and a browser's fetch THROWS on one that is
    // not — so an unencoded `rapport-café.csv` does not upload badly, it fails to upload at
    // all. jsdom is more forgiving than a browser here, so the assertion is on the wire value
    // being ASCII rather than on the round trip, which would pass either way.
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.upload(await screen.findByLabelText('Attach files'), file('rapport-café.csv'))

    await waitFor(() => expect(headers).toHaveLength(1))
    // eslint-disable-next-line no-control-regex
    expect(headers[0]).toMatch(/^[\x00-\x7F]*$/)
    expect(uploaded).toEqual(['rapport-café.csv'])
  })

  it('does not send a file that never arrived', async () => {
    // The ids that travel are the ones the server acknowledged. Sending a placeholder for a
    // refused file would have the run reach for something that is not there.
    refuseSecond = true
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.upload(await screen.findByLabelText('Attach files'), [
      file('one.csv'),
      file('too-big.bin'),
    ])
    await waitFor(() =>
      expect(screen.getByText(/larger than this console accepts/)).toBeInTheDocument(),
    )
    await userEvent.type(screen.getByLabelText('Message'), 'look{Enter}')

    await waitFor(() => expect(sentWith).toEqual([['att-1']]))
  })

  it('keeps a refused file in the composer with its reason', async () => {
    refuseSecond = true
    render(<Console />)
    await screen.findByText('the queue')

    await userEvent.upload(await screen.findByLabelText('Attach files'), [
      file('one.csv'),
      file('too-big.bin'),
    ])

    // Both chips are still there; one says why it did not go.
    await waitFor(() => expect(screen.getAllByTestId('upload-chip')).toHaveLength(2))
    expect(screen.getByText(/larger than this console accepts/)).toBeInTheDocument()
  })
})
