import CarPlay
import os.log

/// Logged, guarded wrappers around `CPInterfaceController`'s template-stack mutations.
///
/// Per the SDK header, a failed push/setRoot/present with a nil completion raises an
/// Objective-C exception (depth limit included) that takes down the whole app, not just the
/// CarPlay scene; pushing a template already in the hierarchy raises regardless. So every
/// stack mutation goes through here: raise-prone preconditions are checked first, the
/// underlying call always gets a completion, and every failure is logged.
///
/// A push onto a full stack replaces the top template (`TopTemplateReplacer`); every mutation
/// waits for a replacement to settle, so none lands between its pop and its push.
@MainActor
extension CPInterfaceController {
  private static let stackLogger = Logger(subsystem: "com.audiobrowser", category: "TemplateStack")

  private static func name(of template: CPTemplate) -> String {
    String(describing: type(of: template))
  }

  // CarPlay's stack depth cap for audio apps, which the SDK does not expose. Exceeding it
  // raises `NSGenericException` synchronously, even through the completion-based push.
  private static let maximumStackDepth = 5

  private static var topReplacerKey: UInt8 = 0

  private var topReplacer: TopTemplateReplacer {
    if let replacer = objc_getAssociatedObject(self, &Self.topReplacerKey) as? TopTemplateReplacer {
      return replacer
    }
    let replacer = TopTemplateReplacer()
    objc_setAssociatedObject(self, &Self.topReplacerKey, replacer, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    return replacer
  }

  private func whenStackSettled(_ mutation: @escaping (CPInterfaceController) -> Void) {
    topReplacer.whenSettled { [weak self] in
      guard let self else { return }
      mutation(self)
    }
  }

  /// Pushes `template`, skipping one already in the hierarchy. At the depth limit it replaces
  /// the top template.
  func safePush(_ template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    whenStackSettled { controller in
      let templates = controller.templates
      guard templates.count >= Self.maximumStackDepth, !templates.contains(where: { $0 === template }) else {
        controller.guardedPush(template, animated: animated, completion: completion)
        return
      }
      controller.topReplacer.push(
        template, replacingTopOf: GuardedTemplateStack(controller: controller), animated: animated,
        completion: completion,
      )
    }
  }

  /// Replaces `expected` while it is still the top template, restoring it if the push fails; a
  /// no-op once the user has moved on.
  func safeReplaceTop(_ expected: CPTemplate, with template: CPTemplate, animated: Bool) {
    whenStackSettled { controller in
      guard controller.topTemplate === expected else { return }
      controller.topReplacer.push(
        template, replacingTopOf: GuardedTemplateStack(controller: controller), animated: animated,
      )
    }
  }

  fileprivate func guardedPush(_ template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)?) {
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

  /// Skips the pop when only the root is left, which would raise.
  fileprivate func guardedPop(animated: Bool, completion: ((Bool, Error?) -> Void)?) {
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

  /// Pops back to `template`, skipping one not in the hierarchy. Succeeds without popping
  /// when it is already the top template.
  func safePop(to template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    whenStackSettled { controller in
      guard controller.templates.contains(where: { $0 === template }) else {
        Self.stackLogger.warning("pop(to:) skipped: \(Self.name(of: template)) is not in the stack")
        completion?(false, nil)
        return
      }
      guard controller.topTemplate !== template else {
        completion?(true, nil)
        return
      }
      controller.pop(to: template, animated: animated) { popped, error in
        if let error {
          Self.stackLogger.error("pop(to:) \(Self.name(of: template)) failed: \(error.localizedDescription)")
        }
        completion?(popped, error)
      }
    }
  }

  func safePopToRoot(animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    whenStackSettled { controller in
      controller.popToRootTemplate(animated: animated) { popped, error in
        if let error {
          Self.stackLogger.error("popToRootTemplate failed: \(error.localizedDescription)")
        }
        completion?(popped, error)
      }
    }
  }

  func safeSetRoot(_ template: CPTemplate, animated: Bool, completion: ((Bool, Error?) -> Void)? = nil) {
    whenStackSettled { controller in
      controller.setRootTemplate(template, animated: animated) { set, error in
        if let error {
          Self.stackLogger.error("setRootTemplate \(Self.name(of: template)) failed: \(error.localizedDescription)")
        }
        completion?(set, error)
      }
    }
  }
}

/// The guarded pop and push a replacement is made of, so a push that still cannot fit is
/// skipped and logged rather than raising.
@MainActor
private struct GuardedTemplateStack: TemplateStack {
  let controller: CPInterfaceController

  var templates: [CPTemplate] {
    controller.templates
  }

  func popTop(completion: @escaping (Bool, Error?) -> Void) {
    controller.guardedPop(animated: false, completion: completion)
  }

  func push(_ template: CPTemplate, animated: Bool, completion: @escaping (Bool, Error?) -> Void) {
    controller.guardedPush(template, animated: animated, completion: completion)
  }
}
