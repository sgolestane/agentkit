# The console's design system

A calm, neutral interface: greys carry the structure, and colour is kept for meaning. The
conversation is the page. What the person said sits in a soft bubble on the right; what the agent
answered is plain text in a 768px reading column, with no card around it. The composer is a pill
at the bottom of that column.

Every value is a token in [`src/index.css`](src/index.css). A component uses the token's utility
(`bg-panel`, `text-muted`, `rounded-[var(--radius-card)]`) and never a raw colour, so light and dark
are one decision.

## Colour

| Token | Utility | Light | Dark | For |
|---|---|---|---|---|
| canvas | `bg-canvas` | `#ffffff` | `#000000` | The page; what a conversation sits on |
| sidebar | `bg-sidebar` | `#f9f9f9` | `#000000` | The conversation list; the admin view's sections |
| panel | `bg-panel` | `#ffffff` | `#212121` | Raised: the composer, cards, menus |
| raised | `bg-raised` | `#f3f3f3` | `#303030` | A step above a panel: code blocks |
| bubble | `bg-bubble` | `#f3f3f3` | `#323232d9` | What the person said |
| hover | `bg-hover` | `#0000000d` | `#ffffff1a` | A row or ghost button under the pointer; the running-turn row |
| selected | `bg-selected` | `#0000000d` | `#ffffff1a` | The open conversation, the current admin section |
| ink | `text-ink` | `#0d0d0d` | `#ffffff` | Text |
| muted | `text-muted` | `#5d5d5d` | `#cdcdcd` | Secondary text, ghost buttons |
| faint | `text-faint` | `#8f8f8f` | `#afafaf` | Section labels, placeholders, hints |
| line | `border-line` | `#0000001a` | `#ffffff26` | Borders and rules |
| line-soft | `border-line-soft` | `#0000000d` | `#ffffff0d` | Hairlines inside a surface |
| primary / on-primary | `bg-primary text-on-primary` | `#0d0d0d` / `#fff` | `#fff` / `#0d0d0d` | The one strong button in a place: Send, Approve, Start |
| accent | `text-accent` | `#3a83f7` | `#63a8f8` | Links; a question the agent asked |
| good | `text-good` | `#3a843f` | `#6bc67f` | It worked |
| warn | `text-warn` | `#d25e28` | `#f1a275` | Needs a decision; something failed along the way |
| bad | `text-bad` | `#ba2623` | `#ff8583` | It failed; destructive actions |

Colour never carries meaning alone: a failure also says so in words.

## Type

The platform's own face (`-apple-system-body`, `system-ui`, Segoe UI, Helvetica, Arial), as a
native app would use it, and `ui-monospace` for code.

| Use | Size / line height | Weight |
|---|---|---|
| Interface text (lists, labels, buttons) | 14 / 20 | 400; section labels 500 |
| Conversation text (`.reading`): messages, answers, the composer | 16 / 26 | 400 |
| Answer headings | h1 24 / 32, h2 20 / 28, h3 18 / 28 | 600 |
| Small print (trace, spend, hints) | 12 / 16 | 400 |
| Code | 13 / 20, mono | 400 |
| Product name in the sidebar | 18 | 600 |

## Shape and space

| Token | Value | For |
|---|---|---|
| `--radius-item` | 10px | Rows, menu items, inputs, ghost buttons |
| `--radius-bubble` | 22px | What the person said |
| `--radius-card` | 16px | Confirmations, the task form, code blocks, menus |
| `--radius-pill` | 28px | The composer |
| full | `rounded-full` | Buttons: Send, Approve, Reject; chips |
| `--shadow-menu` | soft drop + hairline | Menus and floating buttons |
| `--shadow-composer` | hairline (dark: an inset 1px `#ffffff33`) | The composer and the task form |

Layout: sidebar 260px; a 52px header row; the reading column is `max-w-3xl` (768px), centred, with
the composer and the task form aligned to it. Turns are 32px apart; rows in a list are 36px tall
with 10px of horizontal padding.

## Components

- **Buttons.** One primary button per place (`bg-primary text-on-primary rounded-full`). Everything
  else is a ghost (`text-muted hover:bg-hover hover:text-ink`) or an outline pill
  (`border border-line rounded-full`). A destructive button is outlined in `bad`.
- **The composer.** A `bg-panel` pill with the attach button on the left and Send on the right; the
  text field is borderless.
- **Messages.** The person's in `bg-bubble`, right-aligned, at most 70% wide. The agent's answer is
  plain `.reading` text, full width, with its trace and actions underneath.
- **Cards** (a confirmation, the task form) are `bg-panel` with `--radius-card`; a confirmation's
  border says what kind it is: `warn` for an action, `accent` for a question.
- **Views** a tool or the host shows above an answer (a plan, a table, cards, a diff, a chart) are
  outlined in `border-line`, so they read as something shown rather than something said.

The values were taken from a widely used chat interface's published stylesheet, as a reference
for proportions and greys; the console uses no one else's fonts, logos or names.
