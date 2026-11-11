package androidx.media3.session

import android.os.Handler
import android.os.Looper
import androidx.media3.session.legacy.MediaControllerCompat
import androidx.media3.session.legacy.PlaybackStateCompat

// Read in place: Robolectric can't connect a controller to the session.

/** The state legacy controllers (Android Auto, Bluetooth, the system UI) were last told. */
internal fun MediaSession.legacyState() =
  impl.mediaSessionLegacyStub.sessionCompat.controller.playbackState!!.state

/** The current title media3 controllers were last told. */
internal fun MediaSession.controllerTitle() = impl.playerInfo.mediaMetadata.title?.toString()

/** The playback state media3 controllers (the notification among them) were last told. */
internal fun MediaSession.controllerState() = impl.playerInfo.playbackState

/** The player error media3 controllers were last told. */
internal fun MediaSession.controllerError() = impl.playerInfo.playerError

/** Whether media3 controllers were last told it plays. */
internal fun MediaSession.controllerPlaying() = impl.playerInfo.isPlaying

/** Counts the playback states legacy controllers are told from now on. */
internal fun MediaSession.countLegacyStateUpdates(): () -> Int {
  var count = 0
  impl.mediaSessionLegacyStub.sessionCompat.controller.registerCallback(
    object : MediaControllerCompat.Callback() {
      override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
        count += 1
      }
    },
    Handler(Looper.getMainLooper()),
  )
  return { count }
}
