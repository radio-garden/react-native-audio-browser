import Testing

@testable import AudioBrowserTestable

// The dev diagnostic for declarations that can never render (ADR 0011): the
// recovery for the compile-time invalid-combination errors the block model
// deliberately gave up. Structural inertness only — a surface that can't draw
// a property it understands is intended usage, never a finding.

@Suite("InertStyleDiagnostic")
struct InertStyleDiagnosticTests {
  private func findings(
    _ sections: [Section], page: SectionStyle? = nil, path: String = "/home",
  ) -> [String] {
    InertStyleDiagnostic.findings(path: path, pageStyle: page, sections: sections)
  }

  private func playable(_ src: String = "https://s/a.mp3", style: TrackStyle? = nil) -> Track {
    Track(src: src, title: "T", style: style)
  }

  private func browsable(_ path: String = "/albums/1", style: TrackStyle? = nil) -> Track {
    Track(path: path, title: "T", style: style)
  }

  // MARK: - Container properties

  @Test func gridWrapOnAListSectionIsInert() {
    let found = findings([Section(title: "Recent", style: SectionStyle(gridWrap: false), children: [])])
    #expect(found.count == 1)
    #expect(found[0].contains("section 'Recent'"))
    #expect(found[0].contains("style.gridWrap"))
    #expect(found[0].contains("display 'list'"))
    #expect(found[0].contains("/home"))
  }

  @Test func gridWrapInAGridIsLive() {
    let style = SectionStyle(gridWrap: false, display: .grid)
    #expect(findings([Section(style: style, children: [])]).isEmpty)
  }

  @Test func aSectionInheritsThePagesGridDisplay() {
    // `display` resolves by scope override, so the page's grid makes the
    // section's `gridWrap` live without the section restating it.
    let found = findings(
      [Section(style: SectionStyle(gridWrap: false), children: [])],
      page: SectionStyle(display: .grid),
    )
    #expect(found.isEmpty)
  }

  @Test func gridTileOnAListSectionIsInert() {
    let found = findings([Section(style: SectionStyle(gridTile: .card), children: [])])
    #expect(found.count == 1)
    #expect(found[0].contains("style.gridTile"))
    #expect(found[0].contains("section #1"))
  }

  // MARK: - Item properties

  @Test func cardPropertiesNeedTheCardTreatment() {
    let plainGrid = SectionStyle(display: .grid, cardTint: "#1e3a8a", cardImage: .background)
    let found = findings([Section(title: "Featured", style: plainGrid, children: [])])
    #expect(found.count == 2)
    #expect(found.contains { $0.contains("style.cardTint") })
    #expect(found.contains { $0.contains("style.cardImage") })
    #expect(found.allSatisfy { $0.contains("display 'grid', gridTile 'plain'") })
  }

  @Test func cardPropertiesInACardGridAreLive() {
    let cards = SectionStyle(gridTile: .card, display: .grid, cardTint: "#1e3a8a", cardImage: .background)
    #expect(findings([Section(style: cards, children: [])]).isEmpty)
  }

  @Test func imageShapeNeedsAShapedTile() {
    let list = SectionStyle(imageShape: .circular)
    let cards = SectionStyle(gridTile: .card, display: .grid, imageShape: .circular)
    let condensed = SectionStyle(gridTile: .condensed, display: .grid, imageShape: .circular)
    #expect(findings([Section(style: list, children: [])]).count == 1)
    #expect(findings([Section(style: cards, children: [])]).count == 1)
    // Plain and condensed tiles both take a shape.
    #expect(findings([Section(style: condensed, children: [])]).isEmpty)
  }

  @Test func accessorySymbolIsInertOnlyOnCards() {
    let rows = SectionStyle(accessorySymbol: "lock.fill")
    let cards = SectionStyle(gridTile: .card, display: .grid, accessorySymbol: "lock.fill")
    // List rows draw accessories; card elements have no slot for one.
    #expect(findings([Section(style: rows, children: [])]).isEmpty)
    let found = findings([Section(style: cards, children: [])])
    #expect(found.count == 1)
    #expect(found[0].contains("no accessory slot"))
  }

  @Test func artworkRenderingIsNeverInert() {
    let stencilled = SectionStyle(gridTile: .card, display: .grid, artworkRendering: .stencil)
    #expect(findings([Section(style: stencilled, children: [])]).isEmpty)
  }

  // MARK: - Track-level declarations

  @Test func trackDeclarationsAreReportedPerSection() {
    let section = Section(
      title: "Results", style: SectionStyle(display: .grid),
      children: [
        playable(style: TrackStyle(cardTint: "#111")),
        playable("https://s/b.mp3", style: TrackStyle(cardTint: "#222")),
        playable("https://s/c.mp3"),
      ],
    )
    let found = findings([section])
    #expect(found.count == 1)
    #expect(found[0].contains("2 tracks in section 'Results'"))
    #expect(found[0].contains("style.cardTint"))
  }

  @Test func aTracksOwnShapeIsLiveInAPlainGrid() {
    let section = Section(
      style: SectionStyle(display: .grid),
      children: [playable(style: TrackStyle(imageShape: .circular))],
    )
    #expect(findings([section]).isEmpty)
  }

  // MARK: - The page block

  @Test func aPageDeclarationLiveInOneSectionIsNotReported() {
    // Card tint reaches both sections; one renders cards. Live is live.
    let found = findings(
      [
        Section(style: SectionStyle(gridTile: .card, display: .grid), children: []),
        Section(style: SectionStyle(display: .grid), children: []),
      ],
      page: SectionStyle(cardTint: "#1e3a8a"),
    )
    #expect(found.isEmpty)
  }

  @Test func aPageDeclarationNoSectionRendersIsReportedOnce() {
    let found = findings(
      [
        Section(title: "A", style: SectionStyle(display: .grid), children: []),
        Section(title: "B", children: []),
      ],
      page: SectionStyle(cardTint: "#1e3a8a"),
    )
    #expect(found.count == 1)
    #expect(found[0].contains("the page block"))
    #expect(found[0].contains("no section that inherits it"))
  }

  @Test func aSectionOverrideShadowsThePageValue() {
    // Every section declares its own tint, so the page's is never resolved —
    // shadowed, not inert; the diagnostic reports what can't render, not what
    // isn't reached.
    let found = findings(
      [Section(style: SectionStyle(gridTile: .card, display: .grid, cardTint: "#000"), children: [])],
      page: SectionStyle(cardTint: "#1e3a8a"),
    )
    #expect(found.isEmpty)
  }

  @Test func aPageWideGridMakesSectionDeclarationsLive() {
    let found = findings(
      [Section(style: SectionStyle(cardTint: "#111"), children: [])],
      page: SectionStyle(gridTile: .card, display: .grid),
    )
    #expect(found.isEmpty)
  }

  // MARK: - The positional `display`

  @Test func displayOnAPlayableTrackIsInert() {
    let section = Section(
      title: "Tracks",
      children: [
        playable(style: TrackStyle(display: .grid)),
        browsable(style: TrackStyle(display: .grid)),
      ],
    )
    let found = findings([section])
    #expect(found.count == 1)
    #expect(found[0].contains("1 track in section 'Tracks'"))
    #expect(found[0].contains("style.display"))
    #expect(found[0].contains("opens none"))
  }

  @Test func displayOnASectionOrPageIsNeverInert() {
    let found = findings(
      [Section(style: SectionStyle(display: .list), children: [browsable()])],
      page: SectionStyle(display: .grid),
    )
    #expect(found.isEmpty)
  }

  @Test func anUnstyledPageIsSilent() {
    #expect(findings([Section(children: [playable(), browsable()])]).isEmpty)
    #expect(findings([]).isEmpty)
  }
}
