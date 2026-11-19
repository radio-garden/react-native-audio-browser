package com.audiobrowser.model

import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__optional_NowPlayingUpdate____FormatNowPlayingParams as NowPlayingFormatter
import com.margelo.nitro.audiobrowser.NativeSetupPlayerOptions
import com.margelo.nitro.audiobrowser.RemoteOutputsOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerSetupOptionsTest {
  private fun setup(
    remoteOutputs: RemoteOutputsOptions? = null,
    nowPlayingMetadataFormatter: NowPlayingFormatter? = null,
  ) =
    NativeSetupPlayerOptions(
      android = null,
      ios = null,
      options = null,
      autoUpdateNowPlayingMetadata = null,
      nowPlayingMetadataFormatter = nowPlayingMetadataFormatter,
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

  @Test
  fun `a setup without a formatter removes the earlier one`() {
    val options = PlayerSetupOptions()
    val formatter = NowPlayingFormatter { error("not called") }

    options.update(setup(nowPlayingMetadataFormatter = formatter))
    assertSame(formatter, options.nowPlayingMetadataFormatter)

    options.update(setup())
    assertNull(options.nowPlayingMetadataFormatter)
  }
}
