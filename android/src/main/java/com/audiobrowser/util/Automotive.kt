package com.audiobrowser.util

import android.content.Context
import android.content.pm.PackageManager

/** True on Android Automotive OS, not on a phone projecting Android Auto. */
val Context.isAutomotive: Boolean
  get() = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

/** [isAutomotive] for code without a Context: false until the Player sets it. */
object Automotive {
  @Volatile var device = false
}
