# The agent's workbench

A web application built on one idea: instead of routing tickets *to* an autonomous agent,
the workbench is the interface **through which a human IT agent does their work** — against
a **real Jira**, with a real model, and nothing simulated in the product path.

```
Connect → Explore → Assist → Supervise → Bulk Execute → Automate
```

Every rung is a control the operator turns, and every one of them turns back.

## Run it

```bash
./mvnw -q -DskipTests -pl agentkit-examples-workbench -am install

# the ALM — a live Jira (Cloud or Data Center)
export JIRA_BASE_URL=https://yourco.atlassian.net
export JIRA_EMAIL=you@yourco.com
export JIRA_API_TOKEN=…            # id.atlassian.com → Security → API tokens
export WORKBENCH_JQL='project = IT AND resolution = EMPTY ORDER BY updated DESC'   # optional

# the model — one of:
export WORKBENCH_LLM=anthropic  ANTHROPIC_API_KEY=…
export WORKBENCH_LLM=openrouter OPENROUTER_API_KEY=… WORKBENCH_MODEL=anthropic/claude-sonnet-4.5

./mvnw -q -pl agentkit-examples-workbench exec:exec
# open http://localhost:8082    (WORKBENCH_PORT to change)
```

| Variable | Default | What it is |
| --- | --- | --- |
| `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` | — | the ALM, and the identity the agent acts as |
| `WORKBENCH_JQL` | unresolved, newest first | what the inbox is scoped to |
| `WORKBENCH_LLM`, `WORKBENCH_MODEL` | — | the model; OpenRouter needs an explicit model id |
| `WORKBENCH_PORT` | `8082` | the console |
| `WORKBENCH_DATA_DIR` | `data` | durable knowledge, trust and captured evals — delete it and the agent forgets the customer |
| `WORKBENCH_AUTOPILOT_SECONDS` | `90` | how often the autopilot sweeps while a rule is enabled |
| `WORKBENCH_EVAL_DIR` | `data/evals` | where the replay suite reads captured cases from |

The console boots without either connection and says exactly what is missing. There is no
scripted model and no in-memory ticket source: the inbox is your Jira queue, seen with the
identity you signed in with — which is the whole onboarding story. No routing changes,
no ITSM reconfiguration; connect and explore.

### No Atlassian tenant handy? Run the simulator

`JiraSimulator` is a real HTTP server answering the slice of Jira's REST API the client
speaks, in Jira Cloud's own response shapes. The app is configured exactly as for a real
Jira — only the URL differs, and nothing in the product path changes:

```bash
just jira-sim        # terminal 1 → http://localhost:8090 (JIRASIM_PORT to change)

export JIRA_BASE_URL=http://localhost:8090
export JIRA_EMAIL=you@example.com JIRA_API_TOKEN=anything
just web             # terminal 2 → http://localhost:8082
```

Fifteen seeded tickets, grouped by what they exercise:

| Tickets | |
| --- | --- |
| SIM-6, 7, 8 | guest wifi — ask once, learn, then automate the whole family |
| SIM-9, 15 | knowledge nobody has written down yet — ask a person |
| SIM-12, 13 | answerable from the ticket alone (SIM-13's answer is only in a **comment**) |
| SIM-1, 2, 4, 10, 14 | need a capability the workbench does not have |
| SIM-3, 11 | hardware, for a person — SIM-11 is already In Progress |
| SIM-5 | a prompt injection in the description |

Writes really change simulator state (comments append, transitions move status), so a
supervised run reads back what it did — and an issue reads at `/browse/SIM-6`, the one page
real Jira has and the API alone does not, so "where can I see the comment the agent wrote?" has
an answer in the demo too. State resets on restart.

## Or as a conversation

The same workbench with a chat console instead of this dashboard lives next door in
[`agentkit-examples-workbench-chat`](../agentkit-examples-workbench-chat/README.md). Nothing here
changes — it depends on this module rather than forking it, and `just chat` starts **both**
front doors over one `WorkbenchStore`, so a run you start in the conversation is in this
dashboard's list and an approval you press here settles that run.

```bash
cd ../agentkit-examples-workbench-chat && just chat   # chat on 8083, this on 8082
```

Which to reach for, and a line-by-line account of what each can do, is in
[`PARITY.md`](../agentkit-examples-workbench-chat/PARITY.md).

## What it demonstrates

| Idea | In this module |
| --- | --- |
| "How would the agent handle this ticket?" | **Preview** — a rehearsal run: reads execute, every write is refused by the gate and lands in the plan instead. |
| Supervised execution | **Let the agent handle it** — every ALM write parks as an approval card (`GateResult.needsAPerson`); you approve, reject, or watch. |
| Ask the human, mid-run | The `ask_human` tool parks the run as a **question**; your answer resumes it. |
| Answers become knowledge | Every answer is recorded to a file-backed **lesson book** (`WORKBENCH_DATA_DIR`, default `data/`) and injected — fenced as advisory — into every later run's goal, so the questions decrease. |
| Triage and bulk execution | "The agent can handle N of your M open tickets": one structured-output verdict per ticket (`agentkit-json`), cached by ticket revision, with **Run all the agent can handle**. |
| Progressive automation | After supervising a category, click **Automate '&lt;category&gt;' tickets**. While the rule is enabled, an autopilot sweeps the inbox and works matching tickets in AUTO mode — writes up to MEDIUM proceed, anything higher (including any tool without a declared policy) still parks. The rule's row says what it covers and what it has done; pause it and supervision is back. |
| Capability discovery | `report_capability_gap` records what a real ticket needed and could not get, ticket attached, in the **Capability requests** tab. |
| Make the human agent more productive first | **Handle it yourself** — a reply box and the transitions the ALM will currently accept, on every ticket pane. Written to Jira as the signed-in identity, needing no approval because it is already the operator's own action, and recorded apart from runs so the trail answers "did the agent do this, or did I?". Needs the ALM and no model: the console is a usable Jira client with nothing else configured, which is exactly the deployment where somebody is doing all of it by hand. |
| The ITSM as a ledger behind the agent | The pane links out — **open in jira** — and the comments header names where they live and who wrote them. The workbench never keeps its own copy of a comment; it reads them back from the ALM every time. |

## The trust dial

Supervision that only ever tightens is a control nobody keeps, and one that only ever
loosens is not a control. So three settings narrow or widen what the agent may do without
asking, on two different axes — and each one is granted from the console, listed in the
**Automation** tab, and taken back from the same place.

| Control | Axis | Set from | Effect |
| --- | --- | --- | --- |
| **Automation rule** | which **tickets** the agent picks up unwatched | *Automate '&lt;category&gt;' tickets* on a ticket | the autopilot works that triage category on its own |
| **Standing approval** | which **actions** stop needing you | *stop asking me about …* when approving | supervised runs stop parking on that capability |
| **Standing refusal** | which actions must **not** happen | *keep refusing …* when rejecting | a gate denies that capability outright, quoting the reason you gave |

Two rules hold across all three, and both are enforced rather than described:

- **The ceiling.** Clearing a capability, or automating a category, clears the *ordinary*
  case. `HIGH` and above still park — so an argument that escalates a routine call reaches
  a person anyway, and the fallback grading for a tool with no declared policy (`HIGH`, not
  reversible, not idempotent) can never be quietly cleared.
- **Refusal outranks trust.** The standing-refusal gate is composed ahead of the
  supervisor and denies, so a capability somebody refused and meant it about stays refused
  whatever else is set. No with a reason beats yes by habit.

A rejection also teaches without binding: the reason you type is recorded against the
capability and folded, fenced and advisory, into later runs' goals — except the capability
a person has just approved, where their approval is the newer and more specific answer.
Marking it *standing* is a separate choice, because most rejections mean "not this one".

Trust is durable and tenant-scoped, in the same store as the learnings; the grant records
who made it, which is what a per-user scope would key on when one is wanted.

## Shape

```
connector/   Alm + JiraClient — the real REST API (v2, /search/jql with classic fallback),
             HTTP Basic with your email + API token, HttpTransport seam for tests
domain/      Ticket, Run, Approval (actions AND questions), OperatorAction (what the
             person did themselves), TriageVerdict, AutomationRule, CapabilityGap, Risk
tools/       ToolPolicy table + AlmTools (fenced reads, attributed writes) +
             WorkbenchTools (ask_human, report_capability_gap, save_learning); every tool
             declares Tool.boundToOneRun, since they close over one run's context
runtime/     Workbench (one runtime for preview/supervised/auto/resume), Supervisor
             (the ToolGate), Learnings (LessonBook), StandingApprovals, CorrectionBook
             (from core, for refusals), Triage, AutoPilot, AnswerBox
capture/     EvalCaptures — a finished run, snapshotted as a replayable case
sim/         JiraSimulator — the ALM over real HTTP, with a /browse page
web/         JDK HttpServer + one static page; live updates over SSE
```

Everything the model reads that somebody else wrote — ticket bodies, comments, operator
answers, recalled lessons, refusal notes — travels inside `Spotlight` fences; everything a
person decides on is recorded whole (`Approval` carries the `ToolInvocation`), and resuming
replays exactly the approved call, once, compared in the form the resumed run was shown. A
resume carries *every* approved-but-unconsumed action for the ticket, so a model that
re-walks its checklist in a different order does not park on a step already cleared.

Two things a run is refused outright, at the runtime rather than in the console, because
the console is one of three doors and bulk execution and the HTTP API are the others: a
ticket that is already **done**, and a ticket **already being worked** by another run.

## Tests

```bash
./mvnw -pl agentkit-examples-workbench test
```

Behavioural, offline, and named for what they claim: the Jira client against canned wire
responses and against the simulator over loopback HTTP, plus the runtime driven by a
scripted `LlmClient` — a preview changes nothing, a park resumes exactly once, approvals
survive a reordered resume, a question becomes a lesson the next run already knows, a rule
unlocks automation without widening what a person would have been asked about, a rejection
teaches the next run and optionally binds it, "stop asking me about this" clears a
capability but not a consequential call, a done ticket is not work, and the operator can
comment and resolve with no model configured at all.

## Evals

```bash
WORKBENCH_LLM=openrouter WORKBENCH_MODEL=anthropic/claude-sonnet-4.5 OPENROUTER_API_KEY=… \
  ./mvnw -pl agentkit-examples-workbench test -Dtest=WorkbenchEvalTest
```

The adoption ladder scored against a **real model on the real wiring** (`agentkit-eval`):
each case runs `Workbench.agentFor`/`goalFor` against a fresh `JiraSimulator` over loopback
HTTP, with world-state checks reading Jira back through the shipped client. Six cases —
preview is a rehearsal, a supervised write waits for a person, missing knowledge becomes a
question, learned knowledge forestalls the question, earned automation completes
unattended, and an instruction inside a ticket is not an order (world-checked and
LLM-judged) — plus the knowledge-aware triage flip. Skips with a stated reason when no
model is configured; a suite that scores a scripted stand-in has measured its own fixture.

### Evals captured from live runs

Every live run is a labeled example — the operator's decisions are the ground truth. Click
**Save as eval** on a finished run (or `POST /api/runs/{id}/capture`) and the product
snapshots it under `WORKBENCH_DATA_DIR/evals/`: the ticket as filed (the integration's own
comments stripped, status reset), the knowledge the run had, its mode, and the facts of how
it ended. `CapturedCasesEvalTest` then replays every case in `WORKBENCH_EVAL_DIR` (default
`data/evals`) on an empty simulator seeded with exactly that ticket, deriving checks from
the facts: a question must still be asked, supervised work must still park, a preview must
still change nothing, a completed AUTO run must still complete with the same writes and
leave the ticket in the same place. The regression suite writes itself from real work.

```bash
WORKBENCH_LLM=… WORKBENCH_EVAL_DIR=data/evals \
  ./mvnw -pl agentkit-examples-workbench test -Dtest=CapturedCasesEvalTest
```
