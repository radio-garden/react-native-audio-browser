# Sonos UPnP as a second Android playback destination

**Status:** draft (awaiting review)
**Date:** 2026-06-25
**Branch:** `feature/sonos` (based on `feature/cast`)
**Scope:** Android only. iOS Sonos is out of scope for this spec.

## Goal

Let an Android listener move live-stream playback off the phone and onto a
**Sonos** speaker, with the phone acting as a remote control — the same
"mirrored playback destination" model `feature/cast` already implements for
Google Cast (see `docs/adr/0003-google-cast-is-a-mirrored-playback-destination.md`).

ADR 0003 explicitly deferred a destination-agnostic abstraction until "a second
real backend (Sonos) provides the data point." This spec is that second backend:
it generalizes the Cast seam into a backend-neutral destination core and adds a
hand-rolled UPnP/SSDP Sonos backend alongside Google Cast. Both coexist; only one
is active at a time.

## Why UPnP (not Bluetooth, not the Cast SDK)

- **Bluetooth is a route, not a destination, and most Sonos speakers lack it.**
  Over Bluetooth the phone still decodes the stream and ships raw audio bytes; it
  stays tethered, breaks when the app backgrounds/leaves, and is point-to-point.
  The classic Sonos line (One, Play:1/3/5, Five, Beam, Arc, Port, Amp, Sub) has
  **no Bluetooth audio at all** — only the portable/newer models (Roam, Move,
  Era) do. On Android, Bluetooth output is an OS concern with zero app code. It is
  the iOS Output/AirPlay *route* category, not a playback *destination*.
- **The Google Cast SDK speaks Chromecast, not Sonos.** It cannot discover or
  control a Sonos speaker.
- **UPnP makes Sonos a true destination.** Every Wi-Fi Sonos exposes the standard
  UPnP `AVTransport` + `RenderingControl` services. We hand the speaker a stream
  **URL**; it fetches and plays the bytes **itself**. The phone becomes a pure
  remote (can sleep, can leave). This is exactly the destination model the Cast
  branch already built.

## Domain model

Adopt the ADR-0003 vocabulary unchanged, generalized from "Cast device" to
**destination**:

- A **playback destination** moves *where audio plays* off the phone while the
  **Queue**, **Active Track**, and **Now Playing** keep their meaning. Distinct
  from **External surfaces** (audio stays local) and from the iOS **Output** route
  (the phone keeps fetching the bytes).
- **Destination backend** — a mechanism that discovers and drives destinations.
  Two backends: **Google Cast** (existing; Cast SDK + Media3 `CastPlayer`) and
  **Sonos** (new; UPnP/SSDP + a `SonosPlayer`).
- **One active session.** Only one destination plays at a time, because the player
  swap (`MediaSession.setPlayer`) is singular. Connecting to a destination ends any
  other active session first.

`CONTEXT.md` gains a "Sonos" sub-entry under "Playback destinations" (the
destination/backend terms already land there with the Cast work).

## Architecture

### What generalizes (shared, backend-neutral)

The Cast branch already isolated the reusable mechanism. We rename it to
destination-neutral terms (alpha → breaking renames are fine):

| feature/cast | feature/sonos (generalized) | Reuse |
| --- | --- | --- |
| `Player.startCasting(player, …)` / `stopCasting()` / `isLocal` / `activePlayer` / `castPlayer` | `Player.startRemotePlayback(player, …)` / `stopRemotePlayback()` / `isLocal` / `activePlayer` / `remotePlayer` | The swap already takes any Media3 `Player`. Sonos passes a `SonosPlayer`; Cast passes a `CastPlayer`. **Mechanism unchanged** — rename only. |
| `CastDiscoveryLeases` | `DiscoveryLeases` (main, pure) | Verbatim; both backends ref-count their scans through it. |
| `CastStateResolver` | `DestinationStateResolver` (main, pure) | Verbatim; maps (connected, connecting, hasDevices) → state. |
| `CastReSignBudget` | `DestinationReSignBudget` (main, pure) | Verbatim; bounds reactive re-sign of expiring signed URLs. |
| `CastState` (Nitro) | `DestinationState` (Nitro): `no-devices`/`not-connected`/`connecting`/`connected` | Same four values. |
| `onCastStateChanged` | `onDestinationStateChanged(event)` with `kind: 'googlecast' \| 'sonos'` + `deviceName` | One unified state stream for the UI's single "casting to X" indicator. |
| `target: 'local' \| 'cast'` media/artwork discriminator | `target: 'local' \| 'remote'` | The Sonos device fetches the URL itself — identical self-contained-URL constraint. (`'cast'` retained as an alias for back-compat is unnecessary in alpha; rename to `'remote'`.) |

The **single-active-session** invariant and the player swap live in a new
`DestinationCoordinator` (main) that owns both backends and multiplexes their
state into one `onDestinationStateChanged`.

### What stays backend-specific (the platform forces it)

Discovery and device selection genuinely differ and must **not** be forced into
one shape:

- **Google Cast** uses a closed system component (`MediaRouter` +
  `MediaRouteChooserDialog`). Selection is `showCastPicker()` — an opaque system
  dialog. We cannot inject Sonos devices into it.
- **Sonos** has no system picker. The library discovers devices over SSDP and
  **exposes the list to JS**; the app draws its own chooser and calls
  `connectSonosDevice(id)`. This matches the library's headless/imperative stance
  (no native views).

So the backends share *state, leases, re-sign, and the player swap*, but each
keeps its own *discovery + selection* surface.

### The Sonos backend (new)

All Sonos code lives in the **`main`** sourceset (package
`com.audiobrowser.destination.sonos`). Unlike Google Cast it pulls **no heavy
SDK** — only OkHttp (already a dependency) and Android's built-in XML pull
parser — so it needs **no build-time gating** (no `noSonos` sourceset). It is
runtime-inert until discovery is retained.

```
DestinationCoordinator (main)
 ├── CastBridge            (sourceset-gated: real when enableCast, else Noop)
 └── SonosBackend (main, always compiled)
      ├── SsdpDiscovery        — UDP M-SEARCH on 239.255.255.250:1900 +
      │                          MulticastLock; parses SSDP responses
      ├── SonosDeviceDescription — fetch + parse /xml/device_description.xml
      │                            (friendlyName, AVTransport + RenderingControl
      │                            controlURLs, UDN); Sonos filter on
      │                            manufacturer == "Sonos, Inc."
      ├── SoapClient           — build SOAP envelopes, POST via OkHttp, parse
      │                          responses (AVTransport + RenderingControl)
      ├── SonosPlayer          — Media3 SimpleBasePlayer mapping Player commands
      │                          to SOAP (SetAVTransportURI/Play/Pause/Stop,
      │                          Get/SetVolume, Get/SetMute); polls
      │                          GetTransportInfo/GetPositionInfo for state
      └── SonosSessionController — discovery leases, connect/disconnect, builds
                                   SonosPlayer, drives the Player swap, emits
                                   DestinationState, reactive re-sign on stale URL
```

#### Pure, unit-testable units (no Android/network)

The hard logic is extracted into pure functions/objects testable on the JVM:

- `SsdpMessages` — build the M-SEARCH datagram bytes; parse an SSDP response
  (headers → `LOCATION`, `USN`, `ST`).
- `DeviceDescriptionParser` — XML string → `SonosDevice(udn, friendlyName,
  avTransportControlUrl, renderingControlControlUrl, baseUrl)`. Resolves relative
  control URLs against the device base URL.
- `SoapEnvelopes` — build the exact SOAP body for each action
  (`SetAVTransportURI` with DIDL-Lite metadata, `Play`, `Pause`, `Stop`,
  `GetTransportInfo`, `SetVolume`, `GetVolume`, `SetMute`).
- `SoapResponseParser` — parse `GetTransportInfo` → transport state
  (`PLAYING`/`PAUSED_PLAYBACK`/`STOPPED`/`TRANSITIONING`/`NO_MEDIA_PRESENT`);
  `GetVolume` → int; SOAP fault → error.
- `TransportStateMapper` — UPnP transport state → Media3 `Player` state
  (`STATE_READY`/`STATE_BUFFERING`/`STATE_IDLE`) + `isPlaying`.
- `DidlLite` — build the `<DIDL-Lite>` metadata document for `SetAVTransportURI`
  from the Active Track (title/artist/artwork/streamType), and the
  `r:streamContent`/live hints Sonos honors.

The thin I/O glue (`SsdpDiscovery` sockets, `SoapClient` OkHttp calls,
`SonosPlayer` threading) stays as small as possible around these.

#### `SonosPlayer` (Media3 `SimpleBasePlayer`)

`SimpleBasePlayer` is Media3's base for exactly this case (a remote player you
drive by command + state). For the **live-only** scope we implement a minimal
surface:

- **State:** a single `MediaItemData` (the Active Track), `playbackState` from the
  polled UPnP transport state, `playWhenReady`, and `availableCommands` =
  `{PLAY_PAUSE, STOP, SET_VOLUME, GET_VOLUME, GET_CURRENT_MEDIA_ITEM,
  SET_MEDIA_ITEM}`. **No** seek/next/previous/duration (live streams; matches the
  Sonos live UI with no scrubber).
- **Commands:** `handleSetMediaItems` → `SetAVTransportURI` + `Play`;
  `handleSetPlayWhenReady(true/false)` → `Play`/`Pause`; `handleStop` → `Stop`;
  `handleSetDeviceVolume`/`handleSetVolume` → `SetVolume`/`SetMute`. Each returns a
  `Futures` that completes when the SOAP call returns; `invalidateState()` re-reads
  after a poll.
- **State polling:** a coroutine polls `GetTransportInfo` (and `GetVolume` once at
  connect) on a short interval while connected; each poll calls
  `invalidateState()`. Sonos also supports GENA `eventing` (SUBSCRIBE/NOTIFY) for
  push updates — **out of scope**; polling is simpler and sufficient for play
  state. Documented as a future optimization.

Because `SonosPlayer` is a real Media3 `Player`, the existing
`InterceptingPlayer` wrapping (remote-command interception, error masking),
`PlayerListener`, `NowPlayingUpdater` "follow the active player", and the
`MediaSession.setPlayer` swap all work **unchanged**.

#### Reactive re-sign (reused, simplified)

Sonos receives a single URL via `SetAVTransportURI`, not a mirrored queue. If a
signed live URL expires, Sonos reports `STOPPED`/error. On that transition the
session controller JIT re-resolves the Active Track's URL
(`BrowserManager.resolveMediaUrl(url, target=remote)`) and re-issues
`SetAVTransportURI` + `Play`, bounded by `DestinationReSignBudget` so a genuinely
dead stream surfaces a real error instead of looping. Same philosophy as
`CastReSign`, smaller surface.

### JS / Nitro API

Generalize the cross-backend concepts to **destination**; keep each backend's
discovery/selection surface where it diverges.

```ts
// Shared destination state (generalizes CastState)
export type DestinationState =
  | 'no-devices' | 'not-connected' | 'connecting' | 'connected'

export type DestinationKind = 'googlecast' | 'sonos'

export type DestinationChangedEvent = {
  state: DestinationState
  kind: DestinationKind | undefined   // which backend, when connected/connecting
  deviceName: string | undefined
}

export type SonosDevice = { id: string; name: string }   // id = UDN

// inside interface AudioBrowser
// --- shared ---
getDestinationState(): DestinationState
getDestinationKind(): DestinationKind | undefined
getDestinationDeviceName(): string | undefined
isCasting(): boolean                         // convenience: on any remote destination
endDestinationSession(): void                // disconnect → hand back to phone
retainDestinationDiscovery(): void           // ref-counted; scans BOTH backends
releaseDestinationDiscovery(): void
onDestinationStateChanged: (e: DestinationChangedEvent) => void
// --- Google Cast (unchanged mechanism) ---
configureCast(config: CastConfig): void
showCastPicker(): void
// --- Sonos (app draws its own chooser) ---
getSonosDevices(): SonosDevice[]             // snapshot of currently-discovered
connectSonosDevice(deviceId: string): void   // connect (ends any other session)
onSonosDevicesChanged: (devices: SonosDevice[]) => void
```

The JS feature is split: `src/features/destination.ts` exports the shared
surface (`getDestinationState`, `onDestinationStateChanged`,
`endDestinationSession`, `retain/releaseDestinationDiscovery`, and the hooks
`useDestinationState`, `useDestinationDeviceName`, `useIsCasting`);
`src/features/cast.ts` keeps the Cast-only `configureCast`/`showCastPicker`; a new
`src/features/sonos.ts` exports `getSonosDevices`, `connectSonosDevice`,
`onSonosDevicesChanged`, and `useSonosDevices()` (ref-counts discovery while
mounted, like `useCastState`).
Web stubs degrade exactly like the Cast stubs: `no-devices`, empty device list,
no-op connect.

### Build

- **Sonos:** no Gradle flag, no sourceset, no new dependency. Lives in `main`,
  always compiled, runtime-inert until `retainDestinationDiscovery()` /
  `connectSonosDevice()`.
- **Google Cast:** unchanged — still gated by `AudioBrowser_enableCast`
  (cast/noCast sourcesets, Cast SDK deps). The generalized neutral seam and the
  `DestinationCoordinator` live in `main` and reference `CastBridgeProvider.create`
  exactly as today (resolves to real or Noop).
- **Manifest/permissions (app-level, documented):** SSDP needs
  `CHANGE_WIFI_MULTICAST_STATE` (for `MulticastLock`) and `INTERNET` (already
  present). No special runtime permission prompt. On API 33+ no extra local-network
  permission is required for outbound multicast (unlike iOS).

## Error handling

- **No devices found:** state stays `no-devices`; not an error.
- **Device fetch / SOAP failure on connect:** abort the connect, return to
  `not-connected` (or `no-devices`), emit a destination error via the existing
  error channel; do **not** swap the player.
- **Stale URL mid-session:** bounded reactive re-sign (above); on exhaustion,
  surface a real player error through the swapped `SonosPlayer` →
  `PlaybackStateMachine`, same path as local errors.
- **Device disappears (powered off):** SSDP `byebye` or poll failure → end the
  session, hand back to local (paused, matching the unplug convention).
- **Network loss:** poll failures escalate to end-session; reconnect is manual
  (re-discover, re-connect). Documented.

## Testing strategy

**No real Sonos hardware is available in this environment.** Verification is
therefore split:

1. **JVM unit tests (real coverage, run in CI via `testDebugUnitTest`):**
   - `SsdpMessages`: M-SEARCH bytes exact; parse real captured SSDP response
     fixtures (Sonos `ST`/`USN`/`LOCATION`).
   - `DeviceDescriptionParser`: parse a captured Sonos `device_description.xml`
     fixture → correct control URLs (absolute + relative resolution); reject
     non-Sonos descriptions.
   - `SoapEnvelopes`: byte-exact SOAP bodies for every action incl. DIDL-Lite
     escaping.
   - `SoapResponseParser` / `TransportStateMapper`: every transport state +
     SOAP-fault fixture.
   - Generalized pure helpers: `DiscoveryLeases`, `DestinationStateResolver`,
     `DestinationReSignBudget` (port the existing Cast tests).
   - `DestinationCoordinator`: single-active-session invariant (connect Sonos
     while Cast "connected" ends Cast first), state multiplexing.
2. **Mock HTTP round-trip:** a local `MockWebServer` (OkHttp) standing in for a
   Sonos control endpoint to exercise `SoapClient` request/response wiring and
   `SonosPlayer` command→SOAP mapping without a device.
3. **Manual hardware checklist (documented in the guide):** discover → connect →
   play/pause/stop → volume → app-background continuity → device-off handling →
   multi-hour stale-URL re-sign. Cannot be automated here; shipped as a checklist.

The build gate is: `compileDebugKotlin` + `testDebugUnitTest` + `yarn test` +
`tsc` all green. Platform/device builds are **not** run locally (per the repo's
existing convention).

## Out of scope

- **iOS Sonos** (separate spec; iOS has no swappable player, like the Cast iOS
  path).
- **Multi-room / grouping / zone topology** (Sonos-proprietary, large surface).
- **Seek / queue / duration on the device** (live-only; SonosPlayer is
  single-item, no-seek).
- **GENA push eventing** (polling is sufficient; noted as a future optimization).
- **A native cast/Sonos button view** (library is headless; app draws its own).

## Risks / open questions (verify on hardware)

1. Sonos honoring `SetAVTransportURI` for an arbitrary HTTPS live stream + the
   exact DIDL-Lite shape it wants for live (some Sonos firmware is picky about
   `streamContent` / protocolInfo).
2. `MulticastLock` + Android Wi-Fi multicast reliability across OEMs; SSDP retry
   count / timeout tuning.
3. Poll interval vs responsiveness vs Sonos request-rate tolerance.
4. RG signed stream URLs actually fetchable by the Sonos device (egress, TTL).
5. `SimpleBasePlayer` state-invalidation cadence interacting with
   `PlayerListener`/`NowPlayingUpdater` across the swap (same risk class as Cast's
   `setPlayer` swap).
6. Single-active-session hand-off correctness when switching Cast → Sonos.

## File-by-file change list

**Generalize (rename, main):**
- `player/Player.kt` — cast-swap seam → destination-neutral
  (`startRemotePlayback`/`stopRemotePlayback`/`remotePlayer`).
- `cast/CastDiscoveryLeases.kt` → `destination/DiscoveryLeases.kt`
- `cast/CastStateResolver.kt` → `destination/DestinationStateResolver.kt`
- `cast/CastReSignBudget.kt` → `destination/DestinationReSignBudget.kt`
- `cast/CastBridge.kt` → keep as the Cast backend's interface, implementing a new
  neutral `destination/DestinationBackend` for the shared ops.
- `Callbacks.kt` — `onCastStateChanged` → `onDestinationStateChanged` (+ new
  `onSonosDevicesChanged`).
- Nitro spec + `src/features/*` + web stubs — generalized destination API + Sonos
  surface; `target: 'cast'` → `'remote'`.

**New (main, `destination/` + `destination/sonos/`):**
- `destination/DestinationCoordinator.kt`
- `destination/sonos/SsdpDiscovery.kt`, `SsdpMessages.kt`
- `destination/sonos/DeviceDescriptionParser.kt`, `SonosDevice.kt`
- `destination/sonos/SoapClient.kt`, `SoapEnvelopes.kt`, `SoapResponseParser.kt`
- `destination/sonos/DidlLite.kt`, `TransportStateMapper.kt`
- `destination/sonos/SonosPlayer.kt`
- `destination/sonos/SonosSessionController.kt`, `SonosBackend.kt`

**New tests (`android/src/test/.../destination/`):** one per pure unit above +
coordinator + a `MockWebServer` SonosPlayer round-trip; fixtures under
`android/src/test/resources/sonos/`.

**Docs:** `website/guide/` destination/Sonos guide; cross-link `audio-output.md`;
README bullet; a new **ADR 0004** recording the Sonos backend and the
destination-seam extraction (ADRs are immutable records, so ADR 0003 is
referenced, not edited).
