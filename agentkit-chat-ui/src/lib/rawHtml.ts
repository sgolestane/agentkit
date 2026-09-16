import type { Plugin } from 'unified'

/** The mdast shapes this walks. Narrow on purpose: it touches two of them and reads one field. */
interface Node {
  type: string
  value?: string
  children?: Node[]
}

/**
 * Markdown containers whose direct `html` children are *blocks*.
 *
 * Everything else that can hold an `html` node holds it inline — a paragraph, a heading, a table
 * cell — and inline is the case that already degrades well, so it is left alone.
 */
const BLOCK_PARENTS = new Set([
  'root',
  'blockquote',
  'listItem',
  'footnoteDefinition',
  'containerDirective',
])

/**
 * Keeps the text of a raw HTML block instead of losing the line.
 *
 * <h4>Where the text actually went</h4>
 *
 * An answer beginning a line with `<` rendered as **nothing at all** — no error, no log, and
 * "the answer stopped rendering" is indistinguishable from a hang, which is the worst way for
 * an agent console to fail.
 *
 * The cause is not react-markdown, which was what #367 said when it was filed. Measured, with
 * the plugins added one at a time:
 *
 * ```
 * remark-gfm only          "<div>important</div>\nplain\nthe ticket is <b>urgent</b> today"
 * highlight only           "<div>important</div>\nplain\nthe ticket is <b>urgent</b> today"
 * + rehype-sanitize        "\nplain\nthe ticket is urgent today"
 * ```
 *
 * It is **`rehype-sanitize`**. `hast-util-sanitize` drops `raw` nodes, because it has no
 * allowance for something that is not an element. Inline survives by accident of shape:
 * `<b>urgent</b>` is three hast children — raw, text, raw — so dropping the raws leaves the
 * word. A block is *one* raw node holding the tags and the text together, so dropping it takes
 * the sentence with it.
 *
 * <h4>Why the fix is here and not there</h4>
 *
 * Loosening the schema to admit `raw` would admit raw HTML, which is the one thing #356's
 * boundary rests on not doing. So the block is turned into text *before* it becomes a raw node
 * at all: an mdast `html` block becomes a paragraph of plain text, sanitize sees an ordinary
 * paragraph, and React escapes it on the way out.
 *
 * <h4>What a person sees</h4>
 *
 * The characters the model wrote, tags included: `<div>important</div>`. Not the inner text
 * with the tags stripped, which would match the inline case more neatly and would mean parsing
 * HTML out of a string with a regex — the classic way to be wrong about entities, nesting and
 * attributes. Showing exactly what was written is the same judgement `Verbatim` makes, and it
 * is the one that cannot be fooled.
 *
 * Inline `html` is untouched, so `the ticket is <b>urgent</b> today` still reads "the ticket is
 * urgent today".
 */
export const remarkRawHtmlAsText: Plugin = () => (tree: unknown) => {
  walk(tree as Node)
}

function walk(node: Node): void {
  const children = node.children
  if (!children) {
    return
  }
  if (BLOCK_PARENTS.has(node.type)) {
    for (let i = 0; i < children.length; i++) {
      const child = children[i]
      if (child && child.type === 'html') {
        children[i] = {
          type: 'paragraph',
          children: [{ type: 'text', value: child.value ?? '' }],
        }
      }
    }
  }
  for (const child of children) {
    if (child) {
      walk(child)
    }
  }
}
