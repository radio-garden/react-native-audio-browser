package com.audiobrowser

import com.facebook.react.bridge.BaseJavaModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.turbomodule.core.interfaces.TurboModule
import timber.log.Timber

/**
 * Disposes the live [AudioBrowser] when the React instance goes away, e.g. on reload.
 *
 * Nitro runs `dispose()` only when JS calls it, not when the runtime is destroyed; without this the
 * old instance stays registered with the service, the process lifecycle and the car-connection
 * observer, and keeps receiving player callbacks over a dead JSI runtime.
 *
 * It exposes nothing to JS, so JS never asks for it: React only invalidates modules it has created,
 * and under bridgeless creates one unasked only if it is both `needsEagerInit` and a [TurboModule]
 * (`ReactPackageTurboModuleManagerDelegate.getEagerInitModuleNames`). Dropping either leaves
 * [invalidate] uncalled.
 */
internal class AudioBrowserLifecycleModule(reactContext: ReactApplicationContext) :
  BaseJavaModule(reactContext), TurboModule {

  override fun getName(): String = NAME

  override fun invalidate() {
    Timber.d("React instance going away — disposing the live AudioBrowser")
    AudioBrowser.disposeCurrent()
    super.invalidate()
  }

  companion object {
    const val NAME: String = "AudioBrowserLifecycle"
  }
}
