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
   * The explanatory copy under the title. How it lays out on CarPlay depends
   * on whether there's a button:
   * - **with a button** → it sits in the page's header, and a single newline
   *   splits it into a bold line and a lighter line beneath.
   * - **without a button** → it's the centered message on an otherwise empty
   *   page, and newlines just become spaces.
   */
  message?: string
  /**
   * The label on the gate's action button (e.g. "Go Premium"). It also picks
   * the CarPlay layout: include it and you get a page with the button beside
   * the message; leave it out and you get a plain centered message with no
   * button (and `onButtonPressed` never fires). See `message` for how the copy
   * renders in each.
   *
   * iOS/CarPlay only — Android Auto can't show a button or a full-page message.
   */
  buttonTitle?: string
}

/**
 * A block you put on browsing from the car surfaces (CarPlay, Android Auto),
 * which you set and clear at runtime — a subscription wall, a login prompt, a
 * region block, whatever you need (it's generic by design).
 *
 * While it's up, the tabs stay where they are, but every tab's content — and
 * any voice or manual *search* — shows your single gate message instead:
 * - **CarPlay**: a full-page message, with an optional button.
 * - **Android Auto**: one non-playable list tile (no button or full page, so
 *   `buttonTitle` is iOS-only).
 *
 * It only blocks *finding* new content, never *hearing* it: whatever's
 * playing keeps playing, "resume" / "play this" still work by voice, and the
 * queue and now-playing are left alone.
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
 * Raises the gate, or updates it if one's already up. Updating is seamless —
 * the page changes in place, with no navigation reset and the current tab kept.
 * Set it before the car connects and it'll be there the moment it does.
 */
export function setBrowseGate(gate: BrowseGate): void {
  const { onButtonPressed, ...nativeGate } = gate
  buttonHandler = onButtonPressed
  nativeBrowser.setBrowseGate(nativeGate)
}

/**
 * Drops the gate — the tabs' real content comes back, and the current tab is
 * kept.
 */
export function clearBrowseGate(): void {
  buttonHandler = undefined
  nativeBrowser.clearBrowseGate()
}

/**
 * The gate that's currently up, or `undefined` if there isn't one.
 */
export function getBrowseGate(): NativeBrowseGate | undefined {
  return nativeBrowser.getBrowseGate()
}
