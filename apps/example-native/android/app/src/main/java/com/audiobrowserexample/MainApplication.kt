package com.audiobrowserexample

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          // add(MyReactNativePackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // Force AudioBrowserPackage to load and execute static block
    try {
      Class.forName("com.audiobrowser.AudioBrowserPackage")
    } catch (e: Exception) {
      android.util.Log.e("MainApplication", "Failed to load AudioBrowserPackage", e)
    }
    loadReactNative(this)
  }
}
