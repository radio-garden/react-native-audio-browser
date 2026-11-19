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

  /// Whether the Up Next button is on: the option and the `skipToQueueItem` capability both allow
  /// it, an unset capability counting as on, and the queue has another track to go to.
  static func upNextEnabled(option: Bool, skipToQueueItem: Bool?, trackCount: Int) -> Bool {
    option && skipToQueueItem != false && trackCount > 1
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
