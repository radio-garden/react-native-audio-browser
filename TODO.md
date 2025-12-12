# TODO

Note: TODO items are AI generated and can include incorrect or incomplete details

## CarPlay (iOS)

- [ ] Consider adding a configurable loading message for CarPlay cold start
  - Currently shows blank screen while waiting for `configureBrowser()` to be called
  - Could add `carPlayLoadingMessage?: string` to browser config
  - Or just use a generic "Loading..." - but that's not localized

- [ ] Album/Artist button on Now Playing screen
  - Enable `CPNowPlayingTemplate.shared.isAlbumArtistButtonEnabled`
  - Implement `nowPlayingTemplateAlbumArtistButtonTapped` (stub exists in `NowPlayingObserver`)
  - Apple's sample: searches for artist name and auto-plays results
  - Our approach: search for artist and show results list (more user control)
  - Requires search to be configured in browser config

- [ ] Test Now Playing screen on a real CarPlay device
  - Artwork not showing in simulator - need to verify on real hardware
  - MPNowPlayingInfoCenter is being set correctly (hasArtwork=true in logs)
  - May be a simulator limitation

- [ ] CarPlay/Siri voice search (iOS) - library-friendly approach
  - SiriKit requires app-level setup (entitlements, Intent Extension target, Info.plist)
  - Library cannot add these - must be done by host app
  - **Library provides:** `handlePlayMediaIntent(searchTerm: string)` method
    - Searches via browser's search callback
    - Loads results into queue and starts playback
    - Returns success/failure for intent response
  - **App provides:** IntentHandler that calls the library method
    ```swift
    class IntentHandler: INExtension, INPlayMediaIntentHandling {
      func handle(intent: INPlayMediaIntent, completion: ...) {
        let searchTerm = intent.mediaSearch?.mediaName ?? ""
        AudioBrowser.shared.handlePlayMediaIntent(searchTerm) { success in
          completion(INPlayMediaIntentResponse(code: success ? .success : .failure, ...))
        }
      }
    }
    ```
  - **Documentation needed:** Setup guide for Intent Extension, entitlements, Info.plist
  - Mirrors Android's `playFromSearch()` for `MEDIA_PLAY_FROM_SEARCH`
  - Note: `CPSearchTemplate` is for navigation apps, not audio apps
  - Note: `CPAssistantCellConfiguration` requires SiriKit - crashes without it

## Now Playing Buttons (iOS)

- [ ] Investigate renaming `carPlayNowPlayingButtons` to `iosNowPlayingButtons`
  - Currently only used for CarPlay Now Playing screen
  - Check if regular iOS Now Playing (lock screen, Control Center) supports these buttons too
  - If so, rename to `iosNowPlayingButtons` for clarity
  - Also check if this should be in `updateOptions` instead of browser config
  - Requires testing on a physical device (simulator may not show full Now Playing UI)

## Playback Rate

- [x] Make playback rate options configurable
  - ~~Currently hardcoded in CarPlayController: `[0.5, 1.0, 1.5, 2.0]`~~
  - ~~Add `carPlayPlaybackRates?: number[]` to BrowserConfiguration~~ → `carPlayNowPlayingRates`
  - Default: `[1.0, 1.5, 2.0]`
  - Android Auto: No equivalent UI (no `CPNowPlayingPlaybackRateButton`)

## State Persistence (iOS)

- [ ] Persist and restore player settings on iOS
  - Playback rate - save/restore `rate` property
  - Repeat mode - save/restore `repeatMode`
  - Shuffle mode - save/restore `shuffleEnabled`
  - Android already does this via `PlaybackStateStore`
  - Use UserDefaults or similar for iOS persistence
  - Restore settings in `setupPlayer()` or when player initializes

## CarPlay / Android Auto (shared)

- [ ] Pass platform limits to callbacks and API endpoints
  - CarPlay has runtime limits: `CPTabBarTemplate.maximumTabCount`, `CPListTemplate.maximumSectionCount`, `CPListTemplate.maximumItemCount`
  - Android Auto has similar constraints
  - Currently: CarPlay truncates data after fetching full content
  - Proposed: Pass limits in `BrowserSourceCallbackParam` so data sources can optimize
  ```typescript
  interface BrowserSourceCallbackParam {
    path: string
    params: Record<string, string>
    limits?: {
      maxSections?: number
      maxItems?: number
    }
  }
  ```
  - Requires: CarPlay/Android Auto to make separate requests rather than sharing `onContentChanged`

## Browser Architecture

- [ ] Allow sync browser callbacks (currently all callbacks return `Promise<Promise<T>>`)
  - Some callbacks could be synchronous for simpler use cases
  - Would simplify routes like:
    ```typescript
    // Current (async required)
    '/favorites'() {
      return Promise.resolve({
        url: '/favorites',
        title: 'Favorites',
        children: favorites
      })
    }
    // Desired (sync)
    '/favorites': () => ({
      url: '/favorites',
      title: 'Favorites',
      children: favorites
    })
    ```
  - Would require Nitro spec changes and native implementation updates

- [ ] Move initial navigation logic from Nitro layer to BrowserManager on both platforms
  - Currently: `AudioBrowser.kt` and `HybridAudioBrowser.swift` handle initial navigation in `configuration` setter
  - Proposed: `BrowserManager` should auto-navigate when `config` is set, with an `onNavigationError` callback
  - Benefits: Cleaner separation, self-contained BrowserManager, less duplication between platforms

## Queue Optimization
- [ ] Handle duplicate tracks in queue source optimization
  - **Problem:** Current optimization uses `firstIndex(where: { $0.src == trackId })` / `indexOfFirst { it.src == trackId }`
  - If a playlist contains the same track twice, selecting the second instance jumps to the first
  - **Options:**
    1. **Encode index in contextual URL:** Change `__trackId={src}` to `__trackId={src}&__index={n}`
       - Preserves position from browse time
       - Requires changes to BrowserPathHelper on both platforms
    2. **Add unique instance ID to Track:** New `instanceId: string?` field generated client-side
       - Cleaner architecturally, useful for other features (reordering, removing specific instances)
       - Would change contextual URL to use instanceId instead of src
       - ID only stable for queue lifetime (fine for this optimization, resumption re-expands anyway)
       - Requires Nitro type changes and codegen
    3. **Accept limitation:** Document that optimization only works for playlists without duplicates
       - Simplest, but edge case will behave incorrectly
  - Decision pending - need to weigh complexity vs correctness

## Volume

- [ ] Add `useVolume()` hook for reactive volume state
- [ ] Investigate what volume is doing - probably a multiplier on top of system volume (0.0-1.0 range?)
- [ ] Restore volume in PlaybackStateStore (persist and restore volume level across sessions)

## Voice Search / MEDIA_PLAY_FROM_SEARCH

- [ ] Silent failure in voice search (Service.kt:92-106):
  - Issue: The boolean return from `playFromSearch()` is ignored - user gets no feedback if search fails
  - Recommendation: Log the result or show notification/toast when search fails (e.g., "No results found for 'query'")
- [ ] Test with voice commands like "play michael jackson billie jean"
