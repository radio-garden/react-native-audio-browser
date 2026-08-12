package com.audiobrowser

import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.margelo.nitro.audiobrowser.AudioBrowserOnLoad

public class AudioBrowserPackage : TurboReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == AudioBrowserLifecycleModule.NAME) AudioBrowserLifecycleModule(reactContext)
    else null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
    mapOf(
      AudioBrowserLifecycleModule.NAME to
        ReactModuleInfo(
          AudioBrowserLifecycleModule.NAME,
          AudioBrowserLifecycleModule::class.java.name,
          false, // canOverrideExistingModule
          // Eager + TurboModule: nothing in JS imports this module, and its only job is the
          // teardown callback, which never arrives if the module is never created. Both flags are
          // required for that — see the note on the module.
          true, // needsEagerInit
          false, // isCxxModule
          ReactModuleInfo.classIsTurboModule(AudioBrowserLifecycleModule::class.java),
        )
    )
  }

  companion object {
    init {
      AudioBrowserOnLoad.initializeNative()
    }
  }
}
