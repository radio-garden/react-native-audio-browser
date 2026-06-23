# Gate — TS / cross-surface contract thermonuclear review

**Reviewer stance:** adversarial, review-only (no code changed).
**Scope:** the TS public API and the JS↔native wire contract of the gate feature on `feature-fry-gate` (base `feature-fry`).
**Files reviewed in full:** `src/features/gate.ts`, the gate block of `src/specs/audio-browser.nitro.ts`, the gate members of `src/web/NativeAudioBrowser.ts`, `src/features/index.ts`, `src/utils/LazyNativeEmitter.ts`, `src/types/browser.ts` (`SearchParams`). Cross-checked against the generated wire types `nitrogen/generated/shared/c++/{NativeGateRequest,GateDecision,NativeGate,GateEvent,GateReason}.hpp` and the native call-sites that *build* `NativeGateRequest` (`ios/HybridAudioBrowser.swift`, `ios/CarPlay/CarPlayController.swift`, `android/.../AudioBrowser.kt`, `.../player/MediaSessionCallback.kt`, `.../player/Player.kt`).
**Cross-reference:** `docs/superpowers/specs/2026-06-23-gate-design.md`, the prior native reviews (`review-ios.md`, `review-android.md`) and the fail-closed fixes already landed on both native platforms (commits `0a6ab0e` iOS, `b0d59ad` Android).

**Verdict.** The single most important contract — *a JS resolver that throws must propagate as a rejection so native fails closed* — **holds**: `resolveGate` is `async`, has no `try/catch`, and a synchronous throw inside `resolver(...)` rejects the returned promise. With the native side now failing closed on rejection, the throw path is sound end-to-end. The real defects are in the **`buttonHandler` global** (a genuine cross-tab mis-dispatch + a silent-disable footgun) and in **`toPublicRequest`'s lossy casts** (an empty-object `SearchParams` that lies about its required `query`, and an empty-string browse `path`). Plus a web-stub semantic drift that will mislead a web consumer.

**Counts: 1 Critical · 3 Important · 5 Minor.**

---

## CRITICAL

### C1 — `buttonHandler` is a single module-global mutated during *resolution*, so a per-request resolver override routes the wrong tab's button handler (and an override with no button silently disables the default's button)
`src/features/gate.ts:98, 101, 117-119, 141, 145-146`

```ts
let buttonHandler: (() => void) | undefined            // :98  (module-global, ONE slot)
nativeBrowser.onGateButtonPressed = () => buttonHandler?.()   // :101

nativeBrowser.resolveGate = async (req) => {
  const result = resolver ? resolver(toPublicRequest(req)) : true
  if (result === false) return { gated: false }
  if (result === true)  return { gated: true }
  const { onButtonPressed, ...nativeGate } = result    // :117  per-request override
  buttonHandler = onButtonPressed                      // :118  ← clobbers the global
  return { gated: true, gate: nativeGate }
}
```

There is exactly **one** `buttonHandler` slot, and `onGateButtonPressed` (the native→JS tap) always reads that one slot. But `resolveGate` **overwrites** it every time a resolver returns a `Gate` override. The serve path resolves once **per request** (per gated tab on CarPlay — the design says one resolve per gated tab during a tab-bar build). So the *last request resolved* wins the global, regardless of which tab the user later taps. Two concrete failure modes, both reachable with the design's own per-request-override feature:

1. **Cross-tab mis-dispatch.** A resolver that returns different `Gate`s with different `onButtonPressed` for different tabs (e.g. tab A → "Subscribe", tab B → "Sign in") leaves `buttonHandler` set to whichever tab resolved **last** during the tab-bar build. The user taps tab **A**'s gate button → tab **B**'s handler fires. The button label the user sees (from `nativeGate.buttonTitle`, which *is* correctly per-tab on the rendered template) and the handler that runs are now **mismatched**. This is a real correctness bug, not a theoretical one: the per-request-chrome override is the headline new capability, and it ships with a button handler that can't be per-request because the slot is global.

2. **Silent disable.** An override `Gate` that omits `onButtonPressed` runs `buttonHandler = undefined` (`:118`, destructuring yields `undefined`). If the *default* chrome (set by `setGate(gate, …)` at `:145-146`) had a button + handler, a single resolver override **without** a handler now **nulls the global**, so the default gate's button — still rendered on every other gated tab — taps into nothing. The native side keeps showing the default's `buttonTitle` (iOS uses `defaultChrome`/built-in when the decision carries no `gate`), but JS has thrown its handler away. Tap = silent no-op.

**Why this is Critical.** It's a *latent* mis-wire of a security/upsell control: the gate's whole purpose is to drive a consumer action (subscribe / sign-in), and the action button can fire the wrong handler or no handler. It is data-dependent (only bites consumers who use per-request override chrome with buttons — i.e. exactly the new feature), so it will pass a naive single-gate smoke test and surface in the field. It is also **iOS-specific in effect** (only CarPlay renders buttons; Android Auto can't), but the *bug* is in shared TS, so it affects every consumer who writes a multi-button resolver.

**Root cause.** Button identity is carried out-of-band (a JS closure keyed by nothing) while the chrome it belongs to crosses the bridge keyed per-template. The two halves are reunited only by "whatever was assigned last."

**Recommended fix (pick one):**
- **Preferred — key the handler to the rendered gate.** Give each served override a stable token: return an id on `GateDecision`/`NativeGate` (or reuse `buttonTitle`+a counter), keep a `Map<token, () => void>` in JS, and have `onGateButtonPressed` receive that token from native (the tap site already knows *which* template/tab it is). Then a tap dispatches to the exact handler that produced that chrome. This is the only fix that actually makes per-request button handlers correct.
- **Cheaper — constrain the contract.** If per-request *button handlers* are out of scope for v1 (only per-request title/message vary), document that `onButtonPressed` is taken **only** from the default `Gate` passed to `setGate`, ignore `result.onButtonPressed` in `resolveGate` (don't touch `buttonHandler` there at all), and state in the `GateResolver` JSDoc that overrides may set chrome text but the button handler is fixed to the default. That removes the clobber and the silent-disable, at the cost of the feature. Either way, **`resolveGate` must not blindly assign the global from a per-request result.**

---

## IMPORTANT

### I1 — `toPublicRequest` casts an empty object to `SearchParams`, fabricating a value whose **required** `query` is `undefined`
`src/features/gate.ts:103-107`, against `src/types/browser.ts:128-159`

```ts
return req.reason === 'browse'
  ? { kind: 'browse', path: req.path ?? '' }
  : { kind: 'search', params: (req.search ?? {}) as SearchParams }   // :106
```

`SearchParams.query` is **non-optional** (`browser.ts:136`, "always present, but may be empty string"). The `(req.search ?? {}) as SearchParams` cast manufactures a `SearchParams` with **no `query` field at all** when `req.search` is undefined. The wire type allows it: `NativeGateRequest.search` is `std::optional<SearchParams>` (`NativeGateRequest.hpp:50`) and `reason` is independent, so the type system permits `reason:'search'` with `search` absent. A consumer resolver written against the published type does:

```ts
(req) => req.kind === 'search' && req.params.query.startsWith('podcast')  // TypeError: query is undefined
```

— a **runtime crash inside the resolver**, which (post-fail-closed) now *gates* the request. So the blast radius today is "fail closed" rather than "leak," but it converts a malformed request into a thrown resolver, i.e. the consumer's correct-looking code crashes through no fault of its own.

**Is it live?** Today, **no** — every native search site builds `search: searchParams(query)` / `SearchParams(query: …)` with `query` populated (`MediaSessionCallback.kt:612,673`, `Player.kt:869`, `HybridAudioBrowser.swift:1731-1741`). So `req.search` is always present on a real search request and the `?? {}` branch is **dead**. But "the only thing keeping a published-type-violating cast from firing is that all current callers happen to be well-behaved" is exactly the footgun a thermonuclear review exists to flag: the library is public, a future native site (or a third-party native consumer of these generated types) can legally send `reason:'search', search: undefined`, and the cast will hand a malformed `SearchParams` to user code with zero diagnostics.

**What *should* happen when native sends `reason:'search'` with `search` undefined?** That is a contract violation on the wire — there is no meaningful search request without params. The honest options:
- Make the wire type encode the invariant so it can't happen: a discriminated wire union (`{reason:'browse', path} | {reason:'search', search}`) instead of three independent optionals. Nitro structs don't express a tagged union directly, so this likely means the cleaner shape is *exactly* the public `GateRequest` and the flat `NativeGateRequest` is the thing to delete (see I2).
- Or, if the flat struct stays, **don't cast a lie**: treat `reason:'search'` with absent `search` as a malformed request and **fail closed explicitly** (`return { gated: true }`) rather than synthesizing `{} as SearchParams`. At minimum, synthesize a *valid* default (`{ query: '', reference: 'unknown' }`) so the published type isn't violated.

**Recommended:** stop the `as SearchParams` cast. Replace with `req.search ? { kind:'search', params: req.search } : <explicit malformed handling>`. Never hand consumer code a `SearchParams` missing its required `query`.

### I2 — `path: req.path ?? ''` gives a browse resolver an **empty-string path** that is indistinguishable from a real root path, and the parallel `NativeGateRequest` / `GateRequest` shapes can silently drift
`src/features/gate.ts:105`, `src/specs/audio-browser.nitro.ts:42-46`, `nitrogen/generated/shared/c++/NativeGateRequest.hpp:46-58`

Two coupled issues:

1. **Empty-string browse path.** When `req.path` is undefined, the consumer resolver sees `{ kind:'browse', path:'' }`. A resolver that branches on path prefix (`req.path.startsWith('/premium')`) treats `''` as "some root-ish path" rather than "no path was sent." Today the native browse sites always send a `path` *except* the iOS top-level tab-bar build at `CarPlayController.swift:484` which sends `path: nil` deliberately (the whole-tab-bar gate question, no specific tab). So `''` **is reachable** and means "the tab bar as a whole," which a path-prefix resolver will mis-bucket. Unlike I1 this isn't a type lie (`path` is `string`), but it's a semantic one: `''` conflates "root" and "absent." Consider passing `path: req.path` and typing the browse case `{ kind:'browse'; path?: string }`, or document that `''` denotes the tab-bar-level browse check.

2. **Two hand-maintained shapes for one concept.** `NativeGateRequest {reason, path?, search?}` (flat, wire) and `GateRequest` (discriminated union, public) are kept in agreement only by `toPublicRequest`. There is **no exhaustiveness link** between them: add a third `GateReason` (e.g. `'item'`) to the wire and `toPublicRequest`'s ternary silently funnels it into the `:106` search branch (the `else`), fabricating a bogus search request — no compile error, because the ternary isn't a `switch` over `req.reason` and `GateReason` is a bare string union. This is the "silent mismatch only a real build catches" class, except here *even the build won't catch it* — it's a logic fall-through. Recommend converting `toPublicRequest` to a `switch (req.reason)` with a `satisfies never` default so a new reason forces an update.

### I3 — Web stub's `resolveGate` default is a real participant in the contract, but its `{gated:false}` is *opposite* the native default and is reachable on web
`src/web/NativeAudioBrowser.ts:210`, vs `src/features/gate.ts:111-114`

```ts
resolveGate: (request) => Promise<GateDecision> = async () => ({ gated: false })   // web stub :210
```

The web stub correctly implements the full spec surface (`setGate`/`clearGate` no-ops `:966-968`, `onGate`/`onGateButtonPressed`/`resolveGate` slots present), so `tsc`/codegen stays honest — good. **But:** `gate.ts:111` **overwrites** `nativeBrowser.resolveGate` at module load with the *real* JS handler (the one that returns `{gated:true}` for the static case). On web, `nativeBrowser` **is** `NativeAudioBrowser`, so after `gate.ts` runs, the stub's `async () => ({gated:false})` is dead — replaced by the real resolver. That real resolver, on web, will return `{gated:true}` for a static `setGate(gate)` … but **web never calls `resolveGate`** because web's `setGate`/`clearGate` are no-ops and there are no car serve sites. So the net behavior ("web never enforces") is correct, but it is correct **by two independent accidents** (no serve site calls it; and if one did, the real resolver — not the stub default — would answer). The stub's `{gated:false}` default is therefore **misleading documentation**: it claims "web allows," but the actually-installed resolver on web would *gate* a static gate if anything ever called it. A web consumer reading the stub to understand semantics is misled.

**Recommended:** either (a) make the web `setGate`/`clearGate` actually update local state and have `resolveGate` honor it (so web has *consistent* semantics with native, just no surface to render on), or (b) add a comment on `:210` that this default is unreachable because `gate.ts` overwrites `resolveGate` at import and web has no serve sites — so "web never enforces" comes from the absence of serve sites, not from this `false`. Today the `false` reads like the source of the guarantee and it isn't.

---

## MINOR

### M1 — `onGate` emitter matches the `onRemote*` pattern, but "fires after `clearGate`" is possible and undocumented
`src/features/gate.ts:165-167`

`onGate = LazyNativeEmitter.emitterize<GateEvent>((cb) => (nativeBrowser.onGate = cb))` is identical in shape to `onRemote*` (`remoteControls.ts:256-257` etc.): lazy native-callback install on first subscriber, multi-subscriber via a `Set`, cleanup via the returned unsubscribe (`LazyNativeEmitter.ts:41-45`). **Multiple subscribers work; cleanup works.** One nit relevant to the gate's semantics: `onGate` fires per **gated serve**, and serves are driven natively (tab-bar rebuilds, AA re-queries). A `clearGate()` from JS only nulls JS state and calls `nativeBrowser.clearGate()`; an **in-flight** native serve that already decided "gated" can still call `onGate` *after* the JS `clearGate` returns (the native rebuild that the clear triggers, or a serve that resolved just before). So a consumer can receive an `onGate` event "after" they cleared the gate. This is inherent to the async serve model and is consistent with the no-dedup contract, but the `onGate` JSDoc (`:51-58`) doesn't warn that events can trail a `clearGate`. Add one line so a consumer counting hits doesn't treat a trailing event as a bug.

### M2 — `setGate(gate, undefined)` vs `setGate(gate)` correctly collapse, but `hasResolver` is computed as `b != null`, which also swallows an explicitly-passed non-function junk value
`src/features/gate.ts:139-149`

The overload routing is sound: `typeof a === 'function'` cleanly separates the resolver-only overload from the `Gate` overload; `setGate(gate, undefined)` and `setGate(gate)` both yield `b == null` → `hasResolver = false` (`:148`), identical to "gate everything." Correct. The only nit: there's no runtime validation that `b`, when non-null, is actually a function. `setGate(gate, 42 as any)` records `hasResolver: true` to native, then `resolveGate` does `resolver(...)` → `TypeError` → (now) fail-closed gate. Acceptable for a TS library (callers honoring types can't hit it), and the failure mode is safe (closed), so flag-only. The bridge sending `gate` fields raw (no field-type validation) is likewise acceptable-by-convention — note it's *not* validated, so a consumer passing `{title: 42 as any}` ships a number across the bridge to native string fields.

### M3 — `resolveGate`'s `true`/`false` returns rely on the native side resolving default-vs-built-in chrome; JS returning `{gated:true}` with no `gate` is correct but the asymmetry isn't documented in `gate.ts`
`src/features/gate.ts:115-116`

`if (result === false) return { gated: false }` and `if (result === true) return { gated: true }` — the `true` path deliberately returns **no `gate`**, delegating "default chrome or built-in" to native (matches the spec and the native `decision.gate ?? defaultChrome ?? builtInGate` chain). This is correct and intentional, but the contract that "`{gated:true}` with no `gate` means *use native's stored default/built-in*" lives only in the native code and the design doc, not in a comment at this return. A maintainer could "helpfully" attach `defaultChrome` here and double-resolve. One-line comment recommended. (Belt-and-suspenders on native makes a double-resolve harmless today, per `review-ios.md` M5.)

### M4 — Public type exports are complete and the union narrows, but `NativeGate` leaks onto the public surface where `Gate` is the intended consumer type
`src/features/gate.ts:14, 74, 165`; `src/features/index.ts:15`

`export * from './gate'` re-exports **everything**, including the wire types `NativeGate`, `NativeGateRequest`, `GateDecision`, `GateReason`. The intended consumer types are `Gate`, `GateRequest`, `GateResolver`, `GateEvent` — all exported and usable, and `GateRequest` narrows correctly by `kind` (verified: it's a discriminated union on a string-literal discriminant). The leak: a consumer importing from the package root sees both `Gate` **and** `NativeGate` (which is `Gate` minus `onButtonPressed`) and may import the wrong one — `NativeGate` is structurally a valid argument to `setGate` (since `Gate = NativeGate & {onButtonPressed?}` and the handler is optional), so the mistake type-checks and silently means "no button handler." Consider not re-exporting the `Native*`/`GateDecision` wire types from the public barrel (they're only needed by the spec and native), or prefixing a `@internal` JSDoc + an eslint/`api-extractor` rule. `GateReason` is arguably useful publicly (it's `GateEvent.reason`'s type); the others are not.

### M5 — `GateEvent` is `{reason}` only; a consumer cannot dedup by what was gated, and `reason` cannot distinguish *which* browse tab/path
`src/features/gate.ts:59`

The design intentionally deferred `path?`/`search?` on `GateEvent` (struct chosen so it's additive-later), and the native reviews already flagged the per-tab fan-out volume. From the **TS-contract** angle the residual point is: `onGate` is the consumer's only signal, it fires N times per tab-bar build, and each event is byte-identical (`{reason:'browse'}`), so a consumer **cannot** debounce by content — only "saw a browse gate at all." That's a usable-but-coarse contract. Not a bug (it's documented and deferred), but worth restating that the event's current granularity makes the "record a gate-hit" use-case effectively "record *a* hit," not "record *which* hit." If the consumer's real need is per-surface/per-path upsell, the seam should be opened before GA, not after.

---

## Contract checklist (rubric verification)

| Contract point | Verdict | Evidence |
|---|---|---|
| Resolver **throw → rejection → native fails closed** | **HOLDS** | `resolveGate` is `async`, no `try/catch`; sync throw rejects (`gate.ts:111-120`); native fails closed (`HybridAudioBrowser.swift` C1 fix, `AudioBrowser.kt` C1 fix) |
| `true` → `{gated:true}` (no gate), native applies default/built-in | Correct | `gate.ts:116` + native `?? defaultChrome ?? builtInGate` |
| `false` → `{gated:false}` (allow) | Correct | `gate.ts:115` |
| `Gate` override → `{gated:true, gate}` | Correct shape, **but button handler mis-routed** | `gate.ts:117-119` → **C1** |
| `NativeGateRequest ↔ GateRequest` round-trip | Lossy: `path ?? ''`, `search ?? {} as SearchParams` | **I1, I2** |
| `GateDecision {gated, gate?}` wire shape | Matches generated (`GateDecision.hpp:42-49`) | OK |
| Spec `setGate(gate, hasResolver)` / `resolveGate` / `onGate` / `onGateButtonPressed` match `gate.ts` calls | Match | `audio-browser.nitro.ts:122-130` ↔ `gate.ts:143,148,101,166` |
| Web stub implements full surface (codegen honest) | Yes | `NativeAudioBrowser.ts:208-210, 966-968` |
| Web "never enforces" semantics | Correct outcome, **misleading default** | **I3** |
| `onGate` emitter / multi-sub / cleanup | Correct, matches `onRemote*` | `gate.ts:165-167`, `LazyNativeEmitter.ts` |
| Public types exported & narrowable | Yes; `Native*` leak | **M4** |

---

## Most important finding

**C1** — the gate's action-button handler is a single module-global (`buttonHandler`) that `resolveGate` reassigns on every per-request `Gate` override, so the **last tab resolved during a CarPlay tab-bar build wins the global**: a tap on one gated tab's button can invoke a *different* tab's `onButtonPressed`, and an override that omits a handler silently nulls the default gate's button. This is a correctness bug in the headline per-request-chrome feature, data-dependent (only bites multi-button resolvers, so it survives single-gate smoke tests), and it mis-wires the exact upsell/subscribe action the gate exists to drive. Fix by keying the handler to the served chrome (token + `Map`) rather than a global, or by explicitly removing per-request button-handler support and taking `onButtonPressed` only from the default `Gate`.
