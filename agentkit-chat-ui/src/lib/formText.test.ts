import { describe, expect, it } from 'vitest'
import { readForm } from './formText'
import type { AgentInfo } from './types'

const onboarding: AgentInfo = {
  id: 'onboarding', name: 'Onboarding', description: '',
  input: { type: 'object', required: ['employee_id'], properties: {
    employee_id: { type: 'string' }, name: { type: 'string' }, rehire: { type: 'boolean' }, office: { type: 'string' },
  } },
}
const desk: AgentInfo = {
  id: 'desk', name: 'Desk', description: '',
  input: { type: 'object', properties: { name: { type: 'string' }, laptop: { type: 'string' } } },
}

describe('A form written out in a message', () => {
  it('is read as the agent whose fields it names most, with its first copy\'s values', () => {
    const read = readForm('Onboard this employee:\nemployee_id: W-1001\nname: Ravi Menon\nrehire: false\noffice:',
      [desk, onboarding])
    expect(read?.agent.id).toBe('onboarding')
    expect(read?.values).toEqual({ employee_id: 'W-1001', name: 'Ravi Menon', rehire: false })
    expect(read?.records).toBe(1)
  })

  it('counts its copies, and is nothing when it names under two fields', () => {
    expect(readForm('employee_id: W-1\nname: A\n\nemployee_id: W-2\nname: B', [onboarding])?.records).toBe(2)
    expect(readForm('Note: my name: Ravi', [onboarding])).toBeNull()
    expect(readForm('Please onboard Ravi.', [onboarding, desk])).toBeNull()
  })
})
