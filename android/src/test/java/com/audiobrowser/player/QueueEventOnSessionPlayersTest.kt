package com.audiobrowser.player

import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// SDK 28: from 33, media3 asks AudioManager.getAudioDevicesForAttributes, which Robolectric
// answers with null.

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QueueEventOnEngineTest : QueueEventTest() {
  override val remoteOutputs: RemoteOutputsOptions? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QueueEventOnRemoteSwitchingPlayerTest : QueueEventTest() {
  override val remoteOutputs: RemoteOutputsOptions? =
    RemoteOutputsOptions(cast = null, castReceiverApplicationId = null, sonos = true)
}
