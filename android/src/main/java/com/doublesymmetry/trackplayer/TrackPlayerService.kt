package com.doublesymmetry.trackplayer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.audiobrowser.BuildConfig
import com.doublesymmetry.trackplayer.model.PlayerSetupOptions
import com.doublesymmetry.trackplayer.model.PlayerUpdateOptions
import com.facebook.react.bridge.Arguments
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import timber.log.Timber

@MainThread
class TrackPlayerService : MediaLibraryService() {
  lateinit var player: TrackPlayer
  private val binder = LocalBinder()
  private val scope = MainScope()
  private lateinit var mediaSession: MediaLibrarySession

  // Headless service binding
  private val headlessConnection: ServiceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(className: ComponentName, service: IBinder) {}

      override fun onServiceDisconnected(className: ComponentName) {}
    }


  @SuppressLint("WakelockTimeout")
  fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    wakeLock =
      (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TrackPlayerService::class.java.canonicalName)
        .apply {
          setReferenceCounted(false)
          acquire()
        }
  }

  fun abandonWakeLock() {
    wakeLock?.release()
  }

  override fun onCreate() {
    super.onCreate()

    if (BuildConfig.DEBUG) {
      Timber.Forest.plant(
        object : Timber.DebugTree() {
          override fun createStackElementTag(element: StackTraceElement): String? {
            return "${element.className.substringAfterLast('.')}:${element.methodName}"
          }
        }
      )
    } else {
      Timber.Forest.plant(
        object : Timber.Tree() {
          override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= Log.WARN) {
              Log.println(priority, tag ?: "TrackPlayer", message)
              t?.let { throwable ->
                Log.println(priority, tag ?: "TrackPlayer", throwable.toString())
              }
            }
          }
        }
      )
    }

    player = TrackPlayer(this)
    player.setup(PlayerSetupOptions())
    val openAppIntent =
      packageManager.getLaunchIntentForPackage(packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        // Add the Uri data so apps can identify that it was a notification click
        data = "trackplayer://notification.click".toUri()
        action = Intent.ACTION_VIEW
      }
    mediaSession =
      MediaLibrarySession.Builder(this, player.forwardingPlayer, player.getMediaSessionCallback())
        // https://github.com/androidx/media/issues/1218
        .setSessionActivity(
          PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
          )
        )
        .build()
    player.setMediaSession(mediaSession)

    // Bind headless service once at startup for JS task execution
    val headlessIntent = Intent(applicationContext, TrackPlayerHeadlessTaskService::class.java)
    bindService(headlessIntent, headlessConnection, BIND_AUTO_CREATE)
  }

  override fun onBind(intent: Intent?): IBinder? {
    Timber.d("action: ${intent?.action}, package: ${intent?.`package`}")
    return if (intent?.action != null) {
      Timber.d("Returning MediaLibraryService binder for ${intent.action}")
      super.onBind(intent)
    } else {
      Timber.d("Service being bound by module - returning LocalBinder")
      binder
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    onUnbind(rootIntent)

    val appKilledPlaybackBehavior = player.appKilledPlaybackBehavior

    Timber.d("player = $player, appKilledPlaybackBehavior = $appKilledPlaybackBehavior")

    // Check if there are still external controllers connected (like Android Auto)
    val hasExternalControllers =
      mediaSession.connectedControllers.any { controller ->
        controller.packageName != packageName && // Not our own app
          controller.packageName != "com.android.systemui" // Not system UI
      }

    Timber.d("hasExternalControllers = $hasExternalControllers")


    when (appKilledPlaybackBehavior) {
      AppKilledPlaybackBehavior.PAUSE_PLAYBACK -> {
        Timber.d("Pausing playback - appKilledPlaybackBehavior = $appKilledPlaybackBehavior")
        player.pause()
        // Service continues running for Android Auto
      }

      AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION -> {
        if (hasExternalControllers) {
          Timber.d("External controllers still connected - deferring aggressive cleanup")
          // Just pause and remove notification, but keep service alive for external controllers
          player.pause()
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          Timber.d("No external controllers - proceeding with service shutdown")
          try {
            if (::mediaSession.isInitialized) {
              mediaSession.release()
            }
            player.clear()
            player.stop()
            player.destroy()
            scope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            exitProcess(0)
          } catch (e: Exception) {
            Timber.e(e, "Error during aggressive cleanup in onTaskRemoved")
            // Still try to stop the service
            stopSelf()
          }
        }
      }

      AppKilledPlaybackBehavior.CONTINUE_PLAYBACK -> {
        Timber.d("Continuing playback - service remains available for Android Auto")
        // Service continues running for Android Auto with existing callbacks
      }
    }
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
    Timber.d("onGetSession requested by: ${controllerInfo.packageName}")

    // Ensure player is properly set up for external controllers like Android Auto
    // Only call setup if callbacks haven't been installed yet (meaning React Native hasn't connected)
    if (player.getCallbacks() == null) {
      Timber.w("External controller connecting before React Native setup - using default options")
      player.setup(PlayerSetupOptions())
    }

    return mediaSession
  }

  override fun onUnbind(intent: Intent?): Boolean {
    Timber.d("onUnbind called - action: ${intent?.action}, package: ${intent?.`package`}")
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    Timber.d("onDestroy called")

    // Release wake lock if held
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
      }
    }

    Timber.d("Releasing media session and destroying player")
    if (::mediaSession.isInitialized) {
      mediaSession.release()
    }
    player.destroy()

    super.onDestroy()
  }

  // Android Auto request resolution methods
  fun resolveGetItemRequest(requestId: String, mediaItem: MediaItem) {
    player.resolveGetItemRequest(requestId, mediaItem)
  }

  fun resolveGetChildrenRequest(
    requestId: String,
    items: List<MediaItem>,
    totalChildrenCount: Int,
  ) {
    player.resolveGetChildrenRequest(requestId, items, totalChildrenCount)
  }

  fun resolveSearchRequest(requestId: String, items: List<MediaItem>, totalMatchesCount: Int) {
    player.resolveSearchRequest(requestId, items, totalMatchesCount)
  }

  inner class LocalBinder : Binder() {
    val service = this@TrackPlayerService
  }

  companion object {
    // Wake lock management
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
  }
}
