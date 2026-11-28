import CarPlay

enum ArtworkWake {
  case networkChanged
  case appeared(CPTemplate)
}

extension CarPlaySession {
  /// How a failed artwork load for `row` waits to be retried. A wait for the network or a visit
  /// ends, and the retries with it, at the first wake after the row is gone, or when the session
  /// ends.
  func artworkRetryWaits(for row: AnyObject) -> ArtworkRetryWaits {
    let wakeups = artworkWakeups
    let networkMonitor = audioBrowser.networkMonitor
    return ArtworkRetryWaits(
      wanted: { [weak row] in row != nil },
      sleep: { try await Task.sleep(for: .milliseconds($0)) },
      online: { [weak row] in
        guard !networkMonitor.isOnline else { return false }
        try await wakeups.wait { [weak row] _ in .waiting(for: row, ready: networkMonitor.isOnline) }
        return true
      },
      shown: { [weak row] in
        try await wakeups.wait { [weak row] wake in
          guard case let .appeared(template) = wake, let row else { return .waiting(for: row, ready: false) }
          return .waiting(for: row, ready: Self.template(template, shows: row))
        }
      },
    )
  }

  /// The template on screen: the top of the stack, or the tab it shows. An appearance is reported
  /// for the tab bar when Now Playing pops off a list pushed above it.
  var visibleTemplate: CPTemplate? {
    let top = interfaceController.topTemplate
    return (top as? CPTabBarTemplate)?.selectedTemplate ?? top
  }

  private static func template(_ template: CPTemplate, shows row: AnyObject) -> Bool {
    guard let list = template as? CPListTemplate else { return false }
    return list.sections.contains { section in section.items.contains { $0 === row } }
  }
}
