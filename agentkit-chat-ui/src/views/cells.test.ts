import { describe, expect, it } from 'vitest'
import { compare, columnsOf, order, shown, toCsv } from './cells'

/**
 * How a cell is read, sorted and exported.
 *
 * Driven without a DOM, because none of it is about pixels: what absent looks like, where
 * absent sorts, and whether a comma in a value survives a round trip through CSV.
 */
describe('shown', () => {
  it('shows an absent value as absent rather than as the word null', () => {
    // The single most common way a data table lies about itself: `null` looks like a value,
    // sorts like a value, and somebody eventually reports it as one.
    expect(shown(null, 'text')).toBe('—')
    expect(shown(undefined, 'text')).toBe('—')
    expect(shown('', 'number')).toBe('—')
  })

  it('formats numbers and leaves identifiers alone', () => {
    expect(shown(1234567, 'number')).toBe(new Intl.NumberFormat().format(1234567))
    // The column said text, so it stays text — this is why the type is declared rather than
    // sniffed. `INC-0042` right-aligned as a number is a small lie to un-learn.
    expect(shown('INC-0042', 'text')).toBe('INC-0042')
  })

  it('reads a date and leaves one it cannot read', () => {
    expect(shown('2026-09-02T14:31:00Z', 'date')).toBe('2026-09-02 14:31')
    // "Invalid Date" is this code failing; "unknown" is the data.
    expect(shown('unknown', 'date')).toBe('unknown')
  })

  it('says yes and no rather than true and false', () => {
    expect(shown(true, 'bool')).toBe('yes')
    expect(shown(false, 'bool')).toBe('no')
  })
})

describe('compare', () => {
  it('orders numbers as numbers', () => {
    expect([10, 9, 100].sort((a, b) => compare(a, b, 'number'))).toEqual([9, 10, 100])
  })

  it('orders numbers the text collation would get wrong', () => {
    // Plain digits are not enough to prove the number branch exists: the text collation is
    // configured `numeric: true`, so it sorts 9, 10, 100 correctly too — and a mutation that
    // deleted the number branch entirely left that test green. Exponent notation is where the
    // two disagree, and a tool computing a rate or a total will produce it.
    expect(['1e3', '200'].sort((a, b) => compare(a, b, 'number'))).toEqual(['200', '1e3'])
    expect(['1e3', '200'].sort((a, b) => compare(a, b, 'text'))).toEqual(['1e3', '200'])
  })

  it('orders text naturally, so INC-9 comes before INC-10', () => {
    const keys = ['INC-10', 'INC-9', 'INC-1']
    expect(keys.sort((a, b) => compare(a, b, 'text'))).toEqual(['INC-1', 'INC-9', 'INC-10'])
  })

  it('sorts absent values last, whichever way the column points', () => {
    // Sorting them to the top on a descending pass fills the first screen with nothing, which
    // is never what somebody clicking a header wanted. Asserted through `order`, because the
    // direction has to be part of the comparison — the first version reversed the ascending
    // result instead, which flips this rule along with everything else.
    expect([3, null, 1].sort((a, b) => order(a, b, 'number', false))).toEqual([1, 3, null])
    expect([3, null, 1].sort((a, b) => order(a, b, 'number', true))).toEqual([3, 1, null])
  })
})

describe('toCsv', () => {
  it('quotes a value that would otherwise end the field or the row', () => {
    const csv = toCsv(columnsOf([{ name: 'summary', type: 'text' }]), [
      ['laptop, broken'],
      ['he said "no"'],
      ['line one\nline two'],
    ])

    expect(csv).toContain('"laptop, broken"')
    // Doubling an embedded quote is the CSV escape; a backslash is not.
    expect(csv).toContain('"he said ""no"""')
    expect(csv).toContain('"line one\nline two"')
  })

  it('writes an absent value as an empty field, not as a dash', () => {
    // The dash is for a person reading a screen. A spreadsheet opening this should see nothing.
    const csv = toCsv(columnsOf([{ name: 'a', type: 'text' }, { name: 'b', type: 'text' }]), [
      ['x', null],
    ])

    expect(csv.split('\n')[1]).toBe('x,')
  })
})
