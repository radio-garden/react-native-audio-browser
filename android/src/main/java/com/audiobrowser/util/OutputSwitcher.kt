package com.audiobrowser.util

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import timber.log.Timber

/**
 * Presents the system audio **Output Switcher** — the picker users know from the media
 * notification: paired Bluetooth devices, the phone speaker, wired output, Cast targets.
 * System-rendered; the app does not own its look.
 *
 * Availability is Android 11+ (API 30, [isSupported]): API 34+ has the direct
 * `MediaRouter2.showSystemOutputSwitcher()` call, and API 30–33 open the `MEDIA_OUTPUT` settings
 * panel. Below 30 there is no system switcher, so callers should hide the control rather than call
 * [open] (which no-ops + logs).
 *
 * (Android, unlike iOS, does not let an app force media to an arbitrary specific Bluetooth device —
 * route selection is the system's job, which is exactly what this switcher hands off to.)
 */
object OutputSwitcher {
  fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  fun open(context: Context) {
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
        // API 34+: the framework call. Returns false if it couldn't be shown
        // (e.g. no eligible routes); fall back to the settings panel.
        val shown =
          runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }
            .getOrDefault(false)
        if (!shown) openSettingsPanel(context)
      }
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> openSettingsPanel(context)
      else -> Timber.w("OutputSwitcher.open ignored: system switcher needs Android 11+")
    }
  }

  // The MEDIA_OUTPUT settings panel (the rich switcher on Android 11+). There is
  // no public SDK constant for this action below API 34 — apps use the framework
  // settings-panel action string. Launched from the application context, so it
  // needs NEW_TASK. The package extra scopes the switcher to this app's media
  // routing; harmless if the panel ignores it.
  private fun openSettingsPanel(context: Context) {
    val intent =
      Intent(ACTION_MEDIA_OUTPUT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_OUTPUT_SWITCHER_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
      .onFailure { Timber.e(it, "OutputSwitcher: failed to open MEDIA_OUTPUT panel") }
  }

  // Framework settings-panel action + package extra for the media-output switcher.
  // Not public SDK symbols — verify on device.
  private const val ACTION_MEDIA_OUTPUT = "com.android.settings.panel.action.MEDIA_OUTPUT"
  private const val EXTRA_OUTPUT_SWITCHER_PACKAGE = "com.android.settings.panel.extra.PACKAGE_NAME"
}
