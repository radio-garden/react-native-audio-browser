import Foundation
import Testing

@testable import AudioBrowserTestable

// MARK: - Emit

@Suite("emit")
struct EmitTests {
  @Test func listenerReceivesEmittedValue() {
    let emitter = Emitter<Int>()
    var received: Int?
    emitter.addListener { received = $0 }
    emitter.emit(42)
    #expect(received == 42)
  }

  @Test func multipleListenersAllCalled() {
    let emitter = Emitter<String>()
    var calls = [String]()
    emitter.addListener { calls.append("a:\($0)") }
    emitter.addListener { calls.append("b:\($0)") }
    emitter.emit("hello")
    #expect(calls == ["a:hello", "b:hello"])
  }

  @Test func listenersCalledInRegistrationOrder() {
    let emitter = Emitter<Void>()
    var order = [Int]()
    emitter.addListener { order.append(1) }
    emitter.addListener { order.append(2) }
    emitter.addListener { order.append(3) }
    emitter.emit(())
    #expect(order == [1, 2, 3])
  }

  @Test func noCrashWithZeroListeners() {
    let emitter = Emitter<Int>()
    emitter.emit(99)
    // reaching here without crash is the assertion
  }
}

// MARK: - removeListener

@Suite("removeListener")
struct RemoveListenerTests {
  @Test func removedListenerStopsReceiving() {
    let emitter = Emitter<Int>()
    var received = [Int]()
    let token = emitter.addListener { received.append($0) }
    emitter.emit(1)
    emitter.removeListener(token)
    emitter.emit(2)
    #expect(received == [1])
  }

  @Test func otherListenersUnaffected() {
    let emitter = Emitter<Int>()
    var a = [Int]()
    var b = [Int]()
    let tokenA = emitter.addListener { a.append($0) }
    emitter.addListener { b.append($0) }
    emitter.removeListener(tokenA)
    emitter.emit(5)
    #expect(a.isEmpty)
    #expect(b == [5])
  }

  @Test func doubleRemoveSameTokenIsSafe() {
    let emitter = Emitter<Int>()
    let token = emitter.addListener { _ in }
    emitter.removeListener(token)
    emitter.removeListener(token)
    #expect(emitter.listenerCount == 0)
  }
}

// MARK: - removeAllListeners

@Suite("removeAllListeners")
struct RemoveAllListenersTests {
  @Test func clearsAllAndCountGoesToZero() {
    let emitter = Emitter<Int>()
    emitter.addListener { _ in }
    emitter.addListener { _ in }
    emitter.addListener { _ in }
    emitter.removeAllListeners()
    #expect(emitter.listenerCount == 0)
  }

  @Test func previouslyAddedListenersStopReceiving() {
    let emitter = Emitter<Int>()
    var called = false
    emitter.addListener { _ in called = true }
    emitter.removeAllListeners()
    emitter.emit(1)
    #expect(called == false)
  }
}

// MARK: - listenerCount

@Suite("listenerCount")
struct ListenerCountTests {
  @Test func reflectsAddsAndRemoves() {
    let emitter = Emitter<Int>()
    #expect(emitter.listenerCount == 0)

    let t1 = emitter.addListener { _ in }
    #expect(emitter.listenerCount == 1)

    let t2 = emitter.addListener { _ in }
    #expect(emitter.listenerCount == 2)

    emitter.removeListener(t1)
    #expect(emitter.listenerCount == 1)

    emitter.removeListener(t2)
    #expect(emitter.listenerCount == 0)
  }
}

// MARK: - subscribe

@Suite("subscribe")
struct SubscribeTests {
  @Test func receivesUntilCancelled() {
    let emitter = Emitter<Int>()
    var received: [Int] = []
    let subscription = emitter.subscribe { received.append($0) }
    emitter.emit(1)
    subscription.cancel()
    emitter.emit(2)
    #expect(received == [1])
    #expect(emitter.listenerCount == 0)
  }

  @Test func releasingTheSubscriptionRemovesTheListener() {
    let emitter = Emitter<Int>()
    var received: [Int] = []
    do {
      let subscription = emitter.subscribe { received.append($0) }
      emitter.emit(1)
      _ = subscription
    }
    emitter.emit(2)
    #expect(received == [1])
    #expect(emitter.listenerCount == 0)
  }
}
