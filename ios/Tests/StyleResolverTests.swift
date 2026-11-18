import Testing

@testable import AudioBrowserTestable

// Resolution rules: docs/adr/0011-style-is-a-declaration-block.md
@Suite("StyleResolver.sectionStyle")
struct SectionStyleResolutionTests {
  @Test func sectionOverridesThePagePerProperty() {
    let resolved = StyleResolver.sectionStyle(
      section: SectionStyle(gridWrap: nil, display: .list, artworkRendering: nil),
      page: SectionStyle(gridWrap: false, display: .grid, artworkRendering: .stencil),
    )
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
    #expect(resolved.gridTile == nil)
  }

  @Test func gridTileResolvesByScopeOverride() {
    let inherited = StyleResolver.sectionStyle(
      section: nil,
      page: SectionStyle(gridTile: .card),
    )
    #expect(inherited.gridTile == .card)
    let overridden = StyleResolver.sectionStyle(
      section: SectionStyle(gridTile: .plain),
      page: SectionStyle(gridTile: .card),
    )
    #expect(overridden.gridTile == .plain)
    let image = StyleResolver.sectionStyle(
      section: SectionStyle(gridTile: .image),
      page: SectionStyle(gridTile: .card),
    )
    #expect(image.gridTile == .image)
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
    // The track's own `display` is the layout of the page it opens, not of the
    // row it sits in; neither it nor the section's may resolve onto the item.
    let resolved = StyleResolver.trackStyle(
      track: TrackStyle(display: .grid, artworkRendering: nil),
      section: SectionStyle(gridWrap: nil, display: .grid, artworkRendering: nil),
    )
    #expect(resolved.display == nil)
  }

  @Test func imageShapeInheritsAndOverridesPerItem() {
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
    // "none" stays a value here: the renderer, not the resolver, maps it to no accessory.
    let escaped = StyleResolver.trackStyle(
      track: TrackStyle(accessorySymbol: "none"),
      section: SectionStyle(accessorySymbol: "lock.fill"),
    )
    #expect(escaped.accessorySymbol == "none")
  }

  @Test func cardPropertiesInheritAndOverridePerItem() {
    let inherited = StyleResolver.trackStyle(
      track: nil,
      section: SectionStyle(accessorySymbol: nil, cardTint: "#1e3a8a", cardImage: .background),
    )
    #expect(inherited.cardTint == "#1e3a8a")
    #expect(inherited.cardImage == .background)
    let overridden = StyleResolver.trackStyle(
      track: TrackStyle(cardImage: .normal),
      section: SectionStyle(cardTint: "#1e3a8a", cardImage: .background),
    )
    #expect(overridden.cardImage == .normal)
    #expect(overridden.cardTint == "#1e3a8a")
  }
}
