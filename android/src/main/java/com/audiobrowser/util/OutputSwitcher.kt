package com.audiobrowser.util

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import timber.log.Timber

/**
 * Opens the system output switcher, where the user picks the device media plays to. Android lets no
 * app route media to a specific Bluetooth device itself.
 */
object OutputSwitcher {
  /** Android 11+. Below it [open] only logs, so callers hide the control. */
  fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  fun open(context: Context) {
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
        // False when it cannot be shown, e.g. no eligible routes.
        val shown =
          runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }
            .getOrDefault(false)
        if (!shown) openSettingsPanel(context)
      }
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> openSettingsPanel(context)
      else -> Timber.w("OutputSwitcher.open ignored: system switcher needs Android 11+")
    }
  }

  // NEW_TASK because the context may be the application's. The package extra scopes the panel to
  // this app's media.
  private fun openSettingsPanel(context: Context) {
    val intent =
      Intent(ACTION_MEDIA_OUTPUT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_OUTPUT_SWITCHER_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
      .onFailure { Timber.e(it, "OutputSwitcher: failed to open MEDIA_OUTPUT panel") }
  }

  // The framework's settings-panel strings; not public SDK symbols.
  private const val ACTION_MEDIA_OUTPUT = "com.android.settings.panel.action.MEDIA_OUTPUT"
  private const val EXTRA_OUTPUT_SWITCHER_PACKAGE = "com.android.settings.panel.extra.PACKAGE_NAME"
}
