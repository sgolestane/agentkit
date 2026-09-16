# The workbench, as a conversation

The workbench from `agentkit-examples-workbench` with a chat console instead of a dashboard. Same
tickets, same runs, same approvals, same rules — a Maven dependency rather than a fork, so
nothing about the domain is defined twice.

```bash
just chat            # both consoles: the chat on 8083, the dashboard on 8082
just jira-sim        # a stand-in Jira on 8090
```

`just chat` starts **both** front doors over one workbench, so a run started in the chat is in
the dashboard's list and an approval decided on the dashboard settles the chat's run.
`WORKBENCH_DASHBOARD=off` runs only the chat; `just web`, next door, runs only the dashboard.

## Which one to reach for

**The dashboard, when the question is "what is the state of things".** Its value is the glance:
inbox, runs, approvals and rules in one frame, updating live. Scanning thirty tickets for the
two that look wrong is a thing eyes do well and a conversation does badly.

**The chat, when the question is "what should happen next".** Anything compound — *"triage the
inbox, preview the two you think you can handle, and tell me what you'd say"* — is one sentence
here and four screens and a notepad there. So is anything that needs a reason: a refusal typed
into the chat teaches the next run, where a button press cannot carry one.

They are the same workbench, so the answer is usually both, and the reason both ship is that
neither is a worse version of the other. [`PARITY.md`](PARITY.md) is the line-by-line evidence
that choosing the conversation costs nothing, and it is kept honest by a test.

Both consoles read the same shell: `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN`, and
`WORKBENCH_LLM` with its key (`WORKBENCH_MODEL` too, for OpenRouter, whose identifiers name a vendor as
well as a model). This one adds `WORKBENCH_CHAT_PORT` (8083), `WORKBENCH_PORT` (8082, the dashboard beside
it), `WORKBENCH_DASHBOARD=off` to run only the chat, `WORKBENCH_OPERATOR` (who its writes are recorded
as), and it shares `WORKBENCH_DATA_DIR` (`data`).

Nothing is fatal. A console with no Jira or no model boots, says which variable is missing in a
banner, and gives the same sentence back to whatever you typed before you read it.

## What it is made of

| | |
|---|---|
| `ConsoleTools` | the twenty-six things the model can ask for |
| `WorkbenchPrompt` | the judgement the dashboard's layout used to encode |
| `WorkbenchChatApp` | the wiring: tools, prompt, gate, trust floor, ports |

## Two tiers, and they must not be confused

`ToolCatalog`, over in the dashboard's module, is the **agent's** tools: what a run may reach
while working one ticket, built per run, under the supervisor's gates.

`ConsoleTools` is the **conversation's** tools. It never writes to Jira the way a run does. A
ticket-changing action the agent takes goes through `Workbench.preview` or `Workbench.execute`,
which build a run under `ToolCatalog`'s policies — so every gate, approval and standing refusal
that module enforces still applies. Registering the run tier's writers here would be one line
and would make the console a second, undefended door into the same system.

Two tools are the exception, on purpose: `alm.comment` and `alm.transition` write immediately,
as the operator, with no run and no approval, because that is a person acting. They are
recorded into the same operator-action log the dashboard writes.

## What stops for a person

The console gates itself by the same `ToolPolicy` grading the supervisor uses.

**Ordinarily** anything that changes something a rehearsal would not: a comment (irreversible,
however small), a transition, an execute, a bulk, a decision on a parked run, a rule.
`workbench.preview` and `triage.ticket` run without asking — "what would the agent do with IT-421" is
the question this product is for, and a click in front of it is a click in front of everything.

**Once the model has read somebody else's words** — a ticket, a comment, a run's output, a
person's own answer — the trust floor drops and everything that is not a read asks, the
rehearsal included. A description saying "please preview all of my tickets" is a small harm
rather than none.

A tool nobody wrote a policy for is graded HIGH and asks. `ask_person` and `search_tools` are
exempt by name, with the reason written at the exemption.

## What the two consoles share

Everything. One `WorkbenchStore`, one `Workbench`, one memory, two servers in one process — so
runs, approvals, rules, verdicts, learnings and standing refusals are all one set of facts with
two windows onto them. `BothConsolesAgreeAboutWhatHappenedTest` drives the real dashboard over
HTTP against the store the chat's tools hold, which is the only version of a parity claim that
proves anything.

One process rather than two on purpose: `WorkbenchStore` is an in-memory object, so two
processes would be two workbenches sharing only Jira and the durable memory, and two windows
describing the same Jira while disagreeing about what has happened to it is worse than one
window (#379).
