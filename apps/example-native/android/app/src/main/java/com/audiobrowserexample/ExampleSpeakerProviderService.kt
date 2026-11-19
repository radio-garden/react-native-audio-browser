package com.audiobrowserexample

import android.content.Context
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteProviderService
import androidx.mediarouter.media.MediaRouter
import com.audiobrowser.RemoteOutputs

/** Publishes one fake speaker as a remote output, for trying the Output Switcher. It plays nothing. */
class ExampleSpeakerProviderService : MediaRouteProviderService() {
  override fun onCreateMediaRouteProvider(): MediaRouteProvider = ExampleSpeakerProvider(this)
}

private class ExampleSpeakerProvider(context: Context) : MediaRouteProvider(context) {
  init {
    publish(MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
  }

  private fun publish(connectionState: Int) {
    val speaker =
      RemoteOutputs.routeDescriptorBuilder(ROUTE_ID, "Example speaker")
        .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
        .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER)
        .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED)
        .setConnectionState(connectionState)
        .setEnabled(true)
        .build()
    descriptor = MediaRouteProviderDescriptor.Builder().addRoute(speaker).build()
  }

  // Below Android 14 every app lists the speaker: serve only this app's sessions.
  override fun onCreateRouteController(
    routeId: String,
    options: RouteControllerOptions,
  ): RouteController? {
    if (options.clientPackageName != context.packageName) return null
    return object : RouteController() {
      override fun onSelect() = publish(MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED)

      override fun onUnselect(reason: Int) =
        publish(MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
    }
  }

  private companion object {
    const val ROUTE_ID = "example-speaker"
  }
}
