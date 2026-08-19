#if canImport(NitroModules)
  import NitroModules
#endif

extension Section {
  /// Returns a copy of this Section with only the specified fields changed.
  ///
  /// Same double-optional (`T??`) convention as the other copying helpers;
  /// `children` is single-optional since it is non-optional on Section.
  func copying(
    title: String?? = nil,
    style: SectionStyle?? = nil,
    path: String?? = nil,
    children: [Track]? = nil,
  ) -> Section {
    Section(
      title: title ?? self.title,
      style: style ?? self.style,
      path: path ?? self.path,
      children: children ?? self.children,
    )
  }

  /// One untitled section — the canonical wrap of a flat track list
  /// (`children` authoring sugar, search results — ADR 0010).
  static func untitled(_ children: [Track]) -> Section {
    Section(title: nil, style: nil, path: nil, children: children)
  }
}
