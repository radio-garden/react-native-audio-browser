import CarPlay
import NitroModules

extension CarPlaySession {
  /// Resolves `path` into every displayed template showing it; the caller removes or expires the
  /// cached page first.
  func refreshContent(for path: String) async {
    do {
      let resolved = try await audioBrowser.browserManager.resolve(path)
      guard !Task.isCancelled else { return }
      refreshDisplayedTemplates(for: path, with: resolved)
    } catch {
      logger.error("Failed to refresh content for \(path): \(error.localizedDescription)")
    }
  }

  /// Refreshes the list the car shows when its page has expired; the rest refresh on their next
  /// visit. Offline, it waits for the connection.
  func refreshShownPageIfExpired() {
    guard audioBrowser.networkMonitor.isOnline, let path = shownListTemplate()?.contentPath,
          audioBrowser.browserManager.isPageExpired(path)
    else { return }
    spawn { await $0.refreshContent(for: path) }
  }

  func refreshDisplayedTemplates(for path: String, with content: ResolvedTrack) {
    for template in displayedListTemplates() where template.contentPath == path {
      updateTemplate(template, with: content)
    }
  }

  /// Re-resolves every displayed template concurrently, for when all content was invalidated
  /// (e.g. a locale change).
  func refreshAllDisplayedTemplates() async {
    let browserManager = audioBrowser.browserManager
    let targets = displayedListTemplates().compactMap { template in template.contentPath.map { (template, $0) } }
    let pages = await targets.map(\.1).concurrentMap { [logger] path -> ResolvedTrack? in
      do {
        return try await browserManager.resolve(path)
      } catch {
        if !(error is CancellationError || Task.isCancelled) {
          logger.error("invalidateAllContent: failed to refresh \(path): \(error.localizedDescription)")
        }
        return nil
      }
    }
    guard !Task.isCancelled else { return }
    for ((template, _), page) in zip(targets, pages) {
      guard let page else { continue }
      updateTemplate(template, with: page)
    }
  }
}
