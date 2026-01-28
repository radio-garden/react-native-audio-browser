# TODO

Note: TODO items are AI generated and can include incorrect or incomplete details

## API Refactor: Separate ArtworkRequestConfig from MediaRequestConfig

- [x] Create separate `ArtworkRequestConfig` type instead of reusing `MediaRequestConfig`
  - ~~Current `MediaRequestConfig` is overloaded with artwork-specific fields (`imageQueryParams`)~~
  - The `transform` callback has different semantics for media vs artwork (artwork gets `ImageContext`)

- [ ] Simplify by removing `resolve` and passing `track` to `transform` instead
  - Current: two callbacks (`resolve` + `transform`) with overlapping responsibilities
  - Proposed: single `transform` callback that receives everything

  ```typescript
  interface ArtworkTransformParams {
    request: RequestConfig // Merged base config (track.artwork as default path)
    track: Track // The track being processed
    context?: ImageContext // Size hints from AA/CarPlay (undefined at browse-time)
  }

  interface ArtworkRequestConfig extends RequestConfig {
    transform?: (
      params: ArtworkTransformParams
    ) => Promise<RequestConfig | null>
    imageQueryParams?: ImageQueryParams
  }
  ```

  - Return `null` from transform to skip artwork for a track
  - Covers all use cases: URL construction, signing, size params, conditional artwork

- [ ] Apply same pattern to `MediaRequestConfig` for audio streams

  ```typescript
  interface MediaTransformParams {
    request: RequestConfig
    track: Track
  }

  interface MediaRequestConfig extends RequestConfig {
    transform?: (params: MediaTransformParams) => Promise<RequestConfig>
  }
  ```

  - Enables per-track URL signing for audio streams too

## CarPlay (iOS)

- [ ] By default there should be no icon in the tabs on CarPlay
  - Currently tabs may show a default icon
  - Tabs should only show icons if explicitly configured

- [ ] Consider adding a configurable loading message for CarPlay cold start
  - Currently shows blank screen while waiting for `configureBrowser()` to be called
  - Could add `carPlayLoadingMessage?: string` to browser config
  - Or just use a generic "Loading..." - but that's not localized

- [ ] CarPlay lazy tab loading improvements
  - **Loading indicator:** When a tab is selected for the first time, there's no visual feedback while content loads. The tab appears empty until `loadContent` completes. Consider showing a loading item in the section.
  - **Race condition:** If user rapidly switches tabs, multiple `loadContent` calls could be in flight. Current code doesn't track in-flight loads, so `loadContentIfNeeded` could be called multiple times before the first load completes. Consider adding a `Set<String>` to track loading paths.
  - **Error handling:** `loadContent(for:into:)` logs errors but doesn't provide user feedback. A failed lazy load leaves an empty tab with no indication of what went wrong. Consider showing an error item with retry option.

- [ ] CarPlay list item spinner for async operations
  - When a list item is selected, the handler receives a completion block
  - If async work is initiated without immediately calling completion, CarPlay displays a spinner
  - Call completion block when ready to tell CarPlay to remove the spinner
  - Currently we may be calling completion too early or not leveraging this for loading states

- [ ] Configure CPNowPlayingTemplate immediately on CarPlay connect
  - Per WWDC: "When your app connects to the CarPlay scene, that's a great time to set up the shared nowPlayingTemplate"
  - System may launch app just to show Now Playing - template should be ready immediately
  - Configure playback rate button, Up Next button, and observers right away
  - Can always update the shared template later
  - Currently we may be configuring it too late in the lifecycle

- [ ] Album/Artist button on Now Playing screen
  - Enable `CPNowPlayingTemplate.shared.isAlbumArtistButtonEnabled`
  - Implement `nowPlayingTemplateAlbumArtistButtonTapped` (stub exists in `NowPlayingObserver`)

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

- [ ] Move `carPlayUpNextButton` and `carPlayNowPlayingButtons` to `updateOptions`
  - Currently these are in `BrowserConfiguration` but they're really player options
  - Should be moved to `IOSUpdateOptions` alongside other player configuration
  - This matches the pattern where browser config is about content/navigation, updateOptions is about player behavior
  - Would make them updatable at runtime like other player options

- [ ] Investigate renaming `carPlayNowPlayingButtons` to `iosNowPlayingButtons`
  - Currently only used for CarPlay Now Playing screen
  - Check if regular iOS Now Playing (lock screen, Control Center) supports these buttons too
  - If so, rename to `iosNowPlayingButtons` for clarity
  - Requires testing on a physical device (simulator may not show full Now Playing UI)

## Playback Rate

- [x] Make playback rate options configurable
  - ~~Currently hardcoded in CarPlayController: `[0.5, 1.0, 1.5, 2.0]`~~
  - ~~Add `carPlayPlaybackRates?: number[]` to BrowserConfiguration~~ → `iosPlaybackRates` in `updateOptions()`
  - Default: `[0.5, 1.0, 1.5, 2.0]`
  - Android Auto: No equivalent UI (no `CPNowPlayingPlaybackRateButton`)

- [ ] Move `iosPlaybackRates` into `IOSUpdateOptions`
  - Currently a top-level option in `UpdateOptions`
  - Should be nested under `ios: { playbackRates: [...] }` for consistency

- [ ] Add manual testing steps for Now Playing screen updating when `updateOptions()` is called
  - Verify shuffle/repeat/rate button states update when capabilities change
  - Test adding/removing capabilities at runtime
  - Ensure CarPlay Now Playing reflects changes immediately

## Capabilities API

- [ ] Investigate where MPFeedbackCommand `localizedTitle`/`localizedShortTitle` are displayed
  - iOS `likeCommand` uses these properties but unclear where they're visible to users
  - Possibilities: VoiceOver/accessibility, Siri, CarPlay, or not displayed at all
  - Currently hardcoded to "Favorite" in `Capability+RemoteCommand.swift`
  - If visible somewhere useful, consider making configurable via `iosFavoriteTitle` in `updateOptions()`
  - Apple docs: "a localized string used to describe the context of a command"
  - See: https://developer.apple.com/documentation/mediaplayer/mpfeedbackcommand/1622905-localizedtitle

- [ ] Remove like/dislike/bookmark capabilities and callbacks until proper implementation
  - Currently half-implemented: capabilities exist but no clear user-facing functionality
  - `like` maps to iOS dislike command (confusing mapping)
  - `bookmark` and `dislike` exist but unclear what they should do
  - Should be removed until we design a proper rating/feedback system
  - Affects: PlayerCapabilities interface, iOS buildRemoteCommands, Android MediaSessionCommandManager
  - Keep `favorite` since it has clear semantics and implementation

- [ ] Refactor capabilities/notification buttons/now playing buttons for better alignment
  - Current system is inconsistent and confusing:
    - PlayerCapabilities: `skip?: boolean, jump?: boolean` (unified capabilities)
    - NotificationButton: `'skip-to-previous' | 'skip-to-next' | 'jump-backward' | 'jump-forward'` (separate buttons)
    - NowPlayingButtons: `nextPrevious: 'skip' | 'jump'` (iOS constraint forcing choice)
  - Problems: Different naming, different granularity, platform leakage
  - Goal: Consistent naming and concepts across all three systems
  - Consider platform-specific capabilities approach or unified capability->button mapping
  - See controls-plan.md for proposed redesign

- [x] Change `capabilities: Capability[]` to `capabilities: PlayerCapabilities` interface
  - Use an interface with optional boolean properties instead of an array
  - All capabilities enabled by default (`undefined` or `true` = enabled, `false` = disabled)
  - More ergonomic: only specify what you want to disable

  ```typescript
  interface PlayerCapabilities {
    play?: boolean
    pause?: boolean
    stop?: boolean
    seekTo?: boolean
    skipToNext?: boolean
    skipToPrevious?: boolean
    jumpForward?: boolean
    jumpBackward?: boolean
    favorite?: boolean
    bookmark?: boolean
    shuffleMode?: boolean
    repeatMode?: boolean
    playbackRate?: boolean
  }

  // Usage: disable only specific capabilities
  updateOptions({
    capabilities: {
      shuffleMode: false,
      repeatMode: false
    }
  })
  ```

  - [x] Breaking change - updated iOS and Android to check for `!= false` instead of array membership
  - [x] Removed `playFromId`, `playFromSearch`, and `skip` - they didn't gate any functionality
  - [x] Renamed `ButtonCapability` to `NotificationButton` for clarity
  - [x] Added JSDoc comments explaining what each capability enables/disables

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

## Example App

- [ ] Replace current example audio tracks with something else
  - Need to update the example app's audio content
  - Potential sources for freely usable and self-hostable audio files:
    - **Free Music Archive (FMA)**: CC-licensed music tracks
    - **ccMixter**: Community music remixes under Creative Commons
    - **Incompetech**: Royalty-free music by Kevin MacLeod
    - **Freesound**: Short audio clips and sound effects (CC licenses)
    - **YouTube Audio Library**: Free music and sound effects
    - **Internet Archive's Audio Collection**: Public domain recordings
    - **Musopen**: Classical music recordings in public domain
    - **Sample Focus**: Free samples and loops for music production
    - **NASA Audio Collection**: Space-related sounds and recordings (public domain)
    - **BBC Sound Effects**: 33,000+ sound effects under RemArc license
