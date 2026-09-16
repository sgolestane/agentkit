import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalCard } from './ApprovalCard'
import type { PendingDecision } from '../lib/types'

function action(over: Partial<PendingDecision> = {}): PendingDecision {
  return {
    id: 'ask-1',
    conversationId: 'conv-1',
    turnId: 'turn-1',
    kind: 'ACTION',
    tool: 'identity.reset',
    arguments: { user: 'alice' },
    capability: 'identity',
    reason: 'Resetting a password locks the person out until they set a new one.',
    effect: "alice's password is replaced",
    reversible: false,
    question: '',
    askedAt: '2026-09-02T00:00:00Z',
    ...over,
  }
}

describe('ApprovalCard', () => {
  it('says what would happen, on what, and whether it can be undone', () => {
    render(<ApprovalCard decision={action()} onDecide={vi.fn()} />)

    expect(screen.getByText('Approve identity.reset?')).toBeInTheDocument()
    expect(screen.getByText(/locks the person out/)).toBeInTheDocument()
    expect(screen.getByText(/password is replaced/)).toBeInTheDocument()
    expect(screen.getByText(/cannot be undone/)).toBeInTheDocument()
    // The model's own arguments, shown as its own words rather than summarised.
    expect(screen.getByText(/"user": "alice"/)).toBeInTheDocument()
  })

  it('keeps its actions reachable however long the arguments are', () => {
    // The workbench dashboard's stylesheet carries this bug in a comment: left unbounded, the proposed arguments
    // pushed Approve and Reject out of the scroll area, which is the one part of the card that
    // must always be reachable.
    render(
      <ApprovalCard
        decision={action({ arguments: { body: 'x'.repeat(20_000) } })}
        onDecide={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
    // The arguments scroll inside themselves; the actions do not move.
    const shown = screen.getByText(/x{100}/)
    expect(shown.closest('.overflow-auto')).not.toBeNull()
  })

  it('approves, and carries the note with it', async () => {
    const onDecide = vi.fn()
    render(<ApprovalCard decision={action()} onDecide={onDecide} />)

    await userEvent.type(screen.getByLabelText('Note'), 'she asked for it in the ticket')
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    expect(onDecide).toHaveBeenCalledWith('approve', {
      note: 'she asked for it in the ticket',
      standing: false,
    })
  })

  it('rejects, and the note is what teaches the next run', async () => {
    const onDecide = vi.fn()
    render(<ApprovalCard decision={action()} onDecide={onDecide} />)

    await userEvent.type(screen.getByLabelText('Note'), 'she has not asked for this')
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }))

    expect(onDecide).toHaveBeenCalledWith('reject', {
      note: 'she has not asked for this',
      standing: false,
    })
  })

  it('lets a person change the call before approving it', async () => {
    const onDecide = vi.fn()
    render(<ApprovalCard decision={action()} onDecide={onDecide} />)

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const box = screen.getByLabelText('Arguments to run instead')
    await userEvent.clear(box)
    await userEvent.type(box, '{{"user":"alice.smith"}')
    await userEvent.click(screen.getByRole('button', { name: 'Approve as edited' }))

    expect(onDecide).toHaveBeenCalledWith('edit', {
      arguments: { user: 'alice.smith' },
      note: '',
      standing: false,
    })
  })

  it('does not throw away an edit that is not valid JSON', async () => {
    // Somebody editing a call is under time pressure, and silently discarding what they typed
    // is the worst thing this could do. The far side judges the arguments anyway.
    const onDecide = vi.fn()
    render(<ApprovalCard decision={action()} onDecide={onDecide} />)

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const box = screen.getByLabelText('Arguments to run instead')
    await userEvent.clear(box)
    await userEvent.type(box, 'alice.smith')
    await userEvent.click(screen.getByRole('button', { name: 'Approve as edited' }))

    expect(onDecide).toHaveBeenCalledWith('edit', {
      arguments: { value: 'alice.smith' },
      note: '',
      standing: false,
    })
  })

  it('names the family a standing decision would apply to', async () => {
    // The operator is deciding about a family and not about this call, and a checkbox that did
    // not say which family is a decision nobody can make responsibly.
    const onDecide = vi.fn()
    render(<ApprovalCard decision={action()} onDecide={onDecide} />)

    const standing = screen.getByLabelText(/Apply this to every “identity”/)
    await userEvent.click(standing)
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    expect(onDecide).toHaveBeenCalledWith('approve', { note: '', standing: true })
  })

  it('offers no standing choice when the deployment names no capability', () => {
    // Nothing to key it on. Offering it anyway would be a promise the runtime then refuses.
    render(<ApprovalCard decision={action({ capability: '' })} onDecide={vi.fn()} />)

    expect(screen.queryByLabelText(/Apply this to every/)).not.toBeInTheDocument()
  })

  it('shows a reversible action as reversible', () => {
    render(<ApprovalCard decision={action({ reversible: true })} onDecide={vi.fn()} />)

    expect(screen.getByText('yes')).toBeInTheDocument()
  })
})

describe('a question the agent asked', () => {
  function question(): PendingDecision {
    return action({
      kind: 'QUESTION',
      tool: '',
      arguments: {},
      capability: '',
      question: 'There are two people called Alice. Do you mean Alice Smith or Alice Jones?',
    })
  }

  it('asks it in words, with no arguments to approve', () => {
    render(<ApprovalCard decision={question()} onDecide={vi.fn()} />)

    expect(screen.getByText(/two people called Alice/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('sends what the person typed', async () => {
    const onDecide = vi.fn()
    render(<ApprovalCard decision={question()} onDecide={onDecide} />)

    await userEvent.type(screen.getByLabelText('Your answer'), 'Alice Smith')
    await userEvent.click(screen.getByRole('button', { name: 'Answer' }))

    expect(onDecide).toHaveBeenCalledWith('answer', { answer: 'Alice Smith' })
  })

  it('lets a person decline rather than making something up', async () => {
    // A run that cannot be told "I do not know" gets an answer invented under pressure, which
    // is worse than no answer.
    const onDecide = vi.fn()
    render(<ApprovalCard decision={question()} onDecide={onDecide} />)

    await userEvent.click(screen.getByRole('button', { name: 'I do not know' }))

    expect(onDecide).toHaveBeenCalledWith('answer', { answer: '' })
  })

  it('will not send an empty answer through the Answer button', async () => {
    render(<ApprovalCard decision={question()} onDecide={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Answer' })).toBeDisabled()
  })
})
