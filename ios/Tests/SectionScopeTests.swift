@testable import AudioBrowserTestable
import Testing

struct SectionScopeTests {
  private func track(_ src: String, id: String? = nil) -> Track {
    Track(id: id ?? src, src: src)
  }

  private func section(_ title: String? = nil, _ tracks: [Track]) -> Section {
    Section(title: title, children: tracks)
  }

  private func srcs(_ scoped: SectionScope.Scoped?) -> [String?]? {
    scoped.map { $0.section.children.map(\.src) }
  }

  // Two sections sharing an identity — the duplicate-heavy shape the flat
  // model had to disambiguate by precedence; sections + the stamped flat
  // index make it exact.
  private var sections: [Section] {
    [
      section("First", [track("dup"), track("x")]),
      section("Second", [track("y"), track("dup"), track("z")]),
    ]
  }

  @Test func fallsBackToTheFirstSectionContainingTheIdentity() {
    let scoped = SectionScope.scoped(in: sections, containing: "dup")
    #expect(srcs(scoped) == ["dup", "x"])
    #expect(scoped?.tappedOffset == nil)
  }

  @Test func flatIndexPinsTheTappedSection() {
    // Flat index 3 = second section, offset 1.
    let scoped = SectionScope.scoped(in: sections, containing: "dup", tappedIndex: 3)
    #expect(srcs(scoped) == ["y", "dup", "z"])
    #expect(scoped?.tappedOffset == 1)
  }

  @Test func flatIndexPinsTheExactCopyWithinASection() {
    let playlist = [section(nil, [track("a"), track("b"), track("a"), track("c")])]
    let scoped = SectionScope.scoped(in: playlist, containing: "a", tappedIndex: 2)
    #expect(scoped?.tappedOffset == 2)
  }

  @Test func staleIndexFallsBackToTheFirstIdentityMatch() {
    // The child at the stamped index no longer carries the tapped identity
    // (the list shifted) — resolution ignores the index and pins nothing.
    let scoped = SectionScope.scoped(in: sections, containing: "dup", tappedIndex: 1)
    #expect(srcs(scoped) == ["dup", "x"])
    #expect(scoped?.tappedOffset == nil)
  }

  @Test func outOfRangeIndexIsIgnored() {
    let scoped = SectionScope.scoped(in: sections, containing: "dup", tappedIndex: 99)
    #expect(srcs(scoped) == ["dup", "x"])
    #expect(scoped?.tappedOffset == nil)
  }

  @Test func vanishedIdentityReturnsNil() {
    #expect(SectionScope.scoped(in: sections, containing: "gone") == nil)
  }

  // MARK: - Identity (id when non-blank, else src)

  @Test func matchesChildByIdWhenSrcDiffers() {
    let children = [
      Track(id: "stable-a", src: "https://cdn.example/a?token=1"),
      Track(id: "stable-b", src: "https://cdn.example/b?token=2"),
    ]
    let scoped = SectionScope.scoped(in: [section(nil, children)], containing: "stable-b")
    #expect(srcs(scoped) == ["https://cdn.example/a?token=1", "https://cdn.example/b?token=2"])
  }

  @Test func blankIdFallsBackToSrc() {
    let children = [Track(id: "", src: "s1"), Track(id: "", src: "s2")]
    let scoped = SectionScope.scoped(in: [section(nil, children)], containing: "s2")
    #expect(srcs(scoped) == ["s1", "s2"])
  }

  // MARK: - Page shape helpers

  @Test func childrenSugarNormalizesToOneUntitledSection() {
    let page = ResolvedTrack(path: "/p", children: [track("a"), track("b")], title: "P")
    let sections = page.normalizedSections
    #expect(sections?.count == 1)
    #expect(sections?.first?.title == nil)
    #expect(sections?.first?.children.map(\.src) == ["a", "b"])
  }

  @Test func sectionsWinOverChildren() {
    let page = ResolvedTrack(
      path: "/p",
      sections: [section("S", [track("a")])],
      children: [track("zzz")],
      title: "P",
    )
    #expect(page.normalizedSections?.count == 1)
    #expect(page.normalizedSections?.first?.title == "S")
  }

  @Test func flattenedChildrenConcatenateInSectionOrder() {
    let page = ResolvedTrack(
      path: "/p",
      sections: [section("A", [track("a1"), track("a2")]), section(nil, [track("b1")])],
      title: "P",
    )
    #expect(page.flattenedChildren?.map(\.src) == ["a1", "a2", "b1"])
  }

  @Test func childlessPageHasNoSections() {
    #expect(ResolvedTrack(path: "/p", title: "P").normalizedSections == nil)
  }
}
