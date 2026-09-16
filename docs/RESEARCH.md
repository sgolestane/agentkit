# Research: Building a reliable unsupervised AI agent framework in Java

This document captures the research behind AgentKit's design. It surveys the
current (2025–2026) state of the art for autonomous agents and maps each finding
to a concrete requirement in the framework.

## 1. Problem statement

We want a framework for an **intelligent agent that runs unsupervised**. It:

- receives a **goal** and pursues it autonomously to completion;
- uses **progressive disclosure** so it is not overwhelmed by hundreds of tools;
- can consult a **knowledge base** (the framework provides the mechanism, not the data);
- **constructs its own context** deliberately rather than dumping everything into the prompt;
- has **memory** that survives across steps and across sessions;
- is **reliable** and **verifies its own actions** before trusting them;
- supports a **supervisor with subagents**;
- uses **Temporal** for durable execution;
- supports **Skills**.

Because it runs unsupervised, the two dominant risks are (a) *context rot* —
quality degrading as the transcript grows — and (b) *unverified action* — the
agent confidently taking a wrong, irreversible step. The architecture below is
organized around mitigating both.

## 2. Key findings from the field

### 2.1 Progressive disclosure (tools and skills)

Modern agents fail when every tool schema and every instruction is loaded up
front — it burns context and degrades tool selection. The industry answer is
**progressive disclosure**: load only names/descriptions first, then load the
full definition on demand.

- **Agent Skills** (Anthropic) use a three-tier model: at startup only each
  skill's `name` + `description` (~30–50 tokens each) are in context; the full
  `SKILL.md` loads when the skill is triggered; bundled reference files load only
  when actually needed during execution. A skill is just a directory with a
  `SKILL.md` whose YAML frontmatter carries the required `name` and `description`.
- **Tool search** (the `tool_search_tool_bm25` / `tool_search_tool_regex`
  server tools) lets a model keep a large tool library out of context by marking
  tools `defer_loading: true` and searching for the few relevant per request.
  Crucially, discovered schemas are *appended*, which preserves the prompt cache.

→ **Requirement:** a tool registry that can defer tool schemas and expose a
search tool, and a Skills subsystem that mirrors the three-tier disclosure model.
(AgentKit Phases 2 and 3.)

### 2.2 Context engineering

"Context engineering" has replaced "prompt engineering" as the central skill for
long-horizon agents: curating what enters the limited window at each step. LLM
accuracy is known to drop as context length grows even when all needed
information is present (commonly called "context rot"), so active context
management is required *before* the nominal window fills.

Two complementary in-session techniques:

- **Compaction** — summarize earlier context into a compact form when nearing a
  threshold (Anthropic ships a server-side `compact-2026-01-12` beta; the pattern
  generalizes).
- **Context editing** — *clear* stale tool results / thinking blocks rather than
  summarizing them.

→ **Requirement:** a pluggable context builder with a token budgeter, a
compaction strategy, and a context-editing strategy. (AgentKit Phase 6.)

### 2.3 Knowledge base and retrieval

An unsupervised agent must ground its work in authoritative, application-owned
information rather than only its parametric knowledge. The established pattern is
retrieval-augmented generation exposed *as a tool*: the agent decides when to
query a knowledge base and receives back the most relevant chunks. Two retrieval
strategies dominate — lexical (BM25) for exact-term and code-like queries, and
dense vector similarity for semantic matches — and production systems often
combine them. The framework must supply the *mechanism* (ingestion, chunking,
retrieval SPI, a search tool) while leaving the *data* and embedding provider to
the application.

→ **Requirement:** a `KnowledgeBase`/`Retriever` SPI with a document/chunk model,
an in-memory BM25 implementation, an `EmbeddingStore` interface for vector
search, and a `knowledge_search` tool. (AgentKit Phase 4.)

### 2.4 Memory

Compaction keeps a single session alive; **memory** persists knowledge *across*
sessions. The established pattern is a file-backed memory tool: the agent
creates/reads/updates/deletes files in a memory directory, which survives process
restarts. Research on long-horizon agents (indexed experience memory, retrieval +
generation) confirms that a durable, searchable memory materially improves
multi-session performance.

→ **Requirement:** working memory (in-session scratchpad) plus a long-term memory
store SPI with a file-backed reference implementation and a safe, path-validated
memory tool. (AgentKit Phase 5.)

### 2.5 Verification and reliability

Unsupervised agents must not trust their own first output. Effective patterns:

- **Self-verification / critic pass** — a separate check (ideally fresh context)
  that asks "does this actually satisfy the goal / rubric?".
- **Action gating** — hard-to-reverse actions (external writes, deletes, sends)
  are gated; reversibility is the criterion for promoting an action to a gated,
  dedicated tool rather than an opaque shell command.
- **Structured-output validation** — constrain and validate tool arguments and
  final outputs against a schema.
- **Retry with backoff** — transparently recover from transient failures.

→ **Requirement:** a `Verifier` SPI, guardrail/gating hooks on tool execution,
schema validation, and retry policy. (AgentKit Phase 7.)

### 2.6 Supervisor and subagents

Decoupling "the brain from the hands" and delegating independent subtasks to
subagents improves both scale and reliability. A **coordinator** agent holds the
high-level plan and delegates to subagents that each run with their own isolated
context, tools, and (optionally) model. Subagents that communicate results back
to the orchestrator outperform spawn-and-block when work is independent and
fan-out is wide.

→ **Requirement:** a supervisor that can spawn subagents from a roster, delegate
goals, run them (sequentially or in parallel), and collect results. (Phase 8.)

### 2.7 Durable execution with Temporal

Agent runs are long, stateful, and failure-prone (model timeouts, tool errors,
rate limits, process restarts). **Temporal** provides *durable execution*: the
agentic loop is a **Workflow** whose state is persisted in history and replayed
deterministically after any failure; each **model call and tool call is an
Activity** with automatic retries. Signals/Queries/Updates provide the
control-plane (inject input, read state, steer). This is a production pattern:
Temporal and multiple vendors report running long-running agent loops on durable
execution to survive process restarts and transient failures.

→ **Requirement:** a Temporal integration where the agent loop is a workflow and
the non-deterministic steps (LLM inference, tool execution) are activities,
built cleanly on top of the provider-agnostic core. (AgentKit Phase 9.)

## 3. Technology choices

| Concern | Choice | Rationale |
| --- | --- | --- |
| Language | Java 21 (LTS) | The LTS provided by the build toolchain; records, sealed types, pattern matching, and virtual threads suit this domain. Targets are advanced as newer LTS releases become available. |
| Build | Maven multi-module | Clean separation of core / provider / durability / examples; each module independently reviewable. |
| LLM provider | Anthropic Java SDK (`com.anthropic:anthropic-java`), model `claude-opus-4-8` | First-class tool use, thinking, structured outputs; kept behind an `LlmClient` SPI so the core stays provider-agnostic. |
| Durable execution | Temporal Java SDK | Industry-standard durable execution for agent loops. |
| Testing | JUnit 5, AssertJ, Mockito | Standard, fast, mock-friendly — lets us test the core with fakes and no network. |

## 4. Architectural principles

1. **Provider-agnostic core.** All LLM specifics sit behind an `LlmClient` SPI.
   The core depends on no vendor SDK, so it is unit-testable with fakes.
2. **Everything is progressive.** Tools and skills disclose in tiers; context is
   assembled, budgeted, compacted, and edited — never dumped.
3. **Verify before trusting.** Reliability is a first-class subsystem, not an
   afterthought: gating, verification, validation, and retries wrap the loop.
4. **Durability is an integration, not a core dependency.** The same agent loop
   runs in-process or as a Temporal workflow; the core never imports Temporal.
5. **Small, composable SPIs.** `Tool`, `Skill`, `KnowledgeBase`, `MemoryStore`,
   `Verifier`, `ContextStrategy`, `LlmClient` are independent extension points.

## 5. Sources

- Anthropic — *Equipping agents for the real world with Agent Skills*
- Anthropic — *Building agents with the Claude Agent SDK*
- Anthropic — *Introducing advanced tool use on the Claude Developer Platform* (tool search)
- Anthropic — *Effective context engineering for AI agents* / context-editing & compaction docs
- Anthropic — *Scaling Managed Agents: decoupling the brain from the hands* (supervisor/subagents)
- Temporal — *Durable Execution meets AI: why Temporal is ideal for AI agents*
- Temporal — `temporalio/samples-java`
- Anthropic Java SDK — `anthropics/anthropic-sdk-java` (`com.anthropic:anthropic-java`)
- Agent Skills open standard — `SKILL.md` format and progressive disclosure
- Research on long-horizon memory and context compaction (LOCA-bench, Memex(RL), ACON, and related 2025–2026 work)
