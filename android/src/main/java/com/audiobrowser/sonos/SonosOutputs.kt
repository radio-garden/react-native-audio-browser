package com.audiobrowser.sonos

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import java.util.concurrent.CopyOnWriteArraySet
import timber.log.Timber

/**
 * Sonos rooms as remote outputs, over UPnP: whether the system may bind
 * [SonosRouteProviderService], and the room selected in the Output Switcher.
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

  /**
   * Enables or disables the provider service. The component state persists, so the system can bind
   * the service before the app has set up its player. Disabling forgets the selected room.
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
