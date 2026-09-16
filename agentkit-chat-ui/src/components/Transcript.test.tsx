import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Transcript } from './Transcript'
import type { Turn } from '../lib/types'

function turn(over: Partial<Turn> = {}): Turn {
  return {
    id: 'turn-1',
    ordinal: 1,
    userText: 'how many are open?',
    attachments: [],
    answer: 'Twelve are open.',
    state: 'COMPLETED',
    detail: '',
    views: [],
    steps: [],
    inputTokens: 0,
    outputTokens: 0,
    startedAt: '2026-09-02T00:00:00Z',
    endedAt: '2026-09-02T00:00:01Z',
    ...over,
  }
}

const noop = () => {}

describe('Transcript', () => {
  it('shows what the tools produced for a person to look at', () => {
    // Nothing else in this file asserts that a turn's views render at all — removing <Views/>
    // from the transcript left every test green, and a table a tool produced simply never
    // appeared.
    render(
      <Transcript
        turns={[turn({
          views: [{
            kind: 'table',
            data: {
              columns: [{ name: 'category', type: 'text' }],
              rows: [['access']],
            },
          }],
        })]}
        working={false}
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    expect(screen.getByTestId('table-view')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'access' })).toBeInTheDocument()
  })

  it('offers the trace under the answer', () => {
    // Nothing else in this file asserts the trace is rendered at all — removing <Trace/> left
    // every test green, and the most useful thing in the console simply was not there.
    render(
      <Transcript
        turns={[turn({
          steps: [{
            sequence: 1, kind: 'TOOL_CALL', name: 'tickets.inbox',
            detail: { disposition: 'RAN', digest: '12 open' }, millis: 31, failed: false,
            at: '2026-09-02T00:00:00Z',
          }],
        })]}
        working={false}
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    expect(screen.getByTestId('trace')).toBeInTheDocument()
    expect(screen.getByTestId('trace').querySelector('summary'))
      .toHaveTextContent('1 tool call')
  })

  it('renders the answer as markdown rather than as text', () => {
    // Replacing <Markdown/> with {turn.answer} left every other test in this file green,
    // because they all assert on textContent — which is identical either way.
    render(
      <Transcript
        turns={[turn({ answer: '| category | open |\n| --- | --- |\n| access | 12 |' })]}
        working={false}
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'access' })).toBeInTheDocument()
  })

  it('offers to copy an answer once it has finished arriving', () => {
    const { rerender } = render(
      <Transcript turns={[turn({ state: 'RUNNING' })]} working runningTool={null} onRegenerate={noop} onEdit={noop} />,
    )
    expect(screen.queryByLabelText('Copy answer')).not.toBeInTheDocument()

    rerender(<Transcript turns={[turn()]} working={false} runningTool={null} onRegenerate={noop} onEdit={noop} />)

    expect(screen.getByLabelText('Copy answer')).toBeInTheDocument()
  })

  it('shows what was asked and what was answered', () => {
    render(<Transcript turns={[turn()]} working={false} runningTool={null} onRegenerate={noop} onEdit={noop} />)

    expect(screen.getByText('how many are open?')).toBeInTheDocument()
    expect(screen.getByTestId('answer')).toHaveTextContent('Twelve are open.')
  })

  it('names the tool it is running rather than saying nothing for eleven seconds', () => {
    // Nothing but "Thinking…" for as long as it takes is indistinguishable from a hang, and
    // the commonest thing an operator does when a console looks hung is press the button again.
    render(
      <Transcript
        turns={[turn({ state: 'RUNNING', answer: '', startedAt: new Date().toISOString() })]}
        working
        runningTool="tickets.inbox"
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    expect(screen.getByTestId('activity')).toHaveTextContent('Running tickets.inbox')
    expect(screen.getByTestId('activity')).toHaveTextContent('0s')
  })

  it('says a turn is waiting rather than pretending it is running', () => {
    // "The console has your message" and "it is being worked on" are different things to a
    // person, and showing the first as the second says the agent is thinking when it has not
    // been handed the question.
    render(
      <Transcript
        turns={[
          turn({ id: 't1', ordinal: 1, state: 'RUNNING', answer: '', endedAt: null,
            startedAt: new Date().toISOString() }),
          turn({ id: 't2', ordinal: 2, state: 'QUEUED', answer: '', endedAt: null,
            startedAt: new Date().toISOString() }),
        ]}
        working
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    const cards = screen.getAllByTestId('activity')
    expect(cards[0]).toHaveTextContent('Thinking')
    expect(cards[1]).toHaveTextContent('Waiting — 1 ahead of it')
  })

  it('offers Stop on the card itself', async () => {
    const onStop = vi.fn()
    render(
      <Transcript
        turns={[turn({ state: 'RUNNING', answer: '', endedAt: null,
          startedAt: new Date().toISOString() })]}
        working
        runningTool={null}
        onStop={onStop}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Stop' }))

    expect(onStop).toHaveBeenCalled()
  })

  it('stops counting once the turn has ended', () => {
    render(
      <Transcript
        turns={[turn({ state: 'COMPLETED' })]}
        working={false}
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    // A finished card that kept counting would be lying.
    expect(screen.queryByTestId('activity')).not.toBeInTheDocument()
  })

  it('draws a turn waiting on a person as a decision, not an error', () => {
    // The state that makes this an agent's console. A person shown an error dismisses it; a
    // person shown a decision makes it.
    render(
      <Transcript
        turns={[turn({ state: 'WAITING_FOR_HUMAN', detail: 'Resetting a password is not reversible. Approve?' })]}
        working={false}
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    const waiting = screen.getByText(/not reversible/)
    expect(waiting).toBeInTheDocument()
    expect(waiting).toHaveClass('text-warn')
  })

  it('draws a stop as the person`s own decision', () => {
    render(
      <Transcript
        turns={[turn({ state: 'CANCELLED', detail: 'You stopped this.' })]}
        working={false}
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )

    const stopped = screen.getByText('You stopped this.')
    expect(stopped).toHaveClass('text-muted')
    expect(stopped).not.toHaveClass('text-bad')
  })

  it('offers regenerate and edit only once a turn has finished', () => {
    const { rerender } = render(
      <Transcript
        turns={[turn({ state: 'RUNNING' })]}
        working
        runningTool={null}
        onRegenerate={noop}
        onEdit={noop}
      />,
    )
    expect(screen.queryByText('Regenerate')).not.toBeInTheDocument()

    rerender(
      <Transcript turns={[turn()]} working={false} runningTool={null} onRegenerate={noop} onEdit={noop} />,
    )
    expect(screen.getByText('Regenerate')).toBeInTheDocument()
  })

  it('hands the old message back when a person chooses to edit it', async () => {
    const onEdit = vi.fn()
    render(<Transcript turns={[turn()]} working={false} runningTool={null} onRegenerate={noop} onEdit={onEdit} />)

    await userEvent.click(screen.getByText('Edit and resend'))

    expect(onEdit).toHaveBeenCalledWith('how many are open?')
  })

  it('bounds what it draws and says what it is withholding', async () => {
    // A long conversation stays smooth because the DOM is bounded, and the reader is told what
    // is being held back rather than left to wonder where it went.
    const many = Array.from({ length: 260 }, (_, i) =>
      turn({ id: `turn-${i}`, userText: `question ${i}`, answer: `answer ${i}` }),
    )
    render(<Transcript turns={many} working={false} runningTool={null} onRegenerate={noop} onEdit={noop} />)

    expect(screen.queryByText('question 0')).not.toBeInTheDocument()
    expect(screen.getByText('question 259')).toBeInTheDocument()
    expect(screen.getByText('60 earlier turns — show them')).toBeInTheDocument()

    await userEvent.click(screen.getByText('60 earlier turns — show them'))

    expect(screen.getByText('question 0')).toBeInTheDocument()
  })
})
