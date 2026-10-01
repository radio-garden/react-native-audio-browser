import Testing

@testable import AudioBrowserTestable

// The (resolved style, platform capability) → rendered-form mapping (ADR
// 0011): degradation drops decorations before layout, and the one layout drop
// is a wrapping grid on a platform without a wrapping tile container.

@Suite("SectionPresentation")
struct SectionPresentationTests {
  private func style(display: StyleDisplay?, gridWrap: Bool? = nil) -> SectionStyle {
    SectionStyle(gridWrap: gridWrap, display: display, artworkRendering: nil)
  }

  @Test func noStyleRendersAList() {
    #expect(SectionPresentation(for: nil, supportsWrappingGrid: true) == .list)
    #expect(SectionPresentation(for: nil, supportsWrappingGrid: false) == .list)
  }

  @Test func listDisplayRendersAList() {
    #expect(SectionPresentation(for: style(display: .list), supportsWrappingGrid: true) == .list)
  }

  @Test func wrappingGridNeedsTheWrappingContainer() {
    #expect(
      SectionPresentation(for: style(display: .grid), supportsWrappingGrid: true) == .wrappingGrid)
    // The one layout drop: a wrapping grid has nowhere to go but a list —
    // every item stays reachable instead of truncating at an unknowable width.
    #expect(SectionPresentation(for: style(display: .grid), supportsWrappingGrid: false) == .list)
  }

  @Test func singleLineGridRendersOnEveryOS() {
    // gridWrap: false is the legacy image row's own shape, so it never
    // degrades — declaring it INCREASES pre-26 fidelity.
    #expect(
      SectionPresentation(for: style(display: .grid, gridWrap: false), supportsWrappingGrid: true)
        == .singleLineRow)
    #expect(
      SectionPresentation(for: style(display: .grid, gridWrap: false), supportsWrappingGrid: false)
        == .singleLineRow)
  }

  @Test func explicitWrapTrueBehavesLikeTheDefault() {
    #expect(
      SectionPresentation(for: style(display: .grid, gridWrap: true), supportsWrappingGrid: true)
        == .wrappingGrid)
    #expect(
      SectionPresentation(for: style(display: .grid, gridWrap: true), supportsWrappingGrid: false)
        == .list)
  }

  @Test func gridWrapIsInertOnLists() {
    // Declaring a decoration never changes the layout you'd get without it.
    #expect(
      SectionPresentation(for: style(display: .list, gridWrap: false), supportsWrappingGrid: true)
        == .list)
    #expect(
      SectionPresentation(for: style(display: nil, gridWrap: false), supportsWrappingGrid: true)
        == .list)
  }

  @Test func tileFamilyFollowsGridTile() {
    // The family follows the declaration alone; the wrap mode only decides
    // whether its elements take more than one line.
    #expect(SectionPresentation.tileFamily(for: SectionStyle(gridTile: .card)) == .cardElements)
    #expect(
      SectionPresentation.tileFamily(for: SectionStyle(gridTile: .image)) == .imageGridElements)
    #expect(
      SectionPresentation.tileFamily(for: SectionStyle(gridTile: .condensed)) == .condensedElements)
    #expect(SectionPresentation.tileFamily(for: nil) == .rowElements)
    #expect(SectionPresentation.tileFamily(for: SectionStyle(gridTile: .plain)) == .rowElements)
  }

  @Test func aPathlessShelfTitlesTheSectionHeader() {
    let viewAll = Section(title: "Popular", path: "/popular", children: [])
    let preview = Section(title: "Popular", children: [])
    let untitled = Section(children: [])
    // The image row's own text carries a chevron, so it is kept for the
    // shelf that has somewhere to go.
    #expect(SectionPresentation.tileSectionHeader(viewAll, supportsElements: true) == nil)
    #expect(SectionPresentation.tileSectionHeader(preview, supportsElements: true) == "Popular")
    #expect(SectionPresentation.tileSectionHeader(untitled, supportsElements: true) == nil)
    // The legacy row cannot drop its text.
    #expect(SectionPresentation.tileSectionHeader(preview, supportsElements: false) == nil)
  }

  @Test func effectiveAccessorySymbol_noneAndAbsenceDrawNothing() {
    // A declared symbol draws; 'none' (the inheritance escape) and absence
    // both fall back to the derived accessory.
    #expect(
      SectionPresentation.effectiveAccessorySymbol(TrackStyle(accessorySymbol: "lock.fill"))
        == "lock.fill")
    #expect(
      SectionPresentation.effectiveAccessorySymbol(TrackStyle(accessorySymbol: "none")) == nil)
    #expect(SectionPresentation.effectiveAccessorySymbol(TrackStyle()) == nil)
    #expect(SectionPresentation.effectiveAccessorySymbol(nil) == nil)
  }
}
