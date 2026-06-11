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
   * Body copy shown below the title. On CarPlay it renders as the tab page's
   * centered text, and as the body of the gate alert when a button is
   * configured. Newlines collapse to spaces (CarPlay renders single labels).
   */
  message?: string
  /**
   * Title of the gate's action button. On CarPlay the button can't live on
   * the tab pages (tab children show neither rows-with-empty-view nor
   * navigation-bar buttons), so configuring it presents a modal alert — the
   * message as large centered text with this action plus a system-localized
   * OK — when the gate engages or CarPlay connects while gated. Dismissing
   * leaves the buttonless gate pages in the tabs. Omit for no alert (the
   * `onButtonPressed` callback is then never invoked).
   */
  buttonTitle?: string
}

/**
 * An app-imposed block on browsing from external surfaces (CarPlay,
 * Android Auto), set and cleared at runtime. While gated, tabs stay visible
 * but every tab's content — and external-surface search — is replaced by a
 * single full-page message. Playback, the queue, and now-playing are
 * unaffected: a gate blocks finding content, never hearing it.
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
