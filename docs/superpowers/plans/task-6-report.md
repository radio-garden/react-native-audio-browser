# Task 6 Report — Web stub (gate rename) + Codegen

## What changed in the web stub (`src/web/NativeAudioBrowser.ts`)

- **Import** (line 27): replaced `NativeBrowseGate` with `NativeGate, NativeGateRequest, GateDecision, GateEvent` from `../features`.
- **Callback properties** (was line 205): replaced `onBrowseGateButtonPressed: () => void = () => {}` with three new settable props:
  - `onGateButtonPressed: () => void = () => {}`
  - `onGate: (event: GateEvent) => void = () => {}`
  - `resolveGate: (request: NativeGateRequest) => Promise<GateDecision> = async () => ({ gated: false })`
- **Gate methods block** (was lines 959–972): replaced the old `browseGate` field + `setBrowseGate`/`clearBrowseGate`/`getBrowseGate` with pure no-ops `setGate` / `clearGate`. Fields are not stored (web never enforces the gate; reading them caused `TS6133 noUnusedLocals` errors).

No changes to `src/web/browser/BrowserManager.ts` — it had no browse-gate references.

## Extra fix required (beyond the web stub)

**`src/features/gate.ts`** — added a named `GateReason = 'browse' | 'search'` type alias and used it in `NativeGateRequest.reason` and `GateEvent.reason`. Nitrogen does not support inline string-literal unions in struct fields; the named alias causes Nitrogen to emit a proper `GateReason` enum type. Both fields were changed from `'browse' | 'search'` → `GateReason`.

**`src/specs/audio-browser.nitro.ts`** — no import change needed for `GateReason` (it is consumed transitively through the struct types that reference it; importing it directly into the spec caused a `TS6196 declared but never used` error).

## Codegen result

`corepack yarn codegen` passes cleanly (0 TS errors, 1/1 HybridObjects generated).

Generated new files: `GateReason.hpp/.swift/.kt`, `NativeGate.*`, `NativeGateRequest.*`, `GateDecision.*`, `GateEvent.*` (and associated bridge/func wrappers). Deleted: `NativeBrowseGate.*` across all three platforms.
