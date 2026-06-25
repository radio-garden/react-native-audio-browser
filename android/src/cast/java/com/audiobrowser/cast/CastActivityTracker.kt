package com.audiobrowser.cast

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the current foreground (resumed) Activity so [CastSessionController.showPicker] can present
 * the framework chooser dialog, which requires an Activity-themed context. The library runs Cast
 * from the bound Service and otherwise holds no Activity.
 *
 * Registered process-wide once from the Cast bridge. Holds only a [WeakReference] so it never pins
 * an Activity past its lifecycle. Cast-sourceset-only; never compiled into the default build.
 */
object CastActivityTracker {
  private var currentRef: WeakReference<Activity>? = null
  private var registered = false

  val current: Activity?
    get() = currentRef?.get()

  /** Idempotently registers Activity lifecycle tracking against the process [Application]. */
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
