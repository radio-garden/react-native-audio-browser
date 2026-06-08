import Testing

@testable import AudioBrowserTestable

/// Tests for the media `resolve` layer composition: the variant unwrap (sync vs
/// async arm) and the override-wins merge that layers the resolved config over
/// the base. These mirror what `BrowserManager.resolveMediaTrackConfig` +
/// `mergeRequestConfig` do in production, exercised here against the pure
/// `MediaResolveComposer` (which the Nitro path delegates to / duplicates).
@Suite("MediaResolve")
struct MediaResolveTests {
  typealias Config = MediaResolveComposer.RequestConfigLike

  // MARK: - Variant unwrap

  @Test("sync (.first) arm yields its config directly")
  func unwrapSync() async throws {
    let config = Config(userAgent: "RESOLVED")
    let result = try await MediaResolveComposer.unwrap(.sync(config))
    #expect(result == config)
  }

  @Test("async (.second) arm awaits and yields its config")
  func unwrapAsync() async throws {
    let config = Config(userAgent: "RESOLVED-ASYNC")
    let result = try await MediaResolveComposer.unwrap(
      .async {
        // Simulate the Promise<RequestConfig> resolving after a hop.
        await Task.yield()
        return config
      },
    )
    #expect(result == config)
  }

  @Test("async arm propagates errors (rejected promise)")
  func unwrapAsyncThrows() async {
    struct Boom: Error {}
    await #expect(throws: Boom.self) {
      _ = try await MediaResolveComposer.unwrap(
        .async { throw Boom() } as MediaResolveComposer.ResolveVariant<Config>,
      )
    }
  }

  // MARK: - Merge as the final layer

  @Test("resolve userAgent overrides base while keeping baseUrl and path")
  func mergeOverridesUserAgent() {
    let base = Config(path: "/audio.mp3", baseUrl: "https://base.example.com", userAgent: "BASE")
    let resolve = Config(userAgent: "RESOLVED")

    let merged = MediaResolveComposer.merge(base: base, override: resolve)

    #expect(merged.userAgent == "RESOLVED")
    #expect(merged.baseUrl == "https://base.example.com")
    #expect(merged.path == "/audio.mp3")
  }

  @Test("async resolve result merges identically once unwrapped")
  func mergeAfterAsyncUnwrap() async throws {
    let base = Config(path: "/audio.mp3", baseUrl: "https://base.example.com", userAgent: "BASE")
    let resolved = try await MediaResolveComposer.unwrap(
      .async { Config(userAgent: "RESOLVED-ASYNC") },
    )

    let merged = MediaResolveComposer.merge(base: base, override: resolved)

    #expect(merged.userAgent == "RESOLVED-ASYNC")
    #expect(merged.baseUrl == "https://base.example.com")
    #expect(merged.path == "/audio.mp3")
  }

  @Test("absent resolve is a no-op (base is returned unchanged)")
  func absentResolveIsNoOp() {
    let base = Config(path: "/audio.mp3", baseUrl: "https://base.example.com", userAgent: "BASE")

    // Production guards `resolve == nil` and returns base untouched; modelled as
    // merging an all-nil override, which must leave every field intact.
    let merged = MediaResolveComposer.merge(base: base, override: Config())

    #expect(merged == base)
  }

  @Test("headers and query merge per-key with override winning")
  func mergeDictsPerKey() {
    let base = Config(
      headers: ["User-Agent": "BASE", "X-Base": "1"],
      query: ["a": "1"],
    )
    let resolve = Config(
      headers: ["User-Agent": "RESOLVED", "X-Resolve": "2"],
      query: ["b": "2"],
    )

    let merged = MediaResolveComposer.merge(base: base, override: resolve)

    #expect(merged.headers == ["User-Agent": "RESOLVED", "X-Base": "1", "X-Resolve": "2"])
    #expect(merged.query == ["a": "1", "b": "2"])
  }

  @Test("mergeDicts is nil-safe on either side")
  func mergeDictsNilSafe() {
    #expect(MediaResolveComposer.mergeDicts(nil, nil) == nil)
    #expect(MediaResolveComposer.mergeDicts(["a": "1"], nil) == ["a": "1"])
    #expect(MediaResolveComposer.mergeDicts(nil, ["b": "2"]) == ["b": "2"])
  }
}
