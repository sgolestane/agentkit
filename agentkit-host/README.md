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
agents/<id>/evals.yaml     optional: the cases a pull request rehearses
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
admins: [agent-operators]              # optional: directory groups that see the admin view
signIn:                                # optional: the org's identity provider (OpenID Connect)
  issuer: https://login.acme.example   # its issuer; https only
  clientId: agentkit-host              # the host's client there; a secret, if any, is OIDC_CLIENT_SECRET
  emailClaim: email                    # optional: the claim the directory looks people up by
  mcpAudience: api://agentkit          # optional: the audience MCP access tokens carry (default: the org's MCP URL)
repository:                            # optional: where changes proposed in the admin view go
  github: acme/agents                  # as pull requests, with the org's GITHUB_TOKEN secret
  path: orgs/acme                      # optional: where these files are in that repository
  base: main                           # optional: the branch pull requests are opened against
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

input:                              # optional: the form that starts its task
  schema: input.yaml                # a flat JSON Schema: string (enum, date, email), integer, number, boolean
  goal: goal.md                     # optional: the request, with {{input}}, {{field}} and
                                    # {{principal.email}} or {{principal.<field>}} filled in

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

### Starting a task from its input

An agent with an `input` can be started from its fields as well as from words.

- **In the console,** its conversation shows the form, open until the conversation has started.
- **Over MCP,** `run_<agent>` takes the form as its arguments.
- **Checked first.** Either way the input is checked, with every problem listed at once, and then
  made into the request through the goal template (without one, the request is the fields, one
  per line).
- **Plain words still work.** A request in plain words goes to the agent as it is, and its prompt
  lists the fields, so it asks for a required one the request did not give.

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
  `AGENTKIT_HOST_PUBLIC_URL` is the address people reach it at, which identity providers send
  them back to.
- Due deferred actions run every `AGENTKIT_HOST_DEFERRED_SECONDS` (default 30).
- Acme's example agents run on the host as configuration only: see
  [`agentkit-examples-acme`](../agentkit-examples-acme/README.md).

- **Who is asking.** A person signs in to an organization, and their conversations are theirs
  within it: the chat tenant is `org/email`. See [Signing in](#signing-in).
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

### Where it keeps things

By default, conversations and deferred actions are files under `AGENTKIT_HOST_DATA_DIR`
(default `data/agentkit-host`), for one process. With a database they are in Postgres:

```bash
AGENTKIT_HOST_DATABASE_URL='jdbc:postgresql://db.internal:5432/agentkit' \
AGENTKIT_HOST_DATABASE_USER=agentkit AGENTKIT_HOST_DATABASE_PASSWORD=... \
  ./mvnw -q -pl agentkit-host exec:exec
```

- **Keyed by organization.** Every row carries `org_id`: conversations, turns and attachments
  (`chat_*`), deferred actions (`deferred_action`, per organization and agent), and the versions
  each organization was loaded at (`org_version`). One organization's data is found, exported or
  deleted without reading another's.
- **The schema migrates itself** on start, under an advisory lock, so several hosts starting
  together apply each migration once (`agentkit_schema` records which have run).
- **A restart keeps the versions conversations are pinned to.** The host notes every version it
  makes current. On start it loads each checkout, then loads again the most recent earlier
  versions it was serving, taking each out of the repository's history by its commit. A version
  that was never committed (a working tree with changes) cannot be taken out again, and is let go.
- **Deferred actions are claimed once.** A claim is one conditional update, so however many hosts
  sweep, one runs an action. If the host that claimed it stops, the action is due again once the
  claim is 30 minutes old (at least once, as for every deferred store).

## Signing in

Each organization's people sign in with its own identity provider: the OpenID Connect issuer
its `org.yaml` names under `signIn`.

- **The console.** `/sign-in` sends the person to the provider: the authorization code flow, with
  PKCE, a state tied to their browser, and a nonce tied to the ID token. Back at
  `/sign-in/callback`, the ID token is checked against the keys the provider publishes: an
  asymmetric signature, the issuer, the audience (the client id), expiry and the nonce. Then:
  - the email must be one the provider says is verified;
  - the organization's directory must know it. Someone the provider knows and the directory
    does not is refused.
  - A session is then a random id in an `HttpOnly` cookie (`Secure` on https), for 8 hours.
  - Sessions are kept in memory, so a restart signs everyone out.
- **Registering the host.** Create a client with the provider, with
  `<AGENTKIT_HOST_PUBLIC_URL>/sign-in/callback` as its redirect URI. The client secret, if it has
  one, is the org's `OIDC_CLIENT_SECRET` secret.
- **MCP clients.** Each organization's agents are also at `<public URL>/orgs/<org>/mcp`, for
  callers with an access token from the same provider, following MCP's authorization spec.
  - A request without a valid token gets `401` with
    `WWW-Authenticate: Bearer resource_metadata=".../.well-known/oauth-protected-resource/orgs/<org>/mcp"`.
    That metadata names the provider, which the client signs its person in with.
  - The token must be signed by the provider, unexpired, and for this address: its audience is
    the org's MCP URL, or `signIn.mcpAudience`.
  - Its email must be in the directory. One organization per address, so a token from one
    organization's provider is never read as another's.
- **Development sign-in.** `AGENTKIT_HOST_DEV_SIGN_IN=true` replaces all of this with a page
  that takes whoever says who they are, and `/mcp` with an `X-AgentKit-User` header. The host
  then listens on `127.0.0.1` only, so it cannot be reached from another machine.
- **Trying it on one machine.** A small fake identity provider comes with the tests. Run it and
  point an organization's `signIn.issuer` at it, with `clientId: agentkit-host`:

```bash
./mvnw -q -pl agentkit-host exec:exec -Dexec.classpathScope=test \
  -Dexec.mainClass=dev.agentkit.host.auth.FakeIdentityProvider -Dexec.appArgs=8600
```

## The admin view

At `/admin`, for the people in a group `org.yaml` names under `admins` (nobody when it names
none), and linked from their console. It only reads. A change to an agent is a pull request to the
organization's repository, and this is where its effect is seen:

- **Agents, by version.** The version new conversations start on, and the earlier ones still
  serving the conversations pinned to them.
- **What each agent can do.** Its tools grouped by effect, and what the definition adds to each:
  a confirmation, arguments bound to the person, whether a rehearsal refuses it. Also its form,
  its deferred work, its eval cases and its prompts, at any loaded version.
- **Rehearsals.** The reports `rehearse` sent for the organization's pull requests: what held,
  the plans, and what each agent would have done. A report arrives at `POST /host/rehearsals/<org>`
  with the organization's `REHEARSAL_TOKEN` secret (`AGENTKIT_SECRET_<ORG>_REHEARSAL_TOKEN`).
  Reports are kept in Postgres when the host has a database, and in memory otherwise.
- **Deferred work.** Every action the organization's agents have scheduled, its goal and how it
  ended.
- **Connectors.** Whether each is reached, and how many tools it declares.

The same answers are JSON at `/host/admin`, `/host/admin/agents/<id>?version=`,
`/host/admin/rehearsals` and `/host/admin/deferred`.

### Proposing a change

An admin can edit an agent's files at the current version and propose the change: **Propose a
change** on the agent's page. The host never applies it. It opens it for review, and serves it once
it is merged, like any other commit.

- **Checked first.** The edits are applied to a copy of the version the host runs, and the copy is
  loaded against the organization's connectors: every field, reference and tool, and no grant
  without a person. A change the host would refuse is refused with every problem, and nothing is
  opened.
- **One commit on the running one.** The change is a commit whose parent is the commit the host
  runs, on a new `agentkit/…` branch, with a pull request into `repository.base`. If the base has
  moved on since, the pull request shows the conflict instead of undoing what was merged in
  between.
- **What it does, for the reviewer.** The pull request says who proposed it, why, and what changes
  for each agent it touches: tools it can now use or no longer, confirmations, bindings, audience,
  eval cases. Its checks then rehearse those agents.
- **Only an agent's own files.** `agents/<id>/…`, not `org.yaml` or a connector: those changes are
  an operator's, made in the repository.
- **Where it goes.** GitHub, per `org.yaml`'s `repository` and the org's `GITHUB_TOKEN` secret
  (`AGENTKIT_SECRET_<ORG>_GITHUB_TOKEN`, with permission to write contents and pull requests). For
  development, `AGENTKIT_HOST_PROPOSALS=local` opens a branch in the checkout's own repository
  instead.
- **When it cannot.** The host must be running a commit: a checkout with uncommitted changes has
  nothing to build on, and the page says so.

## Over MCP

The host is also an MCP server: each organization's at `/orgs/<org>/mcp` on the console's port,
for callers signed in with the organization's identity provider ([Signing in](#signing-in)). For
each agent a caller may use,
it offers `ask_<agent>`: a turn with that agent, as the caller, in their "<Agent> over MCP"
conversation. That conversation is pinned like any other and appears in their console. An
agent's `mcp.direct` tools are offered too, bound to the caller. Only tools that read may be
listed there, because nothing outside a conversation would stop a call for the person.

- **Confirmations.** When the turn needs the person's word (a confirmed tool, or a question from
  the agent), a client that supports MCP elicitation shows it to the person, not to its model.
  Their answer decides.
- **Clients without elicitation.** A client that can't be asked, or a person who dismisses the
  question, gets a reply saying what is waiting and linking to the conversation in the console.

A client that follows MCP's authorization spec finds the provider from the `401`, signs its
person in, and brings the token:

```bash
claude mcp add --transport http acme https://agents.example.com/orgs/acme/mcp
```

In development, the host serves `/mcp` instead, where a client names its person in the
`X-AgentKit-User: <org>/<email>` header. Like the development sign-in, nothing authenticates it,
and it is off unless development sign-in is on:

```bash
claude mcp add --transport http agents http://localhost:8400/mcp --header "X-AgentKit-User: acme/priya.natarajan@acme.example"
```

## Checking a pull request

A change to an organization's repository gets two checks before it is merged. The
[`agents.yml`](../.github/workflows/agents.yml) workflow runs both on Acme's repository in this one.

### Validate

`validate` loads a repository the way the host will, and lists every problem with its file and
field. Under GitHub Actions each problem is also an annotation on the pull request. It exits 1
when there are problems.

```bash
./mvnw -q -pl agentkit-host exec:exec -Dexec.mainClass=dev.agentkit.host.cli.Validate -Dexec.appArgs=$PWD/orgs/acme
```

- **Connected mode.** With `AGENTKIT_VALIDATE_CONNECT=true` and the organization's secrets,
  `validate` also reaches the connectors and assembles every agent. It then prints what each
  agent can do, grouped by effect, with its confirmations, bindings, deferred work, direct MCP
  tools, eval cases, and what a rehearsal would refuse. That summary is what a reviewer of the
  change needs to see.
- **Annotation paths.** `AGENTKIT_VALIDATE_PATH_PREFIX` prefixes the paths in annotations, for a
  repository kept in a subdirectory.

### Eval cases

An agent's `evals.yaml` holds conversations with it, and what must be true of each:

```yaml
cases:
  - name: needs-incident
    as: priya.natarajan@acme.example          # someone in the directory
    say: Give me read access to payments-prod for 2 hours.   # or input: {the agent's form}
    answers: ["It's for INC-4211."]           # what they say when the agent asks
    expect:
      - asks: true
      - calls: submit_access_request
        with: {resource_id: db-payments-prod, approver_email: dana.kim@acme.example}
      - never: grant_low_risk_access
      - answer_contains: Dana                 # ignoring case
      - judge: The answer says who will decide and that Priya will be told.
```

- **Expectations.** `calls` and `never` are about the calls the agent made, with the arguments in
  `with` if given (equal ignoring case; a list must hold every item). `asks` is whether it asked
  the person anything, in a question or at the end of its reply. `answer_contains` checks the last
  answer. `judge` has the model rule on the conversation against a rubric.
- **Checked like the rest.** The file is checked when the repository is read, a form's input
  against the form. When the agent is assembled, every tool a case names must be one of its tools.

### Rehearse

`rehearse` runs the eval cases of the agents a change touches, against the organization's real
connectors and model, as the people the cases name:

```bash
OPENROUTER_API_KEY=sk-or-... AGENTKIT_SECRET_ACME_...=... AGENTKIT_REHEARSE_SINCE=origin/main \
  ./mvnw -q -pl agentkit-host exec:exec -Dexec.mainClass=dev.agentkit.host.cli.Rehearse -Dexec.appArgs=$PWD/orgs/acme
```

- **Nothing is changed.** Every tool that could change something is refused before it reaches
  its connector, and the call is recorded: any tool not declared `read`, a read that may leave
  something behind (it asks a person something, or records what it found, or its connector does
  not say it has no side effects), and the host's own tools such as the scheduler. The model is
  told the call was not run, and carries on. Reads run, so the agent sees what is really there.
- **What the report shows.** Per case: whether its checks held, the plan for a plan-and-execute
  agent, what it asked, its answer, and what it would have done — each refused call with its
  arguments. Under GitHub Actions a case that failed is an annotation on `evals.yaml`, and the
  report is the job's summary. `AGENTKIT_REHEARSE_REPORT` also writes it as JSON.
- **In the app.** With `AGENTKIT_REHEARSE_POST_URL` (the host) and `AGENTKIT_REHEARSE_POST_TOKEN`
  (the organization's `REHEARSAL_TOKEN`), the report is sent to the host's admin view.
  `AGENTKIT_REHEARSE_PULL_REQUEST` and `AGENTKIT_REHEARSE_TITLE` say which pull request it is for.
- **Which agents.** With `AGENTKIT_REHEARSE_SINCE`, the agents whose files changed since that
  ref, or all of them when `org.yaml` or a connector changed. `AGENTKIT_REHEARSE_AGENTS` names
  them instead. A changed agent with no eval cases is a warning: the pull request cannot show what
  the change does.
- **Exit status.** 0 when every case held, 1 when one did not, 2 when nothing could be rehearsed.

## Tests

```bash
./mvnw -pl agentkit-host test                     # offline, against a real MCP connector over HTTP
AGENTKIT_TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/agentkit_test?user=agentkit' \
  ./mvnw -pl agentkit-host test -Dtest='dev.agentkit.host.store.*Test'   # the Postgres stores
AGENTKIT_HOST_LIVE=true OPENROUTER_API_KEY=sk-or-... \
  ./mvnw -pl agentkit-host test -Dtest=AHostedAgentAgainstARealModelTest
```

The Postgres tests are skipped without `AGENTKIT_TEST_DATABASE_URL`. The chat store's cases run
against the in-memory store too, so the two cannot drift apart. CI runs them against a Postgres
service container.

## Not yet

- **A turn cut off by a restart.** Conversations and their turns are kept, but a turn that was
  running or waiting for a confirmation when the host stopped is not resumed. Its live stream
  and pending confirmations are in the process that ran it, so a person should also stay on one
  instance while a turn runs.
- **Sessions across instances.** Console sessions are kept in the process, so each instance of
  the host signs people in separately, and a restart signs everyone out.
- **Caller identity to connectors.** `bind` passes identity in arguments. The signed caller
  assertion in the connector contract comes later.
