# Agentic IT Operations — an AgentKit example

An IT operations platform where the primary interface is conversation, the same runtime
serves a human asking and a scheduler firing, and no consequential action reaches an
external system without passing a supervisor.

Runs offline. No API key, no database, no framework.

```bash
./mvnw -q -DskipTests -pl agentkit-examples-itops -am install
./mvnw -q -pl agentkit-examples-itops exec:exec
# open http://localhost:8080
```

Ask it to `fix INC0012345`, then watch the Executions and Approvals panels. The scheduler
sweeps for new tickets every minute on its own.

To put a real model behind the same runtime: `ITOPS_LLM=anthropic ANTHROPIC_API_KEY=… `.

### Remembering why an operator said no

Rejecting an approval records what the operator wrote, keyed by the capability, and later
runs are told about it — advisory, fenced, and attributed to the person who wrote it (#329).
Without it the gate keeps making the same mistake safe and nothing makes it rarer: the next
similar ticket proposes the same action and costs the same person the same decision.

It is **off unless you name a directory**:

```
ITOPS_MEMORY_DIR=/var/lib/itops-agent/memory
```

There is no default on purpose. What is stored is replayed into every later run's goal and
is trusted because only someone who can decide an approval writes it, so it needs a
directory only this service can write to — a world-writable path (`/tmp`) lets a local user
plant a symlink and forge an advisory attributed to a named operator.

That last sentence is what the store's containment defends against, and **the containment is
Linux-only**: it descends with `openat` through `SecureDirectoryStream`, and no other JDK
returns one. So this wiring insists on it by default and refuses to start without it:

```
ITOPS_MEMORY_CONTAINMENT=pinned        # the default — refuse to start unpinned
ITOPS_MEMORY_CONTAINMENT=best-effort   # proceed anyway, e.g. developing on a Mac
```

Set the second one and you have said, in your own shell history, what you turned off. See the
`Containment` javadoc for what `best-effort` still enforces — traversal, absolute paths and
symlinked keys are all still refused; what is given up is containment against a concurrent
writer who is already inside the directory.

#### Advice, or a rule

By default a rejection is **advice**: later runs are told what you said and decide for
themselves. That is right for the common case, where rejecting means *"not this one"* —
wrong user, wrong group, wrong day.

When you mean it to keep applying, send `standing`:

```
POST /api/approvals/{id}/reject   {"by":"…","note":"…","standing":true}
```

That puts a gate in front of the whole capability, and the run stops instead of asking
again. It has to be a gate rather than a stronger sentence in the prompt: the system prompt
tells the model that a privileged change needing approval *"is not your cue to stop:
propose the change anyway"*, so advice in the user turn loses to it and the run parks
again — costing the same person the same decision, which is the thing this feature exists
to reduce. `RunRules` makes the same argument about *"if an action is refused, stop"*.

**A gate is coarser than what you wrote.** *"Never grant privileged group access from a
ticket body"* is about where the request came from; the gate can only act on the capability,
so it becomes *"not in this capability until somebody lifts it"*. Every denial says so, and
names the person who set it. Weigh that before sending `standing` — the gap shows up weeks
later, on a legitimate ticket that had nothing to do with ticket bodies.

To lift one:

```
POST /api/corrections/identity.group_membership.write/lift
```

Lifting stops the enforcement and keeps the words, so later runs are still told.

**This console is unauthenticated**, and `standing` is the setting where that matters most.
Anyone who can reach it can install a gate on a whole capability for the tenant, and anyone
who can reach it can lift one. The two endpoints are deliberately symmetric: a control with
no reachable undo is worse than one that can be turned off by the same people who could
already approve and reject anything here. Put the console behind something before pointing
it at a real directory.
Nothing else changes — which is the point, since none of the safety properties are enforced
by the model. (That path was broken until recently: `ItOpsApp` reached the client
reflectively and named a factory that does not exist, so every run with the variable set
printed one line to stderr and quietly used the stand-in. `TheRealModelPathResolvesTest`
resolves every name in that lookup now.)

## The five seeded tickets

Each one exists to demonstrate a different outcome.

| Ticket | What happens | Why |
|---|---|---|
| `INC0012345` add Alice to Finance Application Users | completes on its own | non-privileged group, below the approval threshold |
| `INC0012348` add Alice to **Production-Administrators** | parks for approval | same tool, same schema — the *argument* names a privileged group, so risk escalates to HIGH |
| `INC0012346` delete a terminated employee's account | parks for approval, resumes when approved | DESTRUCTIVE, and the approval card carries the directory's termination evidence |
| `INC0012347` replace a broken monitor | declined, ticket untouched | no capability reaches a desk |
| `INC0012349` password reset, **plus an injected instruction** | the injected action is refused | the target is named only inside the fenced ticket, so nothing established it |

## How the spec maps onto AgentKit

| The spec asks for | Built on |
|---|---|
| Tools as the only boundary | `Tool` / `ToolRegistry`; connectors are unreachable except through a tool handler |
| Progressive tool disclosure | `DisclosingToolRegistry` — the run starts with ticketing tools and `search_tools`, and finds the rest by capability |
| Supervisor / verifier | `ToolGate`, consulted by the agent loop between resolving a tool and running it |
| Deterministic policy **+** LLM verification | `Supervisor` (rules) composed with `Reviewers.model(...)` |
| Human-in-the-loop approval | `ApprovalRequest` rows plus `ExecutionRunner.resume(...)` |
| Risk metadata, contextual | `ToolPolicy` baseline, escalated by `Supervisor` from the arguments |
| Workflows as graphs | `Workflow` nodes/edges, executed by `WorkflowRunner` through the same registry and supervisor |
| Event model / audit | `Execution.Event` append-only, written by `AuditObserver` from what the runtime observed |
| Untrusted ticket text | `Spotlight` evidence fences, labelled `servicenow:INC0012345` |
| A run held to its own history | `RunHistory` (an `AgentObserver`) read live by `RunRules`, composed ahead of `Supervisor` under `ToolGates.allOf` — the shape `Conformance.holdingTo` establishes |
| Scoring the agent itself | `agentkit-eval` over `ExecutionRunner.agentFor`, skipped when no model is configured |

## Six things worth reading the code for

**The ticket is evidence, not instruction.** `IntakeWorker.goalFor` writes the platform's
instruction *outside* the fence and puts the requester's words *inside* one. Anyone who can
file a ticket can write a sentence that an agent holding identity credentials will read.

**Risk comes from the arguments, not just the tool.** `identity.add_user_to_group` is one
row in the policy table. `Employees-All` and `Production-Administrators` are the same call
to it. `Supervisor.effectiveRisk` is where they stop being the same thing — and it raises
only, never lowers.

**The screen is not allowed to read the ticket.** `Reviewers.goalAlignment` runs the goal
through `Spotlight.outsideFences` before looking for the action's target, so an action must
be explained by the operator's objective or by something a system of record actually
returned. This is the clearest answer to "why fence at all if the model can be persuaded":
the fence is what lets *deterministic* code tell the two kinds of text apart.

**Deduplication is a uniqueness constraint, not a memory.** `OpsStore.claimTicket` is one
atomic put; the execution that wins proceeds and the loser is told so. `ON CONFLICT DO
NOTHING` in Postgres. There is deliberately no `exists()` to call first, because checking
and then claiming reintroduces the race.

**Verification is a second read, not a return code.** Every remediation reads the state
back from the system that owns it. A tool reporting success is evidence that a call was
accepted, not that the world changed.

**A refusal is not a detour.** The system prompt says "if an action is refused, stop; do not
look for another route to the same effect" — a security control written as a sentence in a
prompt, which is the shape this repository rejects everywhere else. `RunRules` makes it a
gate: once a call has been refused, every later call reaching the same `ToolPolicy`
capability is denied, whatever tool it arrives through. The record is `RunHistory`, filled by
an observer and read live from inside the gate, and the whole rule is *this disposition and
no other* — `REFUSED`. A park is a person being asked and does not arm it; a tool that ran
and returned an error is not a policy decision and does not arm it either. Measured, with a
model that is refused `identity.remove_user_from_group` and then reaches for
`identity.add_user_to_group`:

|                   | second call graded | second call ran | membership changed |
| ----------------- | ------------------ | --------------- | ------------------ |
| without the gate  | MEDIUM, allowed    | yes             | yes                |
| with it           | refused            | no              | no                 |

The second call is individually legitimate — its target is named in the objective and its
grade is below the approval line. What stops it is what the run had already been told.

## What the prompt asks for, and what checks it

Five demands, three answers. `RunRules` gives the reason for each in full.

| The prompt says | Status | Where |
|---|---|---|
| If an action is refused, stop | **enforced** | `RunRules.noRouteAroundARefusal` |
| Say whether you can do the job before you start | **enforced** | `RunRules.capabilityDeclaredFirst` |
| Read before you act | checked, not enforced | `ItOpsEvalTest`, chat case — the *scheduled* path fences the ticket into the goal and correctly never calls `get_ticket`, so a gate would deny every scheduled run's first write |
| Take ownership before you change anything downstream | checked, not enforced | `ItOpsEvalTest` — `OpsStore.claimTicket` already decides this without a model, and a chat run with no ticket or a resumed run cannot satisfy the precondition, so the gate would need an "does not apply here" input |
| Verify afterwards | checked, not enforced | `ItOpsEvalTest` — a promise broken by making *no* call, which no gate can refuse; and every runtime formulation of "was it read back" either fires on every correct run or guesses which read confirms which write |

## Evals

`agentkit-eval` scores this agent against a real model, through the same
`ExecutionRunner.agentFor` the product runs — not a replica, which would drift from it
silently and in the flattering direction.

```bash
ITOPS_LLM=anthropic ANTHROPIC_API_KEY=… \
  ./mvnw -pl agentkit-examples-itops -am test -Dtest=ItOpsEvalTest
```

Without a key it **skips** and says so, rather than passing: a suite that scores the scripted
stand-in has measured its own fixture. The checks themselves are unit-tested with no model at
all in `agentkit-eval`'s `ChecksTest` — a check nobody can run in CI is a check nobody knows
works.

Two dimensions were added to `Checks` for this and are generally useful: `inOrder`, which is
a *subsequence* so "read the state back afterwards" is not satisfied by the read that came
first; and `refused` / `parkedOn` / `nothingWasRefused`, which read the runner's own
`Disposition` — a call policy refused, a call parked for a person, and a tool that ran and
returned an error are one `isError()` bit to `didNotUseTool` and three different things to a
reviewer.

## No chat console here, and why

The framework has one — `agentkit-chat`, with the Workbench example wired to it in
[`agentkit-examples-workbench-chat`](../agentkit-examples-workbench-chat/README.md). This example
does not use it, and that is a choice rather than a gap: itops is about an **unattended**
intake worker with a review queue somebody clears afterwards, and a conversation is the wrong
shape for work nobody is watching happen. Its operator console is a queue for exactly that
reason.

If you want to see the conversational shape, it is one module over, against the same
framework.

## What is not built

Called out rather than half-built, since the spec asks for abstractions that permit these
later:

- **Persistence is in-memory.** `OpsStore` has the shape a Postgres implementation needs —
  tenant-scoped everywhere, one atomic claim, an append-only event log with a per-execution
  sequence — but it is a `ConcurrentHashMap`.
- **`AGENT` and `WORKFLOW` workflow nodes throw.** They are the interesting half and they
  want nested executions. `WorkflowRunner` refuses rather than skipping them silently.
- **Document-to-workflow extraction.** The API accepts programmatically generated
  definitions and `Workflow.active` exists for review-before-enable; nothing generates them.
- **Cron.** `OpsScheduler` fires on an interval and stores the intended cron string. Note
  the claim already makes a double fire harmless, which is the property to want regardless.
- **Idempotency keys are recorded, not consumed.** `AuditObserver` derives one per call; the
  connectors here are idempotent on their own, so nothing presents them to an external API
  yet.
- **Retry policy.** `TicketProcessingRecord` carries `status` and `finishedAt` so one can be
  added; for now a finished or failed attempt counts as processed.

## A limitation in AgentKit this example ran into, and what became of it

A `ToolGate` could not suspend a run. `Agent.runTool` catches every `RuntimeException` from
a gate and converts it to an error tool-result — deliberately, so a broken gate cannot abort
a run — which meant there was no signal a gate could raise to say "stop, a human is needed".

So `Supervisor` used to enforce parking rather than request it: it raised the approval, set
a flag, and denied every later invocation in that run. The guarantee held — nothing
consequential ran without approval — but it was kept by refusing everything rather than by
stopping, so the run kept being asked what to do next, and what stopped it was the stand-in
model's good manners.

**The framework has the signal** (#101): `GateResult.needsAPerson`, with the in-process loop
ending the run at `StopReason.AWAITING_APPROVAL` and reporting what is outstanding on
`AgentResult.awaiting()`. **This example is now migrated onto it** (#157). Measured, with a
stand-in that does not take the hint and re-proposes the privileged call after each refusal:

|                              | deny-everything | `needsAPerson` |
| ---------------------------- | --------------- | -------------- |
| model turns                  | 24              | 2              |
| privileged calls proposed    | 23              | 1              |
| audit rows on the execution  | 53              | 9              |
| approval rows raised         | 1               | 1              |
| execution status             | `WAITING_FOR_APPROVAL` | `WAITING_FOR_APPROVAL` |

The last row is why this was a workaround and not a hole. The rest is what it cost: a whole
step budget spent on a decision nobody had made, and the one row a reviewer must act on
buried under 44 rows of a model being told no.

That measurement predates `RunRules`, and the `needsAPerson` column is now **3** model turns
rather than 2: `report_capability` must run before anything changes, so an uncooperative
stand-in pays for one declaration before it reaches the call it will be parked on. Once per
run, not once per refusal, which is the difference the table is about.

What the migration did **not** do is the *resume*. `ExecutionRunner.resume` still re-runs the
agent from scratch against a pre-approved request, deliberately as a fresh `Execution` row,
and nothing in the framework offers that. `ApprovalNeeded` also carries three fields where
`ApprovalRequest` carries fifteen, so the example keeps its own richer record and the two
travel side by side — keyed on the execution id, which `OpsStore` mints, rather than on the
model's call id. The flag stays too, as a backstop: `needsAPerson` reports
`allowed() == false`, so a runner that has never heard of it refuses the call and keeps
looping, and without the flag a second proposal would queue a second approval for one run.
