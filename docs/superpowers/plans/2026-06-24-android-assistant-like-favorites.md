# Android Assistant "like" → favorites; remove public rating API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route Google Assistant "I like this" (and its negative) to the Android favorites system, and remove the unused/inert public rating API so rating becomes a purely internal mechanism.

**Architecture:** Android already advertises each now-playing track as heart-rateable (`MediaMetadata.userRating` from `favorited`) and `MediaSession.Callback.onSetRating` already toggles the favorite. We extract the rating→favorite mapping into a small pure helper (`RatingFavorites`) so it is unit-testable, simplify `onSetRating` to use it (dropping the bridge-event emission), and delete the entire public rating surface across the Nitro spec, web stub, iOS, and Android.

**Tech Stack:** TypeScript + Nitro modules (codegen), Kotlin / Media3 (Android), Swift (iOS), JUnit 4 + Robolectric (Android tests), Yarn 4 (`corepack yarn`).

## Global Constraints

- **`corepack yarn`, never bare `yarn`**, inside the library (`~/rg/_libraries/react-native-audio-browser`). The library pins Yarn 4; global `yarn` is v1 and errors.
- **A Nitro spec change requires all surfaces consistent before `corepack yarn codegen`** (its `tsc` step fails otherwise): TS spec, `src/web/NativeAudioBrowser.ts`, iOS `HybridAudioBrowser.swift`, Android `AudioBrowser.kt`.
- **No new public rating API.** The general rating concept (stars / percentage / thumbs, per-track `rating`, `RatingType`, `ratingType` option, `onRemoteSetRating`) is removed, not deferred. Keep only binary favorites.
- **iOS behavior is unchanged** — Siri "I like this" already routes to favorites via `INUpdateMediaAffinityIntent`. No iOS rating wiring is added; the iOS rating *removal* is API cleanup only.
- Library paths in this plan are relative to `~/rg/_libraries/react-native-audio-browser`. The consumer app is `~/rg/native-apps`.

---

### Task 1: `RatingFavorites` mapping helper (Android, pure unit)

Extract the "does this controller rating mean favorite on/off?" decision into a pure, testable function. Only an explicitly-rated heart carries favorite intent.

**Files:**
- Create: `android/src/main/java/com/audiobrowser/util/RatingFavorites.kt`
- Test: `android/src/test/java/com/audiobrowser/util/RatingFavoritesTest.kt`

**Interfaces:**
- Produces: `object RatingFavorites { fun favoritedFor(rating: androidx.media3.common.Rating): Boolean? }` — returns `true`/`false` for a rated `HeartRating` (by `isHeart`), `null` for an unrated heart or any non-heart rating.

- [ ] **Step 1: Write the failing test**

Create `android/src/test/java/com/audiobrowser/util/RatingFavoritesTest.kt`:

```kotlin
package com.audiobrowser.util

import androidx.media3.common.HeartRating
import androidx.media3.common.StarRating
import androidx.media3.common.ThumbRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RatingFavoritesTest {
  @Test fun `rated heart up maps to favorited true`() {
    assertEquals(true, RatingFavorites.favoritedFor(HeartRating(true)))
  }

  @Test fun `rated heart down maps to favorited false`() {
    assertEquals(false, RatingFavorites.favoritedFor(HeartRating(false)))
  }

  @Test fun `unrated heart carries no favorite intent`() {
    assertNull(RatingFavorites.favoritedFor(HeartRating()))
  }

  @Test fun `non-heart ratings carry no favorite intent`() {
    assertNull(RatingFavorites.favoritedFor(ThumbRating(true)))
    assertNull(RatingFavorites.favoritedFor(StarRating(5, 4f)))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd ~/rg/_libraries/react-native-audio-browser/apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.RatingFavoritesTest"`

Expected: FAIL to compile — `RatingFavorites` is unresolved.

(The library has no standalone gradle — its unit tests build through the `example-native` app under `apps/example-native/android`, module `:react-native-audio-browser`. Equivalent shortcut from the library root: `corepack yarn workspace example-native android:test` runs the whole module suite without a `--tests` filter. The same `--tests` filter applies to later test runs.)

- [ ] **Step 3: Write the minimal implementation**

Create `android/src/main/java/com/audiobrowser/util/RatingFavorites.kt`:

```kotlin
package com.audiobrowser.util

import androidx.media3.common.HeartRating
import androidx.media3.common.Rating

/**
 * Maps a Media3 [Rating] arriving from a controller (e.g. Google Assistant "I like this") to a
 * favorite intent. Only an explicitly-rated heart carries favorite intent: a thumbs / star /
 * percentage rating, or an unrated (cleared) heart, returns null — no favorite change.
 */
object RatingFavorites {
  fun favoritedFor(rating: Rating): Boolean? =
    (rating as? HeartRating)?.takeIf { it.isRated }?.isHeart
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd ~/rg/_libraries/react-native-audio-browser/apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.RatingFavoritesTest"`

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git add android/src/main/java/com/audiobrowser/util/RatingFavorites.kt \
        android/src/test/java/com/audiobrowser/util/RatingFavoritesTest.kt
git commit -m "feat(android): add RatingFavorites helper mapping heart rating to favorite intent"
```

---

### Task 2: Route Assistant rating to favorites + lock the advertisement invariant (Android)

Rewire `onSetRating` to use `RatingFavorites` and drop the bridge-event emission, and add characterization tests for the heart-rateability advertisement (`TrackFactory`), including the capability-off case (no `userRating` when `favorited == null`).

**Files:**
- Modify: `android/src/main/java/com/audiobrowser/player/MediaSessionCallback.kt` (the `onSetRating` override + imports)
- Modify: `android/src/test/java/com/audiobrowser/TestFixtures.kt` (add `favorited` param to `track(...)`)
- Test: `android/src/test/java/com/audiobrowser/util/TrackFactoryRatingTest.kt` (new)

**Interfaces:**
- Consumes: `RatingFavorites.favoritedFor(rating)` from Task 1; `player.setActiveTrackFavorited(favorited: Boolean)` (existing — toggles the heart and fires `onFavoriteChanged`); `TrackFactory.toMedia3(track): MediaItem` (existing pure conversion).
- Produces: `TestFixtures.track(..., favorited: Boolean? = null)` for downstream tests.

- [ ] **Step 1: Add a `favorited` parameter to the track fixture**

In `android/src/test/java/com/audiobrowser/TestFixtures.kt`, change the `track(` signature and constructor call. Replace:

```kotlin
  fun track(
    title: String = "T",
    id: String? = null,
    src: String? = "https://s/a.mp3",
    artwork: String? = null,
    artist: String? = null,
    album: String? = null,
  ) =
    Track(
```

with:

```kotlin
  fun track(
    title: String = "T",
    id: String? = null,
    src: String? = "https://s/a.mp3",
    artwork: String? = null,
    artist: String? = null,
    album: String? = null,
    favorited: Boolean? = null,
  ) =
    Track(
```

and in the same `Track(...)` constructor below it, replace the line `      favorited = null,` with `      favorited = favorited,`.

- [ ] **Step 2: Write the failing advertisement test**

Create `android/src/test/java/com/audiobrowser/util/TrackFactoryRatingTest.kt`:

```kotlin
package com.audiobrowser.util

import androidx.media3.common.HeartRating
import com.audiobrowser.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackFactoryRatingTest {
  @Test fun `favorited true advertises a rated, hearted userRating`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = true))
    val rating = item.mediaMetadata.userRating as HeartRating
    assertTrue(rating.isRated)
    assertTrue(rating.isHeart)
  }

  @Test fun `favorited false advertises a rated, un-hearted userRating`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = false))
    val rating = item.mediaMetadata.userRating as HeartRating
    assertTrue(rating.isRated)
    assertEquals(false, rating.isHeart)
  }

  @Test fun `favorited null advertises no userRating (favoriting disabled)`() {
    val item = TrackFactory.toMedia3(TestFixtures.track(favorited = null))
    assertNull(item.mediaMetadata.userRating)
  }
}
```

- [ ] **Step 3: Run the test to verify it passes (characterization of existing behavior)**

Run: `cd ~/rg/_libraries/react-native-audio-browser/apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.TrackFactoryRatingTest"`

Expected: PASS (3 tests). These lock the existing `TrackFactory.metadataBuilder` behavior (`favorited?.let { setUserRating(HeartRating(it)) }`) as a guarded invariant. If any fail, the advertisement behavior has drifted — fix `TrackFactory` before continuing.

- [ ] **Step 4: Simplify `onSetRating` to route through the favorites system**

In `android/src/main/java/com/audiobrowser/player/MediaSessionCallback.kt`, replace the whole `onSetRating` override:

```kotlin
  override fun onSetRating(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    rating: Rating,
  ): ListenableFuture<SessionResult> {
    if (rating is HeartRating && rating.isRated) {
      player.setActiveTrackFavorited(rating.isHeart)
    }

    // Also emit onRemoteSetRating for listeners
    RatingFactory.media3ToBridge(rating)?.let {
      val event = RemoteSetRatingEvent(it)
      player.callbacks?.onRemoteSetRating(event)
    }
    return super.onSetRating(session, controller, rating)
  }
```

with:

```kotlin
  override fun onSetRating(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    rating: Rating,
  ): ListenableFuture<SessionResult> {
    // A heart rating from a controller (e.g. Google Assistant "I like this") toggles the
    // now-playing favorite. setActiveTrackFavorited fires onFavoriteChanged so the consumer
    // persists it — the same path as the notification / CarPlay heart button.
    RatingFavorites.favoritedFor(rating)?.let { player.setActiveTrackFavorited(it) }
    return super.onSetRating(session, controller, rating)
  }
```

Then update the imports at the top of the file: remove `import androidx.media3.common.HeartRating`, `import com.audiobrowser.util.RatingFactory`, and `import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent`; add `import com.audiobrowser.util.RatingFavorites`. Keep `import androidx.media3.common.Rating` (still the param type).

- [ ] **Step 5: Run the Android unit tests + compile to verify**

Run: `cd ~/rg/_libraries/react-native-audio-browser/apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.*" && ./gradlew :react-native-audio-browser:compileDebugKotlin`

Expected: tests PASS; Kotlin compiles. (`RatingFactory` is now referenced only by its own file — it is deleted in Task 3. `bridgeToMedia3` was already unused.)

- [ ] **Step 6: Commit**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git add android/src/main/java/com/audiobrowser/player/MediaSessionCallback.kt \
        android/src/test/java/com/audiobrowser/TestFixtures.kt \
        android/src/test/java/com/audiobrowser/util/TrackFactoryRatingTest.kt
git commit -m "feat(android): route Assistant heart rating to favorites via RatingFavorites

onSetRating now maps a controller heart rating straight to setActiveTrackFavorited
(fires onFavoriteChanged), dropping the onRemoteSetRating bridge emission. Adds
TrackFactory advertisement tests, including capability-off (favorited=null) -> no userRating."
```

---

### Task 3: Remove the public rating API across all surfaces + regenerate bindings

Mechanical removal mirroring the recent like/dislike/bookmark cleanup. Everything here is unused by the consumer and inert. All edits must land together — the codegen `tsc` step requires every surface to agree.

**Files:**
- Delete: `src/features/rating.ts`, `android/src/main/java/com/audiobrowser/util/RatingFactory.kt`
- Modify (TS): `src/features/metadata.ts`, `src/features/remoteControls.ts`, `src/features/player/options.ts`, `src/features/player/setup.ts`, `src/specs/audio-browser.nitro.ts`, `src/web/NativeAudioBrowser.ts`
- Modify (iOS): `ios/HybridAudioBrowser.swift`, `ios/TrackPlayerCallbacks.swift`
- Modify (Android): `android/src/main/java/com/audiobrowser/AudioBrowser.kt`, `android/src/main/java/com/audiobrowser/Callbacks.kt`, `android/src/main/java/com/audiobrowser/model/PlayerUpdateOptions.kt`, `android/src/main/java/com/audiobrowser/player/Player.kt`

**Interfaces:**
- Consumes: nothing new. Removes `onRemoteSetRating` / `handleRemoteSetRating`, `RemoteSetRatingEvent`, `Rating`/`RatingType`, the `ratingType` option, and `TrackMetadataBase.rating` from the public surface.

- [ ] **Step 1: Delete the standalone rating type module**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git rm src/features/rating.ts android/src/main/java/com/audiobrowser/util/RatingFactory.kt
```

- [ ] **Step 2: Remove rating from `src/features/metadata.ts`**

- Remove the import line `import type { Rating } from './rating'`.
- Remove the entire `RatingType` type (the `export type RatingType = | 'heart' | 'thumbs-up-down' | 'three-stars' | 'four-stars' | 'five-stars' | 'percentage' | 'none'` block).
- In `TrackMetadataBase`, remove the field:

```typescript
  /** The track rating */
  rating?: Rating
```

- [ ] **Step 3: Remove rating from `src/features/remoteControls.ts`**

- Remove the `RemoteSetRatingEvent` interface:

```typescript
/**
 * Remote set rating event.
 */
export interface RemoteSetRatingEvent {
  rating: HeartRating | ThumbsRating | StarRating | PercentageRating
}
```

- Remove the `onRemoteSetRating` emitter:

```typescript
/**
 * Subscribes to remote set rating events.
 * @param callback - Called when the user changes the rating for the track remotely
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteSetRating =
  LazyNativeEmitter.emitterize<RemoteSetRatingEvent>(
    (cb) => (nativeBrowser.onRemoteSetRating = cb)
  )
```

- Remove the now-unused rating imports at the top of the file (the `HeartRating, ThumbsRating, StarRating, PercentageRating` names imported from `./rating`). Run `corepack yarn lint` later to confirm none remain referenced.

- [ ] **Step 4: Remove the `ratingType` option from `src/features/player/options.ts` and `setup.ts`**

- In `options.ts`: remove `import type { RatingType } from '../metadata'` and every `ratingType?: RatingType` field (there are three, in the Android setup/options interfaces).
- In `setup.ts`: remove `ratingType` from the `const { … } = android` destructuring, and remove the `ratingType` key from the `definedFields({ appKilledPlaybackBehavior, skipSilence, ratingType, notificationButtons })` Android bag.

- [ ] **Step 5: Remove from the Nitro spec and web stub**

- `src/specs/audio-browser.nitro.ts`: remove `RemoteSetRatingEvent,` from the import block, the line `onRemoteSetRating: (event: RemoteSetRatingEvent) => void`, and the line `handleRemoteSetRating: ((event: RemoteSetRatingEvent) => void) | undefined`.
- `src/web/NativeAudioBrowser.ts`: remove `RemoteSetRatingEvent,` from the import block, the line `onRemoteSetRating: (event: RemoteSetRatingEvent) => void = () => {}`, and the `handleRemoteSetRating: ((event: RemoteSetRatingEvent) => void) | undefined = undefined` field.

- [ ] **Step 6: Remove from iOS**

- `ios/HybridAudioBrowser.swift`: remove `public var onRemoteSetRating: (RemoteSetRatingEvent) -> Void = { _ in }`, remove `public var handleRemoteSetRating: ((RemoteSetRatingEvent) -> Void)?`, and remove the stub:

```swift
  public func remoteSetRating(rating _: Any) {
    // TODO: Convert rating to RemoteSetRatingEvent
  }
```

(remove the whole `remoteSetRating` function body, however many lines it spans).

- `ios/TrackPlayerCallbacks.swift`: remove `func remoteSetRating(rating: Any)` and its doc comment.

- [ ] **Step 7: Remove from Android**

- `android/src/main/java/com/audiobrowser/Callbacks.kt`: remove `fun onRemoteSetRating(event: RemoteSetRatingEvent)` and the `import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent`.
- `android/src/main/java/com/audiobrowser/AudioBrowser.kt`: remove `override var onRemoteSetRating: (RemoteSetRatingEvent) -> Unit = {}`, remove the forwarding override inside the callbacks object:

```kotlin
      override fun onRemoteSetRating(event: RemoteSetRatingEvent) {
        post { this@AudioBrowser.onRemoteSetRating(event) }
      }
```

and remove the `import com.margelo.nitro.audiobrowser.RemoteSetRatingEvent`.
- `android/src/main/java/com/audiobrowser/model/PlayerUpdateOptions.kt`: remove the `var ratingType: NitroRatingType? = null,` field, the `androidOptions.ratingType?.let { ratingType = it }` line, the `ratingType = ratingType,` argument, and the `import com.margelo.nitro.audiobrowser.RatingType as NitroRatingType`.
- `android/src/main/java/com/audiobrowser/player/Player.kt`: remove `var ratingType: RatingType = RatingType.NONE`, the `val ratingTypeChanged = previousOptions.ratingType != options.ratingType` line, the `ratingTypeChanged ||` term in the `hasChanged` expression, the `if (ratingTypeChanged) { options.ratingType?.let { ratingType = it } }` block, and the `import com.margelo.nitro.audiobrowser.RatingType`.

- [ ] **Step 8: Regenerate bindings (runs the `tsc` gate)**

Run: `cd ~/rg/_libraries/react-native-audio-browser && corepack yarn codegen`

Expected: completes with "Generated 1/1 HybridObject" and writes `lib/` — meaning `tsc` passed across the spec + web stub. If `tsc` errors, a reference to a removed symbol remains — fix it and re-run.

- [ ] **Step 9: Verify no references remain + lint + swift build**

```bash
cd ~/rg/_libraries/react-native-audio-browser
grep -rni "onRemoteSetRating\|RemoteSetRatingEvent\|RatingFactory\|ratingType\|\bRatingType\b" src/ ios/ android/src/main nitrogen/ --include="*.ts" --include="*.tsx" --include="*.swift" --include="*.kt"
corepack yarn lint
swift build
```

Expected: the `grep` returns nothing (clean); lint reports 0 errors; `swift build` succeeds. (`RatingType` as a metadata type and `Rating` union are fully gone.)

- [ ] **Step 10: Verify the consumer still compiles**

```bash
cd ~/rg/native-apps && yarn types
cd ~/rg/native-apps/android && ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `yarn types` exits 0; `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
cd ~/rg/_libraries/react-native-audio-browser
git add -A src/ ios/ android/src nitrogen/
git commit -m "Remove public rating API; rating is now internal to Android favoriting

The general rating surface (Rating union, Track.rating, RatingType, the Android
ratingType option, onRemoteSetRating/handleRemoteSetRating) was unused by consumers
and inert natively. Remove it across the Nitro spec, web stub, iOS, and Android, and
regenerate bindings. Android voice 'like' now flows solely through onSetRating ->
setActiveTrackFavorited (see RatingFavorites). Refs the favorites design doc."
```

---

### Task 4: Document the manual Android Auto / Assistant test

Device-only behavior — add a tester walkthrough so the voice-like path is verified on real hardware.

**Files:**
- Modify: the appropriate file under `~/rg/native-apps/manual-testing/` (the Android Auto / favorites walkthrough; if none exists, create `manual-testing/android-auto-assistant-like.md`).

- [ ] **Step 1: Add the walkthrough**

Add a section with these steps:

```markdown
## Android Auto / Google Assistant — "like" toggles favorite

Pre: a station is playing; the `favorite` capability is enabled (row hearts visible).

1. Say "Hey Google, I like this." → the now-playing track becomes favorited:
   the notification/Android Auto heart fills, and the favorite persists in-app.
2. Say "Hey Google, I don't like this." → the track is un-favorited; the heart empties.
3. Open the Favorites tab in-app → the change from step 1/2 is reflected.
4. Disable favoriting (a build/config with the `favorite` capability off) → "Hey Google,
   I like this" does nothing (the item is not advertised as rateable).
```

- [ ] **Step 2: Commit**

```bash
cd ~/rg/native-apps
git add manual-testing/
git commit -m "docs(manual-testing): Android Auto / Assistant 'like' toggles favorite"
```

---

## Self-Review

**Spec coverage:**
- Assistant "like" → favorite (binary toggle, mirror negative): Task 1 (mapping) + Task 2 (`onSetRating` rewire). ✅
- Capability gating (no advertisement when favoriting disabled): Task 2 (`TrackFactoryRatingTest`, `favorited=null` → no `userRating`). ✅
- Remove the public rating API (TS/iOS/Android, all listed symbols): Task 3. ✅
- iOS unchanged / no rating wiring: Task 3 removes only the inert iOS stub; no behavior added. ✅
- Testing (Android unit + compile gates + manual device): Tasks 1–4. ✅

**Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step shows the code. The one `// TODO` shown in Task 3 Step 6 is the *existing* stub being deleted, not a new placeholder. ✅

**Type consistency:** `RatingFavorites.favoritedFor(rating: Rating): Boolean?` defined in Task 1 and consumed in Task 2 with the same name/signature. `TestFixtures.track(..., favorited: Boolean? = null)` added in Task 2 Step 1 and used in Task 2 Step 2. `player.setActiveTrackFavorited(Boolean)` is the existing API. ✅

**Note on running Android tests:** the library has no standalone gradle wrapper. Its unit tests build through the `example-native` app — run from `~/rg/_libraries/react-native-audio-browser/apps/example-native/android` against module `:react-native-audio-browser` (confirmed via `./gradlew projects`). Task 3's *consumer* compile (`:app:compileDebugKotlin`) is a separate, real app build under `~/rg/native-apps/android`.
