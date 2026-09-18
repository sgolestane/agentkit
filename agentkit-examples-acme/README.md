# Acme on the agent host

Acme's agents, as an operator would ship them: **[`orgs/acme`](orgs/acme)**, the organization's
repository of agents, and the MCP connectors those agents use. There is no application here. The
[agent host](../agentkit-host/README.md) runs the repository and provides the console, the MCP
endpoint, sign-in, conversations, confirmations and deferred work.

| Agent | What it does | Connectors |
|---|---|---|
| **Access Desk** | Temporary access under a policy: people ask, the right approver decides, and every grant ends on its own. | the company systems, and the access ledger that holds the desk's rules |
| **Onboarding** | A manager starts their new hire's onboarding from a form. The policy is settled into a plan, and each grant waits for the manager's confirmation. | the onboarding systems: HRIS, Okta, GitHub, AWS, Salesforce, Slack, Workday, IT desk |

Both agents are configuration: an `agent.yaml`, a policy and prompts. Each connector is a
separate MCP server, reached over HTTP with a bearer token.

## Access Desk

1. **Ask.** Priya writes *"I need read access to payments-prod for 2 hours for INC-4211."* The
   agent finds the resource and reads the access policy. It then either grants low-risk access at
   once, or submits a request to the right approver, who gets a direct message.
2. **Approve.** Dana asks it *"What's waiting for me? Approve it, but only for 1 hour."* The
   decision stops for her confirmation (a card in the console, or a question from her MCP
   client), then the access is granted and Priya is told.
3. **It ends.** When access is granted, the agent schedules two deferred actions for the grant: a
   reminder 15 minutes before it expires, and revocation when it does. The host runs each when
   its time comes, with only revoke and notify tools, and for that grant only.

## Onboarding

1. **Start.** Lena, a sales director, opens Onboarding in the console and fills in the form for
   Marcus, a contractor starting on 1 October. It could also be `run_onboarding` from an MCP client,
   or a request in plain words. The form becomes the request through `goal.md`.
2. **Plan.** The planner settles every condition of the policy against Marcus's record, then lists
   only what applies to him: an Okta account in `sales` and `contractors`, a Slack guest account in
   #sales, a Salesforce seat, a laptop pickup ticket in New York, two deferred actions for his end
   date, and a message to Lena. The console shows the plan as soon as it is made.
3. **Carry it out.** Each step runs with the connector's tools. Every tool that gives Marcus
   something stops for Lena's confirmation (a card in the console, or a question from her MCP
   client). A GitHub username that is not on file is obtained without asking Lena: from the hire's
   own Slack profile, then a search, then a Slack form that asks the hire.
4. **Later.** On 17 December the host reminds Lena which access will go. On 31 December it removes
   it, opens a laptop return ticket and tells her, running as the Onboarding agent with only the
   tools that read, revoke, notify or request, for Marcus only.

## Run it

Four processes, one terminal each, from the repository root after
`./mvnw -q -DskipTests install`: the three connectors, then the host.

```bash
COMPANY_HTTP_TOKEN=company-secret \
  ./mvnw -q -pl agentkit-examples-acme exec:exec -Dexec.mainClass=dev.agentkit.accessdesk.systems.CompanySystemsServer
```

```bash
LEDGER_TOKEN=ledger-secret COMPANY_MCP_URL=http://127.0.0.1:8130/mcp COMPANY_MCP_TOKEN=company-secret \
  ./mvnw -q -pl agentkit-examples-acme exec:exec
```

```bash
ONBOARDING_TOKEN=onboarding-secret \
  ./mvnw -q -pl agentkit-examples-acme exec:exec -Dexec.mainClass=dev.agentkit.onboarding.OnboardingConnector
```

```bash
AGENTKIT_HOST_ORGS=$PWD/agentkit-examples-acme/orgs AGENTKIT_HOST_DEV_SIGN_IN=true \
AGENTKIT_SECRET_ACME_COMPANY_URL=http://127.0.0.1:8130/mcp AGENTKIT_SECRET_ACME_COMPANY_TOKEN=company-secret \
AGENTKIT_SECRET_ACME_LEDGER_URL=http://127.0.0.1:8120/mcp AGENTKIT_SECRET_ACME_LEDGER_TOKEN=ledger-secret \
AGENTKIT_SECRET_ACME_ONBOARDING_URL=http://127.0.0.1:8140/mcp AGENTKIT_SECRET_ACME_ONBOARDING_TOKEN=onboarding-secret \
OPENROUTER_API_KEY=sk-or-... ./mvnw -q -pl agentkit-host exec:exec
```

The development sign-in above takes whoever says who they are, and the host then answers this
machine only. To sign in the way a customer would, with an identity provider, add `signIn` to
`org.yaml` and drop `AGENTKIT_HOST_DEV_SIGN_IN`. The host's tests include a fake provider for
trying that on one machine: see [Signing in](../agentkit-host/README.md#signing-in).

To keep conversations, deferred actions and loaded versions in Postgres rather than in files, add
`AGENTKIT_HOST_DATABASE_URL=jdbc:postgresql://...` to the host's command (see
[Where it keeps things](../agentkit-host/README.md#where-it-keeps-things)).

Open <http://localhost:8400> and sign in as one of the demo people:

- Priya Natarajan, engineer: `priya.natarajan@acme.example`
- Dana Kim, engineering manager: `dana.kim@acme.example`
- Sam Okafor, head of security: `sam.okafor@acme.example`
- Lena Ortiz, sales director: `lena.ortiz@acme.example`

The managers (Dana, Sam and Lena) are also offered Onboarding. Sam is in `agent-operators`, the
group `org.yaml` names as admins, so he also sees the admin view at <http://localhost:8400/admin>:
what each version of the agents can do, the pull requests' rehearsals, and the deferred work. From
an agent's page he can propose a change to its files. The host checks it and opens it as a pull
request on the repository `org.yaml` names, or as a local branch with `AGENTKIT_HOST_PROPOSALS=local`. The HRIS holds seven hires, in
[`onboarding/hr.json`](src/main/resources/onboarding/hr.json); a manager can onboard only their own:
Lena has Marcus Bell (W-1002), Sam has Maria Chen (W-1003), and Dana has the rest.

**From Claude Code:**

```bash
claude mcp add --transport http acme http://localhost:8400/mcp \
  --header "X-AgentKit-User: acme/dana.kim@acme.example"
```

Each agent the person may use is a tool. `ask_access_desk` is a turn in that person's "Access Desk
over MCP" conversation, and `my_access`, `my_requests` and `pending_approvals` are offered
directly. A manager also gets `ask_onboarding`, and `run_onboarding`, whose arguments are the
onboarding form. When a turn needs the
person's confirmation, the client asks them, if it supports MCP elicitation. Otherwise the reply
links to the conversation in the console.

**Check a change** to `orgs/acme` the way the host will load it:

```bash
./mvnw -q -pl agentkit-host exec:exec -Dexec.mainClass=dev.agentkit.host.cli.Validate \
  -Dexec.appArgs=$PWD/agentkit-examples-acme/orgs/acme
```

## What a customer changes

Everything a customer changes lives in `orgs/acme`, and none of it needs a code change:

| File | What it is |
|---|---|
| `agents/access-desk/policy.md` | the access policy: routes, justifications, durations, reminders |
| `agents/access-desk/system-prompt.md` | the desk's system prompt |
| `agents/access-desk/deferred-prompt.md` | the prompt deferred actions run with |
| `agents/access-desk/agent.yaml` | which tools, what is confirmed, who it acts as, deferred work, MCP reads |
| `agents/onboarding/policy.md` | the onboarding policy, conditions and all |
| `agents/onboarding/planner.md`, `executor.md` | the prompts the plan is made with, and each step is carried out with |
| `agents/onboarding/input.yaml`, `goal.md` | the form a manager fills in, and the request it becomes |
| `agents/onboarding/deferred-prompt.md` | the prompt the reminder and the removal run with |
| `agents/onboarding/agent.yaml` | plan-and-execute, managers only, confirmed grants, who asks, deferred work |
| `agents/*/evals.yaml` | the conversations a pull request rehearses, and what must be true of each |
| `connectors/*.yaml` | where the connectors are, and which secrets reach them |
| `org.yaml` | the organization, its default model, and its directory |

## How it is built

### Access Desk

**The policy is text; the safety rules are code.** The model applies `policy.md`: which route a
request takes, what a justification must say, and which reminders to schedule. The ledger's tools
([`DeskTools`](src/main/java/dev/agentkit/accessdesk/desk/DeskTools.java), served by
[`AccessLedgerConnector`](src/main/java/dev/agentkit/accessdesk/ledger/AccessLedgerConnector.java))
enforce the rules that must hold whatever the model concludes:

- Only low-sensitivity access is granted without an approver.
- No access lasts longer than the resource's `max_hours`.
- The approver is the owner or the requester's manager, and never the requester.
- Only the named approver decides, and they may shorten a request but not lengthen it.
- Only a grant's holder, approver or resource owner may revoke it.

`agent.yaml` adds what the host enforces for every agent:
- **No raw grants or revocations.** The company systems' `grant_access` and `revoke_access` are
  never given to the model; only their reads and messages are.
- **Confirmation.** An approver's `decide_request` stops for their confirmation.
- **Identity.** Every ledger tool acts as the person asking. The host fills `acting_as` from
  them, and the model never sees it.
- **Scheduling permission.** Only a grant's contacts (holder, approver, owner) may schedule
  deferred work about it, because that work runs later as the desk.

**Deferred actions are written by the model and bounded by the framework.**
- The agent schedules them with `schedule_deferred_action`, relative to the grant's `expires_at`.
- When one comes due, the host runs its goal with the tools that read, revoke, notify or request,
  looks the grant up again through `get_grant`, and refuses any call that isn't about that grant
  or a message to its contacts.
- The ledger's [`ExpiryBackstop`](src/main/java/dev/agentkit/accessdesk/desk/ExpiryBackstop.java)
  revokes anything still active 5 minutes after expiry, in case a deferred action was never
  scheduled or failed.

**Connectors are trusted by declaration.**
- Every tool declares its effect and whom it acts on. Tool annotations are hints a server could
  lie in, so they count only for a connector marked `trustAnnotations`.
- The ledger is marked `authoritative`, because it enforces its own rules for what it grants.
- The contract is [`docs/MCP-CONNECTORS.md`](../docs/MCP-CONNECTORS.md).

### Onboarding

**The policy's conditions are settled while planning.** The policy branches on rehire or new,
full-time or contractor, remote or on-site, department, production access, GitHub username and
end date. `pattern: plan-execute` has the planner resolve all of it against the hire's record into
a flat list of unconditional steps. A fresh executor then carries out each step, with the agent's
tools, bindings and confirmations.

**What holds whatever the model writes:**
- **Confirmation.** Every tool that gives the hire something (an account, a seat, a laptop,
  benefits, AWS or GitHub access) stops for the manager. The host refuses to load an agent that
  would grant without a person in the loop, unless the connector enforces its own rules, as the
  access ledger does.
- **Only the hire's manager.** Each tool that grants or revokes takes `requested_by`, which the
  host binds to the person, and the onboarding systems
  ([`OnboardingSystems`](src/main/java/dev/agentkit/onboarding/OnboardingSystems.java)) refuse
  anyone but the worker's manager in the HRIS. The one other caller they accept is the agent
  itself, which is who its deferred work runs as.
- **A username never passes through the model.** `github_add_member` takes the hire's email and
  adds the GitHub account on record for them. Only the HRIS, the hire's own Slack profile or the
  hire's answer to a Slack form put one on record. A search result is only a suggestion.
- **Deferred work is about one worker.** `hris_get_worker` is how the host looks the worker up,
  both when the manager schedules the work and again when it runs. It returns their identifiers,
  their manager as contact, and the systems that hold something of theirs. Only the worker's
  manager may schedule work about them. When it runs, it may act on nobody else, and tell nobody
  but them and their manager.

## Tests and evals

**On every pull request** that touches `orgs/acme`, the [`agents.yml`](../.github/workflows/agents.yml)
workflow validates the repository against the connectors, then rehearses the eval cases of each
agent the change touches ([`evals.yaml`](orgs/acme/agents/access-desk/evals.yaml)). A rehearsal is
a real conversation with the real model. Every tool that could change something is refused and
recorded, so the pull request shows what each agent would have done. See
[Checking a pull request](../agentkit-host/README.md#checking-a-pull-request). The same, locally,
with the three connectors running:

```bash
OPENROUTER_API_KEY=sk-or-... AGENTKIT_SECRET_ACME_COMPANY_URL=http://127.0.0.1:8130/mcp ... \
  ./mvnw -q -pl agentkit-host exec:exec -Dexec.mainClass=dev.agentkit.host.cli.Rehearse \
  -Dexec.appArgs=$PWD/agentkit-examples-acme/orgs/acme
```

The rehearsal checks what an agent set out to do. The evals below also check what the systems
hold afterwards, against fresh stand-in connectors, which only a test can set up.

```bash
./mvnw -pl agentkit-examples-acme test          # offline, no model
```

- **Desk rules** (`DeskRulesTest`): every rule the ledger enforces, and a grant as a subject record.
- **Backstop** (`ExpiryBackstopTest`).
- **On the host** (`AccessDeskIsConfigurationOnTheHostTest`), with a scripted model and the
  connectors over HTTP:
  - the model's tools, and what it never gets;
  - a grant that is the person's, whatever the model sends;
  - who may schedule work about a grant;
  - a deferred revocation carried out as the desk;
  - a hostile deferred goal held to its grant.
- **MCP over stdio** (`McpStdioRoundTripTest`): the company systems as a subprocess.
- **Onboarding's systems** (`AGrantIsOnlyForTheHiresManagerTest`, `DeferredActionBoundsTest`):
  only the hire's manager, the HRIS as a subject record, and what a deferred run may not do.
- **Onboarding on the host** (`OnboardingIsConfigurationOnTheHostTest`), with a scripted model:
  - offered to managers only;
  - the form as the request, each grant confirmed and done as the manager;
  - another manager refused;
  - the offboarding scheduled by the manager and run later as the agent, with only removals.

```bash
ACCESS_DESK_EVAL=true OPENROUTER_API_KEY=sk-or-... \
  ./mvnw -pl agentkit-examples-acme test -Dtest=AccessDeskEvalTest
```

Six conversations run against a real model on the host. Each is scored on what the ledger, the
company systems and the deferred store hold afterwards:

- a low-risk grant
- a request with a missing incident, answered in a follow-up message
- an owner routed to their manager
- an approver shortening a request
- a request over the maximum
- an attempted self-approval

```bash
ONBOARDING_EVAL=true OPENROUTER_API_KEY=sk-or-... \
  ./mvnw -pl agentkit-examples-acme test -Dtest=OnboardingEvalTest
```

Seven hires are onboarded on the host, each by their manager from the form, with every grant
confirmed. Each is scored on its plan (no conditions, no skip notes, nothing that does not apply
to this hire) and on what the systems hold afterwards:

- `engineer`: remote, GitHub username on file
- `contractor`: sales, on-site, with an end date and its two deferred actions
- `rehire`: Okta reactivated, and production access sent to security as a ticket
- `github-found`: the username on the hire's Slack profile
- `github-asked`: nothing found, so the hire is asked
- `github-guessed`: a search match the hire corrects
- `github-missing`: the hire never answers, and the manager is told it is pending

`ONBOARDING_SCENARIOS="contractor rehire"` runs a subset.

## Limits

- **Demo identity:** the host's development sign-in and MCP header trust whoever says who they
  are. Real sign-in is the host's to add.
- **Fake systems:** the company systems and the onboarding systems are stand-ins. Connecting real
  ones means pointing `connectors/company.yaml` and `connectors/onboarding.yaml` at their MCP
  servers, and the ledger at them too. The onboarding systems keep their state in memory.
- **Single process each:** the ledger is a file, and the host keeps conversations and deferred
  actions in files.
