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
 * Shared by [SonosRouteProviderService], which the system binds, and the player's [SonosRemote]:
 * the selected room and room volumes. A room a session plays on takes its volume through the
 * session.
 */
internal object SonosOutputs {
  const val CATEGORY = "com.audiobrowser.CATEGORY_SONOS"

  @Volatile
  var selected: SonosRoom? = null
    private set

  private val listeners = CopyOnWriteArraySet<() -> Unit>()
  private val volumeListeners = CopyOnWriteArraySet<() -> Unit>()
  private val volumes = ConcurrentHashMap<String, Int>()

  private class Session(val roomId: String, val setVolume: (Int) -> Unit)

  private var session: Session? = null

  /**
   * Turns [SonosRouteProviderService] on or off. The state persists, so the system can bind the
   * service before the app sets up its player. Turning it off forgets the selected room.
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

  /** Calls [listener] on the main thread when [selected] changes. */
  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  fun volumeOf(roomId: String): Int? = volumes[roomId]

  @MainThread
  fun reportVolume(roomId: String, volume: Int) {
    if (volumes.put(roomId, volume) == volume) return
    volumeListeners.forEach { it() }
  }

  /** Calls [listener] on the main thread when a room's volume changes. */
  fun addVolumeListener(listener: () -> Unit) {
    volumeListeners.add(listener)
  }

  fun removeVolumeListener(listener: () -> Unit) {
    volumeListeners.remove(listener)
  }

  /**
   * While a session plays on [roomId], its volume goes through [setVolume]; null ends it, unless
   * another room's session has replaced it.
   */
  @MainThread
  fun setSession(roomId: String, setVolume: ((Int) -> Unit)?) {
    if (setVolume != null) session = Session(roomId, setVolume)
    else if (session?.roomId == roomId) session = null
  }

  fun hasSession(roomId: String): Boolean = session?.roomId == roomId

  /** False when no session plays on [roomId]. */
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

  @MainThread
  internal fun unselect(room: SonosRoom) {
    if (selected?.id != room.id) return
    selected = null
    listeners.forEach { it() }
  }
}
