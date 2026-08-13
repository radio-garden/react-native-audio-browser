@testable import AudioBrowserTestable
import Testing

struct SectionScopeTests {
  private func track(_ src: String, group: String? = nil) -> Track {
    Track(id: src, src: src, groupTitle: group)
  }

  private func runSrcs(_ section: SectionScope.Section?) -> [String?]? {
    guard case let .run(tracks, _) = section else { return nil }
    return tracks.map(\.src)
  }

  private func runOffset(_ section: SectionScope.Section?) -> Int? {
    guard case let .run(_, offset) = section else { return nil }
    return offset
  }

  private func imageRowSrcs(_ section: SectionScope.Section?) -> [String?]? {
    guard case let .imageRow(items) = section else { return nil }
    return items.map(\.src)
  }

  @Test func scopesToTheContiguousGroupTitleRun() {
    let children = [
      track("a", group: "First"),
      track("b", group: "First"),
      track("c", group: "Second"),
      track("d", group: "Second"),
      track("e"),
    ]
    #expect(runSrcs(SectionScope.section(of: children, containing: "c")) == ["c", "d"])
    #expect(runSrcs(SectionScope.section(of: children, containing: "a")) == ["a", "b"])
  }

  @Test func ungroupedItemsFormTheirOwnRun() {
    let children = [
      track("a", group: "First"),
      track("b"),
      track("c"),
      track("d", group: "Second"),
    ]
    #expect(runSrcs(SectionScope.section(of: children, containing: "b")) == ["b", "c"])
  }

  @Test func identicalTitlesInSeparateRunsStaySeparate() {
    let children = [
      track("a", group: "Same"),
      track("x", group: "Other"),
      track("b", group: "Same"),
    ]
    #expect(runSrcs(SectionScope.section(of: children, containing: "a")) == ["a"])
  }

  @Test func findsTheIdInsideAnImageRow() {
    let items = [
      ImageRowItem(src: "s1", title: "One"),
      ImageRowItem(src: "s2", title: "Two"),
    ]
    var row = Track(id: "row", title: "Most Played")
    row.imageRow = items
    let children = [row, track("a")]
    #expect(imageRowSrcs(SectionScope.section(of: children, containing: "s2")) == ["s1", "s2"])
  }

  @Test func unknownIdReturnsNil() {
    #expect(SectionScope.section(of: [track("a")], containing: "zz") == nil)
  }

  // Pins the documented precedence: sections are located by src, and an id
  // present in both an image row and the flat list resolves to the row.
  @Test func imageRowWinsOverAFlatListDuplicate() {
    var row = Track(id: "row", title: "Row")
    row.imageRow = [ImageRowItem(src: "dup", title: "Dup")]
    let children = [row, track("dup"), track("b")]
    #expect(imageRowSrcs(SectionScope.section(of: children, containing: "dup")) == ["dup"])
  }

  @Test func duplicateSrcAcrossRunsResolvesToTheEarlierRun() {
    let children = [
      track("dup", group: "First"),
      track("x", group: "Second"),
      track("dup", group: "Second"),
    ]
    #expect(runSrcs(SectionScope.section(of: children, containing: "dup")) == ["dup"])
  }

  // MARK: - Tapped index tie-breaker

  // The stamped page index pins which surface was tapped when the same
  // identity appears in more than one section; without it the precedence
  // tests above (image row first, earlier run first) apply.
  @Test func tappedIndexPinsTheFlatListCopyOverTheImageRow() {
    var row = Track(id: "row", title: "Row")
    row.imageRow = [ImageRowItem(src: "dup", title: "Dup")]
    let children = [row, track("dup"), track("b")]
    let section = SectionScope.section(of: children, containing: "dup", tappedIndex: 1)
    // The src-less row track shares the nil groupTitle, so it belongs to the
    // run (expansion filters it out as non-playable) — the point is the tap
    // resolved to the flat list, not the image row.
    #expect(runSrcs(section) == [nil, "dup", "b"])
    #expect(runOffset(section) == 1)
  }

  @Test func tappedIndexPinsTheImageRowWhenTheRowWasTapped() {
    var row = Track(id: "row", title: "Row")
    row.imageRow = [ImageRowItem(src: "dup", title: "Dup")]
    let children = [row, track("dup"), track("b")]
    let section = SectionScope.section(of: children, containing: "dup", tappedIndex: 0)
    #expect(imageRowSrcs(section) == ["dup"])
  }

  @Test func tappedIndexPinsTheLaterRun() {
    let children = [
      track("dup", group: "First"),
      track("x", group: "Second"),
      track("dup", group: "Second"),
    ]
    let section = SectionScope.section(of: children, containing: "dup", tappedIndex: 2)
    #expect(runSrcs(section) == ["x", "dup"])
    #expect(runOffset(section) == 1)
  }

  @Test func tappedIndexPinsTheExactCopyWithinARun() {
    let children = [track("a"), track("b"), track("a"), track("c")]
    let section = SectionScope.section(of: children, containing: "a", tappedIndex: 2)
    #expect(runSrcs(section) == ["a", "b", "a", "c"])
    #expect(runOffset(section) == 2)
  }

  @Test func staleTappedIndexFallsBackToTheFirstIdentityMatch() {
    // The child at the stamped index no longer carries the tapped identity
    // (the list shifted) — resolution ignores the index and pins nothing.
    let children = [track("x"), track("a"), track("b")]
    let section = SectionScope.section(of: children, containing: "a", tappedIndex: 0)
    #expect(runSrcs(section) == ["x", "a", "b"])
    #expect(runOffset(section) == nil)
  }

  @Test func outOfRangeTappedIndexIsIgnored() {
    let children = [track("a"), track("b")]
    let section = SectionScope.section(of: children, containing: "a", tappedIndex: 99)
    #expect(runSrcs(section) == ["a", "b"])
    #expect(runOffset(section) == nil)
  }

  // MARK: - Identity (id when non-blank, else src)

  @Test func matchesChildByIdWhenSrcDiffers() {
    let children = [
      Track(id: "stable-a", src: "https://cdn.example/a?token=1"),
      Track(id: "stable-b", src: "https://cdn.example/b?token=2"),
    ]
    #expect(
      runSrcs(SectionScope.section(of: children, containing: "stable-b"))
        == ["https://cdn.example/a?token=1", "https://cdn.example/b?token=2"])
  }

  @Test func blankIdFallsBackToSrc() {
    let children = [Track(id: "", src: "s1"), Track(id: "", src: "s2")]
    #expect(runSrcs(SectionScope.section(of: children, containing: "s2")) == ["s1", "s2"])
  }

  @Test func matchesImageRowItemByIdWhenSrcDiffers() {
    let items = [
      ImageRowItem(id: "item-1", src: "https://cdn.example/one", title: "One"),
      ImageRowItem(id: "item-2", src: "https://cdn.example/two", title: "Two"),
    ]
    var row = Track(title: "Most Played")
    row.imageRow = items
    let children = [row, track("a")]
    #expect(
      imageRowSrcs(SectionScope.section(of: children, containing: "item-2"))
        == ["https://cdn.example/one", "https://cdn.example/two"])
  }
}
