import { createLowlight } from 'lowlight'
import { toText } from 'hast-util-to-text'
import { visit } from 'unist-util-visit'
import type { Element, Root } from 'hast'
import bash from 'highlight.js/lib/languages/bash'
import diff from 'highlight.js/lib/languages/diff'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

/**
 * Syntax colours for the ten languages an agent console actually shows.
 *
 * <h4>Why this is thirty lines rather than a plugin</h4>
 *
 * `rehype-highlight` does exactly this, and its module does
 * `import {common, createLowlight} from 'lowlight'` at the top — so lowlight's whole common
 * set, about thirty-five grammars, is in the bundle whether or not you use it. Passing its
 * `languages` option changes which are *registered*, not which are *shipped*, and Rollup cannot
 * drop a statically imported binding that the module reads.
 *
 * <p>Measured, three clean builds:
 *
 * <pre>
 * no highlighting at all            370 kB raw   115 kB gzipped
 * rehype-highlight, default set     550 kB       169 kB
 * rehype-highlight, ten languages   550 kB       169 kB   &lt;- the option buys nothing
 * this file                         see below
 * </pre>
 *
 * <p>Ten grammars are what this renders — a tool's arguments, a query the model wrote, a stack
 * trace, a diff of a comment it is proposing. A fence in anything else still renders as a code
 * block; it is simply not coloured, which is the right way to be wrong.
 *
 * <h4>It runs before sanitizing, deliberately</h4>
 *
 * This adds `span` elements and `hljs-` class names, and the sanitizer has to see them: a
 * highlighter running *after* the sanitizer would be putting markup back into a tree that had
 * already been declared clean, which is how a safe pipeline stops being one.
 */
const GRAMMARS = { bash, diff, java, javascript, json, python, sql, typescript, xml, yaml }

const lowlight = createLowlight(GRAMMARS)

/** The languages this colours, for a test to assert against rather than infer. */
export const HIGHLIGHTED = Object.keys(GRAMMARS)

/**
 * The `language-x` a fence declared.
 *
 * Whether we have that grammar is not asked here. `lowlight.registered()` was the first
 * spelling and it is a second defence against the same thing the catch below already handles —
 * removing it left every test green, because an unregistered language throws and the catch
 * leaves the block uncoloured either way. Two mechanisms where one is load-bearing means a
 * later reader cannot tell which.
 */
function languageOf(node: Element): string | null {
  const classes = node.properties?.className
  const names = Array.isArray(classes) ? classes.map(String) : []
  for (const name of names) {
    if (name.startsWith('language-')) {
      return name.slice('language-'.length).toLowerCase() || null
    }
  }
  return null
}

/**
 * Colours fenced code blocks in place.
 *
 * Inline `` `like this` `` is left alone, and by construction rather than by a check: only a
 * fence carries a `language-` class, so there is nothing for {@link languageOf} to find on an
 * inline span. An explicit `parent.tagName === 'pre'` test was here first and no test could
 * kill it.
 */
export function rehypeHighlightSubset() {
  return (tree: Root) => {
    visit(tree, 'element', (node: Element) => {
      if (node.tagName !== 'code') {
        return
      }
      const language = languageOf(node)
      if (!language) {
        return
      }
      try {
        // Throws for a grammar we did not bundle, which is the ordinary case for nine tenths
        // of the languages a model might name, and for pathological input to one we did.
        const coloured = lowlight.highlight(language, toText(node, { whitespace: 'pre' }))
        node.properties = {
          ...node.properties,
          className: ['hljs', `language-${language}`],
        }
        node.children = coloured.children as Element['children']
      } catch {
        // A grammar that throws on pathological input leaves the block uncoloured rather than
        // taking the answer down with it. Highlighting is decoration; the code is the point.
      }
    })
  }
}
