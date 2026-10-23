import CarPlay
import NitroModules

extension CarPlaySession {
  /// Loads a content template's page when it first appears, the moment CarPlay applies updates
  /// (an update made straight after a push is dropped).
  func loadContentIfNeeded(for template: CPListTemplate) {
    guard let path = template.contentPath, template.sections.isEmpty else { return }
    spawn { await $0.loadContent(for: path, into: template) }
  }

  /// Resolves `path` into a loading template, showing a centered message when the page is
  /// empty, fails, or outlasts `resolveTimeout`.
  func loadContent(for path: String, into template: CPListTemplate) async {
    let templateId = ObjectIdentifier(template)
    guard !loadingTemplates.contains(templateId) else { return }
    loadingTemplates.insert(templateId)
    defer { loadingTemplates.remove(templateId) }

    let timeout = resolveTimeout
    let watchdog = Task { @MainActor [weak self] in
      do { try await Task.sleep(for: timeout) } catch { return }
      guard let self, template.sections.isEmpty else { return }
      logger.error("loadContent: resolve timed out for \(path)")
      await showNavigationError(
        NavigationError(code: .timeout, message: "", statusCode: nil, statusCodeSuccess: nil), path: path, on: template,
      )
    }
    let outcome: Result<ResolvedTrack, Error>
    do {
      outcome = try await .success(audioBrowser.browserManager.resolve(path, useCache: true))
    } catch {
      outcome = .failure(error)
    }
    // As soon as the resolve settles: formatting its error is a JS round trip the timeout must
    // not overwrite.
    watchdog.cancel()

    switch outcome {
    case let .success(resolved):
      guard !Task.isCancelled else { return }
      // An assistant cell is content in itself, even with no rows; a page of empty sections is
      // empty (ADR 0001).
      let hasChildren = resolved.normalizedSections?.contains { !$0.children.isEmpty } ?? false
      if hasChildren || resolved.carPlaySiriListButton != nil {
        updateTemplate(template, with: resolved)
      } else {
        await showNavigationError(.code(.emptyContent), path: path, on: template)
      }
    case let .failure(error):
      logger.error("Failed to load content for \(path): \(error.localizedDescription)")
      await showNavigationError(NavigationError.from(error), path: path, on: template)
    }
  }

  func updateTemplate(_ template: CPListTemplate, with resolvedTrack: ResolvedTrack) {
    template.updateSections(listItemFactory.createSections(from: resolvedTrack))
    template.assistantCellConfiguration = resolvedTrack.carPlaySiriListButton.map { position in
      CPAssistantCellConfiguration(
        position: position == .top ? .top : .bottom, visibility: .always, assistantAction: .playMedia,
      )
    }
  }

  /// Shows a navigation error, as the app's `formatNavigationError` formats it, as the
  /// template's centered message (ADR 0001).
  private func showNavigationError(_ error: NavigationError, path: String, on template: CPListTemplate) async {
    let formatted = await audioBrowser.browserManager.formatted(error, path: path)
    guard !Task.isCancelled else { return }
    let title = formatted.title.isEmpty ? "Couldn't load" : formatted.title
    if isTabRoot(template) {
      // A tab root cannot be popped, so its message is set in place; it renders when the tab
      // is selected.
      if #available(iOS 18.4, *) {
        template.showsSpinnerWhileEmpty = false
      }
      template.setMessage(title, subtitle: formatted.message)
      template.updateSections([])
    } else {
      // A pushed template's empty view renders reliably only as its initial state, so it is
      // replaced. The message has no path: backing out and re-tapping retries.
      interfaceController.safeReplaceTop(
        template, with: CPListTemplate.message(title, subtitle: formatted.message, listTitle: template.title), animated: false,
      )
    }
  }

  private func isTabRoot(_ template: CPTemplate) -> Bool {
    (interfaceController.rootTemplate as? CPTabBarTemplate)?.templates.contains { $0 === template } ?? false
  }
}
