package com.audiobrowser

import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import coil3.ImageLoader
import coil3.svg.SvgDecoder
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.player.Player
import com.audiobrowser.util.CoilBitmapLoader
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.SearchMode
import com.margelo.nitro.audiobrowser.SearchParams
import kotlin.system.exitProcess
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@MainThread
class Service : MediaLibraryService() {
  lateinit var player: Player
  private val binder = LocalBinder()
  private val scope = MainScope()
  private lateinit var mediaSession: MediaLibrarySession
  private lateinit var imageLoader: ImageLoader

  // Headless service binding
  private val headlessConnection: ServiceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(className: ComponentName, service: IBinder) {}

      override fun onServiceDisconnected(className: ComponentName) {}
    }

  @OptIn(UnstableApi::class)
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
              Log.println(priority, tag ?: "AudioBrowser", message)
              t?.let { throwable ->
                Log.println(priority, tag ?: "AudioBrowser", throwable.toString())
              }
            }
          }
        }
      )
    }

    player = Player(this)
    player.setup(PlayerSetupOptions())

    // Create shared Coil ImageLoader with SVG support
    imageLoader =
      ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .build()

    // Create CoilBitmapLoader for artwork loading with custom headers/auth support
    val coilBitmapLoader =
      CoilBitmapLoader(
        context = this,
        imageLoader = imageLoader,
        getArtworkConfig = { player.browser?.getArtworkConfig() },
      )

    val openAppIntent =
      packageManager.getLaunchIntentForPackage(packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        // Add the Uri data so apps can identify that it was a notification click
        data = "audiobrowser://notification".toUri()
        action = Intent.ACTION_VIEW
      }
    mediaSession =
      MediaLibrarySession.Builder(this, player.forwardingPlayer, player.getMediaSessionCallback())
        // Use Coil for artwork loading (enables custom headers, SVG support)
        .setBitmapLoader(CacheBitmapLoader(coilBitmapLoader))
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
    Intent(applicationContext, HeadlessTaskService::class.java).also { headlessIntent ->
      Timber.d("Binding to HeadlessTaskService for JS execution")
      val bound = bindService(headlessIntent, headlessConnection, BIND_AUTO_CREATE)
      Timber.d("HeadlessTaskService bind result: $bound")
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Timber.d("onStartCommand: action=${intent?.action}")

    // Handle MEDIA_PLAY_FROM_SEARCH intent for voice commands
    intent
      ?.takeIf { it.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH" }
      ?.let { searchIntent ->
        val searchParams = parseSearchIntent(searchIntent)
        Timber.d("MEDIA_PLAY_FROM_SEARCH intent received: $searchParams")
        scope.launch { player.playFromSearch(searchParams) }
      }

    return super.onStartCommand(intent, flags, startId)
  }

  /**
   * Parses MEDIA_PLAY_FROM_SEARCH intent into structured SearchParams.
   *
   * Extracts search mode from EXTRA_MEDIA_FOCUS and relevant metadata fields:
   * - "vnd.android.cursor.item/\*" with empty query → ANY ("play music")
   * - "vnd.android.cursor.item/\*" with query → null (unstructured search)
   * - Audio.Genres.ENTRY_CONTENT_TYPE → GENRE
   * - Audio.Artists.ENTRY_CONTENT_TYPE → ARTIST
   * - Audio.Albums.ENTRY_CONTENT_TYPE → ALBUM
   * - "vnd.android.cursor.item/audio" → SONG
   * - Audio.Playlists.ENTRY_CONTENT_TYPE → PLAYLIST
   * - null or unknown → null (unstructured search)
   *
   * See: https://developer.android.com/guide/components/intents-common#PlaySearch
   */
  private fun parseSearchIntent(intent: Intent): SearchParams {
    // Get the search query from SearchManager.QUERY
    val query = intent.getStringExtra(SearchManager.QUERY) ?: ""

    // Get the media focus type (what kind of media to search for)
    val mediaFocus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS)

    // Determine search mode based on media focus type
    val mode =
      when (mediaFocus) {
        "vnd.android.cursor.item/*" -> {
          // Generic audio content - could be "play music" or unstructured search
          if (query.isEmpty()) SearchMode.ANY else null
        }
        MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE -> SearchMode.GENRE
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> SearchMode.ARTIST
        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> SearchMode.ALBUM
        "vnd.android.cursor.item/audio" -> SearchMode.SONG
        MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> SearchMode.PLAYLIST
        else -> null // No media focus or unknown - unstructured search
      }

    // Extract structured metadata fields
    val genre = intent.getStringExtra("android.intent.extra.genre")
    val artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST)
    val album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM)
    val title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
    val playlist = intent.getStringExtra("android.intent.extra.playlist")

    return SearchParams(
      mode = mode,
      query = query,
      genre = genre,
      artist = artist,
      album = album,
      title = title,
      playlist = playlist,
    )
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
    // Only call setup if callbacks haven't been installed yet (meaning React Native hasn't
    // connected)
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

    // Unbind from HeadlessTaskService to avoid ServiceConnection leak
    unbindService(headlessConnection)

    Timber.d("Releasing media session and destroying player")
    if (::mediaSession.isInitialized) {
      mediaSession.release()
    }
    player.destroy()

    super.onDestroy()
  }

  inner class LocalBinder : Binder() {
    val service = this@Service
  }
}
