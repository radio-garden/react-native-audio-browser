/// When automatic retry gives up: after `recoveryMs` of silence, or, for a Load that never played,
/// `firstConnectMs` after its first failure within one online stretch (docs/adr/0014, rules 1 to 4).
/// Pure: time comes in as milliseconds on one monotonic clock, and nothing fires on its own.
/// Every input can move the deadline, so read `deadlineMs` again after each.
struct SilenceBudget {
  /// The audio a silence needs, in a row, to end; a shorter burst leaves its deadline where it was.
  static let audibleToEndSilenceMs = 5_000

  var recoveryMs: Int
  var firstConnectMs: Int

  /// Whether the current Load has rendered audio; only `loadStarted` clears it.
  private(set) var hasPlayed = false
  private var playIntent = false
  private var online: Bool
  private var audibleSinceMs: Int?
  private var silentSinceMs: Int?
  private var firstOnlineFailureMs: Int?

  init(recoveryMs: Int = 120_000, firstConnectMs: Int = 12_000, online: Bool) {
    self.recoveryMs = recoveryMs
    self.firstConnectMs = firstConnectMs
    self.online = online
  }

  /// When to give up, or nil while audio plays or no audio is wanted. It can lie in the past: a
  /// burst that outlasts it ends past the deadline, which means give up at once.
  var deadlineMs: Int? {
    guard playIntent, audibleSinceMs == nil, let silentSinceMs else { return nil }
    let recovery = silentSinceMs + recoveryMs
    guard !hasPlayed, online, let firstOnlineFailureMs else { return recovery }
    return min(recovery, firstOnlineFailureMs + firstConnectMs)
  }

  /// A new Load: a track change, or a restart from the terminal error. Never a retry's reload,
  /// which would restart the silence on every reconnect.
  mutating func loadStarted(atMs now: Int) {
    hasPlayed = false
    audibleSinceMs = nil
    firstOnlineFailureMs = nil
    silentSinceMs = playIntent ? now : nil
  }

  /// A play intent that comes up starts a new silence; one that goes down ends it.
  mutating func playIntentChanged(_ up: Bool, atMs now: Int) {
    guard up != playIntent else { return }
    playIntent = up
    firstOnlineFailureMs = nil
    silentSinceMs = up && audibleSinceMs == nil ? now : nil
  }

  mutating func audioStarted(atMs now: Int) {
    guard audibleSinceMs == nil else { return }
    audibleSinceMs = now
    hasPlayed = true
  }

  mutating func audioStopped(atMs now: Int) {
    guard let audibleSinceMs else { return }
    self.audibleSinceMs = nil
    guard playIntent else { return }
    if silentSinceMs == nil || now - audibleSinceMs >= Self.audibleToEndSilenceMs {
      silentSinceMs = now
    }
  }

  /// Going offline restarts the first-connect clock, which only an online failure sets again.
  mutating func onlineChanged(_ online: Bool) {
    self.online = online
    if !online { firstOnlineFailureMs = nil }
  }

  /// Every connection's end, whatever it delivered: the first one online starts the first-connect
  /// clock of a Load that has not played.
  mutating func connectionEnded(atMs now: Int) {
    if online, firstOnlineFailureMs == nil { firstOnlineFailureMs = now }
  }
}
