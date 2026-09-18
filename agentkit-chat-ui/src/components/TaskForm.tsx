import { useState } from 'react'
import type { InputField, InputSchema } from '../lib/types'

/**
 * The form that starts a task agent's work: its input's fields, as the agent's definition declares
 * them.
 *
 * <p>Collapsible, and open by default only while the conversation is empty: a form is the natural
 * way to begin a task, and in the way once the conversation is going. The server checks the input
 * and says what is wrong in one sentence, which the console shows like any refusal; the form only
 * marks what is required, so a person is not blocked by a check the server would make anyway.
 */
export function TaskForm({
  schema,
  agentName,
  startOpen,
  disabled,
  onSubmit,
}: {
  schema: InputSchema
  agentName: string
  startOpen: boolean
  disabled: boolean
  onSubmit: (input: Record<string, unknown>) => void
}) {
  const fields = Object.entries(schema.properties)
  const required = new Set(schema.required ?? [])
  const [values, setValues] = useState<Record<string, string | boolean>>({})

  const set = (name: string, value: string | boolean) => setValues((current) => ({ ...current, [name]: value }))

  const submit = () => {
    const input: Record<string, unknown> = {}
    for (const [name, field] of fields) {
      const value = values[name]
      if (field.type === 'boolean') {
        input[name] = value === true
      } else if (typeof value === 'string' && value.trim() !== '') {
        input[name] = field.type === 'integer' || field.type === 'number' ? Number(value) : value.trim()
      }
    }
    onSubmit(input)
  }

  return (
    <details
      open={startOpen}
      className="mx-4 mb-2 rounded-lg border border-line bg-panel text-sm"
      data-testid="task-form"
    >
      <summary className="cursor-pointer select-none px-3 py-2 text-muted hover:text-ink">
        Start {agentName} with its form
      </summary>
      <form
        className="grid gap-3 px-3 pb-3 sm:grid-cols-2"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        {fields.map(([name, field]) => (
          <Field
            key={name}
            name={name}
            field={field}
            required={required.has(name)}
            value={values[name]}
            onChange={(value) => set(name, value)}
          />
        ))}
        <div className="sm:col-span-2">
          <button
            type="submit"
            disabled={disabled}
            className="rounded bg-accent px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            Start
          </button>
        </div>
      </form>
    </details>
  )
}

function Field({
  name,
  field,
  required,
  value,
  onChange,
}: {
  name: string
  field: InputField
  required: boolean
  value: string | boolean | undefined
  onChange: (value: string | boolean) => void
}) {
  const id = `task-${name}`
  // A yes-or-no is always answered, so marking it required would only look like a question.
  const marked = required && field.type !== 'boolean'
  const label = (
    <span>
      {field.title ?? name}
      {marked ? <span className="text-bad"> *</span> : null}
    </span>
  )
  const hint = field.description ? <span className="block text-xs text-muted">{field.description}</span> : null
  const control = 'mt-1 w-full rounded border border-line bg-canvas px-2 py-1 outline-none focus:border-accent'

  if (field.type === 'boolean') {
    return (
      <label htmlFor={id} className="flex items-start gap-2">
        <input
          id={id}
          type="checkbox"
          checked={value === true}
          onChange={(event) => onChange(event.target.checked)}
          className="mt-1"
        />
        <span>
          {label}
          {hint}
        </span>
      </label>
    )
  }
  if (field.enum) {
    return (
      <label htmlFor={id} className="block">
        {label}
        <select
          id={id}
          required={required}
          value={typeof value === 'string' ? value : ''}
          onChange={(event) => onChange(event.target.value)}
          className={control}
        >
          <option value="">{required ? 'Choose…' : '—'}</option>
          {field.enum.map((choice) => (
            <option key={choice} value={choice}>
              {choice}
            </option>
          ))}
        </select>
        {hint}
      </label>
    )
  }
  const type =
    field.type === 'integer' || field.type === 'number' ? 'number' : field.format === 'date' ? 'date' : field.format === 'email' ? 'email' : 'text'
  return (
    <label htmlFor={id} className="block">
      {label}
      <input
        id={id}
        type={type}
        step={field.type === 'integer' ? 1 : undefined}
        required={required}
        value={typeof value === 'string' ? value : ''}
        onChange={(event) => onChange(event.target.value)}
        className={control}
      />
      {hint}
    </label>
  )
}
