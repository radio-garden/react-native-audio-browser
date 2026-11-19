import Testing

@testable import AudioBrowserTestable

struct FavoriteSetTests {
  private func favorites(_ identities: [String], enabled: Bool = true) -> FavoriteSet {
    var set = FavoriteSet()
    set.enabled = enabled
    _ = set.replace(with: identities)
    return set
  }

  @Test func replaceReportsTheIdentitiesThatChanged() {
    var set = FavoriteSet()
    let changes = [["a", "b"], ["b", "a"], ["a", "b", "a"], ["b", "c"], []].map { set.replace(with: $0) }
    #expect(changes == [["a", "b"], [], [], ["a", "c"], ["b", "c"]])
  }

  @Test func hydratesTrueOrFalseByIdentity() {
    let set = favorites(["a", "stable"])
    #expect(set.hydrate(Track(src: "a")).favorited == true)
    #expect(set.hydrate(Track(src: "b")).favorited == false)
    #expect(set.hydrate(Track(id: "stable", src: "x")).favorited == true)
    // An id, when present, is the identity, so the src no longer matches.
    #expect(set.hydrate(Track(id: "other", src: "a")).favorited == false)
  }

  @Test func leavesATrackAloneWhenDisabled() {
    #expect(favorites(["a"], enabled: false).hydrate(Track(src: "a")).favorited == nil)
  }

  @Test func leavesATrackWithoutIdentityAlone() {
    #expect(favorites(["a"]).hydrate(Track(path: "/browse")).favorited == nil)
  }

  @Test func aFavoritedTheTrackCarriesWins() {
    let set = favorites(["a"])
    #expect(set.hydrate(Track(src: "a", favorited: false)).favorited == false)
    #expect(set.hydrate(Track(src: "b", favorited: true)).favorited == true)
  }

  @Test func aLocalToggleHoldsUntilTheNextReplace() {
    var set = favorites(["a"])
    set.set("b", favorited: true)
    set.set("a", favorited: false)
    #expect(set.hydrate(Track(src: "a")).favorited == false)
    #expect(set.hydrate(Track(src: "b")).favorited == true)
    _ = set.replace(with: ["a"])
    #expect(set.hydrate(Track(src: "b")).favorited == false)
  }

  @Test func hydratesEverySectionOfAPage() {
    let page = ResolvedTrack(
      path: "/p",
      sections: [Section(children: [Track(src: "a")]), Section(children: [Track(src: "b")])],
      title: "P",
    )
    let hydrated = favorites(["b"]).hydrate(page)
    #expect(hydrated.sections?.map { $0.children.map(\.favorited) } == [[false], [true]])
  }

  @Test func aHydratedTrackFollowsALaterReplace() throws {
    var set = favorites([])
    let queued = set.hydrate(Track(src: "a"))
    #expect(queued.favorited == false)

    let added = set.replace(with: ["a"])
    let favorited = try #require(set.restamp(queued, changed: added))
    #expect(favorited.favorited == true)

    let removed = set.replace(with: [])
    #expect(set.restamp(favorited, changed: removed)?.favorited == false)
  }

  @Test func restampFillsAnUnsetFlag() {
    var set = favorites([])
    let changed = set.replace(with: ["a"])
    #expect(set.restamp(Track(src: "a"), changed: changed)?.favorited == true)
  }

  @Test func restampLeavesATrackWhoseIdentityDidNotChange() {
    var set = favorites(["a"])
    let changed = set.replace(with: ["a", "c"])
    // Carried by the tracks themselves, against the set: only a change to their identity moves them.
    #expect(set.restamp(Track(src: "a", favorited: false), changed: changed) == nil)
    #expect(set.restamp(Track(src: "b", favorited: true), changed: changed) == nil)
  }

  @Test func restampLeavesATrackThatAlreadyAgrees() {
    var set = favorites([])
    let changed = set.replace(with: ["a"])
    #expect(set.restamp(Track(src: "a", favorited: true), changed: changed) == nil)
  }

  @Test func restampLeavesATrackAloneWhenDisabled() {
    var set = favorites([], enabled: false)
    let changed = set.replace(with: ["a"])
    #expect(set.restamp(Track(src: "a", favorited: false), changed: changed) == nil)
  }

  @Test func restampLeavesATrackWithoutIdentityAlone() {
    var set = favorites([])
    let changed = set.replace(with: ["/browse"])
    #expect(set.restamp(Track(path: "/browse"), changed: changed) == nil)
  }

  @Test func restampsTheChangedTracksOfAPage() {
    var set = favorites(["b"])
    let page = set.hydrate(ResolvedTrack(
      path: "/p",
      sections: [Section(children: [Track(src: "a")]), Section(children: [Track(src: "b"), Track(src: "c")])],
      title: "P",
    ))
    let changed = set.replace(with: ["a", "c"])
    let restamped = set.restamp(page, changed: changed)
    #expect(restamped?.sections?.map { $0.children.map(\.favorited) } == [[true], [false, true]])
    #expect(restamped?.path == "/p")
  }

  @Test func aPageWithNoChangedTrackIsNotRestamped() {
    var set = favorites(["b"])
    let page = set.hydrate(ResolvedTrack(path: "/p", sections: [Section(children: [Track(src: "b")])], title: "P"))
    let changed = set.replace(with: ["b", "elsewhere"])
    #expect(set.restamp(page, changed: changed) == nil)
  }
}
