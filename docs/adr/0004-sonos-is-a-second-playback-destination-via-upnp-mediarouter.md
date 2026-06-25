# Sonos is a second playback destination, via hand-rolled UPnP behind a MediaRouteProvider

**Status:** accepted
**Date:** 2026-06-25
**Relates to:** [ADR 0003](0003-google-cast-is-a-mirrored-playback-destination.md)
**Scope:** Android only.

ADR 0003 modelled Google Cast as a **playback destination** (audio moves off the
phone; the Queue / Active Track / Now Playing keep their meaning) and explicitly
**deferred** a destination-agnostic abstraction until "a second real backend
(Sonos) provides the data point." This ADR records that second backend.

Sonos is added as a second destination **backend** alongside Google Cast. It is
**Android-only** (iOS is out of scope, as on iOS there is no swappable player —
the same asymmetry ADR 0003 notes for Cast). It is **live-only**: a single live
stream, no seek/queue/duration on the speaker.

## Decisions

### Sonos is a custom AndroidX `MediaRouteProvider`, not a bespoke JS API

The Cast SDK is, under the hood, an AndroidX `MediaRouteProvider`, and
`showCastPicker()` simply opens a `MediaRouteChooserDialog` over the registered
providers. Sonos follows the identical pattern: a `SonosMediaRouteProvider`
discovers speakers over SSDP and publishes each as a `MediaRoute`. Consequences:

- **No Sonos-specific JS API.** Sonos speakers appear in the **same** chooser as
  Cast (and Bluetooth). The app uses the existing destination surface
  (`showCastPicker`, `useCastState`, `isCasting`, `getCastDeviceName`,
  `onCastStateChanged`, `endCastSession`, `retainCastDiscovery`). This is also
  more consistent with the library's headless stance (the app draws no device
  list; it opens a system chooser).
- **One discovery, one selection path.** A `DestinationCoordinator` fronts the
  Cast bridge and the Sonos backend behind that single surface, multiplexing
  state (`CONNECTED > CONNECTING > NOT_CONNECTED > NO_DEVICES`), driving discovery
  on both, and presenting one chooser whose selector is the union of both
  backends' route categories. MediaRouter enforces a single selected route across
  providers, so only one destination is ever connected.

*Considered and rejected:* a dedicated `getSonosDevices()` / `connectSonosDevice()`
JS API with an app-drawn chooser. It duplicates what MediaRouter already does,
leaks a device list into app UI, and diverges from how Cast already works.

### The player swap is reused unchanged

A `SonosPlayer` is a Media3 `SimpleBasePlayer` that maps player commands to UPnP
SOAP and reflects polled transport state back as Media3 state. Because it is a
real `Player`, the existing `Player.startCasting`/`stopCasting` swap,
`InterceptingPlayer`, `PlayerListener`, and `NowPlayingUpdater` work on it
unchanged — exactly as for the Cast `CastPlayer`. This is the seam ADR 0003 built;
Sonos validates it as backend-agnostic.

### UPnP is hand-rolled (no jUPnP/Cling)

The control surface is small — SSDP `M-SEARCH`, device-description XML, and a
handful of `AVTransport`/`RenderingControl` SOAP actions — so it is implemented
directly over OkHttp + DOM, with the protocol logic factored into pure,
JVM-unit-tested units (SSDP build/parse, device-description parse, DIDL-Lite,
SOAP envelopes/parse, transport-state mapping, URL rewrite). The only added
dependency is the lightweight standard `androidx.mediarouter` (no Play Services),
in the `main` sourceset, so Sonos works even in Cast-opt-out builds. A full UPnP
stack (jUPnP) would add a large dependency, its own threading/registry model, and
LGPL licensing for no benefit at this surface.

### Raw MP3/ICY radio is rewritten to `x-rincon-mp3radio://`

Sonos starts a raw continuous MP3/ICY radio stream reliably only under the
`x-rincon-mp3radio://` scheme (the long-standing approach in node-sonos/SoCo).
The library rewrites such URLs at `SetAVTransportURI` time; HLS/DASH/AAC/FLAC are
left as plain http(s). As with Cast, the speaker fetches the media URL **itself**,
so URLs must be self-contained (query-signed) — Sonos reuses the same media/
artwork transform `target` discriminator (`target:'cast'`).

### State push (polling) over GENA eventing

Transport state is read by polling `GetTransportInfo` on a short interval while
connected, not via UPnP GENA SUBSCRIBE/NOTIFY. Polling is far simpler and
sufficient for play/pause state on a single live stream. GENA eventing is a
possible future optimisation.

### The public JS API keeps its `Cast*` names (rename deferred)

The cross-backend destination surface is exposed through the **existing** `Cast*`
JS API rather than renamed to `Destination*`/`showOutputPicker`. The Nitro spec
is shared across platforms; renaming it would require touching the iOS Swift
implementation, which cannot be compiled or verified in this Android-only change.
The existing API already functions as the destination API once Sonos rides
MediaRouter, so the rename is **cosmetic** and is deferred to a dedicated
cross-platform pass. On Android the `Cast*` names now cover both Chromecast and
Sonos; this is documented in the Sonos guide.

## Consequences

- **The default (Cast-opt-out) build gains working Sonos** at the cost of the
  small `androidx.mediarouter` dependency; no Cast SDK is pulled in. The Cast
  build is unchanged.
- **The app must declare `CHANGE_WIFI_MULTICAST_STATE`** (for the SSDP multicast
  lock); `INTERNET` is already present. Documented, not abstracted — like the
  Cast Info.plist/consumer-rules requirements in ADR 0003.
- **No automated hardware coverage.** The protocol layer is fully JVM-unit-tested
  (SSDP/SOAP/DIDL/device-XML/transport-map, ~58 tests, plus a MockWebServer
  round-trip), but discovery I/O, the route provider, and end-to-end playback are
  verified only by a documented manual hardware checklist in the Sonos guide.
- **A pre-existing defect was fixed as a prerequisite:** `feature/cast` did not
  compile (three Kotlin errors in `CastReSign`/`CastMediaItemConverter`); these
  were repaired to establish a green baseline.

## Verify on hardware (see the Sonos guide checklist)

Discovery reliability + multicast lock across OEMs; Sonos accepting
`SetAVTransportURI` for arbitrary live streams + the DIDL-Lite shape; the
`x-rincon-mp3radio://` rewrite heuristic; signed-URL fetchability + multi-hour
refresh; the `setPlayer` swap continuity Cast→Sonos; and whether Sonos routes
also surface in the system Output Switcher (MediaRouter2 bridge).
