import type { AgentInfo } from './types'

/** A line that gives one field, `name: value`, maybe as a list item — the rule the host reads a pasted form by. */
const FIELD_LINE = /^\s*(?:[-*]\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s?(.*)$/

/** What a message holds when it is an agent's form written out: whose, the first copy's values, and how many copies. */
export interface FormText {
  agent: AgentInfo
  values: Record<string, string | boolean>
  records: number
}

/**
 * The agent form a message writes out as `field: value` lines, read as the host reads it: a copy starts again where a
 * field it already has comes up again, and needs two fields filled in. Of the agents given, the one whose fields the
 * message names most; null when it is none of theirs.
 */
export function readForm(text: string, agents: AgentInfo[]): FormText | null {
  let best: FormText | null = null
  let bestNamed = 0
  for (const agent of agents) {
    const properties = agent.input?.properties
    if (!properties) {
      continue
    }
    const records: Record<string, string>[] = []
    let record: Record<string, string> = {}
    let seen = new Set<string>()
    const named = new Set<string>()
    for (const line of text.split(/\r?\n/)) {
      const match = FIELD_LINE.exec(line)
      const name = match?.[1]
      if (!match || !name || !(name in properties)) {
        continue
      }
      named.add(name)
      if (seen.has(name)) {
        if (Object.keys(record).length >= 2) {
          records.push(record)
        }
        record = {}
        seen = new Set()
      }
      seen.add(name)
      const value = (match[2] ?? '').trim()
      if (value) {
        record[name] = value
      }
    }
    if (Object.keys(record).length >= 2) {
      records.push(record)
    }
    const first = records[0]
    if (first && named.size > bestNamed) {
      const values: Record<string, string | boolean> = {}
      for (const [name, value] of Object.entries(first)) {
        values[name] = properties[name]?.type === 'boolean' ? /^(true|yes)$/i.test(value) : value
      }
      best = { agent, values, records: records.length }
      bestNamed = named.size
    }
  }
  return best
}
