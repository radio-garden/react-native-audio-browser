/// What CarPlay's Now Playing custom buttons show and do.
enum NowPlayingButtonRules {
  /// `CPNowPlayingTemplate` takes at most five custom buttons and does not say what happens to
  /// more.
  static let maximumButtonCount = 5

  /// The configured buttons, first occurrence of each kept, capped at `maximumButtonCount`: JS
  /// can configure duplicates and more than fit.
  static func buttons<Button: Equatable>(_ configured: [Button]) -> [Button] {
    var buttons: [Button] = []
    for button in configured where !buttons.contains(button) {
      buttons.append(button)
    }
    return Array(buttons.prefix(maximumButtonCount))
  }

  /// Whether a pick in the Up Next list may change the track: an unset `skipToQueueItem`
  /// capability counts as on.
  static func queueItemPickAllowed(_ skipToQueueItem: Bool?) -> Bool {
    skipToQueueItem != false
  }

  enum QueueItemPick: Equatable {
    case skip
    case refuse
    /// The queue changed after the row was built, so its index may name another track.
    case rebuild
  }

  /// What a pick of an Up Next row built for `rowQueueVersion` does, against the queue's
  /// `queueVersion` now (nil without a player).
  static func queueItemPick(skipToQueueItem: Bool?, rowQueueVersion: Int, queueVersion: Int?) -> QueueItemPick {
    guard queueItemPickAllowed(skipToQueueItem), let queueVersion else { return .refuse }
    return queueVersion == rowQueueVersion ? .skip : .rebuild
  }

  /// Whether the Up Next button is on: the option allows it, a pick is allowed, and a track
  /// follows the current one.
  static func upNextEnabled(option: Bool, skipToQueueItem: Bool?, upNextCount: Int) -> Bool {
    option && queueItemPickAllowed(skipToQueueItem) && upNextCount > 0
  }

  static func repeatMode(after mode: RepeatMode) -> RepeatMode {
    switch mode {
    case .off: .track
    case .track: .queue
    case .queue: .off
    }
  }

  /// The rate after `current` in `rates`: the next one, wrapping, when `current` is one of them;
  /// else the first rate above it, else the first. Nil when `rates` is empty.
  static func rate(after current: Double, in rates: [Double]) -> Double? {
    guard !rates.isEmpty else { return nil }
    if let index = rates.firstIndex(where: { (current - $0).magnitude < 0.01 }) {
      return rates[(index + 1) % rates.count]
    }
    return rates.first { $0 > current } ?? rates[0]
  }
}
