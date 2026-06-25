import Foundation

/// Pure de-duplication of Cast state emits. The session-lifecycle callbacks and
/// the discovery observer can both fire for a single transition; without this JS
/// could see a `.connected → .connecting → .connected` flicker as the lagging
/// context observer catches up. Records the last accepted `(state, deviceName)`
/// pair and suppresses an identical repeat.
///
/// **Cast-SDK-free and UNGATED on purpose** so it compiles in the default build
/// and is unit-testable. A value type owned (and confined to the main actor) by
/// `CastSessionManager`.
struct CastStateCoalescer {
  private var lastState: CastState?
  private var lastDeviceName: String?

  /// Returns true when `(state, deviceName)` differs from the last accepted pair
  /// (recording it as the new baseline); false to suppress a duplicate emit.
  mutating func shouldEmit(state: CastState, deviceName: String?) -> Bool {
    guard state != lastState || deviceName != lastDeviceName else { return false }
    lastState = state
    lastDeviceName = deviceName
    return true
  }

  /// Forgets the last accepted pair so the next `shouldEmit` always passes.
  mutating func reset() {
    lastState = nil
    lastDeviceName = nil
  }
}
