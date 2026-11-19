package com.audiobrowser

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaTransferReceiver
import com.audiobrowser.sonos.SonosOutputs
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

  /**
   * A descriptor for a route offered as a remote output, in [CATEGORY] and each of [categories],
   * visible from Android 14 only to this app: another app on the library would list it too, and
   * selecting it there would leave that app's playback local.
   */
  @JvmStatic
  fun routeDescriptorBuilder(
    id: String,
    name: String,
    vararg categories: String,
  ): MediaRouteDescriptor.Builder =
    MediaRouteDescriptor.Builder(id, name)
      .addControlFilter(
        IntentFilter().apply {
          addCategory(CATEGORY)
          categories.forEach(::addCategory)
        }
      )
      .setVisibilityRestricted(emptySet())

  private var types: Map<String, OutputType> = emptyMap()

  private val mainHandler = Handler(Looper.getMainLooper())

  private var selector = selectorFor(types)

  // MediaRouter holds routers weakly: let this one go and the Output Switcher stops listing the
  // category's routes, as the app's discovery preference empties on the next GC.
  private var router: MediaRouter? = null

  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  private val onSonosSelectionChanged: () -> Unit = { router?.let(::update) }

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
    SonosOutputs.addListener(onSonosSelectionChanged)
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
    SonosOutputs.removeListener(onSonosSelectionChanged)
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
    val sonosSelected = SonosOutputs.selected != null
    setCurrent(remoteOutputOf(route.name, types, sonosSelected, route::supportsControlCategory))
    // Posted: this runs inside the router's callbacks, which unselect would re-enter.
    if (isUnservedSonosRoute(sonosSelected, route::supportsControlCategory)) {
      mainHandler.post(::releaseUnservedSonosRoute)
    }
  }

  // Unselecting ends the system's session on another app's provider, which plays nothing of ours
  // but keeps the switcher offering Stop casting.
  private fun releaseUnservedSonosRoute() {
    val router = router ?: return
    val route = router.selectedRoute
    if (!isUnservedSonosRoute(SonosOutputs.selected != null, route::supportsControlCategory)) return
    Timber.d("RemoteOutputs: releasing %s, which another app's provider serves", route.name)
    router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
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
 * A Sonos room's route [another app's provider serves][isUnservedSonosRoute] is not an output.
 */
internal fun remoteOutputOf(
  name: String,
  types: Map<String, OutputType>,
  sonosSelected: Boolean,
  supports: (category: String) -> Boolean,
): Output? {
  if (isUnservedSonosRoute(sonosSelected, supports)) return null
  val type =
    types.entries.firstOrNull { supports(it.key) }?.value
      ?: OutputType.REMOTE.takeIf { supports(RemoteOutputs.CATEGORY) }
      ?: return null
  return Output(type = type, name = name, external = true)
}

/**
 * Whether a selected route is a Sonos room's that this app's provider doesn't serve, as it has no
 * room selected: another app on the library publishes the same rooms, and selecting one of its
 * routes leaves playback here. Holds even while this app has Sonos turned off.
 */
internal fun isUnservedSonosRoute(
  sonosSelected: Boolean,
  supports: (category: String) -> Boolean,
): Boolean = supports(SonosOutputs.CATEGORY) && !sonosSelected
