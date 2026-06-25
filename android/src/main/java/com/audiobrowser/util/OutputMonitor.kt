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
import com.margelo.nitro.audiobrowser.Output
import com.margelo.nitro.audiobrowser.OutputType
import timber.log.Timber

/**
 * Monitors the current system audio output — the device audio is **actually
 * being rendered to right now** — and notifies listeners when it changes.
 *
 * On API 33+ the source of truth is [AudioManager.getAudioDevicesForAttributes]
 * for `USAGE_MEDIA`, which reports the device media audio is *actively routed to*.
 * Crucially this reflects a **manual reroute** — e.g. moving audio to the phone
 * speaker via the system output switcher while Bluetooth headphones stay
 * connected — which neither `isBluetoothA2dpOn` nor MediaRouter's selected route
 * track (both report the merely *connected* device).
 *
 * Change detection:
 *  - [AudioDeviceCallback] fires immediately on device connect/disconnect/plug,
 *    and stays registered the whole time (works in the background too).
 *  - A poll catches a **manual reroute** (the active stream moved to another
 *    already-connected device via the system output switcher). This is the only
 *    way to detect that case, verified the hard way: it emits no public event —
 *    [AudioDeviceCallback] is device add/remove only, `AudioPlaybackCallback`
 *    fires on start/stop only, `MediaRouter`'s selected route stays stuck on the
 *    connected device, and [getAudioDevicesForAttributes] has no public listener.
 *    The player's `AudioTrack` routing listener would catch it, but only while a
 *    track is playing — polling is the only mechanism that also works when nothing
 *    is playing. The poll runs **only while the app is in the foreground** (gated
 *    on [ProcessLifecycleOwner]) and fires once immediately on foreground entry,
 *    so a reroute made while backgrounded is reflected the moment the user returns.
 *    Each tick is a cheap binder query (no wakelock).
 *
 * On API < 33 [getAudioDevicesForAttributes] is unavailable, so we fall back to a
 * coarse heuristic over the connected output devices; that fallback cannot detect
 * a manual reroute while a device stays connected (the long-standing limitation).
 *
 * Threading: [AudioManager] queries are thread-safe, so [current] recomputes live
 * on read (safe from the JS thread's `getOutput()`); emitted change events come
 * from the main-thread callbacks/poll.
 */
class OutputMonitor(private val context: Context) : DefaultLifecycleObserver {

  private val audioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())

  private val mediaAttributes =
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()

  // Last emitted value; the change dedupe baseline. Read from any thread.
  @Volatile private var cached: Output? = null

  private var onOutputChanged: ((Output) -> Unit)? = null

  /** The current audio output, recomputed live, or null when undeterminable. */
  val current: Output?
    get() = computeOutput() ?: cached

  fun setOnOutputChanged(callback: (Output) -> Unit) {
    onOutputChanged = callback
  }

  private val deviceCallback =
    object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = recompute("device-added")

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = recompute("device-removed")
    }

  // Backstop for manual reroutes, which emit no public event (see class doc).
  // Only scheduled while in the foreground.
  private val poll =
    object : Runnable {
      override fun run() {
        recompute("poll")
        mainHandler.postDelayed(this, POLL_INTERVAL_MS)
      }
    }

  /** Begins monitoring. Safe to call from any thread (defers to the main thread). */
  fun start() {
    mainHandler.post {
      audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
      // Delivers onStart synchronously if already foreground, kicking off the poll.
      ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
  }

  /** App entered the foreground: read once now, then poll while foregrounded. */
  override fun onStart(owner: LifecycleOwner) {
    recompute("foreground")
    mainHandler.removeCallbacks(poll)
    mainHandler.postDelayed(poll, POLL_INTERVAL_MS)
  }

  /** App left the foreground: stop polling (AudioDeviceCallback stays registered). */
  override fun onStop(owner: LifecycleOwner) {
    mainHandler.removeCallbacks(poll)
  }

  /**
   * Stops monitoring. Must be called from the owner's teardown
   * (AudioBrowser.dispose) so the callbacks don't leak across JS runtime reloads.
   */
  fun destroy() {
    mainHandler.post {
      mainHandler.removeCallbacks(poll)
      ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
      audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
  }

  private fun recompute(reason: String) {
    val output = computeOutput() ?: return
    if (output == cached) return
    cached = output
    Timber.d("OutputMonitor [%s]: type=%s name=%s", reason, output.type, output.name)
    onOutputChanged?.invoke(output)
  }

  private fun computeOutput(): Output? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val device =
        audioManager.getAudioDevicesForAttributes(mediaAttributes).firstOrNull { it.isSink }
          ?: return null
      val type = outputTypeOf(device.type)
      return Output(type = type, name = deviceName(device, type), external = isExternal(type))
    }
    return computeOutputLegacy()
  }

  /**
   * API < 33 fallback: pick from the connected output devices. Cannot tell which
   * is *actively* rendering, so it mirrors the old `isBluetoothA2dpOn` heuristic.
   */
  @Suppress("DEPRECATION")
  private fun computeOutputLegacy(): Output? {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filterNotNull()
    val device =
      when {
        audioManager.isBluetoothA2dpOn -> outputs.firstOrNull { outputTypeOf(it.type) == OutputType.BLUETOOTH }
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

    /** Maps an [AudioDeviceInfo] type to a cross-platform [OutputType]. */
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
