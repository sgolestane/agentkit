import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Threads } from './Threads'
import { Transcript } from './Transcript'
import { applied, asTurn, loaded } from '../lib/transcript'
import type { AgentInfo, Turn } from '../lib/types'

const noop = () => {}
const agents: AgentInfo[] = [
  { id: 'access-desk', name: 'Access Desk', description: 'Temporary access.' },
  { id: 'onboarding', name: 'Onboarding', description: 'Sets up a new hire.' },
]

function turn(id: string, userText: string, answer: string, agent?: string): Turn {
  return asTurn({ id, ordinal: 1, userText, answer, state: 'COMPLETED', startedAt: '', endedAt: '',
    ...(agent ? { agent: { id: agent, version: 'v1' } } : {}) })
}

describe('A conversation where each message finds its agent', () => {
  it('starts without asking which agent', async () => {
    const onCreate = vi.fn()
    render(<Threads threads={[]} current={null} filter="" working={false} agents={agents} routing
      onFilter={noop} onOpen={noop} onCreate={onCreate} onRename={noop} onForget={noop} />)

    await userEvent.click(screen.getByRole('button', { name: 'Start a new conversation' }))
    expect(onCreate).toHaveBeenCalledWith(undefined)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('says who answered each message, and can ask another agent the same thing', async () => {
    const onSendTo = vi.fn()
    render(<Transcript working={false} runningTool={null} onRegenerate={noop} onEdit={noop} routed agents={agents}
      onSendTo={onSendTo}
      turns={[turn('t1', 'I need Datadog access', 'Granted.', 'access-desk'),
        { ...turn('t2', 'What can you do?', 'Two agents.'), ordinal: 2 }]} />)

    const [first, second] = screen.getAllByTestId('answered-by') as [HTMLElement, HTMLElement]
    expect(first).toHaveTextContent('Access Desk')
    expect(second).toHaveTextContent('AgentKit')

    await userEvent.click(within(first).getByRole('button', { name: 'Ask another agent' }))
    await userEvent.click(screen.getByRole('menuitem', { name: /Onboarding/ }))
    expect(onSendTo).toHaveBeenCalledWith('I need Datadog access', 'onboarding')
  })

  it('learns which agent answered when the turn finishes', () => {
    const start = loaded([asTurn({ id: 't1', userText: 'hi', state: 'RUNNING', startedAt: '' })], 0, true)
    const after = applied(start, { sequence: 1, conversationId: 'c', turnId: 't1', type: 'TURN_FINISHED', runId: '',
      runName: '', at: '', data: { state: 'COMPLETED', answer: 'Hello', agent: { id: 'onboarding', version: 'v1' } } })

    expect(after.turns[0]?.agent).toEqual({ id: 'onboarding', version: 'v1' })
  })
})
