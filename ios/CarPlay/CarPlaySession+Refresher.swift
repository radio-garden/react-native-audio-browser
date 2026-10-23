import CarPlay
import NitroModules

extension CarPlaySession {
  /// Re-resolves `path`, bypassing the cache, into every displayed template showing it.
  func refreshContent(for path: String) async {
    do {
      let resolved = try await audioBrowser.browserManager.resolve(path, useCache: false)
      guard !Task.isCancelled else { return }
      refreshDisplayedTemplates(for: path, with: resolved)
    } catch {
      logger.error("Failed to refresh content for \(path): \(error.localizedDescription)")
    }
  }

  func refreshDisplayedTemplates(for path: String, with content: ResolvedTrack) {
    for template in displayedListTemplates() where template.contentPath == path {
      updateTemplate(template, with: content)
    }
  }

  /// Re-resolves every displayed template at once, bypassing the cache: all content was
  /// invalidated (e.g. a locale change).
  func refreshAllDisplayedTemplates() async {
    let browserManager = audioBrowser.browserManager
    let targets = displayedListTemplates().compactMap { template in template.contentPath.map { (template, $0) } }
    let pages = await targets.map(\.1).concurrentMap { [logger] path -> ResolvedTrack? in
      do {
        return try await browserManager.resolve(path, useCache: false)
      } catch {
        logger.error("invalidateAllContent: failed to refresh \(path): \(error.localizedDescription)")
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
