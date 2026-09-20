import type { Plugin } from 'unified'

/** The mdast shapes this walks. */
interface Node {
  type: string
  value?: string
  children?: Node[]
}

/**
 * Keeps a single line break a line break.
 *
 * Markdown joins the lines of a paragraph into one, so an answer written a field to a line — a
 * template, an address, a list without bullets — came out as one run-on sentence. A chat answer
 * is written to be read as typed, so every line break inside a paragraph becomes a break. Code,
 * which is not a text node, is untouched.
 */
export const remarkLineBreaks: Plugin = () => (tree) => {
  split(tree as Node)
}

function split(node: Node): void {
  if (!node.children) {
    return
  }
  const children: Node[] = []
  for (const child of node.children) {
    if (child.type === 'text' && child.value?.includes('\n')) {
      child.value.split('\n').forEach((line, index) => {
        if (index > 0) {
          children.push({ type: 'break' })
        }
        if (line) {
          children.push({ type: 'text', value: line })
        }
      })
    } else {
      split(child)
      children.push(child)
    }
  }
  node.children = children
}
