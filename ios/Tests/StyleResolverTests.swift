import Testing

@testable import AudioBrowserTestable

// Resolution semantics (ADR 0011): container properties and `display` resolve
// by scope override (`section ?? page`), inherited item properties resolve
// `track ?? section ?? page` — and `display` never inherits onto an item.

@Suite("StyleResolver.sectionStyle")
struct SectionStyleResolutionTests {
  @Test func sectionOverridesThePagePerProperty() {
    let resolved = StyleResolver.sectionStyle(
      section: SectionStyle(gridWrap: nil, display: .list, artworkRendering: nil),
      page: SectionStyle(gridWrap: false, display: .grid, artworkRendering: .stencil),
    )
    // Declared on the section: wins. Undeclared: the page's scope-wide value.
    #expect(resolved.display == .list)
    #expect(resolved.artworkRendering == .stencil)
    #expect(resolved.gridWrap == false)
  }

  @Test func aStylelessSectionTakesThePageBlock() {
    let resolved = StyleResolver.sectionStyle(
      section: nil,
      page: SectionStyle(gridWrap: true, display: .grid, artworkRendering: nil),
    )
    #expect(resolved.display == .grid)
    #expect(resolved.gridWrap == true)
  }

  @Test func noDeclarationsResolveToAnEmptyBlock() {
    let resolved = StyleResolver.sectionStyle(section: nil, page: nil)
    #expect(resolved.display == nil)
    #expect(resolved.artworkRendering == nil)
    #expect(resolved.gridWrap == nil)
  }
}

@Suite("StyleResolver.trackStyle")
struct TrackStyleResolutionTests {
  @Test func trackOverridesTheSectionPerProperty() {
    let resolved = StyleResolver.trackStyle(
      track: TrackStyle(display: nil, artworkRendering: .original),
      section: SectionStyle(gridWrap: nil, display: .grid, artworkRendering: .stencil),
    )
    #expect(resolved.artworkRendering == .original)
  }

  @Test func aStylelessTrackInheritsTheSectionValue() {
    let resolved = StyleResolver.trackStyle(
      track: nil,
      section: SectionStyle(gridWrap: nil, display: nil, artworkRendering: .stencil),
    )
    #expect(resolved.artworkRendering == .stencil)
  }

  @Test func displayNeverInheritsOntoAnItem() {
    // The track's own `display` is the page promise for the page IT opens;
    // the section's describes its children's layout. Neither may surface as
    // the item's resolved display.
    let resolved = StyleResolver.trackStyle(
      track: TrackStyle(display: .grid, artworkRendering: nil),
      section: SectionStyle(gridWrap: nil, display: .grid, artworkRendering: nil),
    )
    #expect(resolved.display == nil)
  }

  @Test func imageShapeInheritsAndOverridesPerItem() {
    // Section-wide circular (an artists shelf); one album overrides.
    let inherited = StyleResolver.trackStyle(
      track: nil,
      section: SectionStyle(imageShape: .circular),
    )
    #expect(inherited.imageShape == .circular)
    let overridden = StyleResolver.trackStyle(
      track: TrackStyle(imageShape: .roundedRectangle),
      section: SectionStyle(imageShape: .circular),
    )
    #expect(overridden.imageShape == .roundedRectangle)
  }

  @Test func accessorySymbolInheritsAndNoneResolvesAsAValue() {
    let inherited = StyleResolver.trackStyle(
      track: nil,
      section: SectionStyle(accessorySymbol: "lock.fill"),
    )
    #expect(inherited.accessorySymbol == "lock.fill")
    // 'none' is the inheritance escape — it must survive resolution intact
    // (the renderer, not the resolver, maps it to "no accessory").
    let escaped = StyleResolver.trackStyle(
      track: TrackStyle(accessorySymbol: "none"),
      section: SectionStyle(accessorySymbol: "lock.fill"),
    )
    #expect(escaped.accessorySymbol == "none")
  }
}
