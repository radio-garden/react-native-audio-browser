# Android Assistant "like" → favorites; remove the public rating API

Date: 2026-06-24
Status: Approved (design)
Refs: builds on the like/dislike/bookmark feedback-command removal (#67, #71)

## Goal

"Hey Google, I like this" (and its negative) toggles the now-playing track's
favorite on Android, routed entirely through the existing favorites system. In
the same change, remove the public rating API, which is unused by consumers and
inert natively — rating becomes a purely internal mechanism that feeds
favoriting.

Two motivations, one change:

1. **Feature:** wire Google Assistant voice "like" to the favorites system on
   Android.
2. **Cleanup:** the general rating surface (`Rating` union, per-track `rating`
   metadata, `RatingType`, the Android `ratingType` option, and
   `onRemoteSetRating` / `handleRemoteSetRating`) is dead weight — the library's
   model is binary favorites, not arbitrary ratings.

## Background

Google Assistant delivers a media "thumbs up / I like this" as a Media3
`setRating` command, not as a feedback ("like") command. For a third-party app
to receive it, two things are required:

- the now-playing item must advertise that it is rateable (and of what type) —
  in Media3 this is `MediaMetadata.userRating` on the current `MediaItem`;
- `COMMAND_SET_RATING` must be in the session's available commands (it is, via
  Media3's `DEFAULT_SESSION_AND_LIBRARY_COMMANDS`);
- the app must implement `MediaSession.Callback.onSetRating`.

The Android Automotive radio guidance uses exactly this shape: `onSetRating`
with a `RATING_HEART` toggles Favorites.

Current state in this library:

- `TrackFactory` publishes each track with
  `MediaMetadata.userRating = HeartRating(favorited)`, and `Player` updates it on
  toggle — so the now-playing item already advertises heart-rateability.
- `MediaSessionCallback.onSetRating` already maps a `HeartRating` to
  `setActiveTrackFavorited(rating.isHeart)` — and *also* converts via
  `RatingFactory` and emits the public `onRemoteSetRating` event.

So the feature is largely already wired; the work is to make the
favorite-routing the *only* job of `onSetRating`, gate advertisement on the
favorite capability, and delete the redundant public rating surface.

The public rating API is confirmed unused and inert:

- the consumer (native-apps) and the webview frontend reference none of it;
- `TrackMetadataBase.rating` is a pass-through field never read by native code
  (the only `userRating` set natively is derived from `favorited`);
- the Android `ratingType` option is stored on `Player` but never applied
  (`TrackFactory` hardcodes `HeartRating` from `favorited`).

## Behavior

- **Like:** Assistant "I like this" → `setRating(HeartRating(isHeart=true))` →
  `setActiveTrackFavorited(true)` → heart fills, `onFavoriteChanged` fires, the
  consumer persists it. Same downstream path as the notification / CarPlay heart.
- **Unlike (mirror):** "I don't like this" / removing the like →
  `HeartRating(isHeart=false)` → `setActiveTrackFavorited(false)`. The negative
  signal is a true binary toggle — it removes the favorite.
- **Capability gating:** when the `favorite` capability is disabled
  (`favoriteMatch == null` → `track.favorited == null`), no `userRating` is
  published, so Assistant cannot toggle a disabled feature.
- **iOS:** unchanged. Siri "I like this" already routes to favorites via
  `INUpdateMediaAffinityIntent` (`RNABMediaAffinityHandler`). No iOS rating
  wiring exists or is added.

## Changes

### Kept — the internal mechanism

- `TrackFactory` / `Player` setting `MediaMetadata.userRating` from `favorited`
  (the advertisement). Capability gating falls out of the existing
  `favorited?.let { … }` guard; the design makes it an explicit, tested
  invariant.
- `MediaSessionCallback.onSetRating`, simplified to:

  ```kotlin
  if (rating is HeartRating && rating.isRated) {
    player.setActiveTrackFavorited(rating.isHeart)
  }
  return super.onSetRating(session, controller, rating)
  ```

  i.e. drop the `RatingFactory` conversion and the `onRemoteSetRating` emission.

### Removed — the public rating API

**TS / Nitro spec (`src/`):**

- delete `src/features/rating.ts` (`Rating`, `HeartRating`, `ThumbsRating`,
  `StarRating`, `PercentageRating`);
- `src/features/metadata.ts` — remove `RatingType` and the
  `TrackMetadataBase.rating?: Rating` field + its `Rating` import;
- `src/features/remoteControls.ts` — remove `RemoteSetRatingEvent` and the
  `onRemoteSetRating` emitter;
- `src/features/player/options.ts` + `setup.ts` — remove the Android
  `ratingType` option from the options bag + its destructuring;
- `src/specs/audio-browser.nitro.ts` + `src/web/NativeAudioBrowser.ts` — remove
  `onRemoteSetRating` / `handleRemoteSetRating` and the `RemoteSetRatingEvent`
  import.

**iOS:**

- `HybridAudioBrowser.swift` — remove `onRemoteSetRating` / `handleRemoteSetRating`
  vars and the `remoteSetRating(rating:)` TODO stub;
- `TrackPlayerCallbacks.swift` — remove `remoteSetRating`.

**Android:**

- `AudioBrowser.kt` / `Callbacks.kt` — remove the `onRemoteSetRating` override +
  interface method;
- delete `util/RatingFactory.kt` (only used to build the bridge event);
- `model/PlayerUpdateOptions.kt` / `player/Player.kt` — remove the `ratingType`
  field + its (no-op) change handling.

This drops the *general* rating concept (stars / percentage / thumbs) and the
per-track `rating` field entirely, keeping only binary favorites.

## Edge cases / invariants

- **Capability off → no voice toggle.** `favoriteMatch == null` → no
  `userRating` advertised → Assistant cannot toggle. (Tested.)
- **Idempotent / redundant ratings.** `setActiveTrackFavorited` no-ops on
  unchanged state and `onFavoriteChanged` de-dupes, so a repeated "like" is
  harmless.
- **`isRated == false`.** A cleared/unrated `HeartRating` is ignored — only an
  explicit like/unlike (`isRated == true`) toggles. Matches current behavior.
- **No now-playing track.** `setActiveTrackFavorited` on a null current track is
  a safe no-op (existing guard).

## Testing

- **Android unit (`onSetRating`):** `HeartRating(true)` favorites the current
  track + fires `onFavoriteChanged`; `HeartRating(false)` un-favorites;
  capability-off publishes no `userRating` and performs no toggle.
- **Compile gates:** `corepack yarn codegen` (regenerates Nitro bindings + runs
  `tsc`), `corepack yarn lint`, `swift build`, and `:app:compileDebugKotlin` in
  native-apps.
- **Manual (device), added to `manual-testing/`:** with Android Auto / Assistant,
  "Hey Google, I like this" hearts the now-playing track and persists; "I don't
  like this" removes it; the notification heart reflects the change.

## Out of scope

- iOS rating wiring (Siri already covered by the affinity intent).
- Any non-favorite rating semantics (stars, percentage, thumbs) — explicitly
  removed, not deferred.
