/// What CarPlay's Now Playing custom buttons show and do.
enum NowPlayingButtonRules {
  /// `CPNowPlayingTemplate` takes at most five custom buttons and does not say what happens to
  /// more.
  static let maximumButtonCount = 5

  /// The configured buttons, first occurrence of each kept, capped at `maximumButtonCount`:
  /// the configuration comes from JS, where duplicates and excess are representable.
  static func buttons<Button: Equatable>(_ configured: [Button]) -> [Button] {
    var buttons: [Button] = []
    for button in configured where !buttons.contains(button) {
      buttons.append(button)
    }
    return Array(buttons.prefix(maximumButtonCount))
  }

  /// The repeat button cycles off → track → queue → off.
  static func repeatMode(after mode: RepeatMode) -> RepeatMode {
    switch mode {
    case .off: .track
    case .track: .queue
    case .queue: .off
    }
  }

  /// The rate after `current` in `rates`: the next one, wrapping, when `current` is one of them;
  /// else the first rate above it, else the first. Nil when there are no rates.
  static func rate(after current: Double, in rates: [Double]) -> Double? {
    guard !rates.isEmpty else { return nil }
    if let index = rates.firstIndex(where: { (current - $0).magnitude < 0.01 }) {
      return rates[(index + 1) % rates.count]
    }
    return rates.first { $0 > current } ?? rates[0]
  }
}
