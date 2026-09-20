import { memo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import type { Options as SanitizeSchema } from 'rehype-sanitize'
import { rehypeHighlightSubset } from '../lib/highlight'
import { remarkLineBreaks } from '../lib/lineBreaks'
import { remarkRawHtmlAsText } from '../lib/rawHtml'
import { plain } from '../lib/text'
import '../lib/highlight.css'

/**
 * The model's answer, rendered.
 *
 * <h4>What this replaces</h4>
 *
 * Two hand-written renderers, in two consoles, each about thirty lines of regular expressions
 * supporting bold, inline code, headings, bullets, a rule and — in one of them — tables. Both
 * carried the same comment explaining that they escaped first and then emitted only their own
 * tags, which was the right rule and the reason they were safe. Moving to a library changes the
 * mechanism and must not change the rule.
 *
 * <h4>Raw HTML is not rendered, and that is two decisions rather than one</h4>
 *
 * `react-markdown` does not interpret raw HTML unless `rehype-raw` is added, so the default is
 * already safe — but "safe because nobody added a plugin" is a property one commit removes
 * silently. `rehype-sanitize` is here as the second, explicit half: it runs over the produced
 * tree on a strict allow-list, so an element or attribute nothing below permits cannot appear
 * however it got there.
 *
 * <p><strong>A raw HTML block loses its text as well as its tags.</strong> `react-markdown`
 * skips the node whole, so an answer that begins a line with `<` renders as nothing at all and
 * says nothing about why — and "the answer stopped rendering" is indistinguishable from a hang.
 * Inline HTML degrades better: only the tag goes and the words around it stay. Pinned by
 * `drops the text of a raw HTML block along with the block` so the day it changes, it changes
 * deliberately.
 *
 * <p>The model is not the only author whose text lands here either. A tool result carries a
 * ticket description or a log line somebody else wrote, and #356 will make the boundary
 * explicit; this is the piece that has to hold when it does.
 */

/**
 * What is allowed through, over rehype-sanitize's own defaults.
 *
 * The defaults already refuse `script`, `style`, `iframe`, every `on*` handler, and any URL
 * scheme outside a known-safe list — which covers `javascript:` in an href and `data:` in an
 * `img src`, the two that matter. What is added is the little GFM needs and nothing else.
 */
/**
 * One `className` spec for a tag: whatever the default allowed, plus {@code extra}.
 *
 * A single entry, because a sanitizer consults the first spec that names an attribute and
 * ignores any later one for the same name.
 */
type ClassNameSpec = [string, ...(string | number | boolean | RegExp | null | undefined)[]]

function classNames(
  existing: readonly unknown[] | undefined,
  extra: RegExp,
): ClassNameSpec {
  const current = (existing ?? []).find(
    (spec): spec is ClassNameSpec => Array.isArray(spec) && spec[0] === 'className',
  )
  return ['className', ...(current ? current.slice(1) : []), extra]
}

export const SCHEMA: SanitizeSchema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    // The class attribute, but only on code and span, and only the highlighter's own names.
    // Allowing class generally would let a model's answer wear this console's own styles, which
    // is how a bubble comes to look like a system notice.
    //
    // MERGED into the default's entry rather than appended after it, and that distinction cost
    // an hour. hast-util-sanitize matches an attribute by the first spec that names it, so
    // `[...defaults, ['className', /hljs/]]` leaves the default's `[['className', /^language-./]]`
    // in front and the second entry is never consulted — the `hljs` class was silently stripped
    // and every block came out uncoloured, with nothing to say why.
        // Anchored at both ends, and `hljs` is spelled on its own. Written first as
    // `/^(hljs|hljs-|language-).+/`, which requires a character AFTER the group — so the bare
    // `hljs` class the highlighter sets never matched, and every block came out with its spans
    // coloured and its container class stripped.
    code: [classNames(defaultSchema.attributes?.code, /^(hljs|hljs-[\w-]+|language-[\w-]+)$/)],
    span: [classNames(defaultSchema.attributes?.span, /^hljs-[\w-]+$/)],
    // GFM checkboxes are inputs the renderer emits; they stay disabled.
    input: [['type', 'checkbox'], 'checked', 'disabled'],
  },
  tagNames: [...(defaultSchema.tagNames ?? []), 'input'],
  // The SECOND of two refusals, and worth knowing which is which. `react-markdown` runs its
  // own `urlTransform` over every href and src before this schema is consulted, and that is
  // what actually answers a `javascript:` link today — measured by allowing `javascript` here
  // and finding the link still refused.
  //
  // This stays because the first layer is a default somebody can turn off with one prop, and
  // because a schema that permits only what a browser will not execute is the statement worth
  // having written down. It does mean a test asserting the outcome cannot tell you which layer
  // answered; both would have to go.
  protocols: {
    ...defaultSchema.protocols,
    href: ['http', 'https', 'mailto'],
    src: ['http', 'https'],
  },
}

export const Markdown = memo(function Markdown({ text }: { text: string }) {
  // Before the parser, not after: a bidi override inside a link's text reorders what the
  // person reads while the href stays what it is, and by the time this is a tree the two are
  // separate nodes. See lib/text.ts for why these characters and not `\p{Cf}` wholesale.
  const safe = plain(text)
  return (
    <div className="md">
      <ReactMarkdown
        // rawHtmlAsText FIRST, and see its note for why it is a remark plugin rather than a
        // rehype one: it has to turn a block into text before that block becomes a `raw` node,
        // because the thing that drops it is the sanitizer and loosening the sanitizer is the
        // one move #356's boundary rests on not making.
        remarkPlugins={[remarkRawHtmlAsText, remarkGfm, remarkLineBreaks]}
        // Highlight, then sanitize. The forward reason is plain: sanitizing first would strip
        // the classes the highlighter is about to add, and the block would come out plain.
        //
        // The safety reason is real but weaker than it first reads, and swapping the order does
        // not fail any test here — measured. Highlighting after sanitizing would put markup back
        // into a tree already declared clean, which is only safe while lowlight emits nothing
        // but spans and text. It does. Relying on that is relying on a third party's output
        // shape rather than on this pipeline, which is why the order is this way round.
        rehypePlugins={[rehypeHighlightSubset, [rehypeSanitize, SCHEMA]]}
        components={{
          pre: CodeBlock,
          a: ({ children, href }) => (
            // Somebody else's link, opened somewhere else, and told not to hand this page's
            // origin over with it.
            <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="text-accent underline">
              {children}
            </a>
          ),
          table: ({ children }) => (
            // The table scrolls inside itself. A wide one that widened the transcript would
            // make the whole conversation scroll sideways, which is the single most common way
            // a chat UI becomes unusable on a laptop.
            <div className="my-4 overflow-x-auto rounded-[var(--radius-item)] border border-line">
              <table className="w-full border-collapse text-sm">{children}</table>
            </div>
          ),
          th: ({ children }) => (
            <th className="border-b border-line px-3 py-2 text-left text-xs font-medium text-muted">
              {children}
            </th>
          ),
          td: ({ children }) => <td className="border-b border-line-soft px-3 py-2 align-top">{children}</td>,
          ul: ({ children }) => <ul className="my-2 list-disc pl-6">{children}</ul>,
          ol: ({ children }) => <ol className="my-2 list-decimal pl-6">{children}</ol>,
          blockquote: ({ children }) => (
            <blockquote className="my-3 border-l-4 border-line pl-5 leading-6">{children}</blockquote>
          ),
          h1: ({ children }) => <p className="mt-6 mb-2 text-2xl leading-8 font-semibold">{children}</p>,
          h2: ({ children }) => <p className="mt-6 mb-2 text-xl leading-7 font-semibold">{children}</p>,
          h3: ({ children }) => <p className="mt-4 mb-1 text-lg leading-7 font-semibold">{children}</p>,
          hr: () => <hr className="my-7 border-line" />,
        }}
      >
        {safe}
      </ReactMarkdown>
    </div>
  )
})

/** A fenced block: scrolls inside itself, and can be copied whole. */
function CodeBlock({ children }: { children?: React.ReactNode }) {
  const [copied, setCopied] = useState(false)
  const [block, setBlock] = useState<HTMLPreElement | null>(null)

  return (
    <div className="group relative my-2">
      <pre
        ref={setBlock}
        className="overflow-x-auto rounded-[var(--radius-card)] bg-raised p-4 font-mono text-[13px] leading-5"
      >
        {children}
      </pre>
      <button
        type="button"
        aria-label="Copy code"
        onClick={() => {
          void copy(block?.textContent ?? '').then(() => {
            setCopied(true)
            setTimeout(() => setCopied(false), 1500)
          })
        }}
        className="absolute right-2 top-2 rounded-[var(--radius-item)] bg-panel px-2 py-1 text-xs text-muted opacity-0 transition hover:text-ink group-hover:opacity-100 focus:opacity-100"
      >
        {copied ? 'Copied' : 'Copy'}
      </button>
    </div>
  )
}

/**
 * Copies, without assuming a clipboard exists.
 *
 * `navigator.clipboard` is absent on an insecure origin, which is exactly how this console is
 * reached in development — `http://localhost` is treated as secure by most browsers but a
 * colleague opening it on `http://192.168.x.x` is not. A copy button that throws there takes
 * the surrounding component down with it.
 */
export async function copy(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Fall through: refused permission is the same outcome as no clipboard at all.
  }
  return false
}
