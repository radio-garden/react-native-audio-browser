# Android Auto / AAOS browse artwork via a content:// provider

**Date:** 2026-06-23
**Issue:** [#63](https://github.com/radio-garden/react-native-audio-browser/issues/63) — browse-list artwork URIs bypass the session `BitmapLoader` on Android Auto/AAOS
**Status:** Design revised after two-reviewer pass (Android/Media3 correctness + architecture); pending spec review

## Problem

On Android Auto / Android Automotive OS, browse-list artwork supplied as a
`MediaMetadata.artworkUri` bypasses the session `BitmapLoader`. Confirmed against
Media3 1.10.1 source:

- `MediaLibraryServiceLegacyStub` only loads a bitmap for a browse item when
  `MediaMetadata.artworkData` is set, and it does so via `decodeBitmap(bytes)` —
  it **never** calls the URI-fetching `BitmapLoader.loadBitmap(uri)` on this path.
- `LegacyConversions.convertToMediaDescriptionCompat` sets `setIconBitmap` only
  when bytes were present, and always sets `setIconUri(metadata.artworkUri)`.

So for browse items with only an `artworkUri`, the **car process** fetches the URL
directly, with its own image loader. Our `CoilBitmapLoader` — which applies custom
request headers, the SVG decoder, and the Android Auto size hint — is never
consulted for browse-list artwork. Three failure modes follow:

1. **Headers dropped** — artwork URLs requiring auth/signed/`User-Agent` headers fail.
2. **SVG unsupported** — the car can't decode SVG; today we work around this by
   eagerly rasterizing SVGs to PNG bytes and embedding them (`artworkData`).
3. **`TransactionTooLargeException`** — `onLoadChildren` returns the whole list across
   a ~1 MB Binder transaction. Embedding PNG bytes per row (the current SVG workaround,
   and the naive fix for #1) risks blowing the limit on large station lists.

Artwork renders correctly in-app and on the now-playing/notification surface, because
those paths *do* use the `BitmapLoader`. Only the browse tree is affected.

## Decision

Build the standard AOSP/UAMP/Pocket-Casts pattern: a `ContentProvider` that serves
browse artwork over `content://`, resolving each URL through the **same** Coil path
the now-playing `BitmapLoader` already uses (headers + SVG + size). Browse `MediaItem`s
carry a short `content://` URI instead of bytes.

This is **default-on** for browse items on Android (no config flag) and a **general
library primitive** (no consumer-specific assumptions). It fixes all three failure
modes at once and lets us retire the eager SVG-byte-embedding special case, making
browse artwork uniform.

### Transport / permission model (corrected after review)

Media3 1.10.1 does **not** grant the connected controller read permission on a
`content://` URI placed in browse-item metadata — verified: there are zero
`grantUriPermission` / `FLAG_GRANT_READ_URI_PERMISSION` calls in `libraries/session/`,
and the legacy bridge passes the bare `iconUri` through. Android Auto / Assistant run
in a **different uid**, so an `exported="false"` provider would throw
`SecurityException` on their `openInputStream`.

The canonical fix, used by both Google's UAMP sample and Pocket Casts, is to declare
the provider **`android:exported="true"`** and have the provider protect itself. We
have a *smaller* attack surface than those samples because we serve **only remote
`http(s)` artwork**, never local files — so there is no path-traversal surface to
canonicalize against. Our self-protection is:

- Reject any `u=` whose scheme is not `http`/`https` → `null`.
- (Hardening, recommended) Only serve URLs that are present in the existing
  display-time resolution registry (`ArtworkResolutionRegistry`) — i.e. URLs we
  actually handed out — so the provider cannot be used as an open fetch-proxy for
  arbitrary URLs by other apps on the device. If that registry does not cover every
  browse URL in practice, fall back to http(s)-only and document the residual
  open-proxy surface (an unauthenticated GET of an attacker-named public URL from our
  process — low severity; matches UAMP/Pocket Casts behavior). Decide during
  implementation based on whether browse URLs are reliably registered.

Note: `resolveDisplayArtwork` only attaches our custom headers to URLs it can attribute
via the registry; an unattributable `u=` is fetched header-less, so attacker-supplied
URLs cannot harvest our auth headers.

Rejected alternatives:

- **Embed bytes for all browse items** (extend the SVG-rasterize path to raster too):
  tiny change, but inlines a PNG per row → `TransactionTooLargeException` on big lists,
  eager fetch of off-screen art, larger IPC. This is precisely the failure mode the
  provider pattern exists to avoid.
- **Per-controller `grantUriPermission` lifecycle** (export `false`, grant each
  connected browse controller at subscribe time, revoke later): possible, but
  materially more logic (track controllers, revoke on disconnect) for no security gain
  over `exported="true"` + http(s)-only, given we serve no local files. Not chosen.
- **Opt-in config flag:** adds API surface while leaving the broken behavior as the
  default for anyone who doesn't know to flip it. Given there is no shipped-app
  constraint, default-on is preferred. (Cost acknowledged below.)

### Cost of default-on (acknowledged)

For a consumer whose browse artwork is plain header-less raster URLs that the car could
fetch directly, routing through our provider adds a per-icon in-process round-trip and a
PNG re-encode they did not strictly need. We accept this because: the round-trip stays
inside the app's own process/sandbox; it makes artwork correct for the consumers who
need headers/SVG; and an opt-out flag would re-introduce broken-by-default for those who
don't know to flip it. Documented here so a future maintainer fielding "why is browse
art slower on Auto" has the rationale.

## Architecture

The core insight: `CoilBitmapLoader.loadBitmap()` already turns *"a URI we handed
out"* into *"a correctly-decoded bitmap"* — applying `resolveDisplayArtwork`
(registry lookup → headers + transformed URL), the SVG decoder, and the size hint.
We extract that into a shared core and point two consumers at it: the existing
now-playing `BitmapLoader`, and a new browse-artwork `ContentProvider`.

### Components (all in the library `android/`)

| Component | Kind | Responsibility |
|---|---|---|
| `CoilArtworkLoader` | new | `suspend fun load(uri: String, sizeHintPixels: Int?): Bitmap` — the Coil request-building + `resolveDisplayArtwork` + SVG-decoder + header logic, lifted out of `CoilBitmapLoader`. Single source of truth for "URI → bitmap". Preserves the existing ordering: resolve first, then SVG-detect on the **transformed** `finalUrl` (so signed/transformed SVG URLs without a `.svg` suffix still decode). Falls back to 512px when `sizeHintPixels` is null. |
| `CoilBitmapLoader` | refactor | Thin `BitmapLoader` that delegates `loadBitmap` to `CoilArtworkLoader`. `decodeBitmap` (embedded bytes, used by the now-playing path) stays. |
| `ArtworkContentProvider` | new | `ContentProvider`. `openFile` returns a `ParcelFileDescriptor` pipe **immediately**; a fire-and-forget writer coroutine on `Dispatchers.IO` resolves the URL via `CoilArtworkLoader`, compresses to PNG, writes to the pipe's `AutoCloseOutputStream`, and closes. No `runBlocking`; the binder thread never blocks on the network. `getType` → `image/png`. |
| `ArtworkUris` | new | Pure build/parse of `content://<authority>/art?u=<url>&s=<size>` (authority = `<packageName>.audiobrowser.artwork`). Trivially unit-testable. |
| `CoilArtworkLoaderHolder` | new | Process-wide `@Volatile` holder for the `CoilArtworkLoader`. The system can construct a `ContentProvider` before the player exists, so the provider looks the core up here. (Named for its payload — a loader, not the provider — and kept distinct from the existing `ArtworkResolutionRegistry`, which is the display-time URI→Track map, a different concept.) See "Holder lifecycle" below. |
| `TrackFactory` | change | **New** `toBrowseMediaItem(track, sizeHintPixels, authority)` that sets `artworkUri = ArtworkUris.contentUri(...)`. The existing `toMedia3(track)` (used by the player queue and now-playing) is **left unchanged**. `toMedia3WithSvgSupport` is removed. |
| `MediaSessionCallback` | change | The three browse-delivery sites — `toMediaItems` (for `onGetChildren`), `onGetItem`, `onGetSearchResult` — call `toBrowseMediaItem`, passing `player.artworkSizeHintPixels` and the authority. The queue / now-playing / resumption paths keep calling plain `toMedia3`. |
| `SvgArtworkRenderer` | change | Retire `applyArtwork` and `renderSvgToBytes` (the provider rasterizes SVG via the shared core). Keep `isSvgUrl` (used by `CoilArtworkLoader`). |
| `Service.kt` / player setup | change | Populate `CoilArtworkLoaderHolder` on create; identity-guarded clear on destroy. |
| `AndroidManifest.xml` (library) | change | Declare the `<provider>` (`exported="true"`). |

### Manifest

```xml
<provider
    android:name="com.audiobrowser.util.ArtworkContentProvider"
    android:authorities="${applicationId}.audiobrowser.artwork"
    android:exported="true" />
```

`${applicationId}` makes the authority unique per consuming app and auto-merges into
the consumer's manifest — **zero config for consumers**. `exported="true"` is required
(see transport model). The provider must run in the **same process** as the media
`Service` (so the `@Volatile` holder is visible); the library declares no
`android:process`, and consumers running the service in a `:remote` process would break
this — documented as a constraint, not supported.

### Holder lifecycle

- **Set on create:** `Service.onCreate` / player setup constructs the `CoilArtworkLoader`
  (closing over `imageLoader` + `resolveDisplayArtwork`) and stores it in
  `CoilArtworkLoaderHolder`.
- **Identity-guarded clear on destroy:** `onDestroy` clears the holder **only if** the
  stored loader is still this instance's (`if (holder === mine) clear()`). This prevents
  a restarting/second `Service` instance from blanking a loader a newer instance just
  populated. Clearing releases the JS/Nitro closures the loader pins (same leak hazard
  `ArtworkResolutionRegistry.clear()` already documents).

## Data flow

1. A browse-delivery callback (`onGetChildren`/`onGetItem`/`onGetSearchResult`) converts
   tracks via `TrackFactory.toBrowseMediaItem`, setting
   `artworkUri = content://${appId}.audiobrowser.artwork/art?u=<rawUrl>&s=<hint>`, where
   `<hint>` is `player.artworkSizeHintPixels` (may be absent → provider defaults to 512).
   No bytes in the `MediaItem` — just a short URI.
2. Media3's legacy bridge sets that as the browser item's `iconUri`. The car
   (`exported="true"` provider) opens it directly.
3. `ArtworkContentProvider.openFile` returns a pipe FD immediately; the writer coroutine
   resolves `rawUrl` via `CoilArtworkLoader.load(rawUrl, hint)` (headers + SVG + size in
   *our* process) → PNG → pipe → car.
4. **Cold start** (provider hit before the holder is populated): return `null`
   (placeholder); the car re-requests on the next browse refresh once setup completes.
   In practice this window is essentially closed already, because `onGetChildren` gates
   on `player.awaitBrowser()` and artwork is only requested *after* a browse list is
   delivered — so the holder (set in `Service.onCreate`) is set by then. **No header-less
   fallback fetch** (it would fail exactly the header/SVG cases this feature fixes).

This kills all three failure modes: headers and SVG always apply (step 3 runs in our
process), and the Binder/`TransactionTooLargeException` risk is gone because
`MediaItem`s carry a tiny URI instead of PNG bytes.

**Known minor inefficiency:** an in-process *Media3* (non-legacy) browser controller
would round-trip `content://` back through Coil → our provider. In practice the only
browse controllers on Android are external (Auto/Assistant/Bluetooth) — the RN app
renders its own browse UI in JS — so this is negligible and not optimized.

## Error handling

- Resolve fails / null bitmap → writer closes the pipe with no data; the car shows its
  placeholder. Logged via Timber. Never throw across the Binder boundary.
- Malformed/missing `u` param, or non-`http(s)` scheme → `openFile` returns `null`
  immediately (also a security guard).
- Pipe closed by the car mid-write (user scrolled away) → the writer coroutine catches
  the broken-pipe `IOException` and cancels. No crash, no leak.
- All resolution on `Dispatchers.IO`; `openFile` itself never blocks.

## Performance

- `openFile` decodes (Coil, source-cached on disk) → `Bitmap` → re-`compress(PNG)` per
  request; Coil caches the *source*, not the re-encoded PNG, so repeated requests for the
  same row re-encode. **Optional optimization** (implement if DHU shows repeated fetches
  are costly): a small LRU of encoded bytes keyed by `url+size`. Not required for
  correctness. PNG (quality 100) is kept for uniformity across raster + SVG; switching
  opaque raster to JPEG is a possible later refinement but complicates `getType`.

## Security

- `exported="true"` is required for cross-uid car access; self-protection is http(s)-only
  (no local files → no path-traversal surface) plus the optional registry-membership
  check (see transport model) to avoid acting as an open fetch-proxy.
- Our custom headers are only attached to registry-attributable URLs, so an
  attacker-supplied `u=` cannot harvest them.

## Testing

The library uses JUnit 4 + Mockito + kotlinx-coroutines-test + Robolectric 4.11.1
(`android/src/test`).

- **Unit (pure):** `ArtworkUris` round-trip (build → parse), including a `u=` URL that
  itself carries query params and an encoded `&` (verify it survives); non-`http(s)`
  rejection.
- **Robolectric:** `ArtworkContentProvider.openFile` happy path (returns a readable PNG
  FD) and null cases (missing param, non-http scheme, holder absent), with a fake
  `CoilArtworkLoader` injected via the holder. Tests share the process-wide holder, so a
  `@Before`/`@After` resets it to avoid cross-test interference.
- **Manual on Android Auto DHU** (Desktop Head Unit), added to `manual-testing/`:
  (a) raster browse art renders, (b) SVG browse art renders, (c) a large station list
  (50+) browses without `TransactionTooLargeException`, (d) header-requiring art
  renders. This is the real proof, since the failure mode is cross-process.

## Implementation sequencing

One PR, but ordered so browse artwork never regresses:

1. Add `CoilArtworkLoader` (+ refactor `CoilBitmapLoader` to delegate), `ArtworkUris`,
   `CoilArtworkLoaderHolder`, `ArtworkContentProvider`, manifest entry, holder
   population in `Service`.
2. Switch the three browse-delivery sites to `toBrowseMediaItem`.
3. **DHU-verify** the provider end-to-end (raster + SVG + large list + headers).
4. Only after step 3 passes, remove `toMedia3WithSvgSupport` /
   `SvgArtworkRenderer.applyArtwork` / `renderSvgToBytes`. (Removing the SVG byte path
   before the provider is proven would regress SVG browse art to blank.)

## Out of scope

- TS / Nitro-spec changes — none (default-on, no new config field).
- Consumer-app changes — none (authority is `${applicationId}`-scoped, auto-merged);
  the only constraint is that the media service runs in the default process.
- iOS / CarPlay — unaffected; CarPlay artwork already resolves in-process.
