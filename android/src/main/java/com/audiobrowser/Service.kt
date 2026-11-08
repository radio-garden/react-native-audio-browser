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
import androidx.media3.session.MediaSessionService
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.svg.SvgDecoder
import com.audiobrowser.model.PlayerSetupOptions
import com.audiobrowser.player.Player
import com.audiobrowser.util.ArtworkProviderDeps
import com.audiobrowser.util.BatteryWarningStore
import com.audiobrowser.util.CoilArtworkLoader
import com.audiobrowser.util.CoilArtworkLoaderHolder
import com.audiobrowser.util.CoilBitmapLoader
import com.margelo.nitro.audiobrowser.AppKilledPlaybackBehavior
import com.margelo.nitro.audiobrowser.MediaReference
import com.margelo.nitro.audiobrowser.SearchMode
import com.margelo.nitro.audiobrowser.SearchParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * The MediaLibraryService behind background playback, Android Auto and other external controllers.
 *
 * As a [MediaSessionService.Listener] it records when Android 12+ refuses a foreground service
 * start from the background (a Bluetooth resume while the app is killed, under battery
 * restrictions), so the user can be warned on their next session.
 */
@MainThread
class Service : MediaLibraryService(), MediaSessionService.Listener {
  lateinit var player: Player
  private val binder = LocalBinder()
  private val scope = MainScope()
  private lateinit var mediaSession: MediaLibrarySession
  private lateinit var imageLoader: ImageLoader

  var onBatteryWarningPendingChanged: ((Boolean) -> Unit)? = null

  private var artworkProviderDeps: ArtworkProviderDeps? = null

  /**
   * Whether AudioBrowser holds its [LocalBinder] binding. While it does, [stopSelf] cannot end the
   * service, so a task removal keeps the player.
   */
  private var moduleBound = false

  private var headlessBound = false
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

    // The disk cache keeps Coil's default size limit: 2% of the disk, at most 250 MB.
    imageLoader =
      ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).build() }
        .build()

    val coilBitmapLoader =
      CoilBitmapLoader(
        context = this,
        imageLoader = imageLoader,
        resolveDisplayArtwork = { uri, sizeHint -> player.resolveDisplayArtwork(uri, sizeHint) },
        getArtworkSizeHint = { player.artworkSizeHintPixels },
      )

    player.coilBitmapLoader = coilBitmapLoader
    player.imageLoader = imageLoader
    player.onPlaybackResumptionFailed = { releasePlaceholder() }

    // ArtworkContentProvider reads the holder to serve browse artwork to Android Auto and AAOS over
    // content:// URIs.
    val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    artworkProviderDeps =
      ArtworkProviderDeps(
        loader = CoilArtworkLoader(this, imageLoader),
        registry = player.browseArtworkRegistry,
        scope = artworkScope,
        artworkSizeHint = { player.artworkSizeHintPixels },
      )
    CoilArtworkLoaderHolder.set(artworkProviderDeps!!)
    // Best-effort: the previous process's files are stale, and keeping them grows the disk across
    // restarts. The provider fetches again on demand.
    runCatching {
      java.io
        .File(cacheDir, com.audiobrowser.util.ArtworkContentProvider.ARTWORK_SUBDIR)
        .deleteRecursively()
    }

    val openAppIntent =
      packageManager.getLaunchIntentForPackage(packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        // Lets the app tell that a notification click opened it.
        data = "audiobrowser://notification".toUri()
        action = Intent.ACTION_VIEW
      }
    mediaSession =
      MediaLibrarySession.Builder(this, player.forwardingPlayer, player.getMediaSessionCallback())
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

    setListener(this)
    player.setMediaSession(mediaSession)

    Intent(applicationContext, HeadlessTaskService::class.java).also { headlessIntent ->
      Timber.d("Binding to HeadlessTaskService for JS execution")
      headlessBound = bindService(headlessIntent, headlessConnection, BIND_AUTO_CREATE)
      Timber.d("HeadlessTaskService bind result: $headlessBound")
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Timber.d("onStartCommand: action=${intent?.action}")

    intent
      ?.takeIf { it.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH" }
      ?.let { searchIntent ->
        val searchParams = parseSearchIntent(searchIntent)
        Timber.d("MEDIA_PLAY_FROM_SEARCH intent received: $searchParams")
        // A search that plays has waited for AudioBrowser, whose MediaBrowser connecting hands
        // the notification to Media3.
        playInForeground(scope, startId, hasMedia = { player.currentTrack != null }) {
          player.playFromSearch(searchParams)
        }
      }
    // Released by Media3's update after the play request, or by a failed resumption.
    if (intent?.isForegroundStartKey() == true) holdForeground()

    return super.onStartCommand(intent, flags, startId)
  }

  /** See https://developer.android.com/guide/components/intents-common#PlaySearch */
  private fun parseSearchIntent(intent: Intent): SearchParams {
    val query = intent.getStringExtra(SearchManager.QUERY) ?: ""

    val mediaFocus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS)

    // The focus picks a vertical. Genre, artist and album focuses are filters, carried by the
    // extras
    // below, so they yield no mode. Android has no station or podcast focus: those verticals are
    // iOS-only.
    val mode =
      when (mediaFocus) {
        "vnd.android.cursor.item/*" -> if (query.isEmpty()) SearchMode.ANY else null
        "vnd.android.cursor.item/audio" -> SearchMode.SONG
        MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> SearchMode.PLAYLIST
        else -> null
      }

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
      reference = MediaReference.UNKNOWN,
    )
  }

  override fun onBind(intent: Intent?): IBinder? {
    Timber.d("action: ${intent?.action}, package: ${intent?.`package`}")
    return if (intent?.action != null) {
      Timber.d("Returning MediaLibraryService binder for ${intent.action}")
      super.onBind(intent)
    } else {
      Timber.d("Service being bound by module - returning LocalBinder")
      moduleBound = true
      binder
    }
  }

  override fun onRebind(intent: Intent?) {
    if (intent?.action == null) moduleBound = true
    super.onRebind(intent)
  }

  @OptIn(UnstableApi::class)
  override fun onTaskRemoved(rootIntent: Intent?) {
    val appKilledPlaybackBehavior = player.appKilledPlaybackBehavior

    Timber.d("player = $player, appKilledPlaybackBehavior = $appKilledPlaybackBehavior")

    when (appKilledPlaybackBehavior) {
      AppKilledPlaybackBehavior.PAUSE_PLAYBACK -> {
        Timber.d("Pausing playback - appKilledPlaybackBehavior = $appKilledPlaybackBehavior")
        player.pause()
      }

      AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION -> {
        // Only a car keeps the session through a swipe: the Bluetooth stack connects as a
        // controller whenever Bluetooth is on, with or without a device.
        val carConnected =
          mediaSession.connectedControllers.any {
            mediaSession.isAutomotiveController(it) || mediaSession.isAutoCompanionController(it)
          }
        if (carConnected) {
          Timber.d("A car is connected - pausing instead of stopping")
          player.pause()
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else if (moduleBound) {
          Timber.d("AudioBrowser still bound - stopping playback but keeping the player")
          stopPlaybackAndRemoveNotification()
          // Ends the service once AudioBrowser unbinds.
          stopSelf()
        } else {
          Timber.d("No car connected - shutting the service down")
          try {
            if (::mediaSession.isInitialized) {
              mediaSession.release()
            }
            stopPlaybackAndRemoveNotification()
            tearDownArtworkProvider()
            player.destroy()
            scope.cancel()
            stopSelf()
          } catch (e: Exception) {
            Timber.e(e, "Error during aggressive cleanup in onTaskRemoved")
            stopSelf()
          }
        }
      }

      AppKilledPlaybackBehavior.CONTINUE_PLAYBACK -> {
        Timber.d("Continuing playback - service remains available for Android Auto")
      }
    }
  }

  private fun stopPlaybackAndRemoveNotification() {
    // Before stop(), while a mid-ramp fade can still restore the volume.
    player.sleepTimer.clear()
    player.clear()
    player.stop()
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
    Timber.d("onGetSession requested by: ${controllerInfo.packageName}")

    // No setup() here: default options would replace the ones setupPlayer applied.
    if (player.getCallbacks() == null) {
      Timber.w("External controller connecting before React Native setup - using default options")
    }

    return mediaSession
  }

  override fun onUnbind(intent: Intent?): Boolean {
    Timber.d("onUnbind called - action: ${intent?.action}, package: ${intent?.`package`}")
    if (intent?.action == null) {
      moduleBound = false
      // Android calls onBind once per distinct Intent while the service lives and caches the
      // binder, so only onRebind tells of the next binding, and only when this returns true.
      return true
    }
    return super.onUnbind(intent)
  }

  @OptIn(UnstableApi::class)
  override fun onForegroundServiceStartNotAllowedException() {
    Timber.w("Foreground service start blocked by Android - recording battery warning")
    BatteryWarningStore.recordFailure(this)
    onBatteryWarningPendingChanged?.invoke(true)
  }

  /**
   * Cancels the artwork provider's scope and clears its holder. Idempotent. Call it before
   * player.destroy() on every teardown path, so no in-flight load reaches a destroyed player.
   */
  private fun tearDownArtworkProvider() {
    artworkProviderDeps?.let {
      it.scope.cancel()
      CoilArtworkLoaderHolder.clearIf(it)
    }
    artworkProviderDeps = null
  }

  override fun onDestroy() {
    Timber.d("onDestroy called")

    // Unbinding a connection that never bound throws IllegalArgumentException.
    if (headlessBound) {
      unbindService(headlessConnection)
      headlessBound = false
    }

    tearDownArtworkProvider()

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
