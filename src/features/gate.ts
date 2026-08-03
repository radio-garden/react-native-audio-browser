import type { SearchParams } from '../types/browser'
import { nativeBrowser } from '../native'
import { LazyNativeEmitter } from '../utils/LazyNativeEmitter'

// MARK: - Wire types (cross the Nitro bridge; native↔JS)

/** Discriminant for gate requests and events. */
export type GateReason = 'browse' | 'search'

/**
 * Flat wire struct for a gate request (crosses the Nitro bridge).
 * Use {@link GateRequest} in consumer-facing code; `gate.ts` converts between them.
 * @internal
 */
export type NativeGateRequest = {
  reason: GateReason
  path?: string
  search?: SearchParams
}

/**
 * Wire form of a resolver result (native awaits this from JS).
 * @internal
 */
export type GateDecision = { gated: boolean; gate?: Gate }

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
 * A gate's chrome (title and optional message).
 *
 * While the gate is active, every browse or search request on car surfaces
 * (CarPlay, Android Auto) shows this page instead of the real content.
 * Playback, the queue, and now-playing are unaffected.
 *
 * - **CarPlay**: plain centered message page.
 * - **Android Auto**: one non-playable list tile (message not shown).
 */
export type Gate = {
  /** Headline shown on the gate page. */
  title: string
  /**
   * The explanatory copy under the title. On CarPlay this is the centered
   * message body; newlines become spaces. Android Auto does not display it.
   */
  message?: string
}

/**
 * The gate chrome crosses the bridge as-is; this alias marks the wire side.
 * @internal
 */
export type NativeGate = Gate

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

let resolver: GateResolver | undefined

function toPublicRequest(req: NativeGateRequest): GateRequest {
  switch (req.reason) {
    case 'browse':
      return { kind: 'browse', path: req.path ?? '' }
    case 'search':
      // req.search is always present on a well-formed search request — the
      // native wire contract guarantees it for reason:'search'. Assert the
      // native contract with `!` so we never hand consumer code a SearchParams
      // with its required `query` field missing.
      return { kind: 'search', params: req.search! }
    default: {
      const _exhaustive: never = req.reason
      throw new Error(`Unhandled GateReason: ${_exhaustive}`)
    }
  }
}

// Native awaits this per request at a serve site.
// No resolver → static gate → every request is gated (native applies stored default / built-in).
nativeBrowser.resolveGate = async (
  req: NativeGateRequest
): Promise<GateDecision> => {
  const result = resolver ? resolver(toPublicRequest(req)) : true
  if (result === false) return { gated: false }
  if (result === true) return { gated: true }
  return { gated: true, gate: result }
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
    resolver = a
    nativeBrowser.setGate(undefined, true)
  } else {
    resolver = b
    nativeBrowser.setGate(a, b != null)
  }
}

/**
 * Drops the gate — the real content comes back, and the current tab is kept.
 */
export function clearGate(): void {
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
