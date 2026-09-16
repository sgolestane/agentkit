import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { Transcript } from './Transcript'
import type { Turn } from '../lib/types'

function turn(id: string): Turn {
  return {
    id, ordinal: 1, userText: `q ${id}`, attachments: [], answer: `a ${id}`,
    state: 'COMPLETED', detail: '', views: [], steps: [], inputTokens: 0,
    outputTokens: 0, startedAt: '2026-09-02T00:00:00Z', endedAt: '2026-09-02T00:00:01Z',
  }
}

/** Puts a scroll position on an element jsdom otherwise reports as zero-sized. */
function scrollTo(element: HTMLElement, { top, height, client }: { top: number; height: number; client: number }) {
  Object.defineProperty(element, 'scrollTop', { value: top, configurable: true })
  Object.defineProperty(element, 'scrollHeight', { value: height, configurable: true })
  Object.defineProperty(element, 'clientHeight', { value: client, configurable: true })
  fireEvent.scroll(element)
}

/**
 * Following the conversation is a state you leave by scrolling away, and rejoin by scrolling
 * back — never something the page decides for you.
 *
 * The failure this prevents is the one every chat UI has shipped at least once: a person scrolls
 * up to read a tool result and is yanked to the bottom on the next token. It is not a cosmetic
 * bug — it makes a streaming answer impossible to read while it streams.
 */
describe('following the end of the transcript', () => {
  const noop = () => {}

  it('offers a way back once the reader has scrolled away', () => {
    render(<Transcript turns={[turn('1')]} working={false} runningTool={null} onRegenerate={noop} onEdit={noop} />)
    expect(screen.queryByText('Jump to the end')).not.toBeInTheDocument()

    scrollTo(screen.getByTestId('transcript'), { top: 0, height: 5000, client: 600 })

    expect(screen.getByText('Jump to the end')).toBeInTheDocument()
  })

  it('rejoins when the reader scrolls back down', () => {
    render(<Transcript turns={[turn('1')]} working={false} runningTool={null} onRegenerate={noop} onEdit={noop} />)
    const list = screen.getByTestId('transcript')
    scrollTo(list, { top: 0, height: 5000, client: 600 })
    expect(screen.getByText('Jump to the end')).toBeInTheDocument()

    scrollTo(list, { top: 4400, height: 5000, client: 600 })

    expect(screen.queryByText('Jump to the end')).not.toBeInTheDocument()
  })

  it('counts a reader a few pixels short of the bottom as being at it', () => {
    // A fractional scroll height and a trackpad's momentum both leave you short. Someone who is
    // visually at the end and is told they are not gets a button they did not need and a
    // transcript that stops following.
    render(<Transcript turns={[turn('1')]} working={false} runningTool={null} onRegenerate={noop} onEdit={noop} />)

    scrollTo(screen.getByTestId('transcript'), { top: 4380, height: 5000, client: 600 })

    expect(screen.queryByText('Jump to the end')).not.toBeInTheDocument()
  })
})
