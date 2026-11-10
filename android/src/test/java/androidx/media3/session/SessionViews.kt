package androidx.media3.session

// Read in place: Robolectric can't connect a controller to the session.

/** The state legacy controllers (Android Auto, Bluetooth, the system UI) were last told. */
internal fun MediaSession.legacyState() =
  impl.mediaSessionLegacyStub.sessionCompat.controller.playbackState!!.state

/** The current title media3 controllers were last told. */
internal fun MediaSession.controllerTitle() = impl.playerInfo.mediaMetadata.title?.toString()
