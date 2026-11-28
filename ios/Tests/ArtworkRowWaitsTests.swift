import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("ArtworkRowWaits")
struct ArtworkRowWaitsTests {
  private final class Row {}

  /// A screen that shows the rows it holds, and a network that is up or down.
  @MainActor
  private final class Car {
    let wakeups = Wakeups<ArtworkWake<String>>()
    var online = true
    var listed: [String: [Row]] = [:]

    func waits(for row: Row) -> ArtworkRetryWaits {
      ArtworkRowWaits.make(
        for: row,
        wakeups: wakeups,
        isOnline: { self.online },
        isListed: { row in self.listed.values.contains { $0.contains { $0 === row } } },
        shows: { screen, row in self.listed[screen]?.contains { $0 === row } ?? false },
        sleep: { _ in },
      )
    }
  }

  private func settle() async {
    for _ in 0 ..< 10 {
      await Task.yield()
    }
  }

  @Test func aRowIsWantedUntilAScreenThatListedItNoLongerDoes() {
    let car = Car()
    let row = Row()
    let waits = car.waits(for: row)
    #expect(waits.wanted())
    car.listed["home"] = [row]
    #expect(waits.wanted())
    car.listed["home"] = [Row()]
    #expect(!waits.wanted())
  }

  @Test func aRowNotYetListedKeepsWaitingAndResumesOnceListedAndShown() async throws {
    let car = Car()
    let row = Row()
    let waits = car.waits(for: row)
    let waiting = Task { @MainActor in try await waits.shown() }
    await settle()
    car.wakeups.post(.sectionsChanged)
    #expect(car.wakeups.waiterCount == 1)
    car.listed["upNext"] = [row]
    car.wakeups.post(.shown("upNext"))
    try await waiting.value
  }

  @Test func aWaitForARowThatIsGoneGivesUpAtTheNextWake() async {
    let car = Car()
    var row: Row? = Row()
    let waits = car.waits(for: row!)
    let waiting = Task { @MainActor in try await waits.shown() }
    await settle()
    row = nil
    car.wakeups.post(.networkChanged)
    await #expect(throws: Wakeups<ArtworkWake<String>>.GaveUp.self) { try await waiting.value }
    #expect(!waits.wanted())
  }

  @Test func onlineReturnsAtOnceWhenTheNetworkIsUp() async throws {
    let car = Car()
    let row = Row()
    car.listed["home"] = [row]
    #expect(try await car.waits(for: row).online() == false)
  }

  @Test func offlineTheWaitEndsWhenTheNetworkComesBack() async throws {
    let car = Car()
    let row = Row()
    car.listed["home"] = [row]
    car.online = false
    let waits = car.waits(for: row)
    let waiting = Task { @MainActor in try await waits.online() }
    await settle()
    car.wakeups.post(.networkChanged)
    #expect(car.wakeups.waiterCount == 1)
    car.online = true
    car.wakeups.post(.networkChanged)
    #expect(try await waiting.value == true)
  }

  @Test func aWaitForARowNoLongerListedGivesUpAtTheNextWake() async {
    let car = Car()
    let row = Row()
    car.listed["home"] = [row]
    car.online = false
    let waits = car.waits(for: row)
    let offline = Task { @MainActor in try await waits.online() }
    let hidden = Task { @MainActor in try await waits.shown() }
    await settle()
    car.listed["home"] = [Row()]
    car.wakeups.post(.sectionsChanged)
    await #expect(throws: Wakeups<ArtworkWake<String>>.GaveUp.self) { try await offline.value }
    await #expect(throws: Wakeups<ArtworkWake<String>>.GaveUp.self) { try await hidden.value }
    #expect(car.wakeups.waiterCount == 0)
  }

  @Test func shownEndsOnAScreenThatListsTheRowOnly() async throws {
    let car = Car()
    let row = Row()
    car.listed["home"] = [row]
    car.listed["recent"] = [Row()]
    let waits = car.waits(for: row)
    let waiting = Task { @MainActor in try await waits.shown() }
    await settle()
    car.wakeups.post(.shown("recent"))
    car.wakeups.post(.networkChanged)
    #expect(car.wakeups.waiterCount == 1)
    car.wakeups.post(.shown("home"))
    try await waiting.value
  }
}
