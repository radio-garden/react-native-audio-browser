import Testing

@testable import AudioBrowserTestable

struct FavoriteSetTests {
  private func favorites(_ identities: [String], enabled: Bool = true) -> FavoriteSet {
    var set = FavoriteSet()
    set.enabled = enabled
    _ = set.replace(with: identities)
    return set
  }

  @Test func replaceReportsWhetherTheIdentitiesChanged() {
    var set = FavoriteSet()
    let changes = [["a", "b"], ["b", "a"], ["a", "b", "a"], []].map { set.replace(with: $0) }
    #expect(changes == [true, false, false, true])
  }

  @Test func hydratesTrueOrFalseByIdentity() {
    let set = favorites(["a", "stable"])
    #expect(set.hydrate(Track(src: "a")).favorited == true)
    #expect(set.hydrate(Track(src: "b")).favorited == false)
    #expect(set.hydrate(Track(id: "stable", src: "x")).favorited == true)
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
}
