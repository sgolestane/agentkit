import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CardsView, linkable } from './CardsView'
import { ViewOf } from './registry'

const card = (over: Record<string, unknown> = {}) => ({
  title: 'IT-421',
  subtitle: 'Grant access to the reporting group',
  fields: { status: 'Open', assignee: 'nobody' },
  url: '',
  ...over,
})

describe('CardsView', () => {
  it('draws the title, the subtitle and every field', () => {
    render(<CardsView view={{ kind: 'cards', data: { cards: [card()] } }} />)

    expect(screen.getByText('IT-421')).toBeInTheDocument()
    expect(screen.getByText('Grant access to the reporting group')).toBeInTheDocument()
    expect(screen.getByText('status')).toBeInTheDocument()
    expect(screen.getByText('Open')).toBeInTheDocument()
  })

  it('is reached through the registry, which is the whole point of the card', () => {
    // `cards` has been a View factory since #336 and had no renderer, so every ticket and
    // every verdict this console has ever shown came out as a collapsed JSON blob under
    // "a cards this console has no drawing for".
    render(<ViewOf view={{ kind: 'cards', data: { cards: [card()] } }} />)

    expect(screen.getByTestId('cards-view')).toBeInTheDocument()
    expect(screen.queryByText(/no drawing for/)).not.toBeInTheDocument()
  })

  it('renders a field exactly as it was filed, never as markdown', () => {
    // A requester who typed asterisks typed them, and seeing that is part of working out
    // what they filed. It is also the difference between a ticket that looks like a ticket
    // and one that looks like something this console said.
    render(
      <CardsView
        view={{
          kind: 'cards',
          data: { cards: [card({ fields: { 'as filed': '**URGENT** see # Notes' } })] },
        }}
      />,
    )

    expect(screen.getByText('**URGENT** see # Notes')).toBeInTheDocument()
    expect(document.querySelector('strong')).toBeNull()
    expect(document.querySelector('h1')).toBeNull()
  })

  it('keeps the paragraphs of a description that has them', () => {
    render(
      <CardsView
        view={{ kind: 'cards', data: { cards: [card({ fields: { body: 'one\n\ntwo' } })] } }}
      />,
    )

    const value = screen.getByText(/one/)
    expect(value.className).toContain('whitespace-pre-wrap')
    expect(value.textContent).toBe('one\n\ntwo')
  })

  it('links through to where the record really lives', () => {
    render(
      <CardsView
        view={{
          kind: 'cards',
          data: { cards: [card({ url: 'https://acme.atlassian.net/browse/IT-421' })] },
        }}
      />,
    )

    const link = screen.getByRole('link', { name: 'open' })
    expect(link).toHaveAttribute('href', 'https://acme.atlassian.net/browse/IT-421')
    // An external destination in a new tab, and without handing the opened page a reference
    // back to this one.
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
  })

  it('refuses a url that is not one a browser should follow', () => {
    // The card's data comes from a tool that may be quoting a stranger, so this is the same
    // attack as a `javascript:` markdown link and gets the same answer.
    for (const url of [
      'javascript:alert(1)',
      ' JavaScript:alert(1)',
      'jAvAsCrIpT:alert(1)',
      'data:text/html,<script>alert(1)</script>',
      'vbscript:msgbox(1)',
      // Parsed rather than prefix-matched: a `startsWith('http')` check passes this one.
      'httpsx:whatever',
    ]) {
      expect(linkable(url)).toBeNull()
    }
    expect(linkable('https://acme.atlassian.net/browse/IT-1')).toBe(
      'https://acme.atlassian.net/browse/IT-1',
    )
    expect(linkable('')).toBeNull()
    expect(linkable(undefined)).toBeNull()
  })

  it('draws no link at all when the deployment could not say where the record is', () => {
    render(<CardsView view={{ kind: 'cards', data: { cards: [card({ url: '' })] } }} />)

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('says an absent field is absent rather than showing the word null', () => {
    render(
      <CardsView
        view={{
          kind: 'cards',
          data: { cards: [card({ fields: { assignee: null, priority: '', runs: 0 } })] },
        }}
      />,
    )

    expect(screen.getAllByText('—')).toHaveLength(2)
    // Zero is a value, not an absence. A card that showed "—" for a real count of nothing
    // would be saying "unknown" where the truth is "none", and those are different answers.
    expect(screen.getByText('0')).toBeInTheDocument()
  })

  it('shows something structured rather than [object Object]', () => {
    render(
      <CardsView
        view={{ kind: 'cards', data: { cards: [card({ fields: { detail: { a: 1 } } })] } }}
      />,
    )

    expect(screen.getByText('{"a":1}')).toBeInTheDocument()
  })

  it('draws several cards, which is what the kind is plural for', () => {
    render(
      <CardsView
        view={{
          kind: 'cards',
          data: { cards: [card(), card({ title: 'IT-422' })] },
        }}
      />,
    )

    expect(screen.getAllByTestId('card')).toHaveLength(2)
  })

  it('says so when there is nothing rather than drawing an empty frame', () => {
    render(<CardsView view={{ kind: 'cards', data: { cards: [] } }} />)

    expect(screen.getByText('Nothing to show.')).toBeInTheDocument()
  })
})
