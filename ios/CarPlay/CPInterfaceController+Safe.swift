import CarPlay
import os.log

/// Logged, precondition-guarded wrappers around `CPInterfaceController`'s
/// template-stack mutations.
///
/// Per the SDK header, an unsuccessful push/setRoot/present with a nil
/// completion doesn't fail quietly — it throws an Objective-C exception (depth
/// limit included) that takes down the whole app, not just the CarPlay scene;
/// pushing a template that is already in the hierarchy raises regardless.
/// Every stack mutation therefore goes through these helpers: the raise-prone
/// preconditions are checked up front, the underlying call always gets a
/// completion, and every failure is logged, with an optional caller completion
/// for call sites that chain on the result.
@MainActor
extension CPInterfaceController {
  private static let stackLogger = Logger(subsystem: "com.audiobrowser", category: "TemplateStack")

  private static func name(of template: CPTemplate) -> String {
    String(describing: type(of: template))
  }

  /// CarPlay's template-stack depth cap for audio apps. Not exposed by the SDK;
  /// exceeding it raises `NSGenericException` ("Application exceeded the
  /// hierarchy depth limit") synchronously — even through the completion-based
  /// push — taking down the whole app, not just the CarPlay scene.
  private static let maximumStackDepth = 5

  /// Pushes `template`, skipping the raise cases (already in the hierarchy,
  /// stack at the depth limit).
  func safePush(_ template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    guard !templates.contains(where: { $0 === template }) else {
      Self.stackLogger.warning("push skipped: \(Self.name(of: template)) is already in the stack")
      completion?(false, nil)
      return
    }
    guard templates.count < Self.maximumStackDepth else {
      Self.stackLogger.error("push skipped: \(Self.name(of: template)) would exceed the depth limit (\(self.templates.count) templates)")
      completion?(false, nil)
      return
    }
    pushTemplate(template, animated: animated) { pushed, error in
      if let error {
        Self.stackLogger.error("pushTemplate \(Self.name(of: template)) failed: \(error.localizedDescription)")
      }
      completion?(pushed, error)
    }
  }

  /// Pops the top template, skipping the raise case (nothing above the root).
  func safePop(animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    guard templates.count > 1 else {
      Self.stackLogger.warning("pop skipped: already at the root template")
      completion?(false, nil)
      return
    }
    popTemplate(animated: animated) { popped, error in
      if let error {
        Self.stackLogger.error("popTemplate failed: \(error.localizedDescription)")
      }
      completion?(popped, error)
    }
  }

  /// Pops back to `template`, skipping the raise case (not in the hierarchy).
  /// A no-op success when it is already the top template.
  func safePop(to template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    guard templates.contains(where: { $0 === template }) else {
      Self.stackLogger.warning("pop(to:) skipped: \(Self.name(of: template)) is not in the stack")
      completion?(false, nil)
      return
    }
    guard topTemplate !== template else {
      completion?(true, nil)
      return
    }
    pop(to: template, animated: animated) { popped, error in
      if let error {
        Self.stackLogger.error("pop(to:) \(Self.name(of: template)) failed: \(error.localizedDescription)")
      }
      completion?(popped, error)
    }
  }

  func safePopToRoot(animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    popToRootTemplate(animated: animated) { popped, error in
      if let error {
        Self.stackLogger.error("popToRootTemplate failed: \(error.localizedDescription)")
      }
      completion?(popped, error)
    }
  }

  func safeSetRoot(_ template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    setRootTemplate(template, animated: animated) { set, error in
      if let error {
        Self.stackLogger.error("setRootTemplate \(Self.name(of: template)) failed: \(error.localizedDescription)")
      }
      completion?(set, error)
    }
  }

  func safePresent(_ template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    presentTemplate(template, animated: animated) { presented, error in
      if let error {
        Self.stackLogger.error("presentTemplate \(Self.name(of: template)) failed: \(error.localizedDescription)")
      }
      completion?(presented, error)
    }
  }

  /// Dismisses the presented template, skipping when nothing is presented.
  func safeDismiss(animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    guard presentedTemplate != nil else {
      Self.stackLogger.debug("dismiss skipped: no presented template")
      completion?(false, nil)
      return
    }
    dismissTemplate(animated: animated) { dismissed, error in
      if let error {
        Self.stackLogger.error("dismissTemplate failed: \(error.localizedDescription)")
      }
      completion?(dismissed, error)
    }
  }
}
