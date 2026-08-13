# TODO — Favorites

One open design item. Everything else from the original review has been
resolved or invalidated — dispositions recorded below so it isn't re-litigated.

## Design — separate transitions from metadata updates

- [ ] **Don't emit `onPlaybackActiveTrackChanged` from `setActiveTrackFavorited`.**
      Today the emit is intentional: `useActiveTrack()` is keyed on this event, so
      without it the heart icon UI doesn't re-render after a favorite toggle
      (`onPlaybackQueueChanged` has the same problem). But the event semantically
      means _transition_ — every consumer (analytics, listen-time accounting,
      queue UIs) naturally treats it that way and has to filter out same-identity
      "transitions".

  Cleaner shape:
  - `onPlaybackActiveTrackChanged` fires on transitions only.
  - `onFavoriteChanged` (and any future per-track metadata event) signals
    mutations of the existing active track.
  - `useActiveTrack` / `useQueue` subscribe to both, so they still re-render
    on a favorite toggle without polluting transition consumers.

  Applies to all three implementations (iOS `HybridAudioBrowser`, Android
  `Player.setActiveTrackFavorited`, web `NativeAudioBrowser`). Once this lands:
  the same-identity guard in `~/rg/native-apps/src/player/analytics.ts` becomes
  removable.

## Resolved / invalidated (do not re-open)

- **Double `onFavoriteChanged` per MediaSession heart tap** (#38, #44) — fixed:
  `MediaSessionCallback` no longer writes the cache or emits; the single emit
  lives in `Player.setActiveTrackFavorited`.
- **`onCustomCommand` vs `onSetRating` event asymmetry** — moot:
  `onRemoteSetRating` no longer exists; both paths funnel through
  `setActiveTrackFavorited`.
- **`onCustomCommand` returns `RESULT_SUCCESS` when nothing happened** — fixed:
  `Player.setActiveTrackFavorited`/`toggleActiveTrackFavorited` return whether
  the gesture applied, and both MediaSession paths report
  `RESULT_ERROR_INVALID_STATE` when it didn't.
- **Non-atomic toggle in `onCustomCommand`** — invalid premise: Media3 dispatches
  `MediaSession.Callback` on the session's application looper (main, where the
  session is built in `Service.onCreate`), not a binder thread as under
  MediaSessionCompat, and ExoPlayer masks playlist state synchronously there —
  rapid taps serialize on one looper and each read sees the already-updated tag.
- **`lastNativeToggle` dedupe in native-apps** — already removed; the consumer
  relies on idempotent add/remove instead.
- **`INDEX_UNSET` race dropping the cache write / `HeartRating.isRated` guard**
  — fixed in earlier passes.
