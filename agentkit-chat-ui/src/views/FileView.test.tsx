import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FileView } from './FileView'
import { ViewOf } from './registry'

describe('FileView', () => {
  it('hands the file over rather than mentioning it', () => {
    render(
      <FileView
        view={{
          kind: 'file',
          data: { id: 'att-1', name: 'case-run-9.json', mediaType: 'application/json', bytes: 2048 },
        }}
      />,
    )

    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('href', '/api/attachments/att-1')
    expect(link).toHaveAttribute('download', 'case-run-9.json')
    expect(link.textContent).toContain('application/json')
  })

  it('builds the href from the id, so a tool cannot point it at another origin', () => {
    // The only thing a tool supplies is an identifier this console appends to its own path.
    // That is what makes a tool-supplied file safe to render as a link at all.
    render(
      <FileView
        view={{ kind: 'file', data: { id: 'https://evil.example/x', name: 'x', bytes: 1 } }}
      />,
    )

    expect(screen.getByRole('link')).toHaveAttribute(
      'href',
      '/api/attachments/https%3A%2F%2Fevil.example%2Fx',
    )
  })

  it('says so when there is no identifier to fetch by', () => {
    render(<FileView view={{ kind: 'file', data: { name: 'orphan.txt' } }} />)

    expect(screen.getByText('A file with no identifier.')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('is reached through the registry', () => {
    render(<ViewOf view={{ kind: 'file', data: { id: 'att-1', name: 'a.txt', bytes: 1 } }} />)

    expect(screen.getByTestId('file-view')).toBeInTheDocument()
  })
})
