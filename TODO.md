# TODO

## Browser Configuration

- [ ] Consider renaming `play` property in `BrowserConfiguration` to better describe what it does (controls queue behavior when navigating to/selecting a track)
- [ ] Unified browse configuration - consolidate `routes` + `browse` into a single concept

## Error Handling

- [ ] Error propagation for navigation failures:
  - [ ] Add `onNavigationError: (error: NavigationError) => void` callback
  - [ ] Add `getNavigationError(): NavigationError | undefined` (cleared on successful navigation)
  - [ ] Define `NavigationError` type with path, message, and error code
  - [ ] Consider: Should navigation methods return `Promise<void>` for error handling?

## Android / MediaSession

- [ ] Implement playback resumption in `MediaSessionCallback.onPlaybackResumption()`:
  - [ ] add some kind of configuration to BrowserConfiguration to handle playback
        resumption, i.e. returning the most recently played queue
  - [ ] Return stored `MediaItemsWithStartPosition` to restore playback state
