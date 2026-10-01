import Foundation
import os.log

/// The stack mutations a top-template replacement is made of:
/// `CPInterfaceController` in the app, a fake in tests.
@MainActor
protocol TemplateStack {
  associatedtype Template: AnyObject

  /// Root first.
  var templates: [Template] { get }

  /// Unanimated.
  func popTop(completion: @escaping (Bool, Error?) -> Void)
  func push(_ template: Template, animated: Bool, completion: @escaping (Bool, Error?) -> Void)
}

/// Pushes onto a full template stack by replacing its top template — an
/// unanimated pop, then the push — and holds back every other stack mutation
/// until both have completed. See "Template Depth" in
/// `website/guide/carplay.md`.
@MainActor
final class TopTemplateReplacer {
  private let logger = Logger(subsystem: "com.audiobrowser", category: "TemplateStack")
  private var deferred: [() -> Void] = []

  /// True from the pop until the push completes.
  private(set) var isReplacing = false

  /// Runs `mutation` now, or — while a replacement is in flight — once it has
  /// settled, after the mutations that arrived before it.
  func whenSettled(_ mutation: @escaping () -> Void) {
    guard isReplacing else {
      mutation()
      return
    }
    deferred.append(mutation)
  }

  /// Pushes `template` onto `stack` in place of its top template. When the
  /// push fails, the popped template is pushed back, so the user stays where
  /// they were. `completion` reports the push of `template` itself.
  ///
  /// Refuses a stack with nothing above the root to replace, and a call made
  /// while a replacement is in flight: route it through `whenSettled`.
  func push<Stack: TemplateStack>(
    _ template: Stack.Template,
    replacingTopOf stack: Stack,
    animated: Bool,
    completion: ((Bool, Error?) -> Void)? = nil,
  ) {
    let templates = stack.templates
    guard !isReplacing, templates.count > 1, let top = templates.last else {
      logger.error("push skipped: \(Self.name(of: template)) has no template to replace (\(templates.count) templates)")
      completion?(false, nil)
      return
    }
    isReplacing = true
    logger.info("push at the depth limit: replacing the top, \(Self.name(of: top)), with \(Self.name(of: template))")

    stack.popTop { [self] popped, error in
      guard popped else {
        logger.error("replace abandoned: pop failed, \(Self.name(of: template)) not pushed (\(stack.templates.count) templates)")
        settle(completion, pushed: false, error: error)
        return
      }
      stack.push(template, animated: animated) { [self] pushed, error in
        guard !pushed else {
          settle(completion, pushed: true, error: error)
          return
        }
        logger.error("replace failed: \(Self.name(of: template)) not pushed, restoring \(Self.name(of: top))")
        stack.push(top, animated: false) { [self] restored, _ in
          if !restored {
            logger.error("replace failed: \(Self.name(of: top)) not restored (\(stack.templates.count) templates)")
          }
          settle(completion, pushed: false, error: error)
        }
      }
    }
  }

  private func settle(_ completion: ((Bool, Error?) -> Void)?, pushed: Bool, error: Error?) {
    // Still replacing here, so a mutation the completion issues queues behind
    // the ones that arrived during the replacement.
    completion?(pushed, error)
    isReplacing = false
    while !isReplacing, !deferred.isEmpty {
      deferred.removeFirst()()
    }
  }

  private static func name(of template: AnyObject) -> String {
    String(describing: type(of: template))
  }
}
