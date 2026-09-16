import { describe, expect, it } from 'vitest'
import { sanitize } from 'hast-util-sanitize'
import { toHtml } from 'hast-util-to-html'
import type { Root } from 'hast'
import { SCHEMA } from './Markdown'

/**
 * The sanitize schema on its own, without the renderer in front of it.
 *
 * <h4>Why this exists separately</h4>
 *
 * `react-markdown` runs its own `urlTransform` over every href and src before this schema is
 * ever consulted, so a rendering test that feeds it `[x](javascript:alert(1))` passes whether
 * or not this schema forbids the protocol — measured, by allowing `javascript` in the schema
 * and watching the link stay refused.
 *
 * <p>Two layers is the right number and the outer one is a default somebody can turn off with a
 * prop. What was missing was any way to tell whether the inner one still worked. These drive it
 * directly, on trees the renderer would never produce, so the answer is about this schema and
 * nothing else.
 */
function cleaned(tree: Root): string {
  return toHtml(sanitize(tree, SCHEMA))
}

function element(tagName: string, properties: Record<string, unknown>, text = 'x'): Root {
  return {
    type: 'root',
    children: [
      {
        type: 'element',
        tagName,
        properties: properties as never,
        children: [{ type: 'text', value: text }],
      },
    ],
  }
}

describe('the sanitize schema', () => {
  it('refuses a javascript: href even when nothing upstream did', () => {
    expect(cleaned(element('a', { href: 'javascript:alert(1)' }))).not.toContain('javascript:')
  })

  it('refuses a data: image source', () => {
    // data: in an img src is a whole document the browser parses, and svg carries script.
    expect(cleaned(element('img', { src: 'data:image/svg+xml;base64,PHN2Zz4=' })))
      .not.toContain('data:')
  })

  it('allows the protocols a browser will not execute', () => {
    expect(cleaned(element('a', { href: 'https://jira.example.com/browse/INC-1' })))
      .toContain('https://jira.example.com/browse/INC-1')
    expect(cleaned(element('a', { href: 'mailto:ops@example.com' }))).toContain('mailto:')
  })

  it('strips a class from an ordinary element', () => {
    // A model's paragraph wearing this console's own error styling is a phishing surface inside
    // a tool the operator trusts.
    expect(cleaned(element('p', { className: ['text-bad', 'border-bad'] })))
      .not.toContain('text-bad')
  })

  it('keeps only the highlighter`s own class names', () => {
    const code = cleaned(element('code', { className: ['hljs', 'language-sql', 'text-bad'] }))
    expect(code).toContain('hljs')
    expect(code).toContain('language-sql')
    expect(code).not.toContain('text-bad')

    const span = cleaned(element('span', { className: ['hljs-keyword', 'text-bad'] }))
    expect(span).toContain('hljs-keyword')
    expect(span).not.toContain('text-bad')
  })

  it('refuses an event handler on anything', () => {
    expect(cleaned(element('p', { onClick: 'alert(1)' }))).not.toContain('onclick')
    expect(cleaned(element('img', { src: 'https://x/y.png', onError: 'alert(1)' })))
      .not.toContain('onerror')
  })

  it('refuses a script element outright', () => {
    expect(cleaned(element('script', {}, 'alert(1)'))).not.toContain('<script')
  })

  it('refuses style and iframe', () => {
    expect(cleaned(element('style', {}, 'body{display:none}'))).not.toContain('<style')
    expect(cleaned(element('iframe', { src: 'https://evil.example' }))).not.toContain('<iframe')
  })

  it('leaves a GFM checkbox alone but never enables it', () => {
    const box = cleaned(element('input', { type: 'checkbox', checked: true, disabled: true }))
    expect(box).toContain('type="checkbox"')
    expect(box).toContain('disabled')
  })

  it('refuses an input that is not a checkbox', () => {
    // A text input inside an answer is a credential prompt waiting to happen.
    expect(cleaned(element('input', { type: 'password' }))).not.toContain('type="password"')
  })
})
