# Google Cast — implementation plan

Design rationale: [`docs/adr/0003-google-cast-is-a-mirrored-playback-destination.md`](docs/adr/0003-google-cast-is-a-mirrored-playback-destination.md).
Glossary: `CONTEXT.md` → "Playback destinations".

Cast is **compiled in by default** and **inert at runtime** until `configureCast()` is called (the runtime gate is the real safety). Size-sensitive apps can **opt out** at build time (Android `AudioBrowser_enableCast=false`; iOS `AUDIOBROWSER_DISABLE_CAST=1 pod install`) to link no Cast SDK. The gating machinery below is unchanged — only the default flipped (see ADR 0003 Update).

---

## Layer 0 — Cross-platform contract (shared)

### Nitro spec (`src/specs/audio-browser.nitro.ts`)

Add `CastState` + Cast options type and append Cast members to the `AudioBrowser` interface (same shape as the `Ios*` output members):

```ts
export type CastState =
  | 'no-devices'      // none discovered on the network
  | 'not-connected'   // devices available, idle
  | 'connecting'
  | 'connected'       // audio is on the Cast device

export type CastConfig = {
  /** Cast receiver application id. Omit for Google's Default Media Receiver. */
  receiverApplicationId?: string
}

// inside interface AudioBrowser { ... }  // MARK: cast
configureCast(config: CastConfig): void   // idempotent; first call inits the SDK + discovery wiring
getCastState(): CastState                  // 'no-devices' on a non-Cast build / before configure
getCastDeviceName(): string | undefined
isCasting(): boolean
showCastPicker(): void                     // present system chooser; no-op if not configured
endCastSession(): void                     // disconnect → hand back to local
onCastStateChanged: (event: CastStateChangedEvent) => void
```

```ts
export type CastStateChangedEvent = { state: CastState; deviceName: string | undefined }
```

### `target` discriminator (`src/types/browser.ts`)

Add to `MediaTransformParams` (used by both media and artwork transforms):

```ts
export interface MediaTransformParams {
  request: RequestConfig
  context?: ImageContext
  /** Resolution destination. 'cast' URLs are fetched by the Cast device itself,
   *  so they must be self-contained (query-signed) — request headers do not cross. */
  target: 'local' | 'cast'
}
```

Native media/artwork resolution passes `target: 'cast'` when resolving for the receiver, `'local'` otherwise (default).

### TS feature (`src/features/cast.ts`) — model on `src/features/output.ts`

Flat exports: `configureCast`, `getCastState`, `getCastDeviceName`, `isCasting`, `showCastPicker`, `endCastSession`, `onCastStateChanged` (via `NativeUpdatedValue.emitterize`), `useCastState`, `useCastDeviceName`, `useIsCasting` (via `useNativeUpdatedValue`). Re-export `CastState`, `CastConfig`, `CastStateChangedEvent`. Register in `src/features/index.ts` and `src/index.ts`.

### Web no-op stub (`src/web/…` + `src/native.web.ts`)

`getCastState()` → `'no-devices'`; `getCastDeviceName/isCasting` → `undefined`/`false`; `configureCast/showCastPicker/endCastSession` → no-ops; `onCastStateChanged` never fires. Mirror how the `Ios*` output API degrades.

### Codegen

After the spec change, regenerate with `yarn codegen` (Nitrogen). **Not** a platform build — safe to run. Native protocols/interfaces for the new members appear under `nitrogen/generated/`.

---

## Layer 1 — Android (`android/`)

### Build gating

- `gradle.properties`: `AudioBrowser_enableCast=true` (default; the build.gradle fallback is also `true`). Opt out with `false`.
- `build.gradle`: when enabled, add a `cast` sourceset (`android/src/cast/java/com/audiobrowser/cast/…`) to `main`, set a `buildConfigField "boolean", "ENABLE_CAST"`, and add deps:
  ```gradle
  implementation "androidx.media3:media3-cast:${media3Version}"
  implementation "com.google.android.gms:play-services-cast-framework:21.5.0"
  ```
- When disabled, none of the above compiles; core reaches Cast through a `CastBridge` interface with an inert default impl (`NoopCastBridge`) selected via `BuildConfig.ENABLE_CAST`.

### New files (cast sourceset)

- `cast/AudioBrowserCastOptionsProvider.kt` — `OptionsProvider`. Reads receiver app id from a static `CastConfigHolder` (set by `configureCast()` before `CastContext.getSharedInstance`); default = `CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`. Empty `getAdditionalSessionProviders`.
- `cast/CastConfigHolder.kt` — process-static receiver app id + configured flag.
- `cast/CastSessionController.kt` — owns `SessionManagerListener`; on `onSessionStarted/Resumed` build `CastPlayer`, transfer queue+position from the active local player, `mediaSession.setPlayer(InterceptingPlayer(castPlayer, …))`; on `onSessionEnded` transfer back and `setPlayer(localInterceptingPlayer)`. Emits `CastStateChangedEvent` to JS via `Callbacks`. Owns discovery ref-count (`MediaRouter.addCallback` with `CALLBACK_FLAG_PERFORM_ACTIVE_SCAN` while subscribers > 0).
- `cast/CastMediaItemConverter.kt` — `MediaItemConverter` (Media3). Maps our `MediaItem` → `MediaQueueItem`/`MediaInfo`: media URL (already `target:'cast'`-resolved), title/subtitle/artwork URL into `MediaMetadata`, and our **stable Track identity into `customData`** (JSON). Reverse mapping for rehydration.
- `cast/CastReSign.kt` — bounded reactive re-sign: on a Cast load error attributable to a stale URL, JIT re-resolve that item (via `BrowserManager.resolveMediaUrl` with `target=cast`) and `RemoteMediaClient.queueUpdateItems`; cap attempts per item (mirror `StuckRecoveryPolicy` philosophy) then surface a real error.
- `consumer-rules.pro` (+ reference from `build.gradle` `consumerProguardFiles`) — keep `AudioBrowserCastOptionsProvider` (reflectively instantiated).
- `cast/AndroidManifest.xml` (cast sourceset) — `<meta-data android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME" android:value="com.audiobrowser.cast.AudioBrowserCastOptionsProvider"/>` (merged only when the sourceset is active).

### Edits to existing files

- `player/Player.kt` — introduce `activePlayer` (local ExoPlayer | CastPlayer); route transport/queue/position through it; expose accessor for `NowPlayingUpdater`/timers; gate local-only subsystems (buffer/EQ/stuck/transform) on `isLocal`. Provide queue+position transfer helpers used by `CastSessionController`. Route volume to the Cast device while casting.
- `player/PlayerListener.kt` — attach/detach to the active player on swap; feed the same `PlaybackStateMachine` from Cast player events.
- `Callbacks.kt` — add `onCastStateChanged`.
- `AudioBrowser.kt` — implement spec methods: `configureCast` (set holder → `CastContext.getSharedInstance` → wire `CastSessionController`), `getCastState`, `getCastDeviceName`, `isCasting`, `showCastPicker` (`MediaRouteChooserDialog`), `endCastSession`. Delegate to `CastBridge`. Wire discovery ref-count to `onCastStateChanged` subscription.
- `AudioBrowser.onServiceConnected` (NOT `Service.kt`) — hands `CastSessionController` the `MediaLibrarySession` + local `InterceptingPlayer` (via `attachCastBridge` → `player.sessionOrNull`) so it can repoint on connect/disconnect. `Service.kt` itself has no Cast references.
- `player/EqualizerController.kt` — no-op while casting.

---

## Layer 2 — iOS (`ios/`)

### Build gating

- `AudioBrowser.podspec` — add the Cast pod **by default** (`s.dependency 'google-cast-sdk'`) and define the `AUDIOBROWSER_ENABLE_CAST` Swift active compilation condition, UNLESS opted out via `AUDIOBROWSER_DISABLE_CAST=1 pod install`. All Cast Swift code is wrapped in `#if AUDIOBROWSER_ENABLE_CAST` (compiles to inert `#else` no-ops when opted out).
- Document required app Info.plist: `NSLocalNetworkUsageDescription`, `NSBonjourServices` (`_googlecast._tcp` + `_<receiverAppId>._googlecast._tcp`), and that the iOS 14+ local-network prompt appears on first discovery.

### New files

- `ios/Cast/CastSessionManager.swift` — `GCKSessionManagerListener` + `GCKCastContext` init from `configureCast()` (default discovery criteria = Default Media Receiver when id omitted). On session start/resume: suspend local `AVPlayer`, push the mirrored queue via `GCKRemoteMediaClient.queueLoad`, route transport to the remote client; on end: hand back to `PlaybackCoordinator`. Owns discovery start/stop ref-counted to subscribers. Emits `CastStateChangedEvent`.
- `ios/Cast/CastMediaItemConverter.swift` — Track → `GCKMediaQueueItem`/`GCKMediaInformation` (media URL, metadata, artwork URL, stable Track identity in `customData`); reverse for rehydration.
- `ios/Cast/CastReSign.swift` — bounded reactive re-sign on stale-URL remote load error via `queueUpdateItems`.
- `ios/Cast/CastState.swift` — map `GCKCastState`/connection state → our `CastState`.

### Edits to existing files

- `ios/Player/PlaybackCoordinator.swift` — `isRemote` mode: while casting, suspend local playback and forward transport/queue/seek to `CastSessionManager`; surface position/state from remote-client callbacks into the same `PlaybackStateMachine`. Volume → Cast device.
- `ios/HybridAudioBrowser.swift` — implement spec methods (`configureCast`, `getCastState`, `getCastDeviceName`, `isCasting`, `showCastPicker` via `GCKCastContext.sharedInstance().presentCastDialog()`, `endCastSession`), all under `#if AUDIOBROWSER_ENABLE_CAST` with inert `#else` no-ops returning the disabled defaults.
- `ios/TrackPlayerCallbacks.swift` (or equivalent) — `onCastStateChanged`.
- Media/artwork resolve paths — pass `target: .cast` when resolving for the receiver.

---

## Layer 3 — Docs

- `website/guide/cast.md` — new guide: opt-in setup (Gradle flag, iOS pod env + Info.plist), `configureCast`, state/hooks, `showCastPicker`, drawing your own button, the `target: 'cast'` transform branch (header-vs-URL auth), and the documented limitations (best-effort live metadata, no artwork re-sign, current-item-first load, full-queue rehydration on relaunch).
- `website/guide/audio-output.md` — cross-link: Cast (a destination) is **not** the iOS Output route; point to the Cast guide.
- `README.md` — feature bullet + opt-in callout.
- API reference picks up `src/features/cast.ts` exports via TypeDoc automatically.

---

## Discovery lifecycle (revised after review)

`emitterize` installs the native `onCastStateChanged` callback once at module load and never signals unsubscribe, so discovery cannot be ref-counted off the event subscription. Instead the spec exposes `retainCastDiscovery()` / `releaseCastDiscovery()`; the `useCastState` / `useCastDeviceName` / `useIsCasting` hooks ref-count them across mounts (one native retain for N mounted hooks). Active scanning runs only while a Cast hook is mounted (or a manual `retainCastDiscovery()` is held).

## Status (landed, pending morning build verification)

- **Layer 0 (contract):** done — spec + `src/features/cast.ts` + web stubs + `MediaResolveTarget`/`target`; `yarn codegen` run clean; `tsc` green.
- **Layer 1 (Android):** implemented + reviewed; review-fix pass in progress (media `target=cast` threading, `CastBridge.release()` on dispose, real `retain/releaseCastDiscovery`, listener attach symmetry, Activity-based `showCastPicker`, re-sign race guard).
- **Layer 2 (iOS):** implemented + reviewed; review-fix pass in progress (receiver→coordinator queue resync, media `target=.cast`, real `retain/releaseCastDiscovery`, Cast-aware `duration`, re-sign target item, configure-id warning).
- **Layer 3 (docs):** done — `website/guide/cast.md`, audio-output cross-link, README bullet/callout, sidebar entry.
- **Reviews:** thermonuclear adversarial review run per platform; both confirmed the **default (Cast-disabled) build is safe**. Fixes routed back to implementers.

## Build / CI

- Add a Cast-enabled variant to `.github/workflows/android-build.yml` and `ios-build.yml` to keep the gated sourceset/podspec green.
- **Do not run platform builds locally** — verified in the morning.

## Open risks / to verify on device

1. `MediaSession.setPlayer()` swap preserving notification + connected-controller continuity across connect/disconnect.
2. Reactive re-sign loop bound correctly distinguishes "stale URL" from "dead stream" (no infinite reload).
3. RG signed stream + artwork URLs are actually fetchable by the Cast device (egress / CORS / token TTL) under `target: 'cast'`.
4. iOS live-metadata in-place update fidelity (best-effort) without a stream reload.
5. Full-queue rehydration round-trip through `customData` re-resolves Tracks correctly via `BrowserManager`.
