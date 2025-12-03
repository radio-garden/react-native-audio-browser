# Future Ideas

Ideas for future improvements that aren't urgent but worth considering.

## Playback Resumption Cache

Currently, playback resumption always resolves via the `browse` callback, which requires JS to be ready and potentially a network request.

**Idea:** Cache the full queue (or at least the playing track) alongside the URL/position in `PlaybackStateStore`. Use cached data if fresh (e.g., <24h), otherwise fall back to browse resolution.

**Benefits:**
- Instant resumption without waiting for JS/network
- Works offline
- Better UX for Bluetooth play button

**Trade-offs:**
- More storage (need to serialize Track array)
- Potentially stale metadata
- Need to decide TTL / freshness heuristic

**Implementation notes:**
- Could use JSON serialization for Track array
- Consider caching just the single playing track for faster first-play, then expand queue in background
- Browse callback could refresh cache in background after playback starts

## Configurable Retry Backoff Parameters

Currently retry backoff uses hardcoded values (1s initial, 1.5x multiplier, 5s cap). Could expose these for apps with specific needs.

**Potential API:**
```typescript
type RetryConfig = {
  maxRetries?: number;      // undefined = infinite
  initialDelayMs?: number;  // default: 1000
  multiplier?: number;      // default: 1.5
  maxDelayMs?: number;      // default: 5000
}
```

**Use cases:**
- `{ multiplier: 1, maxDelayMs: 2000 }` - constant 1s retries (aggressive, for time-sensitive streams)
- `{ multiplier: 2, maxDelayMs: 10000 }` - slower backoff for battery-sensitive apps
- `{ initialDelayMs: 500 }` - faster first retry

**Trade-offs:**
- More API surface to document/maintain
- Risk of misconfiguration (e.g., multiplier < 1)
- Current defaults work well for most streaming use cases

## Auto-Update Now Playing from Stream Metadata

Once `updateNowPlaying()` is implemented (see TODO-now-playing-metadata.md), consider adding automatic mode for radio streams.

**Idea:** When `onPlaybackMetadata` fires (ICY/ID3 tags from live streams), automatically pipe it to the now playing notification.

**Potential API:**
```typescript
AudioPlayer.setAutoNowPlayingFromStreamMetadata(enabled: boolean)
// or as a setup option:
setup({ autoNowPlayingFromStreamMetadata: true })
```

**Benefits:**
- Zero-effort live stream support
- Title/artist from ICY tags automatically appears in notification
- No manual wiring of `onPlaybackMetadata` → `updateNowPlaying()`

**Trade-offs:**
- Less control (might want to filter/transform metadata first)
- Current explicit approach gives full flexibility
- Could always be done in userland with:
  ```typescript
  onPlaybackMetadata.subscribe(({ title, artist }) => {
    AudioPlayer.updateNowPlaying({ title, artist })
  })
  ```

**Decision:** Start with explicit control, add auto-mode later if there's demand.

## Google Media Actions / URI-Based Playback

Google Assistant supports two ways to start playback:
1. **Search-based** (`onPlayFromSearch`) - User says "play X", app searches and plays
2. **URI-based** (`onPlayFromUri`) - Google already knows the content URI, direct playback

Currently we only support search-based. URI-based requires a partnership with Google.

**What's involved:**

1. Apply to [Google Media Actions program](https://developers.google.com/actions/media)
2. Submit a content catalog feed (JSON-LD) with station URIs:
   ```json
   {
     "@type": "RadioStation",
     "name": "BBC Radio 1",
     "broadcastDisplayName": "BBC Radio 1",
     "potentialAction": {
       "@type": "ListenAction",
       "target": {
         "urlTemplate": "radiogarden://station/abc123",
         "actionPlatform": ["http://schema.org/AndroidPlatform"]
       }
     }
   }
   ```
3. Implement `onPlayFromUri` to handle direct URI playback

**Benefits:**
- Faster playback (no search step)
- More accurate matching (Google knows exact station)
- Works when search API is slow/unavailable
- Better handling of stations with similar names

**Implementation:**
- Add `onPlayFromUri` handler in `MediaSessionCallback`
- Parse URI scheme (e.g., `radiogarden://station/{id}`)
- Look up station by ID and start playback

**Trade-offs:**
- Requires ongoing catalog maintenance and submission to Google
- Business relationship/approval process with Google
- Current `onPlayFromSearch` handles most voice commands adequately

**References:**
- https://developer.android.com/guide/topics/media-apps/interacting-with-assistant
- https://developers.google.com/search/docs/appearance/structured-data/media-actions

## Animated Artwork on Lock Screen

iOS 26 introduced animated album artwork on the lock screen - tapping the artwork expands it and plays the animation.

**References:**
- [Reddit: Lockscreen animated album art is just gorgeous](https://www.reddit.com/r/AppleMusic/comments/1ne6a2t/lockscreen_animated_album_art_is_just_gorgeous/)

**To investigate:**
- How to provide animated artwork via `MPNowPlayingInfoCenter`
- What formats are supported (GIF, APNG, video?)
- Android equivalent (if any)

## Remote Vector Drawables for Android Auto Icons

Currently, tintable icons in Android Auto require bundled vector drawables via `android.resource://` URIs. This means icons must be included in the app at build time.

**Question:** Is there any way to load vector drawable data from a remote URL?

**Possibilities to explore:**
1. **Data URI with SVG** - `data:image/svg+xml;base64,...` - probably not supported by Media3's artwork loading
2. **Custom ContentProvider** - Serve vector XML via `content://` URI - complex but might work
3. **HTTP-served vector XML** - Unlikely, Android probably treats it as image download not VectorDrawable

**Workaround (current):**
- Pass app version to API via query params or user agent
- API returns `android.resource://` URIs only for icons bundled in that app version
- Older app versions get fallback http:// PNG/JPEG URLs

**Why this matters:**
- Adding new navigation icons requires app update
- Can't dynamically add category icons from server
- Limits flexibility for server-driven UI

**To test:**
- Try `data:image/svg+xml,...` as artwork URI
- Check if Media3 has any extension points for custom URI schemes

## Completion Status for Media Items (Android Auto/AAOS)

Android Auto supports visual indicators on media items to show playback progress - useful for podcasts, audiobooks, or any episodic content.

**Available via `MediaConstants` extras:**
- `DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS`:
  - `VALUE_COMPLETION_STATUS_NOT_PLAYED` - unplayed indicator
  - `VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED` - started but not finished
  - `VALUE_COMPLETION_STATUS_FULLY_PLAYED` - checkmark/complete indicator
- `DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE` - progress bar (0.0 to 1.0, augments partially played)

**Potential API:**
```typescript
type Track = {
  // ... existing fields
  completionStatus?: 'not-played' | 'partially-played' | 'fully-played'
  completionPercentage?: number // 0.0 to 1.0
}
```

**Use cases:**
- Podcast apps - show which episodes have been listened to
- Audiobook apps - track chapter progress
- Music apps - "recently played" with completion indicators

**Trade-offs:**
- Not relevant for radio/live streams
- Adds complexity for apps that don't need it
- App is responsible for tracking/persisting completion state

**References:**
- https://androidx.de/androidx/media/utils/MediaConstants.html

## Separate Styles for Browsable vs Playable Children

Currently `childrenStyle` applies the same style to both browsable and playable children. Some containers might benefit from different styles (e.g., grid for album folders, list for individual tracks).

**Potential API:**
```typescript
type ChildrenStyle = {
  playable?: TrackStyle
  browsable?: TrackStyle
}

type Track = {
  // ... existing fields
  childrenStyle?: ChildrenStyle
}
```

**Usage:**
```typescript
// Different styles for browsable vs playable children
childrenStyle: { playable: 'list', browsable: 'grid' }

// Same style for both (more verbose than current)
childrenStyle: { playable: 'grid', browsable: 'grid' }
```

**Use cases:**
- Library section with album folders (browsable → grid) and individual songs (playable → list)
- "Recently Played" mixing playlists (browsable) and tracks (playable)

**Alternative - union type for ergonomics:**
```typescript
childrenStyle?: TrackStyle | {
  playable?: TrackStyle
  browsable?: TrackStyle
}

// Simple (same for both)
childrenStyle: 'grid'

// Explicit (different styles)
childrenStyle: { playable: 'list', browsable: 'grid' }
```

**Trade-offs:**
- Object-only form is more verbose for the common case
- Union form is more ergonomic but slightly more complex to handle on native side
- Most containers have homogeneous children anyway
- Current simple `childrenStyle: TrackStyle` covers ~90% of use cases

**Decision:** Keep simple `childrenStyle: TrackStyle` for now. Add nested object (or union) if real-world use cases emerge.

## Tesla Artwork Bitmap Fallback

Tesla vehicles don't support URI-based artwork loading in their media player. They require embedded bitmaps in the metadata.

**Problem:**
- Most cars/Android Auto load artwork from `METADATA_KEY_ALBUM_ART_URI`
- Tesla ignores URIs and needs `METADATA_KEY_ALBUM_ART` (embedded Bitmap)
- Without this, Tesla shows no artwork

**Solution (from Pocket Casts):**
```kotlin
// URI for most platforms
nowPlayingBuilder.putString(METADATA_KEY_ALBUM_ART_URI, bitmapUri)

// Embedded bitmap for devices that don't support URIs (Tesla!)
// Skip on Wear OS and Automotive to save memory
if (!Util.isWearOs(context) && !Util.isAutomotive(context)) {
    loadBitmap(artworkUrl)?.let { bitmap ->
        nowPlayingBuilder.putBitmap(METADATA_KEY_ALBUM_ART, bitmap)
    }
}
```

**Considerations:**
- Media3's `DefaultMediaNotificationProvider` may handle this automatically
- Only matters for users with Tesla vehicles
- Adds memory overhead (bitmap in metadata)
- Need to detect Tesla or just always include bitmap on phone

**Detection approaches:**
1. Always include bitmap (simple, slightly more memory)
2. Check `Build.MANUFACTURER` for "Tesla" (if running on Tesla's Android system)
3. Wait for user reports before implementing

**Trade-offs:**
- Memory: Bitmap embedded in metadata
- Complexity: Need to load bitmap synchronously or cache it
- Scope: Only affects Tesla users

**References:**
- Pocket Casts `MediaSessionManager.kt:409-419`

## Custom Cache Key for Stable Caching with Dynamic URLs

ExoPlayer's disk cache uses the URL as cache key by default. This causes cache misses when URLs change but content is the same (signed URLs, CDN rotation, token refresh).

**Problem:**
```
# Monday - signed URL
https://cdn.example.com/episode.mp3?sig=abc&expires=123

# Tuesday - same file, new signature
https://cdn.example.com/episode.mp3?sig=xyz&expires=456
```
Default behavior: Cache miss → re-downloads entire file.

**Solution (from Pocket Casts):**
```kotlin
MediaItem.Builder()
    .setUri(episodeUri)
    .setCustomCacheKey(episode.uuid)  // stable identifier
    .build()
```

**Potential implementation:**
```kotlin
// In MediaFactory.createMediaSource()
MediaItem.Builder()
    .setUri(finalUrl.toUri())
    .setCustomCacheKey(track.src ?: track.url)  // src is stable, URL may have tokens
    .build()
```

**Use cases:**
- Apps using signed URLs (AWS S3, CloudFront, GCS)
- CDN rotation or load balancing
- Token-based authentication on streams
- URLs with session/analytics parameters

**When not needed:**
- Live streams (not cached anyway)
- Static URLs that never change
- Apps without disk caching enabled

**Trade-offs:**
- Minimal implementation effort
- No API change needed (uses existing `src` field)
- Only matters for apps with disk caching + dynamic URLs

## MP3 Index Seeking for Accurate Seeking in VBR Files

ExoPlayer's default MP3 seeking uses constant bitrate estimation, which is fast but inaccurate for variable bitrate (VBR) files. Enabling index seeking builds a seek table for accurate positioning.

**Implementation (from Pocket Casts):**
```kotlin
val extractorsFactory = DefaultExtractorsFactory()
    .setConstantBitrateSeekingEnabled(true)  // We already do this

// Optional: enable index seeking for accurate seeks in VBR MP3s
if (settings.prioritizeSeekAccuracy.value) {
    extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
}
```

**Potential API:**
```typescript
setup({
  mp3SeekAccuracy?: 'fast' | 'accurate'  // default: 'fast'
})
```

**How it works:**
- `fast` (default): Estimates seek position based on bitrate - instant but may land ±5-10 seconds off in VBR files
- `accurate`: Builds an index by scanning the file - uses more memory/CPU but seeks to exact position

**Use cases:**
- Podcast apps where users scrub to specific timestamps
- Audiobook apps with chapter markers
- Any app where "skip back 30 seconds" must be precise

**Trade-offs:**
- Memory: Index table size depends on file length
- Startup: Small delay while initial index is built
- Not needed for: CBR files, live streams, music (where ±few seconds doesn't matter)

**References:**
- Pocket Casts uses this as a user preference (`prioritizeSeekAccuracy`)
- https://developer.android.com/media/media3/exoplayer/seeking

## Shared Coil Image Cache with react-native-nitro-image

The app currently uses Coil in two places:
1. `Service.kt` - Shared ImageLoader for Media3 artwork and browse-time URL transformation
2. React Native side - `<Image>` components for displaying track artwork

These use separate caches, causing artwork to be downloaded twice.

**[react-native-nitro-web-image](https://github.com/mrousavy/react-native-nitro-image)** is a Nitro-based image library that uses Coil on Android. However, it creates its own `ImageLoader` instance in `HybridWebImageFactory`:

```kotlin
// packages/react-native-nitro-web-image/.../HybridWebImageFactory.kt
private val imageLoader = ImageLoader(context)  // Own instance, own cache
```

**Options to share the cache:**

1. **Share disk cache directory** - Configure both ImageLoaders to use the same disk cache path (`cacheDir.resolve("artwork")`). Separate instances but shared files.

2. **Fork/extend nitro-web-image** - Create a custom factory that accepts an external ImageLoader.

3. **Expose our ImageLoader to JS** - Add a Nitro method that loads images using our existing shared ImageLoader instead of using nitro-web-image.

4. **Use Coil's singleton** - Both could use `context.imageLoader` (Coil's app-level singleton) with matching config.

**Simplest approach:** Configure matching disk cache directories. Even with separate ImageLoader instances, Coil's disk cache is file-based and can be shared.

**Implementation notes:**
- Our disk cache: `cacheDir.resolve("artwork")` (configured in `Service.kt`)
- nitro-web-image uses Coil defaults (2% of disk, different directory)
- Would need to either:
  - Configure nitro-web-image's cache directory (if supported)
  - Or create our own image loading Nitro method

**Better approach: Use nitro-image as a dependency**

`react-native-nitro-image` is designed to be used as a shared type across libraries. We could:

1. Add `react-native-nitro-image` as a peer/dev dependency
2. Add `:react-native-nitro-image` to `build.gradle` dependencies
3. Create our own `HybridImageLoaderSpec` implementation that uses our shared Coil ImageLoader
4. Return `HybridImageSpec` instances from a Nitro method

```kotlin
// In react-native-audio-browser
class HybridArtworkLoader(
  private val imageLoader: ImageLoader,  // Our shared instance from Service.kt
  private val context: Context
): HybridImageLoaderSpec() {
  override fun loadImage(): Promise<HybridImageSpec> {
    // Use our shared imageLoader with disk cache
    return imageLoader.loadImageAsync(url, options, context)
  }
}
```

This way:
- We control the ImageLoader instance (with our disk cache config)
- App can render artwork using `<NitroImage />` component
- Single shared cache for both Media3 and React Native UI
- No fork needed - we implement the protocol with our loader
- **SVG support** - our ImageLoader already has `SvgDecoder` configured (nitro-web-image doesn't include `coil-svg`)

**Trade-offs:**
- Adds dependency on react-native-nitro-image
- More setup (CMake, podspec, build.gradle)
- Requires New Architecture

**References:**
- https://github.com/mrousavy/react-native-nitro-image
- [Using the native Image type in a third-party library](https://github.com/mrousavy/react-native-nitro-image#using-the-native-image-type-in-a-third-party-library)
- Coil disk cache docs: https://coil-kt.github.io/coil/image_loaders/#disk-cache

## Allow ImageSource as artwork input

Currently `artwork` accepts only a string URL. We could allow passing `ImageSource` directly:

```typescript
interface Track {
  artwork?: string | ImageSource;  // Accept both
  readonly artworkSource?: ImageSource;  // Always output normalized
}
```

**Use case:** Users who need custom headers/auth for artwork but don't want to configure global artwork transform.

```typescript
// Instead of configuring artwork.resolve globally:
const track = {
  title: 'My Track',
  artwork: {
    uri: 'https://cdn.example.com/image.jpg',
    headers: { Authorization: `Bearer ${token}` }
  }
}
```

**Implementation notes:**
- Native side would detect if `artwork` is already an `ImageSource`
- If so, use it directly (or optionally merge with global config)
- If string, transform via existing artwork config → `artworkSource`
- Adds variant type complexity in Nitro (runtime type checking)

**Trade-offs:**
- Cleaner for one-off auth needs
- But adds union type overhead (variant in C++/Kotlin/Swift)
- API becomes "input can be X or Y, output is always Y"
- May not be worth it if global artwork config covers most cases

## Custom Notification Buttons

Currently `ButtonCapability` only supports predefined actions: `skip-to-previous`, `skip-to-next`, `jump-backward`, `jump-forward`, `favorite`. Users may want custom actions (e.g., "sleep timer", "playback speed", "share").

**Potential API:**
```typescript
notificationButtons: {
  back: 'skip-to-previous',
  forward: 'skip-to-next',
  overflow: [
    'favorite',
    { id: 'sleep-timer', icon: 'timer', label: 'Sleep Timer' }
  ]
}
```

**Implementation needs:**
- Icon resource registration
- Custom session command handling
- JS callback for button tap

## Keyboard Controls Documentation

Spacebar pause/resume for external keyboards must be handled at the Activity level in consuming apps (services don't receive key events).

**To do:**
- Add reference implementation to example app
- Document in library README

**Reference:** https://developer.android.com/media/media3/session/control-playback#keyboard

## Browsable + Playable Combined Items

Media3 demo sets both `isPlayable = true` and `isBrowsable = true` on album folders. This allows users to tap to play entire folder OR navigate into it.

**Needs research:**
- How does Android Auto present these? Is there a play icon vs browse tap?
- Does Google Assistant handle "play [album name]" differently for playable folders?
- Are there UX issues reported with this pattern?

**Reference:** Media3 session demo `MediaItemTree.kt:222-238`

## Google Assistant Compliance (Optional Enhancements)

Reference: https://developer.android.com/media/media3/session/control-playback

### Latency Optimization

Implement `ACTION_PREPARE_FROM_SEARCH` to reduce playback latency. Google Assistant can call prepare before play to cache content during voice announcement.

**Implementation:**
- Add handler in `MediaSessionCallback` for `onPrepareFromSearch()`
- Should prepare media without starting playback

**Reference:** https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#prepare

### URI-Based Playback (Only if partnering with Google)

Implement `ACTION_PLAY_FROM_URI` / `ACTION_PREPARE_FROM_URI`. Only needed if providing URIs to Google via Media Actions.

**Reference:** https://developer.android.com/guide/topics/media-apps/interacting-with-assistant

### Deep Link Intent Handling

Handle `EXTRA_START_PLAYBACK` intent extra. When Assistant launches app via deep link (not MediaBrowser), it adds `EXTRA_START_PLAYBACK=true`. App should auto-start playback when receiving intent with this extra.

**Implementation:** Check for extra in Activity's `onCreate()` / `onNewIntent()`

**Reference:** https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#playback-with-an-intent

### Business Logic Error Codes

Surface explicit error codes for business logic failures. Currently using Media3's technical error codes (io, timeout, etc.). For better Assistant UX, set specific `PlaybackState` error codes:

- `ERROR_CODE_AUTHENTICATION_EXPIRED` - User needs to sign in
- `ERROR_CODE_NOT_SUPPORTED` - Action not supported
- `ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED` - Premium feature requested by free user
- `ERROR_CODE_NOT_AVAILABLE_IN_REGION` - Content geo-restricted

**Implementation:** Add `setPlaybackError(code, message)` API for JS to report these conditions

**Reference:** https://developer.android.com/reference/androidx/media3/common/PlaybackException
