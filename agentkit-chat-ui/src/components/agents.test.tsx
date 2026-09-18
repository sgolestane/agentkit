import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Threads } from './Threads'
import type { AgentInfo, Conversation } from '../lib/types'

const noop = () => {}

const helpdesk: AgentInfo = { id: 'helpdesk', name: 'IT Helpdesk', description: 'Tickets and MFA resets.' }
const security: AgentInfo = { id: 'security-desk', name: 'Security Desk', description: '', unavailable: 'The directory connector could not be reached.' }

function pinned(id: string, title: string, agent: string): Conversation {
  return { id, title, createdAt: '', updatedAt: '', agent: { id: agent, version: 'abc123' } }
}

describe('A console with more than one agent', () => {
  it('asks which agent a new conversation is with, and says why one cannot answer', async () => {
    const onCreate = vi.fn()
    render(
      <Threads threads={[]} current={null} filter="" working={false} agents={[helpdesk, security]}
        onFilter={noop} onOpen={noop} onCreate={onCreate} onRename={noop} onForget={noop} />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Start a new conversation' }))
    expect(screen.getByRole('menu', { name: 'Which agent' })).toBeInTheDocument()
    expect(screen.getByText('Tickets and MFA resets.')).toBeInTheDocument()
    expect(screen.getByText('The directory connector could not be reached.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('menuitem', { name: /Security Desk/ }))
    expect(onCreate).toHaveBeenCalledWith('security-desk')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('names the agent each conversation is with', () => {
    render(
      <Threads threads={[pinned('conv-1', 'laptop', 'helpdesk'), pinned('conv-2', 'who is sam', 'security-desk')]}
        current="conv-1" filter="" working={false} agents={[helpdesk, security]}
        onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />,
    )

    expect(screen.getByRole('button', { name: /^laptop/ })).toHaveTextContent('IT Helpdesk')
    expect(screen.getByRole('button', { name: /^who is sam/ })).toHaveTextContent('Security Desk')
  })
})

describe('A console with one agent', () => {
  it('starts a conversation with it at once, and does not label every row with it', async () => {
    const onCreate = vi.fn()
    render(
      <Threads threads={[pinned('conv-1', 'laptop', 'helpdesk')]} current="conv-1" filter="" working={false}
        agents={[helpdesk]}
        onFilter={noop} onOpen={noop} onCreate={onCreate} onRename={noop} onForget={noop} />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Start a new conversation' }))
    expect(onCreate).toHaveBeenCalledWith('helpdesk')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'laptop' })).not.toHaveTextContent('IT Helpdesk')
  })
})
