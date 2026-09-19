import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Admin, short } from './Admin'
import { Threads } from '../components/Threads'
import type { AdminAgent, AdminOverview, RehearsalReport } from '../lib/types'

const V2 = '2c6d870039d30b0dddb517ee5e0963f1bf3f89fe'
const V1 = '1a2b3c4d5e6f70819293a4b5c6d7e8f901234567'

const overview: AdminOverview = {
  org: 'acme',
  current: V2,
  admins: ['agent-operators'],
  versions: [
    {
      version: V2,
      current: true,
      agents: [
        { id: 'onboarding', name: 'Onboarding', description: 'Sets up a new hire.', pattern: 'plan-execute',
          audience: ['managers'], evals: 3 },
        { id: 'access-desk', name: 'Access Desk', description: 'Temporary access.', pattern: 'chat',
          audience: ['everyone'], evals: 0, unavailable: 'The ledger connector could not be reached.' },
      ],
    },
    { version: V1, current: false, agents: [] },
  ],
  connectors: [{ name: 'onboarding', reached: true, tools: 19 }],
}

const onboarding: AdminAgent = {
  id: 'onboarding', name: 'Onboarding', description: 'Sets up a new hire.', version: V2, pattern: 'plan-execute',
  model: 'anthropic/claude-sonnet-5', audience: ['managers'], limits: { maxSteps: 12, maxTokens: 4096 },
  prompts: { planner: 'Plan it.', executor: 'Do one step.', policy: 'Onboarding policy.' },
  tools: [
    { connector: 'onboarding', name: 'hris_get_worker', description: 'Look a worker up.', effect: 'read',
      system: 'hris', confirmed: false, bound: {}, sideEffects: 'none', refusedInRehearsal: false },
    { connector: 'onboarding', name: 'okta_create_user', description: 'Create an Okta account.', effect: 'grant',
      system: 'okta', confirmed: true, bound: { requested_by: 'principal.email' }, sideEffects: 'external',
      refusedInRehearsal: true },
  ],
  input: { type: 'object', required: ['employee_id'], properties: { employee_id: { type: 'string', title: 'Employee id' } } },
  deferred: { actor: 'onboarding', subjects: { worker: 'onboarding/hris_get_worker(employee_id)' } },
  mcpDirect: [],
  evals: [{ name: 'contractor', as: 'lena.ortiz@acme.example', input: { employee_id: 'W-1002' }, answers: [],
    expect: ['calls okta_create_user with {groups=[sales, contractors]}'] }],
  planReuse: {
    after: 3, recheckEvery: 10, sameWhen: [],
    kinds: [
      { task: ['department=Sales', 'employment_type=contractor', 'termination_date given'], runs: 4,
        settled: ['Okta: create an account for {{input.work_email}} with groups sales and contractors.'] },
      { task: ['department=Engineering', 'employment_type=full-time'], runs: 1 },
    ],
  },
}

const report: RehearsalReport = {
  org: 'acme', version: V2, at: '2026-09-18T20:00:00Z', receivedAt: '2026-09-18T20:01:00Z',
  pullRequest: 'https://github.com/acme/agents/pull/7', title: 'Tighten the planner', held: 1, cases: 2, untested: [],
  results: [
    { agent: 'onboarding', case: 'contractor', as: 'lena.ortiz@acme.example', passed: true, state: 'COMPLETED',
      millis: 1000, plan: ['Okta: create the account.'], questions: [],
      calls: [{ tool: 'okta_create_user', arguments: { email: 'marcus.bell@acme.example' }, refused: true, would: 'grant' }],
      checks: [{ name: 'calls okta_create_user', passed: true, detail: '' }], answer: 'Would have.' },
    { agent: 'onboarding', case: 'rehire', as: 'sam.okafor@acme.example', passed: false, state: 'COMPLETED',
      millis: 1000, plan: [], questions: [], calls: [],
      checks: [{ name: 'never calls schedule_deferred_action', passed: false, detail: 'tool was requested' }], answer: '' },
  ],
}

function serve(routes: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = String(url).replace(/^\/host/, '')
    const body = routes[path]
    return new Response(JSON.stringify(body ?? { error: `nothing at ${path}` }), { status: body ? 200 : 404 })
  }))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('The admin view', () => {
  it('shows each loaded version with its agents, and what one of them can do', async () => {
    serve({ '/admin': overview, [`/admin/agents/onboarding?version=${V2}`]: onboarding })
    render(<Admin />)

    expect(await screen.findByText('acme — admin')).toBeInTheDocument()
    expect(screen.getByTestId('current-version')).toHaveTextContent(`serving ${V2.slice(0, 10)}`)
    expect(screen.getByText(/current: new conversations start here/)).toBeInTheDocument()
    expect(screen.getByText(/still serving the conversations pinned to it/)).toBeInTheDocument()
    expect(screen.getByText('Unavailable: The ledger connector could not be reached.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /^Onboarding/ }))
    const agent = await screen.findByRole('article', { name: 'Onboarding' })
    const can = within(agent).getByRole('region', { name: 'What it can do' })
    expect(within(can).getByText('grant')).toBeInTheDocument()
    expect(within(can).getByText('confirmed by the person')).toBeInTheDocument()
    expect(within(can).getByText('requested_by ← principal.email')).toBeInTheDocument()
    expect(within(can).getByText('refused in a rehearsal')).toBeInTheDocument()
    expect(within(agent).getByText(/Runs as/)).toHaveTextContent('worker (looked up with onboarding/hris_get_worker(employee_id))')
    expect(within(agent).getByText('calls okta_create_user with {groups=[sales, contractors]}')).toBeInTheDocument()
    const kinds = within(agent).getAllByTestId('plan-kind')
    expect(kinds[0]).toHaveTextContent('department=Sales · employment_type=contractor · termination_date given')
    expect(kinds[0]).toHaveTextContent('4 runssettled')
    expect(kinds[0]).toHaveTextContent('Okta: create an account for {{input.work_email}}')
    expect(kinds[1]).toHaveTextContent('1 runstill planned')
  })

  it('shows what each pull request’s rehearsal held, and what an agent would have done', async () => {
    serve({ '/admin': overview, '/admin/rehearsals': { reports: [report] } })
    render(<Admin />)

    await userEvent.click(await screen.findByRole('button', { name: 'Rehearsals' }))
    expect(await screen.findByText('1 of 2 held')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'pull request' })).toHaveAttribute('href', report.pullRequest)
    const cases = screen.getAllByTestId('rehearsal-case')
    expect(cases[0]).toHaveTextContent('would grant: okta_create_user')
    expect(cases[1]).toHaveTextContent('✗ never calls schedule_deferred_action — tool was requested')
  })

  it('shows whose model account the agents run on, its budgets, and what they spent', async () => {
    serve({
      '/admin': overview,
      '/admin/usage': {
        account: 'host',
        concurrentCalls: { limit: 8, running: 2 },
        budgets: [
          { setBy: 'in its org.yaml', hostAccountOnly: false, caps: ['1,500 tokens a day'],
            reached: 'for today (1,500 tokens)' },
          { setBy: 'by the host', hostAccountOnly: true, caps: ['$500.00 a month'] },
        ],
        today: { calls: 2, inputTokens: 800, outputTokens: 800, usd: 0.0144 },
        month: { calls: 40, inputTokens: 16000, outputTokens: 16000, usd: 1.25 },
        byAgent: [{ agent: 'access-desk', model: 'anthropic/claude-sonnet-5', account: 'host', calls: 40,
          inputTokens: 16000, outputTokens: 16000, usd: 1.25 }],
        unpriced: ['some/other-model'],
      },
    })
    render(<Admin />)

    await userEvent.click(await screen.findByRole('button', { name: 'Model use' }))
    expect(await screen.findByText(/The host’s model account: the host pays/)).toHaveTextContent(
      'At most 8 calls at once; 2 running now.')
    const budgets = screen.getAllByTestId('budget')
    expect(budgets[0]).toHaveTextContent('1,500 tokens a day, set in its org.yaml')
    expect(budgets[0]).toHaveTextContent('spent for today (1,500 tokens)')
    expect(budgets[1]).toHaveTextContent('$500.00 a month, set by the host, on what the host pays for')
    expect(budgets[1]).toHaveTextContent('within')
    expect(screen.getByTestId('spent')).toHaveTextContent(
      'Today: 2 calls, 1,600 tokens, $0.01. This month (UTC): 40 calls, 32,000 tokens, $1.25.')
    expect(screen.getByText(/no price for some\/other-model/)).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'access-desk' })).toBeInTheDocument()
  })

  it('shows where messages went, what the router spent, and the ones sent again to another agent', async () => {
    serve({
      '/admin': overview,
      '/admin/routing': {
        days: 30, enabled: true, messages: 5,
        byAgent: { onboarding: 3, 'access-desk': 1 },
        byAction: { agent: 3, answer: 1, chosen: 1 },
        names: { onboarding: 'Onboarding', 'access-desk': 'Access Desk' },
        routerTokens: { today: 2500, days: 12000 },
        misroutes: [{ id: 't9', at: '2026-09-19T10:00:00Z', said: 'Give Ravi staging access', routedTo: 'onboarding',
          chosen: 'access-desk', before: [] }],
        recent: [{ at: '2026-09-19T10:01:00Z', said: 'What can you do?', to: null, action: 'answer',
          why: 'about the agents' }],
      },
    })
    render(<Admin />)

    await userEvent.click(await screen.findByRole('button', { name: 'Routing' }))
    expect(await screen.findByTestId('routing-summary')).toHaveTextContent(
      'In the last 30 days, 5 messages: Onboarding 3, Access Desk 1. AgentKit answered 1, asked 0 back and showed a '
      + 'form 0 times; people chose the agent themselves 1 times.')
    expect(screen.getByTestId('router-tokens')).toHaveTextContent('2,500 tokens today and 12,000 in 30 days')
    expect(screen.getByTestId('misroute')).toHaveTextContent('Went to Onboarding; sent again to Access Desk')
    expect(screen.getByRole('cell', { name: 'AgentKit (answered)' })).toBeInTheDocument()

    // A misroute becomes a routing case, opened for review.
    const posted = vi.fn(async () => new Response(JSON.stringify({ opened: true, case: 'give-ravi-staging-t9',
      branch: 'agentkit/add-the-routing-case-1', url: 'https://github.com/acme/agents/pull/9' }), { status: 201 }))
    vi.stubGlobal('fetch', posted)
    await userEvent.click(screen.getByRole('button', { name: 'Add as a routing case: expect Access Desk' }))
    expect(await screen.findByTestId('case-opened')).toHaveTextContent('Opened for review: pull request.')
    expect(screen.getByRole('link', { name: 'pull request' })).toHaveAttribute('href',
      'https://github.com/acme/agents/pull/9')
    expect(posted).toHaveBeenCalledWith('/host/admin/routing/cases', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ misroute: 't9' }) }))
  })

  it('says why someone who is not an admin sees nothing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(
      { error: 'Only the admins org.yaml names see this organization\'s admin view.' }), { status: 403 })))
    render(<Admin />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Only the admins org.yaml names')
  })

  it('is linked from the console for an admin only', () => {
    const noop = () => {}
    const { rerender } = render(<Threads threads={[]} current={null} filter="" working={false} admin
      onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />)
    expect(screen.getByRole('link', { name: /Admin/ })).toHaveAttribute('href', '/admin')

    rerender(<Threads threads={[]} current={null} filter="" working={false}
      onFilter={noop} onOpen={noop} onCreate={noop} onRename={noop} onForget={noop} />)
    expect(screen.queryByRole('link', { name: /Admin/ })).not.toBeInTheDocument()
  })

  it('names a version by its commit, and says when it was never committed', () => {
    expect(short(V2)).toBe('2c6d870039')
    expect(short(`${V2}-dirty-a15eb07267db`)).toBe('2c6d870039 (uncommitted)')
  })
})
