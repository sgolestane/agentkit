import { useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { ProposeChange } from './ProposeChange'
import { Account, RAIL, ROW, RailButton, useFolded } from '../components/Threads'
import { AgentIcon, ChartIcon, ChatIcon, ClockIcon, PlugIcon, RehearsalIcon, SidebarIcon } from '../components/Icons'
import type {
  AdminAgent,
  AdminDeferredAction,
  AdminOverview,
  AdminTool,
  AdminUsage,
  ModelSpend,
  RehearsalReport,
  RehearsalResult,
} from '../lib/types'

/**
 * The organization's admin view: what each loaded version of its agents can do, what its pull
 * requests' rehearsals showed, the work its agents have scheduled, and what their model calls spent.
 *
 * Read-only on purpose. An agent is changed by a pull request to the organization's repository,
 * and this is where the effect of one is read — so there is nothing here to edit, and nothing
 * that could drift from what Git says.
 */
type Section = 'agents' | 'rehearsals' | 'deferred' | 'usage' | 'connectors'

const SECTIONS: { id: Section; label: string; icon: React.ReactNode }[] = [
  { id: 'agents', label: 'Agents', icon: <AgentIcon /> },
  { id: 'rehearsals', label: 'Rehearsals', icon: <RehearsalIcon /> },
  { id: 'deferred', label: 'Deferred work', icon: <ClockIcon /> },
  { id: 'usage', label: 'Model use', icon: <ChartIcon /> },
  { id: 'connectors', label: 'Connectors', icon: <PlugIcon /> },
]

/** The order effects are shown in: what only looks first, what takes away last. */
const EFFECTS = ['read', 'request', 'notify', 'grant', 'revoke', 'schedule']

export function short(version: string): string {
  const dirty = version.indexOf('-dirty-')
  return dirty > 0 ? `${version.slice(0, 10)} (uncommitted)` : version.slice(0, 10)
}

export function Admin() {
  const [overview, setOverview] = useState<AdminOverview | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [section, setSection] = useState<Section>('agents')
  const [open, setOpen] = useState<{ id: string; version: string } | null>(null)

  useEffect(() => {
    api.admin
      .overview()
      .then(setOverview)
      .catch((error: unknown) =>
        setProblem(error instanceof ApiError ? error.message : 'The admin view did not answer.'),
      )
  }, [])

  return (
    <div className="flex h-full">
      <AdminNav
        overview={overview}
        section={section}
        onSection={(one) => {
          setSection(one)
          setOpen(null)
        }}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-[52px] shrink-0 items-center gap-3 px-4">
          <h1 className="text-lg font-semibold">{overview ? `${overview.org} — admin` : 'Admin'}</h1>
          {overview ? (
            <span className="text-sm text-faint" data-testid="current-version">
              serving {short(overview.current)}
            </span>
          ) : null}
        </header>

        {problem ? (
          <p className="mx-4 my-2 rounded-[var(--radius-item)] border border-bad bg-panel px-4 py-2 text-sm text-bad" role="alert">
            {problem}
          </p>
        ) : null}

        {overview ? (
          <main className="min-h-0 flex-1 overflow-y-auto px-4 pb-6">
            {section === 'agents' && open ? (
              <AgentDetail
                id={open.id}
                version={open.version}
                current={open.version === overview.current}
                proposals={overview.proposals}
                onBack={() => setOpen(null)}
              />
            ) : section === 'agents' ? (
              <Versions overview={overview} onOpen={(id, version) => setOpen({ id, version })} />
            ) : section === 'rehearsals' ? (
              <Rehearsals />
            ) : section === 'deferred' ? (
              <Deferred />
            ) : section === 'usage' ? (
              <Usage />
            ) : (
              <Connectors overview={overview} />
            )}
          </main>
        ) : null}
      </div>
    </div>
  )
}

/**
 * The admin view's sidebar, the same as the console's: back to the conversations, the sections,
 * and who is signed in. It folds to a rail with the console's, since it is the same choice.
 */
function AdminNav({
  overview,
  section,
  onSection,
}: {
  overview: AdminOverview | null
  section: Section
  onSection: (section: Section) => void
}) {
  const [folded, fold] = useFolded()
  const [me, setMe] = useState<{ user?: string; org?: string }>({})
  useEffect(() => {
    api
      .overview()
      .then((read) => setMe({
        user: typeof read.user === 'string' ? read.user : read.tenant,
        org: typeof read.org === 'string' ? read.org : undefined,
      }))
      .catch(() => {
        // Who is signed in is a courtesy here; the view works without it.
      })
  }, [])

  if (folded) {
    return (
      <aside className="flex w-[52px] shrink-0 flex-col items-center gap-1 bg-sidebar py-2">
        <RailButton label="Open the sidebar" onClick={() => fold(false)}>
          <SidebarIcon />
        </RailButton>
        <a href="/" aria-label="Back to conversations" title="Back to conversations" className={RAIL}>
          <ChatIcon />
        </a>
        {overview ? SECTIONS.map((one) => (
          <button
            key={one.id}
            type="button"
            aria-label={one.label}
            title={one.label}
            aria-current={section === one.id ? 'page' : undefined}
            onClick={() => onSection(one.id)}
            className={`${RAIL} ${section === one.id ? 'bg-selected text-ink' : ''}`}
          >
            {one.icon}
          </button>
        )) : null}
        <div className="flex-1" />
        {me.user ? <Account user={me.user} org={me.org} rail /> : null}
      </aside>
    )
  }

  return (
    <aside className="flex w-[260px] shrink-0 flex-col bg-sidebar">
      <div className="flex h-[52px] shrink-0 items-center gap-2 pl-4 pr-2">
        <span className="text-lg font-semibold">AgentKit</span>
        <div className="ml-auto">
          <RailButton label="Close the sidebar" onClick={() => fold(true)}>
            <SidebarIcon />
          </RailButton>
        </div>
      </div>

      <div className="flex shrink-0 flex-col px-2">
        <a href="/" className={ROW}>
          <ChatIcon />
          Back to conversations
        </a>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {overview ? (
          <nav aria-label="Admin sections">
            <h2 className="px-2.5 pb-1 pt-5 text-sm font-medium text-faint">{overview.org} admin</h2>
            {SECTIONS.map((one) => (
              <button
                key={one.id}
                type="button"
                onClick={() => onSection(one.id)}
                aria-current={section === one.id ? 'page' : undefined}
                className={`${ROW} w-full text-left ${section === one.id ? 'bg-selected' : ''}`}
              >
                {one.icon}
                {one.label}
              </button>
            ))}
            <p className="px-2.5 pt-5 text-xs text-faint">
              Changes are pull requests to the organization&apos;s repository. Admins: {overview.admins.join(', ')}.
            </p>
          </nav>
        ) : null}
      </div>

      {me.user ? (
        <div className="shrink-0 p-2">
          <Account user={me.user} org={me.org} />
        </div>
      ) : null}
    </aside>
  )
}

function Versions({
  overview,
  onOpen,
}: {
  overview: AdminOverview
  onOpen: (id: string, version: string) => void
}) {
  return (
    <div className="space-y-6">
      {overview.router ? (
        <p className="text-sm text-muted" data-testid="routing">
          {overview.router.enabled
            ? `Routing is on: a message sent to no agent in particular goes to the one that handles it, decided with ${
                overview.router.model}${overview.router.instructions ? ' and the organization\'s own instructions' : ''}. ${
                overview.router.cases > 0 ? `${overview.router.cases} routing case(s) are rehearsed on every pull request.`
                  : 'No routing cases (routing.yaml): a pull request cannot show where messages would go.'}`
            : 'Routing is off: a person chooses an agent to start a conversation.'}
        </p>
      ) : null}
      {overview.versions.map((version) => (
        <section key={version.version} aria-label={`Version ${short(version.version)}`}>
          <h2 className="mb-2 text-base font-semibold">
            {short(version.version)}{' '}
            <span className="font-normal text-muted">
              {version.current
                ? '— current: new conversations start here'
                : '— still serving the conversations pinned to it'}
            </span>
          </h2>
          <ul className="grid gap-2 sm:grid-cols-2">
            {version.agents.map((agent) => (
              <li key={agent.id}>
                <button
                  type="button"
                  onClick={() => onOpen(agent.id, version.version)}
                  className="w-full rounded-[var(--radius-card)] border border-line bg-panel p-4 text-left hover:bg-hover"
                >
                  <span className="block text-base font-medium">{agent.name}</span>
                  <span className="block text-xs text-muted">
                    {agent.id} · {agent.pattern} · for {agent.audience.join(', ')} ·{' '}
                    {agent.evals === 0 ? 'no eval cases' : `${agent.evals} eval case${agent.evals === 1 ? '' : 's'}`}
                  </span>
                  <span className="mt-1 block text-sm text-muted">{agent.description}</span>
                  {agent.unavailable ? (
                    <span className="mt-1 block text-sm text-bad">Unavailable: {agent.unavailable}</span>
                  ) : null}
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

function AgentDetail({
  id,
  version,
  current,
  proposals,
  onBack,
}: {
  id: string
  version: string
  current: boolean
  proposals?: AdminOverview['proposals']
  onBack: () => void
}) {
  const [agent, setAgent] = useState<AdminAgent | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [proposing, setProposing] = useState(false)

  useEffect(() => {
    api.admin
      .agent(id, version)
      .then(setAgent)
      .catch((error: unknown) => setProblem(error instanceof ApiError ? error.message : 'The agent did not load.'))
  }, [id, version])

  if (problem) {
    return <p className="text-sm text-bad">{problem}</p>
  }
  if (!agent) {
    return <p className="text-sm text-muted">Loading…</p>
  }
  const byEffect = EFFECTS.map((effect) => ({ effect, tools: agent.tools.filter((tool) => tool.effect === effect) }))
    .filter((group) => group.tools.length > 0)

  return (
    <article className="max-w-4xl space-y-8" aria-label={agent.name}>
      <button type="button" onClick={onBack} className="-ml-2.5 rounded-[var(--radius-item)] px-2.5 py-1.5 text-sm text-muted hover:bg-hover hover:text-ink">
        ← All agents
      </button>
      <header>
        <h2 className="text-2xl font-semibold">{agent.name}</h2>
        <p className="mt-1 text-base text-muted">{agent.description}</p>
        <p className="mt-2 text-sm text-faint">
          {agent.id} at {short(agent.version)} · {agent.pattern} · {agent.model} · for {agent.audience.join(', ')} · at
          most {agent.limits.maxSteps} steps
        </p>
        {agent.unavailable ? <p className="mt-1 text-sm text-bad">Unavailable: {agent.unavailable}</p> : null}
        {current && proposals?.enabled ? (
          <button
            type="button"
            onClick={() => setProposing((open) => !open)}
            aria-expanded={proposing}
            className="mt-3 rounded-full border border-line px-4 py-1.5 text-sm hover:bg-hover"
          >
            {proposing ? 'Close the change' : 'Propose a change'}
          </button>
        ) : current && proposals?.why ? (
          <p className="mt-2 text-sm text-faint">Changes cannot be proposed from here now: {proposals.why}</p>
        ) : null}
      </header>

      {proposing && proposals?.where ? (
        <ProposeChange agentId={agent.id} agentName={agent.name} where={proposals.where} />
      ) : null}

      <section aria-label="What it can do">
        <h3 className="mb-2 text-base font-semibold">What it can do</h3>
        {byEffect.map((group) => (
          <div key={group.effect} className="mb-3">
            <h4 className="mb-1.5 text-sm font-medium text-faint">{group.effect}</h4>
            <ul className="divide-y divide-line-soft rounded-[var(--radius-card)] border border-line bg-panel">
              {group.tools.map((tool) => (
                <ToolRow key={tool.name} tool={tool} />
              ))}
            </ul>
          </div>
        ))}
      </section>

      {agent.input ? (
        <section aria-label="Its form">
          <h3 className="mb-2 text-base font-semibold">Its form</h3>
          <ul className="text-sm">
            {Object.entries(agent.input.properties).map(([name, field]) => (
              <li key={name}>
                <code>{name}</code> — {field.title ?? name} ({field.type}
                {field.enum ? `: ${field.enum.join(', ')}` : ''}
                {agent.input?.required?.includes(name) ? ', required' : ''})
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {agent.deferred ? (
        <section aria-label="Deferred work">
          <h3 className="mb-2 text-base font-semibold">Deferred work</h3>
          <p className="text-sm">
            Runs as <code>{agent.deferred.actor}</code>, about{' '}
            {Object.entries(agent.deferred.subjects)
              .map(([kind, lookup]) => `${kind} (looked up with ${lookup})`)
              .join(', ')}
            . A deferred action gets only the tools that read, revoke, notify or request, for its subject.
          </p>
        </section>
      ) : null}

      {agent.planReuse ? (
        <section aria-label="Plan reuse">
          <h3 className="mb-2 text-base font-semibold">Plan reuse</h3>
          <p className="text-sm">
            Once the last {agent.planReuse.after} plans for tasks alike agree, the next is carried out on that plan
            without asking the model to plan; one in {agent.planReuse.recheckEvery} is planned afresh. Tasks are alike
            when their choices{agent.planReuse.sameWhen.length > 0 ? `, ${agent.planReuse.sameWhen.join(', ')}` : ''}{' '}
            and the fields filled in match.
          </p>
          {agent.planReuse.kinds.length === 0 ? (
            <p className="mt-1 text-sm text-faint">Nothing planned at this version yet.</p>
          ) : (
            <ul className="mt-2 space-y-2">
              {agent.planReuse.kinds.map((kind) => (
                <li key={kind.task.join('|')} className="rounded-[var(--radius-card)] border border-line bg-panel p-4 text-sm"
                  data-testid="plan-kind">
                  <p className="text-muted">{kind.task.join(' · ')}</p>
                  <p className="mt-1">
                    {kind.runs} run{kind.runs === 1 ? '' : 's'}
                    {kind.settled ? <Badge tone="good">settled</Badge> : <Badge tone="muted">still planned</Badge>}
                  </p>
                  {kind.settled ? (
                    <ol className="mt-1 list-decimal pl-5">
                      {kind.settled.map((step, i) => (
                        <li key={i}>{step}</li>
                      ))}
                    </ol>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}

      {agent.mcpDirect.length > 0 ? (
        <p className="text-sm">
          Offered directly over MCP: {agent.mcpDirect.map((tool) => <code key={tool}>{tool} </code>)}
        </p>
      ) : null}

      <section aria-label="Eval cases">
        <h3 className="mb-2 text-base font-semibold">Eval cases</h3>
        {agent.evals.length === 0 ? (
          <p className="text-sm text-warn">None: a pull request cannot show what a change to this agent does.</p>
        ) : (
          <ul className="space-y-2">
            {agent.evals.map((one) => (
              <li key={one.name} className="rounded-[var(--radius-card)] border border-line bg-panel p-4 text-sm">
                <p className="font-medium">
                  {one.name} <span className="font-normal text-muted">as {one.as}</span>
                </p>
                <p className="mt-1">{one.say ?? `the form: ${JSON.stringify(one.input)}`}</p>
                <ul className="mt-2 list-disc pl-5 text-muted">
                  {one.expect.map((expectation) => (
                    <li key={expectation}>{expectation}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-label="Prompts">
        <h3 className="mb-2 text-base font-semibold">Prompts</h3>
        {Object.entries(agent.prompts).map(([name, text]) => (
          <details key={name} className="mb-2 rounded-[var(--radius-card)] border border-line bg-panel">
            <summary className="cursor-pointer px-4 py-3 text-sm font-medium">{name}</summary>
            <pre className="whitespace-pre-wrap px-4 pb-4 font-mono text-[13px] leading-5 text-muted">{text}</pre>
          </details>
        ))}
      </section>
    </article>
  )
}

function ToolRow({ tool }: { tool: AdminTool }) {
  const bound = Object.entries(tool.bound)
  return (
    <li className="px-4 py-3 text-sm">
      <span className="font-mono text-[13px]">{tool.name}</span>{' '}
      <span className="text-sm text-faint">
        {tool.connector} · {tool.system}
      </span>
      {tool.confirmed ? <Badge tone="warn">confirmed by the person</Badge> : null}
      {bound.map(([argument, from]) => (
        <Badge key={argument} tone="muted">
          {argument} ← {from}
        </Badge>
      ))}
      {tool.refusedInRehearsal ? <Badge tone="muted">refused in a rehearsal</Badge> : null}
      <span className="mt-0.5 block text-sm text-muted">{tool.description}</span>
    </li>
  )
}

function Badge({ tone, children }: { tone: 'warn' | 'muted' | 'good' | 'bad'; children: React.ReactNode }) {
  const color = { warn: 'text-warn', muted: 'text-muted', good: 'text-good', bad: 'text-bad' }[tone]
  return <span className={`ml-2 rounded-full border border-line px-2 py-0.5 text-xs ${color}`}>{children}</span>
}

function Rehearsals() {
  const [reports, setReports] = useState<RehearsalReport[] | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  useEffect(() => {
    api.admin
      .rehearsals()
      .then((read) => setReports(read.reports))
      .catch((error: unknown) => setProblem(error instanceof ApiError ? error.message : 'No rehearsals loaded.'))
  }, [])

  if (problem) {
    return <p className="text-sm text-bad">{problem}</p>
  }
  if (!reports) {
    return <p className="text-sm text-muted">Loading…</p>
  }
  if (reports.length === 0) {
    return (
      <p className="text-sm text-muted">
        No rehearsal has been reported yet. A pull request&apos;s check sends one when it runs rehearse with this
        host&apos;s address and the organization&apos;s rehearsal token.
      </p>
    )
  }
  return (
    <ul className="space-y-3">
      {reports.map((report) => (
        <li key={`${report.receivedAt}-${report.version}`} className="rounded-[var(--radius-card)] border border-line bg-panel">
          <details open={report.held < report.cases}>
            <summary className="cursor-pointer px-4 py-3 text-sm">
              <span className={report.held === report.cases ? 'text-good' : 'text-bad'}>
                {report.held} of {report.cases} held
              </span>{' '}
              · {report.title ?? report.ref ?? short(report.version)}{' '}
              <span className="text-sm text-faint">
                at {short(report.version)}, {new Date(report.receivedAt).toLocaleString()}
              </span>
              {report.pullRequest ? (
                <a href={report.pullRequest} className="ml-2 text-sm text-accent" target="_blank" rel="noreferrer">
                  pull request
                </a>
              ) : null}
            </summary>
            {report.untested.length > 0 ? (
              <p className="px-4 text-sm text-warn">Changed with no eval cases: {report.untested.join(', ')}</p>
            ) : null}
            <ul className="space-y-2 px-4 pb-4">
              {report.results.map((result) => (
                <RehearsalCase key={`${result.agent}/${result.case}`} result={result} />
              ))}
            </ul>
            {report.routing && report.routing.length > 0 ? (
              <div className="px-4 pb-4">
                <h3 className="mb-1.5 text-sm font-medium text-faint">Routing</h3>
                <ul className="space-y-1.5 text-sm" data-testid="routing-cases">
                  {report.routing.map((one) => (
                    <li key={one.case}>
                      <span className={one.passed ? 'text-good' : 'text-bad'}>{one.passed ? 'held' : 'failed'}</span>{' '}
                      <span className="font-medium">{one.case}</span>{' '}
                      <span className="text-faint">— expected {one.expected}, {one.got}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </details>
        </li>
      ))}
    </ul>
  )
}

function RehearsalCase({ result }: { result: RehearsalResult }) {
  const would = result.calls.filter((call) => call.refused)
  return (
    <li className="rounded-[var(--radius-item)] border border-line-soft bg-canvas p-3 text-sm" data-testid="rehearsal-case">
      <p>
        <span className={result.passed ? 'text-good' : 'text-bad'}>{result.passed ? 'held' : 'failed'}</span>{' '}
        <span className="font-medium">
          {result.agent} / {result.case}
        </span>{' '}
        <span className="text-sm text-faint">as {result.as}</span>
      </p>
      <ul className="mt-1.5 text-sm">
        {result.checks.map((check) => (
          <li key={check.name} className={check.passed ? 'text-muted' : 'text-bad'}>
            {check.passed ? '✓' : '✗'} {check.name}
            {check.passed ? '' : ` — ${check.detail}`}
          </li>
        ))}
      </ul>
      {result.plan.length > 0 ? (
        <ol className="mt-1.5 list-decimal pl-5 text-sm">
          {result.plan.map((step, index) => (
            <li key={index}>{step}</li>
          ))}
        </ol>
      ) : null}
      {would.length > 0 ? (
        <ul className="mt-1.5 text-sm">
          {would.map((call, index) => (
            <li key={index}>
              would {call.would}: <code>{call.tool}</code> {JSON.stringify(call.arguments)}
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  )
}

function Deferred() {
  const [agents, setAgents] = useState<{ id: string; name: string; actions: AdminDeferredAction[] }[] | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  useEffect(() => {
    api.admin
      .deferred()
      .then((read) => setAgents(read.agents))
      .catch((error: unknown) => setProblem(error instanceof ApiError ? error.message : 'Nothing loaded.'))
  }, [])

  if (problem) {
    return <p className="text-sm text-bad">{problem}</p>
  }
  if (!agents) {
    return <p className="text-sm text-muted">Loading…</p>
  }
  if (agents.length === 0) {
    return <p className="text-sm text-muted">No agent schedules work for later.</p>
  }
  return (
    <div className="space-y-4">
      {agents.map((agent) => (
        <section key={agent.id} aria-label={agent.name}>
          <h2 className="mb-2 text-base font-semibold">{agent.name}</h2>
          {agent.actions.length === 0 ? (
            <p className="text-sm text-faint">Nothing scheduled.</p>
          ) : (
            <table className="w-full text-left text-sm [&_td]:pr-4 [&_th]:pr-4">
              <thead className="text-faint">
                <tr>
                  <th className="py-2 font-medium">Subject</th>
                  <th>Runs</th>
                  <th>Status</th>
                  <th>Scheduled by</th>
                  <th>Goal</th>
                </tr>
              </thead>
              <tbody>
                {agent.actions.map((action) => (
                  <tr key={action.id} className="border-t border-line-soft align-top">
                    <td className="py-2.5">{action.subject}</td>
                    <td>
                      {new Date(action.runAt).toLocaleString()}
                      <span className="block text-muted">{action.when}</span>
                    </td>
                    <td>
                      {action.status.toLowerCase()}
                      {action.outcome ? <span className="block text-muted">{action.outcome}</span> : null}
                    </td>
                    <td className="break-all">{action.scheduledBy}</td>
                    <td className="max-w-md whitespace-pre-wrap">{action.goal}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      ))}
    </div>
  )
}

function tokens(spend: ModelSpend): string {
  return `${(spend.inputTokens + spend.outputTokens).toLocaleString('en-US')} tokens`
}

function dollars(usd: number): string {
  return `$${usd.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function Usage() {
  const [usage, setUsage] = useState<AdminUsage | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  useEffect(() => {
    api.admin
      .usage()
      .then(setUsage)
      .catch((error: unknown) => setProblem(error instanceof ApiError ? error.message : 'Nothing loaded.'))
  }, [])

  if (problem) {
    return <p className="text-sm text-bad">{problem}</p>
  }
  if (!usage) {
    return <p className="text-sm text-muted">Loading…</p>
  }
  return (
    <div className="space-y-4 text-sm">
      <section aria-label="Account">
        <h2 className="mb-2 text-base font-semibold">Account</h2>
        <p>
          {usage.account === 'host'
            ? 'The host’s model account: the host pays.'
            : `The organization’s own ${usage.account} account, with its MODEL_API_KEY secret.`}{' '}
          At most {usage.concurrentCalls.limit} calls at once; {usage.concurrentCalls.running} running now.
        </p>
        {usage.unavailable ? <p className="mt-1 text-bad">{usage.unavailable}</p> : null}
      </section>

      <section aria-label="Budgets">
        <h2 className="mb-2 text-base font-semibold">Budgets</h2>
        {usage.budgets.length === 0 ? (
          <p className="text-sm text-faint">No budget: nothing caps what the agents spend.</p>
        ) : (
          <ul className="space-y-1">
            {usage.budgets.map((budget) => (
              <li key={budget.setBy} data-testid="budget">
                {budget.caps.join(', ')}, set {budget.setBy}
                {budget.hostAccountOnly ? ', on what the host pays for' : ''}
                {budget.reached ? <Badge tone="bad">spent {budget.reached}</Badge> : <Badge tone="good">within</Badge>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-label="Spent">
        <h2 className="mb-2 text-base font-semibold">Spent</h2>
        <p data-testid="spent">
          Today: {usage.today.calls} calls, {tokens(usage.today)}, {dollars(usage.today.usd)}. This month (UTC):{' '}
          {usage.month.calls} calls, {tokens(usage.month)}, {dollars(usage.month.usd)}.
        </p>
        {usage.unpriced.length > 0 ? (
          <p className="mt-1 text-sm text-warn">
            The host has no price for {usage.unpriced.join(', ')}, so its cost is not in these dollars.
          </p>
        ) : null}
        {usage.byAgent.length > 0 ? (
          <table className="mt-3 w-full text-left text-sm [&_td]:pr-4 [&_th]:pr-4">
            <thead className="text-faint">
              <tr>
                <th className="py-2 font-medium">Agent</th>
                <th>Model</th>
                <th>Paid by</th>
                <th>Calls</th>
                <th>Tokens</th>
                <th>Cost</th>
              </tr>
            </thead>
            <tbody>
              {usage.byAgent.map((row) => (
                <tr key={`${row.agent}/${row.model}/${row.account}`} className="border-t border-line">
                  <td className="py-2.5">{row.agent}</td>
                  <td>{row.model}</td>
                  <td>{row.account === 'host' ? 'the host' : 'the organization'}</td>
                  <td>{row.calls}</td>
                  <td>{tokens(row)}</td>
                  <td>{dollars(row.usd)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </div>
  )
}

function Connectors({ overview }: { overview: AdminOverview }) {
  return (
    <table className="w-full max-w-3xl text-left text-sm [&_td]:pr-4 [&_th]:pr-4">
      <thead className="text-faint">
        <tr>
          <th className="py-2 font-medium">Connector</th>
          <th>Reached</th>
          <th>Tools declared</th>
        </tr>
      </thead>
      <tbody>
        {overview.connectors.map((connector) => (
          <tr key={connector.name} className="border-t border-line-soft">
            <td className="py-2.5 font-mono text-[13px]">{connector.name}</td>
            <td className={connector.reached ? 'text-good' : 'text-bad'}>
              {connector.reached ? 'yes' : `no — ${connector.failure ?? 'not connected'}`}
            </td>
            <td>{connector.tools}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
