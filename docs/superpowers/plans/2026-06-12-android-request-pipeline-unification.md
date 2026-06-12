# Android Request Pipeline Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify Android's four hand-rolled request pipelines (browse, search, media, artwork) behind the deep `buildApiRequest`/layer shape iOS already has, move media+artwork URL resolution into a BrowserManager-owned module, and make display-time artwork resolution Track-first like iOS — then dedupe the one merge-helper duplication iOS itself has.

**Architecture:** Port iOS's `buildApiRequest(kind:route:path:params:initialQuery:)` into Kotlin `BrowserManager` (kills the browse/search fork). Collapse `RequestConfigBuilder`'s three transform-wins-else-merge overloads into one private semantics helper. Create `browser/BrowserUrlResolution.kt` (extension functions on `BrowserManager` — the Kotlin analog of `ios/Browser/BrowserManager+URLResolution.swift`) owning `resolveMediaUrl` + `resolveArtworkUrl`. Replace `CoilBitmapLoader`'s URL-first display-time transform with Track-first re-resolution via a new `ArtworkResolutionRegistry` (uri → how it was resolved), eliminating the double-transform of already-resolved artwork URIs.

**Tech Stack:** Kotlin (library `android/`), Media3, Coil, Nitro-generated types (`com.margelo.nitro.audiobrowser.*`), JUnit4 + kotlinx-coroutines-test on JVM. Swift only in Task 8.

**Worktree:** All work happens in `/Users/puckey/rg/_libraries/rnab-wt-request-pipeline` (branch `request-pipeline`). All paths below are relative to that root.

---

## Hard constraints (read first)

1. **Nitro `Promise` cannot be constructed in JVM unit tests** (`Promise.initHybrid()` is JNI). Tests must use **static configs only** (no `transform`/`resolve` callbacks). Transform/resolve *behavior* is covered by the Swift suite (`MediaResolveTests` etc.) and existing `RequestConfigBuilderTest` static cases. Never write a JVM test that constructs a Nitro Promise.
2. **Run commands for tests:**
   - Android: `cd apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --console=plain` (needs sandbox off — SDK access)
   - Swift: `swift test --disable-sandbox` from repo root (sandbox off)
   - TS: `yarn test` from repo root
3. Baseline (all green, verified 2026-06-12): Android 83 tests, Swift 383, TS 56.
4. Nitro-generated structs (`RequestConfig`, `TransformableRequestConfig`, `MediaRequestConfig`, `ArtworkRequestConfig`, `ImageQueryParams`, `ImageContext`, `ImageSource`, `Track`) are Kotlin data classes — `copy()` works. `TransformableRequestConfig` positional order: `(transform, transformSync, method, path, baseUrl, headers, query, body, contentType, userAgent)`. `RequestConfig`: `(method, path, baseUrl, headers, query, body, contentType, userAgent)`. Prefer named args.
5. Conventional commits (`refactor(android): …`) — semantic-release reads them.

---

### Task 1: `buildApiRequest` — unify browse + search request building

**Files:**
- Modify: `android/src/main/java/com/audiobrowser/browser/BrowserManager.kt:1136-1310` (`executeApiRequest`, `executeSearchApiRequest`)
- Test: `android/src/test/java/com/audiobrowser/browser/BrowserManagerBuildApiRequestTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `android/src/test/java/com/audiobrowser/browser/BrowserManagerBuildApiRequestTest.kt`:

```kotlin
package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests [BrowserManager.buildApiRequest] — the single request-building path for
 * browse and search (the Kotlin port of iOS `buildApiRequest`). Static layers
 * only: Nitro Promises (transforms/resolvers) cannot be constructed on the JVM;
 * transform semantics are covered by RequestConfigBuilderTest and the Swift suite.
 */
class BrowserManagerBuildApiRequestTest {

  private lateinit var bm: BrowserManager

  @Before
  fun setup() {
    bm = BrowserManager()
  }

  private fun layer(
    baseUrl: String? = null,
    path: String? = null,
    query: Map<String, String>? = null,
    headers: Map<String, String>? = null,
  ) =
    TransformableRequestConfig(
      transform = null,
      transformSync = null,
      method = null,
      path = path,
      baseUrl = baseUrl,
      headers = headers,
      query = query,
      body = null,
      contentType = null,
      userAgent = null,
    )

  @Test
  fun `layers apply request then kind then route, override-wins`() = runTest {
    bm.config =
      BrowserConfig(request = layer(baseUrl = "https://api.example.com", query = mapOf("a" to "request")))
    val request =
      bm.buildApiRequest(
        kindConfig = layer(query = mapOf("a" to "kind", "b" to "kind")),
        routeConfig = layer(query = mapOf("b" to "route")),
        path = "/stations",
        params = emptyMap(),
      )
    assertTrue(request.url, request.url.startsWith("https://api.example.com/stations?"))
    assertTrue(request.url, request.url.contains("a=kind"))
    assertTrue(request.url, request.url.contains("b=route"))
  }

  @Test
  fun `initialQuery seeds the base so it reaches the final URL`() = runTest {
    bm.config = BrowserConfig(request = layer(baseUrl = "https://api.example.com"))
    val request =
      bm.buildApiRequest(
        kindConfig = layer(path = "/search"),
        routeConfig = null,
        path = null,
        params = emptyMap(),
        initialQuery = mapOf("q" to "jazz", "mode" to "genre"),
      )
    assertTrue(request.url, request.url.contains("q=jazz"))
    assertTrue(request.url, request.url.contains("mode=genre"))
    assertTrue(request.url, request.url.startsWith("https://api.example.com/search?"))
  }

  @Test
  fun `headers merge across layers with later layers winning`() = runTest {
    bm.config =
      BrowserConfig(
        request = layer(baseUrl = "https://api.example.com", headers = mapOf("x-a" to "request", "x-b" to "request"))
      )
    val request =
      bm.buildApiRequest(
        kindConfig = layer(headers = mapOf("x-b" to "kind")),
        routeConfig = null,
        path = "/p",
        params = emptyMap(),
      )
    assertEquals("request", request.headers?.get("x-a"))
    assertEquals("kind", request.headers?.get("x-b"))
  }

  @Test
  fun `missing baseUrl throws ContentNotFoundException`() = runTest {
    bm.config = BrowserConfig(request = layer(query = mapOf("a" to "1")))
    try {
      bm.buildApiRequest(kindConfig = null, routeConfig = null, path = "/nowhere", params = emptyMap())
      fail("expected ContentNotFoundException")
    } catch (e: ContentNotFoundException) {
      assertEquals("/nowhere", e.path)
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --tests 'com.audiobrowser.browser.BrowserManagerBuildApiRequestTest' --console=plain` (sandbox off)
Expected: **compilation failure** — `buildApiRequest` unresolved.

- [ ] **Step 3: Implement `buildApiRequest`**

In `BrowserManager.kt`, directly above `executeApiRequest` (around line 1136), add:

```kotlin
  /**
   * Builds the HTTP request for an API-backed path by layering request (shared) →
   * kind (browse/search) → route configs. Each layer's transform receives the
   * previous layer's output; a layer with no transform merges its static fields.
   *
   * `initialQuery` seeds query params onto the BASE the kind layer receives (e.g.
   * search q/mode/…): a layer with a transform "wins completely" and is handed only
   * the base, so params placed on a layer's own static query would be dropped
   * before the transform runs. Mirrors iOS `buildApiRequest`.
   *
   * @throws ContentNotFoundException when no layer supplies a baseUrl — there is
   *   nothing to fetch, so the path is genuinely "not found" rather than a network
   *   error (mirrors iOS's `guard let baseUrl`).
   */
  internal suspend fun buildApiRequest(
    kindConfig: TransformableRequestConfig?,
    routeConfig: TransformableRequestConfig?,
    path: String?,
    params: Map<String, String>,
    initialQuery: Map<String, String>? = null,
  ): HttpClient.HttpRequest {
    // Resolve the request/browse resolver thunks once per content generation (cached).
    ensureLayersResolved()

    var merged =
      RequestConfig(
        method = null,
        path = path,
        baseUrl = null,
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )
    resolvedRequestLayer?.let { merged = RequestConfigBuilder.mergeConfig(merged, it, params) }
    if (!initialQuery.isNullOrEmpty()) {
      merged = merged.copy(query = (merged.query ?: emptyMap()) + initialQuery)
    }
    kindConfig?.let { merged = RequestConfigBuilder.mergeConfig(merged, it, params) }
    routeConfig?.let { merged = RequestConfigBuilder.mergeConfig(merged, it, params) }

    if (merged.baseUrl.isNullOrBlank()) {
      throw ContentNotFoundException(path ?: "")
    }
    return RequestConfigBuilder.buildHttpRequest(merged)
  }
```

Add `import com.margelo.nitro.audiobrowser.RequestConfig` and `import com.audiobrowser.http.HttpClient` if not already present (both already imported in this file — verify).

- [ ] **Step 4: Run test to verify it passes**

Same command as Step 2. Expected: 4 tests PASS.

- [ ] **Step 5: Rewrite `executeApiRequest` onto `buildApiRequest`**

Replace the body of `executeApiRequest` (currently `BrowserManager.kt:1140-1205`) — the layer-merging block and baseUrl guard go away; response handling is unchanged:

```kotlin
  /**
   * Execute an API request for browser content. Request building (layering +
   * transforms + baseUrl guard) lives in [buildApiRequest]; this adds the
   * browse-specific response shape (a ResolvedTrack page object).
   */
  private suspend fun executeApiRequest(
    apiConfig: TransformableRequestConfig?,
    path: String,
    routeParams: Map<String, String>,
  ): ResolvedTrack {
    return withContext(Dispatchers.IO) {
      // request (shared) → browse (kind) → route. apiConfig is null for the
      // implicit default (an unmatched browse path → fetch via request + browse + path).
      ensureLayersResolved()
      val httpRequest = buildApiRequest(resolvedBrowseLayer, apiConfig, path, routeParams)
      val response = httpClient.request(httpRequest)

      response.fold(
        onSuccess = { httpResponse ->
          if (httpResponse.isSuccessful) {
            val jsonResolvedTrack = json.decodeFromString<JsonResolvedTrack>(httpResponse.body)
            jsonResolvedTrack.toNitro()
          } else {
            Timber.w(
              "HTTP request failed with status ${httpResponse.code} for ${httpRequest.url}: ${httpResponse.body}"
            )
            throw HttpStatusException(httpResponse.code, "Server returned ${httpResponse.code}")
          }
        },
        onFailure = { exception ->
          Timber.e(exception, "HTTP request failed")
          throw NetworkException("Network request failed: ${exception.message}", exception)
        },
      )
    }
  }
```

(`ensureLayersResolved()` before reading `resolvedRequestLayer`/`resolvedBrowseLayer` is required — `buildApiRequest` also calls it, but the `resolvedBrowseLayer` argument is read *before* the call. Idempotent within a generation.)

- [ ] **Step 6: Rewrite `executeSearchApiRequest` onto `buildApiRequest`**

Replace the body of `executeSearchApiRequest` (currently `BrowserManager.kt:1217-1310`). The hand-built base config, the `searchConfig` field-copy, and the merge chain all collapse; the search-param map and response handling are unchanged:

```kotlin
  private suspend fun executeSearchApiRequest(
    apiConfig: TransformableRequestConfig,
    params: SearchParams,
  ): Array<Track> {
    return withContext(Dispatchers.IO) {
      try {
        val searchQueryParams = buildMap {
          put("q", params.query)
          params.mode?.let { put("mode", it.toString().lowercase()) }
          params.genre?.let { put("genre", it) }
          params.artist?.let { put("artist", it) }
          params.album?.let { put("album", it) }
          params.title?.let { put("title", it) }
          params.playlist?.let { put("playlist", it) }
        }

        // request (shared) → search (kind); no browse layer and no route — search
        // is its own kind. The search params seed the base (see buildApiRequest docs).
        val httpRequest =
          buildApiRequest(
            kindConfig = apiConfig,
            routeConfig = null,
            path = null,
            params = emptyMap(),
            initialQuery = searchQueryParams,
          )
        val response = httpClient.request(httpRequest)

        response.fold(
          onSuccess = { httpResponse ->
            if (httpResponse.isSuccessful) {
              // The search endpoint returns a bare Track array (unlike browse,
              // which returns a page object). iOS parses it the same way.
              val jsonTracks = json.decodeFromString<List<JsonTrack>>(httpResponse.body)
              jsonTracks.map { it.toNitro() }.toTypedArray()
            } else {
              Timber.w(
                "Search HTTP request failed with status ${httpResponse.code}: ${httpResponse.body}"
              )
              emptyArray()
            }
          },
          onFailure = { exception ->
            Timber.e(exception, "Search HTTP request failed")
            emptyArray()
          },
        )
      } catch (e: Exception) {
        Timber.e(e, "Error executing search API request")
        emptyArray()
      }
    }
  }
```

Note the deliberate behavior nuance: with no baseUrl configured, search previously attempted a relative-URL request that failed inside `httpClient` → `emptyArray()`. Now `buildApiRequest` throws `ContentNotFoundException` → caught by the existing catch-all → `emptyArray()`. Same outcome, no network attempt.

- [ ] **Step 7: Run the full Android suite**

Run: `cd apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --console=plain` (sandbox off)
Expected: 87 tests (83 + 4 new), 0 failures.

- [ ] **Step 8: Commit**

```bash
git add android/src/main/java/com/audiobrowser/browser/BrowserManager.kt \
        android/src/test/java/com/audiobrowser/browser/BrowserManagerBuildApiRequestTest.kt
git commit -m "refactor(android): unify browse/search request building behind buildApiRequest

Ports iOS's buildApiRequest(kind:route:path:params:initialQuery:) shape.
The search path's 60-line fork existed only because initialQuery seeding
was never ported; both pipelines now share one request-building seam."
```

---

### Task 2: Collapse `RequestConfigBuilder`'s three transform-wins overloads

**Files:**
- Modify: `android/src/main/java/com/audiobrowser/http/RequestConfigBuilder.kt:81-248`
- Test: existing `android/src/test/java/com/audiobrowser/http/RequestConfigBuilderTest.kt` (guard; extend with 2 cases)

- [ ] **Step 1: Add the guard tests (static-merge equivalence for Media/Artwork overloads)**

Open `RequestConfigBuilderTest.kt`, read its existing helpers/style first, then add (adapting helper names to the file's existing builders if present):

```kotlin
  @Test
  fun `mergeConfig MediaRequestConfig static fields merge over base and preserve callbacks`() =
    runTest {
      val base =
        RequestConfig(
          method = null,
          path = "/stream.mp3",
          baseUrl = "https://media.example.com",
          headers = mapOf("x-base" to "1"),
          query = null,
          body = null,
          contentType = null,
          userAgent = "base-ua",
        )
      val override =
        MediaRequestConfig(
          resolve = null,
          resolveSync = null,
          transform = null,
          transformSync = null,
          method = null,
          path = null,
          baseUrl = null,
          headers = mapOf("x-media" to "2"),
          query = mapOf("token" to "abc"),
          body = null,
          contentType = null,
          userAgent = null,
        )
      val merged = RequestConfigBuilder.mergeConfig(base, override)
      assertEquals("/stream.mp3", merged.path)
      assertEquals("https://media.example.com", merged.baseUrl)
      assertEquals("1", merged.headers?.get("x-base"))
      assertEquals("2", merged.headers?.get("x-media"))
      assertEquals("abc", merged.query?.get("token"))
      assertEquals("base-ua", merged.userAgent)
    }

  @Test
  fun `mergeConfig ArtworkRequestConfig static fields merge and preserve imageQueryParams`() =
    runTest {
      val base =
        RequestConfig(
          method = null,
          path = "/art.png",
          baseUrl = "https://img.example.com",
          headers = null,
          query = null,
          body = null,
          contentType = null,
          userAgent = null,
        )
      val override =
        ArtworkRequestConfig(
          resolve = null,
          resolveSync = null,
          transform = null,
          transformSync = null,
          imageQueryParams = ImageQueryParams(width = "w", height = "h"),
          method = null,
          path = null,
          baseUrl = null,
          headers = null,
          query = mapOf("sig" to "xyz"),
          body = null,
          contentType = null,
          userAgent = null,
        )
      val merged = RequestConfigBuilder.mergeConfig(base, override, imageContext = null)
      assertEquals("/art.png", merged.path)
      assertEquals("xyz", merged.query?.get("sig"))
      assertEquals("w", merged.imageQueryParams?.width)
    }
```

(Imports: `com.margelo.nitro.audiobrowser.MediaRequestConfig`, `ArtworkRequestConfig`, `ImageQueryParams`. If the generated `ArtworkRequestConfig`/`MediaRequestConfig` constructors have a different field order, use named args exactly as above — they are named. If `ImageQueryParams` has different field names, check `nitrogen/generated/android/kotlin/com/margelo/nitro/audiobrowser/ImageQueryParams.kt` and adapt.)

- [ ] **Step 2: Run to verify they pass against the CURRENT code**

Run: `./gradlew :react-native-audio-browser:testDebugUnitTest --tests 'com.audiobrowser.http.RequestConfigBuilderTest' --console=plain`
Expected: PASS (these are characterization tests pinning current behavior before the refactor).

- [ ] **Step 3: Commit the characterization tests**

```bash
git add android/src/test/java/com/audiobrowser/http/RequestConfigBuilderTest.kt
git commit -m "test(android): pin Media/Artwork mergeConfig static-merge behavior"
```

- [ ] **Step 4: Refactor — one semantics helper, two rewrap helpers**

In `RequestConfigBuilder.kt`, add below `composeResolved`:

```kotlin
  /**
   * The single definition of layer-application semantics: a transform (async and/or
   * sync) wins completely — with both set they run as a pipeline, async first, then
   * sync, each replacing the running config — otherwise the override's static
   * fields merge over the base. A thrown transform falls back to the base.
   */
  private suspend fun applyLayerSemantics(
    base: RequestConfig,
    staticOverride: RequestConfig,
    hasTransform: Boolean,
    label: String,
    runTransforms: suspend (RequestConfig) -> RequestConfig,
  ): RequestConfig {
    if (!hasTransform) return mergeConfig(base, staticOverride)
    return try {
      runTransforms(base)
    } catch (e: Exception) {
      Timber.e(e, "Failed to apply $label transform function, using base config")
      base
    }
  }

  /** Rebuilds a [MediaRequestConfig] with [c]'s request fields, preserving callbacks. */
  private fun MediaRequestConfig.withRequestFields(c: RequestConfig) =
    MediaRequestConfig(
      resolve = resolve,
      resolveSync = resolveSync,
      transform = transform,
      transformSync = transformSync,
      method = c.method,
      path = c.path,
      baseUrl = c.baseUrl,
      headers = c.headers,
      query = c.query,
      body = c.body,
      contentType = c.contentType,
      userAgent = c.userAgent,
    )

  /** Rebuilds an [ArtworkRequestConfig] with [c]'s request fields, preserving callbacks + imageQueryParams. */
  private fun ArtworkRequestConfig.withRequestFields(c: RequestConfig) =
    ArtworkRequestConfig(
      resolve = resolve,
      resolveSync = resolveSync,
      transform = transform,
      transformSync = transformSync,
      imageQueryParams = imageQueryParams,
      method = c.method,
      path = c.path,
      baseUrl = c.baseUrl,
      headers = c.headers,
      query = c.query,
      body = c.body,
      contentType = c.contentType,
      userAgent = c.userAgent,
    )
```

Then replace the three overload bodies:

```kotlin
  suspend fun mergeConfig(
    base: RequestConfig,
    override: TransformableRequestConfig,
    routeParams: Map<String, String>? = null,
  ): RequestConfig =
    applyLayerSemantics(
      base,
      toRequestConfig(override),
      hasTransform = override.transform != null || override.transformSync != null,
      label = "request",
    ) { start ->
      var result = start
      override.transform?.let { result = awaitAsyncConfig(it.invoke(result, routeParams)) }
      override.transformSync?.let { result = awaitSyncConfig(it.invoke(result, routeParams)) }
      result
    }

  suspend fun mergeConfig(
    base: RequestConfig,
    override: MediaRequestConfig,
    routeParams: Map<String, String>? = null,
  ): MediaRequestConfig {
    val finalConfig =
      applyLayerSemantics(
        base,
        toRequestConfig(override),
        hasTransform = override.transform != null || override.transformSync != null,
        label = "media",
      ) { start ->
        var result = start
        override.transform?.let { result = awaitAsyncConfig(it.invoke(result, routeParams)) }
        override.transformSync?.let { result = awaitSyncConfig(it.invoke(result, routeParams)) }
        result
      }
    return override.withRequestFields(finalConfig)
  }

  suspend fun mergeConfig(
    base: RequestConfig,
    override: ArtworkRequestConfig,
    imageContext: ImageContext? = null,
  ): ArtworkRequestConfig {
    val finalConfig =
      applyLayerSemantics(
        base,
        toRequestConfig(override),
        hasTransform = override.transform != null || override.transformSync != null,
        label = "artwork",
      ) { start ->
        var result = start
        override.transform?.let {
          result = awaitAsyncConfig(it.invoke(MediaTransformParams(result, imageContext)))
        }
        override.transformSync?.let {
          result = awaitSyncConfig(it.invoke(MediaTransformParams(result, imageContext)))
        }
        result
      }
    return override.withRequestFields(finalConfig)
  }
```

Also simplify the tail of `applyMediaResolve` (`RequestConfigBuilder.kt:176-190`) to use the rewrap helper:

```kotlin
    // Resolve wins: merge it over the layered config (override-wins on every field).
    val merged = mergeConfig(toRequestConfig(layered), resolved)
    return layered.withRequestFields(merged)
```

Keep the 2-arg `suspend fun mergeConfig(base, override: TransformableRequestConfig)` convenience overload (it just calls the 3-arg version) — delete it only if nothing references it (`grep -rn "mergeConfig(" android/src ios src`); `AudioBrowser.kt:308` uses the 2-arg form.

- [ ] **Step 5: Run the full Android suite**

Run: `./gradlew :react-native-audio-browser:testDebugUnitTest --console=plain`
Expected: 89 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add android/src/main/java/com/audiobrowser/http/RequestConfigBuilder.kt
git commit -m "refactor(android): single definition of transform-wins layer semantics

The three mergeConfig overloads each repeated 'transform wins completely,
else static merge' plus a 13-field rewrap; now one applyLayerSemantics
helper + two withRequestFields rewraps."
```

---

### Task 3: `BrowserUrlResolution.kt` — move `resolveMediaUrl` out of `AudioBrowser`

**Files:**
- Create: `android/src/main/java/com/audiobrowser/browser/BrowserUrlResolution.kt`
- Modify: `android/src/main/java/com/audiobrowser/AudioBrowser.kt:286-341` (`getMediaRequestConfig`)
- Test: `android/src/test/java/com/audiobrowser/browser/BrowserUrlResolutionTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests browser/BrowserUrlResolution.kt — media/artwork URL resolution owned by
 * BrowserManager (the Kotlin analog of iOS BrowserManager+URLResolution.swift).
 * Static configs only (Nitro Promises are JNI-backed; see file-top note in
 * BrowserManagerLayerResolutionTest).
 */
class BrowserUrlResolutionTest {

  private lateinit var bm: BrowserManager

  @Before
  fun setup() {
    bm = BrowserManager()
  }

  private fun requestLayer(baseUrl: String) =
    TransformableRequestConfig(
      transform = null,
      transformSync = null,
      method = null,
      path = null,
      baseUrl = baseUrl,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )

  @Test
  fun `resolveMediaUrl returns null when neither media nor request layer is configured`() =
    runTest {
      bm.config = BrowserConfig()
      assertNull(bm.resolveMediaUrl("https://cdn.example.com/a.mp3"))
    }

  @Test
  fun `resolveMediaUrl applies the request layer to a relative src`() = runTest {
    bm.config = BrowserConfig(request = requestLayer("https://api.example.com"))
    val resolved = bm.resolveMediaUrl("/stream/123")
    assertEquals("https://api.example.com", resolved?.baseUrl)
    assertEquals("/stream/123", resolved?.path)
  }

  @Test
  fun `resolveMediaUrl merges static media config over the request layer`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        media =
          MediaRequestConfig(
            resolve = null,
            resolveSync = null,
            transform = null,
            transformSync = null,
            method = null,
            path = null,
            baseUrl = "https://media.example.com",
            headers = mapOf("x-token" to "abc"),
            query = null,
            body = null,
            contentType = null,
            userAgent = null,
          ),
      )
    val resolved = bm.resolveMediaUrl("/stream/123")
    assertEquals("https://media.example.com", resolved?.baseUrl)
    assertEquals("abc", resolved?.headers?.get("x-token"))
    assertEquals("/stream/123", resolved?.path)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :react-native-audio-browser:testDebugUnitTest --tests 'com.audiobrowser.browser.BrowserUrlResolutionTest' --console=plain`
Expected: compilation failure — `resolveMediaUrl` unresolved.

- [ ] **Step 3: Create `BrowserUrlResolution.kt` with `resolveMediaUrl`**

The body moves from `AudioBrowser.getMediaRequestConfig` (`AudioBrowser.kt:291-341`), minus `runBlocking`/`try` (those stay at the binding surface). **One deliberate fix:** the original looked up the cached track only when `mediaConfig?.resolve != null`, silently skipping `resolveSync`-only consumers; include both.

```kotlin
package com.audiobrowser.browser

import com.audiobrowser.http.RequestConfigBuilder
import com.margelo.nitro.audiobrowser.MediaRequestConfig
import com.margelo.nitro.audiobrowser.RequestConfig

/**
 * Outbound URL resolution for media (and, in later tasks, artwork) — the Android
 * analog of iOS `BrowserManager+URLResolution.swift`. Owns how a Track's `src`
 * becomes a fetchable request: request layer (shared, incl. its transform) →
 * media config → per-Track `media.resolve`, with transform-wins semantics
 * centralised in [RequestConfigBuilder].
 */

/**
 * Builds the media request config for [originalUrl]. Returns null only when
 * neither a request layer nor a media config is set (the caller then uses the
 * original URL as-is). Mirrors iOS `resolveMediaUrl`.
 */
suspend fun BrowserManager.resolveMediaUrl(originalUrl: String): MediaRequestConfig? {
  val mediaConfig = config.media
  // The request layer counts as present when a static `request` OR a `requestResolver`
  // is set — a resolver-only consumer still needs its baseUrl/headers/transform
  // applied to media URLs.
  val hasRequestLayer = config.request != null || config.requestResolver != null
  if (mediaConfig == null && !hasRequestLayer) return null

  // Layered: request (shared, incl. its transform) → media. The request layer runs
  // for media even when no media-specific config is present (so a relative src
  // still gets baseUrl).
  val requestConfig = resolvedRequestConfig()
  var base =
    RequestConfig(
      method = null,
      path = originalUrl,
      baseUrl = null,
      headers = null,
      query = null,
      body = null,
      contentType = null,
      userAgent = null,
    )
  requestConfig?.let { base = RequestConfigBuilder.mergeConfig(base, it) }
  val mediaLayered =
    if (mediaConfig != null) {
      RequestConfigBuilder.mergeConfig(base, mediaConfig)
    } else {
      MediaRequestConfig(
        resolve = null,
        resolveSync = null,
        transform = null,
        transformSync = null,
        method = base.method,
        path = base.path,
        baseUrl = base.baseUrl,
        headers = base.headers,
        query = base.query,
        body = base.body,
        contentType = base.contentType,
        userAgent = base.userAgent,
      )
    }
  // Final, most-specific layer: media.resolve(track). The cached Track carries any
  // per-track `request` override; resolve reads it and returns the winning config.
  // Only look up the track when a resolve callback exists (async OR sync — the
  // old code checked only `resolve`, silently breaking resolveSync-only consumers).
  val track =
    if (mediaConfig?.resolve != null || mediaConfig?.resolveSync != null) {
      getCachedTrack(originalUrl)
    } else null
  return RequestConfigBuilder.applyMediaResolve(mediaLayered, track)
}
```

`resolvedRequestConfig()` and `getCachedTrack` are `internal`/public on `BrowserManager` — same module, fine.

- [ ] **Step 4: Run test to verify it passes**

Same command. Expected: 3 tests PASS.

- [ ] **Step 5: Reduce `AudioBrowser.getMediaRequestConfig` to the binding-surface shell**

Replace `AudioBrowser.kt:286-341` with:

```kotlin
  /**
   * Media URL transformation for [com.audiobrowser.player.TransformingDataSource].
   * Resolution lives in [resolveMediaUrl] (browser/BrowserUrlResolution.kt); this
   * shell owns only the blocking bridge: it runs on ExoPlayer's IO thread
   * (TransformingDataSource.open), so blocking here is safe and intentional.
   */
  fun getMediaRequestConfig(originalUrl: String): MediaRequestConfig? {
    return try {
      runBlocking { browserManager.resolveMediaUrl(originalUrl) }
    } catch (e: Exception) {
      Timber.e(e, "Failed to transform media URL: $originalUrl")
      null
    }
  }
```

Add `import com.audiobrowser.browser.resolveMediaUrl`. Remove now-unused imports in `AudioBrowser.kt` (likely `RequestConfig`, `RequestConfigBuilder` — only if nothing else in the file uses them; grep within the file first).

- [ ] **Step 6: Run the full Android suite**

Expected: 92 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add android/src/main/java/com/audiobrowser/browser/BrowserUrlResolution.kt \
        android/src/main/java/com/audiobrowser/AudioBrowser.kt \
        android/src/test/java/com/audiobrowser/browser/BrowserUrlResolutionTest.kt
git commit -m "refactor(android): move media URL resolution into BrowserUrlResolution

Kotlin analog of iOS BrowserManager+URLResolution.swift. AudioBrowser keeps
only the runBlocking binding shell. Also fixes resolveSync-only media
configs never receiving the cached track."
```

---

### Task 4: Move `resolveArtworkUrl` into `BrowserUrlResolution`; delete the `artworkUrlResolver` wiring

**Files:**
- Modify: `android/src/main/java/com/audiobrowser/browser/BrowserUrlResolution.kt` (add `resolveArtworkUrl`, `substituteTrackId`, `buildHeadersMap`, `applyImageQueryParams`)
- Modify: `android/src/main/java/com/audiobrowser/browser/BrowserManager.kt:145, 459-560` (delete `artworkUrlResolver` property; call `resolveArtworkUrl` directly)
- Modify: `android/src/main/java/com/audiobrowser/player/Player.kt:142-176, 211-230, ~1262` (delete `wireUpArtworkResolver` + setter side effects; reroute now-playing artwork resolve)
- Modify: `android/src/main/java/com/audiobrowser/util/CoilBitmapLoader.kt:218-441` (delete `transformArtworkUrlForTrack` + blocking variant)
- Test: extend `BrowserUrlResolutionTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `BrowserUrlResolutionTest.kt`:

```kotlin
  private fun track(artwork: String?, id: String? = null, src: String? = "https://s/a.mp3") =
    Track(
      id = id,
      url = null,
      src = src,
      title = "T",
      subtitle = null,
      artist = null,
      album = null,
      description = null,
      genre = null,
      duration = null,
      artwork = artwork,
      artworkSource = null,
      favorited = null,
      request = null,
      children = null,
    )

  @Test
  fun `resolveArtworkUrl returns original artwork untouched when no artwork config`() = runTest {
    bm.config = BrowserConfig()
    val source = bm.resolveArtworkUrl(track(artwork = "https://img.example.com/a.png"))
    assertEquals("https://img.example.com/a.png", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl returns null when no config and no artwork`() = runTest {
    bm.config = BrowserConfig()
    assertNull(bm.resolveArtworkUrl(track(artwork = null)))
  }

  @Test
  fun `resolveArtworkUrl layers request and artwork config over the track artwork`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork = staticArtworkConfig(query = mapOf("sig" to "xyz")),
      )
    val source = bm.resolveArtworkUrl(track(artwork = "/art/1.png"))
    assertEquals("https://api.example.com/art/1.png?sig=xyz", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl substitutes the id token`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork = staticArtworkConfig(path = "/artwork/{id}"),
      )
    val source = bm.resolveArtworkUrl(track(artwork = null, id = "abc"))
    assertEquals("https://api.example.com/artwork/abc", source?.uri)
  }

  @Test
  fun `resolveArtworkUrl applies image query params from the image context`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork = staticArtworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h")),
      )
    val source =
      bm.resolveArtworkUrl(
        track(artwork = "/art/1.png"),
        perRouteConfig = null,
        imageContext = ImageContext(256.0, 256.0),
      )
    assertTrue(source!!.uri.contains("w=256"))
    assertTrue(source.uri.contains("h=256"))
  }
```

With this helper added near `requestLayer`:

```kotlin
  private fun staticArtworkConfig(
    path: String? = null,
    query: Map<String, String>? = null,
    imageQueryParams: ImageQueryParams? = null,
  ) =
    ArtworkRequestConfig(
      resolve = null,
      resolveSync = null,
      transform = null,
      transformSync = null,
      imageQueryParams = imageQueryParams,
      method = null,
      path = path,
      baseUrl = null,
      headers = null,
      query = query,
      body = null,
      contentType = null,
      userAgent = null,
    )
```

(Imports: `Track`, `ArtworkRequestConfig`, `ImageQueryParams`, `ImageContext`, `assertTrue`. If the generated `Track` constructor has more/different fields, check `nitrogen/generated/android/kotlin/com/margelo/nitro/audiobrowser/Track.kt` and fill all fields with nulls except those set — keep named args.)

- [ ] **Step 2: Run to verify failure** (compilation: `resolveArtworkUrl` unresolved).

- [ ] **Step 3: Move the implementation**

In `BrowserUrlResolution.kt`, add — bodies move from `CoilBitmapLoader.transformArtworkUrlForTrack` (`CoilBitmapLoader.kt:232-393`), `substituteTrackId` (`:395-410` area), and `buildHeadersMap` (`:412+`), with these changes: `getArtworkConfig()` callback → `resolvedRequestConfig()` + `config.artwork`; the duplicated image-query-params block becomes `applyImageQueryParams`; `Timber` tags preserved:

```kotlin
/**
 * Resolves a Track's artwork into a fetchable [ImageSource]: request layer →
 * artwork config (per-route overrides global) → per-Track `artwork.resolve` →
 * image query params from [imageContext] → artwork transform → `{id}`
 * substitution. Mirrors iOS `resolveArtworkUrl`. Returns null when there is no
 * artwork (or a resolver explicitly produced none).
 */
suspend fun BrowserManager.resolveArtworkUrl(
  track: Track,
  perRouteConfig: ArtworkRequestConfig? = null,
  imageContext: ImageContext? = null,
): ImageSource? {
  val effectiveArtworkConfig = perRouteConfig ?: config.artwork
  val trackArtwork = track.artwork?.takeIf { it.isNotEmpty() }

  if (effectiveArtworkConfig == null && trackArtwork == null) return null
  if (effectiveArtworkConfig == null) {
    return trackArtwork?.let { ImageSource(uri = it, method = null, headers = null, body = null) }
  }

  return try {
    // Base via the shared request layer (its transform runs for artwork too).
    var mergedConfig =
      RequestConfig(
        method = null,
        path = null,
        baseUrl = null,
        headers = null,
        query = null,
        body = null,
        contentType = null,
        userAgent = null,
      )
    resolvedRequestConfig()?.let {
      mergedConfig = RequestConfigBuilder.mergeConfig(mergedConfig, it, emptyMap())
    }
    if (trackArtwork != null) {
      mergedConfig = mergedConfig.copy(path = trackArtwork)
    }

    // Per-track resolution — async `resolve` first, then `resolveSync` merged over
    // it (sync winning) via the tested helper. Mirrors iOS resolveArtworkUrl.
    val asyncResolved =
      effectiveArtworkConfig.resolve?.let { RequestConfigBuilder.awaitAsyncConfig(it.invoke(track)) }
    val syncResolved =
      effectiveArtworkConfig.resolveSync?.let { RequestConfigBuilder.awaitSyncConfig(it.invoke(track)) }
    val resolvedConfig = RequestConfigBuilder.composeResolved(asyncResolved, syncResolved)

    // If a resolver ran but produced nothing, that means no artwork.
    if (
      (effectiveArtworkConfig.resolve != null || effectiveArtworkConfig.resolveSync != null) &&
        resolvedConfig == null
    ) {
      return null
    }

    // Artwork config's static fields (not resolve/transform — those run separately).
    mergedConfig =
      RequestConfigBuilder.mergeConfig(mergedConfig, RequestConfigBuilder.toRequestConfig(effectiveArtworkConfig))
    resolvedConfig?.let { mergedConfig = RequestConfigBuilder.mergeConfig(mergedConfig, it) }

    // Image query params BEFORE transform (so the transform can override them).
    mergedConfig = applyImageQueryParams(mergedConfig, effectiveArtworkConfig.imageQueryParams, imageContext)

    // Transform (async first, then sync), receiving the image context.
    var transformedConfig = mergedConfig
    effectiveArtworkConfig.transform?.let {
      transformedConfig =
        RequestConfigBuilder.awaitAsyncConfig(it.invoke(MediaTransformParams(transformedConfig, imageContext)))
    }
    effectiveArtworkConfig.transformSync?.let {
      transformedConfig =
        RequestConfigBuilder.awaitSyncConfig(it.invoke(MediaTransformParams(transformedConfig, imageContext)))
    }

    // `{id}` token substitution, only for a non-empty id. Mirrors iOS substituteTrackId.
    track.id?.takeIf { it.isNotEmpty() }?.let { transformedConfig = substituteTrackId(transformedConfig, it) }

    val uri = RequestConfigBuilder.buildUrl(transformedConfig)
    if (uri.isEmpty()) return null

    ImageSource(
      uri = uri,
      method = transformedConfig.method,
      headers =
        buildHeadersMap(
          transformedConfig.headers?.toMap(),
          transformedConfig.userAgent,
          transformedConfig.contentType,
        ),
      body = transformedConfig.body,
    )
  } catch (e: Exception) {
    Timber.e(e, "Failed to transform artwork URL for track: ${track.title}")
    null
  }
}

/** Folds [imageContext] width/height into the query under the configured param names. */
private fun applyImageQueryParams(
  config: RequestConfig,
  imageQueryParams: ImageQueryParams?,
  imageContext: ImageContext?,
): RequestConfig {
  if (imageContext == null || imageQueryParams == null) return config
  val contextQuery = mutableMapOf<String, String>()
  imageQueryParams.width?.let { key -> imageContext.width?.let { contextQuery[key] = it.toInt().toString() } }
  imageQueryParams.height?.let { key -> imageContext.height?.let { contextQuery[key] = it.toInt().toString() } }
  if (contextQuery.isEmpty()) return config
  return config.copy(query = (config.query ?: emptyMap()) + contextQuery)
}
```

Move `substituteTrackId` and `buildHeadersMap` from `CoilBitmapLoader.kt` into this file as private top-level functions, **unchanged**. Note a subtle preservation: the original built the track-artwork base via a `mergeConfig(baseConfig, urlRequestConfig)` whose only set field was `path` — `copy(path = trackArtwork)` is equivalent (override-wins on path only) and clearer.

- [ ] **Step 4: Run the new tests** — expected PASS (8 total in this class).

- [ ] **Step 5: Reroute the two callers, delete the wiring**

1. **`BrowserManager.kt:145`** — delete the `artworkUrlResolver` property.
2. **`BrowserManager.kt:507-521`** (in `resolveUncached`) — replace the resolver-null-check block with a direct call:

```kotlin
                // Transform artwork URL. At browse-time there is no display size info.
                transformedTrack =
                  transformArtworkUrl(
                    transformedTrack,
                    effectiveArtworkConfig,
                    path,
                    index,
                    ImageContext(null, null),
                  )
```

3. **`BrowserManager.kt:545+`** — the private `transformArtworkUrl` wrapper drops its `resolver` parameter and calls `resolveArtworkUrl(track, artworkConfig, imageContext)` directly (keep its edge-case handling and `artworkSource` population exactly as-is).
4. **`Player.kt:211-230`** — delete `wireUpArtworkResolver` and its call sites in the `browser` / `coilBitmapLoader` property setters (the setters keep any *other* wiring they do — read them fully first; only the artwork-resolver wiring and its `clearContentCache()` companion go away).
5. **`Player.kt:~1262`** (inside the now-playing artwork resolve) — replace `loader.transformArtworkUrlForTrack(track, nowPlayingArtwork, imageContext)` with `audioBrowser.browserManager.resolveArtworkUrl(track, nowPlayingArtwork, imageContext)` (adapt to the surrounding code's variable names; add `import com.audiobrowser.browser.resolveArtworkUrl`). If the surrounding code only had a `loader` reference, get the browser via the existing `browser` property — read the enclosing function first.
6. **`CoilBitmapLoader.kt`** — delete `transformArtworkUrlForTrack`, its blocking variant (`:435-441`), `substituteTrackId`, and `buildHeadersMap` (now living in `BrowserUrlResolution.kt`). The URL-first `transformArtworkUrl(originalUrl, sizeHintPixels)` and the `getArtworkConfig` constructor param **stay until Task 5**.

**Behavior change (intended, call out in the commit):** browse-time artwork transformation no longer depends on the player/bitmap-loader being wired. Previously `artworkUrlResolver` was null until `Player` set it, so content resolved before player setup had untransformed artwork (mitigated by a cache clear on wire-up — also now deleted). Now `BrowserManager` always resolves artwork itself.

- [ ] **Step 6: Run the full Android suite** — expected 97 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add -A android/src
git commit -m "refactor(android): BrowserManager owns artwork URL resolution

Moves transformArtworkUrlForTrack from CoilBitmapLoader into
browser/BrowserUrlResolution.kt as BrowserManager.resolveArtworkUrl
(mirroring iOS), deletes the artworkUrlResolver lambda wiring and the
order-dependent Player setter side effects. Browse-time artwork
transformation no longer requires the player to be set up first."
```

---

### Task 5: Track-first display-time artwork — `ArtworkResolutionRegistry` + `loadBitmap` rewrite

**Files:**
- Create: `android/src/main/java/com/audiobrowser/browser/ArtworkResolutionRegistry.kt`
- Modify: `android/src/main/java/com/audiobrowser/browser/BrowserManager.kt` (registry property + registration in the artwork wrapper + `displayArtworkSource`in `BrowserUrlResolution.kt`)
- Modify: `android/src/main/java/com/audiobrowser/player/Player.kt` (register now-playing resolutions; add `resolveDisplayArtwork` + `findQueueTrackByArtworkUri`)
- Modify: `android/src/main/java/com/audiobrowser/util/CoilBitmapLoader.kt` (constructor + `loadBitmap`; delete URL-first path)
- Modify: `android/src/main/java/com/audiobrowser/Service.kt:94-109` (wiring)
- Modify: `android/src/main/java/com/audiobrowser/AudioBrowser.kt:268-284` (delete `getArtworkConfig` if unused after this)
- Test: `android/src/test/java/com/audiobrowser/browser/ArtworkResolutionRegistryTest.kt` (create), extend `BrowserUrlResolutionTest.kt`

**Why:** `loadBitmap(uri)` re-transforms whatever URI Media3 hands it. For browse-resolved tracks and now-playing-resolved artwork that URI is *already transformed* — a second transform is only safe if the consumer's Transform is idempotent (undocumented, accidental). iOS never has this problem because display-time loading is Track-first (`CarPlayImageLoader.swift:65`). The registry remembers *how* a URI was produced so display time can re-resolve from the Track with a real size hint.

- [ ] **Step 1: Write the failing registry test**

```kotlin
package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkResolutionRegistryTest {

  private fun track(title: String) =
    Track(
      id = null, url = null, src = "https://s/$title.mp3", title = title, subtitle = null,
      artist = null, album = null, description = null, genre = null, duration = null,
      artwork = null, artworkSource = null, favorited = null, request = null, children = null,
    )

  @Test
  fun `lookup returns the registered entry`() {
    val registry = ArtworkResolutionRegistry()
    registry.register("https://img/a.png?sig=1", track("a"), perRouteConfig = null)
    assertEquals("a", registry.lookup("https://img/a.png?sig=1")?.track?.title)
    assertNull(registry.lookup("https://img/unknown.png"))
  }

  @Test
  fun `re-registering a uri overwrites the entry`() {
    val registry = ArtworkResolutionRegistry()
    registry.register("u", track("old"), null)
    registry.register("u", track("new"), null)
    assertEquals("new", registry.lookup("u")?.track?.title)
  }

  @Test
  fun `evicts least-recently-used beyond capacity`() {
    val registry = ArtworkResolutionRegistry(maxEntries = 2)
    registry.register("u1", track("t1"), null)
    registry.register("u2", track("t2"), null)
    registry.lookup("u1") // touch u1 so u2 is eldest
    registry.register("u3", track("t3"), null)
    assertEquals("t1", registry.lookup("u1")?.track?.title)
    assertNull(registry.lookup("u2"))
  }
}
```

(Adapt the `Track` constructor fields exactly as in Task 4's test if they differ.)

- [ ] **Step 2: Run — compilation failure expected.**

- [ ] **Step 3: Implement the registry**

```kotlin
package com.audiobrowser.browser

import com.margelo.nitro.audiobrowser.ArtworkRequestConfig
import com.margelo.nitro.audiobrowser.Track

/**
 * Remembers how an artwork URI was produced (which Track, which artwork-config
 * kind) so the display-time bitmap loader can re-resolve it Track-first with a
 * real size hint — instead of re-running the artwork Transform on an
 * already-transformed URL, which is only safe for idempotent transforms.
 * Bounded LRU; thread-safe (registered from browse coroutines and the
 * now-playing scope, read from the bitmap loader's IO scope).
 */
class ArtworkResolutionRegistry(private val maxEntries: Int = 256) {

  data class Entry(val track: Track, val perRouteConfig: ArtworkRequestConfig?)

  private val entries =
    object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
        size > maxEntries
    }

  @Synchronized
  fun register(uri: String, track: Track, perRouteConfig: ArtworkRequestConfig?) {
    entries[uri] = Entry(track, perRouteConfig)
  }

  @Synchronized fun lookup(uri: String): Entry? = entries[uri]
}
```

- [ ] **Step 4: Run registry tests — PASS expected.**

- [ ] **Step 5: Register resolutions at both production sites + add `displayArtworkSource`**

1. `BrowserManager`: add `val artworkResolutions = ArtworkResolutionRegistry()` near the other properties. In the private `transformArtworkUrl` wrapper (Task 4's version), after a successful resolution that produced an `ImageSource`, register it:

```kotlin
        artworkResolutions.register(imageSource.uri, track, artworkConfig)
```

(against the *original* track and the *effective per-route* config that was used — adapt to the wrapper's local names.)

2. `Player.maybeResolveNowPlayingArtwork`: after a successful `resolveArtworkUrl(track, nowPlayingArtwork, imageContext)` produces a source that gets stamped onto the media item, register it:

```kotlin
        audioBrowser.browserManager.artworkResolutions.register(source.uri, track, nowPlayingArtwork)
```

3. In `BrowserUrlResolution.kt`, add:

```kotlin
/**
 * Display-time artwork resolution by URI (Media3's BitmapLoader only receives a
 * URI). A registry hit re-resolves Track-first with the real [sizeHintPixels] —
 * never re-transforming an already-transformed URL. Returns null for unknown
 * URIs; the caller decides the fallback (fetch as-is).
 */
suspend fun BrowserManager.displayArtworkSource(uri: String, sizeHintPixels: Int?): ImageSource? {
  val entry = artworkResolutions.lookup(uri) ?: return null
  val imageContext =
    sizeHintPixels?.takeIf { it > 0 }?.let { ImageContext(it.toDouble(), it.toDouble()) }
  return resolveArtworkUrl(entry.track, entry.perRouteConfig, imageContext)
}
```

- [ ] **Step 6: Write + run a `displayArtworkSource` test**

Add to `BrowserUrlResolutionTest.kt`:

```kotlin
  @Test
  fun `displayArtworkSource re-resolves a registered uri with the size hint`() = runTest {
    bm.config =
      BrowserConfig(
        request = requestLayer("https://api.example.com"),
        artwork = staticArtworkConfig(imageQueryParams = ImageQueryParams(width = "w", height = "h")),
      )
    val t = track(artwork = "/art/1.png")
    val browseTime = bm.resolveArtworkUrl(t) // no size at browse time
    bm.artworkResolutions.register(browseTime!!.uri, t, null)

    val displayTime = bm.displayArtworkSource(browseTime.uri, sizeHintPixels = 512)
    assertTrue(displayTime!!.uri.contains("w=512"))
    assertTrue(displayTime.uri.contains("h=512"))
  }

  @Test
  fun `displayArtworkSource returns null for an unknown uri`() = runTest {
    bm.config = BrowserConfig()
    assertNull(bm.displayArtworkSource("https://img/unknown.png", 512))
  }
```

Run: expected PASS.

- [ ] **Step 7: Player composition + queue fallback**

In `Player.kt`, add (near the now-playing artwork code):

```kotlin
  /**
   * Finds the queue Track whose published artwork URI matches [uri], for
   * display-time artwork resolution of app-supplied queue tracks that never went
   * through browse (so they are not in the resolution registry). Must run on the
   * main thread (ExoPlayer access).
   */
  private fun findQueueTrackByArtworkUri(uri: String): Track? {
    for (i in 0 until exoPlayer.mediaItemCount) {
      val item = exoPlayer.getMediaItemAt(i)
      if (item.mediaMetadata.artworkUri?.toString() == uri) {
        return item.localConfiguration?.tag as? Track
      }
    }
    return null
  }

  /**
   * Track-first display-time artwork resolution for [com.audiobrowser.util.CoilBitmapLoader]:
   * registry hit (browse/now-playing-resolved URIs) → queue-tag lookup
   * (app-supplied tracks with raw artwork). Null means "fetch the URI as-is".
   */
  suspend fun resolveDisplayArtwork(uri: String, sizeHintPixels: Int?): ImageSource? {
    val audioBrowser = browser ?: return null
    val browserManager = audioBrowser.browserManager
    browserManager.displayArtworkSource(uri, sizeHintPixels)?.let {
      return it
    }
    val track = withContext(Dispatchers.Main) { findQueueTrackByArtworkUri(uri) } ?: return null
    val imageContext =
      sizeHintPixels?.takeIf { it > 0 }?.let { ImageContext(it.toDouble(), it.toDouble()) }
    return browserManager.resolveArtworkUrl(track, null, imageContext)
  }
```

(Imports: `com.audiobrowser.browser.displayArtworkSource`, `com.audiobrowser.browser.resolveArtworkUrl`, `ImageContext`, `ImageSource`, `withContext`, `Dispatchers`.)

- [ ] **Step 8: Rewrite `CoilBitmapLoader` and the Service wiring**

`CoilBitmapLoader` constructor: replace `getArtworkConfig: suspend () -> ArtworkConfig?` with `resolveDisplayArtwork: suspend (uri: String, sizeHintPixels: Int?) -> ImageSource?`. Delete the `ArtworkConfig` data class and `transformArtworkUrl` (`:156-216`). Rewrite `loadBitmap`:

```kotlin
  override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
    val future = SettableFuture.create<Bitmap>()

    scope.launch {
      try {
        val originalUrl = uri.toString()
        // Use hint from media browser (Android Auto), or fall back to screen-based size
        val sizeHint = getArtworkSizeHint() ?: defaultArtworkSizePixels

        // Track-first resolution (registry / queue lookup). Null → the URI was not
        // produced by us (or predates the registry): fetch it as-is, never
        // re-transform a URL we cannot attribute.
        val source =
          try {
            resolveDisplayArtwork(originalUrl, sizeHint)
          } catch (e: Exception) {
            Timber.e(e, "Display artwork resolution failed for $originalUrl")
            null
          }
        val finalUrl = source?.uri ?: originalUrl
        val headers = source?.headers ?: emptyMap()

        // Check if this is an SVG that needs special decoding
        val isSvg = SvgArtworkRenderer.isSvgUrl(finalUrl)

        Timber.d("Loading artwork: $finalUrl (headers: ${headers.keys}, svg: $isSvg)")

        val requestBuilder =
          ImageRequest.Builder(context)
            .data(finalUrl)
            .allowHardware(false) // Required for Media3 notification compatibility

        if (headers.isNotEmpty()) {
          val networkHeaders = NetworkHeaders.Builder()
          headers.forEach { (key, value) -> networkHeaders.add(key, value) }
          requestBuilder.httpHeaders(networkHeaders.build())
        }

        if (isSvg) {
          requestBuilder.decoderFactory { result, options, _ -> SvgDecoder(result.source, options) }
        }

        val result = imageLoader.execute(requestBuilder.build())
        val bitmap = result.image?.toBitmap()

        if (bitmap != null) {
          future.set(bitmap)
        } else {
          Timber.e("Failed to decode image from $finalUrl - result.image was null")
          future.setException(IllegalStateException("Failed to load bitmap from $finalUrl"))
        }
      } catch (e: Exception) {
        Timber.e(e, "Exception loading artwork from $uri")
        future.setException(e)
      }
    }

    return future
  }
```

`source?.headers` is the Nitro `ImageSource.headers` map type — if it is not already `Map<String, String>`, convert with `?.toMap()` as the old code did.

`Service.kt:94-109`: replace the `getArtworkConfig = { player.browser?.getArtworkConfig() }` wiring with:

```kotlin
        resolveDisplayArtwork = { uri, sizeHint -> player.resolveDisplayArtwork(uri, sizeHint) },
```

`AudioBrowser.kt:268-284`: delete `getArtworkConfig()` (and the now-playing variant ending at `:284`) **if** `grep -rn "getArtworkConfig\|ArtworkConfig(" android/src` shows no remaining callers. Delete unused imports.

- [ ] **Step 9: Run the full Android suite + Swift + TS**

```bash
cd apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --console=plain
cd ../../.. && swift test --disable-sandbox && yarn test
```
Expected: Android 102 tests 0 failures; Swift 383; TS 56.

- [ ] **Step 10: Commit**

```bash
git add -A android/src
git commit -m "refactor(android)!: display-time artwork is Track-first via a resolution registry

loadBitmap no longer re-transforms whatever URI Media3 hands it (double
transform for browse/now-playing-resolved artwork unless the consumer's
Transform was idempotent). A bounded registry maps produced URIs back to
(Track, config kind); display time re-resolves with the real size hint,
matching iOS CarPlayImageLoader. Unknown URIs are fetched as-is.

BREAKING (internal): CoilBitmapLoader's getArtworkConfig constructor
callback is replaced by resolveDisplayArtwork."
```

---

### Task 6: Smoke-test on a device/emulator

**Files:** none (verification only)

- [ ] **Step 1: Build and run the example app**

```bash
yarn workspace example-native android  # or the worktree's documented run script — check apps/example-native/package.json
```

If no emulator/device is available, state that explicitly in the final report instead of skipping silently.

- [ ] **Step 2: Verify with Android Auto desktop head unit or the notification**

Checks: (1) browse a list — artwork renders; (2) play a track — notification artwork renders at full quality (the size-hint path); (3) if the example app configures `nowPlayingArtwork`, confirm the now-playing artwork appears and the log line `Loading artwork:` shows a size param, not a doubly-transformed URL.

---

### Task 7: CONTEXT.md — name the Layer concept

**Files:**
- Modify: `CONTEXT.md` (library root)

- [ ] **Step 1: Add the term**

The code has said "layer" for years (`ensureLayersResolved`, "request layer", "kind layer") without the glossary defining it. Add to the `### Requests` section of `CONTEXT.md`, after **Resolve**/**Transform**:

```markdown
**Layer**:
One config in the outbound-request stack, applied base-up: **request** (shared) →
**kind** (browse / search / media / artwork / nowPlayingArtwork) → **route** (per-Route)
→ per-Track **Resolve**. Each Layer either merges its static fields over the running
config or — when it has a **Transform** — replaces it entirely (transform-wins).
`buildApiRequest` on both platforms is the canonical application of the stack.
_Avoid_: stage, level, override (the mechanism, not the concept).
```

- [ ] **Step 2: Commit**

```bash
git add CONTEXT.md
git commit -m "docs: define Layer in the domain glossary"
```

---

### Task 8: iOS alignment — dedupe the duplicated static merge

**Files:**
- Modify: `ios/Browser/BrowserManager+URLResolution.swift:366-378` (`mergeRequestConfig`)
- Modify: `ios/Browser/BrowserManager.swift:~495-510` (`applyLayer`'s static-merge branch)

iOS has the same static-merge written twice: `applyLayer`'s else-branch (`BrowserManager.swift:495+`) and `URLResolution`'s private `mergeRequestConfig` (`:366`). Android now has exactly one (`RequestConfigBuilder.mergeConfig`). Port the deduplication back.

- [ ] **Step 1: Read both implementations and confirm they are field-for-field identical**

`grep -n -A14 "private func mergeRequestConfig" ios/Browser/BrowserManager+URLResolution.swift` and compare with `applyLayer`'s else-branch. If they differ semantically (e.g. one carries `path` from base, the other doesn't), STOP and report the difference instead of merging them — that difference would be load-bearing.

- [ ] **Step 2: Hoist one shared implementation**

Make `mergeRequestConfig(base:override:)` an `internal` function on `BrowserManager` (it lives in the URLResolution extension file — both call sites are BrowserManager extensions/methods, so they can share it). Have `applyLayer`'s else-branch call it:

```swift
    return mergeRequestConfig(base: base, override: RequestConfig(
      method: layer.method, path: base.path, baseUrl: layer.baseUrl, headers: layer.headers,
      query: layer.query, body: layer.body, contentType: layer.contentType, userAgent: layer.userAgent,
    ))
```

…adapting to whichever direction is cleaner after Step 1's reading (the goal is ONE place that knows "override wins per-field, dicts merge override-wins, `path` carried from base in layer application"). Note the `path` nuance from `applyLayer`'s doc comment ("`path` is carried from the base — only a transform may change it") — preserve it exactly; passing `path: base.path` in the override as shown achieves this only if `mergeRequestConfig` is override-wins on path. Verify against the Swift tests.

- [ ] **Step 3: Run the Swift suite**

Run: `swift test --disable-sandbox`
Expected: 383 tests pass.

- [ ] **Step 4: Commit**

```bash
git add ios/Browser
git commit -m "refactor(ios): share one static request-config merge between applyLayer and URL resolution"
```

---

### Task 9: Final verification + lint

- [ ] **Step 1: Full check across all three layers**

```bash
yarn test && swift test --disable-sandbox
cd apps/example-native/android && ./gradlew :react-native-audio-browser:testDebugUnitTest --console=plain && cd ../../..
yarn lint 2>/dev/null || true   # run whatever lint scripts package.json defines (check "scripts")
yarn tsc --noEmit 2>/dev/null || yarn typecheck 2>/dev/null || true
```

Expected: all green. Report exact counts.

- [ ] **Step 2: Self-review the diff**

```bash
git log --oneline feature-fry..HEAD
git diff feature-fry --stat
```

Check: no leftover references to `artworkUrlResolver`, `transformArtworkUrlForTrack`, `getArtworkConfig`, `wireUpArtworkResolver` (`grep -rn` for each). No `TODO` introduced.

---

## Self-review notes (already applied)

- **Spec coverage:** browse/search unification → Task 1; three-overload collapse → Task 2; BrowserManager-owned URL resolution → Tasks 3–4; artwork-path unification + double-transform fix → Task 5; iOS alignment → Task 8; CONTEXT.md side effect of the grilling → Task 7.
- **Known intentional behavior changes** (each called out in its commit): search no-baseUrl now short-circuits without a network attempt (Task 1); `resolveSync`-only media configs get the cached track (Task 3); artwork transformation no longer gated on player wiring (Task 4); display-time never re-transforms unattributable URIs (Task 5).
- **Type consistency check:** `buildApiRequest(kindConfig:routeConfig:path:params:initialQuery:)` used identically in Tasks 1; `resolveMediaUrl`/`resolveArtworkUrl`/`displayArtworkSource` names consistent across Tasks 3–5; `ArtworkResolutionRegistry.Entry(track, perRouteConfig)` consistent between Task 5 steps.
- **Generated-type caveat:** Nitro struct constructors in test code (Track, ArtworkRequestConfig, MediaRequestConfig, ImageQueryParams) must be checked against `nitrogen/generated/android/kotlin/com/margelo/nitro/audiobrowser/` at execution time — field order/names there are the source of truth; the plan uses named args everywhere to minimize breakage.
