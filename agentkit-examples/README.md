# AgentKit examples

Runnable demos, each a `main` that wires real subsystems together against a live
model. They are here rather than in the README because a walkthrough is a
different thing from a capability — the README says what AgentKit does, these
show one way of doing it.

| Demo | What it exercises |
| --- | --- |
| `EndToEndAgent` | Most subsystems at once: disclosure, skills, knowledge, memory, verification, gating. |
| `SkillExample` | The skills subsystem end to end, with three-tier progressive disclosure. |
| `SupervisorExample` | A supervisor decomposing a briefing goal across two specialised subagents. |
| `CollaborationExample` | All three collaboration primitives — blackboard, peer messaging, refine loop. |
| `WebResearchAgent` | A client-executed search tool, so it works on any backend including Bedrock. |
| `TemporalWorkerExample` | The loop running durably as a Temporal workflow. |

## Running one

```bash
export ANTHROPIC_API_KEY=sk-ant-...

./mvnw install -DskipTests        # once, from the repo root — publishes the modules locally
./mvnw -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.EndToEndAgent
```

Two things about that command are not obvious. Run it against this module's pom
(`-f agentkit-examples/pom.xml`, or `cd agentkit-examples && ../mvnw ...`) so the
goal does not run against the aggregator root. And use **`exec:exec`**, which
forks a JVM — not `exec:java`, whose in-process classloader mishandles the AWS SDK
and fails with a spurious *"the 'sso' service module must be on the class path"*
even though the jars are present. The one-time `install` is what lets this module
resolve its sibling jars.

To run against Bedrock instead, set `AGENTKIT_BACKEND=bedrock` and see
[`docs/BACKENDS.md`](../docs/BACKENDS.md) for the rest.

## Web research (`WebResearchAgent`)

`WebResearchAgent` gives an agent a single `web_search` tool and asks it a
question worth grounding in current docs — *how to add a user to a group with the
Microsoft Graph API*. The agent searches, then answers from the results and cites
the URLs.

`web_search` is a **client-executed** tool (`WebResearchTools.webSearchTool`) over
a small `WebSearch` seam — so it works against any backend, **including Bedrock**,
where Anthropic's server-side web-search tool isn't available. The *search tool* needs no
setup — it returns offline sample results by default; set `TAVILY_API_KEY` to
search the live web via `TavilyWebSearch` (JDK HTTP client + Jackson, no vendor
SDK). Swap in another provider by implementing `WebSearch`. The *model* still
comes from `ExampleBackend`, so set `ANTHROPIC_API_KEY` (or the Bedrock variables in
[`docs/BACKENDS.md`](../docs/BACKENDS.md)) as for any other demo.

```bash
export ANTHROPIC_API_KEY=sk-ant-...                # model backend (or the Bedrock vars, see docs/BACKENDS.md)
export TAVILY_API_KEY=tvly-...                     # optional — omit for offline sample search results

./mvnw install -DskipTests                          # once — publish the modules locally
./mvnw -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.WebResearchAgent
```
