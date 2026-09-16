import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApprovalCard } from '../components/ApprovalCard'
import { Markdown } from '../components/Markdown'
import { Verbatim } from '../components/Verbatim'
import { ViewOf } from '../views/registry'
import { plain, reorders } from './text'

/** Right-to-left override: everything after it renders backwards. */
const RLO = '‮'
/** Pop directional formatting, which is how the trick is hidden mid-line. */
const PDF = '‬'

/**
 * Text that is inert and still lies about what it says.
 *
 * <p>React escapes text nodes, so nothing a requester or a model wrote can become markup —
 * that is settled, and `escaping.test.tsx` holds it. This is the attack that survives escaping:
 * a bidirectional override reorders the glyphs without changing the string, so what a person
 * reads and what the console holds are different things and the difference is invisible.
 *
 * <p>Trojan Source, CVE-2021-42574. It matters here because this console's whole safety story
 * is that a person reads a tool call before it happens.
 */
describe('text that reorders itself', () => {
  it('takes out the formatting characters and nothing else', () => {
    expect(plain(`resolve${RLO}gpj.exe`)).toBe('resolvegpj.exe')
    expect(plain(`a${RLO}b${PDF}c`)).toBe('abc')
    expect(plain('⁦isolated⁩')).toBe('isolated')
    expect(plain('‎mark‏')).toBe('mark')

    // Not `\p{Cf}` wholesale, which is what Spotlight strips on the model's side. A zero-width
    // joiner is how an emoji sequence and several Indic and Arabic scripts are written, and a
    // console that stripped them would mangle a legitimate name to defend against a different
    // character.
    expect(plain('👩‍💻')).toBe('👩‍💻')
    expect(plain('क‍ष')).toBe('क‍ष')
    expect(plain('ordinary text')).toBe('ordinary text')
  })

  it('says whether a string carries one, the same way twice', () => {
    expect(reorders(`a${RLO}b`)).toBe(true)
    // A global regex keeps `lastIndex` between calls; asking twice has to give one answer.
    expect(reorders(`a${RLO}b`)).toBe(true)
    expect(reorders('ordinary')).toBe(false)
    expect(plain(`a${RLO}b`)).toBe('ab')
    expect(plain(`a${RLO}b`)).toBe('ab')
  })

  it('is gone from the two deliberate rendering paths', () => {
    const verbatim = render(<Verbatim text={`comment${RLO}txt.exe`} />)
    expect(verbatim.container.textContent).toBe('commenttxt.exe')
    expect(reorders(verbatim.container.textContent ?? '')).toBe(false)
    verbatim.unmount()

    const markdown = render(<Markdown text={`see ${RLO}gnp.exe for details`} />)
    expect(reorders(markdown.container.textContent ?? '')).toBe(false)
    markdown.unmount()
  })

  it('is gone from every widget that draws somebody else’s words', () => {
    const views = [
      { kind: 'cards', data: { cards: [{ title: `IT-1${RLO}`, subtitle: `sub${RLO}x`, fields: { body: `f${RLO}x` }, url: '' }] } },
      { kind: 'table', data: { columns: [{ name: 'a', type: 'text' }], rows: [[`cell${RLO}x`]] } },
      { kind: 'timeline', data: { moments: [{ kind: 'tool', label: `l${RLO}x`, detail: `d${RLO}x` }] } },
    ]

    for (const view of views) {
      const { container, unmount } = render(<ViewOf view={view as never} />)
      expect(reorders(container.textContent ?? ''), view.kind).toBe(false)
      unmount()
    }
  })

  it('is gone from the card a person reads before pressing Approve', () => {
    // The one that matters most. This card exists so somebody can see what a tool would do
    // before it does it; a comment body that renders as something other than what it is
    // defeats the entire point of putting it in front of them.
    render(
      <ApprovalCard
        decision={{
          id: 'ap-1',
          conversationId: 'c-1',
          turnId: 't-1',
          kind: 'ACTION',
          tool: 'alm.comment',
          arguments: { ticket_key: 'IT-1', body: `Closing this${RLO}.dessergorp on ,tnetnoc` },
          capability: 'workbench.operator',
          reason: `It would comment${RLO}.gnihton seod`,
          effect: `A comment${RLO}x`,
          reversible: false,
          question: '',
          askedAt: '2026-08-27T10:00:00Z',
        }}
        onDecide={() => {}}
      />,
    )

    const card = screen.getByTestId('approval-card')
    expect(reorders(card.textContent ?? '')).toBe(false)
  })
})
