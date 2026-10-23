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
    let loads = displayedListTemplates().compactMap { template in
      template.contentPath.map { path in
        (template, Task<ResolvedTrack?, Never> { @MainActor [logger] in
          do {
            return try await browserManager.resolve(path, useCache: false)
          } catch {
            logger.error("invalidateAllContent: failed to refresh \(path): \(error.localizedDescription)")
            return nil
          }
        })
      }
    }
    for load in loads {
      guard let resolved = await load.1.value, !Task.isCancelled else { continue }
      updateTemplate(load.0, with: resolved)
    }
  }
}
