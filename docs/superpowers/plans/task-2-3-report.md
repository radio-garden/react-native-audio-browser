# Task 2 + 3 Report

## What changed

### Task 2 — Nitro spec (`src/specs/audio-browser.nitro.ts`)
- Line 49 import: `NativeBrowseGate from '../features/browseGate'` → `NativeGate, NativeGateRequest, GateDecision, GateEvent from '../features/gate'`
- `// MARK: browse gate` block replaced with `// MARK: gate`:
  - `setBrowseGate(gate)` → `setGate(gate: NativeGate | undefined, hasResolver: boolean)`
  - `clearBrowseGate()` → `clearGate()`
  - `getBrowseGate()` removed (deleted as specified)
  - `onBrowseGateButtonPressed` → `onGateButtonPressed`
  - Added: `resolveGate: (request: NativeGateRequest) => Promise<GateDecision>`
  - Added: `onGate: (event: GateEvent) => void`

### Task 3 — `src/features/gate.ts` (renamed from `browseGate.ts`)
- `git mv src/features/browseGate.ts src/features/gate.ts`
- Rewrote the file:
  - Wire types: `NativeGate`, `NativeGateRequest` (flat struct with `reason`/`path`/`search`), `GateDecision`, `GateEvent`
  - Public types: `Gate` (= `NativeGate & { onButtonPressed? }`) , `GateRequest` (discriminated union with `kind`), `GateResolver`
  - `toPublicRequest()` converts `NativeGateRequest` → `GateRequest` (maps `reason` → `kind`, `search` → `params`)
  - `nativeBrowser.resolveGate` assigned at module load (async, awaited by native at serve sites)
  - `setGate` overloads: `(gate, resolve?)` and `(resolve)` — consistent with Controller note
  - `clearGate()` clears all state + calls native
  - `onGate` exported as `LazyNativeEmitter.emitterize<GateEvent>` (same pattern as `onRemotePlay` etc.)
  - Preserved useful doc comments from `browseGate.ts`, rewritten for the new shape

### `src/features/index.ts`
- `export * from './browseGate'` → `export * from './gate'`

### `src/features/gate.test.ts` (new)
- Vitest (not jest — matches the repo's existing test style using `vi.mock`, `vi.fn()`, etc.)
- Tests placed at `src/features/gate.test.ts` (not `__tests__/` — matches where `browser.test.ts` lives)

## Deviations from the prompt

1. **Test framework**: The prompt showed jest syntax (`jest.mock`, `jest.fn()`). The repo uses **vitest** (`vi.mock`, `vi.fn()`). Adapted accordingly — tests are otherwise semantically identical.

2. **`__resolveGateForTest` export**: The plan's Task 3 test draft used a `__resolveGateForTest` test-only export. The task's Section D test (the authoritative one for Tasks 2+3) accesses `(nativeBrowser as any).resolveGate` directly, which works because the module assigns to it at load time. Used the direct approach — no need for a test-only export.

3. **`src/web/` untouched**: `src/web/NativeAudioBrowser.ts` still has `browseGate`/`NativeBrowseGate` references. These are explicitly deferred to Task 6 (web stub) per the task instructions ("Leave `ios/`, `android/`, `website/` for Task 7 — `src/` only here").

## Jest/Vitest output summary

```
✓ src/features/gate.test.ts (7 tests) 2ms
Test Files  1 passed (1)
      Tests  7 passed (7)
   Duration  85ms
```

All 7 tests green.
