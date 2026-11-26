#if canImport(NitroModules)
  import NitroModules
#endif

extension Section {
  /// Returns a copy with the given fields replaced. An omitted argument keeps the field;
  /// `.some(nil)` clears it. `children` is required, so it can only be replaced.
  func copying(
    title: String?? = nil,
    style: SectionStyle?? = nil,
    path: String?? = nil,
    queue: SectionQueue?? = nil,
    children: [Track]? = nil,
  ) -> Section {
    Section(
      title: title ?? self.title,
      style: style ?? self.style,
      path: path ?? self.path,
      queue: queue ?? self.queue,
      children: children ?? self.children,
    )
  }

  /// The canonical wrap of a flat track list into one section (ADR 0010).
  static func untitled(_ children: [Track]) -> Section {
    Section(title: nil, style: nil, path: nil, queue: nil, children: children)
  }
}
