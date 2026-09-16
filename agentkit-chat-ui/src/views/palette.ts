/**
 * Which colour a series wears.
 *
 * <h4>The palette is validated, not chosen</h4>
 *
 * These eight are the reference categorical palette from the data-visualisation guidance,
 * checked with its own validator rather than by eye — lightness band, chroma floor, adjacent
 * colour-vision-deficiency separation, normal-vision floor and surface contrast, in both modes:
 *
 * <pre>
 * light  worst adjacent CVD ΔE 9.1   normal-vision ΔE 19.6   3 slots below 3:1 contrast
 * dark   worst adjacent CVD ΔE 8.4   normal-vision ΔE 19.3   all 8 above 3:1
 * </pre>
 *
 * <p>The light-mode contrast warning is why every chart here ships a legend with visible text
 * labels and a table of its own numbers: the rule is that a warning obligates relief, and both
 * of those are it. Identity is never carried by colour alone.
 *
 * <p>The dark column is the same eight hues re-stepped for a dark surface — a selected set, not
 * an automatic flip of the light one.
 *
 * <h4>Eight is the ADJACENT-pairs answer, and not every form gets it</h4>
 *
 * The numbers above compare neighbouring slots, which is the right question for a bar chart:
 * its marks sit side by side with a surface gap between them, so slot 2 never has to be told
 * apart from slot 7. A scatter is the other case — its marks are interleaved by the data and
 * any two clouds can overlap — so it must clear the floors for *every* pair, and under
 * `--pairs all` this palette does not:
 *
 * <pre>
 * light  8 slots  FAIL  CVD ΔE 3.2 (#008300↔#eb6834)   normal ΔE 7.1 (#e34948↔#eb6834)
 * light  3 slots  PASS  CVD ΔE 9.2                     normal ΔE 24.0
 * dark   4 slots  FAIL  CVD ΔE 4.8 · normal ΔE 10.6 (#c98500↔#d95926)
 * dark   3 slots  PASS  CVD ΔE 9.4                     normal ΔE 20.9
 * </pre>
 *
 * So `SCATTER_CLOUDS` is three, and it is three because the validator says so rather than
 * because three felt safe. See `scatterData.ts`.
 */
const LIGHT = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7', '#e34948']
const DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767']

/** What a ninth series wears, and every one after it. */
const OTHER_LIGHT = '#6b7280'
const OTHER_DARK = '#9aa2af'

/**
 * Which slot each series name has been given, in the order names were first seen.
 *
 * <h4>Colour follows the entity, never its rank</h4>
 *
 * Keyed on the series' NAME rather than its position, and remembered for the life of the page.
 * Two consequences, both of them the point:
 *
 * <ul>
 *   <li>The same series is the same colour in every chart of a conversation. An answer with a
 *       breakdown by category followed by a second chart of the same categories should not
 *       recolour them, or a reader compares the two by matching legends instead of by looking.
 *   <li>A chart drawn with one series missing does not repaint the survivors. If colour came
 *       from position, dropping the second of four would move the third and fourth up a slot,
 *       and every earlier screenshot of the same data would disagree with the new one.
 * </ul>
 */
const slots = new Map<string, number>()

/** Forgets every assignment. For tests, which must not depend on what ran before them. */
export function forgetSeriesColours(): void {
  slots.clear()
}

/**
 * The CSS colour for a series, in the mode asked for.
 *
 * <p>Past the eighth distinct series everything shares one neutral. A ninth generated hue is
 * indistinguishable from an existing one under colour-vision deficiency and breaks every check
 * the palette passed — so the honest answer is to stop colouring and let the legend carry
 * identity, which the guidance calls folding into "Other".
 */
export function seriesColour(name: string, dark: boolean): string {
  let slot = slots.get(name)
  if (slot === undefined) {
    slot = slots.size
    slots.set(name, slot)
  }
  if (slot >= LIGHT.length) {
    return dark ? OTHER_DARK : OTHER_LIGHT
  }
  return (dark ? DARK : LIGHT)[slot] as string
}

/**
 * The neutral a folded group wears, without taking a slot to get it.
 *
 * <p>Asked for by name rather than by passing a made-up series name to `seriesColour`: that
 * would enter the fake name in the slot map, hand it a real hue as the next free slot, and
 * shift every genuine series behind it. The fold is not a series and must not be counted as
 * one.
 */
export function foldedColour(dark: boolean): string {
  return dark ? OTHER_DARK : OTHER_LIGHT
}

/** How many distinct series have been coloured, so a caller can tell it has run out. */
export function colouredSoFar(): number {
  return slots.size
}

export const SERIES_SLOTS = LIGHT.length
