# TODO

## Volume

- [ ] Add `useVolume()` hook for reactive volume state
- [ ] Investigate what volume is doing - probably a multiplier on top of system volume (0.0-1.0 range?)
- [ ] Restore volume in PlaybackStateStore (persist and restore volume level across sessions)

## Battery Optimization

- [ ] Handle `onForegroundServiceStartNotAllowedException` by persisting the event
- [ ] On next app boot, check if this occurred and surface it to JS (callback or getter)
- [ ] Let apps prompt users to disable battery optimization for background playback reliability
- [ ] Reference: `MediaSessionService.Listener.onForegroundServiceStartNotAllowedException()`

## Voice Search / MEDIA_PLAY_FROM_SEARCH

- [ ] Silent failure in voice search (Service.kt:92-106):
  - Issue: The boolean return from `playFromSearch()` is ignored - user gets no feedback if search fails
  - Recommendation: Log the result or show notification/toast when search fails (e.g., "No results found for 'query'")
- [ ] Test with voice commands like "play michael jackson billie jean"
