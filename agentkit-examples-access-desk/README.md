# Access Desk

Temporary access, by chat. People ask for access in plain language, a policy decides who
approves, approvers decide in their own console, and every grant ends on its own.

- **Chat:** the `agentkit-chat` console, one per person.
- **Connectors:** MCP servers.
- **MCP server:** Access Desk is one itself, so you can use it from Claude Code.

## What it does

1. **Ask.** Priya writes *"I need read access to payments-prod for 2 hours for INC-4211."* The
   agent finds the resource, reads the access policy, and either grants low-risk access at once
   or submits a request to the right approver, who gets a direct message.
2. **Approve.** Dana opens her console: *"What's waiting for me? Approve it, but only for 1 hour."*
   The decision goes through a confirmation card, the access is granted, and Priya is told.
3. **It ends.** When access is granted, the agent schedules two deferred actions for the grant: a
   reminder 15 minutes before it expires, and revocation when it does. Both run when their time
   comes, with only revoke and notify tools, for that grant only.

## Run it

```bash
./mvnw -q -DskipTests -pl agentkit-examples-access-desk -am install
OPENROUTER_API_KEY=sk-or-... ./mvnw -q -pl agentkit-examples-access-desk exec:exec
```

Then open <http://localhost:8100>. It lists a console for each demo person:

- Priya Natarajan, engineer: 8101
- Dana Kim, engineering manager: 8102
- Sam Okafor, head of security: 8103

The page also has a **demo clock**: moving it forward runs whatever deferred actions are due, so
you can watch a two-hour grant be reminded and revoked in seconds.

**From Claude Code:**

```bash
claude mcp add --transport http access-desk http://localhost:8100/mcp \
  --header "X-Access-Desk-User: priya.natarajan@acme.example"
```

Then ask Claude to get you access. `ask_access_desk` is a turn with the same agent, policy and
rules as the console, and it appears in that person's console as "Access Desk over MCP".

## What a customer changes

Everything a customer changes is a file; none of it needs a code change. The defaults live in
`src/main/resources/access-desk/`, and each environment variable replaces one:

| File | Replaced by | What it is |
|---|---|---|
| `policy.md` | `ACCESS_DESK_POLICY_FILE` | the access policy: routes, justifications, durations, reminders |
| `system-prompt.md` | `ACCESS_DESK_SYSTEM_PROMPT_FILE` | the desk's system prompt |
| `deferred-prompt.md` | `ACCESS_DESK_DEFERRED_PROMPT_FILE` | the prompt deferred actions run with |
| `connectors.json` | `ACCESS_DESK_CONNECTORS_FILE` | the MCP servers to connect, and declarations for their tools |

Other settings:

- `ACCESS_DESK_MODEL`: default `anthropic/claude-sonnet-5`.
- `ACCESS_DESK_PORT`: default 8100; the consoles use the ports after it.
- `ACCESS_DESK_DATA_DIR`: default `data/access-desk`.
- `ACCESS_DESK_USERS`: the people who get a console.
- `ACCESS_DESK_SWEEP_SECONDS`: default 15.

## How it is built

**The policy is text; the safety rules are code.** The model applies `policy.md`: which route a
request takes, what a justification must say, and which reminders to schedule. The desk's tools
(`desk/DeskTools`) enforce the rules that must hold whatever the model concludes:

- Only low-sensitivity access is granted without an approver.
- No access lasts longer than the resource's `max_hours`.
- The approver is the owner or the requester's manager, and never the requester.
- Only the named approver decides, and they may shorten a request but not lengthen it.
- The company systems' raw grant and revoke tools are never given to a model.

**Tools describe themselves.** Every tool declares its effect (read, grant, revoke, notify,
request or schedule) and which argument names who it acts on (`tools/ToolInfo`). The company
systems' MCP server sends the standard annotations, plus these declarations in `_meta`.
`connectors.json` can declare tools for servers that don't send them. A tool nobody declares is
left out.

**Deferred actions are written by the model and limited by the product** (`deferred/`).
`schedule_deferred_action` stores a goal about a subject (here, a grant) and checks its time, which
can be exact or relative to a field such as `expires_at`. When the time comes, `DeferredRunner`
runs the goal:

- **Goal:** fenced as a procedure under the product's own objective.
- **Tools:** only those that read, revoke, notify or request.
- **Gate:** refuses any call that isn't about that grant, or a notification to its holder, approver
  or owner.

`ExpiryBackstop` deterministically revokes anything still active 5 minutes after expiry, in case a
deferred action was never scheduled or failed.

**MCP.** The client is `agentkit-mcp`'s. Tool annotations are hints a server could lie in, so a
server's are acted on only when `connectors.json` marks it `"trustAnnotations": true`, as the
bundled company systems are. The server side lives in this app: `mcp/McpServer` implements the tool
subset of the protocol, with transports for stdio (`StdioMcpServer`) and streamable HTTP
(`HttpMcpEndpoint`).

## Tests and evals

```bash
./mvnw -pl agentkit-examples-access-desk test          # 20 offline tests, no model
```

The offline tests cover:

- **MCP:** a real stdio subprocess round trip, and the HTTP transport.
- **Desk rules:** every enforced rule.
- **Deferred actions:** the runner with a scripted model, including restarting while an action waits,
  a hostile goal, and the backstop.
- **MCP bridge:** `ask_access_desk` over MCP running a real chat turn.

```bash
ACCESS_DESK_EVAL=true OPENROUTER_API_KEY=sk-or-... \
  ./mvnw -pl agentkit-examples-access-desk test -Dtest=AccessDeskEvalTest
```

Six conversations run against a real model through `ChatRuntime`, each scored on what the ledger,
the company systems and the deferred store hold afterwards:

- a low-risk grant
- a request with a missing incident, answered in a follow-up message
- an owner routed to their manager
- an approver shortening a request
- a request over the maximum
- an attempted self-approval

## Limits

- **Demo identity:** a person is identified by their console's port and by the MCP header. Nothing
  authenticates either.
- **Fake systems:** the company systems are a stand-in, and connecting real ones means writing
  `connectors.json` entries for their MCP servers.
- **Single process:** the store and ledger are files, the runner is in-process, and nothing is shared
  across instances.
