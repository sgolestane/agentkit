# The smoke run

Everything else in this repository is offline: scripted models, fake connectors, jsdom. That is
the right default — it is fast, it is deterministic, and it catches almost everything.

**Almost.** The console's page has 227 component tests and they all run in jsdom, which does not
enforce a Content-Security-Policy, does not run a real event stream, and does not resolve
classpath resources. The first time this console was opened in a browser it served *the
dashboard's page* — because a classpath is flat, `agentkit-examples-workbench` ships its own
`ui/index.html`, and this module depends on both. Every test in the repository passed.

So: one run, by hand, on demand. Ten minutes, and it is the only thing that has ever found that
class of bug.

## Without credentials — two minutes, catches the most

```bash
just chat            # or: WORKBENCH_DASHBOARD=off just chat
open http://localhost:8083
```

With nothing configured the console boots and says so, which is itself the thing to check.

- [ ] **The page is the chat.** A threads sidebar on the left, a composer at the bottom. If you
      are looking at panels called INBOX and WAITING ON YOU, you are looking at the dashboard and
      the classpath has collided again — `TheChatServesItsOwnPageTest` should have caught it.
- [ ] **The browser console is empty.** No errors, and in particular **no CSP violations**. This
      is the check jsdom cannot do: a policy that forbids something the page needs produces a
      blank console and a person who removes the policy rather than the cause.
- [ ] **The banner names the missing variables**, and the composer is disabled with the same
      sentence rather than silently doing nothing.
- [ ] **A conversation exists and the stream is connected** — the URL becomes `/c/conv-…` on its
      own, which means the page created one and subscribed.

## With a model and a Jira — the real arc

```bash
just jira-sim        # in one terminal, if you have no Jira to point at
JIRA_BASE_URL=http://localhost:8090 JIRA_EMAIL=you@example.com JIRA_API_TOKEN=anything \
WORKBENCH_LLM=openrouter WORKBENCH_MODEL=anthropic/claude-sonnet-4.5 OPENROUTER_API_KEY=… \
just chat
```

Then, in the conversation, in this order — it is the demo arc and it is also the test:

- [ ] **"what's in my inbox?"** — a table, not a paragraph of ticket keys. Sortable, filterable.
- [ ] **"what would you do with <one of them>?"** — it calls `workbench.preview`, not `workbench.execute`,
      and it does not ask you to approve anything. Check the trace to be sure it rehearsed rather
      than guessed: a model that answered from the description alone is the failure this whole
      prompt is written against.
- [ ] **"go on then"** — an approval card appears *before* anything happens. Read it: the tool,
      the arguments, reversible or not. Approve it.
- [ ] **The run parks and asks you something.** Answer it. The answer becomes a learning —
      `learnings.list` should have it afterwards.
- [ ] **Refuse something, with a reason.** Then ask what it would do with a similar ticket: the
      reason should come back as advice, and `decisions.refusals` should list it if you made it
      standing.
- [ ] **"automate access requests"** — it asks first, and the message says what that means in
      tickets rather than just "done".
- [ ] **Open the dashboard on 8082.** The run you started is in its list. Approve something
      *there* and watch the chat's run finish. One workbench, two windows.
- [ ] **Stop a long turn mid-flight.** The transcript says you stopped it, not that it failed.
- [ ] **Drop a screenshot on the composer and ask about it.** The model should answer about
      the *picture* — not about a filename. This one is here rather than in the README's gif
      because the file input is hidden from the accessibility tree and cannot be driven by the
      automation that records one; it is the step most likely to rot unnoticed, since a console
      that quietly describes `shot.png` instead of looking at it still sounds right.

## What this does not cover

A real browser test in CI. Playwright would give one, and it would need the browser binaries
downloaded on every build to check a page that already has 227 component tests. The trade was
made the other way: this checklist plus `TheChatServesItsOwnPageTest`, which is the specific
regression a browser found. If a second bug of this class turns up, that trade should be
revisited rather than defended.
