import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskForm } from './TaskForm'
import type { InputSchema } from '../lib/types'

const HIRE: InputSchema = {
  type: 'object',
  required: ['name', 'start_date', 'employment'],
  properties: {
    name: { type: 'string', title: 'Full name' },
    start_date: { type: 'string', format: 'date', title: 'Start date' },
    employment: { type: 'string', enum: ['full_time', 'contractor'], title: 'Employment' },
    remote: { type: 'boolean', title: 'Works remotely', description: 'Ship the laptop home' },
    desk: { type: 'integer', title: 'Desk number' },
  },
}

describe('A task form', () => {
  it('is built from the schema and sends what was filled in, typed as the schema says', async () => {
    const onSubmit = vi.fn()
    render(<TaskForm schema={HIRE} agentName="Onboarding" startOpen disabled={false} onSubmit={onSubmit} />)

    await userEvent.type(screen.getByLabelText(/Full name/), 'Jo Park')
    await userEvent.type(screen.getByLabelText(/Start date/), '2026-10-01')
    await userEvent.selectOptions(screen.getByLabelText(/Employment/), 'contractor')
    await userEvent.click(screen.getByLabelText(/Works remotely/))
    await userEvent.type(screen.getByLabelText(/Desk number/), '42')
    await userEvent.click(screen.getByRole('button', { name: 'Start' }))

    expect(onSubmit).toHaveBeenCalledWith({
      name: 'Jo Park',
      start_date: '2026-10-01',
      employment: 'contractor',
      remote: true,
      desk: 42,
    })
    expect(screen.getByText('Ship the laptop home')).toBeInTheDocument()
  })

  it('marks what is required and leaves out what was left empty', async () => {
    const onSubmit = vi.fn()
    render(<TaskForm schema={HIRE} agentName="Onboarding" startOpen disabled={false} onSubmit={onSubmit} />)

    expect(screen.getByLabelText(/Full name/)).toBeRequired()
    expect(screen.getByLabelText(/Desk number/)).not.toBeRequired()
    await userEvent.type(screen.getByLabelText(/Full name/), 'Jo')
    await userEvent.type(screen.getByLabelText(/Start date/), '2026-10-01')
    await userEvent.selectOptions(screen.getByLabelText(/Employment/), 'full_time')
    await userEvent.click(screen.getByRole('button', { name: 'Start' }))

    expect(onSubmit).toHaveBeenCalledWith({ name: 'Jo', start_date: '2026-10-01', employment: 'full_time', remote: false })
  })

  it('opens by itself only on a conversation that has not started', () => {
    const { rerender } = render(
      <TaskForm schema={HIRE} agentName="Onboarding" startOpen disabled={false} onSubmit={() => {}} />,
    )
    expect(screen.getByTestId('task-form')).toHaveAttribute('open')

    rerender(<TaskForm key="other" schema={HIRE} agentName="Onboarding" startOpen={false} disabled={false} onSubmit={() => {}} />)
    expect(screen.getByTestId('task-form')).not.toHaveAttribute('open')
    expect(screen.getByText('Start Onboarding with its form')).toBeInTheDocument()
  })
})
