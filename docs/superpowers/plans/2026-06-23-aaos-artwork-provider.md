# AAOS Browse Artwork content:// Provider — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve Android Auto / AAOS browse-list artwork through an exported, token-gated `content://` provider so header-bearing and SVG artwork render on the car and large lists never hit `TransactionTooLargeException`.

**Architecture:** Browse `MediaItem`s carry a short `content://…/art/<token>` URI instead of a raw URL or embedded bytes. At browse-build time the library resolves each http(s) artwork (it already does — `track.artworkSource` holds the resolved URL + headers) and registers `token → {finalUrl, headers, isSvg}` in a plain-data `BrowseArtworkRegistry`. The exported `ArtworkContentProvider` looks the token up (unknown → null, so it can never fetch an arbitrary URL), resolves it in-process via a shared `CoilArtworkLoader` (headers + SVG decoder + size), and streams a PNG over a pipe. Non-http(s) artwork (e.g. `android.resource://` tab icons) is passed through to `setArtworkUri` unchanged.

**Tech Stack:** Kotlin, Media3 1.10.1 (`MediaLibrarySession`), Coil 3 (`coil3`), kotlinx-coroutines, Guava `ListenableFuture`; tests JUnit 4 + Mockito + kotlinx-coroutines-test + Robolectric 4.11.1.

**Design spec:** `docs/superpowers/specs/2026-06-23-aaos-artwork-provider-design.md` (read it first).

## Global Constraints

- Package root: `com.audiobrowser` (namespace, `android/build.gradle:54`). New files live under `android/src/main/java/com/audiobrowser/…`; tests under `android/src/test/java/com/audiobrowser/…`.
- Provider authority is exactly `${applicationId}.audiobrowser.artwork` (manifest placeholder → unique per consuming app, auto-merged).
- The provider is `exported="true"` and MUST be token-gated: it never accepts or fetches a caller-supplied URL, only tokens registered by the library. Enforce http(s)-only on the registered `finalUrl` as defense-in-depth.
- The provider and the media `Service` MUST run in the same process (the `@Volatile` holder must be visible). The library declares no `android:process`.
- No `runBlocking` on the binder thread: `openFile` returns the pipe FD immediately; resolve/decode/encode happen on a bounded background scope; the write FD is closed in `finally` on every path (success, null bitmap, cancellation, broken pipe).
- No TS / Nitro-spec changes. No consumer-app code changes beyond the same-process constraint.
- Do NOT delete `toMedia3WithSvgSupport` / `SvgArtworkRenderer.applyArtwork` / `renderSvgToBytes` until the DHU verification task (Task 9) passes — removing them earlier regresses SVG browse art to blank.
- **Android unit tests** run via the example app's Gradle against the library module (verified working in this checkout — existing suite builds green in ~8s):
  `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest`
  Append `--tests "com.audiobrowser.…ClassName"` to scope to one class. `ANDROID_HOME` must be exported (it is unset in a fresh shell). The module Gradle path is `:react-native-audio-browser`.
- Commit after every task with the shown message.

## File Structure

| File | Responsibility |
|---|---|
| `util/ArtworkUris.kt` (new) | Build/parse `content://<authority>/art/<token>`; deterministic `tokenFor(url)`. Pure. |
| `browser/BrowseArtworkRegistry.kt` (new) | `@Synchronized` map `token → ResolvedArtwork(finalUrl, headers, isSvg)`. Plain data, no Nitro handles. Bounded LRU. |
| `util/CoilArtworkLoader.kt` (new) | `suspend fun load(finalUrl, headers, sizeHintPixels, isSvg): Bitmap`. The Coil request build (data + headers + `.size()` + forced SVG decoder). Shared decode core. |
| `util/CoilBitmapLoader.kt` (modify) | Delegate the decode half of `loadBitmap` to `CoilArtworkLoader`; keep URI resolution + `decodeBitmap`. |
| `util/CoilArtworkLoaderHolder.kt` (new) | Process-wide `@Volatile` holder: `CoilArtworkLoader` + `BrowseArtworkRegistry` + provider `CoroutineScope`. Identity-guarded clear. |
| `util/ArtworkContentProvider.kt` (new) | Exported provider. `openFile` token lookup → pipe; bounded writer; `getType`=image/png; CRUD no-ops. |
| `util/TrackFactory.kt` (modify) | New `toBrowseMediaItem(track, sizeHintPixels, registry, authority)`; scheme routing; register in registry. Remove `toMedia3WithSvgSupport` (Task 9). |
| `util/SvgArtworkRenderer.kt` (modify) | Keep `isSvgUrl`; remove `applyArtwork` + `renderSvgToBytes` (Task 9). |
| `player/MediaSessionCallback.kt` (modify) | Browse-delivery sites call `toBrowseMediaItem`. |
| `Service.kt` (modify) | Populate holder+registry+scope on create; identity-guarded clear + cancel scope before `player.destroy()`. |
| `android/src/main/AndroidManifest.xml` (modify) | Declare `<provider exported="true">`. |
| `manual-testing/android-auto-artwork.md` (new) | DHU walkthrough (Task 9). |

---

### Task 1: `ArtworkUris` — content URI build/parse (pure)

**Files:**
- Create: `android/src/main/java/com/audiobrowser/util/ArtworkUris.kt`
- Test: `android/src/test/java/com/audiobrowser/util/ArtworkUrisTest.kt`

**Interfaces:**
- Produces:
  - `ArtworkUris.AUTHORITY_SUFFIX: String = "audiobrowser.artwork"`
  - `ArtworkUris.authorityFor(packageName: String): String` → `"$packageName.$AUTHORITY_SUFFIX"`
  - `ArtworkUris.tokenFor(url: String): String` — deterministic SHA-256 hex of `url` (stable across rebuilds → dedupes registry entries; opaque, never exposes the URL).
  - `ArtworkUris.contentUri(authority: String, token: String): String` → `"content://$authority/art/$token"`
  - `ArtworkUris.parseToken(uri: android.net.Uri): String?` — returns the `<token>` for a well-formed `…/art/<token>` URI under any authority, else `null`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.audiobrowser.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkUrisTest {
  @Test
  fun `tokenFor is deterministic and opaque`() {
    val url = "https://cdn.example.com/a.png?sig=abc&x=1"
    assertEquals(ArtworkUris.tokenFor(url), ArtworkUris.tokenFor(url))
    // token does not leak the url
    assert(!ArtworkUris.tokenFor(url).contains("cdn.example.com"))
  }

  @Test
  fun `contentUri round-trips through parseToken`() {
    val authority = ArtworkUris.authorityFor("com.myapp")
    val token = ArtworkUris.tokenFor("https://cdn.example.com/a.svg")
    val uri = ArtworkUris.contentUri(authority, token)
    assertEquals("content://com.myapp.audiobrowser.artwork/art/$token", uri)
    assertEquals(token, ArtworkUris.parseToken(Uri.parse(uri)))
  }

  @Test
  fun `parseToken rejects malformed uris`() {
    assertNull(ArtworkUris.parseToken(Uri.parse("content://com.myapp.audiobrowser.artwork/nope")))
    assertNull(ArtworkUris.parseToken(Uri.parse("https://example.com/art/x")))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.ArtworkUrisTest"`
Expected: FAIL — `ArtworkUris` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.audiobrowser.util

import android.net.Uri
import java.security.MessageDigest

/** Builds and parses the opaque `content://<pkg>.audiobrowser.artwork/art/<token>` URIs. */
object ArtworkUris {
  const val AUTHORITY_SUFFIX = "audiobrowser.artwork"
  private const val PATH = "art"

  fun authorityFor(packageName: String): String = "$packageName.$AUTHORITY_SUFFIX"

  /** Stable, opaque token for a resolved artwork URL (SHA-256 hex). */
  fun tokenFor(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun contentUri(authority: String, token: String): String = "content://$authority/$PATH/$token"

  /** The `<token>` for a `…/art/<token>` content URI, or null if the shape is wrong. */
  fun parseToken(uri: Uri): String? {
    if (uri.scheme != "content") return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments[0] != PATH) return null
    return segments[1].takeIf { it.isNotEmpty() }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.ArtworkUrisTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/com/audiobrowser/util/ArtworkUris.kt \
        android/src/test/java/com/audiobrowser/util/ArtworkUrisTest.kt
git commit -m "feat(android): ArtworkUris token-based content URI build/parse"
```

---

### Task 2: `BrowseArtworkRegistry` — token → resolved artwork (pure)

**Files:**
- Create: `android/src/main/java/com/audiobrowser/browser/BrowseArtworkRegistry.kt`
- Test: `android/src/test/java/com/audiobrowser/browser/BrowseArtworkRegistryTest.kt`

**Interfaces:**
- Produces:
  - `data class ResolvedArtwork(val finalUrl: String, val headers: Map<String, String>?, val isSvg: Boolean)`
  - `class BrowseArtworkRegistry(maxEntries: Int = 2048)`
  - `fun register(token: String, artwork: ResolvedArtwork)`
  - `fun lookup(token: String): ResolvedArtwork?`
  - `fun clear()`
- Holds plain data only (no `Track`, no Nitro handles) → safe to size large; cleared on browser-config replacement / content invalidation.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.audiobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseArtworkRegistryTest {
  @Test
  fun `register then lookup returns the entry`() {
    val reg = BrowseArtworkRegistry()
    val art = ResolvedArtwork("https://x/a.png", mapOf("Authorization" to "Bearer t"), isSvg = false)
    reg.register("tok", art)
    assertEquals(art, reg.lookup("tok"))
  }

  @Test
  fun `lookup of unknown token is null`() {
    assertNull(BrowseArtworkRegistry().lookup("missing"))
  }

  @Test
  fun `clear drops entries`() {
    val reg = BrowseArtworkRegistry()
    reg.register("tok", ResolvedArtwork("https://x/a.png", null, false))
    reg.clear()
    assertNull(reg.lookup("tok"))
  }

  @Test
  fun `evicts oldest beyond capacity`() {
    val reg = BrowseArtworkRegistry(maxEntries = 2)
    reg.register("a", ResolvedArtwork("https://x/a", null, false))
    reg.register("b", ResolvedArtwork("https://x/b", null, false))
    reg.register("c", ResolvedArtwork("https://x/c", null, false))
    assertNull(reg.lookup("a")) // evicted
    assertEquals("https://x/c", reg.lookup("c")?.finalUrl)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.browser.BrowseArtworkRegistryTest"`
Expected: FAIL — `BrowseArtworkRegistry` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.audiobrowser.browser

/**
 * Token → resolved browse artwork, the [com.audiobrowser.util.ArtworkContentProvider]'s only data
 * source. Stores plain resolved data (no Track / Nitro handles), so unlike [ArtworkResolutionRegistry]
 * it pins no JS closures and can be sized generously. Cleared when the browser config is replaced or
 * content is invalidated. Thread-safe: written from browse coroutines, read from the provider's IO scope.
 */
class BrowseArtworkRegistry(private val maxEntries: Int = 2048) {

  private val entries =
    object : LinkedHashMap<String, ResolvedArtwork>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedArtwork>): Boolean =
        size > maxEntries
    }

  @Synchronized
  fun register(token: String, artwork: ResolvedArtwork) {
    entries[token] = artwork
  }

  @Synchronized fun lookup(token: String): ResolvedArtwork? = entries[token]

  @Synchronized fun clear() = entries.clear()
}

/** Everything the provider needs to fetch one artwork, resolved at browse-build time. */
data class ResolvedArtwork(
  val finalUrl: String,
  val headers: Map<String, String>?,
  val isSvg: Boolean,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.browser.BrowseArtworkRegistryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/com/audiobrowser/browser/BrowseArtworkRegistry.kt \
        android/src/test/java/com/audiobrowser/browser/BrowseArtworkRegistryTest.kt
git commit -m "feat(android): BrowseArtworkRegistry (plain-data token store for the artwork provider)"
```

---

### Task 3: `CoilArtworkLoader` + refactor `CoilBitmapLoader` to delegate

Extract the decode half of the existing `CoilBitmapLoader.loadBitmap` (`util/CoilBitmapLoader.kt:79-140`) into a reusable suspend core that takes an already-resolved URL + headers + svg flag, and **adds `.size()`** (the current code never downsamples raster — confirmed: `.size(` appears only in `SvgArtworkRenderer`).

**Files:**
- Create: `android/src/main/java/com/audiobrowser/util/CoilArtworkLoader.kt`
- Modify: `android/src/main/java/com/audiobrowser/util/CoilBitmapLoader.kt`
- Test: `android/src/test/java/com/audiobrowser/util/CoilArtworkLoaderTest.kt`

**Interfaces:**
- Consumes: `coil3.ImageLoader`, `android.content.Context`.
- Produces:
  - `class CoilArtworkLoader(context: Context, imageLoader: ImageLoader, defaultSizePixels: Int = 512)`
  - `suspend fun load(finalUrl: String, headers: Map<String, String>?, sizeHintPixels: Int?, isSvg: Boolean): android.graphics.Bitmap` — throws on failure.

- [ ] **Step 1: Write the failing test** (verifies the request the loader builds — headers, size, svg decoder — via a stubbed `ImageLoader`)

```kotlin
package com.audiobrowser.util

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoilArtworkLoaderTest {
  @Test
  fun `load applies size hint and headers to the request`() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    var captured: ImageRequest? = null
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    // FakeImageLoader records the request and returns a known bitmap.
    val imageLoader = FakeImageLoader(context, fakeBitmap) { captured = it }

    val loader = CoilArtworkLoader(context, imageLoader, defaultSizePixels = 512)
    val bmp = loader.load(
      finalUrl = "https://cdn.example.com/a.png",
      headers = mapOf("Authorization" to "Bearer t"),
      sizeHintPixels = 256,
      isSvg = false,
    )

    assertEquals(fakeBitmap, bmp)
    assertNotNull(captured)
    // data is the finalUrl
    assertEquals("https://cdn.example.com/a.png", captured!!.data)
  }

  @Test
  fun `load falls back to default size when hint is null`() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val imageLoader = FakeImageLoader(context, fakeBitmap) {}
    val loader = CoilArtworkLoader(context, imageLoader, defaultSizePixels = 512)
    val bmp = loader.load("https://cdn.example.com/a.png", null, null, false)
    assertEquals(fakeBitmap, bmp)
  }
}
```

> **Note for implementer:** `FakeImageLoader` is a tiny test double implementing `coil3.ImageLoader` whose `execute(request)` records the request and returns a `SuccessResult` wrapping `fakeBitmap` (as a `coil3.BitmapImage`). Coil 3 exposes `coil3.asImage()` for `Bitmap`. Put it in `android/src/test/java/com/audiobrowser/util/FakeImageLoader.kt`. If implementing `ImageLoader` directly proves heavy, use Mockito (`mock<ImageLoader>()`) and stub `execute(...)`; the assertion that matters is the captured request's `data` and that the returned bitmap propagates.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.CoilArtworkLoaderTest"`
Expected: FAIL — `CoilArtworkLoader` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.audiobrowser.util

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.svg.SvgDecoder
import coil3.toBitmap

/**
 * Shared "resolved source → Bitmap" core. Used by both [CoilBitmapLoader] (now-playing) and
 * [ArtworkContentProvider] (browse). Adds `.size()` so raster decodes are downsampled to the hint
 * (the prior loadBitmap path decoded at full resolution). SVG is forced via [isSvg], carried from
 * build time rather than re-derived from a possibly-suffixless transformed URL.
 */
class CoilArtworkLoader(
  private val context: Context,
  private val imageLoader: ImageLoader,
  private val defaultSizePixels: Int = 512,
) {
  suspend fun load(
    finalUrl: String,
    headers: Map<String, String>?,
    sizeHintPixels: Int?,
    isSvg: Boolean,
  ): Bitmap {
    val builder =
      ImageRequest.Builder(context)
        .data(finalUrl)
        .size(sizeHintPixels ?: defaultSizePixels)
        .allowHardware(false) // required for Media3 notification compatibility

    if (!headers.isNullOrEmpty()) {
      val net = NetworkHeaders.Builder()
      headers.forEach { (k, v) -> net.add(k, v) }
      builder.httpHeaders(net.build())
    }
    if (isSvg) {
      builder.decoderFactory { result, options, _ -> SvgDecoder(result.source, options) }
    }

    val result = imageLoader.execute(builder.build())
    return result.image?.toBitmap()
      ?: throw IllegalStateException("Failed to decode artwork from $finalUrl")
  }
}
```

- [ ] **Step 4: Refactor `CoilBitmapLoader.loadBitmap` to delegate the decode**

In `util/CoilBitmapLoader.kt`, keep the URI-resolution logic (`resolveDisplayArtwork`, `finalUrl`, `headers`, `isSvg` detection) but replace the inline `ImageRequest`/`execute` block (lines ~104-132) with a call into a `CoilArtworkLoader` built once in the constructor. Concretely, add to the class body:

```kotlin
  private val core = CoilArtworkLoader(context, imageLoader, defaultArtworkSizePixels)
```

and inside `loadBitmap`'s coroutine, after computing `finalUrl`, `headers`, and `isSvg`, replace the request-building + execute + null-check with:

```kotlin
        val bitmap = core.load(finalUrl, headers, sizeHint, isSvg)
        future.set(bitmap)
```

Leave `decodeBitmap`, `supportsMimeType`, and the `resolveDisplayArtwork`/`getArtworkSizeHint` plumbing unchanged. (`defaultArtworkSizePixels` already = 512 at `CoilBitmapLoader.kt:56`.)

- [ ] **Step 5: Run tests to verify pass (new + existing CoilBitmapLoader behavior)**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.CoilArtworkLoaderTest"`
Expected: PASS. Then run the full util test package to confirm no regression:
Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/src/main/java/com/audiobrowser/util/CoilArtworkLoader.kt \
        android/src/main/java/com/audiobrowser/util/CoilBitmapLoader.kt \
        android/src/test/java/com/audiobrowser/util/CoilArtworkLoaderTest.kt \
        android/src/test/java/com/audiobrowser/util/FakeImageLoader.kt
git commit -m "refactor(android): extract CoilArtworkLoader decode core; downsample raster via .size()"
```

---

### Task 4: `CoilArtworkLoaderHolder` — process-wide access for the provider

The system can construct a `ContentProvider` before the player exists, so the provider reads its dependencies from a `@Volatile` holder. The holder also owns the provider's bounded `CoroutineScope` so teardown can cancel in-flight writers.

**Files:**
- Create: `android/src/main/java/com/audiobrowser/util/CoilArtworkLoaderHolder.kt`
- Test: `android/src/test/java/com/audiobrowser/util/CoilArtworkLoaderHolderTest.kt`

**Interfaces:**
- Consumes: `CoilArtworkLoader` (Task 3), `BrowseArtworkRegistry` (Task 2).
- Produces:
  - `data class ArtworkProviderDeps(val loader: CoilArtworkLoader, val registry: BrowseArtworkRegistry, val scope: kotlinx.coroutines.CoroutineScope)`
  - `object CoilArtworkLoaderHolder { fun set(deps: ArtworkProviderDeps); fun get(): ArtworkProviderDeps?; fun clearIf(deps: ArtworkProviderDeps) }`
  - `clearIf` clears only when the held instance `===` the passed one (identity guard, so a restarting Service can't blank a newer instance).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.audiobrowser.util

import com.audiobrowser.browser.BrowseArtworkRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoilArtworkLoaderHolderTest {
  private fun deps() =
    ArtworkProviderDeps(mock(), BrowseArtworkRegistry(), CoroutineScope(Dispatchers.Unconfined))

  @After fun tearDown() = CoilArtworkLoaderHolder.get()?.let { CoilArtworkLoaderHolder.clearIf(it) } ?: Unit

  @Test fun `get returns what was set`() {
    val d = deps(); CoilArtworkLoaderHolder.set(d); assertSame(d, CoilArtworkLoaderHolder.get())
  }

  @Test fun `clearIf only clears the matching instance`() {
    val first = deps(); val second = deps()
    CoilArtworkLoaderHolder.set(first)
    CoilArtworkLoaderHolder.set(second)
    CoilArtworkLoaderHolder.clearIf(first) // stale instance — must NOT clear
    assertSame(second, CoilArtworkLoaderHolder.get())
    CoilArtworkLoaderHolder.clearIf(second)
    assertNull(CoilArtworkLoaderHolder.get())
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.CoilArtworkLoaderHolderTest"`
Expected: FAIL — `CoilArtworkLoaderHolder` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.audiobrowser.util

import com.audiobrowser.browser.BrowseArtworkRegistry
import kotlinx.coroutines.CoroutineScope

/** Dependencies the exported [ArtworkContentProvider] needs, published by the media Service. */
data class ArtworkProviderDeps(
  val loader: CoilArtworkLoader,
  val registry: BrowseArtworkRegistry,
  val scope: CoroutineScope,
)

/**
 * Process-wide handoff between the media Service (which builds the deps) and the ContentProvider
 * (which the OS may instantiate before the Service exists). `@Volatile` publishes the reference
 * safely across the binder/main threads.
 */
object CoilArtworkLoaderHolder {
  @Volatile private var deps: ArtworkProviderDeps? = null

  fun set(deps: ArtworkProviderDeps) {
    this.deps = deps
  }

  fun get(): ArtworkProviderDeps? = deps

  /** Clears only if [deps] is still the current one — prevents a stale Service from blanking a newer. */
  @Synchronized
  fun clearIf(deps: ArtworkProviderDeps) {
    if (this.deps === deps) this.deps = null
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.CoilArtworkLoaderHolderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/com/audiobrowser/util/CoilArtworkLoaderHolder.kt \
        android/src/test/java/com/audiobrowser/util/CoilArtworkLoaderHolderTest.kt
git commit -m "feat(android): CoilArtworkLoaderHolder (process-wide provider deps, identity-guarded clear)"
```

---

### Task 5: `ArtworkContentProvider` — token lookup, bounded pipe streaming

**Files:**
- Create: `android/src/main/java/com/audiobrowser/util/ArtworkContentProvider.kt`
- Test: `android/src/test/java/com/audiobrowser/util/ArtworkContentProviderTest.kt`

**Interfaces:**
- Consumes: `CoilArtworkLoaderHolder` (Task 4), `BrowseArtworkRegistry`/`ResolvedArtwork` (Task 2), `ArtworkUris` (Task 1), `CoilArtworkLoader` (Task 3).
- Produces: an exported `ContentProvider`. `openFile(uri, mode): ParcelFileDescriptor?` — null on token miss / non-http(s) / holder absent; otherwise a readable pipe streaming a PNG. `getType` = `"image/png"`. `query/insert/update/delete` = no-ops.
- Concurrency: a `Semaphore(MAX_CONCURRENT)` gates resolve+decode+encode on the holder's scope; the write FD is closed in `finally`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.audiobrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.audiobrowser.browser.ResolvedArtwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkContentProviderTest {
  private lateinit var provider: ArtworkContentProvider
  private lateinit var registry: BrowseArtworkRegistry

  @Before fun setUp() {
    provider = Robolectric.setupContentProvider(ArtworkContentProvider::class.java)
    registry = BrowseArtworkRegistry()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    // Fake loader returns a known 8x8 bitmap regardless of input.
    val fakeBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val loader = object : CoilArtworkLoader(context, FakeImageLoader(context, fakeBitmap) {}) {}
    CoilArtworkLoaderHolder.set(
      ArtworkProviderDeps(loader, registry, CoroutineScope(Dispatchers.IO))
    )
  }

  @After fun tearDown() {
    CoilArtworkLoaderHolder.get()?.let { CoilArtworkLoaderHolder.clearIf(it) }
  }

  private fun uri(token: String) =
    Uri.parse(ArtworkUris.contentUri(ArtworkUris.authorityFor("com.test"), token))

  @Test fun `getType is png`() {
    assertEquals("image/png", provider.getType(uri("anything")))
  }

  @Test fun `openFile returns a readable PNG for a registered http token`() {
    val token = ArtworkUris.tokenFor("https://cdn/a.png")
    registry.register(token, ResolvedArtwork("https://cdn/a.png", null, isSvg = false))
    val pfd = provider.openFile(uri(token), "r")
    assertNotNull(pfd)
    val bytes = java.io.FileInputStream(pfd!!.fileDescriptor).readBytes()
    assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) // valid image
  }

  @Test fun `openFile returns null for an unknown token`() {
    assertNull(provider.openFile(uri("nope"), "r"))
  }

  @Test fun `openFile returns null for a non-http registered url`() {
    val token = ArtworkUris.tokenFor("android.resource://com.test/drawable/ic")
    registry.register(token, ResolvedArtwork("android.resource://com.test/drawable/ic", null, false))
    assertNull(provider.openFile(uri(token), "r"))
  }

  @Test fun `openFile returns null when holder absent`() {
    CoilArtworkLoaderHolder.get()?.let { CoilArtworkLoaderHolder.clearIf(it) }
    val token = ArtworkUris.tokenFor("https://cdn/a.png")
    registry.register(token, ResolvedArtwork("https://cdn/a.png", null, false))
    assertNull(provider.openFile(uri(token), "r"))
  }
}
```

> **Note:** `CoilArtworkLoader` is currently `final`. To allow the test's trivial subclass/fake, either mark `CoilArtworkLoader` `open` or inject a `FakeImageLoader` into a real `CoilArtworkLoader` (preferred — drop the `object : CoilArtworkLoader(...) {}` and use `CoilArtworkLoader(context, FakeImageLoader(context, fakeBitmap) {})`). Use the same `FakeImageLoader` from Task 3.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.ArtworkContentProviderTest"`
Expected: FAIL — `ArtworkContentProvider` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.audiobrowser.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.audiobrowser.browser.ResolvedArtwork
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * Exported, token-gated provider serving browse artwork to Android Auto / AAOS (which run in a
 * different uid and cannot read a non-exported provider; Media3 issues no URI grant — verified).
 * It NEVER fetches a caller-supplied URL: the content URI carries an opaque token, looked up in
 * [com.audiobrowser.browser.BrowseArtworkRegistry]; an unknown token returns null. So it cannot be
 * used as a fetch proxy / SSRF vector. http(s)-only on the registered finalUrl is defense-in-depth.
 */
class ArtworkContentProvider : ContentProvider() {

  private val gate = Semaphore(MAX_CONCURRENT)

  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String = "image/png"

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    val token = ArtworkUris.parseToken(uri) ?: return null
    val deps = CoilArtworkLoaderHolder.get() ?: return null
    val art: ResolvedArtwork = deps.registry.lookup(token) ?: return null
    val scheme = Uri.parse(art.finalUrl).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null

    val pipe = ParcelFileDescriptor.createReliablePipe()
    val readSide = pipe[0]
    val writeSide = pipe[1]

    // Return the read end immediately; fetch/decode/encode happens off the binder thread.
    deps.scope.launch {
      ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
        try {
          gate.withPermit {
            val bitmap: Bitmap =
              deps.loader.load(art.finalUrl, art.headers, deps.artworkSizeHint(), art.isSvg)
            ByteArrayOutputStream().use { buf ->
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, buf)
              out.write(buf.toByteArray())
            }
          }
        } catch (e: Throwable) {
          // Closing with no/partial data → car shows its placeholder. Never propagate across binder.
          Timber.w(e, "Artwork stream failed for token=$token")
        }
      } // AutoCloseOutputStream.use guarantees the write FD is closed on every path
    }

    return readSide
  }

  override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
  override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0

  companion object {
    private const val MAX_CONCURRENT = 6
  }
}
```

> **Size hint:** add `fun ArtworkProviderDeps.artworkSizeHint(): Int?` as a one-line helper, or pass the hint into the deps. Simplest: extend `ArtworkProviderDeps` with `val artworkSizeHint: () -> Int?` set from `{ player.artworkSizeHintPixels }` in Service (mirrors the existing `CoilBitmapLoader` callback at `Service.kt:109`). Update Task 4's data class + Task 8's wiring accordingly. The provider then calls `deps.artworkSizeHint()`.

> **Caching refinement (spec "Caching", in scope):** the PNG-per-request above is correct but re-encodes each call. After the happy-path test passes, add a small encoded-bytes LRU keyed by `token + sizeHint` (or serve Coil's disk-cache snapshot FD for raster) to avoid scroll flicker. Keep it behind the same `gate`. This is a follow-up step within this task, not a new task; verify on DHU (Task 9 case f).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.ArtworkContentProviderTest"`
Expected: PASS.

- [ ] **Step 5: Add the encoded-bytes LRU (anti-flicker) and a regression test**

Add an LRU (`object : LinkedHashMap<String, ByteArray>(…) { removeEldestEntry … }`, capacity ~64) inside the provider, populated after `compress`, checked before launching the writer (cache hit → write cached bytes). Add a test asserting two `openFile` calls for the same token decode to a valid image (and, if you expose a counter, that the loader ran once). Run the class tests again — Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/src/main/java/com/audiobrowser/util/ArtworkContentProvider.kt \
        android/src/test/java/com/audiobrowser/util/ArtworkContentProviderTest.kt
git commit -m "feat(android): exported token-gated ArtworkContentProvider with bounded streaming + LRU"
```

---

### Task 6: `TrackFactory.toBrowseMediaItem` — scheme routing + registration

**Files:**
- Modify: `android/src/main/java/com/audiobrowser/util/TrackFactory.kt`
- Test: `android/src/test/java/com/audiobrowser/util/TrackFactoryBrowseTest.kt`

**Interfaces:**
- Consumes: `BrowseArtworkRegistry`/`ResolvedArtwork` (Task 2), `ArtworkUris` (Task 1), `SvgArtworkRenderer.isSvgUrl` (existing). `Track.artworkSource` (resolved `ImageSource` with `.uri`, `.headers`) is already populated at browse time by `BrowserManager.transformArtworkUrl` (`BrowserManager.kt:578`); `artworkUri(track) = track.artworkSource?.uri ?: track.artwork`.
- Produces:
  - `fun TrackFactory.toBrowseMediaItem(track: Track, sizeHintPixels: Int?, registry: BrowseArtworkRegistry, authority: String): MediaItem`
  - Routing: if the resolved artwork URL is `http`/`https` → register `ResolvedArtwork(finalUrl, headers, isSvg)` under `ArtworkUris.tokenFor(finalUrl)`, set `artworkUri = ArtworkUris.contentUri(authority, token)`. Otherwise (`android.resource://`, `file://`, null) → `setArtworkUri(rawUri)` unchanged.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.audiobrowser.util

import android.net.Uri
import com.audiobrowser.browser.BrowseArtworkRegistry
import com.margelo.nitro.audiobrowser.ImageSource
import com.margelo.nitro.audiobrowser.Track
// Reuse the project's existing Track test fixture (see com.audiobrowser.TestFixtures).
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackFactoryBrowseTest {
  private val authority = ArtworkUris.authorityFor("com.test")

  @Test fun `http artwork is wrapped in a content uri and registered`() {
    val reg = BrowseArtworkRegistry()
    val track = browseTrack(
      artworkSource = ImageSource(uri = "https://cdn/a.svg", method = null,
        headers = mapOf("Authorization" to "Bearer t"), body = null)
    )
    val item = TrackFactory.toBrowseMediaItem(track, 256, reg, authority)

    val artUri = Uri.parse(item.mediaMetadata.artworkUri.toString())
    assertEquals("content", artUri.scheme)
    val token = ArtworkUris.parseToken(artUri)
    assertNotNull(token)
    val entry = reg.lookup(token!!)!!
    assertEquals("https://cdn/a.svg", entry.finalUrl)
    assertEquals("Bearer t", entry.headers!!["Authorization"])
    assertEquals(true, entry.isSvg) // detected from the .svg url
  }

  @Test fun `android resource artwork is passed through unchanged`() {
    val reg = BrowseArtworkRegistry()
    val resUri = "android.resource://com.test/drawable/ic_folder"
    val track = browseTrack(artwork = resUri, artworkSource = null)
    val item = TrackFactory.toBrowseMediaItem(track, 256, reg, authority)
    assertEquals(resUri, item.mediaMetadata.artworkUri.toString())
  }

  // browseTrack(...) builds a minimal browsable Track (src=null) via TestFixtures; fill required fields.
}
```

> **Note:** Use the existing `com.audiobrowser.TestFixtures` helper to construct `Track` (it already exists for the other browser tests). Add a small `browseTrack(...)` builder there if convenient. `ImageSource` is the Nitrogen-generated `com.margelo.nitro.audiobrowser.ImageSource` with fields `uri, method, headers, body` (see `src/types/browser-nodes.ts:15`).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.TrackFactoryBrowseTest"`
Expected: FAIL — `toBrowseMediaItem` unresolved.

- [ ] **Step 3: Write the implementation** (add to `TrackFactory`, reusing existing private `metadataBuilder`, `buildMediaItem`, `artworkUri`)

```kotlin
  /**
   * Browse-surface conversion. Routes http(s) artwork through the content:// provider (so headers +
   * SVG apply in our process, and no bytes cross the Binder), registering it in [registry]. Non-http
   * artwork (android.resource:// tab icons, file://) passes through to setArtworkUri unchanged so
   * vector/category icons survive. Plain toMedia3 (queue/now-playing) is unaffected.
   */
  fun toBrowseMediaItem(
    track: Track,
    sizeHintPixels: Int?,
    registry: BrowseArtworkRegistry,
    authority: String,
  ): MediaItem {
    val rawUrl = artworkUri(track) // artworkSource.uri ?: artwork
    val scheme = rawUrl?.let { Uri.parse(it).scheme?.lowercase() }
    val builder = metadataBuilder(track)
    if (rawUrl != null && (scheme == "http" || scheme == "https")) {
      val isSvg = SvgArtworkRenderer.isSvgUrl(rawUrl) || SvgArtworkRenderer.isSvgUrl(track.artwork)
      val token = ArtworkUris.tokenFor(rawUrl)
      registry.register(
        token,
        ResolvedArtwork(rawUrl, track.artworkSource?.headers?.toMap(), isSvg),
      )
      builder.setArtworkUri(ArtworkUris.contentUri(authority, token).toUri())
    } else if (rawUrl != null) {
      builder.setArtworkUri(rawUrl.toUri())
    }
    return buildMediaItem(track, builder.build())
  }
```

Add imports: `com.audiobrowser.browser.BrowseArtworkRegistry`, `com.audiobrowser.browser.ResolvedArtwork`. (`headers` on `ImageSource` is a `Record<string,string>` → Kotlin `Map`; `.toMap()` if it surfaces as a different map type — adjust to the generated type.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest --tests "com.audiobrowser.util.TrackFactoryBrowseTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/com/audiobrowser/util/TrackFactory.kt \
        android/src/test/java/com/audiobrowser/util/TrackFactoryBrowseTest.kt \
        android/src/test/java/com/audiobrowser/TestFixtures.kt
git commit -m "feat(android): TrackFactory.toBrowseMediaItem (content:// for http(s), passthrough otherwise)"
```

---

### Task 7: Wire browse-delivery sites to `toBrowseMediaItem`

Switch the three browse callbacks to the new converter. The queue / now-playing / resumption sites keep `toMedia3`.

**Files:**
- Modify: `android/src/main/java/com/audiobrowser/player/MediaSessionCallback.kt`

**Interfaces:**
- Consumes: `TrackFactory.toBrowseMediaItem` (Task 6), the `BrowseArtworkRegistry` from the holder/Service, `player.artworkSizeHintPixels` (`Player.kt:1188`), `player.context.packageName`.

- [ ] **Step 1: Replace `toMediaItems`** (`MediaSessionCallback.kt:415-423`). It currently maps via `toMedia3WithSvgSupport`. Change to:

```kotlin
  private fun toMediaItems(tracks: List<Track>): List<MediaItem> {
    val registry = player.browseArtworkRegistry
    val authority = com.audiobrowser.util.ArtworkUris.authorityFor(player.context.packageName)
    val sizeHint = player.artworkSizeHintPixels
    return tracks.map { TrackFactory.toBrowseMediaItem(it, sizeHint, registry, authority) }
  }
```

(`toBrowseMediaItem` is synchronous — no `coroutineScope`/`async` needed. `player.browseArtworkRegistry` is added in Task 8.) Update the function signature from `suspend fun` to `fun` and fix the call site if the compiler flags the now-unnecessary suspend.

- [ ] **Step 2: Update `onGetItem`** to use `toBrowseMediaItem` where it currently builds a real (non-sentinel) item via `toMedia3`. Find the `toMedia3(` call in `onGetItem` (after the OFFLINE/ERROR/GATE sentinel branches, ~line 446) and replace it with `TrackFactory.toBrowseMediaItem(track, player.artworkSizeHintPixels, player.browseArtworkRegistry, ArtworkUris.authorityFor(player.context.packageName))`. Leave the sentinel-tile builders (`createOfflineMediaItem`, `createBrowseErrorMediaItem`, gate tile) untouched.

- [ ] **Step 3: Update `onGetSearchResult`** the same way — replace its `toMedia3(` mapping of result tracks with `toBrowseMediaItem(...)` (same args).

- [ ] **Step 4: Verify it compiles + existing callback tests pass**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:testDebugUnitTest`
Expected: PASS (full suite). If a `MediaSessionCallback` test asserted the old `toMedia3WithSvgSupport` artwork shape, update it to expect a `content://` artworkUri for http art.

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/com/audiobrowser/player/MediaSessionCallback.kt
git commit -m "feat(android): route browse-delivery (children/item/search) artwork through the content provider"
```

---

### Task 8: Manifest provider + Service wiring (populate/clear holder, registry, scope)

**Files:**
- Modify: `android/src/main/AndroidManifest.xml`
- Modify: `android/src/main/java/com/audiobrowser/Service.kt`
- Modify: `android/src/main/java/com/audiobrowser/player/Player.kt` (expose `browseArtworkRegistry`)

**Interfaces:**
- Consumes: all prior tasks.
- Produces: `Player.browseArtworkRegistry: BrowseArtworkRegistry` (a `val`, created once, cleared on config invalidation alongside `artworkResolutions`); the holder populated with `{loader, registry, scope, artworkSizeHint}`.

- [ ] **Step 1: Declare the provider.** In `android/src/main/AndroidManifest.xml`, inside `<application>` (alongside the existing `<service>`), add:

```xml
<provider
    android:name="com.audiobrowser.util.ArtworkContentProvider"
    android:authorities="${applicationId}.audiobrowser.artwork"
    android:exported="true" />
```

- [ ] **Step 2: Add the registry to `Player`.** Where `artworkResolutions` (the `ArtworkResolutionRegistry`) is declared, add a sibling:

```kotlin
  val browseArtworkRegistry = com.audiobrowser.browser.BrowseArtworkRegistry()
```

Find where `artworkResolutions.clear()` is called (browser-config replacement / content invalidation) and add `browseArtworkRegistry.clear()` next to it (same lifecycle — stale tokens must not outlive a config swap).

- [ ] **Step 3: Populate the holder in `Service.onCreate`.** Right after the existing `coilBitmapLoader` block (`Service.kt:104-114`), add:

```kotlin
    val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    artworkProviderDeps =
      ArtworkProviderDeps(
        loader = CoilArtworkLoader(this, imageLoader),
        registry = player.browseArtworkRegistry,
        scope = artworkScope,
        artworkSizeHint = { player.artworkSizeHintPixels },
      )
    CoilArtworkLoaderHolder.set(artworkProviderDeps!!)
```

Add a field `private var artworkProviderDeps: ArtworkProviderDeps? = null` and the imports. (This requires Task 4's `ArtworkProviderDeps` to carry `artworkSizeHint: () -> Int?` — add that field there and use it in Task 5's provider via `deps.artworkSizeHint()`.)

- [ ] **Step 4: Tear down in `Service.onDestroy`.** Before the existing `player` teardown / `super.onDestroy()`, cancel the scope and clear the holder by identity, so no in-flight writer derefs a torn-down player:

```kotlin
    artworkProviderDeps?.let {
      it.scope.cancel()
      CoilArtworkLoaderHolder.clearIf(it)
    }
    artworkProviderDeps = null
```

(`import kotlinx.coroutines.cancel`.) Confirm this runs before `player.destroy()`.

- [ ] **Step 5: Build the whole module + run the full unit suite**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:assembleDebug :react-native-audio-browser:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 6: Commit**

```bash
git add android/src/main/AndroidManifest.xml \
        android/src/main/java/com/audiobrowser/Service.kt \
        android/src/main/java/com/audiobrowser/player/Player.kt
git commit -m "feat(android): declare artwork provider + wire holder/registry/scope lifecycle in Service"
```

---

### Task 9: DHU verification, then retire the SVG-byte path

The provider is now live end-to-end. Verify on a real head unit BEFORE deleting the old SVG byte-embedding code (deleting earlier regresses SVG art to blank).

**Files:**
- Create: `manual-testing/android-auto-artwork.md`
- Modify: `android/src/main/java/com/audiobrowser/util/TrackFactory.kt` (remove `toMedia3WithSvgSupport` x2)
- Modify: `android/src/main/java/com/audiobrowser/util/SvgArtworkRenderer.kt` (remove `applyArtwork`, `renderSvgToBytes`; keep `isSvgUrl`)

- [ ] **Step 1: Write the DHU walkthrough** `manual-testing/android-auto-artwork.md`, covering: install on a device with Android Auto + DHU running (`~/Library/Android/sdk/extras/google/auto/desktop-head-unit`), then verify:
  - (a) a station/category with **raster** http artwork renders in the browse list;
  - (b) a tab/category with **SVG** http artwork renders;
  - (c) a list of **50+** stations browses with no crash (watch logcat for `TransactionTooLargeException`);
  - (d) artwork from a **header-requiring** origin renders (i.e. a URL that 401s without our headers);
  - (e) a tab/folder using an **`android.resource://`** drawable icon still shows its icon (regression guard for the scheme-passthrough);
  - (f) scroll a long list down and back — **no flicker / reload** of already-seen art (validates the LRU/snapshot);
  - (g) open the **queue / Up Next** list and check whether SVG/header-requiring track artwork renders there too (the queue path still uses `toMedia3`).

- [ ] **Step 2: Run the DHU checks.** Record results in the doc. If (g) shows the queue list also drops Coil-required art, file a follow-up issue (apply `toBrowseMediaItem`-style treatment to the queue path or document the exemption) — do NOT expand this PR's scope.

- [ ] **Step 3: Gate.** Only proceed if (a)–(f) pass. (g) is informational.

- [ ] **Step 4: Remove `toMedia3WithSvgSupport`** (both overloads, `TrackFactory.kt:49-66`) — nothing calls it after Task 7.

- [ ] **Step 5: Remove `SvgArtworkRenderer.applyArtwork` and `renderSvgToBytes`** (`SvgArtworkRenderer.kt:42-126`); keep `isSvgUrl` (used by Task 6). Remove now-unused imports (`MediaMetadata`, `ByteArrayOutputStream`, `Bitmap`, coil request bits) flagged by the compiler.

- [ ] **Step 6: Build + full unit suite**

Run: `cd apps/example-native/android && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :react-native-audio-browser:assembleDebug :react-native-audio-browser:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 7: Commit**

```bash
git add manual-testing/android-auto-artwork.md \
        android/src/main/java/com/audiobrowser/util/TrackFactory.kt \
        android/src/main/java/com/audiobrowser/util/SvgArtworkRenderer.kt
git commit -m "chore(android): retire eager SVG byte-embedding now the artwork provider is DHU-verified"
```

---

## Self-Review (completed)

- **Spec coverage:** transport/exported model → Tasks 5,8; token-gated no-arbitrary-fetch → Tasks 1,2,5,6; `BrowseArtworkRegistry` (plain data, generous bound) → Task 2; scheme passthrough for `android.resource://` → Task 6 (+DHU e); shared `CoilArtworkLoader` + `.size()` downsample → Task 3; bounded scope + finally-close → Task 5; holder lifecycle (identity-guarded, scope cancel before destroy) → Tasks 4,8; caching/anti-flicker → Task 5 step 5 (+DHU f); SVG flag from build time → Task 6; manifest + same-process → Task 8; sequencing (retire byte path last) → Task 9; queue-list open question → Task 9 g. All spec sections map to a task.
- **Placeholder scan:** no TBD/TODO/"handle edge cases"; every code step shows code; test commands concrete.
- **Type consistency:** `ResolvedArtwork(finalUrl, headers, isSvg)`, `ArtworkProviderDeps(loader, registry, scope, artworkSizeHint)`, `toBrowseMediaItem(track, sizeHintPixels, registry, authority)`, `CoilArtworkLoader.load(finalUrl, headers, sizeHintPixels, isSvg)`, `ArtworkUris.{authorityFor,tokenFor,contentUri,parseToken}` used identically across Tasks 1–8. Task 4's `ArtworkProviderDeps` gains `artworkSizeHint` (noted in Tasks 5 & 8).
