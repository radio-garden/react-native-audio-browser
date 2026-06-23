# Android Auto / AAOS browse artwork via a content:// provider

**Date:** 2026-06-23
**Issue:** [#63](https://github.com/radio-garden/react-native-audio-browser/issues/63) — browse-list artwork URIs bypass the session `BitmapLoader` on Android Auto/AAOS
**Status:** Design approved, pending spec review

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

Build the standard AOSP/UAMP/Pocket-Casts pattern: an exported-to-granted-controllers
`ContentProvider` that serves browse artwork over `content://`, resolving each URL
through the **same** Coil path the now-playing `BitmapLoader` already uses (headers +
SVG + size). Browse `MediaItem`s carry a short `content://` URI instead of bytes.

This is **default-on** for browse items on Android (no config flag) and a **general
library primitive** (no consumer-specific assumptions). It fixes all three failure
modes at once and lets us retire the eager SVG-byte-embedding special case, making
browse artwork uniform.

Rejected alternatives:

- **Embed bytes for all browse items** (extend the SVG-rasterize path to raster too):
  tiny change, but inlines a PNG per row → `TransactionTooLargeException` on big lists,
  eager fetch of off-screen art, larger IPC. This is precisely the failure mode the
  provider pattern exists to avoid.
- **Opt-in config flag:** adds API surface while leaving the broken behavior as the
  default for anyone who doesn't know to flip it. Given there is no shipped-app
  constraint, default-on is preferred.

## Architecture

The core insight: `CoilBitmapLoader.loadBitmap()` already turns *"a URI we handed
out"* into *"a correctly-decoded bitmap"* — applying `resolveDisplayArtwork`
(registry lookup → headers + transformed URL), the SVG decoder, and the size hint.
We extract that into a shared resolution core and point two consumers at it: the
existing now-playing `BitmapLoader`, and a new browse-artwork `ContentProvider`.

### Components (all in the library `android/`)

| Component | Kind | Responsibility |
|---|---|---|
| `ArtworkResolver` | new | `suspend fun loadBitmap(uri: String, sizeHint: Int?): Bitmap` — the Coil request-building + `resolveDisplayArtwork` + SVG-decoder + header logic, lifted out of `CoilBitmapLoader`. Single source of truth for "URI → bitmap". |
| `CoilBitmapLoader` | refactor | Thin `BitmapLoader` that delegates `loadBitmap` to `ArtworkResolver`. `decodeBitmap` (embedded bytes, used by the now-playing path) stays. |
| `ArtworkContentProvider` | new | `ContentProvider`. `openFile` resolves the embedded URL via `ArtworkResolver`, compresses to PNG, streams over a `ParcelFileDescriptor` pipe. `getType` → `image/png`. |
| `ArtworkUris` | new | Pure build/parse of `content://<authority>/art?u=<url>&s=<size>`. Trivially unit-testable. |
| `BrowseArtworkProviderHolder` | new | Process-wide `@Volatile` holder for the `ArtworkResolver`. The system can construct a `ContentProvider` before the player exists, so the provider looks the resolver up here. Populated at player setup, cleared on destroy. (Named distinctly from the existing `ArtworkResolutionRegistry`, which is the display-time URI→Track map — a different concept.) |
| `TrackFactory` | change | Browse items set `artworkUri = ArtworkUris.contentUri(rawUrl, sizeHint)` instead of a raw URI or embedded SVG bytes. |
| `SvgArtworkRenderer` | change | Retire the `applyArtwork` byte path and `renderSvgToBytes` (the provider rasterizes SVG via the shared core). Keep `isSvgUrl` (still used by `ArtworkResolver`). |
| `Service.kt` / player setup | change | Populate `BrowseArtworkProviderHolder` on create, clear on destroy. |
| `AndroidManifest.xml` (library) | change | Declare the `<provider>`. |

### Manifest

```xml
<provider
    android:name="com.audiobrowser.util.ArtworkContentProvider"
    android:authorities="${applicationId}.audiobrowser.artwork"
    android:exported="false"
    android:grantUriPermissions="true" />
```

`${applicationId}` makes the authority unique per consuming app and auto-merges into
the consumer's manifest — **zero config for consumers**. `exported="false"` +
`grantUriPermissions="true"` means only controllers that Media3 explicitly grants
(the connected car/Assistant) can read these URIs; arbitrary apps cannot.

## Data flow

1. `onGetChildren` → `toMediaItems`: each track with artwork gets
   `artworkUri = content://${appId}.audiobrowser.artwork/art?u=<rawUrl>&s=<hint>`.
   No bytes in the `MediaItem` — just a short URI.
2. Media3's legacy bridge sets that as `iconUri` and auto-grants the connected
   controller temporary read permission.
3. The car opens the URI → `ArtworkContentProvider.openFile` →
   `ArtworkResolver.loadBitmap(rawUrl, hint)` (headers + SVG + size applied in *our*
   process) → PNG → pipe back to the car.
4. **Cold start** (provider hit before player setup populated the holder): `openFile`
   awaits the holder with a short timeout (~2s, mirroring the existing `awaitBrowser`
   pattern); on timeout, the provider builds a plain Coil `ImageLoader` from its own
   context and fetches the raw URL header-less; returns `null` (placeholder) if even
   that fails.

This kills all three failure modes: headers and SVG always apply (step 3 runs in our
process), and the Binder/`TransactionTooLargeException` risk is gone because
`MediaItem`s carry a tiny URI instead of PNG bytes.

**Known minor inefficiency:** an in-process *Media3* (non-legacy) browser controller
would round-trip `content://` back through Coil → our provider. In practice the only
browse controllers on Android are external (Auto/Assistant/Bluetooth) — the RN app
renders its own browse UI in JS — so this is negligible and not optimized.

## Error handling

- Resolve fails / null bitmap → `openFile` returns `null` (car shows its placeholder);
  logged via Timber. Never throw across the Binder boundary.
- Malformed/missing `u` param, or non-`http(s)` scheme → `null` immediately (also a
  security guard).
- Pipe closed by the car mid-write (user scrolled away) → the writer coroutine catches
  the broken-pipe `IOException` and cancels. No crash, no leak.
- All resolution on `Dispatchers.IO`; the PNG write happens on a background thread
  feeding the `ParcelFileDescriptor` pipe.

## Security

- `exported="false"` + `grantUriPermissions="true"`: only Media3-granted controllers
  can read.
- The provider resolves **only `http(s)`** original URLs; `file://`, `content://`,
  `android.resource://` are rejected. No local-file serving and no path-traversal
  surface (unlike implementations that serve local files and must canonicalize paths).
- URLs only ever originate from our own browse metadata and run through the same
  `resolveDisplayArtwork` as everything else — no new attacker-controlled input path.

## Testing

The library uses JUnit 4 + Mockito + kotlinx-coroutines-test + Robolectric 4.11.1
(`android/src/test`).

- **Unit (pure):** `ArtworkUris` round-trip (build → parse), non-`http(s)` rejection.
- **Robolectric:** `ArtworkContentProvider.openFile` happy path (returns a readable PNG
  FD) and null cases (missing param, non-http scheme, resolver absent), with a fake
  `ArtworkResolver`.
- **Manual on Android Auto DHU** (Desktop Head Unit), added to `manual-testing/`:
  (a) raster browse art renders, (b) SVG browse art renders, (c) a large station list
  (50+) browses without `TransactionTooLargeException`, (d) header-requiring art
  renders. This is the real proof, since the failure mode is cross-process.

## Out of scope

- TS / Nitro-spec changes — none (default-on, no new config field).
- Consumer-app changes — none (authority is `${applicationId}`-scoped, auto-merged).
- iOS / CarPlay — unaffected; CarPlay artwork already resolves in-process.
