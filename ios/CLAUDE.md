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

TP["TrackPlayer<br/>Core AVPlayer Logic<br/>Queue Management<br/>LoadSeekCoordinator"]

subgraph Browser["Browser System"]
  BM["BrowserManager<br/>@MainActor<br/>Navigation, Routing, Caching<br/>URL Resolution"]
  SR["SimpleRouter<br/>Route Pattern Matching<br/>Parameter Extraction"]
  BPH["BrowserPathHelper<br/>Contextual URLs<br/>Path Utilities"]
  BC["BrowserConfig<br/>Configuration Wrapper"]
  HC["HttpClient<br/>URLSession Wrapper"]
  LRU["LRUCache<br/>Track and Content Cache<br/>Thread-safe"]
end

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

subgraph CarPlay["CarPlay"]
  CPC["CarPlayController<br/>@MainActor @objc<br/>Tab Bar, List, Now Playing"]
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
HAB -->|Uses| NM
HAB -->|Uses| EM
HAB -.->|Implements| TPC

BM --> SR
BM --> BPH
BM --> BC
BM --> HC
BM -->|"trackCache 3000, contentCache 20"| LRU
BM --> SFR

TP -->|Controls| AVP
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

PSO -->|KVO| AVP
PTO -->|addPeriodicTimeObserver| AVP
PINO -->|NotificationCenter| AVP
PIPO -->|KVO| AVP

RCC -->|addTarget| MPRC
NPIC -->|nowPlayingInfo| MPNP
TP -->|setCategory| AS
RM -->|Monitors| NM

CPC -->|"via HAB.browserManager"| BM
CPC -->|"via HAB.getPlayer()"| TP
CPC -->|Templates| CPT
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

class HAB nitro
class TP core
class BM,SR,BPH,BC,HC,LRU browser
class PSO,PTO,PINO,PIPO observer
class RCC,NPIC controller
class AVP,MPRC,AS,MPNP,CPT,JS platform
class PSM,PPUM,STM,RM state
class NM,EM,OV,SFR util
class TPC protocol
class CPC,MIH carplay
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
3. **Browser → Player**: `navigateTrack()` can expand contextual URLs and load queue
4. **Player → Controllers**: TrackPlayer owns RemoteCommandController and NowPlayingInfoController
5. **Platform → Observers**: KVO and NotificationCenter feed back to TrackPlayer

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
│                                     # Queue management, media URL resolution
│                                     # Owns all observers, state managers, controllers
│                                     # Nested LoadSeekCoordinator for deferred seeks
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
│   │                                 # CPTabBarTemplate, CPListTemplate, Now Playing
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
│   └── PlayerUpdateOptions.swift     # Update options struct
├── Extension/
│   ├── Track+AVPlayer.swift          # Track artwork loading
│   ├── Track+Copying.swift           # Track.copying() for selective field updates
│   ├── Capability+RemoteCommand.swift # Capability to RemoteCommand conversion
│   ├── ChapterMetadata+AVFoundation.swift  # AVTimedMetadataGroup → ChapterMetadata
│   ├── TimedMetadata+AVFoundation.swift    # AVTimedMetadataGroup → TimedMetadata
│   ├── TrackMetadata+AVFoundation.swift    # AVMetadataItem → TrackMetadata
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
│   └── SimpleRouterTests.swift       # Unit tests for route matching
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
