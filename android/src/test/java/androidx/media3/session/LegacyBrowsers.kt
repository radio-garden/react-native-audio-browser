package androidx.media3.session

import androidx.media3.common.Player

// Robolectric can't bind a MediaBrowserCompat, so a car is registered as onGetRoot registers one,
// through media3's package-private internals: a media3 upgrade can break these.

/** Connects [car] to the session's legacy browser service with [commands], as onGetRoot does. */
internal fun MediaSession.connectLegacyBrowser(
  car: MediaSession.ControllerInfo,
  commands: SessionCommands,
) {
  // Creates the legacy browser service.
  impl.legacyBrowserServiceBinder
  impl.legacyBrowserService!!
    .connectedControllersManager
    .addController(car.remoteUserInfo!!, car, commands, Player.Commands.EMPTY)
}

/** Whether media3's legacy browser service lets [car] search. */
internal fun MediaSession.legacyBrowserMaySearch(car: MediaSession.ControllerInfo): Boolean =
  impl.legacyBrowserService!!
    .connectedControllersManager
    .isSessionCommandAvailable(car, SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
