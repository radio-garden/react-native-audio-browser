@testable import AudioBrowserTestable
import Testing

struct SectionScopeTests {
  private func track(_ src: String, group: String? = nil) -> Track {
    Track(id: src, src: src, groupTitle: group)
  }

  private func runSrcs(_ section: SectionScope.Section?) -> [String?]? {
    guard case let .run(tracks) = section else { return nil }
    return tracks.map(\.src)
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
}
