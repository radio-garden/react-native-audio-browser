import Foundation
import Testing

@testable import AudioBrowserTestable

@MainActor
@Suite("NavigationErrorState")
struct NavigationErrorStateTests {
  private final class Events {
    var errors: [NavigationErrorType?] = []
    var displays: [String?] = []
  }

  private let events = Events()
  private let state: NavigationErrorState

  init() {
    let events = events
    state = NavigationErrorState(
      onErrorChanged: { events.errors.append($0?.code) },
      onFormattedChanged: { events.displays.append($0?.title) },
    )
  }

  private func follow(_ load: () async throws -> Bool) async {
    await state.follow(
      load,
      describe: { _ in NavigationError(code: .networkError, message: "offline", statusCode: nil, statusCodeSuccess: nil) },
      format: { FormattedNavigationError(title: "Network Error", message: $0.message) },
    )
  }

  private func failANavigation() async {
    await follow { throw URLError(.notConnectedToInternet) }
  }

  @Test func aLoadThatThrowsReportsTheErrorAndItsDisplay() async {
    await failANavigation()

    #expect(state.error?.code == .networkError)
    #expect(state.formatted?.title == "Network Error")
    #expect(events.errors == [.networkError])
    #expect(events.displays == ["Network Error"])
  }

  @Test func aLoadThatPublishesItsContentClearsTheError() async {
    await failANavigation()

    await follow { true }

    #expect(state.error == nil)
    #expect(state.formatted == nil)
    #expect(events.errors == [.networkError, nil])
    #expect(events.displays == ["Network Error", nil])
  }

  @Test func aLoadThatPublishesWithNoErrorSetEmitsNothing() async {
    await follow { true }

    #expect(events.errors.isEmpty)
    #expect(events.displays.isEmpty)
  }

  @Test func aSupersededLoadKeepsTheError() async {
    await failANavigation()

    await follow { false }

    #expect(state.error?.code == .networkError)
    #expect(state.formatted?.title == "Network Error")
    #expect(events.errors == [.networkError])
  }
}
