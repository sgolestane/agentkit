import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DiffView } from './DiffView'
import { ViewOf } from './registry'

describe('DiffView', () => {
  it('says how many lines differ, because that is the question', () => {
    render(
      <DiffView
        view={{
          kind: 'diff',
          data: { title: 'The comment', before: 'one\ntwo\nthree', after: 'one\nTWO\nthree' },
        }}
      />,
    )

    expect(screen.getByText('The comment')).toBeInTheDocument()
    expect(screen.getByText('1 line(s) differ')).toBeInTheDocument()
  })

  it('says plainly when a change changes nothing', () => {
    render(<DiffView view={{ kind: 'diff', data: { before: 'same', after: 'same' } }} />)

    expect(screen.getByText('nothing changes')).toBeInTheDocument()
  })

  it('shows a line that only one side has', () => {
    render(<DiffView view={{ kind: 'diff', data: { before: 'one', after: 'one\ntwo' } }} />)

    expect(screen.getByText('two')).toBeInTheDocument()
    expect(screen.getByText('1 line(s) differ')).toBeInTheDocument()
  })

  it('over-reports rather than under-reports, and that is the safe direction', () => {
    // A positional walk, not a diff algorithm: an insertion at the top marks everything after
    // it. Under-reporting is the failure that matters — it is the one where somebody approves
    // a change they were not shown.
    render(<DiffView view={{ kind: 'diff', data: { before: 'b\nc', after: 'a\nb\nc' } }} />)

    expect(screen.getByText('3 line(s) differ')).toBeInTheDocument()
  })

  it('is reached through the registry', () => {
    render(<ViewOf view={{ kind: 'diff', data: { before: 'a', after: 'b' } }} />)

    expect(screen.getByTestId('diff-view')).toBeInTheDocument()
  })
})
