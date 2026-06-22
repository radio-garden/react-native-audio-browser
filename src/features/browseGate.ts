import { nativeBrowser } from '../native'

// MARK: - Types

/**
 * The wire shape of a browse gate — what crosses the bridge and what
 * `getBrowseGate` returns. The button callback stays in JS (see `BrowseGate`).
 */
export type NativeBrowseGate = {
  /** Headline shown on the gate page. */
  title: string
  /**
   * Body copy shown below the title. On CarPlay with a button the page is a
   * row-less list whose enhanced section header carries the message in large
   * type: a newline splits it into the header's title and subtitle lines.
   * Without a button the message renders as the centered empty view, where
   * newlines collapse to spaces.
   */
  message?: string
  /**
   * Title of the action button — and the switch between CarPlay's two gate
   * layouts: **present** → a row-less list whose section header carries the
   * title/message with this button beside it; **omitted** → the centered
   * empty-view page with no button (and `onButtonPressed` is never invoked).
   * See `message` for how the body copy renders in each. iOS/CarPlay only —
   * Android Auto has no button or full-page surface.
   */
  buttonTitle?: string
}

/**
 * An app-imposed block on browsing from external surfaces (CarPlay,
 * Android Auto), set and cleared at runtime. While gated, tabs stay visible
 * but every tab's content — and external-surface search — is replaced by a
 * single message: a full-page centered view on CarPlay, a non-playable list
 * tile on Android Auto (the only in-browse signal its UI renders — buttons
 * and full-page messages don't exist there, so `buttonTitle` is iOS-only).
 * Voice-initiated search/play is refused on both platforms. Playback, the
 * queue, and now-playing are unaffected: a gate blocks finding content,
 * never hearing it.
 *
 * Generic by design: subscription, login, and region blocks are all
 * browse gates.
 */
export type BrowseGate = NativeBrowseGate & {
  /** Invoked when the user taps the gate page's button. */
  onButtonPressed?: () => void
}

// MARK: - Internal wiring

let buttonHandler: (() => void) | undefined
nativeBrowser.onBrowseGateButtonPressed = () => buttonHandler?.()

// MARK: - Getters and Setters

/**
 * Sets (or replaces) the browse gate. Calling this while a gate is already
 * set updates the page in place — no navigation reset, the selected tab is
 * kept. A gate set before an external surface connects renders immediately
 * at connect.
 */
export function setBrowseGate(gate: BrowseGate): void {
  const { onButtonPressed, ...nativeGate } = gate
  buttonHandler = onButtonPressed
  nativeBrowser.setBrowseGate(nativeGate)
}

/**
 * Clears the browse gate. Tab content is restored and the selected tab is
 * kept.
 */
export function clearBrowseGate(): void {
  buttonHandler = undefined
  nativeBrowser.clearBrowseGate()
}

/**
 * Gets the current browse gate, or undefined when not gated.
 */
export function getBrowseGate(): NativeBrowseGate | undefined {
  return nativeBrowser.getBrowseGate()
}
