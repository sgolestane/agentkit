# Nothing the dashboard can do is missing from the chat

One line per thing `agentkit-examples-workbench`'s `WebServer` exposes, and how a person gets it in
the chat. Both consoles ship, so this is not a retirement gate — it is the evidence that
choosing the conversation costs nothing.

Derived from `WebServer`'s route table, not from memory. `BothConsolesAgreeAboutWhatHappenedTest`
holds the two to the same store, so the rows below are about *reach*, not about whether the two
agree once they get there.

## Reading

| The dashboard | In the chat | Notes |
|---|---|---|
| `GET /overview` | it is the banner, not a tool | The chat's `/api/overview` carries the same facts and the same `problems` list; the composer is disabled with the sentence when something is missing. |
| `GET /tickets` | `tickets.inbox` | Plus each ticket's verdict *and whether it is stale*, which the dashboard shows as a badge. |
| `GET /tickets/{key}` | `tickets.get` | One card, the description as filed, comments, transitions, prior runs, operator actions. |
| `GET /runs` | `runs.list` | A sortable table. |
| `GET /runs/{id}` | `runs.get` | A timeline rather than a log dump, bounded at 200 moments with what was dropped named. |
| `GET /approvals` | `approvals.list` | |
| `GET /rules` | `rules.list` | Richer: coverage, **what a rule leaves to you**, runs by outcome, when it last acted. |
| `GET /gaps` | `gaps.list` | |
| `GET /learnings` | `learnings.list` | |
| `GET /corrections` | `decisions.refusals` | |
| `GET /trusted` | `decisions.trusted` | |
| `GET /tools` | `workbench.capabilities` | Asserted equal to the dashboard's inventory by test — two consoles disagreeing about what the agent may do is the disagreement that matters most. |
| `GET /events` (SSE) | the transcript's own stream | Different shape, same job: the chat streams `ChatEvent`s for the turn you are watching rather than every run event for the tenant. See *Deliberate omissions*. |

## Doing

| The dashboard | In the chat | Notes |
|---|---|---|
| `POST /tickets/{key}/preview` | `workbench.preview` | Runs without asking; asks once the model has read somebody else's words. |
| `POST /tickets/{key}/execute` | `workbench.execute` | Always asks first. |
| `POST /tickets/{key}/triage` | `triage.ticket` | |
| `POST /triage/run` | `triage.sweep` | |
| `POST /bulk/execute` | `workbench.bulk_execute` | Same cap of 25, and it says what it did not run. |
| `POST /tickets/{key}/comment` | `alm.comment` | As the operator, recorded into the same `OperatorAction` log. Asks first. |
| `POST /tickets/{key}/transition` | `alm.transition` | Likewise. |
| `POST /approvals/{id}/approve` | `approvals.decide` with `approved: true` | |
| `POST /approvals/{id}/reject` | `approvals.decide` with `approved: false` | `standing: true` for a refusal that binds. |
| `POST /approvals/{id}/answer` | `approvals.answer` | |
| `POST /rules` | `rules.automate` | Refuses a family name that is not one, rather than saving a rule on the `unknown` bucket — see *Where the chat is stricter*. |
| `POST /rules/{id}/toggle` | `rules.toggle` | Says what pausing means in tickets, not just that it is paused. |
| `POST /corrections/lift` | `decisions.lift` | |
| `POST /trusted/revoke` | `decisions.revoke` | |
| `POST /runs/{id}/capture` | `evals.capture` | |

## Where the chat is stricter, on purpose

- **`rules.automate` refuses a family name that is not a name.** The dashboard reduces its
  input through `Spotlight.name`, so "password resets" is saved as the category `unknown` — the
  bucket for tickets triage *could not classify*. A text box with a person behind it makes that
  a typo; a tool a model calls makes it a rule that automates the least classifiable work in the
  queue. The chat refuses and says where the real family names are.
- **Everything that changes something asks first.** The dashboard's buttons are pressed by a
  person, so the press *is* the confirmation. A tool call is not, so `alm.comment`,
  `alm.transition`, `workbench.execute`, the bulk, the decisions and the rules all stop for an
  approval card. Reads and rehearsals do not.
- **The trust floor tightens after a read.** Once the model has taken in a ticket description,
  even the rehearsal asks. The dashboard has no equivalent because a dashboard has no context
  window to poison.

## Deliberate omissions

- **The tenant-wide event stream.** `GET /events` streams every run event for the tenant, which
  is what a dashboard with four live panels needs. A conversation is about one thing at a time
  and already streams that thing. Somebody who wants the firehose has the dashboard, running
  beside the chat on 8082 over the same store.
- **A screen of everything at once.** The dashboard's value is the glance — inbox, runs,
  approvals and rules in one frame. That is a genuinely different affordance and the chat does
  not try to reproduce it. This is the reason both consoles ship rather than one replacing the
  other.
- **`GET /overview` as a tool.** It answers "is this deployment wired up", which in the chat is
  a banner and a refusal sentence rather than something to ask about. Nothing is lost.

## What the chat has and the dashboard does not

- **Finding a ticket by what it says** — `tickets.search`. The dashboard has no search endpoint
  at all: its inbox is the list and you scroll it. In a conversation "the one about the reporting
  group" is the ordinary way somebody refers to a ticket, so the surface has to answer it.
- Asking a question in the middle of work (`ask_person`) and getting an answer back into the
  same run.
- Uploading a file into the conversation.
- Being able to say what you want in a sentence rather than finding the button for it —
  including the compound things a screen has no widget for: *"triage the inbox, preview the two
  you think you can handle, and tell me what you would say."*

## Scored against a real model

**#383, run 2026-09-05 against `anthropic/claude-sonnet-4.5` via OpenRouter.** The one acceptance
criterion of #353 that no branch could meet: *the chat path scores no worse than the dashboard
path*. `ChatParityEvalTest` runs both arms against the same ticket in two worlds that have never
met — the dashboard arm is `Workbench.execute`/`preview` driven directly, the chat arm is a
sentence typed into the console, so the system prompt, the tool descriptions and the gate are all
inside the measurement.

Fifteen cases: eleven where a supervised run should stop before it changes anything, two where the
operator asked to *look* and not touch, and two on an automated family with the knowledge to
answer it — the only group where the world changes, and so the only group where "no worse" has
stakes. Three full sweeps, because a run against a model is a sample and one clean sweep is luck:

| run | in full agreement | differed, not worse | **worse** |
|-----|-------------------|---------------------|-----------|
| A   | 15 / 15           | 0                   | **0**     |
| B   | 13 / 15           | 2                   | **0**     |
| C   | 12 / 15           | 3                   | **0**     |

**Nought regressions in 45 case-runs**, and every difference is the same shape: the console called
`ask_person` and put the question to the operator itself, where the dashboard started a run that
then stopped for a person. Both stop, neither reaches Jira, and the operator is asked either way —
it is a different route to the same halt, and it is the conversational one, which is what a chat
console is for.

The two cases where the world actually changes agreed in all three sweeps: both arms completed,
both wrote to Jira, on `SIM-7` and `SIM-8`.

`worse()` is deliberately asymmetric and the asymmetry is the point. A chat arm that reached Jira
where the dashboard did not is a regression — the world changing on a model's reading of a
sentence. A chat arm that stopped where the dashboard proceeded is *more cautious*, and caution is
the direction this console is built to fail in, so it is reported and not failed.

Re-run it:

```
WORKBENCH_LLM=openrouter WORKBENCH_MODEL=anthropic/claude-sonnet-4.5 \
  ./mvnw -pl agentkit-examples-workbench-chat -am test -Dtest=ChatParityEvalTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`-Dworkbench.parity.cases=SIM-7,SIM-8` narrows it. Without a model it skips, loudly: a parity run
against a scripted stand-in compares two fixtures and proves they are the same fixture.

## Not covered here

**The AUTO path's triage is seeded, not run.** The two automated cases get their verdict written
straight into the store rather than earned from a live triage pass. That is one fewer model call
per case, and it keeps the measurement on the thing being compared — the run — rather than on
whether triage reached the same category twice. `WorkbenchEvalTest` is where triage itself is scored.

**Cases the simulator does not seed.** The fifteen here are its ticket set, chosen to cover each
class it produces. A deployment with its own captured cases should point `EvalCaptures` at them
and add them; the harness takes a ticket key and does not care where it came from.
