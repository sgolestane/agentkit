# agentkit-host

Agents as configuration. An organization keeps its agents in a Git repository: prompts,
policy, which tools each agent gets, what stops for a person, and which arguments belong to
the person asking rather than the model. The host loads that repository at a commit, connects
to its MCP connectors, and assembles each agent for every turn.

Code enters only through connectors ([`docs/MCP-CONNECTORS.md`](../docs/MCP-CONNECTORS.md)).
A connector enforces its own domain rules. The host enforces what applies to every agent:
- tools are chosen by what they declare;
- confirmations stop for the person;
- bound arguments are filled from the person asking;
- nothing grants without a person, unless the connector granting it is marked authoritative.

## The repository

```
org.yaml
connectors/<name>.yaml
agents/<id>/agent.yaml
agents/<id>/...            prompt files agent.yaml names
```

[`src/test/resources/repos/acme`](src/test/resources/repos/acme) is a complete example.

### `org.yaml`

```yaml
org: acme
model: anthropic/claude-sonnet-5       # for agents that name none
directory:                             # optional: who someone is
  connector: helpdesk
  tool: directory_lookup
  argument: email
```

The directory tool is called with the email of the person the host authenticated. It returns
one JSON object:
- its scalar fields become that person's facts (`principal.manager`, `principal.title`, …);
- a `groups` list becomes their groups.

With no directory, a person is their email and nothing more.

### `connectors/<name>.yaml`

```yaml
url: ${secret:HELPDESK_URL}
headers:
  Authorization: Bearer ${secret:HELPDESK_TOKEN}
timeoutSeconds: 60
trustAnnotations: true      # act on the server's readOnlyHint and side-effect hints
authoritative: false        # true: it enforces its own rules for what it grants
tools:                      # declare tools a server does not describe itself
  send_message: {effect: notify, subject: to_email}
```

- **Secrets:** `${secret:NAME}` is filled from the organization's secrets. A secret that isn't
  set stops the version from loading.
- **Local connectors:** a local `command` is refused unless the host allows local connectors
  (development only).
- **Connector outages:** a connector that is down makes only the agents using it unavailable,
  and they say why.

### `agents/<id>/agent.yaml`

```yaml
name: IT Helpdesk
description: Opens IT tickets and resets MFA for the person asking.
pattern: chat                       # or plan-execute (below)
model: anthropic/claude-sonnet-5    # optional
audience: [everyone]                # or group names from the directory

prompt:
  system: prompts/system.md         # relative to this directory, inside the repository
  policy: policy.md                 # optional; appended to the system prompt

tools:                              # by connector, then effect and/or name
  - connector: helpdesk
    effects: [read, request, notify, grant]
  - connector: ledger
    tools: [my_access, pending_approvals]

confirm:                            # stop for the person before these run
  - helpdesk/reset_mfa

bind:                               # hidden from the model, filled from the person
  helpdesk/open_ticket: {requester: principal.email}
  ledger/*: {acting_as: principal.email}

limits: {maxSteps: 12, maxTokens: 1024}

mcp:                                # optional: reads also offered directly to MCP callers
  direct: [helpdesk/directory_lookup]

deferred:                           # optional: work scheduled for later
  prompt: deferred-prompt.md        # the system prompt a deferred action runs with
  actor: access-desk                # who it acts as; bindings get this for principal.email
  subjects:                         # what it may be about, and where each is looked up
    grant: {tool: ledger/get_grant, argument: grant_id}
```

A bound argument disappears from the tool's schema. Anything the model sends under that name
is replaced. If the person's record has no value for it, the call is refused and not sent.

**Deferred work.** An agent with a `deferred` section is given `schedule_deferred_action` in
every turn.
- A person may schedule work only about a subject they are a contact of, because the work runs
  later as the agent.
- When the work comes due, it runs with the framework's bounds (`dev.agentkit.core.deferred`):
  only the agent's tools that read, revoke, notify or request, only about that subject, with the
  subject's record looked up again at that moment.
- It runs with the agent as the current version defines it: today's rules, whichever version
  scheduled it.
- The subject record a connector returns is described in
  [`docs/MCP-CONNECTORS.md`](../docs/MCP-CONNECTORS.md).

### Plan-and-execute agents

An agent with `pattern: plan-execute` makes a plan once, then carries it out a step at a time.
It suits work with many steps, where the policy's conditions are best settled before anything is
done, such as onboarding someone.

```yaml
pattern: plan-execute
prompt:
  planner: planner.md      # the plan is made with this, the policy, who is asking and the time
  executor: executor.md    # each step runs with this, who is asking and the time
  policy: policy.md
```

- **Making the plan.** The planner sees the request, the policy, the person's record and the
  names of the agent's tools. It answers with a numbered list of steps.
- **Carrying it out.** Each step runs on a fresh agent with the agent's tools, bindings and
  confirmations, exactly as in a chat turn.
- **What the person sees.** The plan appears as soon as it is made, and each step is announced as
  it starts, with its tool calls in the trace. The answer lists every step and what came of it,
  and says where the plan stopped if a step did not finish.
- **Limits.** A plan longer than 20 steps is refused before anything runs.

## Validation

A version loads whole or not at all, and every problem is listed at once, with its file and
field. That makes the list usable as a pull-request check.

**When the files are read** (`RepoLoader`):
- unknown or misspelt fields
- names
- effects
- references between files
- prompt files that are missing or outside the repository, including through symlinks

**When the connectors are reached** (`HostedAgent.assemble`):
- selectors that select nothing
- named tools that don't exist
- two connectors offering the same tool name
- confirmations and bindings that don't fit their tool
- a tool with the effect `grant` that is neither confirmed nor from an authoritative connector

## Using it as a library

```java
AgentHost host = AgentHost.open(checkout, AgentHost.Options.hosted(secrets));   // version = the commit
HostedAgent helpdesk = host.agent("helpdesk").orElseThrow();
ChatRuntime runtime = new ChatRuntime(store, events,
        helpdesk.chatAgents(Optional.of(llm), host::principal, Instant::now, self::get));
```

`chatAgents` builds the agent for each turn:
- it acts as the conversation's tenant, looked up in the directory;
- it checks the agent's audience;
- it hands the model the agent's tools, bound to that person;
- it stops confirmed tools for the person;
- it builds the system prompt from the prompt file, the policy file, the person's record
  (fenced) and the time.

## Running the host

One process serves every organization, in one console:

```bash
AGENTKIT_HOST_ORGS=$PWD/orgs AGENTKIT_HOST_DEV_SIGN_IN=true \
AGENTKIT_SECRET_ACME_HELPDESK_URL=https://... AGENTKIT_SECRET_ACME_HELPDESK_TOKEN=... \
OPENROUTER_API_KEY=sk-or-... ./mvnw -q -pl agentkit-host exec:exec
```

- `AGENTKIT_HOST_ORGS` holds one checkout per organization, each directory named for its org. Give it
  as an absolute path: `exec:exec` runs from the module's own directory.
- `AGENTKIT_SECRET_<ORG>_<NAME>` fills `${secret:NAME}` for that organization.
- The console is at http://localhost:8400 (`AGENTKIT_HOST_PORT` to change it).
- Due deferred actions run every `AGENTKIT_HOST_DEFERRED_SECONDS` (default 30).
- Access Desk runs on the host as configuration only: see its README.

- **Who is asking.** A person signs in to an organization, and their conversations are theirs
  within it: the chat tenant is `org/email`. Development sign-in (`/sign-in`) trusts whoever
  says who they are. It stands in for each organization's identity provider and is off
  unless enabled.
- **Which agent.** The console offers the agents of the organization's current version whose
  audience includes the person. A new conversation is pinned to the agent chosen and that
  version (`Conversation.Pin`).
- **Which version.** Each checkout is looked at every `AGENTKIT_HOST_RELOAD_SECONDS`
  (default 30), and a new commit becomes current.
  - A commit that doesn't load is logged, and the previous version keeps serving.
  - The last `OrgHost.RETAINED` versions stay loaded, so conversations continue with the prompt,
    policy and tools they started with.
  - A conversation whose version was let go is told to start a new one. It is never moved.
  - Pulling the checkout on merge is the deployment's job.

## Over MCP

The host is also an MCP server, at `/mcp` on the console's port. For each agent a caller may use,
it offers `ask_<agent>`: a turn with that agent, as the caller, in their "<Agent> over MCP"
conversation. That conversation is pinned like any other and appears in their console. An
agent's `mcp.direct` tools are offered too, bound to the caller. Only tools that read may be
listed there, because nothing outside a conversation would stop a call for the person.

- **Confirmations.** When the turn needs the person's word (a confirmed tool, or a question from
  the agent), a client that supports MCP elicitation shows it to the person, not to its model.
  Their answer decides.
- **Clients without elicitation.** A client that can't be asked, or a person who dismisses the
  question, gets a reply saying what is waiting and linking to the conversation in the console.

In development, a client names its person in the `X-AgentKit-User: <org>/<email>` header. Like
the development sign-in, nothing authenticates it, and it is off unless development sign-in is on:

```bash
claude mcp add --transport http agents http://localhost:8400/mcp --header "X-AgentKit-User: acme/priya.natarajan@acme.example"
```

## Checking a pull request

`validate` loads a repository the way the host will, and lists every problem with its file and
field. Under GitHub Actions each problem is also an annotation on the pull request. It exits 1
when there are problems.

```bash
./mvnw -q -pl agentkit-host exec:exec -Dexec.mainClass=dev.agentkit.host.cli.Validate -Dexec.appArgs=$PWD/orgs/acme
```

- **Connected mode.** With `AGENTKIT_VALIDATE_CONNECT=true` and the organization's secrets,
  `validate` also reaches the connectors and assembles every agent. It then prints what each
  agent can do, grouped by effect, with its confirmations, bindings, deferred work and direct MCP
  tools. That summary is what a reviewer of the change needs to see.
- **Annotation paths.** `AGENTKIT_VALIDATE_PATH_PREFIX` prefixes the paths in annotations, for a
  repository kept in a subdirectory.

## Tests

```bash
./mvnw -pl agentkit-host test                     # offline, against a real MCP connector over HTTP
AGENTKIT_HOST_LIVE=true OPENROUTER_API_KEY=sk-or-... \
  ./mvnw -pl agentkit-host test -Dtest=AHostedAgentAgainstARealModelTest
```

## Not yet

- **Real sign-in.** Only development sign-in exists; OIDC per organization comes next.
- **Caller identity to connectors.** `bind` passes identity in arguments. The signed caller
  assertion in the connector contract comes later.
