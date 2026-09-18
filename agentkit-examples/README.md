# AgentKit examples

Runnable demos, each a `main` that wires real subsystems together against a live
model. They are here rather than in the README because a walkthrough is a
different thing from a capability — the README says what AgentKit does, these
show one way of doing it.

| Demo | What it exercises |
| --- | --- |
| `EndToEndAgent` | Most subsystems at once: disclosure, skills, knowledge, memory, verification, gating. |
| `SkillExample` | The skills subsystem end to end, with three-tier progressive disclosure. |
| `SupervisorExample` | A supervisor decomposing a briefing goal across two specialised subagents. |
| `CollaborationExample` | All three collaboration primitives — blackboard, peer messaging, refine loop. |
| `WebResearchAgent` | A client-executed search tool, so it works on any backend including Bedrock. |
| `TemporalWorkerExample` | The loop running durably as a Temporal workflow. |
| `onboarding.OnboardingApp` | `PlanningAgent` resolving a branching policy into a flat plan, over fake Okta/Slack/GitHub tools, with evals scored on system state. |
| `routine.UnlockDeskApp` | `RoutineAgent` learning a recurring job from the model, then replaying it with no model call, and handing back what does not fit. |

## Running one

```bash
export ANTHROPIC_API_KEY=sk-ant-...

./mvnw install -DskipTests        # once, from the repo root — publishes the modules locally
./mvnw -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.EndToEndAgent
```

Two things about that command are not obvious. Run it against this module's pom
(`-f agentkit-examples/pom.xml`, or `cd agentkit-examples && ../mvnw ...`) so the
goal does not run against the aggregator root. And use **`exec:exec`**, which
forks a JVM — not `exec:java`, whose in-process classloader mishandles the AWS SDK
and fails with a spurious *"the 'sso' service module must be on the class path"*
even though the jars are present. The one-time `install` is what lets this module
resolve its sibling jars.

To run against Bedrock instead, set `AGENTKIT_BACKEND=bedrock` and see
[`docs/BACKENDS.md`](../docs/BACKENDS.md) for the rest.

## Web research (`WebResearchAgent`)

`WebResearchAgent` gives an agent a single `web_search` tool and asks it a
question worth grounding in current docs — *how to add a user to a group with the
Microsoft Graph API*. The agent searches, then answers from the results and cites
the URLs.

`web_search` is a **client-executed** tool (`WebResearchTools.webSearchTool`) over
a small `WebSearch` seam — so it works against any backend, **including Bedrock**,
where Anthropic's server-side web-search tool isn't available. The *search tool* needs no
setup — it returns offline sample results by default; set `TAVILY_API_KEY` to
search the live web via `TavilyWebSearch` (JDK HTTP client + Jackson, no vendor
SDK). Swap in another provider by implementing `WebSearch`. The *model* still
comes from `ExampleBackend`, so set `ANTHROPIC_API_KEY` (or the Bedrock variables in
[`docs/BACKENDS.md`](../docs/BACKENDS.md)) as for any other demo.

```bash
export ANTHROPIC_API_KEY=sk-ant-...                # model backend (or the Bedrock vars, see docs/BACKENDS.md)
export TAVILY_API_KEY=tvly-...                     # optional — omit for offline sample search results

./mvnw install -DskipTests                          # once — publish the modules locally
./mvnw -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.WebResearchAgent
```

## IT onboarding (`onboarding.OnboardingApp`)

The application is two pieces. `OnboardingGoal` is the only onboarding-specific one: one
onboarding policy full of conditions (rehire or new, full-time or contractor, remote or on-site,
Engineering or Sales, production access requested, GitHub username on file, termination date) plus
one hire's HRIS record. `planexecute.PlanExecuteAgent` runs it, and knows nothing about onboarding:
a planner prompt, an executor prompt, a model and a tool registry around `PlanningAgent`. `LlmPlanner` resolves every condition
while planning and emits a flat list of unconditional steps; a fresh executor carries
out each one against in-memory Okta, GitHub, AWS, Salesforce, Slack, Workday and IT-desk
tools (`OnboardingSystems`).

What planning cannot know is left to the executor. A GitHub username that is not on
file becomes one "obtain it" step, and the executor tries the hire's own Slack profile,
then a GitHub search (a match is only a suggestion the hire must confirm), then asks the
hire on Slack. `github_add_member` takes an email and adds only the account on record,
so a username never passes through the model.

A hire with a termination date gets two deferred actions, scheduled once their access is set
up: a reminder to their manager 14 days before, and on the day the removal of everything they
were given. The planner and executor write what each does from the policy and the results so far;
nothing about them is defined in code. What is fixed is what a deferred action may do when it
runs: tools declare their effect and whom they act on, and a deferred run gets only the ones that
read, revoke, notify or request, for that one worker. The bounds are `agentkit-core`'s
(`dev.agentkit.core.deferred`); `DeferredActionBoundsTest` pins that onboarding's tools fall under
them, without a model.

The policy and both prompts are files, not code, so they can be changed without a rebuild:
`src/main/resources/onboarding/{policy,planner-prompt,executor-prompt}.md` are the defaults, and
`ONBOARDING_POLICY_FILE`, `ONBOARDING_PLANNER_PROMPT_FILE` and `ONBOARDING_EXECUTOR_PROMPT_FILE`
each replace one.

### Running it

`OnboardingApp` onboards one hire, from a `key: value` HRIS record file or a bundled sample
contractor, and prints the plan, every tool call, and the deferred actions with the goal and
tools each will run with:

```bash
export OPENROUTER_API_KEY=sk-or-...
export ONBOARDING_MODEL=anthropic/claude-sonnet-5    # optional; any OpenRouter model id
export ONBOARDING_HIRE_FILE=./my-hire.properties     # optional; see src/main/resources/onboarding/sample-hire.properties
export ONBOARDING_POLICY_FILE=./my-policy.md         # optional; your own policy

./mvnw -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.onboarding.OnboardingApp
```

### Evals

The evals are tests, kept apart from the application: `OnboardingFixtures` holds seven hires and
what the systems already hold, and `onboarding.evals.OnboardingEvalTest` runs each hire against a
real model and scores it with `agentkit-eval` checks, on the plan (no conditional wording, no skip
notes, branches that do not apply absent) and on the systems' final state (accounts, grants,
tickets, where a missing GitHub username came from, deferred actions and their goals). They cost
real tokens, so they are skipped unless asked for:

```bash
ONBOARDING_EVAL=true OPENROUTER_API_KEY=sk-or-... \
ONBOARDING_SCENARIOS="github-found rehire" \
    ./mvnw -f agentkit-examples/pom.xml test -Dtest=OnboardingEvalTest
```

Leave out `ONBOARDING_SCENARIOS` to run all seven: `engineer`, `contractor`, `rehire`,
`github-found`, `github-asked`, `github-guessed` and `github-missing`.

## Account unlock desk (`routine.UnlockDeskApp`)

An IT desk unlocks ten locked-out accounts: look the person up, unlock Okta, reset MFA, tell
them, tell their manager. The same five steps every time — which is exactly the work a model
should stop being paid to rediscover. `UnlockDesk` wraps the agent in core's `RoutineAgent`:

- **The first tickets go to the model** and are recorded, with the employee's and manager's
  emails taken out as placeholders.
- **Once three in a row agree**, the rest are replayed: the same tools, in the same order, with
  this ticket's values, and no model call.
- **A ticket that does not fit stops the replay.** Gus has a hardware token, so the MFA reset
  fails; the model is handed the job *with what already ran*, opens an IT ticket and sends the
  notices without unlocking him twice. The desk then goes back to learning.

A live run against `anthropic/claude-sonnet-5`:

```
Ticket                     Path                Model calls   Tokens in/out
ana.silva@acme.example     MODEL                         5   10,367 / 476
ben.cho@acme.example       MODEL                         5   10,367 / 470
cara.nwosu@acme.example    MODEL                         5   10,410 / 483
dev.patel@acme.example     REPLAYED                      0   0 / 0
eve.martin@acme.example    REPLAYED                      0   0 / 0
finn.berg@acme.example     REPLAYED                      0   0 / 0
gus.reyes@acme.example     REPLAY_THEN_MODEL             3   6,820 / 587
hana.ito@acme.example      MODEL                         5   10,345 / 468
ivan.petrov@acme.example   MODEL                         5   10,410 / 509
jo.adams@acme.example      MODEL                         5   10,367 / 498

Model calls: 33 for 10 tickets (about 50 if every ticket had gone to the model)
```

One design choice makes it work: **the tools take structured arguments**. A notification names a
template and an address rather than carrying a sentence the model wrote. Prose differs every time
a model writes it, so a run with prose in its arguments never agrees exactly with the last one and
is never replayed — the safe outcome, and the reason to shape tools this way where the saving matters.

```bash
export OPENROUTER_API_KEY=sk-or-...
./mvnw install -DskipTests          # once, from the repo root
./mvnw -q -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.routine.UnlockDeskApp
```

`UNLOCK_DESK_MODEL` picks another OpenRouter model. `UnlockDeskTest` runs the same queue offline
with a scripted model and checks the accounts themselves: everyone unlocked exactly once, nine MFA
resets, one IT ticket, twenty notices, and forty model calls instead of sixty.

