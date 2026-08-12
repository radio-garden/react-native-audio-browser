# Fix Wave A Report

**Branch:** `feature-fry-gate`  
**Date:** 2026-06-23

## What was removed

### Gate button (review-ts C1)

- **`src/specs/audio-browser.nitro.ts`**: deleted `onGateButtonPressed: () => void` from the gate block.
- **`src/features/gate.ts`**:
  - `NativeGate` stripped of `buttonTitle?: string`; doc updated to describe plain centered message.
  - `Gate` is now `export type Gate = NativeGate` (alias; no `onButtonPressed` field).
  - Module-global `buttonHandler` and `nativeBrowser.onGateButtonPressed = () => buttonHandler?.()` both removed.
  - `setGate`: removed `const { onButtonPressed, ...nativeGate } = a` destructuring — `a` (a `Gate`/`NativeGate`) is now passed directly to `nativeBrowser.setGate(a, b != null)`. Both `buttonHandler = …` assignments removed.
  - `resolveGate` handler: removed `const { onButtonPressed, ...nativeGate } = result` — now just `return { gated: true, gate: result }`.
  - `clearGate`: removed `buttonHandler = undefined`.
- **`src/web/NativeAudioBrowser.ts`**: removed `onGateButtonPressed: () => void = () => {}` property.
- Confirmed: `rg -n "onButtonPressed|buttonTitle|onGateButtonPressed" src/` → no matches.

## TS hardening choices

### I1 — `toPublicRequest` switch + SearchParams non-null assertion

Converted `toPublicRequest` from a ternary to a `switch (req.reason)` with a `default` that assigns `req.reason` to `never` and throws, ensuring a new `GateReason` value is a compile error rather than a silent fall-through.

For the `reason:'search'` branch: used `req.search!` (non-null assertion) with a comment asserting the native wire contract. Rationale: `req.search` is always populated by all four native serve sites; a null check that falls back to a synthesized `SearchParams` would hand consumer code an object with `query: undefined` — violating the published type. The `!` assertion documents the contract expectation without manufacturing a lie. If a future native site sends `reason:'search'` without `search`, TypeScript won't catch it at that boundary, but the consumer resolver will receive the actual undefined and throw — which now propagates as a rejection and fails closed (correct behaviour given the post-fix native side).

### I2 — switch exhaustiveness (same change)

The `switch` default's `const _exhaustive: never = req.reason` catches any new `GateReason` values at compile time.

## Web init-window default (review-parity C1, web side)

`src/web/NativeAudioBrowser.ts` `resolveGate` default changed from `async () => ({ gated: false })` to `async () => ({ gated: true })` with a comment explaining this is only reachable in the init window and that web has no serve sites.

## Codegen result

`corepack yarn codegen` → success (0 errors).  
`git diff --stat nitrogen/`: 13 files changed, 12 insertions, 105 deletions.  
Changes cover all generated platforms: shared C++, iOS Swift/C++, Android Kotlin/C++, autolinking files.  
`buttonTitle` and `onGateButtonPressed` are absent from all generated bindings.

## Test result

`corepack yarn test src/features/gate.test.ts` → 8/8 passed.  
No test referenced `onButtonPressed` directly; the "resolver Gate → gated + override chrome" test passes because the override `{ title: 'O' }` is already a valid `Gate`/`NativeGate`.

## tsc / lint

`npx tsc --noEmit` → only the pre-existing `src/features/player/setup.test.ts` `StallReason` error.  
`corepack yarn lint` → 0 warnings, 0 errors.
