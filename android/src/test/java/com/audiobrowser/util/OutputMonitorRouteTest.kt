package com.audiobrowser.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// AudioDeviceInfo.TYPE_ECHO_REFERENCE, a system API.
private const val TYPE_ECHO_REFERENCE = 28

// API 33: OutputMonitor reads the devices media routes to, which Robolectric does not shadow.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutputMonitorRouteTest {
  private val phoneSpeaker = Output(type = OutputType.SPEAKER, name = Build.MODEL, external = false)

  @Test
  fun `media routed only to the remote submix reads as the phone speaker`() {
    val monitor = routedTo(device(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, "remote-submix"))

    assertEquals(phoneSpeaker, monitor.current)
  }

  @Test
  fun `headphones that give way to the remote submix read as the phone speaker`() {
    val audioManager = mock(AudioManager::class.java)
    route(audioManager, device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Headphones"))
    val monitor = monitorOf(audioManager)
    val emitted = mutableListOf<Output>()
    monitor.setOnOutputChanged { emitted.add(it) }
    monitor.start()
    idle()
    val callback = ArgumentCaptor.forClass(AudioDeviceCallback::class.java)
    verify(audioManager).registerAudioDeviceCallback(callback.capture(), any(Handler::class.java))
    callback.value.onAudioDevicesAdded(arrayOf())
    assertEquals(OutputType.HEADPHONES, emitted.last().type)

    route(audioManager, device(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, "remote-submix"))
    callback.value.onAudioDevicesRemoved(arrayOf())

    assertEquals(phoneSpeaker, emitted.last())
    assertEquals(phoneSpeaker, monitor.current)
    monitor.destroy()
    idle()
  }

  @Test
  fun `the remote submix beside a listener's output reads as that output`() {
    val monitor =
      routedTo(
        device(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, "remote-submix"),
        device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Headset"),
      )

    assertEquals(
      Output(type = OutputType.BLUETOOTH, name = "Headset", external = true),
      monitor.current,
    )
  }

  @Test
  fun `a line out without a product name is named by the phone model`() {
    val monitor = routedTo(device(AudioDeviceInfo.TYPE_LINE_ANALOG, ""))

    assertEquals(
      Output(type = OutputType.OTHER, name = Build.MODEL, external = true),
      monitor.current,
    )
  }

  @Test
  fun `devices no listener picks have no output type`() {
    for (type in
      listOf(
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        AudioDeviceInfo.TYPE_TELEPHONY,
        TYPE_ECHO_REFERENCE,
        AudioDeviceInfo.TYPE_UNKNOWN,
      )) {
      assertNull("$type", OutputMonitor.outputTypeOf(type))
    }
  }

  @Test
  fun `outputs a listener picks map to their kind`() {
    val expected =
      mapOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to OutputType.BLUETOOTH,
        AudioDeviceInfo.TYPE_BLE_HEADSET to OutputType.BLUETOOTH,
        AudioDeviceInfo.TYPE_BLE_SPEAKER to OutputType.BLUETOOTH,
        AudioDeviceInfo.TYPE_BLE_BROADCAST to OutputType.BLUETOOTH,
        AudioDeviceInfo.TYPE_HEARING_AID to OutputType.BLUETOOTH,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO to OutputType.BLUETOOTH,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES to OutputType.HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET to OutputType.HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET to OutputType.HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE to OutputType.USB,
        AudioDeviceInfo.TYPE_USB_ACCESSORY to OutputType.USB,
        AudioDeviceInfo.TYPE_HDMI to OutputType.HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC to OutputType.HDMI,
        AudioDeviceInfo.TYPE_HDMI_EARC to OutputType.HDMI,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to OutputType.RECEIVER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to OutputType.SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE to OutputType.SPEAKER,
        AudioDeviceInfo.TYPE_MULTICHANNEL_GROUP to OutputType.OTHER,
        AudioDeviceInfo.TYPE_LINE_ANALOG to OutputType.OTHER,
        AudioDeviceInfo.TYPE_LINE_DIGITAL to OutputType.OTHER,
        AudioDeviceInfo.TYPE_AUX_LINE to OutputType.OTHER,
        AudioDeviceInfo.TYPE_DOCK to OutputType.OTHER,
        AudioDeviceInfo.TYPE_DOCK_ANALOG to OutputType.OTHER,
        AudioDeviceInfo.TYPE_IP to OutputType.OTHER,
      )
    for ((type, outputType) in expected) {
      assertEquals("$type", outputType, OutputMonitor.outputTypeOf(type))
    }
  }

  @Test
  fun `media on an Automotive bus reads as the speaker`() {
    val monitor = routedTo(device(AudioDeviceInfo.TYPE_BUS, "bus0_media_out"))

    assertEquals(phoneSpeaker, monitor.current)
  }

  private fun routedTo(vararg devices: AudioDeviceInfo): OutputMonitor {
    val audioManager = mock(AudioManager::class.java)
    route(audioManager, *devices)
    return monitorOf(audioManager)
  }

  private fun route(audioManager: AudioManager, vararg devices: AudioDeviceInfo) {
    `when`(audioManager.getAudioDevicesForAttributes(any(AudioAttributes::class.java)))
      .thenReturn(devices.toList())
  }

  private fun monitorOf(audioManager: AudioManager): OutputMonitor {
    val context = mock(Context::class.java)
    `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
    return OutputMonitor(context)
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  private fun device(type: Int, productName: String): AudioDeviceInfo {
    val device = mock(AudioDeviceInfo::class.java)
    `when`(device.type).thenReturn(type)
    `when`(device.isSink).thenReturn(true)
    `when`(device.productName).thenReturn(productName)
    return device
  }
}
