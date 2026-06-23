# Gate (formerly Browse Gate) — Design

**Date:** 2026-06-23
**Library:** react-native-audio-browser
**Status:** Settled. Supersedes `2026-06-22-content-gating-model-proposal.md` (the throw-based / per-surface model explored there is abandoned — see Decision log).

## Summary

Rename **Browse Gate → Gate**. Keep the gate global and imperative. Add two things:

1. A per-request **`GateResolver`** so a gate can apply to *some* browse paths / search interactions and not others (surface-independent).
2. An **`onGate`** notification event so a consumer can record a gate-hit and act later (e.g. a deferred upsell on a surface that can run a purchase flow).

Defer all per-*surface* divergence (different content/result per surface) — it isn't needed while the library is the car/system-integration layer and the app renders its own browse UI ("Setup A").

## API

```ts
type GateRequest =
  | { kind: 'browse'; path: string }
  | { kind: 'search'; params: SearchParams }

type Gate = {
  title: string
  message?: string
}

// Per-request decision:
//   Gate  → gate this request with this chrome (may vary per request)
//   true  → gate with the default chrome (the `gate` arg), or a built-in minimal gate if none
//   false → let this request through
type GateResolver = (request: GateRequest) => Gate | boolean

function setGate(gate: Gate, resolve?: GateResolver): void   // default chrome + optional predicate; omit resolve → gate everything
function setGate(resolve: GateResolver): void                // resolver only, no default chrome
function clearGate(): void

type GateEvent = { reason: 'browse' | 'search' }
onGate?: (event: GateEvent) => void                 // on BrowserConfiguration, peer to onRemote*
```

The resolver is scoped to the active gate and cleared by `clearGate`. When `resolve` returns `true` and no default chrome was set (the resolver-only overload), the library renders a **built-in minimal gate**. There is intentionally **no `getGate`** — a consumer holds its own gate state; the library exposes no read-back. (The native enforcement sites keep their own internal gate state regardless.)

## How it works

The gate stays global imperative state (`setGate` / `clearGate`), unchanged from today. The only new logic is at the **four existing car-only enforcement sites**: when a gate is set, the site consults the resolver for the `request` before serving anything.

| Site | Platform | Kind | `GateRequest` |
|---|---|---|---|
| CarPlay push-block (`CarPlayController.swift` ~693) | iOS | browse | `{ kind: 'browse', path }` |
| Siri `INPlayMediaIntent` refuse (`HybridAudioBrowser.swift` ~1668) | iOS | search | `{ kind: 'search', params }` |
| Android Auto gate tile (`MediaSessionCallback.kt` ~270, `onGetChildren`) | Android | browse | `{ kind: 'browse', path }` |
| Android Auto search refuse (`MediaSessionCallback.kt` ~565, `onSearch`) | Android | search | `{ kind: 'search', params }` |

- `setGate(gate)` with a bare `Gate` and no resolver → every request is gated with that chrome (today's behaviour, unchanged).
- resolver returns a `Gate` → serve that gate **and** fire `onGate({ reason })`.
- resolver returns `true` → serve the default chrome (or built-in if none) **and** fire `onGate({ reason })`.
- resolver returns `false` → serve normally; no event.

Example — block browsing, but let a "play my favorites" Siri intent through:

```ts
setGate(
  { title: 'Radio Premium', message: 'Subscribe to browse in the car.' },
  (req) => req.kind === 'browse' || req.params.reference !== 'my',
)
```

(Resume is never gated — it doesn't pass through these sites at all.)

## Why this is cache-safe and cheap

The resolver is **surface-independent**: the same `(request)` yields the same decision on every car surface. That is what keeps it cheap, and distinguishes it from the abandoned per-surface model:

- The resolver runs **before** the cache and is a pure function of `(request, app state)`. A gated request never serves cached content; an allowed request serves normal cached content. **No surface dimension in any cache key, no leak.**
- It is a function at the serve site that **returns** (a `Gate`, `true`, or `false`), not a `throw` inside a content callback — so it can never unwind and break an in-flight load.
- Under Setup A the four enforcement sites are **car-only**, so the resolver never runs for an in-app request — no app-vs-car ambiguity.

## Implementation note

The resolver is a **synchronous native→JS call on the serve path**, which has watchdogs (CarPlay ~15s; Android `awaitBrowser` timeout). It must be synchronous and fast — no network in the resolver. Nitro/JSI supports sync callbacks; this is a different bridge pattern from the async, fire-and-forget `onButtonPressed`.

**Resolver errors fail CLOSED.** If the resolver throws/rejects, or the native→JS hop rejects for an infrastructure reason (bridge tear-down on a JS reload, the JS runtime mid-reload, a serialization error), the serve site **gates** the request with the stored default / built-in chrome rather than serving content. A gate exists to withhold content, so "I can't decide" must withhold, not leak — serving on error would be a content/paywall bypass. This matches the no-resolver static path, which already gates by default. A *successful* resolver returning `false` still allows the request. Consumers needing advisory/upsell semantics that must never block on error should treat the gate as enforcing and not rely on fail-open.

`onGate` fires **once per gated serve** with no library-side dedup — i.e. each time the gate chrome is rendered for a request, **not** once per user action. On CarPlay a single action can serve the gate for several requests at once: building the tab bar resolves and serves each gated tab, so one tab-bar (re)build emits one `onGate` per gated tab (up to one per tab). A consumer that wants "once per session" debounces on its side. `GateEvent` is a struct (not a bare enum arg) so `path?` / `search?` / `surface?` can be added later without a breaking change.

## Cut / deferred — with the seams left open

- **Different content per surface** (Android Auto sees a different list than the app) — deferred. It is the only piece that forces a `SurfaceId` cache key, composite-key invalidation, and a **track-cache quarantine** (the track cache is shared with playback; a divergent variant leaking in = wrong-track-on-resume). Out of scope while the library is the car layer. *Seam:* the future shape is a `SurfaceHook(ctx, request) => BrowseDecision` where `ctx = { id: SurfaceId, rendering }` and today's global gate is the degenerate `() => ({ kind: 'gate', gate })`.
- **Layout adaptation** (`imageRow`→list, item caps) — renderer-internal, *not* gate work; the renderer already adapts the one canonical result per surface.
- **`configure` / `setEnabled` split, `carConnected` / `path` / `search` on `GateEvent`** — additive later if a real need appears.

## Decision log (so the reasoning isn't re-litigated)

- **`throw GateError` abandoned.** A throw unwinds the stack and aborts the whole in-flight load, not one item. Use a serve-site predicate that *returns* instead — which is also the shape the existing gate already has (an imperative substitution, not a throw).
- **Surface model abandoned for v1.** "Surface" conflates a *location* axis (phone/car) and a *modality* axis (touch/voice) that the platforms don't expose cleanly (a Siri media intent can't reveal CarPlay-vs-phone). A clean surface model is only *needed* for per-surface **content** divergence — which Setup A drops — so building it now would ship an unsolved sub-design half-built. Non-breaking to add later (library unshipped).
- **per-request ≠ per-surface.** Per-request, surface-*independent* gating (the `GateResolver`) is cache-safe and cheap. Per-*surface* divergence is what forces surface-keyed caches. We take the former, defer the latter.
- **Setup A vs B.** A car list usually differs from an app list, but that difference is normally *two content sources* (car via the library, app in the consumer's own UI), not one library pipeline diverging by surface. The library stays the car/system layer (A); driving in-app browse through the library and diverging by surface (B) is the deferred case.
