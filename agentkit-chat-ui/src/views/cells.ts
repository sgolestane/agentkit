import { plain } from '../lib/text'

/**
 * How one cell of a table is read and shown.
 *
 * The column declares its own type — `View.Column.number(...)` on the Java side — rather than
 * having it sniffed here. Sniffing gets an identifier that happens to be digits wrong, and
 * `INC-0042` right-aligned as a number is a small lie that a person then has to un-learn.
 */
export type ColumnType = 'text' | 'number' | 'date' | 'bool'

export interface Column {
  name: string
  type: ColumnType
}

/** What a column says it is, defaulting to text for anything unrecognised. */
export function columnsOf(raw: unknown): Column[] {
  if (!Array.isArray(raw)) {
    return []
  }
  return raw.map((entry) => {
    const record = (entry ?? {}) as Record<string, unknown>
    const type = String(record.type ?? 'text')
    return {
      name: String(record.name ?? ''),
      type: (['text', 'number', 'date', 'bool'] as const).includes(type as ColumnType)
        ? (type as ColumnType)
        : 'text',
    }
  })
}

export function rowsOf(raw: unknown): unknown[][] {
  return Array.isArray(raw) ? raw.map((row) => (Array.isArray(row) ? row : [row])) : []
}

const NUMBER = new Intl.NumberFormat()

/**
 * A cell, as a person should read it.
 *
 * <h4>Absent is not the word "null"</h4>
 *
 * A missing value rendered as `null` is the single most common way a data table lies about
 * itself: it looks like a value, it sorts like a value, and somebody eventually reports it as
 * one. An em dash says "nothing here" and cannot be mistaken for content.
 */
export function shown(value: unknown, type: ColumnType): string {
  return plain(rendered(value, type))
}

/** The value as this column reads it, before it is made safe to look at. */
function rendered(value: unknown, type: ColumnType): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  if (type === 'bool') {
    return value === true || value === 'true' ? 'yes' : 'no'
  }
  if (type === 'number') {
    const asNumber = typeof value === 'number' ? value : Number(value)
    return Number.isFinite(asNumber) ? NUMBER.format(asNumber) : String(value)
  }
  if (type === 'date') {
    const parsed = new Date(String(value))
    // Left alone when it will not parse. A date column carrying "unknown" should show
    // "unknown", not "Invalid Date" — the first is the data, the second is this code failing.
    return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toISOString().slice(0, 16).replace('T', ' ')
  }
  return String(value)
}

/** Whether a cell is absent, which is a different question from what it looks like. */
export function isAbsent(value: unknown): boolean {
  return value === null || value === undefined || value === ''
}

/**
 * Orders two cells of a column, in the direction asked for.
 *
 * <h4>Absent sorts last both ways, and reversing cannot do that</h4>
 *
 * The first version sorted ascending and called `.reverse()` for descending, which flips the
 * absent rule with everything else — so a descending pass filled the first screen with empty
 * cells, which is never what somebody clicking a header wanted. Direction has to be part of the
 * comparison rather than applied to its result.
 */
export function order(a: unknown, b: unknown, type: ColumnType, descending: boolean): number {
  const aAbsent = isAbsent(a)
  const bAbsent = isAbsent(b)
  if (aAbsent || bAbsent) {
    return aAbsent && bAbsent ? 0 : aAbsent ? 1 : -1
  }
  return descending ? -compare(a, b, type) : compare(a, b, type)
}

/** Orders two present cells of a column, ascending. */
export function compare(a: unknown, b: unknown, type: ColumnType): number {
  const aAbsent = isAbsent(a)
  const bAbsent = isAbsent(b)
  if (aAbsent || bAbsent) {
    return aAbsent && bAbsent ? 0 : aAbsent ? 1 : -1
  }
  if (type === 'number') {
    return Number(a) - Number(b)
  }
  if (type === 'date') {
    return new Date(String(a)).getTime() - new Date(String(b)).getTime()
  }
  if (type === 'bool') {
    return Number(a === true || a === 'true') - Number(b === true || b === 'true')
  }
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' })
}

/** The rows as a CSV a spreadsheet will open. */
export function toCsv(columns: Column[], rows: unknown[][]): string {
  const escape = (value: unknown) => {
    if (isAbsent(value)) {
      return ''
    }
    const text = String(value)
    // Quoted when it contains anything that would otherwise end the field or the row. Doubling
    // an embedded quote is the CSV escape, not a backslash.
    return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  return [
    columns.map((column) => escape(column.name)).join(','),
    ...rows.map((row) => columns.map((_, index) => escape(row[index])).join(',')),
  ].join('\n')
}
