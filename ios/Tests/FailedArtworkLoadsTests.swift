import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("FailedArtworkLoads")
struct FailedArtworkLoadsTests {
  private final class Row {}

  private let serverError = ArtworkLoadFailure(status: 503)

  @Test func aRetryReloadsAFailedLoadOnce() {
    let loads = FailedArtworkLoads()
    let row = Row()
    var reloads = 0
    loads.add(serverError, owner: row) { reloads += 1 }
    loads.retry()
    loads.retry()
    #expect(reloads == 1)
  }

  @Test func aLoadWhoseRowIsGoneIsNotReloaded() {
    let loads = FailedArtworkLoads()
    var row: Row? = Row()
    var reloads = 0
    loads.add(serverError, owner: row!) { reloads += 1 }
    row = nil
    loads.retry()
    #expect(reloads == 0)
  }

  @Test func aFinalFailureIsNotKept() {
    let loads = FailedArtworkLoads()
    let row = Row()
    var reloads = 0
    loads.add(ArtworkLoadFailure(status: 404), owner: row) { reloads += 1 }
    loads.retry()
    #expect(reloads == 0)
  }
}
