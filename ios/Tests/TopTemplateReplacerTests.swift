import Foundation
import Testing

@testable import AudioBrowserTestable

private final class Page {
  let name: String
  init(_ name: String) { self.name = name }
}

/// A template stack whose mutations complete only when the test steps them,
/// the way CarPlay's complete on a later run-loop turn.
@MainActor
private final class FakeStack: TemplateStack {
  private(set) var templates: [Page]
  private(set) var calls: [String] = []
  var failingPushes: Set<String> = []
  var failsPop = false
  private var pending: [() -> Void] = []

  init(_ names: String...) {
    templates = names.map(Page.init)
  }

  var names: [String] {
    templates.map(\.name)
  }

  func popTop(completion: @escaping (Bool, Error?) -> Void) {
    calls.append("pop")
    pending.append { [self] in
      guard !failsPop else {
        completion(false, nil)
        return
      }
      templates.removeLast()
      completion(true, nil)
    }
  }

  func push(_ template: Page, animated: Bool, completion: @escaping (Bool, Error?) -> Void) {
    calls.append("push \(template.name)\(animated ? " animated" : "")")
    pending.append { [self] in
      guard !failingPushes.contains(template.name) else {
        completion(false, nil)
        return
      }
      templates.append(template)
      completion(true, nil)
    }
  }

  func step() {
    pending.removeFirst()()
  }

  func run() {
    while !pending.isEmpty {
      step()
    }
  }
}

@Suite("TopTemplateReplacer")
@MainActor
struct TopTemplateReplacerTests {
  private let replacer = TopTemplateReplacer()
  private let stack = FakeStack("tabs", "A", "B", "C", "D")

  @Test func replacesTheTopPageAndKeepsTheRest() {
    var result: Bool?
    replacer.push(Page("E"), replacingTopOf: stack, animated: true) { pushed, _ in result = pushed }
    stack.run()

    #expect(stack.names == ["tabs", "A", "B", "C", "E"])
    #expect(stack.calls == ["pop", "push E animated"])
    #expect(result == true)
    #expect(!replacer.isReplacing)
  }

  @Test func thePushWaitsForThePopToComplete() {
    replacer.push(Page("E"), replacingTopOf: stack, animated: true)
    #expect(stack.calls == ["pop"])
    stack.step()
    #expect(stack.calls == ["pop", "push E animated"])
  }

  @Test func aMutationArrivingMidReplacementRunsOnceItHasSettled() {
    var seen: [[String]] = []
    replacer.push(Page("E"), replacingTopOf: stack, animated: true)
    stack.step()
    replacer.whenSettled { seen.append(stack.names) }
    #expect(seen.isEmpty)
    stack.run()

    #expect(seen == [["tabs", "A", "B", "C", "E"]])
  }

  @Test func deferredMutationsRunInArrivalOrderBehindASecondReplacement() {
    var order: [String] = []
    replacer.push(Page("E"), replacingTopOf: stack, animated: true)
    replacer.whenSettled { [replacer, stack] in
      order.append("F")
      replacer.push(Page("F"), replacingTopOf: stack, animated: true)
    }
    replacer.whenSettled { order.append("after F: \(stack.names.last ?? "")") }
    stack.run()

    #expect(stack.names == ["tabs", "A", "B", "C", "F"])
    #expect(order == ["F", "after F: F"])
  }

  @Test func aMutationIssuedByTheCompletionQueuesBehindEarlierArrivals() {
    var order: [String] = []
    replacer.push(Page("E"), replacingTopOf: stack, animated: true) { [replacer] _, _ in
      replacer.whenSettled { order.append("from completion") }
    }
    replacer.whenSettled { order.append("arrived mid-replacement") }
    stack.run()

    #expect(order == ["arrived mid-replacement", "from completion"])
  }

  @Test func aFailedPopLeavesTheStackAsItWas() {
    stack.failsPop = true
    var result: Bool?
    replacer.push(Page("E"), replacingTopOf: stack, animated: true) { pushed, _ in result = pushed }
    stack.run()

    #expect(stack.names == ["tabs", "A", "B", "C", "D"])
    #expect(stack.calls == ["pop"])
    #expect(result == false)
    #expect(!replacer.isReplacing)
  }

  @Test func aFailedPushRestoresThePoppedPage() {
    stack.failingPushes = ["E"]
    let top = stack.templates[4]
    var result: Bool?
    var ranAfter = false
    replacer.push(Page("E"), replacingTopOf: stack, animated: true) { pushed, _ in result = pushed }
    replacer.whenSettled { ranAfter = true }
    stack.run()

    #expect(stack.names == ["tabs", "A", "B", "C", "D"])
    #expect(stack.templates[4] === top)
    #expect(stack.calls == ["pop", "push E animated", "push D"])
    #expect(result == false)
    #expect(ranAfter)
  }

  @Test func aFailedRestoreStillSettles() {
    stack.failingPushes = ["E", "D"]
    var result: Bool?
    replacer.push(Page("E"), replacingTopOf: stack, animated: true) { pushed, _ in result = pushed }
    stack.run()

    #expect(stack.names == ["tabs", "A", "B", "C"])
    #expect(result == false)
    #expect(!replacer.isReplacing)
  }

  @Test func refusesAStackWithNothingAboveTheRoot() {
    let rootOnly = FakeStack("tabs")
    var result: Bool?
    replacer.push(Page("E"), replacingTopOf: rootOnly, animated: true) { pushed, _ in result = pushed }

    #expect(rootOnly.calls.isEmpty)
    #expect(result == false)
    #expect(!replacer.isReplacing)
  }
}
