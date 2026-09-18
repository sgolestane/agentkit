# Access Desk

Temporary access, by chat. People ask for access in plain language, a policy decides who
approves, approvers decide in their own conversation, and every grant ends on its own.

Access Desk is an agent on the [agent host](../agentkit-host/README.md). It has no application
of its own. It is:
- **[`orgs/acme`](orgs/acme)**, Acme's repository of agents: an `agent.yaml`, the policy and the
  prompts;
- **two MCP connectors**: the company systems, and the access ledger, which holds the desk's rules.

The host provides the rest: the console, the MCP endpoint, sign-in, conversations, confirmations
and deferred work.

## What it does

1. **Ask.** Priya writes *"I need read access to payments-prod for 2 hours for INC-4211."* The
   agent finds the resource and reads the access policy. It then either grants low-risk access at
   once, or submits a request to the right approver, who gets a direct message.
2. **Approve.** Dana asks it *"What's waiting for me? Approve it, but only for 1 hour."* The
   decision stops for her confirmation (a card in the console, or a question from her MCP
   client), then the access is granted and Priya is told.
3. **It ends.** When access is granted, the agent schedules two deferred actions for the grant: a
   reminder 15 minutes before it expires, and revocation when it does. The host runs each when
   its time comes, with only revoke and notify tools, and for that grant only.

## Run it

Three processes, one terminal each, from the repository root after
`./mvnw -q -DskipTests install`:

```bash
COMPANY_HTTP_TOKEN=company-secret \
  ./mvnw -q -pl agentkit-examples-acme exec:exec -Dexec.mainClass=dev.agentkit.accessdesk.systems.CompanySystemsServer
```

```bash
LEDGER_TOKEN=ledger-secret COMPANY_MCP_URL=http://127.0.0.1:8130/mcp COMPANY_MCP_TOKEN=company-secret \
  ./mvnw -q -pl agentkit-examples-acme exec:exec
```

```bash
AGENTKIT_HOST_ORGS=$PWD/agentkit-examples-acme/orgs AGENTKIT_HOST_DEV_SIGN_IN=true \
AGENTKIT_SECRET_ACME_COMPANY_URL=http://127.0.0.1:8130/mcp AGENTKIT_SECRET_ACME_COMPANY_TOKEN=company-secret \
AGENTKIT_SECRET_ACME_LEDGER_URL=http://127.0.0.1:8120/mcp AGENTKIT_SECRET_ACME_LEDGER_TOKEN=ledger-secret \
OPENROUTER_API_KEY=sk-or-... ./mvnw -q -pl agentkit-host exec:exec
```

Open <http://localhost:8400> and sign in as one of the demo people:

- Priya Natarajan, engineer: `priya.natarajan@acme.example`
- Dana Kim, engineering manager: `dana.kim@acme.example`
- Sam Okafor, head of security: `sam.okafor@acme.example`

**From Claude Code:**

```bash
claude mcp add --transport http access-desk http://localhost:8400/mcp \
  --header "X-AgentKit-User: acme/dana.kim@acme.example"
```

The host offers `ask_access_desk`, a turn in that person's "Access Desk over MCP" conversation.
It also offers `my_access`, `my_requests` and `pending_approvals` directly. When a turn needs the
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
| `connectors/*.yaml` | where the connectors are, and which secrets reach them |
| `org.yaml` | the organization, its default model, and its directory |

## How it is built

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

## Tests and evals

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

## Limits

- **Demo identity:** the host's development sign-in and MCP header trust whoever says who they
  are. Real sign-in is the host's to add.
- **Fake systems:** the company systems are a stand-in. Connecting real ones means pointing
  `connectors/company.yaml` at their MCP servers, and the ledger at them too.
- **Single process each:** the ledger is a file, and the host keeps conversations and deferred
  actions in files.
