import CarPlay

/// What a CarPlay template shows, kept in its `userInfo`.
struct CarPlayTemplateInfo {
  enum Kind {
    /// Content that `path` resolves to; loaded lazily and refreshed.
    case content
    /// The Browse Gate's page for `path`, which is never loaded.
    case gate
  }

  /// The browse path the template stands for. Nil only for a gate page shown at the root.
  let path: String?
  let kind: Kind
}

extension CPTemplate {
  var carPlayInfo: CarPlayTemplateInfo? {
    get { userInfo as? CarPlayTemplateInfo }
    set { userInfo = newValue }
  }

  /// The path this template loads content from; nil for gate and message pages.
  var contentPath: String? {
    guard let info = carPlayInfo, info.kind == .content else { return nil }
    return info.path
  }
}

extension CPListTemplate {
  /// A list template whose content is still resolving: the system spinner on iOS 18.4+, and
  /// the app's `loadingTitle`, when it set one, as the empty-view subtitle. A title-slot hint
  /// would render at CarPlay's large empty-state size.
  static func loading(title: String?, path: String?, loadingTitle: String?) -> CPListTemplate {
    let template = CPListTemplate(title: title, sections: [])
    if let path { template.carPlayInfo = CarPlayTemplateInfo(path: path, kind: .content) }
    if let loadingTitle { template.emptyViewSubtitleVariants = [loadingTitle] }
    if #available(iOS 18.4, *) {
      template.showsSpinnerWhileEmpty = true
    }
    return template
  }

  /// An empty list template showing a centered message. CarPlay renders an empty view reliably
  /// only as a template's initial state, so a message is a new template, never a mutation.
  static func message(
    _ title: String,
    subtitle: String?,
    listTitle: String? = nil,
    info: CarPlayTemplateInfo? = nil,
  ) -> CPListTemplate {
    let template = CPListTemplate(title: listTitle, sections: [])
    template.setMessage(title, subtitle: subtitle)
    template.carPlayInfo = info
    return template
  }

  /// Newlines collapse to spaces: the variants are width alternatives, not lines.
  func setMessage(_ title: String, subtitle: String?) {
    emptyViewTitleVariants = [title]
    emptyViewSubtitleVariants = subtitle.flatMap { $0.isEmpty ? nil : [$0.replacingOccurrences(of: "\n", with: " ")] } ?? []
  }
}
