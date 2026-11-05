package com.audiobrowser.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import com.audiobrowser.RemoteOutputs
import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// API 32: OutputMonitor reads the connected devices there, which Robolectric shadows; it does not
// shadow API 33's getAudioDevicesForAttributes.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class OutputMonitorTest {
  private lateinit var audioManager: AudioManager
  private lateinit var monitor: OutputMonitor
  private val emitted = mutableListOf<Output>()

  private val kitchen = Output(type = OutputType.REMOTE, name = "Kitchen", external = true)

  @Before
  fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    monitor = OutputMonitor(context)
    monitor.setOnOutputChanged { emitted.add(it) }
    monitor.start()
    idle()
  }

  @After
  fun tearDown() {
    monitor.destroy()
    idle()
    RemoteOutputs.setCurrent(null)
  }

  @Test
  fun `a remote output takes precedence over the local device`() {
    routeMediaTo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    RemoteOutputs.setCurrent(kitchen)
    idle()

    assertEquals(kitchen, monitor.current)
    assertEquals(kitchen, emitted.last())
  }

  @Test
  fun `leaving the remote output reports the local device again`() {
    routeMediaTo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    RemoteOutputs.setCurrent(kitchen)
    idle()

    RemoteOutputs.setCurrent(null)
    idle()

    assertEquals(OutputType.SPEAKER, monitor.current?.type)
    assertEquals(OutputType.SPEAKER, emitted.last().type)
  }

  @Test
  fun `leaving a remote output with no local reading reports the phone speaker`() {
    for (type in listOf(OutputType.REMOTE, OutputType.CAST, OutputType.SONOS)) {
      RemoteOutputs.setCurrent(Output(type = type, name = "Kitchen", external = true))
      idle()

      RemoteOutputs.setCurrent(null)
      idle()

      val phoneSpeaker = Output(type = OutputType.SPEAKER, name = "Phone speaker", external = false)
      assertEquals("$type", phoneSpeaker, emitted.last())
      assertEquals("$type", phoneSpeaker, monitor.current)
    }
  }

  private fun routeMediaTo(deviceType: Int) {
    val device = mock(AudioDeviceInfo::class.java)
    `when`(device.type).thenReturn(deviceType)
    `when`(device.productName).thenReturn("Pixel")
    shadowOf(audioManager).addOutputDevice(device, false)
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
