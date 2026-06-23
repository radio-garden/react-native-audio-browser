# Gate — Android thermonuclear review

**Reviewer stance:** adversarial, review-only (no code changed).
**Scope:** Android gate implementation on `feature-fry-gate` (base `feature-fry`).
**Files reviewed in full:** `AudioBrowser.kt`, `player/MediaSessionCallback.kt`, `player/Player.kt`, `Service.kt` (call site), `util/BrowserPathHelper.kt`, generated bindings under `nitrogen/generated/android/kotlin/`, plus the iOS `gateDecision`/`setGate`/`clearGate` for parity and `src/features/gate.ts` for the JS contract.

**Verdict:** The implementation is largely faithful to the design — single choke point, static fast path, no-dedup `onGate`, `onGetItem` deliberately silent, cache untouched. But it ships **two genuine defects** (a fail-open content leak that diverges from the design's intent for a premium gate, and a real `@Volatile` compound-read race that can crash on a force-unwrap) plus several important correctness/parity gaps.

Counts: **2 Critical · 4 Important · 4 Minor**

---

## CRITICAL

### C1 — Fail-open on resolver error leaks gated content (and silently plays it on voice-play)
**`AudioBrowser.kt:754-756`** (`gateDecision`), consumed at every site.

```kotlin
val decision = runCatching { resolveGate(request).await().await() }.getOrNull()
if (decision == null || !decision.gated) return GateOutcome(false, null)
```

A thrown/rejected/timed-out JS resolver collapses to `null`, which is treated as **not gated → content served**. For the premium-gate use case (the entire motivating scenario), a resolver that throws — JS bundle still loading, an exception in the consumer's predicate, a runtime reload mid-serve — **opens the gate and serves the real browse tree / plays the searched track** (`Player.playFromSearch:867-875` proceeds to `searchPlayable` + `setQueue` + `play`). This is a content-leak / paywall-bypass on the exact error path a gate exists to hold.

The design (`gate-design.md` "Implementation note") only commits to the resolver being *fast and synchronous-feeling*; it does **not** mandate fail-open. The plan's spike chose `runCatching{…}.getOrNull()` for "never breaks the serve path," conflating "don't crash the serve" with "don't gate." Those are separable: a resolver error should **fail closed** (gate with the stored default / built-in) for an *active* gate, not allow.

**Why this is Critical and not just a parity nit:** an active gate means the consumer has explicitly declared "content is blocked." The only correct fallback when we cannot compute the per-request decision is the gate's own default chrome — never the content. Fail-open converts a transient JS hiccup into a paywall hole.

**Recommended fix:** when `isGateActive && hasResolver` and the resolver throws/times out, fall back to the **static decision**, not allow:

```kotlin
val decision = runCatching { resolveGate(request).await().await() }.getOrNull()
  ?: return GateOutcome(true, defaultChrome ?: builtInGate)   // fail CLOSED while a gate is active
if (!decision.gated) return GateOutcome(false, null)
return GateOutcome(true, decision.gate ?: defaultChrome ?: builtInGate)
```

This must be decided **jointly with iOS** (`HybridAudioBrowser.swift:581`, `try? await … else { allow }`) — iOS has the identical fail-open, so whatever is chosen, fix both. If product genuinely wants fail-open, it must be a documented, deliberate decision in the design doc, not an implicit consequence of `getOrNull()`. As written, it is undocumented and wrong for the premium case.

---

### C2 — `@Volatile` compound read in `gateDecision` can crash on `outcome.chrome!!`
**`AudioBrowser.kt:716-718, 751-757`** (state + helper) → unwrapped at **`MediaSessionCallback.kt:304, 458, 679`**.

State is three independent `@Volatile` fields:

```kotlin
@Volatile private var defaultChrome: NativeGate? = null
@Volatile private var hasResolver: Boolean = false
@Volatile private var isGateActive: Boolean = false
```

`@Volatile` gives per-field visibility but **no atomicity across the compound read**. `gateDecision` runs on the Media3 IO thread (`scope.future{}`); `setGate`/`clearGate` run on the JS thread. The static fast path is:

```kotlin
if (!isGateActive) return GateOutcome(false, null)
if (!hasResolver)  return GateOutcome(true, defaultChrome)   // line 753
```

Interleave: thread A reads `isGateActive == true` (line 752). Thread B runs `clearGate()` — sets `isGateActive=false`, **`defaultChrome=null`**, `hasResolver=false` (`:730-736`). Thread A resumes at line 753, reads `hasResolver == false`, reads `defaultChrome == null`, returns `GateOutcome(gated = true, chrome = null)`. The serve site then does:

```kotlin
return@future LibraryResult.ofItemList(ImmutableList.of(createGateMediaItem(outcome.chrome!!)), params)  // NPE
```

→ **`NullPointerException` on `outcome.chrome!!`**, crashing the browse serve coroutine.

A second interleave hits the same way: `setGate(gate, hasResolver=true)` arriving between the `isGateActive` read and a resolver decision whose `decision.gate == null` falls back to `defaultChrome` (`:756`) — if `clearGate` niled `defaultChrome` in between, `builtInGate` still saves it there, so the resolver path is actually safe. **The static path (line 753) is the unsafe one** because it returns `defaultChrome` directly with no `?: builtInGate` guard.

Note the report's claim "By contract, `chrome` is non-null whenever `gated` is true" (`task-5-report.md:16`) is **false under concurrency** — exactly because line 753 returns the raw `defaultChrome`.

**Recommended fix (two independent hardenings, do both):**
1. Make the static path total: `if (!hasResolver) return GateOutcome(true, defaultChrome ?: builtInGate)`. This removes the only path that can return `gated=true, chrome=null`.
2. Snapshot state atomically. Either guard `gateDecision`'s prologue + the setters with the same lock, or fold the three fields into a single `@Volatile private var gate: GateState?` (`data class GateState(val chrome: NativeGate?, val hasResolver: Boolean)`, `null` == inactive) so one volatile read yields a consistent triple. The single-reference approach is cleaner and matches how `setGate`/`clearGate` already mutate as a unit.

Even with fix (1), the race still lets a *just-cleared* gate serve one stale gate tile (gated=true with built-in chrome after clear) or a *just-set* gate briefly allow — harmless-ish but the atomic snapshot (2) closes it properly. iOS avoids this entirely by confining all gate state to `@MainActor` (`HybridAudioBrowser.swift:578` `@MainActor func gateDecision`); Android's `suspend fun` has **no dispatcher confinement**, so it genuinely races. This is a real platform divergence, not a theoretical one.

---

## IMPORTANT

### I1 — `gateDecision` has no dispatcher confinement; the iOS `@MainActor` guarantee is silently dropped
**`AudioBrowser.kt:751`** vs iOS **`HybridAudioBrowser.swift:577-578`**.

iOS pins `gateDecision` to `@MainActor`, so the whole read-decide sequence is serialized against `setGate`/`clearGate` (also `onMainActor`). Android's `suspend fun gateDecision` inherits whatever dispatcher the caller's `scope.future{}` uses — `Dispatchers.IO` (`MediaSessionCallback.kt:54`). There is no `withContext(Dispatchers.Main)` and the setters run on the JS thread, not Main. So the Android version has **none of the serialization iOS relies on**. This is the root cause enabling C2 and is itself a parity defect worth calling out: the two platforms do not have the same concurrency model, and the Android one is unsound. Fix is C2's option (2) (atomic snapshot) — confining to Main would also work but `resolveGate().await().await()` already hops to JS, so a single volatile snapshot is the lighter fix.

### I2 — `invalidateAllContent()` on every `setGate`/`clearGate` re-queries all subscribers; combined with per-serve `onGate` this is a thundering herd, and the gate-tile re-query is unbounded by design
**`AudioBrowser.kt:727, 735`** → `MediaSessionCallback.invalidateAllContent → notifySubscribedChildrenChanged` (`:567-570, 542-548`).

Each gate toggle calls `notifyChildrenChanged(Int.MAX_VALUE)` on **every** subscribed parent. Android Auto responds by re-calling `onGetChildren` for each, and **every gated re-serve fires `onGate`** (`MediaSessionCallback.kt:300`). So one `setGate` → N subscribed paths → N `onGetChildren` → N `onGate` events, each potentially a JS round-trip through `resolveGate` (when a resolver is installed). With the no-dedup contract this is *intended* but the volume is easy to under-estimate:

- There is no infinite loop (serving the gate tile does **not** itself call `invalidateAllContent`), so it terminates — good.
- But a controller that re-subscribes to the gate tile's own `GATE_PATH` (the code explicitly anticipates this, `onGetChildren:294` comment "including re-queries of the gate tile's own sentinel path") gets gated again → another `onGate`. On a head unit that polls, `onGate(browse)` can fire repeatedly per path with no app action. The design says consumers debounce; that's defensible, but the **combination** of (a) invalidate-all on toggle and (b) per-serve onGate with a live resolver means a single `setGate` can spray dozens of `resolveGate` JS calls. Confirm this is acceptable for the watchdog budget (`awaitBrowser` 10s timeout, `Player.kt:156`) — a slow resolver × N parents could stack.

**Recommended:** no code change required if the volume is accepted, but (1) document the N-fan-out explicitly, and (2) consider that `onGate` firing on a re-query of `GATE_PATH` itself is arguably a spurious event (the user is re-reading the already-served gate, not hitting a fresh gated content level) — see I3.

### I3 — `onGate` fires on re-query of the gate tile's own sentinel path (`onGetChildren`), which is the same "item re-read" case `onGetItem` deliberately suppresses
**`MediaSessionCallback.kt:294-307`** vs the `onGetItem` rationale at **`:443-459`**.

The design's reason for `onGetItem` *not* emitting `onGate` is: "item lookup of an already-served sentinel, not a fresh content serve" (`task-5-report.md:23`). But `onGetChildren(parentId = GATE_PATH)` is *exactly the same situation* — a controller drilling into / re-querying the gate tile it was already shown — and it **does** fire `onGate(BROWSE)` (`:300`). The comment at `:294` even acknowledges "including re-queries of the gate tile's own sentinel path" serve the tile, but doesn't carve it out of the event. So the "no event on a sentinel re-read" principle is applied inconsistently: suppressed in `onGetItem`, not suppressed in `onGetChildren` for the same sentinel. A consumer counting `onGate` will see phantom browse hits when AA re-drills the gate tile.

**Recommended:** either (a) skip `onGate` when `parentId == BrowserPathHelper.GATE_PATH` (the user is re-reading the gate, not crossing a fresh gated boundary), matching `onGetItem`'s logic; or (b) document that gate-tile re-drills count as browse gate-hits and `onGetItem`'s suppression is the inconsistent special case. (a) is more consistent with the stated principle.

### I4 — Default `resolveGate` (`Promise.resolved(Promise.resolved(...))`) is dead but masks a real init-window allow
**`AudioBrowser.kt:170-172`**.

```kotlin
override var resolveGate: (request: NativeGateRequest) -> Promise<Promise<GateDecision>> = {
  Promise.resolved(Promise.resolved(GateDecision(gated = false, gate = null)))
}
```

This default returns *not gated*. It's only reachable when `hasResolver == true` but JS has not yet assigned `resolveGate` — i.e. the consumer called `setGate(resolver)` from the native side's view (so `hasResolver=true` was recorded) but the JS `nativeBrowser.resolveGate` slot is somehow still the default. Given `gate.ts` assigns `nativeBrowser.resolveGate` at module load (`gate.ts:104`) and `setGate` is called after, this is normally unreachable. But if it *is* hit (e.g. a JS reload races a serve), it returns **allow** while a gate is active — same fail-open class as C1. Once C1 is fixed to fail-closed, this default should match: return `GateDecision(gated = true, gate = null)` so the static default/built-in applies, *or* rely on the C1 catch. As-is it's an undocumented allow-by-default during the init window. Low likelihood, but it's the same leak direction and should be flipped when C1 is addressed.

---

## MINOR

### M1 — `searchParams(query)` is correct but loses the structured fields the voice intent already parsed
**`MediaSessionCallback.kt:132-142`** (the `onSearch`/`onGetSearchResult` sites).

`searchParams(query)` wraps a bare query with `reference = MediaReference.UNKNOWN`, all other fields null — verified to match `BrowserManager`'s own construction (`BrowserManager.kt:722-730, 779-787`), so the value is valid and the resolver will see a sane `SearchParams`. Minor: the resolver only ever sees `{ query }` for the AA search-bar sites, never genre/artist/album, so a resolver that wants to allow e.g. "play my favorites" (the design's headline example, `gate-design.md:62-68`) **cannot** distinguish it at `onSearch`/`onGetSearchResult` — only at `playFromSearch` (which passes the real structured `params`, `Player.kt:869`). That's a real asymmetry vs the design example, but it's inherent to the AA free-text search surface (no structure available), so flag-only. Document that the `reference`/`mode`-based resolver predicate only fires meaningfully on the voice-play path on Android.

### M2 — `playFromSearch` fail-open also plays content on resolver error (subset of C1, called out for the device test matrix)
**`Player.kt:867-875`**. Same `gateDecision` → on resolver error, `gated=false`, proceeds to `searchPlayable` + `play`. Folds into C1's fix but deserves an explicit manual-test line: "gate active + resolver throws + voice 'play X' → must NOT play." The current code plays.

### M3 — `GATE_PATH` direct `onGetItem` fetch builds a `BROWSE` request even though the gate path may have been reached via search
**`MediaSessionCallback.kt:451-456`** always uses `reason = GateReason.BROWSE` for the `GATE_PATH` item lookup. Since the gate tile is shared between browse and search serves (same `GATE_PATH` mediaId, `createGateMediaItem`), a resolver that returns different decisions for browse vs search could get the "wrong" reason here. In practice `onGetItem` only needs gated-or-not (it doesn't emit `onGate`, and chrome resolution rarely depends on reason), so impact is low — but a resolver keyed on `request.reason` could mis-decide a search-origin gate-tile re-read as browse. Acceptable given the item lookup is just confirming the sentinel; note it.

### M4 — Comment says "Mirrors the iOS `gateDecision(for:)` helper" but the concurrency model does NOT mirror it
**`AudioBrowser.kt:743`** doc comment claims parity with iOS. As established in C2/I1, iOS is `@MainActor`-confined and Android is not — the helpers are structurally similar but their thread-safety guarantees differ materially. The comment overstates the parity and could mislead a future maintainer into assuming the same safety. Tighten the comment (or, better, achieve the parity via C2 fix).

---

## Parity summary (vs iOS, `task-4-report.md`)

| Property | iOS | Android | Verdict |
|---|---|---|---|
| Fail mode on resolver error | fail-open (`try?` → allow) | fail-open (`getOrNull()` → allow) | **Both wrong for premium (C1)** — fix together |
| Built-in fallback | override → default → built-in | override → default → built-in (resolver path); **static path returns raw default, no built-in (C2)** | Android static path unsafe |
| Resume never gated | resume branch returns before gate check (Siri) | `onPlaybackResumption` never calls `gateDecision`; `playFromSearch` is voice-play not resume | OK |
| No dedup `onGate` | per gated serve | per gated serve | OK (parity), but Android also fires on `GATE_PATH` re-drill (I3) |
| `onGetItem` no event | n/a | suppressed | OK, but inconsistent with `onGetChildren` sentinel re-query (I3) |
| Concurrency | `@MainActor` serialized | `@Volatile` compound read, unconfined `suspend` | **Divergence (C2/I1)** |
| Cache poisoning | none | none (notify-only, cache stays warm — `AudioBrowser.kt:724-727`) | OK |

---

## Most important finding
**C2** — the `@Volatile` compound read lets a concurrent `clearGate()` produce `GateOutcome(gated = true, chrome = null)` on the static fast path (`AudioBrowser.kt:753`), which crashes at `createGateMediaItem(outcome.chrome!!)` (`MediaSessionCallback.kt:304/458/679`). It is both a crash and a refutation of the implementer's stated "chrome is non-null whenever gated" invariant. Fix: make the static path `defaultChrome ?: builtInGate` **and** snapshot the three gate fields atomically (fold into one `@Volatile` reference). C1 (fail-open leak) is the most important *design* question and must be resolved jointly with iOS.
