import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Threads, nameOf } from './Threads'
import type { AgentInfo } from '../lib/types'

const noop = () => {}
const agents: AgentInfo[] = [
  { id: 'access-desk', name: 'Access Desk', description: 'Temporary access.' },
  { id: 'onboarding', name: 'Onboarding', description: 'Sets up a new hire.', unavailable: 'No model.' },
]

// A store of its own: Node's experimental localStorage can shadow jsdom's with one that keeps nothing.
beforeEach(() => {
  const kept = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => kept.get(key) ?? null,
    setItem: (key: string, value: string) => void kept.set(key, value),
    removeItem: (key: string) => void kept.delete(key),
  })
})

afterEach(() => vi.unstubAllGlobals())

describe('The sidebar', () => {
  it('starts a conversation with an agent from its own section, and says which cannot run', async () => {
    const onCreate = vi.fn()
    render(<Threads threads={[]} current={null} filter="" working={false} agents={agents}
      onFilter={noop} onOpen={noop} onCreate={onCreate} onRename={noop} onForget={noop} />)

    const section = within(screen.getByRole('region', { name: 'Agents' }))
    await userEvent.click(section.getByRole('button', { name: 'Access Desk' }))
    expect(onCreate).toHaveBeenCalledWith('access-desk')
    expect(section.getByRole('button', { name: 'Onboarding' })).toHaveAttribute('title', 'No model.')
  })

  it('shows who is signed in, and opens onto signing out', async () => {
    render(<Threads threads={[]} current={null} filter="" working={false} user="acme/priya.natarajan@acme.example"
      org="acme" onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />)

    const account = screen.getByRole('button', { name: 'Priya Natarajan, signed in to acme' })
    expect(account).toHaveTextContent('PN')
    await userEvent.click(account)
    const menu = within(screen.getByRole('menu', { name: 'Account' }))
    expect(menu.getByText('priya.natarajan@acme.example')).toBeInTheDocument()
    expect(menu.getByRole('menuitem', { name: 'Sign out' })).toHaveAttribute('href', '/sign-out')
  })

  it('folds to a rail of icons, and remembers it', async () => {
    const { unmount } = render(<Threads threads={[]} current={null} filter="" working={false} admin
      onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />)

    await userEvent.click(screen.getByRole('button', { name: 'Close the sidebar' }))
    expect(screen.queryByText('Conversations')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start a new conversation' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin')
    unmount()

    render(<Threads threads={[]} current={null} filter="" working={false}
      onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />)
    expect(screen.getByRole('button', { name: 'Open the sidebar' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Open the sidebar' }))
    expect(screen.getByText('Conversations')).toBeInTheDocument()
  })

  it('names a person as their email suggests', () => {
    expect(nameOf('priya.natarajan@acme.example')).toBe('Priya Natarajan')
    expect(nameOf('sam_okafor@acme.example')).toBe('Sam Okafor')
    expect(nameOf('dana@acme.example')).toBe('Dana')
  })
})
