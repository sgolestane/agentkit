# MCP connectors

A connector is an MCP server that gives agents tools over some system: a directory, a ticket
queue, an access ledger. It is the only way code enters an agent host (see
[Agent host](#where-this-is-going)): the domain rules that must hold whatever a model concludes
live in the connector, and the host enforces what is generic about tools. The host can only do
that if every tool says what it does, so that is most of this contract.

This document is the contract for a connector in any language. `agentkit-mcp` implements both
sides of it in Java: `dev.agentkit.mcp.server` for writing a connector, and `McpConnectors` /
`HttpMcpConnection` for connecting to one.

## 1. Transport

| | Status |
|---|---|
| Streamable HTTP, a JSON response per request (`application/json`) | Supported; the production transport |
| Streamable HTTP, a response on an event stream (`text/event-stream`) | Supported; events before the response are skipped |
| stdio | Supported for local development; a hosted deployment does not run connector processes |
| Elicitation: the server asks the client's person something mid-call | Supported both ways: `HttpMcpEndpoint` asks through `McpCall`; `HttpMcpConnection` answers with an `Elicitor` |
| Other server-initiated requests (sampling, roots) | Refused as not supported; `ping` is answered |

The client sends `Mcp-Session-Id` back when the server issued one, `MCP-Protocol-Version` on
every request after `initialize`, and starts a new session once if the server answers `404` to
an old one. It follows `nextCursor` when listing tools.

## 2. Declaring what a tool does

Every rule the host applies to tools is written against a **declaration**: the system the tool
acts on, its **effect**, and the argument naming **whom** it acts on. For example, a deferred
action may only use tools that read, revoke, notify or request, and only about its own subject.
A tool with no declaration is **left out**, because an undeclared tool could do anything.

A connector declares each tool in the tool's `_meta` in its `tools/list` result:

```json
{
  "name": "revoke_grant",
  "description": "Revoke a grant now.",
  "inputSchema": { "type": "object", "properties": { "grant_id": { "type": "string" } } },
  "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": true },
  "_meta": {
    "dev.agentkit/effect": "revoke",
    "dev.agentkit/system": "access-desk",
    "dev.agentkit/subject": "grant_id"
  }
}
```

| Key | Required | Value |
|---|---|---|
| `dev.agentkit/effect` | yes | `read`, `grant`, `revoke`, `notify`, `request` or `schedule` (see below) |
| `dev.agentkit/system` | no | The system as a person would name it (`okta`, `crm`). Defaults to the connector's name. |
| `dev.agentkit/subject` | no | The argument that names who or what the tool acts on (an email, a grant id). Leave it out only for a tool that acts on nobody in particular. |

The effects, as `ToolEffect` defines them:

- **read**: looks something up, or asks someone for information. Changes nothing anybody holds.
- **grant**: gives someone something, such as access, a seat, equipment or an enrollment.
- **revoke**: takes away something someone holds.
- **notify**: tells a person something.
- **request**: records a request, or asks another team to do something.
- **schedule**: schedules work for later.

Send the standard annotations too, derived from the declaration: `readOnlyHint` for `read`,
`destructiveHint` for `revoke`, and `idempotentHint` when calling twice is harmless. Clients that
don't know these keys still get something useful from them. `McpServer` does this for you.

### Where a declaration comes from on the client

For each field, the first of these that gives a value wins:

1. **The operator's connectors file.** It covers a third-party server that doesn't describe itself,
   and it lets an operator override one that does.
2. **The server's `_meta` keys.**
3. **A `readOnlyHint: true`**, only for a server the file marks `"trustAnnotations": true`. That
   declares a `read` and nothing more.

The same trust flag governs side effects. A trusted server's annotations are acted on
(`McpTool.trustingAnnotations`). Any other server's tools stay `UNKNOWN`, which means a rehearsal
won't run them and a retry won't repeat them.

## 3. The connectors file

```json
{
  "servers": [
    {
      "name": "ledger",
      "url": "https://ledger.example.com/mcp",
      "headers": { "Authorization": "Bearer ${ledgerToken}" },
      "timeoutSeconds": 120,
      "trustAnnotations": true,
      "tools": { "send_message": { "effect": "notify", "subject": "to_email" } }
    },
    {
      "name": "local-dev",
      "command": ["${java}", "-cp", "${classpath}", "com.example.DevServer"]
    }
  ]
}
```

- Each server has exactly one of `url` or `command`.
- `${name}` in a command, URL or header value is filled in by the application, so credentials are
  never written in the file.
- Server names must be unique. They become the default `system` for their tools.

## 4. Who is calling (proposed)

> **Status:** proposed. It will be implemented in the agent host, where the host first calls
> connectors on a person's behalf. Nothing in `agentkit-mcp` sends or checks it yet.

A connector that enforces rules needs to know **whom** the host is acting for. For example,
Access Desk's rule "nobody approves their own request" depends on who is asking. In a single
process, that was a constructor argument (`new DeskTools(me, …)`). Across a network the host has
to vouch for it, and the model must never be able to choose it.

Two credentials travel with every call, and they answer different questions:

| | Answers | Carried in | Checked by |
|---|---|---|---|
| **Connection credential** | Is this the agent host, acting for this org? | The HTTP request: OAuth client-credentials bearer token or mTLS, from the org's secrets | The connector's HTTP front |
| **Caller assertion** | Which person, which agent, which conversation? | `params._meta["dev.agentkit/caller"]` of each `tools/call` | The connector's tool handlers |

The caller assertion goes in `_meta` rather than a header so it is independent of the transport,
and so it is tied to the one call it authorizes. It is a compact JWS (a JWT), signed by the host
with EdDSA (Ed25519). The host publishes its keys at `/.well-known/jwks.json`, identified by `kid`.

```json
{
  "iss": "https://agents.example.com",
  "aud": "ledger",
  "org": "acme",
  "sub": "user_01J9Z…",
  "email": "priya.natarajan@acme.example",
  "agent": "access-desk",
  "agent_version": "3f9c2e1",
  "conversation": "c_7Q…",
  "turn": "t_2M…",
  "iat": 1789000000,
  "exp": 1789000060,
  "jti": "a1b2c3…"
}
```

A connector:

- **Verifies** the signature against the host's published keys, `aud` equal to its own name,
  `exp` no more than 60 seconds after `iat` and not yet passed, and `org` equal to the org its
  connection credential belongs to.
- **Takes identity only from the assertion.** An argument that names the acting person (the host
  can fill one in, hidden from the model) must match `email`/`sub`, or the call is refused.
- **May refuse to repeat a `jti`.** Tool calls that change something should.
- **Treats a missing assertion as an anonymous caller.** Only tools that are safe for anyone may
  answer it.

**Deferred actions** are carried out by the host when their time comes, with no person present.
Their assertion has `sub` set to `agent:<agent id>` and `on_behalf_of` set to whoever scheduled
the action. A connector decides what that identity may do. Access Desk's equivalent is the
`access-desk` identity, which may revoke any grant.

**Why not per-user OAuth to each connector:** the people using an agent usually have no account
in the connector. The host is the party that authenticated them. Token exchange (RFC 8693) can
replace the assertion later without changing what a connector checks.

## 5. Subject records, for deferred work

An agent can schedule work for later: a reminder before a grant expires, its revocation when it
does. When that work runs, the host bounds it by its **subject**: it may act only on what the
subject's record names, and notify only its contacts. A connector provides the record through a
read tool that the agent definition names (`deferred.subjects.<kind>: {tool, argument}`). The
host calls that tool itself, with the subject's id, and it answers with one JSON object:

```json
{
  "id": "GR-1001",
  "identifiers": ["GR-1001", "priya.natarajan@acme.example"],
  "contacts": ["dana.kim@acme.example"],
  "facts": {"status": "ACTIVE", "expires_at": "2026-09-16T17:00:00Z", "holder": "priya.natarajan@acme.example"},
  "holdings": ["GR-1001"]
}
```

| Field | Meaning |
|---|---|
| `identifiers` | Every value a tool argument may use to refer to the subject. A deferred action may act only on these. |
| `contacts` | The people a deferred action may notify, besides the subject. |
| `facts` | The record's fields. Scheduling may be relative to a time field (`relative_to: expires_at`). |
| `holdings` | Optional: what the subject holds, which a removal should cover. The host tells the model about any it left out. |

An error, or anything that is not such an object, means the subject does not exist.

## Until the caller assertion: bound arguments

Until section 4 is implemented, a connector learns who is asking from an argument the agent
definition binds, for example `bind: {ledger/*: {acting_as: principal.email}}`.
- The host hides that argument from the model and fills it in on every call, so the model cannot
  choose it.
- The connector trusts it only because the caller presented the connector's bearer token, which
  only the host holds.
- A deferred action fills it with the agent's `deferred.actor` (for example `access-desk`),
  which the connector recognises as the agent itself rather than a person.

The Access Desk ledger (`agentkit-examples-acme`, `AccessLedgerConnector`) is a
complete connector built this way.

## Where this is going

These are the extension points of the agent host. It is one application with a web console and
an MCP endpoint, and operators add agents to it as versioned definitions in Git: prompts, policy,
tools selected by declaration, confirmation gates, deferred actions. Anything a definition can't
express goes into a connector, under this contract.
