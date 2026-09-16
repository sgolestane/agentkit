import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Trace, money } from './Trace'
import type { Step, Turn } from '../lib/types'

function step(over: Partial<Step> = {}): Step {
  return {
    sequence: 1,
    kind: 'TOOL_CALL',
    name: 'tickets.inbox',
    detail: {
      arguments: '{limit=50}',
      digest: '12 open tickets across 6 categories',
      disposition: 'RAN',
      ran: true,
      resultChars: 36,
      provenance: 'THIRD_PARTY',
      isError: false,
    },
    millis: 31,
    failed: false,
    at: '2026-09-02T00:00:00Z',
    ...over,
  }
}

function turn(steps: Step[], over: Partial<Turn> = {}): Turn {
  return {
    id: 'turn-1', ordinal: 1, userText: 'how many?', attachments: [],
    answer: 'Twelve.', state: 'COMPLETED', detail: '', views: [], steps,
    inputTokens: 1200, outputTokens: 340,
    startedAt: '2026-09-02T00:00:00Z', endedAt: '2026-09-02T00:00:01Z',
    ...over,
  }
}

describe('Trace', () => {
  it('is one line until somebody wants it', () => {
    // A trace open by default turns every reply into a wall of machinery.
    render(<Trace turn={turn([step()])} />)

    expect(screen.getByTestId('trace')).not.toHaveAttribute('open')
    expect(screen.queryByText(/what the model read/)).not.toBeInTheDocument()
  })

  it('counts the calls and the tokens on its one line', () => {
    render(
      <Trace
        turn={turn([
          step({ sequence: 1, kind: 'MODEL_CALL', name: 'opus' }),
          step({ sequence: 2 }),
        ])}
      />,
    )

    const summary = screen.getByTestId('trace').querySelector('summary')
    expect(summary).toHaveTextContent('1 model call, 1 tool call')
    expect(summary).toHaveTextContent('1,200 in / 340 out')
  })

  it('shows what the model actually read, which is the question when an answer is wrong', async () => {
    render(<Trace turn={turn([step()])} />)

    await userEvent.click(screen.getByTestId('trace').querySelector('summary')!)
    await userEvent.click(screen.getByRole('button', { name: /tickets\.inbox/ }))

    expect(screen.getByText('what the model read')).toBeInTheDocument()
    expect(screen.getByText('12 open tickets across 6 categories')).toBeInTheDocument()
    expect(screen.getByText('{limit=50}')).toBeInTheDocument()
  })

  it('says what happened to a call in words rather than in an enum', async () => {
    // A refused call, a parked one and an unknown tool are three different things an operator
    // acts on differently, and a console that showed them all as "error" would be answering
    // the wrong question.
    const dispositions: [string, string][] = [
      ['REFUSED', 'a gate refused it'],
      ['PARKED', 'stopped to ask somebody'],
      ['UNKNOWN_TOOL', 'no such tool'],
      ['THREW', 'the tool failed'],
      ['NOT_ATTEMPTED', 'never started'],
    ]
    for (const [value, said] of dispositions) {
      const { unmount } = render(
        <Trace turn={turn([step({ detail: { ...step().detail, disposition: value, ran: false } })])} />,
      )
      await userEvent.click(screen.getByTestId('trace').querySelector('summary')!)
      expect(screen.getAllByText(said).length).toBeGreaterThan(0)
      unmount()
    }
  })

  it('does not badge a call that simply ran', async () => {
    render(<Trace turn={turn([step()])} />)
    await userEvent.click(screen.getByTestId('trace').querySelector('summary')!)

    expect(screen.queryByText('ran', { selector: 'span' })).not.toBeInTheDocument()
  })

  it('marks a turn whose trace contains a failure', () => {
    render(<Trace turn={turn([step({ failed: true })])} />)

    expect(screen.getByTestId('trace').querySelector('summary')).toHaveTextContent(
      'something failed',
    )
  })

  it('shows what a model call said and how it stopped', async () => {
    render(
      <Trace
        turn={turn([
          step({
            kind: 'MODEL_CALL',
            name: 'opus',
            detail: { stopReason: 'TOOL_USE', text: 'Let me look.', inputTokens: 10, outputTokens: 4 },
          }),
        ])}
      />,
    )

    await userEvent.click(screen.getByTestId('trace').querySelector('summary')!)
    await userEvent.click(screen.getByRole('button', { name: /opus/ }))

    expect(screen.getByText('TOOL_USE')).toBeInTheDocument()
    expect(screen.getByText('Let me look.')).toBeInTheDocument()
    expect(screen.getByText('10 in / 4 out')).toBeInTheDocument()
  })

  it('draws nothing at all for a turn with no steps', () => {
    const { container } = render(<Trace turn={turn([])} />)

    expect(container.querySelector('[data-testid="trace"]')).toBeNull()
  })

  it('shows a cost when the deployment said what its model charges', () => {
    render(<Trace turn={turn([step()], { costUsd: 0.0031 })} />)

    expect(screen.getByTestId('trace').querySelector('summary')).toHaveTextContent('$0.0031')
  })
})

describe('money', () => {
  it('does not round a real cost away to nothing', () => {
    // A turn costing $0.0031 shown as "$0.00" says the run was free, and a conversation of two
    // hundred of them is not.
    expect(money(0.0031)).toBe('$0.0031')
    expect(money(0)).toBe('$0')
    expect(money(1.234)).toBe('$1.23')
  })
})
