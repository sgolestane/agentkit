# AG-UI and MCP Apps: adapters, and why neither is the native contract

Two standards now cover the ground `agentkit-chat` covers. Both are adopted as **adapters**,
and this is the decision written down rather than left implicit — #359's last acceptance item
asks for exactly that: *"if either standard turns out to be the better native contract, that is
written down as a decision rather than a silent rewrite."*

Neither did. Here is why, and what would change the answer.

---

## AG-UI

**What it is.** An event protocol between an agent backend and a frontend built for it. The
client posts a `RunAgentInput` and reads Server-Sent Events back; the events are
`RUN_STARTED`, `TEXT_MESSAGE_START`/`CONTENT`/`END`, `TOOL_CALL_START`/`ARGS`/`END`/`RESULT`,
`STATE_SNAPSHOT`, `RUN_FINISHED`. CopilotKit and assistant-ui are built on it.

**What we did.** `agentkit-agui`: a translation of our stream into theirs, and one endpoint that
speaks it. A deployment can run its own console on one port and somebody's CopilotKit app on
another, against one `ChatRuntime` and one set of conversations.

**Why it is not the native contract.**

1. **It has no word for an approval.** AG-UI's answer to human-in-the-loop is that the *frontend*
   declares a tool and the agent calls it. That is a coherent design and it is the opposite of
   this framework's: here a **gate** decides a call needs a person, and the person approves,
   refuses, or *edits the arguments* of a call the model already proposed. Making AG-UI native
   would mean either giving that up or carrying it in `CUSTOM` events anyway — which is what
   the adapter does, and doing it in an adapter costs nothing while doing it natively would
   mean the framework's central safety feature lived in the protocol's escape hatch.

2. **It has no word for a view.** `ToolResult` carries a digest for the model and views for the
   person, and the split is load-bearing — it is what lets a tool return a hundred-row table
   for a sentence of context. AG-UI's nearest neighbour is state synchronisation, and a view is
   not state: it is produced once at a moment in the transcript, and a `STATE_SNAPSHOT` would
   have to either repeat it forever or appear to delete it. `CUSTOM` again.

3. **It is a wire format, and this is a Java library.** The thing `agentkit-chat` owns is a
   transcript, a worker per conversation, a blocking `Approver` and a resumable stream — none
   of which AG-UI specifies. Adopting it natively would replace the part we already agree on
   and leave every part we do not.

**What would change the answer.** If AG-UI grows a first-class approval — a request the backend
raises and the frontend answers, with edit — then the argument in (1) goes away and the case
for native gets strong, because that is the one thing we carry that its escape hatch is holding.
Worth re-reading the spec when that lands.

**What #389 found, which is the reason to read this section.** The tests here checked event
names, required fields and ordering over a real socket — *against the rules this repository
wrote down*. The gap that leaves is not theoretical. Run through `@ag-ui/client`'s own
`verifyEvents` — the protocol's state machine, written by the people who defined it — the
stream was **rejected twice**:

```
Cannot send 'STEP_FINISHED' for step "model" that was not started
Cannot send 'RUN_FINISHED' while steps are still active: running
```

`MODEL_CALL` became a lone `STEP_FINISHED`, and the `STEP_STARTED` opened by `TURN_RUNNING` was
never closed. Nobody had written down that steps come in pairs, so the adapter broke it and
every test here agreed with the adapter. **A client that rejects stops reading**, so the first
of those meant the answer arriving afterwards never reached the screen at all.

Both are fixed in `AgUiEvents`. What keeps them fixed is `aguiRecording.test.ts` in
`agentkit-chat-ui`: recordings of real runs, produced from the live server by
`TheRecordedRunsAreWhatThisServerStillSendsTest` and replayed through that SDK on every build —
`verifyEvents` for the state machine, `EventSchemas` for the fields. The Java test re-checks the
recordings against the server, so neither a stale fixture nor an unvalidated stream passes.

Worth knowing which half caught what: the **schema** accepted the broken stream happily. A lone
`STEP_FINISHED` is a valid `StepFinishedEvent`. Only the state machine knew it was wrong, which
is the concrete form of "a schema can be right while a client chokes".

A third, smaller thing came out of the same exercise: `RUN_STARTED` echoed this framework's
internal conversation id as the `threadId` rather than the one the caller sent. Not a measured
break — `@ag-ui/client` never compares them — but the field names the *client's* thread, and a
client that filed runs under it would open a second thread beside the one on screen. It echoes
the caller's now.

**What is still unproved.** No CopilotKit or assistant-ui *application* has been rendered
against this. What is proved is that the stream satisfies the SDK those applications are built
on, mechanically and on every build, which was the load-bearing part of the question.

---

## MCP Apps (SEP-1865)

**What it is.** The first official MCP extension, final 2026-01-26. A server predeclares a
`ui://` resource holding a self-contained HTML page; a tool points at it from
`_meta.ui.resourceUri`; a host fetches the resource and renders it in a sandboxed iframe, and
the page talks to the host over JSON-RPC on `postMessage`.

**What we did.** `agentkit-mcp` reads the metadata, fetches the resource, and attaches it as a
`View` of kind `mcp-app`. The console renders it in a frame that is sandboxed without
`allow-same-origin` and carries its own `default-src 'none'`, and it is handed the two
notifications its data flow needs.

**Why it is not the native contract.** It is not a competitor to `View` — it is a *producer* of
one, and that is the shape the adapter takes. A `View` is a small typed payload a renderer
draws; an MCP App is a page that draws itself. The first can be sorted, filtered, exported to
CSV, read by a screen reader and restyled by the console's own theme, because the console knows
what it is. The second cannot, because the console knows only that it is HTML.

That is not a criticism of the extension — it exists so a *third party* can ship an interface to
a host that has never heard of its domain, and for that job self-drawing HTML is exactly right.
It is the wrong default for a deployment's own tools, where the console and the tool are written
by the same people and typed views are strictly better.

So: **`View` for tools you own, MCP Apps for tools you do not.**

**What we deliberately did not implement.** The host side of the JSON-RPC bridge — `tools/call`,
`ui/open-link`, `ui/request-display-mode`, `ui/update-model-context`. Every request from the
frame is answered with JSON-RPC `-32601`, *method not found*.

Not implementing it is the safe state, not a gap to be closed carelessly: an app that can ask
this console to run a tool is an app that can ask it to run **any** tool, on a page whose other
job is approving privileged actions. The spec anticipates this — it says hosts may require
explicit user consent for UI-initiated tool calls — and consent-in-front-of-a-third-party-page
is its own design problem.

**#390 asked whether to open any of it, and the answer is no.** The refusal stays, for all four
methods, deliberately and not for want of time. The reasoning is worth writing down because the
cheapest-looking option is the one that fails:

- A consent prompt in front of an app's request puts a person in the position of judging a call
  they did not initiate, described in words the app's own server chose. That is the same shape
  as the prompt-injection surface the rest of this repository spends its effort closing, except
  the injected text arrives with a button next to it.
- `ui/open-link` looks like the safe subset and mostly is, but a link is an exfiltration channel:
  the app chooses the URL, and this console's transcript is full of things worth putting in a
  query string. It would need its own outbound policy to be worth the surface, and it does not
  have one.
- The console's approval flow answers *"should this agent do the thing it decided to do"*. An
  app's request is not that question, and routing it through the same UI would make two
  different things look identical to the person deciding.

An app that needs to act should expose a tool on its own MCP server, where this console's
existing gate, provenance and approval rules already apply to it. That path is open today and it
is the one that is actually governed.

---

## The rule both of them follow

Neither adapter is a dependency of `agentkit-core`. This is the same rule Temporal follows and
for the same reason, stated in the architecture doc as *"durability is an integration"*:
presentation is one too. `agentkit-core` does not know that `agentkit-chat` exists, and
`agentkit-chat` does not know that `agentkit-agui` does.
