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
pattern: chat                       # the only pattern so far
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
```

A bound argument disappears from the tool's schema. Anything the model sends under that name
is replaced. If the person's record has no value for it, the call is refused and not sent.

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

## Using it

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

## Tests

```bash
./mvnw -pl agentkit-host test                     # offline, against a real MCP connector over HTTP
AGENTKIT_HOST_LIVE=true OPENROUTER_API_KEY=sk-or-... \
  ./mvnw -pl agentkit-host test -Dtest=AHostedAgentAgainstARealModelTest
```

## Not yet

- **One agent per runtime.** A conversation is not yet pinned to an agent and a version, so one
  `ChatRuntime` serves one agent.
- **Tenants.** Chat's tenant is still the person, not the organization plus the person.
- **Deferred actions** aren't configurable per agent yet.
- **No MCP front door or `validate` CLI** yet.
- **Caller identity to connectors.** `bind` passes identity in arguments. The signed caller
  assertion in the connector contract comes later.
