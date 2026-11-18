/// When automatic retry gives up: after `recoveryMs` of silence, or, for a Load that never played,
/// `firstConnectMs` after its first failure within one online stretch (ADR 0014, rules 1 to 4).
/// Time comes in as milliseconds on one monotonic clock, and nothing fires on its own: every input
/// can move the deadline, so read `deadlineMs` again after each.
struct SilenceBudget {
  /// A shorter burst of audio leaves the silence, and its deadline, where they were.
  static let audibleToEndSilenceMs = 5_000

  var recoveryMs: Int
  var firstConnectMs: Int

  /// Whether this Load has rendered audio; only `loadStarted` clears it (ADR 0004).
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

  /// Nil while audio plays or none is wanted. It can lie in the past, which means give up at once.
  var deadlineMs: Int? {
    guard playIntent, audibleSinceMs == nil, let silentSinceMs else { return nil }
    let recovery = silentSinceMs + recoveryMs
    guard !hasPlayed, online, let firstOnlineFailureMs else { return recovery }
    return min(recovery, firstOnlineFailureMs + firstConnectMs)
  }

  /// For a new Load, never a retry's reload, which would restart the silence on every reconnect.
  mutating func loadStarted(atMs now: Int) {
    hasPlayed = false
    audibleSinceMs = nil
    firstOnlineFailureMs = nil
    silentSinceMs = playIntent ? now : nil
  }

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

  mutating func onlineChanged(_ online: Bool) {
    self.online = online
    if !online { firstOnlineFailureMs = nil }
  }

  mutating func connectionEnded(atMs now: Int) {
    if online, firstOnlineFailureMs == nil { firstOnlineFailureMs = now }
  }
}
