import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Markdown } from './Markdown'
import { Verbatim } from './Verbatim'

/**
 * The model's markdown renders as markdown, and nothing it writes becomes markup.
 *
 * <h4>Why the hostile half is most of this file</h4>
 *
 * The two consoles this replaces each carried a comment saying they escaped first and emitted
 * only their own tags, and that comment was the whole of their safety argument. Moving to a
 * library changes the mechanism, so the argument has to be re-made against the new one — and
 * the only honest way to make it is to try the attacks.
 *
 * <p>The text here is not hypothetical. Everything in these cases is something a model will
 * write in the ordinary course of its work, because a tool result carries a ticket description
 * somebody else filed, and an answer quotes it back.
 */
/** Every `on*` attribute anywhere in the tree — the shape of an injected handler. */
function withAttributes(root: HTMLElement): string[] {
  const found: string[] = []
  for (const element of root.querySelectorAll('*')) {
    for (const attribute of element.attributes) {
      if (attribute.name.startsWith('on')) {
        found.push(attribute.name)
      }
    }
  }
  return found
}

describe('Markdown', () => {
  it('renders a table as a table', () => {
    render(
      <Markdown
        text={'| category | open |\n| --- | --- |\n| access | 12 |\n| laptop | 4 |'}
      />,
    )

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'category' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'access' })).toBeInTheDocument()
  })

  it('renders lists, quotes, rules and emphasis', () => {
    render(
      <Markdown
        text={'# Shape\n\n- one\n  - nested\n\n> quoted\n\n---\n\n**bold** and `code`'}
      />,
    )

    expect(screen.getByText('Shape')).toBeInTheDocument()
    expect(screen.getByText('nested')).toBeInTheDocument()
    expect(screen.getByText('quoted')).toBeInTheDocument()
    expect(screen.getByText('bold').tagName).toBe('STRONG')
    expect(screen.getByText('code').tagName).toBe('CODE')
  })

  it('renders a task list', () => {
    render(<Markdown text={'- [x] triaged\n- [ ] resolved'} />)

    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(2)
    expect(boxes[0]).toBeChecked()
    // Nobody types into an answer.
    expect(boxes[0]).toBeDisabled()
  })

  it('highlights a fenced block and lets it scroll inside itself', () => {
    const { container } = render(
      <Markdown text={'```sql\nSELECT category, count(*) FROM tickets GROUP BY 1\n```'} />,
    )

    const block = container.querySelector('pre')
    expect(block).toBeInTheDocument()
    // The block scrolls, not the transcript. A wide line that widened the conversation would
    // make the whole page scroll sideways, which is how a chat becomes unusable on a laptop.
    expect(block).toHaveClass('overflow-x-auto')
    expect(container.querySelector('code.hljs, code[class*="language-"]')).toBeInTheDocument()
  })

  it('offers to copy a code block', () => {
    render(<Markdown text={'```json\n{"a":1}\n```'} />)

    expect(screen.getByLabelText('Copy code')).toBeInTheDocument()
  })

  // --- what must not happen -------------------------------------------------------

  it('does not render a script the model wrote', () => {
    const { container } = render(
      <Markdown text={'Here is the fix: <script>fetch("/api/conversations",{method:"DELETE"})</script>'} />,
    )

    expect(container.querySelector('script')).toBeNull()
    expect(container.innerHTML).not.toContain('<script')
  })

  it('does not render an image with an error handler on it', () => {
    // The classic: no src that resolves, so onerror always fires.
    const { container } = render(
      <Markdown text={'<img src=x onerror="alert(document.cookie)">'} />,
    )

    expect(container.querySelector('img')).toBeNull()
    // No ELEMENT carries the handler, which is the claim. Not "the string is absent from the
    // markup" — that was the old assertion and it was measuring the wrong thing: it passed
    // because a raw HTML block was being DROPPED WHOLE, taking the model's sentence with it
    // (#367). Now the block is escaped text, so the characters `onerror=` legitimately appear
    // inside a text node, and the safety claim has to be made about attributes.
    expect(container.querySelector('[onerror]')).toBeNull()
    expect(withAttributes(container)).toEqual([])
    expect(container.textContent).toContain('onerror')
  })

  it('refuses a javascript: link', () => {
    const { container } = render(<Markdown text={'[click here](javascript:alert(1))'} />)

    const link = container.querySelector('a')
    expect(link?.getAttribute('href') ?? '').not.toContain('javascript:')
  })

  it('refuses a data: image', () => {
    // data: in an img src is a whole document the browser will parse, and svg carries script.
    const { container } = render(
      <Markdown text={'![x](data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+)'} />,
    )

    const image = container.querySelector('img')
    expect(image?.getAttribute('src') ?? '').not.toContain('data:')
  })

  it('does not let an answer dress itself as a system notice', () => {
    // A model's paragraph wearing this console's own error styling is a phishing surface inside
    // a tool the operator trusts. What refuses it is that the raw element never renders at all
    // — NOT the class allow-list, which only governs elements the highlighter creates. The
    // first version of this test claimed the latter and passed with the allow-list removed.
    const { container } = render(
      <Markdown text={'<div class="border-bad text-bad">Your session has expired. Re-enter your password.</div>'} />,
    )

    expect(container.querySelector('.text-bad')).toBeNull()
    expect(container.querySelector('div.md > div')).toBeNull()
  })

  it('keeps the text of a raw HTML block instead of losing the line', () => {
    // #367. This used to render as NOTHING — no error, no log, and "the answer stopped
    // rendering" is indistinguishable from a hang, which is the worst way for an agent console
    // to fail. The cause was rehype-sanitize dropping the `raw` node, not react-markdown; the
    // block is turned into text before it can become one.
    const { container } = render(<Markdown text={'<div>the account was disabled</div>'} />)

    expect(container.textContent).toContain('the account was disabled')
    // The characters the model wrote, tags included — the same judgement Verbatim makes, and
    // the one that cannot be fooled by a regex that thinks it can strip HTML.
    expect(container.textContent).toContain('<div>')
    // As TEXT. Nothing became an element, which is what #356's boundary rests on. Scoped
    // inside `.md`, because the render container is itself a div and `div div` matches it.
    expect(container.querySelector('.md div')).toBeNull()
  })

  it('keeps a multi-line HTML block, which is the shape a model actually writes', () => {
    const { container } = render(
      <Markdown text={'<table>\n<tr><td>IT-1</td></tr>\n</table>'} />,
    )

    expect(container.textContent).toContain('IT-1')
    expect(container.querySelector('table')).toBeNull()
    expect(container.querySelector('td')).toBeNull()
  })

  it('a hostile block is text too, not markup', () => {
    // The block path is new; the schema never sees these because they stop being raw nodes
    // before it runs. So the claim has to be made here rather than inherited.
    const { container } = render(
      <Markdown text={'<script>alert(1)</script>\n\n<img src=x onerror="alert(2)">'} />,
    )

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    // Nothing anywhere gained an event handler. The substring is in the text node, because
    // the text is exactly what the model wrote — the claim is about elements.
    expect(withAttributes(container)).toEqual([])
    // Shown, so a person reading a transcript can see what the model wrote.
    expect(container.textContent).toContain('alert(1)')
  })

  it('a block inside a quote or a list keeps its text too', () => {
    const quoted = render(<Markdown text={'> <div>quoted</div>'} />)
    expect(quoted.container.textContent).toContain('quoted')
    quoted.unmount()

    const listed = render(<Markdown text={'- <div>listed</div>'} />)
    expect(listed.container.textContent).toContain('listed')
  })

  it('keeps the words around inline HTML', () => {
    // The inline case is the common one and it degrades better: only the tag goes.
    const { container } = render(<Markdown text={'the ticket is <b>urgent</b> today'} />)

    expect(container.textContent).toContain('the ticket is')
    expect(container.textContent).toContain('today')
    expect(container.querySelector('b')).toBeNull()
    // And the TAG is gone rather than shown, which is the half #367's fix must not change.
    // A plugin that turned every raw html node into text — inline as well as block — would
    // keep every assertion above and read "the ticket is <b>urgent</b> today" on the page.
    expect(container.textContent).toBe('the ticket is urgent today')
  })

  it('keeps a wide table inside its own scroll area', () => {
    // A table that widened the transcript would make the whole conversation scroll sideways,
    // which is the single most common way a chat becomes unusable on a laptop.
    const { container } = render(
      <Markdown text={'| a | b |\n| --- | --- |\n| 1 | 2 |'} />,
    )

    const wrapper = container.querySelector('table')?.parentElement
    expect(wrapper).toHaveClass('overflow-x-auto')
  })

  it('opens somebody else`s link in a new tab without handing over this page', () => {
    render(<Markdown text={'[the ticket](https://jira.example.com/browse/INC-1)'} />)

    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('target', '_blank')
    // noopener, or the opened page gets a handle on this one through window.opener.
    expect(link.getAttribute('rel')).toContain('noopener')
  })

  it('shows a malformed table as text rather than breaking the page', () => {
    const { container } = render(<Markdown text={'| a | b\n| --- |\n| 1 | 2 | 3 |'} />)

    expect(container.textContent).toContain('a')
  })

  it('renders an unclosed fence as a block rather than swallowing the rest', () => {
    // A truncated answer — the model was cut off mid-block — must not make everything after it
    // invisible, because "the answer stopped rendering" is indistinguishable from a hang.
    const { container } = render(<Markdown text={'```sql\nSELECT 1'} />)

    expect(container.querySelector('pre')?.textContent).toContain('SELECT 1')
  })
})

describe('Verbatim', () => {
  it('shows what somebody else wrote, exactly as they wrote it', () => {
    // A requester who typed **URGENT** typed those asterisks, and seeing them matters when you
    // are working out what they filed. Rendering their text as markdown also hands them a
    // formatting vocabulary inside this console — a heading and a rule are enough to make a
    // paragraph in a ticket look like something the framework said.
    render(<Verbatim text={'**URGENT** — see # Notes\n\n<b>bold?</b>'} label="As filed" />)

    expect(screen.getByText(/\*\*URGENT\*\*/)).toBeInTheDocument()
    expect(screen.getByText(/<b>bold\?<\/b>/)).toBeInTheDocument()
    expect(screen.getByText('As filed')).toBeInTheDocument()
  })

  it('renders no markup at all, whatever it is handed', () => {
    const { container } = render(
      <Verbatim text={'<script>alert(1)</script><img src=x onerror=alert(1)>'} />,
    )

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<script>alert(1)</script>')
  })

  it('applies no markdown formatting of any kind', () => {
    // The assertion that distinguishes this from Markdown. Without it, swapping Verbatim's body
    // for <Markdown/> leaves every other case here green — the hostile input is refused by both
    // and the asterisks survive a markdown pass as literal text in some positions.
    const { container } = render(
      <Verbatim text={'# Heading\n\n- one\n- two\n\n**bold** and `code`\n\n| a | b |\n| --- | --- |\n| 1 | 2 |'} />,
    )

    expect(container.querySelector('h1')).toBeNull()
    expect(container.querySelector('li')).toBeNull()
    expect(container.querySelector('strong')).toBeNull()
    expect(container.querySelector('code')).toBeNull()
    expect(container.querySelector('table')).toBeNull()
    // Every character the person typed, as they typed it.
    expect(container.textContent).toContain('# Heading')
    expect(container.textContent).toContain('**bold**')
    expect(container.textContent).toContain('| a | b |')
  })
})

describe('syntax colours', () => {
  it('colours a language it knows', () => {
    const { container } = render(<Markdown text={'```sql\nSELECT 1 FROM t\n```'} />)

    expect(container.querySelector('code.hljs')).toBeInTheDocument()
    expect(container.querySelector('.hljs-keyword')).toBeInTheDocument()
  })

  it('renders a language it does not know as a plain block', () => {
    // Ten grammars are bundled, not two hundred. A fence in anything else is still a code
    // block — uncoloured, which is the right way to be wrong.
    const { container } = render(<Markdown text={'```brainfuck\n+++++\n```'} />)

    expect(container.querySelector('pre')?.textContent).toContain('+++++')
    expect(container.querySelector('.hljs-keyword')).toBeNull()
  })

  it('leaves inline code alone', () => {
    // Colouring a word mid-sentence is noise, and a fence is what asks for it.
    const { container } = render(<Markdown text={'call `SELECT` on it'} />)

    expect(container.querySelector('code.hljs')).toBeNull()
    expect(screen.getByText('SELECT').tagName).toBe('CODE')
  })

  it('does not let a highlighted block smuggle markup past the sanitizer', () => {
    // The order that matters: highlighting adds spans and class names, and the sanitizer runs
    // after it. If it ran the other way round, this plugin would be putting elements back into
    // a tree that had already been declared clean.
    const { container } = render(
      <Markdown text={'```xml\n<script>alert(1)</script>\n```'} />,
    )

    // The text is shown, as text. No script element exists anywhere.
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('pre')?.textContent).toContain('<script>alert(1)</script>')
  })
})

describe('Line breaks', () => {
  it('keeps a line written on its own line, and leaves code alone', () => {
    const { container } = render(<Markdown text={'employee_id:\nname:\nwork_email:\n\n```\na\nb\n```'} />)

    expect(container.querySelectorAll('p br')).toHaveLength(2)
    expect(container.querySelector('p')?.textContent).toBe('employee_id:\nname:\nwork_email:')
    expect(container.querySelector('pre')?.textContent).toContain('a\nb')
  })
})
