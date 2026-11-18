enum NowPlayingButtonRules {
  /// `CPNowPlayingTemplate` takes at most five custom buttons and does not say what happens to
  /// more.
  static let maximumButtonCount = 5

  static func buttons<Button: Equatable>(_ configured: [Button]) -> [Button] {
    var buttons: [Button] = []
    for button in configured where !buttons.contains(button) {
      buttons.append(button)
    }
    return Array(buttons.prefix(maximumButtonCount))
  }

  static func repeatMode(after mode: RepeatMode) -> RepeatMode {
    switch mode {
    case .off: .track
    case .track: .queue
    case .queue: .off
    }
  }

  static func rate(after current: Double, in rates: [Double]) -> Double? {
    guard !rates.isEmpty else { return nil }
    if let index = rates.firstIndex(where: { (current - $0).magnitude < 0.01 }) {
      return rates[(index + 1) % rates.count]
    }
    return rates.first { $0 > current } ?? rates[0]
  }
}
