import CarPlay

extension CarPlaySession {
  /// How a failed artwork load for `row` waits to be retried (`ArtworkRowWaits`).
  func artworkRetryWaits(for row: AnyObject) -> ArtworkRetryWaits {
    let networkMonitor = audioBrowser.networkMonitor
    return ArtworkRowWaits.make(
      for: row,
      wakeups: artworkWakeups,
      isOnline: { networkMonitor.isOnline },
      isListed: { [weak self] row in
        self?.displayedListTemplates().contains { Self.template($0, lists: row) } ?? false
      },
      shows: { template, row in Self.template(template, lists: row) },
    )
  }

  /// Wakes the artwork waits for `template`, and for the template on screen when that differs:
  /// popping Now Playing off a list pushed above the tab bar reports the tab bar.
  func postArtworkShown(_ template: CPTemplate) {
    let top = interfaceController.topTemplate
    let visible = (top as? CPTabBarTemplate)?.selectedTemplate ?? top
    artworkWakeups.post(.shown(template))
    if let visible, visible !== template { artworkWakeups.post(.shown(visible)) }
  }

  private static func template(_ template: CPTemplate, lists row: AnyObject) -> Bool {
    let shown = (template as? CPTabBarTemplate)?.selectedTemplate ?? template
    guard let list = shown as? CPListTemplate else { return false }
    return list.sections.contains { section in section.items.contains { $0 === row } }
  }
}
