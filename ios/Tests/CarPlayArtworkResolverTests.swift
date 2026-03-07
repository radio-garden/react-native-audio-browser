import Testing

@testable import AudioBrowserTestable

// MARK: - SF Symbol Detection

@Suite("SF symbol artwork")
struct SFSymbolTests {
  @Test func sfSymbolArtwork_returnsSfSymbolAction() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "sf:heart.fill",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .sfSymbol(artwork: "sf:heart.fill", width: 40, height: 40))
  }

  @Test func sfSymbolWithParams_returnsSfSymbolAction() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "sf:play.circle?bg=#000&fg=#fff",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 24, targetHeight: 24,
      displayScale: 3.0,
      urlResolver: nil
    )
    #expect(action == .sfSymbol(artwork: "sf:play.circle?bg=#000&fg=#fff", width: 24, height: 24))
  }

  @Test func sfSymbolTakesPriorityOverUrlResolver() async {
    var resolverCalled = false
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "sf:music.note",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: { _, _ in
        resolverCalled = true
        return ArtworkResolvedImage(uri: "https://example.com/img.jpg", headers: nil)
      }
    )
    #expect(action == .sfSymbol(artwork: "sf:music.note", width: 40, height: 40))
    #expect(!resolverCalled)
  }

  @Test func sfSymbolInArtworkSource_usedAsFallback() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: nil,
      artworkSourceUri: "sf:star.fill",
      artworkCarPlayTinted: nil,
      targetWidth: 20, targetHeight: 20,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .sfSymbol(artwork: "sf:star.fill", width: 20, height: 20))
  }
}

// MARK: - No Artwork

@Suite("no artwork")
struct NoArtworkTests {
  @Test func noArtworkOrSource_returnsNone() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: nil,
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .none)
  }

  @Test func noArtworkOrSource_withResolver_returnsNone() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: nil,
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: { _, _ in nil }
    )
    #expect(action == .none)
  }
}

// MARK: - URL Resolution

@Suite("URL resolution")
struct URLResolutionTests {
  @Test func resolvedUrl_returnsFetchWithHeaders() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/art.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: false,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: { pixelWidth, pixelHeight in
        #expect(pixelWidth == 80)
        #expect(pixelHeight == 80)
        return ArtworkResolvedImage(
          uri: "https://cdn.example.com/art_80x80.jpg",
          headers: ["Authorization": "Bearer token"]
        )
      }
    )
    #expect(action == .fetch(
      uri: "https://cdn.example.com/art_80x80.jpg",
      headers: ["Authorization": "Bearer token"],
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func resolvedUrl_noHeaders_returnsFetchWithNilHeaders() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/art.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 24, targetHeight: 24,
      displayScale: 3.0,
      urlResolver: { _, _ in
        ArtworkResolvedImage(uri: "https://cdn.example.com/resized.jpg", headers: nil)
      }
    )
    #expect(action == .fetch(
      uri: "https://cdn.example.com/resized.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func resolverReturnsNil_fallsBackToDirect() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/art.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: { _, _ in nil }
    )
    #expect(action == .fetch(
      uri: "https://example.com/art.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func resolvedInvalidUrl_fallsBackToDirect() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/fallback.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: { _, _ in
        // Return an invalid URI that URL(string:) would reject
        ArtworkResolvedImage(uri: "", headers: nil)
      }
    )
    // Should fall back to direct artwork URL
    #expect(action == .fetch(
      uri: "https://example.com/fallback.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func displayScale_appliedToPixelDimensions() async {
    var receivedWidth: Double?
    var receivedHeight: Double?
    _ = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/art.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 50, targetHeight: 30,
      displayScale: 2.5,
      urlResolver: { w, h in
        receivedWidth = w
        receivedHeight = h
        return ArtworkResolvedImage(uri: "https://cdn.example.com/art.jpg", headers: nil)
      }
    )
    #expect(receivedWidth == 125) // 50 * 2.5
    #expect(receivedHeight == 75) // 30 * 2.5
  }
}

// MARK: - Direct Fallback

@Suite("direct fallback")
struct DirectFallbackTests {
  @Test func noResolver_usesArtworkUrl() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/artwork.png",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/artwork.png",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func noArtwork_usesArtworkSourceUri() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: nil,
      artworkSourceUri: "https://example.com/source.jpg",
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/source.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func artworkPreferredOverArtworkSource() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/artwork.jpg",
      artworkSourceUri: "https://example.com/source.jpg",
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/artwork.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func invalidDirectUrl_returnsNone() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .none)
  }
}

// MARK: - SVG Detection

@Suite("SVG detection")
struct SVGDetectionTests {
  @Test func svgExtension_setsIsSvgTrue() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/icon.svg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/icon.svg",
      headers: nil,
      shouldTint: false,
      isSvg: true
    ))
  }

  @Test func svgUppercase_setsIsSvgTrue() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/icon.SVG",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/icon.SVG",
      headers: nil,
      shouldTint: false,
      isSvg: true
    ))
  }

  @Test func resolvedSvgUrl_setsIsSvgTrue() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/art.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: { _, _ in
        ArtworkResolvedImage(uri: "https://cdn.example.com/icon.svg", headers: nil)
      }
    )
    #expect(action == .fetch(
      uri: "https://cdn.example.com/icon.svg",
      headers: nil,
      shouldTint: false,
      isSvg: true
    ))
  }

  @Test func nonSvg_setsIsSvgFalse() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/photo.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/photo.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }
}

// MARK: - Tinting

@Suite("tinting")
struct TintingTests {
  @Test func artworkCarPlayTintedTrue_setsShouldTint() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/icon.png",
      artworkSourceUri: nil,
      artworkCarPlayTinted: true,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/icon.png",
      headers: nil,
      shouldTint: true,
      isSvg: false
    ))
  }

  @Test func artworkCarPlayTintedFalse_doesNotTint() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/photo.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: false,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/photo.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func artworkCarPlayTintedNil_defaultsToFalse() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/photo.jpg",
      artworkSourceUri: nil,
      artworkCarPlayTinted: nil,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: nil
    )
    #expect(action == .fetch(
      uri: "https://example.com/photo.jpg",
      headers: nil,
      shouldTint: false,
      isSvg: false
    ))
  }

  @Test func tintingPreservedThroughResolver() async {
    let action = await CarPlayArtworkResolver.resolve(
      artwork: "https://example.com/icon.png",
      artworkSourceUri: nil,
      artworkCarPlayTinted: true,
      targetWidth: 40, targetHeight: 40,
      displayScale: 2.0,
      urlResolver: { _, _ in
        ArtworkResolvedImage(uri: "https://cdn.example.com/icon.png", headers: nil)
      }
    )
    #expect(action == .fetch(
      uri: "https://cdn.example.com/icon.png",
      headers: nil,
      shouldTint: true,
      isSvg: false
    ))
  }
}
