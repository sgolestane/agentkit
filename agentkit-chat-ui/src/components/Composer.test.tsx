import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Composer } from './Composer'

/**
 * The box a person types in.
 *
 * Every one of these is a thing that is wrong in some chat somewhere: Enter that inserts a
 * newline instead of sending, Shift+Enter that sends, a whitespace-only message that starts a
 * turn, and a disabled input that does not say why it is disabled.
 */
describe('Composer', () => {
  it('sends on Enter and clears itself', async () => {
    const onSend = vi.fn()
    render(<Composer onSend={onSend} />)

    await userEvent.type(screen.getByLabelText('Message'), 'how many are open?{Enter}')

    expect(onSend).toHaveBeenCalledWith('how many are open?')
    expect(screen.getByLabelText('Message')).toHaveValue('')
  })

  it('adds a line on Shift+Enter rather than sending', async () => {
    const onSend = vi.fn()
    render(<Composer onSend={onSend} />)

    await userEvent.type(screen.getByLabelText('Message'), 'one{Shift>}{Enter}{/Shift}two')

    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Message')).toHaveValue('one\ntwo')
  })

  it('will not send whitespace', async () => {
    const onSend = vi.fn()
    render(<Composer onSend={onSend} />)

    await userEvent.type(screen.getByLabelText('Message'), '   {Enter}')

    expect(onSend).not.toHaveBeenCalled()
  })

  it('says why it is disabled rather than just being disabled', () => {
    render(
      <Composer
        onSend={vi.fn()}
        disabled
        disabledReason="No model is configured. Set CHAT_LLM, then restart."
      />,
    )

    const box = screen.getByLabelText('Message')
    expect(box).toBeDisabled()
    // A person whose console will not accept a message is owed the sentence that says why, and
    // the runtime has already written it.
    expect(box).toHaveAttribute('placeholder', expect.stringContaining('CHAT_LLM'))
  })

  it('takes back the old message when a person edits and resends', async () => {
    const { rerender } = render(<Composer onSend={vi.fn()} />)

    rerender(<Composer onSend={vi.fn()} initialText="the message I meant to send" />)

    expect(await screen.findByLabelText('Message')).toHaveValue('the message I meant to send')
  })
})
