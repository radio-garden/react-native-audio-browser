package com.audiobrowser.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.audiobrowser.RemoteOutputs
import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import timber.log.Timber

/**
 * Reports the audio output media plays to: a selected [RemoteOutputs] route first, else the local
 * device.
 *
 * On API 33+ the local device is [AudioManager.getAudioDevicesForAttributes] for `USAGE_MEDIA`,
 * which follows a manual reroute through the system output switcher while headphones stay
 * connected; `isBluetoothA2dpOn` and MediaRouter's selected route report only the connected device.
 * Below 33 a heuristic over the connected devices stands in, and misses such a reroute.
 *
 * Change events arrive on the main thread; [current] can be read from any thread.
 */
class OutputMonitor(private val context: Context) : DefaultLifecycleObserver {

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())

  private val mediaAttributes =
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()

  // Last emitted output, the baseline for change detection; [current] reads it from any thread.
  @Volatile private var cached: Output? = null

  private var onOutputChanged: ((Output) -> Unit)? = null

  /** Read live; the last emitted output when there is no reading, null before any. */
  val current: Output?
    get() = computeOutput() ?: cached

  fun setOnOutputChanged(callback: (Output) -> Unit) {
    onOutputChanged = callback
  }

  private val deviceCallback =
    object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
        recompute("device-added")

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
        recompute("device-removed")
    }

  private val remoteOutputListener = { recompute("remote-output") }

  // A manual reroute to an already-connected device emits no public event: AudioDeviceCallback is
  // add/remove only, AudioPlaybackCallback start/stop only, and getAudioDevicesForAttributes has no
  // listener. Polling catches it even when nothing plays. Scheduled only in the foreground.
  private val poll =
    object : Runnable {
      override fun run() {
        recompute("poll")
        mainHandler.postDelayed(this, POLL_INTERVAL_MS)
      }
    }

  /** Safe from any thread: registers on the main thread. */
  fun start() {
    mainHandler.post {
      audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
      RemoteOutputs.addListener(remoteOutputListener)
      // Delivers onStart at once when already in the foreground, which starts the poll.
      ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
  }

  // Reads at once, so a reroute made in the background shows on return.
  override fun onStart(owner: LifecycleOwner) {
    recompute("foreground")
    mainHandler.removeCallbacks(poll)
    mainHandler.postDelayed(poll, POLL_INTERVAL_MS)
  }

  // The device callback stays registered in the background; only the poll stops.
  override fun onStop(owner: LifecycleOwner) {
    mainHandler.removeCallbacks(poll)
  }

  /** Must be called on the owner's dispose, or the callbacks leak across JS runtime reloads. */
  fun destroy() {
    mainHandler.post {
      mainHandler.removeCallbacks(poll)
      ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
      audioManager.unregisterAudioDeviceCallback(deviceCallback)
      RemoteOutputs.removeListener(remoteOutputListener)
    }
  }

  private fun recompute(reason: String) {
    // With no local reading, a remote output that is gone falls back to the phone speaker, so
    // listeners hear that it is gone.
    val output =
      computeOutput()
        ?: if (cached?.type in REMOTE_TYPES)
          Output(
            type = OutputType.SPEAKER,
            name = defaultName(OutputType.SPEAKER),
            external = false,
          )
        else return
    if (output == cached) return
    cached = output
    Timber.d("OutputMonitor [%s]: type=%s name=%s", reason, output.type, output.name)
    onOutputChanged?.invoke(output)
  }

  private fun computeOutput(): Output? {
    RemoteOutputs.current?.let {
      return it
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val device =
        audioManager.getAudioDevicesForAttributes(mediaAttributes).firstOrNull { it.isSink }
          ?: return null
      val type = outputTypeOf(device.type)
      return Output(type = type, name = deviceName(device, type), external = isExternal(type))
    }
    return computeOutputLegacy()
  }

  // API < 33: cannot tell which connected device renders, so it guesses from isBluetoothA2dpOn.
  @Suppress("DEPRECATION")
  private fun computeOutputLegacy(): Output? {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filterNotNull()
    val device =
      when {
        audioManager.isBluetoothA2dpOn ->
          outputs.firstOrNull { outputTypeOf(it.type) == OutputType.BLUETOOTH }
        else ->
          outputs.firstOrNull { outputTypeOf(it.type) == OutputType.HEADPHONES }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
      } ?: return null
    val type = outputTypeOf(device.type)
    return Output(type = type, name = deviceName(device, type), external = isExternal(type))
  }

  private fun deviceName(device: AudioDeviceInfo, type: OutputType): String =
    device.productName?.toString()?.ifBlank { null } ?: defaultName(type)

  private fun isExternal(type: OutputType): Boolean =
    type != OutputType.SPEAKER && type != OutputType.RECEIVER

  private fun defaultName(type: OutputType): String =
    when (type) {
      OutputType.BLUETOOTH -> "Bluetooth"
      OutputType.HEADPHONES -> "Headphones"
      OutputType.USB -> "USB"
      OutputType.HDMI -> "HDMI"
      OutputType.RECEIVER -> "Earpiece"
      else -> "Phone speaker"
    }

  companion object {
    private const val POLL_INTERVAL_MS = 1000L
    private val REMOTE_TYPES = setOf(OutputType.REMOTE, OutputType.CAST, OutputType.SONOS)

    /** Maps an [AudioDeviceInfo] `TYPE_*` to an [OutputType]; [OutputType.OTHER] if unknown. */
    fun outputTypeOf(deviceType: Int): OutputType =
      when (deviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> OutputType.BLUETOOTH

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET -> OutputType.HEADPHONES

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputType.USB

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> OutputType.HDMI

        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputType.RECEIVER

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputType.SPEAKER

        else -> OutputType.OTHER
      }
  }
}
