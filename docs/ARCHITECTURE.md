# AgentKit Architecture

AgentKit is a Java framework for **reliable, unsupervised, tool-using agents**.
This document explains how the pieces fit together. For the phased build history
see [`PLAN.md`](PLAN.md); for the design rationale see [`RESEARCH.md`](RESEARCH.md).

## Design tenets

1. **Provider-agnostic core.** `agentkit-core` depends on no vendor SDK. All model
   specifics sit behind the `LlmClient` SPI, so the whole framework is unit-testable
   with fakes and portable across providers.
2. **Everything is progressive.** Tools, skills, and context disclose in tiers so a
   large capability surface never floods the model's context window.
3. **Verify before trusting.** Reliability — verification, gating, retries — is a
   first-class subsystem, not an afterthought.
4. **Durability is an integration.** The same loop runs in-process or as a Temporal
   workflow; the core never imports Temporal.
5. **Fail closed on what matters, tolerate what does not.** An unknown that could
   produce a confident wrong answer is refused: `ToolGates.readOnly` denies a tool
   that never declared its effects, `JsonSchemas` refuses a Jackson annotation it
   cannot model, `StructuredOutput` fails on an unknown property in a constrained
   reply. An unknown whose worst case is a missing feature is ignored: an
   `LlmRequest` option no adapter recognises is dropped, and `DurableJson`
   tolerates a field a newer payload carries past an older worker, because failing
   there stalls a workflow Temporal will retry forever rather than surfacing
   anything.

## Modules

```
agentkit-core/           provider-agnostic core: the loop + every subsystem
agentkit-llm-anthropic/  LlmClient over the Anthropic Java SDK
agentkit-llm-bedrock/    the Anthropic adapter on Claude via Amazon Bedrock
agentkit-llm-openrouter/ LlmClient over OpenRouter's OpenAI-compatible API
agentkit-temporal/       the loop as a Temporal workflow (durable execution)
agentkit-mcp/            MCP client (stdio, streamable HTTP) and server; declared connector tools
agentkit-eval/           dataset evals: outcome, tool-use, trajectory, LLM-judge
agentkit-json/           schemas derived from Java types, replies parsed back
agentkit-otel/           OpenTelemetry traces and metrics (GenAI conventions)
agentkit-chat/           a conversation as the front door: turns, events, approvals
agentkit-chat-ui/        the console agentkit-chat serves; built into its jar
agentkit-agui/           that stream spoken as AG-UI (adapter, not a core dependency)
agentkit-host/           agents as configuration: an org's Git repo of definitions, over MCP connectors
agentkit-examples/       runnable end-to-end demos wiring it all together
agentkit-examples-acme/  an org's agents on the host as configuration, and the connectors they use
```

The Anthropic adapter carries a `ModelResolver` seam that translates the logical
model id on each request into the provider's wire id, so `agentkit-llm-bedrock`
reuses the adapter (adding only the Bedrock backend and
application-inference-profile discovery — an AWS control-plane call that maps
logical ids → account-specific profile ARNs). The seam defaults to identity, so
the first-party path is unaffected.

Only `agentkit-core` is required; the others are opt-in integrations. Dependencies
flow one way: adapters and integrations depend on core, never the reverse.

`agentkit-json` is the reason the core can stay serialization-free. Deriving a
schema from a record and parsing a reply back into it needs Jackson, and putting
that in the core would make every embedder take it — so it lives one module out,
and `agentkit-core` keeps the guarantee that matters: no vendor SDK, and no JSON
library. (Its one compile dependency is `slf4j-api`, a logging facade with no
implementation attached — the embedder chooses that.)

## The agent loop

The heart is a bounded goal→result loop (`core.agent.Agent`):

```
Goal
 │
 ▼
┌─────────────────────────────────────────────────────────────┐
│  while steps < maxSteps:                                     │
│    1. ContextStrategy.prepare(history)   ← edit / compact    │
│    2. LlmClient.generate(request)        ← model turn        │
│    3. on stop reason:                                        │
│         END_TURN, no tools  → COMPLETED                      │
│         tool_use            → run tools, append results, loop│
│         REFUSAL→REFUSED, PAUSE→PAUSED, MAX_TOKENS→TRUNCATED  │
│    4. each tool: ToolGate.evaluate → Tool.execute           │
└─────────────────────────────────────────────────────────────┘
 │
 ▼
AgentResult (stopReason, output, steps, usage)
```

A single tool failure never aborts the run — a thrown tool (or a gate denial)
becomes an error `ToolResult` the model can react to. The loop depends only on
`LlmClient` and `ToolRegistry`, which is what lets the *same* logic run durably
under Temporal.

## Subsystems (all in `agentkit-core`)

| Subsystem | Package | Key types | What it provides |
| --- | --- | --- | --- |
| **Messages** | `message` | `Message`, sealed `ContentBlock` | Provider-agnostic content model. |
| **Tools** | `tool` | `Tool`, `ToolRegistry`, `DisclosingToolRegistry` | Tool calling + progressive disclosure (deferred tools revealed via BM25 `search_tools`). |
| **LLM SPI** | `llm` | `LlmClient`, `LlmRequest`, `LlmResponse` | The one seam every provider implements. |
| **Skills** | `skill` | `Skill`, `SkillLibrary`, `Skills` | `SKILL.md` skills with three-tier disclosure (name → body → resources). |
| **Knowledge** | `knowledge` | `KnowledgeBase`, `Retriever`, `KnowledgeTools` | RAG mechanism: chunking + BM25 / vector retrieval exposed as `knowledge_search`. Data & embeddings are yours. |
| **Memory** | `memory` | `MemoryStore`, `WorkingMemory`, `MemoryTools` | Durable cross-session memory + per-run scratchpad, confined to a root (no traversal, no symlink escape). |
| **Context** | `context` | `ContextStrategy`, `ClearToolResultsEditor`, `SummarizingCompactor` | Keeps long runs within budget: edit old tool results, then summarise. |
| **Reliability** | `reliability` | `RetryingLlmClient`, `ToolGate`, `ToolGates` | Backoff retries, action gating for hard-to-reverse tools, and a read-only rehearsal mode keyed off `Tool.sideEffects()`. |
| **Verification** | `verify` | `Verifier`, `LlmVerifier`, `SelfVerifyingAgent`, `Verifiers` | Don't trust the first answer: an independent critic must pass, else retry with feedback. |
| **Supervision** | `supervisor` | `Supervisor`, `Subagent`, `Synthesizer` | Decompose a goal across subagents (parallel fan-out or model-driven `delegate`), then synthesise. |
| **Concurrency** | `concurrent` | `TaskContext` | The seam parallel primitives use to carry a caller's thread-locals (a trace, an MDC) across a thread handoff. |
| **Graphs** | `graph` | `AgentGraph`, `GraphNode`, `JoinPolicy` | A DAG declared up front: a different agent per node, data flowing along the edges, conditional branches. |
| **Goal-oriented planning** | `goap` | `GoapRunner`, `GoapPlanner`, `Action`, `Objective`, `WorldState` | Actions declare what they need and produce; the cheapest order is searched for and recomputed after every step. Single-threaded, where `graph` is concurrent. |

### How they compose

The subsystems are orthogonal seams, wired at construction (see
`agentkit-examples/EndToEndAgent`):

```
              ┌──────────────── SelfVerifyingAgent ────────────────┐
              │  runs a fresh Agent per attempt; critic must pass  │
              └───────────────────────┬────────────────────────────┘
                                      │
          ┌───────────────────────── Agent ─────────────────────────┐
          │  LlmClient (RetryingLlmClient → Anthropic)               │
          │  ContextStrategy (edit + compact)                        │
          │  ToolGate (deny destructive)                             │
          │  ToolRegistry (DisclosingToolRegistry):                  │
          │     always: knowledge_search, memory, remember, recall   │
          │     deferred: order_lookup, delete_document (gated)      │
          └──────────────────────────────────────────────────────────┘
```

A `Supervisor` sits *above* this: each `Subagent` is itself an `Agent` (built
fresh per delegation), and the supervisor fans subgoals out and synthesises.

The table above is the reliability and context core. Four further packages sit
alongside it, each with its own section in the README: `collab` (blackboard, peer
messaging, generator/critic refine loops), `planning` (plan-and-execute),
`reflect` (Reflexion-style lessons from failed attempts), and `codeexec`
(programmatic tool calling in a sandbox).

## Durable execution (`agentkit-temporal`)

The durable path reimplements the loop as a Temporal **workflow**, delegating the
two non-deterministic / side-effecting operations to **activities**:

```
        Workflow (deterministic, replayed from history)
        ─ conversation, step & usage counters, control flow ─
             │  LlmCallSpec              │  ToolInvocation
             ▼                           ▼
      ┌─────────────┐            ┌──────────────┐     Worker process
      │ LlmActivity │            │ ToolActivity │     (holds the real
      │  → LlmClient│            │  → ToolReg.  │      client & tools)
      └─────────────┘            └──────────────┘
```

- The workflow holds only replayable state plus the serializable run config and
  tool specs; the model client and tools live in the worker's activity impls.
- A completed activity is memoized in history and never re-run on replay — proven
  in-memory (no server) by forcing replay with the sticky cache disabled.
- Core stays serialization-annotation-free: the one polymorphic type, the sealed
  `ContentBlock`, is taught to Jackson by a mix-in in the Temporal module's
  `DataConverter`.

**v1 scope:** the durable loop uses a fixed tool set (progressive disclosure would
need the revealed set tracked as durable state), and context strategy / gating /
verification wrap the loop rather than living inside it. Activity retry is
at-least-once, so non-idempotent tools should set `toolMaxAttempts = 1`.

## The chat console (`agentkit-chat`, `agentkit-chat-ui`)

A conversation as the front door. Not a widget library and not an example's UI —
the loop already runs, and what was missing was the shape a person sits in front
of while it does.

```
        Conversation ── Turn* ── Step*        the transcript, durable
             │                                 (QUEUED → RUNNING → COMPLETED
             │                                  | FAILED | WAITING_FOR_HUMAN
             ▼                                  | CANCELLED)
        ChatRuntime ── one worker per conversation
             │  Agents.agentFor(Session) ─────► the application's Agent
             │                                  (its model, tools, gate, prompt)
             ├─► ChatObserver ──► ChatStore  the trace, on the turn
             │                └─► ChatEvents ──► SSE, replayable from a cursor
             └─► Approver ──► a person, blocking, in the transcript
```

**What it owns.** The transcript and its states; one worker per conversation, so
turns are ordered and a stop has one thread to interrupt; an `Approver` that
blocks the run and asks the person watching; an event stream a browser resumes
from a cursor rather than restarting; attachments; and the page, built into the
jar so a clean clone produces a runnable console with no npm step.

**What it deliberately does not.** It has no opinion about the model, the tools,
the gate, the trust floor or the system prompt — `Agents.agentFor` is the
application's whole contribution and it returns a builder rather than an agent so
the deployment still makes those choices. It has no domain: a ticket, a run and
an approval belong to whatever is being consoled, which is why the Workbench example
supplies its own tool surface rather than this module growing one.

**Blocking, not replaying.** A gate that needs a person calls the `Approver` on
the agent thread, and here that is the design rather than a compromise: the
alternative — end the run, decide, replay — is what the durable path does because
it has to, and it costs the run's whole context. The turn stays `RUNNING` while it
waits, because it has not ended and saying it had is a lie a console then has to
undo.

**The second channel.** `ToolResult` carries a digest the model reads *and* views
a person looks at, so a tool can hand back a hundred-row table without spending a
hundred rows of context. It goes one way: nothing folds a view back into the
conversation, and `AViewIsNeverFedBackAsContextTest` is the guard for the change
that would.

## Standards (`agentkit-agui`, and MCP Apps in `agentkit-mcp`)

Both are **adapters**, and [`STANDARDS.md`](STANDARDS.md) is the decision rather than the
implementation note: what each is, why neither became the native contract, and what would
change that answer.

The short version. AG-UI has no first-class approval — its human-in-the-loop is a
frontend-declared tool, where this framework has a gate that stops a call the model already
proposed and a person who may edit its arguments — and no word for a view, which is the
split that lets a tool return a hundred rows for a sentence of context. MCP Apps is not a
competitor to `View` but a producer of one: a typed view can be sorted, exported and
restyled because the console knows what it is, and a self-drawing page cannot, which is
exactly right for a third party's tool and wrong for your own.

Neither is a dependency of `agentkit-core`. Same rule as Temporal: durability is an
integration, and so is presentation.

## Testing strategy

Every subsystem is unit-tested against fakes — no network in the test suite. The
`LlmClient` seam makes the entire loop (in-process and durable) exercisable with a
scripted fake model; the Temporal tests run in the SDK's in-memory environment.
`agentkit-examples` carries integration tests that drive the fully-wired agent and
supervisor end-to-end against a fake, proving the subsystems compose.
