import Testing

@testable import AudioBrowserTestable

@MainActor
private final class Holder: FavoriteStamped {
  var tracks: [Track]
  let name: String
  let log: Log

  init(_ name: String, _ tracks: [Track], log: Log) {
    self.name = name
    self.tracks = tracks
    self.log = log
  }

  func restampFavorites(_ restamp: (Track) -> Track?) {
    log.entries.append(name)
    tracks = tracks.map { restamp($0) ?? $0 }
  }
}

private final class Log {
  var entries: [String] = []
}

@MainActor
struct FavoritesTests {
  private let log = Log()

  private func makeFavorites(content: [Track] = [], queue: [Track] = []) -> (Favorites, Holder, Holder) {
    let content = Holder("content", content, log: log)
    let queue = Holder("queue", queue, log: log)
    let favorites = Favorites(content: content)
    favorites.enabled = true
    favorites.queue = queue
    favorites.changed.addListener { [log] in log.entries.append("changed") }
    return (favorites, content, queue)
  }

  @Test func aReplaceRestampsTheContentThenTheQueueThenEmits() {
    let (favorites, content, queue) = makeFavorites(
      content: [Track(src: "a", favorited: false)],
      queue: [Track(src: "a", favorited: false), Track(src: "b", favorited: false)],
    )

    favorites.replace(with: ["a"])

    #expect(log.entries == ["content", "queue", "changed"])
    #expect(content.tracks.map(\.favorited) == [true])
    #expect(queue.tracks.map(\.favorited) == [true, false])
  }

  @Test func aReplaceThatChangesNothingRestampsNothing() {
    let (favorites, _, _) = makeFavorites()
    favorites.replace(with: [])
    #expect(log.entries.isEmpty)
  }

  @Test func aToggleRestampsEveryCopyOfTheTrack() {
    let a = Track(src: "a", favorited: false)
    let (favorites, content, queue) = makeFavorites(content: [a], queue: [a, Track(src: "b"), a])

    favorites.set("a", favorited: true)

    #expect(log.entries == ["content", "queue", "changed"])
    #expect(content.tracks.map(\.favorited) == [true])
    #expect(queue.tracks.map(\.favorited) == [true, nil, true])
  }

  @Test func aToggleRestampsACopyThatDisagreesWithTheSet() {
    let (favorites, _, queue) = makeFavorites(queue: [Track(src: "a", favorited: false)])
    favorites.replace(with: ["a"])
    queue.tracks = [Track(src: "a", favorited: false)]

    favorites.set("a", favorited: true)

    #expect(queue.tracks.map(\.favorited) == [true])
  }

  @Test func aReplaceWhileDisabledReachesAStampedCopy() {
    let (favorites, _, queue) = makeFavorites(queue: [Track(src: "a", favorited: false)])
    favorites.replace(with: ["a"])
    favorites.enabled = false

    favorites.replace(with: [])
    favorites.enabled = true

    #expect(queue.tracks.map(\.favorited) == [false])
  }

  @Test func theQueueIsThePlayer() throws {
    let player = makePlayer()
    let favorites = Favorites(content: Holder("content", [], log: log))
    favorites.queue = player
    try player.setQueue([Track(src: "a", favorited: false), Track(src: "a", favorited: false)])

    favorites.set("a", favorited: true)

    #expect(player.tracks.map(\.favorited) == [true, true])
  }
}
