# TODO — Favorites / MediaSession heart toggle

Findings from the review of the `MediaSessionCallback.setFavorited` dedup fix
(see issue radio-garden/react-native-audio-browser#38). The dedup fix removed a
real double-emit bug; these are the loose ends.

## Design — separate transitions from metadata updates

- [ ] **Don't emit `onPlaybackActiveTrackChanged` from `setActiveTrackFavorited`.**
  Today the emit is intentional (commit `f479051`): `useActiveTrack()` is keyed on
  this event, so without it the heart icon UI doesn't re-render after a favorite
  toggle. But the event semantically means *transition* — every consumer
  (analytics, listen-time accounting, queue UIs) naturally treats it that way and
  has to filter out same-URL "transitions". `onPlaybackQueueChanged` has the same
  problem.

  Cleaner shape:
  - `onPlaybackActiveTrackChanged` fires on transitions only.
  - `onFavoriteChanged` (and any future per-track metadata event) signals
    mutations of the existing active track.
  - `useActiveTrack` / `useQueue` subscribe to both, so they still re-render
    on a favorite toggle without polluting transition consumers.

  Once this lands: revert the same-URL guard in
  `~/rg/native-apps/src/player/analytics.ts:29-36` (currently defensive).

## Library bugs

- [ ] **`INDEX_UNSET` race drops the cache write.**
  `Player.setActiveTrackFavorited` returns at `Player.kt:807-808` when
  `exoPlayer.currentMediaItemIndex == C.INDEX_UNSET`, *before* the
  `browserManager.updateFavorite` write at line 813. The deleted
  `MediaSessionCallback.setFavorited` wrapper persisted to the favorites cache
  regardless of exoPlayer index state. Worse, in the `onSetRating` path
  (`MediaSessionCallback.kt:141-148`) `onRemoteSetRating` still fires after the
  no-op `setActiveTrackFavorited`, so JS observes a rating event without the
  corresponding favorite mutation.

- [x] **`onSetRating` doesn't guard on `HeartRating.isRated`.** ~~Fixed in
  `MediaSessionCallback.kt:140` — now `if (rating is HeartRating && rating.isRated)`.~~

- [ ] **`onCustomCommand` returns `RESULT_SUCCESS` when nothing happened.**
  `MediaSessionCallback.kt:122-127` unconditionally returns
  `Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))` even
  when `setActiveTrackFavorited` no-ops (INDEX_UNSET / null currentTrack). The
  controller's heart icon flips optimistically; the next state push reverts
  it, looking like a flaky control.

- [ ] **`onCustomCommand` vs `onSetRating` event asymmetry.**
  `onCustomCommand` (notification heart) does not emit `onRemoteSetRating`;
  `onSetRating` (HeartRating) does (`MediaSessionCallback.kt:147`). One user
  gesture produces different event sequences depending on whether the
  controller routes via custom action or rating API. Either emit
  `onRemoteSetRating` from `onCustomCommand` too, or drop it from
  `onSetRating`'s HeartRating branch.

- [ ] **Non-atomic toggle in `onCustomCommand`.**
  `MediaSessionCallback.kt:123-126` reads `player.currentTrack?.favorited` then
  calls `setActiveTrackFavorited(!currentFavorited)`. `currentTrack` derives
  from `exoPlayer.currentMediaItem.localConfiguration.tag`
  (`TrackFactory.kt:13`); `exoPlayer.replaceMediaItem` posts to the player
  looper, so the tag swap isn't synchronous with this binder-thread callback.
  A rapid second tap before the post lands re-reads the stale tag and queues
  the same direction again, dropping one toggle.

## Downstream follow-up (consumer)

- [ ] **Remove `lastNativeToggle` dedupe in native-apps.**
  `~/rg/native-apps/src/store/models/favorites.ts:153-168` implements a 500 ms
  `(src, favorited)` dedupe with an explicit comment citing
  `Player.kt:858 + MediaSessionCallback.kt:125`. Once consumers pull the dedup
  fix this is dead code, and the 500 ms suppression can silently drop a
  legitimate same-direction re-emit (server reconcile, rapid retoggle). Tied
  to native-apps `TODO.md:60-63`.
