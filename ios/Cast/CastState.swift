import Foundation

#if AUDIOBROWSER_ENABLE_CAST

  import GoogleCast

  /// Maps the Google Cast SDK's discovery/session lifecycle onto our flat
  /// cross-platform `CastState` (see `CONTEXT.md` → "Playback destinations").
  ///
  /// The SDK exposes two orthogonal signals we collapse into one enum:
  ///   - `GCKCastContext.castState` — discovery + connection lifecycle
  ///     (`.noDevicesAvailable` / `.notConnected` / `.connecting` / `.connected`).
  ///   - the active `GCKSession`'s `connectionState` — finer-grained connection
  ///     state when a session exists.
  ///
  /// `GCKCastState` already lines up 1:1 with our four states, so the primary
  /// mapping is from it; the session connection state is used only as a
  /// refinement when the context hasn't yet reflected a transition.
  ///
  /// Both overloads only decompose the GCK enum into the three booleans the pure,
  /// ungated `CastStateResolver` takes — the actual truth table lives there
  /// (shared shape with Android), this is just the SDK call boundary.
  enum CastStateMapper {
    /// Maps a raw `GCKCastState` to our `CastState`.
    static func map(_ gckState: GCKCastState) -> CastState {
      CastStateResolver.resolve(
        connected: gckState == .connected,
        connecting: gckState == .connecting,
        hasDevices: gckState != .noDevicesAvailable,
      )
    }

    /// Maps a session connection state to our `CastState`. Used when reacting to
    /// `GCKSessionManagerListener` callbacks before the context's `castState`
    /// KVO fires, so the emitted state is never stale. A session exists here, so a
    /// device is by definition available (`hasDevices: true`); a disconnecting /
    /// disconnected session reads as `notConnected`.
    static func map(_ connectionState: GCKConnectionState) -> CastState {
      CastStateResolver.resolve(
        connected: connectionState == .connected,
        connecting: connectionState == .connecting,
        hasDevices: true,
      )
    }
  }

#endif
