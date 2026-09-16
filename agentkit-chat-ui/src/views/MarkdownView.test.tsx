import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MarkdownView } from './MarkdownView'
import { ViewOf } from './registry'

describe('MarkdownView', () => {
  it('renders the formatting, because this kind is a tool saying it meant it', () => {
    render(<MarkdownView view={{ kind: 'markdown', data: { text: '# Plan\n\n- one\n- two' } }} />)

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    // A heading comes out as a bold line rather than an <h1>, which is the shared renderer's
    // decision and is kept on purpose: this console's headings belong to the console, and a
    // tool that could emit an <h1> into a transcript could make its own output look like the
    // page's own structure. The text is there and it is emphasised; it is not a landmark.
    const heading = screen.getByText('Plan')
    expect(heading.tagName).toBe('P')
    expect(heading.className).toContain('font-semibold')
  })

  it('sanitizes through the same schema the model answers use', () => {
    // A tool's markdown may be quoting a document, so it gets #342's schema rather than a
    // second, looser copy of it.
    render(
      <MarkdownView
        view={{ kind: 'markdown', data: { text: '<img src=x onerror="alert(1)">' } }}
      />,
    )

    expect(document.querySelector('img[onerror]')).toBeNull()
  })

  it('draws nothing at all for nothing, rather than an empty box', () => {
    const { container } = render(
      <MarkdownView view={{ kind: 'markdown', data: { text: '   ' } }} />,
    )

    expect(container).toBeEmptyDOMElement()
  })

  it('is reached through the registry', () => {
    render(<ViewOf view={{ kind: 'markdown', data: { text: 'hello' } }} />)

    expect(screen.getByTestId('markdown-view')).toBeInTheDocument()
  })
})
