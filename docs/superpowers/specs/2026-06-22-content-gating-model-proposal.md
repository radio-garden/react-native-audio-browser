# Proposal: Content Gating as a Returnable Result

**Status:** proposal (seeking review)
**Library:** react-native-audio-browser

## Problem

Today, gating is a **hard native refuse**: when a consumer sets a Browse Gate, the library refuses voice/external **search** (the funnel short-circuits and returns failure). Three problems:

1. **It over-blocks.** On iOS, the play-media funnel checks the gate *before* the resume branch, so "resume" / "play this" / "play «App»" are also refused. But those are *hearing* an already-active/persisted track — which the gate's own contract says it must **never** block ("blocks finding content, never hearing it"). (Android's `playFromSearch` is search-only, so its resume path — `onPlay` — isn't affected; the over-block is iOS-specific.)
2. **No consumer control.** The library decides to refuse — but only the consumer knows which content is free vs premium, logged-in-only, region-locked, etc. The library is making a policy call without the information to make it.
3. **It can't serve "free while gated."** A subscription-gated app may still want voice "play «free station»" to work while premium browse is gated. Hard-refuse blocks everything.

A known, **inherent** limitation (not solved here): voice failure is opaque — the in-app funnel returns success/failure and can't *speak* the gate reason (no Intents extension / resolve phase, per ADR 0002). Browse shows the gate message; voice can only fail.

## Background (what exists today)

- **Browse Gate** — imperative, global: the app sets a gate (with a message); the library auto-applies it to browse (every tab's content becomes the message) and to external search (refuse). Generic by design (subscription / login / region are all Browse Gates).
- **`BrowseError`** — a browse callback may return `{ error: string }` to surface a message on CarPlay / Android Auto (and an in-app `NavigationError`). So **return-based signaling already exists**.
- **Search source** — `SearchSourceCallback` (`(params) => Promise<Track[]>`) **or** `TransformableRequestConfig` (HTTP).
- **`transform` / `transformSync`** — the final step on every outbound request (browse / search / media / artwork). Where config-form consumers do dynamic logic (read local state, reshape the request).

## Proposal

Make **"gated" a value a consumer hook can return**, at every layer where the consumer already has a function — unifying gating under one primitive instead of a native pre-check.

### Three layers (two are the same primitive)

1. **Imperative Browse Gate** *(keep, unchanged)* — coarse, global, set-and-forget. Best for "gate everything" and for config-form consumers who don't want per-request logic.
2. **`GatedResult` from a content callback** (browse / search) — fine-grained, decided by what was resolved.
3. **`GatedResult` from `transform`** (browse / search / media) — fine-grained, on the request pipeline. This is the config-form counterpart to (2): a `TransformableRequestConfig` consumer gates in `transform`, where they already branch. A **`media` transform** returning gated = per-track **playback** gating.

(2) and (3) are the **same** primitive — "return gated instead of the normal result" — at the callback layer and the request-pipeline layer.

### Shape — throw, don't return

The consumer signals gating by **throwing a recognized error** from any hook,
rather than widening every return type:

```ts
class GatedError extends Error { /* optional: type / message */ }

// from any hook — transform, browse callback, search callback, resolve:
throw new GatedError()
```

Why throw beats a returned `GatedResult` / a `gated` flag on `BrowseError`:

- **No return-type widening.** `transform` stays `(request) => RequestConfig`;
  content callbacks keep `ResolvedTrack | Track[] | BrowseError`. No new union
  member to narrow anywhere — this was the worst ergonomic smell in review.
- **Uniform across every hook** with zero signature changes — they can all
  already throw.
- **Composes with existing error handling** — the library already catches thrown
  callback errors → `NavigationError`; `GatedError` is just a recognized subclass
  routed to the gate UX (a `gated` code) instead of a generic error.
- **Resume-safe by the existing code.** Cold resume wraps queue expansion in
  `try? await expandQueueFromContextualUrl(...)` with a single-track fallback, so
  a `GatedError` thrown during resume-time resolution is swallowed → resume still
  plays. Gating can't block hearing.

### Behavior per surface

- **CarPlay / Android Auto browse** → render the gate message (same as the imperative gate).
- **Voice / external search** → refuse playback; surface the message where the surface allows.
- **In-app** → surfaced as a `NavigationError` (a `gated` code) for the app to render.

### Layering / short-circuit

- `transform`s run base-up (`request` → kind → route). **Any** transform returning gated → the request is gated; stop the stack (first-gate-wins).
- A content callback returning gated → that resolution is gated.

### Resume-safety (by construction)

Gating lives on the **find / resolve** path. The native **resume** branch has no callback and no transform, so it is **never** reachable by this mechanism — the model is resume-safe by design. (The legacy imperative-gate iOS over-block is a separate fix; see below.)

### Interaction with the imperative gate

- Imperative gate remains for coarse/global and config-form "gate everything."
- `GatedResult` is the fine-grained, dynamic complement.
- If both apply, gated wins (any signal gates).

### What the consumer gains

- The gate decision moves to where the free/premium knowledge actually lives.
- "Serve free, block premium" becomes ordinary: don't gate; just return the free subset.
- Uniform mechanism across callback and config sources, and across browse / search / media.

### What the consumer takes on

- **Bypass-prevention shifts to the consumer.** Today the native refuse is fail-safe; a consumer literally cannot leak gated content via voice. With delegation, a consumer who ignores the gate and returns premium content bypasses their own gate. Since they set the gate and own the free/premium knowledge, this is arguably the correct owner — but it is a deliberate shift and must be documented prominently.

## Open questions (for review)

1. **Three mechanisms — too many?** Should the imperative gate eventually be expressible *as* a `GatedResult` (one model), or is the coarse imperative gate worth keeping distinct?
2. **Shape.** `{ gated: true; message? }` vs folding into `BrowseError` (e.g. `{ error, gated? }`)? Should it carry its own message or reuse the imperative gate's message?
3. **Scope of hooks.** Just `transform`, or also `resolve` (the per-track `RequestConfigResolver`)?
4. **Media gating.** Per-track playback gating via the `media` transform — in scope now, or defer?
5. **Fail-safe.** Is losing the library's bypass-prevention acceptable, or should there be an opt-in (e.g. the library still refuses by default unless the consumer opts into handling gated)?
6. **Naming.** `GatedResult` / `gated: true` — better names?

## Review outcome & revised direction (2026-06-22)

Three independent reviews (API-ergonomics, security/correctness, YAGNI) plus the product framing — keep Siri search free; gate the *car* experience and convert via a deferred upsell (GitLab radiogarden/mono#3302) — converged:

- **Mechanism: `throw GatedError`** (see Shape), not a returned `GatedResult` / `gated` flag — preferred when a consumer needs per-request dynamic gating.
- **Fail-closed default.** The imperative Browse Gate stays the default; throwing is opt-in. Don't silently shift bypass-prevention to the consumer.
- **Defer** media/playback gating (real-time state machine, no message channel), `resolve` gating, and a gate-message field — all speculative.
- **The near-term need is smaller than this proposal:** the imperative gate + an **`onBrowseGate` gate-hit event** (browse/search) feeding a **deferred upsell** on next app open (#3302). Skip-hits come from the consumer's existing remote-skip interception, not the Browse Gate.
- **`throw GatedError` and `onBrowseGate` compose:** a thrown `GatedError` fires the same `onBrowseGate(type)` event + gate UX as the imperative gate, so building `onBrowseGate` now doesn't foreclose throw-gating later.

**Build now:** `onBrowseGate` event + deferred upsell. **Park:** consumer-delegated `throw GatedError` until a consumer needs per-request gating.

## Separate change — DONE

The iOS **resume over-block** is a straight bug: the funnel gated the resume branch, but resume is *hearing*. **Fixed** — gate only the search branch (iOS-only; Android's resume path never checked the gate).
