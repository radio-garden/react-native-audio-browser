package com.audiobrowser.model

import com.margelo.nitro.audiobrowser.NativeSetupPlayerOptions
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSetupOptionsTest {
  private fun setup(remoteOutputs: RemoteOutputsOptions?) =
    NativeSetupPlayerOptions(
      android = null,
      ios = null,
      options = null,
      autoUpdateNowPlayingMetadata = null,
      nowPlayingMetadataFormatter = null,
      playWhenReady = null,
      repeatMode = null,
      retry = null,
      keepSessionAliveOnError = null,
      remoteOutputs = remoteOutputs,
    )

  @Test
  fun `a setup that leaves remote outputs out turns them off`() {
    val options = PlayerSetupOptions()
    val sonos = RemoteOutputsOptions(cast = null, castReceiverApplicationId = null, sonos = true)

    options.update(setup(sonos))
    assertEquals(sonos, options.remoteOutputs)

    options.update(setup(null))
    assertNull(options.remoteOutputs)
  }
}
