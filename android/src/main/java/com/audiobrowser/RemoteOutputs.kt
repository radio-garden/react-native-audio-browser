package com.audiobrowser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaTransferReceiver
import com.audiobrowser.util.OutputSwitcher
import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import java.util.concurrent.CopyOnWriteArraySet
import timber.log.Timber

/**
 * Remote outputs: the speakers and TVs of media route providers, offered in the system Output
 * Switcher next to the phone and Bluetooth, the one selected there reported as the [Output]. Only
 * the selection is reported; rendering the audio there is the provider's job, or the Cast session's
 * for a Cast device.
 *
 * Holds the process's one [MediaRouter] while enabled, on Android 11+ where the switcher exists.
 */
object RemoteOutputs {
  /** The control category a media route provider's routes declare to be offered as outputs. */
  const val CATEGORY = "com.audiobrowser.CATEGORY_REMOTE_OUTPUT"

  private var types: Map<String, OutputType> = emptyMap()

  private var selector = selectorFor(types)

  // MediaRouter holds routers weakly: let this one go and the Output Switcher stops listing the
  // category's routes, as the app's discovery preference empties on the next GC.
  private var router: MediaRouter? = null

  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  /** The selected remote output, or null while playback stays local. Read from any thread. */
  @Volatile
  internal var current: Output? = null
    private set

  // A callback with a selector only hears of routes in the category: going back to the phone
  // arrives as the remote route's unselect, not as the phone's select.
  private val callback =
    object : MediaRouter.Callback() {
      override fun onRouteSelected(
        router: MediaRouter,
        selectedRoute: MediaRouter.RouteInfo,
        reason: Int,
      ) = update(router)

      override fun onRouteUnselected(
        router: MediaRouter,
        route: MediaRouter.RouteInfo,
        reason: Int,
      ) = update(router)

      override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) =
        update(router)
    }

  /**
   * Turns remote outputs on or off, and with them the routes of each control category in [types],
   * reported as its output type. Idempotent.
   */
  @MainThread
  internal fun setEnabled(context: Context, enabled: Boolean, types: Map<String, OutputType>) {
    val types = types.takeIf { enabled }.orEmpty()
    if (!enabled) stop()
    if (types != this.types) {
      this.types = types
      selector = selectorFor(types)
      router?.let(::listen)
    }
    if (enabled) start(context)
  }

  private fun selectorFor(types: Map<String, OutputType>) =
    MediaRouteSelector.Builder()
      .addControlCategory(CATEGORY)
      .apply { types.keys.forEach(::addControlCategory) }
      .build()

  /** Calls [listener] on the main thread whenever [current] changes. */
  internal fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  internal fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  private fun start(context: Context) {
    if (router != null || !OutputSwitcher.isSupported()) return
    warnWithoutTransferReceiver(context)
    val router = MediaRouter.getInstance(context.applicationContext)
    this.router = router
    listen(router)
  }

  private fun listen(router: MediaRouter) {
    router.removeCallback(callback)
    router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    update(router)
  }

  private fun stop() {
    val router = router ?: return
    router.removeCallback(callback)
    if (router.selectedRoute.matchesSelector(selector)) {
      router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
    }
    this.router = null
    setCurrent(null)
  }

  /** Never throws: a failed check must not keep [start] from setting up the router. */
  private fun warnWithoutTransferReceiver(context: Context) {
    try {
      context.packageManager.getReceiverInfo(
        ComponentName(context, MediaTransferReceiver::class.java),
        PackageManager.GET_META_DATA,
      )
    } catch (e: Exception) {
      Timber.w(
        e,
        "RemoteOutputs: found no MediaTransferReceiver in the app's manifest, so the Output Switcher won't list remote outputs",
      )
    }
  }

  private fun update(router: MediaRouter) {
    val route = router.selectedRoute
    setCurrent(remoteOutputOf(route.name, types, route::supportsControlCategory))
  }

  @VisibleForTesting
  @MainThread
  internal fun setCurrent(output: Output?) {
    if (output == current) return
    current = output
    Timber.d("RemoteOutputs: %s", output?.name ?: "local")
    listeners.forEach { it() }
  }
}

/**
 * The [Output] a selected route stands for, typed by the first category in [types] it [supports],
 * else [OutputType.REMOTE] for [RemoteOutputs.CATEGORY]; null for a route the outputs don't offer.
 */
internal fun remoteOutputOf(
  name: String,
  types: Map<String, OutputType>,
  supports: (category: String) -> Boolean,
): Output? {
  val type =
    types.entries.firstOrNull { supports(it.key) }?.value
      ?: OutputType.REMOTE.takeIf { supports(RemoteOutputs.CATEGORY) }
      ?: return null
  return Output(type = type, name = name, external = true)
}
