import CarPlay

struct CarPlayTemplateInfo {
  enum Kind {
    case content
    case gate
  }

  let path: String?
  let kind: Kind
}

extension CPTemplate {
  var carPlayInfo: CarPlayTemplateInfo? {
    get { userInfo as? CarPlayTemplateInfo }
    set { userInfo = newValue }
  }

  var contentPath: String? {
    guard let info = carPlayInfo, info.kind == .content else { return nil }
    return info.path
  }
}

extension CPListTemplate {
  /// The system spinner on iOS 18.4+, and `loadingTitle` as the empty-view subtitle, since the
  /// title slot renders at CarPlay's large empty-state size.
  static func loading(title: String?, path: String?, loadingTitle: String?) -> CPListTemplate {
    let template = CPListTemplate(title: title, sections: [])
    if let path { template.carPlayInfo = CarPlayTemplateInfo(path: path, kind: .content) }
    if let loadingTitle { template.emptyViewSubtitleVariants = [loadingTitle] }
    if #available(iOS 18.4, *) {
      template.showsSpinnerWhileEmpty = true
    }
    return template
  }

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
