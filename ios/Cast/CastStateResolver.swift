import Foundation

/// Pure mapping from the three observable Cast facts to the cross-platform
/// `CastState` (`connected` > `connecting` > `notConnected` > `noDevices`).
///
/// **Cast-SDK-free and UNGATED on purpose** — it touches only the generated
/// `CastState` enum (always present, independent of the Cast build flag), so it
/// compiles in the default build and is unit-testable from the SPM test target
/// (mirrors Android's `CastStateResolver`). The gated `CastStateMapper` reads the
/// three booleans off the GCK enums at the call boundary and delegates here.
enum CastStateResolver {
  static func resolve(connected: Bool, connecting: Bool, hasDevices: Bool) -> CastState {
    if connected { return .connected }
    if connecting { return .connecting }
    if hasDevices { return .notConnected }
    return .noDevices
  }
}
