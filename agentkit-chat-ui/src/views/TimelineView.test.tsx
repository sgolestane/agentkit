import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ViewOf } from './registry'
import { TimelineView } from './TimelineView'

const moment = (over: Record<string, unknown> = {}) => ({
  kind: 'tool',
  label: 'tool completed',
  detail: 'jira.get_ticket',
  at: '2026-08-27 10:00Z',
  failed: false,
  ...over,
})

describe('TimelineView', () => {
  it('draws every moment in the order it was given', () => {
    render(
      <TimelineView
        view={{
          kind: 'timeline',
          data: {
            title: 'run-1 · IT-421',
            moments: [
              moment({ label: 'run started' }),
              moment({ label: 'tool completed' }),
              moment({ label: 'run completed' }),
            ],
          },
        }}
      />,
    )

    // The order is the data. Nothing here sorts, because a timeline sorted by anything but
    // time has stopped being one — which is exactly why this is not a table.
    const labels = screen.getAllByTestId('moment').map((li) => li.textContent)
    expect(labels[0]).toContain('run started')
    expect(labels[1]).toContain('tool completed')
    expect(labels[2]).toContain('run completed')
  })

  it('marks where it went wrong so a reader does not count rows to find it', () => {
    render(
      <TimelineView
        view={{
          kind: 'timeline',
          data: {
            moments: [moment(), moment({ label: 'tool failed', failed: true }), moment()],
          },
        }}
      />,
    )

    const failed = screen.getAllByTestId('moment')[1]!
    // Announced as well as coloured: a red dot is not a signal to a reader who cannot see
    // it, and "where did it stop" is the first question anybody asks of a run.
    expect(within(failed).getByText('failed')).toBeInTheDocument()
  })

  it('renders a detail verbatim, because it routinely quotes a ticket', () => {
    render(
      <TimelineView
        view={{
          kind: 'timeline',
          data: { moments: [moment({ detail: '**URGENT** the requester wrote this' })] },
        }}
      />,
    )

    expect(screen.getByText('**URGENT** the requester wrote this')).toBeInTheDocument()
    expect(document.querySelector('strong')).toBeNull()
  })

  it('keeps the line breaks of a detail that has them', () => {
    // A tool result or a stack trace on one line is a tool result nobody can read. The class
    // is the whole of the claim, so the claim is asserted rather than the class.
    render(
      <TimelineView
        view={{ kind: 'timeline', data: { moments: [moment({ detail: 'one\ntwo' })] } }}
      />,
    )

    const detail = screen.getByText(/one/)
    expect(detail.className).toContain('whitespace-pre-wrap')
    expect(detail.textContent).toBe('one\ntwo')
  })

  it('is reached through the registry', () => {
    render(<ViewOf view={{ kind: 'timeline', data: { moments: [moment()] } }} />)

    expect(screen.getByTestId('timeline-view')).toBeInTheDocument()
    expect(screen.queryByText(/no drawing for/)).not.toBeInTheDocument()
  })

  it('says nothing happened rather than drawing an empty frame', () => {
    render(<TimelineView view={{ kind: 'timeline', data: { moments: [] } }} />)

    expect(screen.getByText('Nothing happened yet.')).toBeInTheDocument()
  })

  it('survives a moment with nothing on it', () => {
    // A producer that left a field out has still produced a moment, and dropping it would
    // lose a step from a sequence whose whole value is being complete.
    render(<TimelineView view={{ kind: 'timeline', data: { moments: [{}, null] } }} />)

    expect(screen.getAllByTestId('moment')).toHaveLength(2)
  })
})
