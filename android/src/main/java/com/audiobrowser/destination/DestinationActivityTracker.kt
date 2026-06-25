package com.audiobrowser.destination

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the current foreground (resumed) Activity so the [DestinationCoordinator] can present the
 * MediaRouter chooser dialog, which needs an Activity-themed context. The library runs from the
 * bound Service and otherwise holds no Activity.
 *
 * Lives in the **main** sourceset so the chooser works for Sonos even in Cast-opt-out builds (it is
 * the single Activity tracker for both backends' picker). Holds only a [WeakReference]; registered
 * process-wide once.
 */
object DestinationActivityTracker {
  private var currentRef: WeakReference<Activity>? = null
  private var registered = false

  val current: Activity?
    get() = currentRef?.get()

  fun register(application: Application) {
    if (registered) return
    registered = true
    application.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
          currentRef = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
          if (currentRef?.get() === activity) currentRef = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
          if (currentRef?.get() === activity) currentRef = null
        }
      }
    )
  }
}
