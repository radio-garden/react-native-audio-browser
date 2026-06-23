import { nativeBrowser } from '../native'
import type { SearchParams } from '../types/browser'
import { LazyNativeEmitter } from '../utils/LazyNativeEmitter'

// MARK: - Wire types (cross the Nitro bridge; native↔JS)

/** Discriminant for gate requests and events. */
export type GateReason = 'browse' | 'search'

/**
 * The wire shape of the gate chrome — what crosses the bridge.
 * The button callback stays in JS (see {@link Gate}).
 */
export type NativeGate = {
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
   * The label on the gate's action button. It also picks the CarPlay layout:
   * include it and you get a page with the button beside the message; leave it
   * out and you get a plain centered message with no button (and
   * `onButtonPressed` never fires). See `message` for how the copy renders in
   * each.
   *
   * iOS/CarPlay only — Android Auto can't show a button or a full-page message.
   */
  buttonTitle?: string
}

/**
 * Flat wire struct for a gate request (crosses the Nitro bridge).
 * Use {@link GateRequest} in consumer-facing code; `gate.ts` converts between them.
 */
export type NativeGateRequest = {
  reason: GateReason
  path?: string
  search?: SearchParams
}

/** Wire form of a resolver result (native awaits this from JS). */
export type GateDecision = { gated: boolean; gate?: NativeGate }

/**
 * Fired once per gated serve (each time the gate chrome is rendered for a
 * request). This is **not** once per user action: on car surfaces a single
 * action can serve the gate for several requests at once — e.g. building the
 * tab bar resolves and serves each gated tab, firing one event per gated tab.
 * There is no library-side dedup; debounce on the consumer side (e.g. record a
 * gate-hit at most once per session).
 */
export type GateEvent = { reason: GateReason }

// MARK: - Public types (consumer-facing)

/**
 * A gate's chrome plus its JS-only button handler.
 *
 * While the gate is active, every browse or search request on car surfaces
 * (CarPlay, Android Auto) shows this page instead of the real content.
 * Playback, the queue, and now-playing are unaffected.
 *
 * - **CarPlay**: full-page message, with an optional button.
 * - **Android Auto**: one non-playable list tile (no button or full page, so
 *   `buttonTitle` is iOS-only).
 */
export type Gate = NativeGate & {
  /** Invoked when the user taps the gate page's button. */
  onButtonPressed?: () => void
}

/**
 * Describes the request being decided by a {@link GateResolver}.
 * Discriminated by `kind`: `'browse'` for content navigation, `'search'` for
 * voice/manual search queries.
 */
export type GateRequest =
  | { kind: 'browse'; path: string }
  | { kind: 'search'; params: SearchParams }

/**
 * Decides per request whether to gate.
 * - Return a {@link Gate} to gate with specific chrome for this request.
 * - Return `true` to gate using the default chrome (or the built-in fallback).
 * - Return `false` to allow the request through.
 */
export type GateResolver = (request: GateRequest) => Gate | boolean

// MARK: - Internal state

let buttonHandler: (() => void) | undefined
let resolver: GateResolver | undefined

nativeBrowser.onGateButtonPressed = () => buttonHandler?.()

function toPublicRequest(req: NativeGateRequest): GateRequest {
  return req.reason === 'browse'
    ? { kind: 'browse', path: req.path ?? '' }
    : { kind: 'search', params: (req.search ?? {}) as SearchParams }
}

// Native awaits this per request at a serve site.
// No resolver → static gate → every request is gated (native applies stored default / built-in).
nativeBrowser.resolveGate = async (
  req: NativeGateRequest
): Promise<GateDecision> => {
  const result = resolver ? resolver(toPublicRequest(req)) : true
  if (result === false) return { gated: false }
  if (result === true) return { gated: true }
  const { onButtonPressed, ...nativeGate } = result // per-request chrome override
  buttonHandler = onButtonPressed
  return { gated: true, gate: nativeGate }
}

// MARK: - Public API

/**
 * Raises the gate with default chrome, optionally with a per-request resolver.
 * Calling it again while a gate is already up updates the chrome in place,
 * keeping the current tab. Note this is **not** fully navigation-preserving: on
 * some surfaces (CarPlay) any gate change pops pushed navigation back to root,
 * so a page the user had drilled into is reset to the tab root. Set the gate
 * before the car connects and it'll be there the moment it does.
 */
export function setGate(gate: Gate, resolve?: GateResolver): void
/**
 * Raises the gate with a resolver only (no default chrome).
 * The resolver must return a {@link Gate} or `true` for gated requests;
 * native uses the built-in fallback chrome when `true` and no default is set.
 */
export function setGate(resolve: GateResolver): void
export function setGate(a: Gate | GateResolver, b?: GateResolver): void {
  if (typeof a === 'function') {
    buttonHandler = undefined
    resolver = a
    nativeBrowser.setGate(undefined, true)
  } else {
    const { onButtonPressed, ...nativeGate } = a
    buttonHandler = onButtonPressed
    resolver = b
    nativeBrowser.setGate(nativeGate, b != null)
  }
}

/**
 * Drops the gate — the real content comes back, and the current tab is kept.
 */
export function clearGate(): void {
  buttonHandler = undefined
  resolver = undefined
  nativeBrowser.clearGate()
}

/**
 * Subscribes to gate events (fired when a request is gated).
 * @returns Cleanup function to unsubscribe.
 */
export const onGate = LazyNativeEmitter.emitterize<GateEvent>(
  (cb) => (nativeBrowser.onGate = cb)
)
