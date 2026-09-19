import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PlanAnswer, planSteps } from './PlanAnswer'

const answer = [
  '1. Okta: create an account for marcus.bell@acme.example.',
  '   - Done: I created the account.',
  '2. Slack: create a member account.',
  '   - Stopped (failed): The HRIS does not know that email.',
  '3. Tell the manager.',
  '   - Failed (send_message, open_ticket): I tried to message them.',
  '4. Close the ticket.',
  '   - Not started.',
].join('\n')

describe('A carried-out plan', () => {
  it('is read back into its steps, and anything else is not', () => {
    expect(planSteps(answer)).toEqual([
      { step: 'Okta: create an account for marcus.bell@acme.example.', outcome: 'done', said: 'I created the account.' },
      { step: 'Slack: create a member account.', outcome: 'stopped', reason: 'failed',
        said: 'The HRIS does not know that email.' },
      { step: 'Tell the manager.', outcome: 'failed', reason: 'send_message, open_ticket',
        said: 'I tried to message them.' },
      { step: 'Close the ticket.', outcome: 'not-started', said: '' },
    ])
    expect(planSteps('Done — I granted it.')).toBeNull()
    expect(planSteps('1. A step\nsomething else')).toBeNull()
    expect(planSteps('**Step 1 of 3:** Okta: create an account')).toBeNull()
  })

  it('shows each step with what came of it folded away until opened', async () => {
    render(<PlanAnswer steps={planSteps(answer)!} />)

    const outcomes = screen.getAllByTestId('plan-step-outcome') as HTMLDetailsElement[]
    expect(outcomes).toHaveLength(3)
    const [done, stopped, failed] = outcomes as [HTMLDetailsElement, HTMLDetailsElement, HTMLDetailsElement]
    expect(within(failed).getByText('Failed: send_message, open_ticket')).toHaveClass('text-bad')
    expect(done.open || stopped.open).toBe(false)
    expect(within(done).getByText('Done')).toBeInTheDocument()
    expect(within(stopped).getByText('Stopped (failed)')).toHaveClass('text-warn')
    expect(screen.getByText('Not started')).toBeInTheDocument()

    await userEvent.click(within(stopped).getByText('Stopped (failed)'))
    expect(stopped.open).toBe(true)
    expect(within(stopped).getByText('The HRIS does not know that email.')).toBeInTheDocument()
  })
})
