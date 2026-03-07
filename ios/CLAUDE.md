# iOS Development Guide

## Quick Commands

- `yarn ios:rebuild` - Rebuild and run with pod install (required after adding/removing files)
- `yarn ios:format` - Format Swift code

## Architecture

```mermaid
graph TB
JS["React Native Layer<br/>JavaScript"]

subgraph Nitro["Nitro Hybrid Object"]
  HAB["HybridAudioBrowser<br/>Implements HybridAudioBrowserSpec<br/>~50 callback properties"]
end

TP["TrackPlayer<br/>Core AVPlayer Logic<br/>State Machine (PlaybackEvent)<br/>Owns: ML, NPU, LSC"]

TS["TrackSelector<br/>@MainActor<br/>Shared Track Selection<br/>Decision Tree + Queue Expansion"]

subgraph Queue["Queue Management"]
  QM["QueueManager<br/>Queue State, Navigation<br/>Repeat Mode, Shuffle"]
  SO["ShuffleOrder<br/>Fisher-Yates Shuffle"]
end

subgraph Browser["Browser System"]
  BM["BrowserManager<br/>@MainActor<br/>Navigation, Routing, Caching<br/>URL Resolution"]
  SR["SimpleRouter<br/>Route Pattern Matching<br/>Parameter Extraction"]
  BPH["BrowserPathHelper<br/>Contextual URLs<br/>Path Utilities"]
  BC["BrowserConfig<br/>Configuration Wrapper"]
  LRU["LRUCache<br/>Track and Content Cache<br/>Thread-safe"]
end

HC["HttpClient<br/>URLSession Wrapper"]

subgraph Platform["Apple Platform APIs"]
  AVP["AVPlayer<br/>Apple AVFoundation"]
  MPRC["MPRemoteCommandCenter<br/>Lock Screen, CarPlay"]
  AS["AVAudioSession<br/>Audio Session"]
  MPNP["MPNowPlayingInfoCenter<br/>Now Playing Info"]
  CPT["CPTemplateApplicationScene<br/>CarPlay Templates"]
end

subgraph Observers["Observer Layer"]
  PSO["PlayerStateObserver<br/>KVO status, timeControlStatus"]
  PTO["PlayerTimeObserver<br/>Periodic and Boundary Time"]
  PINO["PlayerItemNotificationObserver<br/>End and Fail Events"]
  PIPO["PlayerItemPropertyObserver<br/>Duration, Metadata, Buffering"]
end

subgraph StateManagers["State Managers"]
  PSM["PlayingStateManager<br/>playing, buffering flags"]
  PPUM["PlaybackProgressUpdateManager<br/>Timer-based Progress"]
  STM["SleepTimerManager<br/>Time and End-of-Track"]
  RM["RetryManager<br/>Exponential Backoff<br/>Network-aware Acceleration"]
end

subgraph Controllers["Controllers"]
  RCC["RemoteCommandController<br/>MPRemoteCommand Handlers<br/>Lazy Handler Overrides"]
  NPIC["NowPlayingInfoController<br/>@MainActor<br/>iOS 16+ Auto Publishing"]
end

subgraph Loaders["Media Loading"]
  ML["MediaLoader<br/>URL Resolution, AVURLAsset<br/>Metadata + Playable loading"]
  MLD["MediaLoaderDelegate<br/>Protocol"]
  NPU["NowPlayingUpdater<br/>Metadata + Artwork<br/>MPNowPlayingInfoCenter"]
  LSC["LoadSeekCoordinator<br/>Deferred Seek State<br/>pendingSeek / seekInFlight"]
  SCH["SeekCompletionHandler<br/>Protocol"]
end

subgraph CarPlay["CarPlay"]
  CPC["CarPlayController<br/>@MainActor @objc<br/>Tab Bar, List, Navigation"]
  CPIL["CarPlayImageLoader<br/>@MainActor<br/>SF Symbols, Artwork, Tinting"]
  CPAR["CarPlayArtworkResolver<br/>Artwork Load Strategy"]
  CPNPM["CarPlayNowPlayingManager<br/>@MainActor<br/>Now Playing, Buttons, Up Next"]
  MIH["RNABMediaIntentHandler<br/>Siri Intent Handling"]
end

subgraph Utilities["Utilities"]
  NM["NetworkMonitor<br/>NWPathMonitor"]
  EM["Emitter<br/>Multi-listener Events"]
  OV["OnceValue<br/>Async Init Gate"]
  SFR["SFSymbolRenderer<br/>SF Symbol to Image"]
end

TPC["TrackPlayerCallbacks<br/>@MainActor Protocol<br/>~30 methods"]

JS -->|"Direct sync/async calls"| HAB
HAB -->|Owns| TP
HAB -->|Owns| BM
HAB -->|Owns| TS
HAB -->|Uses| NM
HAB -->|Uses| EM
HAB -.->|Implements| TPC

TS -->|"Queue reuse, expand, intercept"| BM
TS -->|"Reads queue state"| TP
CPC -->|Owns| TS

BM --> SR
BM --> BPH
BM --> BC
BM --> HC
BM -->|"trackCache 3000, contentCache 20"| LRU
BM --> SFR

TP -->|Controls| AVP
TP -->|Owns| QM
TP -->|Owns| PSO
TP -->|Owns| PTO
TP -->|Owns| PINO
TP -->|Owns| PIPO
TP -->|Owns| PSM
TP -->|Owns| PPUM
TP -->|Owns| STM
TP -->|Owns| RM
TP -->|Owns| RCC
TP -->|Owns| NPIC
TP -->|Owns| ML
TP -->|Owns| NPU
TP -->|Owns| LSC
TP -.->|Implements| MLD
TP -.->|Implements| SCH
ML -.->|Delegate protocol| MLD
ML -->|delegate: TrackPlayer| TP
LSC -.->|delegate protocol| SCH
LSC -->|delegate: TrackPlayer| TP
NPU -->|Updates| NPIC

QM --> SO

PSO -->|KVO| AVP
PTO -->|addPeriodicTimeObserver| AVP
PINO -->|NotificationCenter| AVP
PIPO -->|KVO| AVP

RCC -->|addTarget| MPRC
NPIC -->|nowPlayingInfo| MPNP
TP -->|setCategory| AS
RM -->|Monitors| NM

CPC -->|Owns| CPIL
CPC -->|Owns| CPNPM
CPC -->|"via HAB.browserManager"| BM
CPC -->|"via HAB.getPlayer()"| TP
CPC -->|Templates| CPT
CPIL -->|Uses| CPAR
CPIL -->|"resolveArtworkUrl"| BM
CPNPM -->|"via HAB.getPlayer()"| TP
CPNPM -->|Templates| CPT
MIH -->|Search & Play| HAB
OV -->|"Gates CarPlay cold start"| HAB

TPC -.->|Events| HAB
HAB -.->|"~50 callbacks to JS"| JS

classDef nitro fill:#e1f5ff,stroke:#333,stroke-width:2px
classDef core fill:#ffe1e1,stroke:#333,stroke-width:2px
classDef browser fill:#f3e5f5,stroke:#333,stroke-width:2px
classDef observer fill:#e1ffe1,stroke:#333,stroke-width:2px
classDef controller fill:#fff3e1,stroke:#333,stroke-width:2px
classDef platform fill:#f0f0f0,stroke:#333,stroke-width:2px
classDef state fill:#e8f5e9,stroke:#333,stroke-width:2px
classDef util fill:#fafafa,stroke:#333,stroke-width:2px
classDef protocol fill:#fff8e1,stroke:#333,stroke-width:1px,stroke-dasharray:5 5
classDef carplay fill:#e1f0ff,stroke:#333,stroke-width:2px
classDef queue fill:#fce4ec,stroke:#333,stroke-width:2px
classDef loader fill:#fdf3e1,stroke:#333,stroke-width:2px

class HAB nitro
class TP core
class TS core
class BM,SR,BPH,BC,LRU browser
class HC browser
class PSO,PTO,PINO,PIPO observer
class RCC,NPIC controller
class AVP,MPRC,AS,MPNP,CPT,JS platform
class PSM,PPUM,STM,RM state
class NM,EM,OV,SFR util
class TPC,MLD,SCH protocol
class CPC,CPIL,CPAR,CPNPM,MIH carplay
class QM,SO queue
class ML,NPU,LSC loader
```

## Key Architecture Points

### Nitro Module Pattern

- `HybridAudioBrowser` is the single entry point, implementing `HybridAudioBrowserSpec`
- Unified interface for both browsing and playback functionality
- Direct JS-native method calls (no event emitters)
- ~50 callbacks as closure properties organized into categories:
  - **Browser** (4): `onPathChanged`, `onContentChanged`, `onTabsChanged`, `onNavigationError`
  - **Player** (14): `onPlaybackProgressUpdated`, `onPlaybackActiveTrackChanged`, `onPlaybackPlayingState`, etc.
  - **Remote** (16): `onRemotePlay`, `onRemotePause`, `onRemoteNext`, etc.
  - **Remote Handlers** (14): `handleRemotePlay`, `handleRemotePause`, etc. (optional JS overrides)
  - **Other** (8): `onOptionsChanged`, `onFavoriteChanged`, `onNowPlayingChanged`, `onOnlineChanged`, etc.
- Types generated by Nitrogen in `nitrogen/generated/ios/swift/`

### Data Flow

1. **JS → Native**: Direct sync/async method calls via Nitro
2. **Native → JS**: Callback properties invoked from native code
3. **Browser → Player**: `navigateTrack()` uses TrackSelector to resolve track selection (contextual URL expansion, queue reuse, handler interception) and then executes the resulting PlaybackIntent
4. **Player → Queue**: TrackPlayer delegates queue operations to QueueManager (pure state, no AVPlayer knowledge)
5. **Player → Controllers**: TrackPlayer owns RemoteCommandController and NowPlayingInfoController
6. **Player → MediaLoader**: TrackPlayer delegates URL resolution and asset loading to MediaLoader; receives results via MediaLoaderDelegate
7. **Player → NowPlayingUpdater**: TrackPlayer calls NowPlayingUpdater for track metadata and playback state; NowPlayingUpdater updates NowPlayingInfoController
8. **Player → LoadSeekCoordinator**: TrackPlayer uses LoadSeekCoordinator to capture and execute seeks that arrive before AVPlayerItem is ready; receives completion via SeekCompletionHandler
9. **Platform → Observers**: KVO and NotificationCenter feed back to TrackPlayer
10. **State Machine**: PlaybackEvent triggers deterministic transitions in TrackPlayer via `transition(_ event)` with side effects

### Thread Safety

- `HybridAudioBrowser.onMainActor()` ensures player operations run on main thread
- `TrackPlayer`, `BrowserManager`, all observers, controllers, and state managers are `@MainActor`
- `LRUCache` uses NSLock for thread-safe access
- `UncheckedSendableBox` wrapper for non-Sendable return types from `onMainActor()` calls

### Relationship to RNTP

This codebase is adapted from react-native-track-player. Key differences:

- **Bridge layer removed** - No `TrackPlayerModule.swift`, no Obj-C bridge
- **Nitro types used** - `Track`, `PlayingState`, events from nitrogen/generated
- **Browser added** - Navigation, routing, HTTP client (not in RNTP)
- **Unified API** - Browser and player merged into single HybridAudioBrowser
- **URL Resolution** - Media and artwork URL transforms via configuration

## Project Structure

```
ios/
├── HybridAudioBrowser.swift          # Main Nitro entry point
│                                     # Implements HybridAudioBrowserSpec & TrackPlayerCallbacks
│                                     # ~50 callback properties (browser, player, remote)
├── TrackPlayer.swift                 # Core AVPlayer logic
│                                     # State machine (PlaybackEvent → PlaybackState)
│                                     # Owns all observers, state managers, controllers
│                                     # Owns MediaLoader, NowPlayingUpdater, LoadSeekCoordinator
│                                     # Conforms to MediaLoaderDelegate, SeekCompletionHandler
├── PlaybackEvent.swift              # PlaybackEvent enum and PlaybackState
│                                     # State machine transition function nextState(from:on:)
├── TrackSelector.swift               # @MainActor shared track selection decision tree
│                                     # Used by HybridAudioBrowser and CarPlayController
│                                     # Returns SelectionResult enum (play/intercepted/browse/none)
│                                     # Handles contextual URL expansion, queue reuse, handler interception
├── TrackPlayerCallbacks.swift        # @MainActor callback protocol (~30 methods)
│                                     # Bridge between TrackPlayer events and HybridAudioBrowser
├── Browser/
│   ├── BrowserManager.swift          # @MainActor navigation, routing, caching
│   │                                 # URL resolution (media, artwork)
│   │                                 # Favorites hydration, queue expansion
│   ├── SimpleRouter.swift            # Route pattern matching
│   │                                 # Supports {param}, *, ** wildcards
│   │                                 # Specificity-based best match
│   ├── BrowserConfig.swift           # Configuration wrapper
│   ├── BrowserPathHelper.swift       # Path utilities & contextual URLs
│   │                                 # __trackId encoding for playable-only tracks
│   └── JsonModels.swift              # JSON Codable models for API responses
├── Observer/
│   ├── PlayerStateObserver.swift     # @MainActor KVO: AVPlayer.status, timeControlStatus
│   ├── PlayerTimeObserver.swift      # Periodic & boundary time events
│   ├── PlayerItemNotificationObserver.swift  # Track end/fail notifications
│   └── PlayerItemPropertyObserver.swift      # Duration, metadata, buffering
├── Player/
│   ├── QueueManager.swift            # Queue state, navigation, repeat, shuffle
│   │                                 # Delegate: QueueManagerDelegate
│   │                                 # Returns QueueNavigationResult (.trackChanged, .sameTrackReplay, .noChange)
│   ├── ShuffleOrder.swift            # Fisher-Yates shuffle ordering
│   ├── MediaLoader.swift             # @MainActor URL resolution, AVURLAsset creation
│   │                                 # Async metadata and playable loading
│   │                                 # Notifies via MediaLoaderDelegate
│   ├── MediaLoaderDelegate.swift     # @MainActor protocol: item ready, errors, metadata callbacks
│   ├── NowPlayingUpdater.swift       # @MainActor MPNowPlayingInfoCenter updates
│   │                                 # Track metadata, artwork loading (Kingfisher)
│   │                                 # Playback values and playback state
│   ├── LoadSeekCoordinator.swift     # Deferred seek state machine (idle/pendingSeek/seekInFlight)
│   │                                 # Handles seeks arriving before AVPlayerItem is ready
│   ├── SeekCompletionHandler.swift   # @MainActor protocol: handleSeekCompleted(to:didFinish:)
│   ├── PlayingStateManager.swift     # Computes playing/buffering state
│   ├── SleepTimerManager.swift       # @MainActor sleep timer (time & end-of-track)
│   ├── PlaybackProgressUpdateManager.swift   # Timer-based periodic progress
│   └── RetryManager.swift            # Exponential backoff with network-aware acceleration
├── NowPlayingInfo/
│   ├── NowPlayingInfoController.swift # @MainActor MPNowPlayingInfoCenter
│   │                                 # iOS 16+ auto publishing via MPNowPlayingSession
│   │                                 # iOS 15.x manual fallback
│   ├── NowPlayingInfoCenter.swift    # Protocol for testability
│   ├── NowPlayingInfoKeyValue.swift  # Key-value protocol
│   ├── MediaItemProperty.swift       # Track metadata properties
│   └── NowPlayingInfoProperty.swift  # Playback state properties
├── RemoteCommand/
│   └── RemoteCommandController.swift # @MainActor MPRemoteCommandCenter handlers
│                                     # Lazy handler properties for customization
│                                     # iOS 16+ session command center switching
├── CarPlay/
│   ├── CarPlayController.swift       # @MainActor @objc CarPlay scene delegate
│   │                                 # CPTabBarTemplate, CPListTemplate, Navigation
│   │                                 # Owns CarPlayImageLoader, CarPlayNowPlayingManager
│   ├── CarPlayArtworkResolver.swift   # Platform-independent artwork load strategy
│   │                                 # SF symbol detection, URL resolution, tinting/SVG decisions
│   ├── CarPlayImageLoader.swift      # @MainActor image loading service
│   │                                 # SF Symbols, artwork loading, adaptive tinting
│   ├── CarPlayNowPlayingManager.swift # @MainActor Now Playing management
│   │                                 # Buttons, Up Next, NowPlayingObserver
│   ├── RNABMediaIntentHandler.swift  # Siri INPlayMediaIntent handling
│   └── Track+CarPlay.swift           # CarPlay-specific Track extensions
├── Http/
│   └── HttpClient.swift              # URLSession wrapper
│                                     # JSON decoding with detailed errors
├── Model/
│   ├── TrackPlayerError.swift        # PlaybackError, QueueError
│   ├── MediaURL.swift                # URL parsing utilities
│   ├── RemoteCommand.swift           # Remote command enum with config
│   ├── SourceType.swift              # file vs stream detection
│   ├── PlayerUpdateOptions.swift     # Update options struct
│   └── NitroTypeStubs.swift          # Nitro type stubs
├── Extension/
│   ├── Track+AVPlayer.swift          # Track artwork loading
│   ├── Track+Copying.swift           # Track.copying() for selective field updates
│   ├── Capability+RemoteCommand.swift # Capability to RemoteCommand conversion
│   ├── ChapterMetadata+AVFoundation.swift  # AVTimedMetadataGroup → ChapterMetadata
│   ├── TimedMetadata+AVFoundation.swift    # AVTimedMetadataGroup → TimedMetadata
│   ├── TrackMetadata+AVFoundation.swift    # AVMetadataItem → TrackMetadata
│   ├── NavigationError+Conversion.swift   # Error → NavigationError conversion
│   │                                      # Shared by HybridAudioBrowser and CarPlayController
│   └── CxxVectorConformances.swift   # Nitro C++ vector type conformances
├── Option/
│   ├── PitchAlgorithms.swift         # AVAudioTimePitchAlgorithm mapping
│   ├── TimeEventFrequency.swift      # Event frequency enum
│   └── SessionCategories.swift       # Audio session categories
├── Util/
│   ├── LRUCache.swift                # Thread-safe LRU cache
│   │                                 # O(1) get/set with doubly-linked list
│   ├── MetadataAdapter.swift         # AVMetadataItem parsing
│   ├── Emitter.swift                 # Generic multi-listener event emitter
│   ├── OnceValue.swift               # One-time async initialization gate
│   ├── NetworkMonitor.swift          # NWPathMonitor wrapper
│   ├── SFSymbolRenderer.swift        # SF Symbol → file:// image rendering
│   └── SVGProcessor.swift            # SVG processing
├── Tests/
│   ├── SimpleRouterTests.swift       # Unit tests for route matching
│   ├── QueueManagerTests.swift       # Unit tests for queue management
│   ├── LoadSeekCoordinatorTests.swift # Unit tests for deferred seek logic
│   ├── MediaLoaderTests.swift        # Unit tests for media loading
│   └── Support/
│       └── TestTypes.swift           # Test support types
└── Support/
    └── Bridge.h                       # Objective-C bridge header
```

## Type Mapping

Nitro generates types that must be used instead of RNTP types:

| Nitro Type (use this)          | RNTP Type (removed)                            |
| ------------------------------ | ---------------------------------------------- |
| `Track`                        | `ios/Model/Track.swift`                        |
| `PlayingState`                 | `ios/Model/PlaybackPlayingState.swift`         |
| `RepeatMode`                   | `ios/Option/RepeatMode.swift`                  |
| `PlaybackProgressUpdatedEvent` | `ios/Event/PlaybackProgressUpdatedEvent.swift` |
| etc.                           | etc.                                           |

## Implementation Notes

### Track Type

The Nitro `Track` has:

- `url: String?` - Browsable path (for navigation)
- `src: String?` - Playback URL (for AVPlayer)
- `artwork: String?` - Artwork URL
- `artworkSource: ImageSource?` - Detailed artwork config

Use `track.src ?? track.url` for AVPlayer URL.

### Callbacks

HybridAudioBrowserSpec defines ~50 callbacks as properties:

```swift
// Browser callbacks (4)
var onPathChanged: (String) -> Void = { _ in }
var onContentChanged: (ResolvedTrack?) -> Void = { _ in }
var onTabsChanged: ([Track]) -> Void = { _ in }
var onNavigationError: (NavigationErrorEvent) -> Void = { _ in }

// Playback callbacks (14)
var onPlaybackProgressUpdated: (PlaybackProgressUpdatedEvent) -> Void = { _ in }
var onPlaybackActiveTrackChanged: (PlaybackActiveTrackChangedEvent) -> Void = { _ in }
var onPlaybackPlayingState: (PlayingState) -> Void = { _ in }
var onPlaybackError: (PlaybackErrorEvent) -> Void = { _ in }
var onPlaybackChanged: (Playback) -> Void = { _ in }
// ...etc

// Remote callbacks (16) - fire events to JS
var onRemotePlay: () -> Void = {}
var onRemotePause: () -> Void = {}
var onRemoteNext: () -> Void = {}
// ...etc

// Remote handlers (14) - optional JS overrides for default behavior
var handleRemotePlay: (() -> Void)?     // If set, overrides default
var handleRemotePause: (() -> Void)?
var handleRemoteNext: (() -> Void)?
// ...etc
```

Callbacks are set from:

- `TrackPlayerCallbacks` protocol methods (player events)
- `BrowserManager` callbacks (navigation events)
- `NetworkMonitor.onChanged` (connectivity)
- `SleepTimerManager.onChanged` (sleep timer)

### Promises

Async methods return `Promise<T>`:

```swift
func setupPlayer(options: PartialSetupPlayerOptions) throws -> Promise<Void>
```

Use Nitro's Promise helpers for async operations.

### Logging

**Always use `os.Logger` instead of `print()` for debugging.**

```swift
import os.log

class MyClass {
    private let logger = Logger(subsystem: "com.audiobrowser", category: "MyClass")

    func doSomething() {
        logger.debug("Debug message")
        logger.info("Info message")
        logger.error("Error message")
    }
}
```

- Use `logger.debug()` for development debugging
- Use `logger.info()` for general information
- Use `logger.error()` for errors
- See `HttpClient.swift` for a complete example
