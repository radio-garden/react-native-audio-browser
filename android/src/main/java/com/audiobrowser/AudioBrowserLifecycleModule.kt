package com.audiobrowser

import com.facebook.react.bridge.BaseJavaModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.turbomodule.core.interfaces.TurboModule
import timber.log.Timber

/**
 * Tears the live [AudioBrowser] down when the React instance goes away.
 *
 * Nitro's `dispose()` is reachable only from an explicit `dispose()` call in JS — it is *not* run
 * when the runtime is destroyed. Without this, a reload leaves the previous instance registered
 * with the Service, the process lifecycle and the car-connection observer, so it stays reachable
 * (and keeps receiving player callbacks over a dead JSI runtime) forever.
 *
 * [invalidate] is what React Native calls on instance teardown, so it is the hook that actually
 * fires. The module exists only for that callback and exposes nothing to JS, so it has to be
 * created without JS ever asking for it: `TurboModuleManager.invalidate` only invalidates modules
 * in its holder map, and under bridgeless a module reaches that map eagerly only if it is *both*
 * `needsEagerInit` and `isTurboModule`
 * (`ReactPackageTurboModuleManagerDelegate.getEagerInitModuleNames`). Hence the marker interface —
 * a plain legacy module is created solely on request from JS, which for this one never comes.
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
