package com.audiobrowser

import com.facebook.react.bridge.BaseJavaModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.turbomodule.core.interfaces.TurboModule
import timber.log.Timber

/**
 * Disposes the live [AudioBrowser] when the React instance goes away, e.g. on reload: Nitro runs
 * `dispose()` only when JS calls it, never on runtime teardown.
 *
 * JS never asks for this module, so under bridgeless React creates it, and later invalidates it,
 * only because it is both `needsEagerInit` and a [TurboModule]
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
