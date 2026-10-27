package com.audiobrowser.util

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether this is an Android Automotive OS device, as opposed to a phone projecting Android Auto.
 */
val Context.isAutomotive: Boolean
  get() = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

/** Whether the process runs on Android Automotive OS: false until the Player sets it. */
object Automotive {
  @Volatile var device = false
}
