import AVFoundation
import Foundation
import Testing

@testable import AudioBrowserTestable

/// A source as the stream server serves it, and the loop wired as the Player wires it, on a virtual
/// clock: each connect runs `connection`, and the terminal error drops the connection in flight.
@MainActor
private final class Rig {
  let clock = VirtualClock()
  let loop: RetryLoop
  private(set) var connects: [Int] = []
  private(set) var errors: [(atMs: Int, error: TrackPlayerError.PlaybackError)] = []
  private var connection: (Rig) -> Void = { _ in }
  private var current = 0

  init(maxRetries: Int? = nil) {
    loop = RetryLoop(clock: clock)
    loop.updatePolicy(from: .second(RetryConfig(
      maxRetries: maxRetries.map(Double.init), maxRetryDurationMs: nil, firstConnectMaxRetryDurationMs: nil,
    )))
    loop.onRetry = { [unowned self] _ in connect() }
    loop.onError = { [unowned self] in
      errors.append((clock.nowMs, $0))
      current += 1
    }
  }

  /// A play with intent: a new Load and its first connect.
  func play(_ connection: @escaping (Rig) -> Void) {
    self.connection = connection
    loop.playIntentChanged(true)
    load()
  }

  /// A new Load of the same source, as a track change or a restart from the error makes one.
  func load() {
    loop.loadStarted()
    connect()
  }

  /// A connect: the loop's reconnect, or a reload the Player starts in place of the connection.
  func connect() {
    current += 1
    connects.append(clock.nowMs)
    loop.connectionStarted()
    connection(self)
  }

  /// Runs `action` `afterMs` from now, unless the connection it belongs to has gone.
  func later(_ afterMs: Int, _ action: @escaping (RetryLoop) -> Void) {
    let connection = current
    clock.at(clock.nowMs + afterMs) { [unowned self] in
      if connection == current { action(loop) }
    }
  }

  func run(untilMs: Int) async { await clock.run(until: untilMs) }
}

extension Rig {
  /// Audio after `connectMs`, then a clean close `closeAfterMs` into it.
  static func closes(afterMs closeAfterMs: Int, connectMs: Int = 300) -> (Rig) -> Void {
    { rig in
      rig.later(connectMs) { $0.playbackBecameAudible() }
      rig.later(connectMs + closeAfterMs) { loop in
        loop.playbackStoppedBeingAudible()
        loop.handleFailure(.liveEnd)
      }
    }
  }

  /// A connection that is refused, or times out, `afterMs` after it starts.
  static func fails(afterMs: Int, with error: URLError.Code = .cannotConnectToHost) -> (Rig) -> Void {
    { rig in rig.later(afterMs) { $0.handleError(URLError(error), context: .mediaLoad) } }
  }

  /// The gaps between each connection's close and the next connect, for one that closes on
  /// `closeAfterMs` of audio after `connectMs`.
  func gaps(closeAfterMs: Int, connectMs: Int = 300) -> Set<Int> {
    Set(zip(connects, connects.dropFirst()).map { $1 - ($0 + connectMs + closeAfterMs) })
  }
}

/// The plan's cases (docs/adr/0014), each as the stream server serves it on the simulator.
@Suite("RetryLoop cases")
@MainActor
struct RetryLoopCaseTests {
  @Test func aStreamThatClosesEvery10sPlaysOn_reconnectingAtOnce() async {
    let rig = Rig()
    rig.play(Rig.closes(afterMs: 10_000))
    await rig.run(untilMs: 600_000)
    #expect(rig.errors.isEmpty)
    #expect(rig.connects.count > 50)
    #expect(rig.gaps(closeAfterMs: 10_000) == [0])
  }

  @Test func aStreamThatClosesEvery30sPlaysOn() async {
    let rig = Rig()
    rig.play(Rig.closes(afterMs: 30_000))
    await rig.run(untilMs: 600_000)
    #expect(rig.errors.isEmpty)
    #expect(rig.gaps(closeAfterMs: 30_000) == [0])
  }

  /// No run of audio reaches 5 s, so the silence that began with the play never ends; the burst
  /// playing at 120 s ends past the deadline, which gives up as it ends.
  @Test func aStreamThatPlays2sPerConnectionEndsAt120s() async {
    let rig = Rig()
    rig.play(Rig.closes(afterMs: 2_000))
    await rig.run(untilMs: 300_000)
    #expect(rig.errors.map(\.error) == [.playbackStalled])
    let endedAt = rig.errors.first?.atMs ?? 0
    #expect((120_000 ... 122_300).contains(endedAt))
    #expect(rig.connects.allSatisfy { $0 < endedAt })
  }

  /// The watch gives the silent connect up at 30 s, a failure that starts the first-connect
  /// budget; having delivered nothing, it is replaced 1 s later.
  @Test func aConnectionOpenWithNoDataIsReplacedAt30s_andEndsAt42s() async {
    let rig = Rig()
    rig.play { _ in }
    await rig.run(untilMs: 300_000)
    #expect(rig.connects == [0, 31_000])
    #expect(rig.errors.map(\.atMs) == [42_000])
    #expect(rig.errors.map(\.error) == [.playbackStalled])
  }

  @Test func aDeadAddressThatNeverPlayedEnds12sAfterItsFirstFailure() async {
    let rig = Rig()
    rig.play(Rig.fails(afterMs: 50))
    await rig.run(untilMs: 300_000)
    #expect(rig.errors.map(\.atMs) == [12_050])
    #expect(rig.errors.map(\.error) == [.hostUnreachable])
    // Each wait grows from the failure before it: 1, 1.5, 2.25, 3.375 s.
    #expect(rig.connects == [0, 1_050, 2_600, 4_900, 8_325])
  }

  /// The deadline wins over a connect in flight: it gives up as it passes, and the connect's own
  /// end, which the Player no longer hears, starts nothing.
  @Test func theDeadlineGivesUpOnAConnectInFlight() async {
    let rig = Rig()
    rig.play(Rig.fails(afterMs: 5_000, with: .timedOut))
    await rig.run(untilMs: 60_000)
    #expect(rig.connects == [0, 6_000, 12_500])
    #expect(rig.errors.map(\.atMs) == [17_000])
    #expect(rig.errors.map(\.error) == [.hostUnreachable])
  }

  @Test func timeOfflineDuringAPlayedStreamsSilenceCounts() async {
    let rig = Rig()
    rig.play { rig in
      rig.later(300) { $0.playbackBecameAudible() }
      // As the Player reports it: the failure arrives while its state still reads .playing.
      rig.later(60_300) { loop in
        loop.onlineChanged(false)
        loop.handleFailure(.playback)
      }
    }
    await rig.run(untilMs: 400_000)
    #expect(rig.connects == [0])
    #expect(rig.errors.map(\.atMs) == [180_300])
  }

  @Test func theNetworkReturningReconnectsWithinHalfASecond() async {
    let rig = Rig()
    rig.play { rig in
      rig.later(300) { $0.playbackBecameAudible() }
      rig.later(60_300) { loop in
        loop.onlineChanged(false)
        loop.handleFailure(.playback)
      }
    }
    rig.clock.at(90_000) { rig.loop.onlineChanged(true) }
    await rig.run(untilMs: 91_000)
    // The wait asks the pacer again every half second.
    #expect(rig.connects.count == 2)
    #expect((90_000 ... 90_500).contains(rig.connects.last ?? 0))
  }

  @Test func aRestartAfterTheTerminalErrorStartsFresh() async {
    let rig = Rig()
    rig.play(Rig.fails(afterMs: 50))
    rig.clock.at(20_000) { rig.load() }
    await rig.run(untilMs: 300_000)
    #expect(rig.errors.map(\.atMs) == [12_050, 32_050])
  }

  @Test func aNewLoadStartsFresh() async {
    let rig = Rig()
    rig.play(Rig.fails(afterMs: 50))
    rig.clock.at(8_000) { rig.load() }
    await rig.run(untilMs: 300_000)
    #expect(rig.errors.map(\.atMs) == [20_050])
  }

  @Test func maxRetriesCountsConnectionsInARowThatDeliveredNothing() async {
    let rig = Rig(maxRetries: 2)
    rig.play(Rig.fails(afterMs: 50))
    await rig.run(untilMs: 300_000)
    #expect(rig.connects == [0, 1_050, 2_600])
    #expect(rig.errors.map(\.atMs) == [2_650])
  }

  @Test func aPauseClearsTheDeadline() async {
    let rig = Rig()
    rig.play { _ in }
    // Before the watch gives the connect up at 30 s.
    rig.clock.at(20_000) { rig.loop.playIntentChanged(false) }
    await rig.run(untilMs: 300_000)
    #expect(rig.errors.isEmpty)
  }

  @Test func aCancelClearsTheDeadline_untilTheNextConnect() async {
    let rig = Rig()
    rig.play { _ in }
    rig.clock.at(20_000) { rig.loop.cancel() }
    await rig.run(untilMs: 300_000)
    #expect(rig.errors.isEmpty)
  }

  /// A media reset or `retryNow` reloads in place of a connection that played: its end starts the
  /// count over, so the next connection may fail once and still reconnect.
  @Test func aReloadThePlayerStartsEndsTheConnectionItReplaces() async {
    let rig = Rig(maxRetries: 1)
    var connection = 0
    rig.play { rig in
      connection += 1
      if connection == 2 {
        rig.later(300) { $0.playbackBecameAudible() }
      } else {
        rig.later(50) { $0.handleError(URLError(.cannotConnectToHost), context: .mediaLoad) }
      }
    }
    rig.clock.at(60_000) {
      rig.loop.playbackStoppedBeingAudible()
      rig.connect()
    }
    await rig.run(untilMs: 61_060)
    #expect(rig.connects == [0, 1_050, 60_000, 61_050])
    #expect(rig.errors.isEmpty)
  }

  /// `retryNow` or a seek to the live edge during a reconnect cuts it off before it delivers
  /// anything: that is the listener's, not the source's failure.
  @Test func aReloadThatCutsOffAConnectDoesNotCountAgainstTheSource() async {
    let rig = Rig(maxRetries: 2)
    var connection = 0
    rig.play { rig in
      connection += 1
      if !(2 ... 4).contains(connection) {
        rig.later(50) { $0.handleError(URLError(.cannotConnectToHost), context: .mediaLoad) }
      }
    }
    // The second connect hangs: three reloads replace it while it connects.
    rig.clock.at(2_000) { rig.connect() }
    rig.clock.at(2_100) { rig.connect() }
    rig.clock.at(2_200) { rig.connect() }
    await rig.run(untilMs: 3_760)
    #expect(rig.connects == [0, 1_050, 2_000, 2_100, 2_200, 3_750])
    #expect(rig.errors.isEmpty)
  }

  /// The first-connect clock starts at the first failure seen online.
  @Test func timeOfflineDoesNotBurnTheFirstConnectBudget() async {
    let rig = Rig()
    rig.loop.onlineChanged(false)
    rig.play(Rig.fails(afterMs: 50))
    rig.clock.at(60_000) { rig.loop.onlineChanged(true) }
    await rig.run(untilMs: 300_000)
    // Offline, the wait asks again every half second from the failure at 50 ms.
    #expect(rig.connects.prefix(2) == [0, 60_050])
    #expect(rig.errors.map(\.atMs) == [72_100])
  }

  /// A silence that ends with no failure after audio played is a stall, not the failure before it.
  @Test func theDeadlineReportsAStall_notAFailureAudioHasPlayedSince() async {
    let rig = Rig()
    var connection = 0
    rig.play { rig in
      connection += 1
      if connection == 1 {
        rig.later(50) { $0.handleError(nil, context: .mediaLoad, httpStatusCode: 503) }
      } else {
        rig.later(300) { $0.playbackBecameAudible() }
        rig.later(60_000) { $0.playbackStoppedBeingAudible() }
      }
    }
    await rig.run(untilMs: 300_000)
    #expect(rig.connects == [0, 1_050])
    #expect(rig.errors.map(\.atMs) == [181_050])
    #expect(rig.errors.map(\.error) == [.playbackStalled])
  }

  @Test func aPlayWithNothingLoadedNeverGivesUp() async {
    let rig = Rig()
    rig.loop.playIntentChanged(true)
    await rig.run(untilMs: 300_000)
    #expect(rig.errors.isEmpty)
  }

  /// AVFoundation can report one failure through the item, its notification and the player.
  @Test func aFailureReportedTwiceEndsItsConnectionOnce() async {
    let rig = Rig(maxRetries: 2)
    rig.play { rig in
      rig.later(50) { loop in
        loop.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
        loop.handleError(URLError(.cannotConnectToHost), context: .playback)
      }
    }
    await rig.run(untilMs: 300_000)
    #expect(rig.connects == [0, 1_050, 2_600])
    #expect(rig.errors.map(\.atMs) == [2_650])
  }
}

// MARK: - handleError

@Suite("RetryLoop.handleError")
@MainActor
struct RetryLoopHandleErrorTests {
  final class Record {
    var retrying: [TrackPlayerError.PlaybackError] = []
    var errors: [TrackPlayerError.PlaybackError] = []
    var reloads: [Bool] = []
  }

  private func makeLoop(givesUp: Bool = false, delay: TimeInterval = 0) -> (RetryLoop, Record) {
    let loop = makeRetryLoop(givesUp: givesUp, delay: delay)
    let record = Record()
    loop.onRetryingError = { record.retrying.append($0) }
    loop.onError = { record.errors.append($0) }
    loop.onRetry = { record.reloads.append($0) }
    return (loop, record)
  }

  @Test func aNonRetryableErrorSurfacesAtOnce() {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.badURL), context: .playback)
    #expect(record.errors == [.playbackFailed])
    #expect(record.retrying.isEmpty)
    #expect(loop.pendingTask == nil)
  }

  @Test func aDisabledPolicySurfacesAtOnce() {
    let (loop, record) = makeLoop()
    loop.updatePolicy(from: nil)
    loop.handleError(URLError(.notConnectedToInternet), context: .mediaLoad)
    #expect(record.errors == [.notConnectedToInternet])
    #expect(record.retrying.isEmpty)
    #expect(loop.pendingTask == nil)
  }

  @Test func aRetryableErrorReloadsFromTheContextsPosition() async {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    #expect(loop.pendingTask != nil)
    await loop.pendingTask?.value
    loop.handleError(URLError(.timedOut), context: .mediaLoad)
    await loop.pendingTask?.value

    #expect(record.reloads == [true, false])
    #expect(record.errors.isEmpty)
    // Session release waits on `pendingTask`, so a finished retry must clear it.
    #expect(loop.pendingTask == nil)
  }

  @Test func theAdvisoryPrecedesTheRetry() async {
    let (loop, record) = makeLoop()
    loop.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    #expect(record.retrying == [.hostUnreachable])
    #expect(record.reloads.isEmpty)
    await loop.pendingTask?.value
    #expect(record.errors.isEmpty)
  }

  @Test func givingUpReportsTheAdvisoryThenTheTerminalError() async {
    let (loop, record) = makeLoop(givesUp: true)
    loop.handleError(URLError(.cannotConnectToHost), context: .mediaLoad)
    await loop.pendingTask?.value
    #expect(record.retrying == [.hostUnreachable])
    #expect(record.errors == [.hostUnreachable])
  }

  @Test func aCancelledRetryStaysSilent() async {
    let (loop, record) = makeLoop(delay: 0.05)
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    loop.cancel()
    #expect(loop.pendingTask == nil)
    await task?.value
    #expect(record.errors.isEmpty)
    #expect(record.reloads.isEmpty)
  }

  @Test func aNewFailureReplacesTheRetryInFlight() async {
    let (loop, record) = makeLoop(delay: 0.02)
    loop.handleError(URLError(.timedOut), context: .playback)
    let first = loop.pendingTask
    loop.handleError(URLError(.timedOut), context: .playback)
    let second = loop.pendingTask
    await first?.value
    await second?.value
    #expect(record.reloads == [true])
    #expect(record.errors.isEmpty)
  }

  @Test func theHTTPStatusReachesTheReport() async {
    let (loop, record) = makeLoop(givesUp: true)
    loop.handleError(nil, context: .playback, httpStatusCode: 404)
    loop.handleError(URLError(.badURL), context: .playback, httpStatusCode: 503)
    await loop.pendingTask?.value
    #expect(record.errors == [.httpStatus(404), .httpStatus(503)])
  }

  @Test func givingUpOnAnOpaqueFailureReportsTheFallback() async {
    let (loop, record) = makeLoop(givesUp: true)
    loop.handleError(NSError(domain: "CoreMediaErrorDomain", code: -12939), context: .mediaLoad)
    await loop.pendingTask?.value
    #expect(record.errors == [.failedToLoadKeyValue])
  }

  @Test func aNewLoadDropsTheRetryInFlightSilently() async {
    let (loop, record) = makeLoop(delay: 0.02)
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    loop.loadStarted()
    #expect(loop.pendingTask == nil)
    await task?.value
    #expect(record.errors.isEmpty)
    #expect(record.reloads.isEmpty)
  }

  /// A loop whose backoff waits until cancelled; the closure says whether it has started waiting.
  private func makeWaitingLoop() -> (RetryLoop, Record, () -> Bool) {
    var waiting = false
    let loop = RetryLoop(sleep: { _ in
      await MainActor.run { waiting = true }
      try await Task.sleep(for: .seconds(60))
    })
    loop.updatePolicy(from: .first(true))
    loop.playIntentChanged(true)
    let record = Record()
    loop.onError = { record.errors.append($0) }
    loop.onRetry = { record.reloads.append($0) }
    return (loop, record, { waiting })
  }

  @Test func aCancelDuringTheBackoffStaysSilent() async {
    let (loop, record, isWaiting) = makeWaitingLoop()
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    while !isWaiting() {
      await Task.yield()
    }
    loop.cancel()
    await task?.value
    #expect(record.errors.isEmpty)
    #expect(record.reloads.isEmpty)
  }

  @Test func aCancelAsTheBackoffEndsStopsTheReload() async {
    let holder = LoopHolder()
    // The cancel lands as the backoff completes: the sleep returns normally, not by throwing.
    let loop = RetryLoop(sleep: { _ in
      await holder.loop?.cancel()
    })
    holder.loop = loop
    loop.updatePolicy(from: .first(true))
    loop.playIntentChanged(true)
    let record = Record()
    loop.onError = { record.errors.append($0) }
    loop.onRetry = { record.reloads.append($0) }
    loop.handleError(URLError(.timedOut), context: .playback)
    let task = loop.pendingTask
    await task?.value
    #expect(record.reloads.isEmpty)
    #expect(record.errors.isEmpty)
  }
}

@MainActor
private final class LoopHolder {
  weak var loop: RetryLoop?
}
