import Testing

@testable import AudioBrowserTestable

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
    #expect(SectionPresentation(for: style(display: .grid), supportsWrappingGrid: false) == .list)
  }

  @Test func singleLineGridRendersOnEveryOS() {
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
    #expect(
      SectionPresentation(for: style(display: .list, gridWrap: false), supportsWrappingGrid: true)
        == .list)
    #expect(
      SectionPresentation(for: style(display: nil, gridWrap: false), supportsWrappingGrid: true)
        == .list)
  }

  @Test func tileFamilyFollowsGridTile() {
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
    // CarPlay draws a chevron beside the image row's text, so a shelf with a path titles it there.
    #expect(SectionPresentation.tileSectionHeader(viewAll, supportsElements: true) == nil)
    #expect(SectionPresentation.tileSectionHeader(preview, supportsElements: true) == "Popular")
    #expect(SectionPresentation.tileSectionHeader(untitled, supportsElements: true) == nil)
    // Before the iOS 26 element API, the legacy image row's text always carries the title.
    #expect(SectionPresentation.tileSectionHeader(preview, supportsElements: false) == nil)
  }

  @Test func effectiveAccessorySymbol_noneAndAbsenceDrawNothing() {
    // nil draws the derived accessory (a browsable row's chevron), not no accessory.
    #expect(
      SectionPresentation.effectiveAccessorySymbol(TrackStyle(accessorySymbol: "lock.fill"))
        == "lock.fill")
    #expect(
      SectionPresentation.effectiveAccessorySymbol(TrackStyle(accessorySymbol: "none")) == nil)
    #expect(SectionPresentation.effectiveAccessorySymbol(TrackStyle()) == nil)
    #expect(SectionPresentation.effectiveAccessorySymbol(nil) == nil)
  }
}
