import AVFoundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("PlayerObserver")
struct PlayerObserverTests {
  @Test func invalidatingRemovesTheBoundaryObserver() {
    let observer = PlayerObserver(player: AVPlayer()) { _ in }
    #expect(observer.boundaryObserverToken != nil)
    observer.invalidate()
    #expect(observer.boundaryObserverToken == nil)
  }

  @Test func reportsTheInitialStatus_andNothingOnceInvalidated() async {
    var events: [PlayerObserver.Event] = []
    let observer = PlayerObserver(player: AVPlayer()) { events.append($0) }
    await drain()
    #expect(events.contains { if case .status = $0 { true } else { false } })

    events = []
    let late = PlayerObserver(player: AVPlayer()) { events.append($0) }
    late.invalidate()
    await drain()
    #expect(events.isEmpty)
    // Keeps the first observer alive until its delivery has been checked.
    _ = observer
  }
}

@MainActor
@Suite("ItemObserver")
struct ItemObserverTests {
  private func makeItem() -> AVPlayerItem {
    AVPlayerItem(url: URL(fileURLWithPath: "/nonexistent.mp3"))
  }

  @Test func deliversTheItemsNotifications() async {
    let item = makeItem()
    var events: [ItemObserver.Event] = []
    let observer = ItemObserver(item: item) { events.append($0) }
    NotificationCenter.default.post(name: .AVPlayerItemPlaybackStalled, object: item)
    NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)
    // Another item's notification is not delivered.
    NotificationCenter.default.post(name: .AVPlayerItemPlaybackStalled, object: makeItem())
    await drain()
    #expect(events.map(label) == ["stalled", "playedToEnd"])
    observer.invalidate()
  }

  @Test func dropsADeliveryThatLandsAfterInvalidate() async {
    let item = makeItem()
    var events: [ItemObserver.Event] = []
    let observer = ItemObserver(item: item) { events.append($0) }
    // Delivery hops to the main actor in a Task, so it lands after this invalidate.
    NotificationCenter.default.post(name: .AVPlayerItemFailedToPlayToEndTime, object: item)
    observer.invalidate()
    await drain()
    #expect(events.isEmpty)
  }

  @Test func invalidateRemovesTheMetadataOutput() {
    let item = makeItem()
    let observer = ItemObserver(item: item) { _ in }
    #expect(item.outputs.contains { $0 is AVPlayerItemMetadataOutput })
    observer.invalidate()
    #expect(!item.outputs.contains { $0 is AVPlayerItemMetadataOutput })
  }

  private func label(_ event: ItemObserver.Event) -> String {
    switch event {
    case .likelyToKeepUp: "likelyToKeepUp"
    case .failed: "failed"
    case .playedToEnd: "playedToEnd"
    case .failedToPlayToEnd: "failedToPlayToEnd"
    case .stalled: "stalled"
    case .timedMetadata: "timedMetadata"
    }
  }
}

@MainActor
private func drain() async {
  for _ in 0 ..< 20 {
    await Task.yield()
  }
}
