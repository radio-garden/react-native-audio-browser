# Android Auto / AAOS browse artwork via a content:// provider

**Date:** 2026-06-23
**Issue:** [#63](https://github.com/radio-garden/react-native-audio-browser/issues/63) — browse-list artwork URIs bypass the session `BitmapLoader` on Android Auto/AAOS
**Status:** Design revised after two review rounds (4 reviewers: Media3 correctness, architecture, security/concurrency, real-world AAOS rendering); pending spec review

## Problem

On Android Auto / Android Automotive OS, browse-list artwork supplied as a
`MediaMetadata.artworkUri` bypasses the session `BitmapLoader`. Confirmed against
Media3 1.10.1 source:

- `MediaLibraryServiceLegacyStub` only loads a bitmap for a browse item when
  `MediaMetadata.artworkData` is set, via `decodeBitmap(bytes)` — it **never** calls
  `BitmapLoader.loadBitmap(uri)` on this path.
- `LegacyConversions.convertToMediaDescriptionCompat` sets `setIconBitmap` only when
  bytes were present, and always sets `setIconUri(metadata.artworkUri)`.

So for browse items with only an `artworkUri`, the **car process** fetches the URL
directly with its own image loader. Our `CoilBitmapLoader` — custom request headers,
SVG decoder, Android Auto size hint — is never consulted. Three failure modes:

1. **Headers dropped** — artwork URLs needing auth/signed/`User-Agent` headers fail.
2. **SVG unsupported** — the car can't decode SVG (today worked around by eagerly
   rasterizing SVGs to PNG bytes and embedding them via `artworkData`).
3. **`TransactionTooLargeException`** — `onLoadChildren` returns the whole list across a
   ~1 MB Binder transaction; embedding PNG bytes per row risks blowing the limit on
   large station lists.

Artwork renders correctly in-app and on the now-playing/notification surface (those use
the `BitmapLoader`). Only list surfaces delivered through the legacy browser bridge are
affected. **This includes the browse tree; whether it also includes the car's
queue/Up-Next list is an open question to confirm on a head unit — see "Queue list".**

## Decision

Build the standard AOSP/UAMP/Pocket-Casts pattern: a `ContentProvider` that serves
browse artwork over `content://`. Crucially (security, below), the provider serves
**only artwork the library pre-attributed at browse-build time** — it never fetches an
arbitrary caller-supplied URL. Browse `MediaItem`s carry a short `content://` URI
instead of bytes.

Default-on for browse items on Android (no config flag), general library primitive.
Fixes all three failure modes and retires the eager SVG-byte special case.

### Transport / permission model

Media3 1.10.1 does **not** grant the connected controller read permission on a
`content://` URI in browse metadata — verified: zero `grantUriPermission` /
`FLAG_GRANT_READ_URI_PERMISSION` in `libraries/session/`; the legacy bridge passes the
bare `iconUri` through. Android Auto / Assistant run in a **different uid**, so
`exported="false"` would `SecurityException` on their `openInputStream`.

The canonical fix (Google UAMP, Pocket Casts both verified) is **`android:exported="true"`**
with the provider protecting itself. Because the provider is reachable by any app on the
device, self-protection is **mandatory, not optional**:

- **No arbitrary-URL fetching.** The content URI carries an **opaque token**, not a URL.
  `openFile` looks the token up in an in-process `BrowseArtworkRegistry`; an unknown
  token → `null`. The provider therefore cannot be used as a fetch proxy / SSRF vector,
  cannot harvest the consumer's auth headers (see below), and cannot be steered to
  internal hosts or its own authority.
- This is required because the existing display-time resolution attaches the consumer's
  static request + artwork headers (auth, `User-Agent`) to **any** URI via
  `BrowserManager.unattributedArtworkSource` (`BrowserUrlResolution.kt:226-247`). Routing
  attacker input through that path would leak credentials. The token design means the
  provider never calls that path at all — it only serves entries the library itself
  registered.
- http(s)-only is still enforced as defense-in-depth on the registered `finalUrl`.

### Why a separate `BrowseArtworkRegistry` (not the existing `ArtworkResolutionRegistry`)

`ArtworkResolutionRegistry` (`ArtworkResolutionRegistry.kt`) maps display URI → `Track`

- Nitro config handles, is bounded to **256 entries** to avoid pinning dead JS closures,
  and re-resolves Track-first at display time. Reusing it for the provider has two
  problems: (a) the 256-entry LRU evicts rows in lists larger than 256 — exactly the
  large-list case this feature targets — so a legitimate fetch could miss; (b) it pins
  Nitro closures and re-runs `resolveDisplayArtwork`, which hops to `Dispatchers.Main`
  (`Player.kt:807`) — letting an exported provider schedule main-thread work.

Instead, `BrowseArtworkRegistry` stores **plain resolved data** — `token → { finalUrl,
headers, isSvg }` (an already-resolved `ImageSource` + svg flag, no Nitro handles, no
closures). It is populated at `toBrowseMediaItem` time (artwork is already resolved there
via `resolveArtworkUrl`, which yields the `finalUrl` + headers). Because it holds no
closures it can be sized generously / per-browse-session without the leak hazard, and the
provider resolves entirely off it — no `Player` deref, no Main hop, no closure
use-after-free. Cleared on browser-config replacement / content invalidation (same
triggers as `ArtworkResolutionRegistry.clear()`).

Rejected alternatives: embedding bytes for all rows (`TransactionTooLargeException`);
per-controller `grantUriPermission` lifecycle (more logic, no gain over exported +
token); opt-in flag (leaves the broken default for the uninformed).

### Cost of default-on (acknowledged)

For consumers whose browse art is plain header-less raster, this adds a per-icon
in-process round-trip. Accepted: stays within the app's own sandbox, fixes correctness
for those who need headers/SVG, and an opt-out flag would re-introduce broken-by-default.

## Architecture

The core insight: `CoilBitmapLoader.loadBitmap()` already turns a URI into a
correctly-decoded bitmap (headers + SVG decoder + size). We extract a shared core and
point two consumers at it: the now-playing `BitmapLoader`, and the new provider.

### Components (all in the library `android/`)

| Component                       | Kind     | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ------------------------------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CoilArtworkLoader`             | new      | `suspend fun load(source: ImageSource, sizeHintPixels: Int?, isSvg: Boolean): Bitmap` — the Coil request build (data + headers + decoder + **`.size(sizeHintPixels ?: 512)`**, which the current raster path does NOT do → real downsample, prevents decode OOM). SVG forced via the `isSvg` flag carried from build time (not re-derived from a possibly-suffixless transformed URL). Single source of truth for "resolved source → bitmap". |
| `CoilBitmapLoader`              | refactor | Thin `BitmapLoader`; `loadBitmap` resolves the URI (existing `displayArtworkSource`/`unattributedArtworkSource` path, unchanged for now-playing) then delegates decode to `CoilArtworkLoader`. `decodeBitmap` (embedded bytes) stays.                                                                                                                                                                                                         |
| `BrowseArtworkRegistry`         | new      | `@Synchronized` map `token → { finalUrl, headers, isSvg }`. Plain data, no Nitro handles. Generously bounded / cleared on config invalidation. The provider's only data source.                                                                                                                                                                                                                                                               |
| `ArtworkContentProvider`        | new      | Exported provider. `openFile` looks up the token → miss → `null`; hit (http/https only) → returns a `ParcelFileDescriptor` pipe **immediately**; a bounded writer coroutine resolves+decodes via `CoilArtworkLoader`, serves bytes, closes the FD in `finally`. `getType` → `image/png`. `query/insert/update/delete` → no-op (`null`/`0`). No `runBlocking`.                                                                                 |
| `ArtworkUris`                   | new      | Pure build/parse of `content://<pkg>.audiobrowser.artwork/art/<token>`. Opaque path segment (UAMP/Pocket-Casts style), not a query param.                                                                                                                                                                                                                                                                                                     |
| `CoilArtworkLoaderHolder`       | new      | Process-wide `@Volatile` holder for `CoilArtworkLoader` + `BrowseArtworkRegistry` (the provider may be constructed before the player). Identity-guarded clear; provider scope cancelled before `player.destroy()`.                                                                                                                                                                                                                            |
| `TrackFactory`                  | change   | New `toBrowseMediaItem(track, sizeHintPixels)`: **if `artwork` is http(s)** → resolve, register in `BrowseArtworkRegistry`, set `artworkUri = ArtworkUris.contentUri(token)`; **otherwise** (`android.resource://`, `file://`, …) → `setArtworkUri(rawUri)` unchanged (so vector/category icons survive). Existing `toMedia3(track)` (queue/now-playing) unchanged; `toMedia3WithSvgSupport` removed.                                         |
| `MediaSessionCallback`          | change   | The browse-delivery sites — `toMediaItems` (`onGetChildren`), `onGetItem`, `onGetSearchResult` — call `toBrowseMediaItem` with `player.artworkSizeHintPixels`. Queue/resumption/now-playing keep `toMedia3`.                                                                                                                                                                                                                                  |
| `SvgArtworkRenderer`            | change   | Retire `applyArtwork` + `renderSvgToBytes`; keep/relocate `isSvgUrl` (used at build time to set the `isSvg` flag).                                                                                                                                                                                                                                                                                                                            |
| `Service.kt` / setup            | change   | Populate the holder + registry on create; identity-guarded clear and **cancel the provider scope before** `player.destroy()` on destroy.                                                                                                                                                                                                                                                                                                      |
| `AndroidManifest.xml` (library) | change   | Declare `<provider exported="true">`.                                                                                                                                                                                                                                                                                                                                                                                                         |

### Concurrency & resource safety

- The provider owns a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)` with a
  **bounded `Semaphore`** (e.g. 4–6 concurrent) gating resolve+decode+encode, so a hostile
  caller spamming `openInputStream` (or a car opening 50 FDs at once) cannot exhaust
  threads / the OkHttp pool / memory with N simultaneous full bitmaps.
- The writer body is wrapped in `try { … } finally { out.close() }`, handling
  `CancellationException` so the FD is **always** closed (scope torn down, OOM, broken
  pipe) — never leaking an FD that blocks the car's read forever. The bitmap is released
  after `compress`.
- Raster decode is size-bounded (`.size()` above) → no full-resolution decode of a large
  image.

### Manifest

```xml
<provider
    android:name="com.audiobrowser.util.ArtworkContentProvider"
    android:authorities="${applicationId}.audiobrowser.artwork"
    android:exported="true" />
```

`${applicationId}` → unique authority per app, auto-merged (zero consumer config). The
provider must run in the **same process** as the media `Service` (the `@Volatile` holder
must be visible); consumers using a `:remote` service process are unsupported.
**Consumer-facing note:** this adds a new _exported_ component to every consuming app —
documented for store/privacy/pentest review; it is read-only, token-gated, and serves
only artwork the app itself produced.

## Data flow

1. A browse-delivery callback converts tracks via `toBrowseMediaItem`. For http(s)
   artwork: resolve → register `token → {finalUrl, headers, isSvg}` →
   `artworkUri = content://${appId}.audiobrowser.artwork/art/<token>`. For non-http(s)
   artwork: `setArtworkUri(rawUri)` unchanged.
2. Media3's legacy bridge sets that as the browser item's `iconUri`. The car opens it
   (exported provider, cross-uid OK).
3. `ArtworkContentProvider.openFile` looks up the token (miss → `null`), returns a pipe
   FD immediately; the bounded writer resolves `finalUrl` with the stored headers via
   `CoilArtworkLoader` (headers + SVG + size in _our_ process) → PNG → pipe → car.
4. **Cold start** (provider hit before holder/registry populated): return `null`; the car
   re-requests on the next browse refresh. In practice closed already — `onGetChildren`
   gates on `awaitBrowser()`, and artwork is only requested after a list is delivered, by
   which point setup ran. **No header-less fallback fetch.**

Kills all three failure modes; `MediaItem`s carry a tiny token URI, not bytes.

**Known minor inefficiency:** an in-process Media3 (non-legacy) browser controller would
round-trip `content://` back through Coil → our provider. Only external controllers
(Auto/Assistant/Bluetooth) browse on Android (the RN app renders its own browse UI in
JS), so this is negligible.

## Caching

The car re-requests the same `iconUri` often (scroll in/out, re-subscribe, and this
codebase fires `notifyChildrenChanged` on every network-state change,
`MediaSessionCallback.kt:68`). UAMP and Pocket Casts both serve a **disk-cache snapshot
file** rather than re-encoding per request (UAMP serves the cached file directly; Pocket
Casts opens an FD on Coil's `diskCache` snapshot). Per-request decode→re-encode causes
visible pop-in/flicker on a head unit, so this is **in scope, not optional**:

- For raster, serve Coil's disk-cache snapshot FD directly when the cached bytes are a
  servable image format (no decode/re-encode).
- For SVG (must rasterize) or when no servable snapshot exists, decode → encode once and
  cache the encoded bytes (small LRU keyed by `token+size`).

## Error handling

- Token miss / non-http(s) `finalUrl` / malformed URI → `openFile` returns `null` (car
  shows its placeholder; matches UAMP/Pocket-Casts).
- Resolve fails / null bitmap → close pipe with no data; Timber-logged; never throw across
  Binder.
- Broken pipe / cancellation → caught; FD closed in `finally`.

## Security

- `exported="true"` (required cross-uid). Self-protection: **token-gated** (no arbitrary
  URL), serving only library-registered artwork; http(s)-only on `finalUrl` as
  defense-in-depth; no local-file serving (no path-traversal surface). The provider never
  calls `unattributedArtworkSource`, so attacker input cannot harvest consumer headers or
  reach internal hosts.

## Testing

JUnit 4 + Mockito + kotlinx-coroutines-test + Robolectric 4.11.1 (`android/src/test`).

- **Unit (pure):** `ArtworkUris` token round-trip; `BrowseArtworkRegistry` register/lookup
  - eviction; `toBrowseMediaItem` routing (http(s) → content://; `android.resource://` →
    passthrough).
- **Robolectric:** `ArtworkContentProvider.openFile` — happy path (readable PNG FD), token
  miss → `null`, non-http → `null`, holder absent → `null`, with a fake `CoilArtworkLoader`
  via the holder. `@Before`/`@After` reset the process-wide holder/registry to avoid
  cross-test interference.
- **Manual on Android Auto DHU**, added to `manual-testing/`: (a) raster browse art
  renders, (b) SVG browse art renders, (c) 50+ list browses without
  `TransactionTooLargeException`, (d) header-requiring art renders, (e) a tab/folder with
  an `android.resource://` icon still shows its icon, (f) scroll a long list and back —
  no art flicker/reload, (g) inspect whether the queue/Up-Next list shows correct art for
  an SVG/header-requiring track (see below).

## Queue list (open question)

The car's queue/Up-Next list is rendered from session `MediaItem` metadata via the same
legacy path, and those items come from `toMedia3` (`MediaSessionCallback.kt:671,769,797`),
which this spec leaves unchanged. So queue-list artwork _may_ bypass Coil exactly as
browse did. This spec does not change the queue path; DHU case (g) determines whether a
follow-up is needed (apply `toBrowseMediaItem`-style treatment, or document why queue art
is exempt). The problem statement's "list surfaces" wording reflects this uncertainty.

## Implementation sequencing

One PR, ordered so artwork never regresses:

1. Add `CoilArtworkLoader` (+ refactor `CoilBitmapLoader`), `ArtworkUris`,
   `BrowseArtworkRegistry`, `CoilArtworkLoaderHolder`, `ArtworkContentProvider` (bounded
   scope, finally-close, snapshot serving), manifest entry, holder/registry population in
   `Service`.
2. Switch the three browse-delivery sites to `toBrowseMediaItem` (with scheme
   pass-through).
3. **DHU-verify** (a)–(g).
4. Only after (a)–(g) pass, remove `toMedia3WithSvgSupport` /
   `SvgArtworkRenderer.applyArtwork` / `renderSvgToBytes`.

## Out of scope

- TS / Nitro-spec changes — none.
- Consumer-app changes — none beyond the same-process constraint and the
  exported-component disclosure note.
- iOS / CarPlay — unaffected (resolves in-process already).
