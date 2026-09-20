import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProposeChange } from './ProposeChange'

const files = {
  version: 'abc',
  files: [
    { path: 'agents/helpdesk/agent.yaml', content: 'name: IT Helpdesk\n' },
    { path: 'agents/helpdesk/policy.md', content: 'Open a ticket.' },
  ],
}

function host(answer: { status: number; body: unknown }) {
  const posted: unknown[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (String(url).endsWith('/files')) {
      return new Response(JSON.stringify(files), { status: 200 })
    }
    posted.push(JSON.parse(String(init?.body)))
    return new Response(JSON.stringify(answer.body), { status: answer.status })
  }))
  return posted
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Proposing a change', () => {
  it('sends only the files that were edited, and shows where it was opened and what it does', async () => {
    const posted = host({ status: 201, body: { opened: true, branch: 'agentkit/x', url: 'https://github.com/acme/agents/pull/8',
      where: 'pull requests on acme/agents', summary: '**helpdesk** — files: policy.md\n- what it can do is unchanged' } })
    render(<ProposeChange agentId="helpdesk" agentName="IT Helpdesk" where="pull requests on acme/agents" />)

    expect(await screen.findByText('Nothing edited yet.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check and open for review' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'policy.md' }))
    const editor = screen.getByLabelText('Contents of agents/helpdesk/policy.md')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Be brief.')
    expect(screen.getByRole('button', { name: 'policy.md •' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Check and open for review' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Opened for review: https://github.com/acme/agents/pull/8')
    expect(screen.getByRole('status')).toHaveTextContent('what it can do is unchanged')
    expect(posted).toEqual([{ title: 'Change IT Helpdesk', description: '', files: { 'agents/helpdesk/policy.md': 'Be brief.' } }])
  })

  it('shows every reason a change the host would not load was refused', async () => {
    host({ status: 422, body: { opened: false, problems: [
      'agents/helpdesk/agent.yaml confirm: helpdesk/reset_mfa grants something, so it must be confirmed',
      'agents/helpdesk/agent.yaml limts: is not a field here'] } })
    render(<ProposeChange agentId="helpdesk" agentName="IT Helpdesk" where="branches in /repo" />)

    const editor = await screen.findByLabelText('Contents of agents/helpdesk/agent.yaml')
    await userEvent.type(editor, 'limts: 3')
    await userEvent.click(screen.getByRole('button', { name: 'Check and open for review' }))

    const refused = await screen.findByRole('alert')
    expect(refused).toHaveTextContent('Not opened, because:')
    expect(refused).toHaveTextContent('grants something, so it must be confirmed')
    expect(refused).toHaveTextContent('limts: is not a field here')
  })
})
