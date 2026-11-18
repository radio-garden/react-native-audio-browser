package com.audiobrowser.sonos

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import timber.log.Timber

/**
 * Sonos rooms as remote outputs, shared by [SonosRouteProviderService], which the system binds, and
 * the player's [SonosRemote]: whether the service may be bound, the room selected in the Output
 * Switcher, and room volumes. While a session plays on a room, its volume goes through that
 * session, which reads it back.
 */
internal object SonosOutputs {
  /**
   * The control category a Sonos room's route declares beside
   * [com.audiobrowser.RemoteOutputs.CATEGORY].
   */
  const val CATEGORY = "com.audiobrowser.CATEGORY_SONOS"

  /** The room playback goes to, or null. Written on the main thread, read from any. */
  @Volatile
  var selected: SonosRoom? = null
    private set

  private val listeners = CopyOnWriteArraySet<() -> Unit>()
  private val volumeListeners = CopyOnWriteArraySet<() -> Unit>()
  private val volumes = ConcurrentHashMap<String, Int>()

  private class Session(val roomId: String, val setVolume: (Int) -> Unit)

  private var session: Session? = null

  /**
   * Enables or disables [SonosRouteProviderService]. The component state persists, so the system
   * can bind the service before the app has set up its player. Disabling forgets the selected room.
   */
  @MainThread
  fun setEnabled(context: Context, enabled: Boolean) {
    if (!enabled) selected?.let(::unselect)
    if (enabled && !canHoldMulticastLock(context)) {
      Timber.w(
        "Sonos: the app's manifest doesn't declare CHANGE_WIFI_MULTICAST_STATE, so Sonos discovery runs without a multicast lock and can miss rooms"
      )
    }
    val component = ComponentName(context, SonosRouteProviderService::class.java)
    val state =
      if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
      else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    if (context.packageManager.getComponentEnabledSetting(component) == state) return
    context.packageManager.setComponentEnabledSetting(
      component,
      state,
      PackageManager.DONT_KILL_APP,
    )
  }

  /** Calls [listener] on the main thread whenever [selected] changes. */
  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  /** [roomId]'s volume as last read or set, from 0 to 100, or null before either. */
  fun volumeOf(roomId: String): Int? = volumes[roomId]

  @MainThread
  fun reportVolume(roomId: String, volume: Int) {
    if (volumes.put(roomId, volume) == volume) return
    volumeListeners.forEach { it() }
  }

  /** Calls [listener] on the main thread whenever a room's volume changes. */
  fun addVolumeListener(listener: () -> Unit) {
    volumeListeners.add(listener)
  }

  fun removeVolumeListener(listener: () -> Unit) {
    volumeListeners.remove(listener)
  }

  /**
   * While a session plays on [roomId], [setVolume] sets its volume; null when it ends, which leaves
   * another room's session in place.
   */
  @MainThread
  fun setSession(roomId: String, setVolume: ((Int) -> Unit)?) {
    if (setVolume != null) session = Session(roomId, setVolume)
    else if (session?.roomId == roomId) session = null
  }

  fun hasSession(roomId: String): Boolean = session?.roomId == roomId

  /** Sets [roomId]'s volume through the session playing there: false when none does. */
  @MainThread
  fun setVolumeThroughSession(roomId: String, volume: Int): Boolean {
    val session = session ?: return false
    if (session.roomId != roomId) return false
    session.setVolume(volume)
    return true
  }

  internal fun canHoldMulticastLock(context: Context) =
    context.checkSelfPermission(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) ==
      PackageManager.PERMISSION_GRANTED

  @MainThread
  internal fun select(room: SonosRoom) {
    if (selected == room) return
    selected = room
    listeners.forEach { it() }
  }

  /** Clears the selection, unless another room was selected since [room]. */
  @MainThread
  internal fun unselect(room: SonosRoom) {
    if (selected?.id != room.id) return
    selected = null
    listeners.forEach { it() }
  }
}
