package com.audiobrowser

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.audiobrowser.event.EventControllerConnection
import com.audiobrowser.extension.find
import com.audiobrowser.model.AppKilledPlaybackBehavior
import com.audiobrowser.model.CustomCommandButton
import com.audiobrowser.model.TrackPlayerOptions
import com.audiobrowser.option.PlayerCapability
import com.audiobrowser.util.ReactContextResolver
import com.facebook.react.ReactApplication
import com.facebook.react.ReactInstanceManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.guava.future
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
@MainThread
class AudioBrowserService : HeadlessJsMediaService() {
  lateinit var player: AudioPlayer
  private val binder = MusicBinder()
  private val scope = MainScope()
  private lateinit var reactInstanceManager: ReactInstanceManager
  private var registeredModule: TrackPlayerModule? = null
  private var wasStartedByExternalService: Boolean = false
  // Temporary reference to the initial player's ExoPlayer, used for MediaSession initialization
  // before the player is configured with options from JavaScript
  private var temporaryPlayer: ExoPlayer? = null
  private lateinit var mediaSession: MediaLibrarySession
  private var sessionCommands: SessionCommands? = null
  private var playerCommands: Player.Commands? = null
  private var customLayout: List<CommandButton> = listOf()
  private var lastWake: Long = 0
  var onStartCommandIntentValid: Boolean = true

  // Android Auto callback request storage
  private val pendingGetItemRequests = ConcurrentHashMap<String, SettableFuture<MediaItem>>()
  private val pendingGetChildrenRequests = ConcurrentHashMap<String, SettableFuture<List<MediaItem>>>()
  private val pendingSearchRequests = ConcurrentHashMap<String, SettableFuture<List<MediaItem>>>()

  // MediaItem lookup by ID for Android Auto
  private var mediaItemById: MutableMap<String, MediaItem> = mutableMapOf()

  fun acquireWakeLock() {
    acquireWakeLockNow(this)
  }

  fun abandonWakeLock() {
    sWakeLock?.release()
  }

  override fun onCreate() {
    Timber.Forest.plant(
      object : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String? {
          return "RNTP-${element.className}:${element.methodName}"
        }
      }
    )

    // Initialize ReactInstanceManager
    reactInstanceManager = (application as ReactApplication).reactNativeHost.reactInstanceManager

    // Create initial player with default options for MediaSession
    player = AudioPlayer(this)

    val openAppIntent =
      packageManager.getLaunchIntentForPackage(packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        // Add the Uri data so apps can identify that it was a notification click
        data = "trackplayer://notification.click".toUri()
        action = Intent.ACTION_VIEW
      }
    mediaSession =
      MediaLibrarySession.Builder(this, player.exoPlayer, InnerMediaSessionCallback())
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

    // Now set up player properly with default options
    setupPlayer(TrackPlayerOptions())

    // Callbacks will be set when the module registers itself via onServiceConnected
    Timber.Forest.d("TrackPlayerService.onCreate() - waiting for module registration")

    super.onCreate()
  }

  private var appKilledPlaybackBehavior =
    AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    onStartCommandIntentValid = intent != null
    Timber.Forest.d("onStartCommand: ${intent?.action}, ${intent?.`package`}")
    super.onStartCommand(intent, flags, startId)

    // Player is always set up in onCreate()
    // For external services (Android Auto), ensure React Native is running for callbacks
    intent?.let { serviceIntent ->
      if (isStartedByExternalService(serviceIntent)) {
        Timber.Forest.d("Started by external service - launching React Native for callbacks")
        wasStartedByExternalService = true
        ensureReactNativeForCallbacks()
      }
    }

    return START_STICKY
  }

  /** Determines if the service was started by an external service like Android Auto */
  private fun isStartedByExternalService(intent: Intent): Boolean {
    return intent.action == "androidx.media3.session.MediaSessionService" ||
      intent.`package`?.contains("auto") == true ||
      intent.`package`?.contains("androidauto") == true ||
      intent.categories?.contains("android.intent.category.CAR_DOCK") == true
  }

  fun setupPlayer(playerOptionsData: TrackPlayerOptions, callbacks: AudioPlayerCallbacks? = null) {
    Timber.Forest.d("Setting up player")

    val options = playerOptionsData.toPlayerOptions()

    // Always create a new player instance
    val oldPlayer = if (::player.isInitialized) player else null
    player = AudioPlayer(this@AudioBrowserService, options, callbacks)
    oldPlayer?.destroy()
    mediaSession.player = player.forwardingPlayer

    // Callbacks will be set when module registers itself via registerModule()
    if (callbacks == null) {
      Timber.Forest.d("No callbacks provided - waiting for module registration")
    } else {
      Timber.Forest.d("Callbacks provided directly")
    }
  }

  /**
   * Updates player options without recreating the player. Used when React Native sends new options
   * after player is already set up.
   */
  fun updatePlayerOptions(playerOptionsData: TrackPlayerOptions) {
    Timber.Forest.d("Updating player options")
    updateOptions(playerOptionsData)
  }

  private fun getModule(): ListenableFuture<TrackPlayerModule?> {
    return CoroutineScope(Dispatchers.Main).future {
      val reactContext = ReactContextResolver.getReactContext(reactInstanceManager)
      Timber.Forest.d("ReactContext obtained, looking for TrackPlayerModule...")

      // Use getNativeModule but check if it creates a new instance
      Timber.Forest.d("About to call getNativeModule...")
      return@future reactContext.getNativeModule(TrackPlayerModule::class.java)
    }
  }

  /** Connects to React Native module when it's ready, without flaky delays. */
  private fun connectToReactNativeWhenReady() {
    CoroutineScope(Dispatchers.Main).launch {
      try {
        Timber.Forest.d("Attempting to connect to React Native module...")
        // This suspends until ReactContext is actually ready
        val reactContext = ReactContextResolver.getReactContext(reactInstanceManager)
        Timber.Forest.d("ReactContext obtained, looking for TrackPlayerModule...")

        // Use getNativeModule but check if it creates a new instance
        Timber.Forest.d("About to call getNativeModule...")
        val trackPlayerModule = reactContext.getNativeModule(TrackPlayerModule::class.java)
        trackPlayerModule?.let { module ->
          Timber.Forest.d("Got TrackPlayerModule instance: ${module.hashCode()}")
          player.setCallbacks(module)
          Timber.Forest.d("Successfully connected TrackPlayerModule callbacks to player")

          // Test callback by triggering a state change
          Timber.Forest.d(
            "Current player callbacks: ${if (player.getCallbacks() != null) "SET" else "NULL"}"
          )
        } ?: run { Timber.Forest.w("TrackPlayerModule was null") }
      } catch (e: Exception) {
        Timber.Forest.e(e, "Failed to connect to React Native module")
      }
    }
  }

  /**
   * Registers a TrackPlayerModule instance with this service. Called when the module connects to
   * the service.
   */
  fun registerModule(module: AudioBrowserModule) {
    val moduleHash = module.hashCode()
    Timber.Forest.d("TrackPlayerModule registered with service: $moduleHash")

    // Always prioritize the latest module registration
    // This ensures main app takes precedence over headless context
    val previousModule = registeredModule
    registeredModule = module
    player.setCallbacks(module)
    Timber.Forest.d("Successfully set callbacks on player")

    // Log if this replaces an existing module
    if (previousModule != null && previousModule != module) {
      Timber.Forest.d("Module replaced: old=${previousModule.hashCode()}, new=$moduleHash")
      Timber.Forest.d("Events will now flow to the new module")
    }
  }

  /**
   * Unregisters the current TrackPlayerModule. Called when the app is closed but service continues
   * running.
   */
  fun unregisterModule() {
    registeredModule?.let { module ->
      Timber.Forest.d("Unregistering TrackPlayerModule: ${module.hashCode()}")
      registeredModule = null
    }
  }

  /**
   * Checks if the service is in a degraded state and attempts recovery. This ensures Android Auto
   * functionality remains stable.
   */
  private fun ensureServiceStability() {
    if (!::player.isInitialized) {
      Timber.Forest.w("Player not initialized - recreating with default options")
      player = AudioPlayer(this)
      setupPlayer(TrackPlayerOptions())
    }

    if (!::mediaSession.isInitialized) {
      Timber.Forest.w("MediaSession not initialized - this should not happen")
      // Don't try to recreate here as it's complex - log for debugging
    }

    // Ensure callbacks are available for Android Auto even if module is disconnected
    if (registeredModule == null && player.getCallbacks() == null) {
      Timber.Forest.d("No callbacks available - service will function with basic playback only")
    }
  }

  /**
   * Ensures React Native is running to provide callbacks for external services like Android Auto.
   * This will launch React Native headlessly if needed to register callbacks.
   */
  private fun ensureReactNativeForCallbacks() {
    Timber.Forest.d("ensureReactNativeForCallbacks() called")

    // If we already have a registered module, we're good
    registeredModule?.let { module ->
      Timber.Forest.d("Using already registered module: ${module.hashCode()}")
      player.setCallbacks(module)
      return
    }

    // Check if React Native is already running
    if (
      ::reactInstanceManager.isInitialized &&
        reactInstanceManager.hasStartedCreatingInitialContext()
    ) {
      Timber.Forest.d("React Native already running - attempting to connect")
      connectToReactNativeWhenReady()
      return
    }

    // Launch React Native headlessly to register callbacks
    Timber.Forest.d("Launching React Native headlessly for callback registration")
    launchReactNativeHeadless()
  }

  /** Launches React Native in headless mode to ensure callbacks can be registered. */
  private fun launchReactNativeHeadless() {
    try {
      Timber.Forest.d("Starting React Native headless JS task")

      // Create task data to identify this as a callback registration task
      val taskData = Arguments.createMap().apply { putString("purpose", "callback_registration") }

      // Start the headless JS task which will trigger React Native initialization
      val taskConfig = HeadlessJsTaskConfig(CALLBACK_REGISTRATION_TASK, taskData, 30000, true)
      startTask(taskConfig)

      // Once React Native initializes, connect to the module
      CoroutineScope(Dispatchers.Main).launch {
        try {
          val reactContext = ReactContextResolver.getReactContext(reactInstanceManager)
          Timber.Forest.d("ReactContext ready after headless launch - connecting module")
          connectToReactNativeModule(reactContext)
        } catch (e: Exception) {
          Timber.Forest.e(e, "Failed to connect to React Native module after headless launch")
        }
      }
    } catch (e: Exception) {
      Timber.Forest.e(e, "Failed to launch React Native headlessly")
    }
  }

  /** Connects to the TrackPlayerModule in the given ReactContext. */
  private fun connectToReactNativeModule(reactContext: ReactContext) {
    try {
      val trackPlayerModule = reactContext.getNativeModule(TrackPlayerModule::class.java)
      trackPlayerModule?.let { module ->
        Timber.Forest.d("Connected to TrackPlayerModule: ${module.hashCode()}")
        registerModule(module)
      } ?: run { Timber.Forest.w("TrackPlayerModule not found in ReactContext") }
    } catch (e: Exception) {
      Timber.Forest.e(e, "Failed to get TrackPlayerModule from ReactContext")
    }
  }

  /**
   * Sets callbacks on the player by connecting to the TrackPlayerModule via ReactContext. This
   * allows external services (like Android Auto) to connect callbacks to the player.
   */
  fun setCallbacksViaReactContext() {
    Timber.Forest.d("setCallbacksViaReactContext() called")
    registeredModule?.let { module ->
      Timber.Forest.d("Using already registered module: ${module.hashCode()}")
      player.setCallbacks(module)
    }
      ?: run {
        Timber.Forest.d(
          "No module registered yet - attempting to find existing React Native module"
        )
        // Only try to connect if React Native might be running
        if (::reactInstanceManager.isInitialized) {
          connectToReactNativeWhenReady()
        } else {
          Timber.Forest.d("React Native not available - service will run without callbacks")
        }
      }
  }

  fun updateOptions(options: TrackPlayerOptions) {
    options.audioOffload?.let { audioOffload -> player.setAudioOffload(audioOffload) }

    options.skipSilence?.let { skipSilence -> player.skipSilence = skipSilence }

    options.ratingType?.let { ratingType -> player.ratingType = ratingType.compat }

    appKilledPlaybackBehavior =
      AppKilledPlaybackBehavior::string.find(options.appKilledPlaybackBehavior)
        ?: AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION

    player.shuffleMode = options.shuffle ?: false

    // Progress update events now handled by MusicModule
    val capabilities = options.capabilities ?: emptyList()
    val notificationCapabilities =
      options.notificationCapabilities?.takeIf { it.isNotEmpty() } ?: capabilities

    val playerCommandsBuilder =
      Player.Commands.Builder()
        .addAll(
          // HACK: without COMMAND_GET_CURRENT_MEDIA_ITEM, notification cannot be created
          Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
          Player.COMMAND_GET_TRACKS,
          Player.COMMAND_GET_TIMELINE,
          Player.COMMAND_GET_METADATA,
          Player.COMMAND_GET_AUDIO_ATTRIBUTES,
          Player.COMMAND_GET_VOLUME,
          Player.COMMAND_GET_DEVICE_VOLUME,
          Player.COMMAND_GET_TEXT,
          Player.COMMAND_SEEK_TO_MEDIA_ITEM,
          Player.COMMAND_SET_MEDIA_ITEM,
          Player.COMMAND_CHANGE_MEDIA_ITEMS,
          Player.COMMAND_PREPARE,
          Player.COMMAND_RELEASE,
        )
    notificationCapabilities.forEach {
      when (it) {
        PlayerCapability.PLAY,
        PlayerCapability.PAUSE -> {
          playerCommandsBuilder.add(Player.COMMAND_PLAY_PAUSE)
        }

        PlayerCapability.STOP -> {
          playerCommandsBuilder.add(Player.COMMAND_STOP)
        }

        PlayerCapability.SEEK_TO -> {
          playerCommandsBuilder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }

        else -> {}
      }
    }
    customLayout =
      CustomCommandButton.entries
        .filter { notificationCapabilities.contains(it.capability) }
        .map { c -> c.commandButton }
    val sessionCommandsBuilder =
      MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
    customLayout.forEach { v -> v.sessionCommand?.let { sessionCommandsBuilder.add(it) } }

    sessionCommands = sessionCommandsBuilder.build()
    playerCommands = playerCommandsBuilder.build()

    if (mediaSession.mediaNotificationControllerInfo != null) {
      // https://github.com/androidx/media/blob/c35a9d62baec57118ea898e271ac66819399649b/demos/session_service/src/main/java/androidx/media3/demo/session/DemoMediaLibrarySessionCallback.kt#L107
      mediaSession.setCustomLayout(mediaSession.mediaNotificationControllerInfo!!, customLayout)
      mediaSession.setAvailableCommands(
        mediaSession.mediaNotificationControllerInfo!!,
        sessionCommandsBuilder.build(),
        playerCommands!!,
      )
    }
  }

  override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig {
    val taskName = intent?.getStringExtra("taskName") ?: TASK_KEY
    return when (taskName) {
      CALLBACK_REGISTRATION_TASK ->
        HeadlessJsTaskConfig(CALLBACK_REGISTRATION_TASK, Arguments.createMap(), 30000, true)
      else -> HeadlessJsTaskConfig(TASK_KEY, Arguments.createMap(), 0, true)
    }
  }

  override fun onBind(intent: Intent?): IBinder? {
    return if (intent?.action != null) {
      super.onBind(intent)
    } else {
      // Module is binding to service
      Timber.Forest.d("Service being bound by module")
      binder
    }
  }

  override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
    // https://github.com/androidx/media/issues/843#issuecomment-1860555950
    super.onUpdateNotification(session, true)
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    onUnbind(rootIntent)
    Timber.Forest.d("onTaskRemoved - app closed, evaluating service behavior")
    Timber.Forest.d("player = $player, appKilledPlaybackBehavior = $appKilledPlaybackBehavior")

    // Unregister the module but keep callbacks for Android Auto
    unregisterModule()

    when (appKilledPlaybackBehavior) {
      AppKilledPlaybackBehavior.PAUSE_PLAYBACK -> {
        Timber.Forest.d("Pausing playback - appKilledPlaybackBehavior = $appKilledPlaybackBehavior")
        player.pause()
        // Service continues running for Android Auto
      }
      AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION -> {
        Timber.Forest.d("Killing service - appKilledPlaybackBehavior = $appKilledPlaybackBehavior")
        mediaSession.release()
        player.clear()
        player.stop()
        // HACK: the service first stops, then starts, then call onTaskRemove. Why system
        // registers the service being restarted?
        player.destroy()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        onDestroy()
        // https://github.com/androidx/media/issues/27#issuecomment-1456042326
        stopSelf()
        exitProcess(0)
      }
      AppKilledPlaybackBehavior.CONTINUE_PLAYBACK -> {
        Timber.Forest.d("Continuing playback - service remains available for Android Auto")
        // Service continues running for Android Auto with existing callbacks
      }
    }
  }

  @SuppressLint("VisibleForTests")
  private fun selfWake(clientPackageName: String): Boolean {
    val reactActivity = reactContext?.currentActivity
    if (
      // HACK: validate reactActivity is present; if not, send wake intent
      (reactActivity == null || reactActivity.isDestroyed) && Settings.canDrawOverlays(this)
    ) {
      val currentTime = System.currentTimeMillis()
      if (currentTime - lastWake < 100000) {
        return false
      }
      lastWake = currentTime
      val activityIntent = packageManager.getLaunchIntentForPackage(packageName)
      activityIntent!!.data = "trackplayer://service-bound".toUri()
      activityIntent.action = Intent.ACTION_VIEW
      activityIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      var activityOptions = ActivityOptions.makeBasic()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        activityOptions =
          activityOptions.setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
          )
      }
      this.startActivity(activityIntent, activityOptions.toBundle())
      return true
    }
    return false
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
    Timber.Forest.d("onGetSession requested by: ${controllerInfo.packageName}")

    // Ensure service is stable before providing session
    ensureServiceStability()

    return mediaSession
  }

  override fun onHeadlessJsTaskFinish(taskId: Int) {
    // This is empty so ReactNative doesn't kill this service
  }

  override fun onDestroy() {
    if (::player.isInitialized) {
      Timber.Forest.d("Releasing media session and destroying player")
      mediaSession.release()
      player.destroy()
    }

    super.onDestroy()
  }

  // Android Auto request resolution methods
  fun resolveGetItemRequest(requestId: String, mediaItem: MediaItem) {
    // Store MediaItem in lookup map for later use in onAddMediaItems/onSetMediaItems
    mediaItem.mediaId?.let { mediaId ->
      mediaItemById[mediaId] = mediaItem
      Timber.d("Stored single MediaItem: mediaId=$mediaId, title=${mediaItem.mediaMetadata.title}")
    }

    pendingGetItemRequests.remove(requestId)?.set(mediaItem)
  }

  fun resolveGetChildrenRequest(requestId: String, items: List<MediaItem>, totalChildrenCount: Int) {
    Timber.d("resolveGetChildrenRequest service method called: requestId=$requestId, itemCount=${items.size}")

    // Store MediaItems in lookup map for later use in onAddMediaItems/onSetMediaItems
    items.forEach { mediaItem ->
      mediaItem.mediaId?.let { mediaId ->
        mediaItemById[mediaId] = mediaItem
        Timber.d("Stored MediaItem: mediaId=$mediaId, title=${mediaItem.mediaMetadata.title}")
      }
    }

    val future = pendingGetChildrenRequests.remove(requestId)
    if (future != null) {
      future.set(items)
      Timber.d("Resolved future for requestId=$requestId with ${items.size} items")
    } else {
      Timber.w("No pending future found for requestId=$requestId")
    }
  }

  fun resolveSearchRequest(requestId: String, items: List<MediaItem>, totalMatchesCount: Int) {
    pendingSearchRequests.remove(requestId)?.set(items)
  }

  inner class MusicBinder : Binder() {
    val service = this@AudioBrowserService
  }

  private val rootItem = MediaItem.Builder()
  .setMediaId("/")
  .setMediaMetadata(MediaMetadata.Builder()
    .setIsBrowsable(true)
    .setIsPlayable(false)
    .build())
  .build()

  private inner class InnerMediaSessionCallback : MediaLibrarySession.Callback {
    // HACK: I'm sure most of the callbacks were not implemented correctly.
    // ATM I only care that andorid auto still functions.

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
      player.onControllerDisconnected(controller.packageName)
      super.onDisconnected(session, controller)
    }

    // Configure commands available to the controller in onConnect()
    @OptIn(UnstableApi::class)
    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
      Timber.Forest.d(controller.packageName)
      val isMediaNotificationController = session.isMediaNotificationController(controller)
      val isAutomotiveController = session.isAutomotiveController(controller)
      val isAutoCompanionController = session.isAutoCompanionController(controller)
      player.onControllerConnected(
        EventControllerConnection(
          packageName = controller.packageName,
          isMediaNotificationController = isMediaNotificationController,
          isAutomotiveController = isAutomotiveController,
          isAutoCompanionController = isAutoCompanionController,
        )
      )


      if (
        controller.packageName in
          arrayOf(
            "com.android.systemui",
            // https://github.com/googlesamples/android-media-controller
            "com.example.android.mediacontroller",
            // Android Auto
            "com.google.android.projection.gearhead",
          )
      ) {
        // HACK: attempt to wake up activity (for legacy APM). if not, start headless.
        if (!selfWake(controller.packageName)) {
          onStartCommand(null, 0, 0)
        }
      }
      return if (
        isMediaNotificationController || isAutomotiveController || isAutoCompanionController
      ) {
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
          .setCustomLayout(customLayout)
          .setAvailableSessionCommands(
            sessionCommands ?: MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
          )
          .setAvailablePlayerCommands(
            playerCommands ?: MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
          )
          .build()
      } else {
        super.onConnect(session, controller)
      }
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      command: SessionCommand,
      args: Bundle,
    ): ListenableFuture<SessionResult> {
      when (command.customAction) {
        CustomCommandButton.JUMP_BACKWARD.customAction -> {
          player.forwardingPlayer.seekBack()
        }
        CustomCommandButton.JUMP_FORWARD.customAction -> {
          player.forwardingPlayer.seekForward()
        }
        CustomCommandButton.NEXT.customAction -> {
          player.forwardingPlayer.seekToNext()
        }
        CustomCommandButton.PREVIOUS.customAction -> {
          player.forwardingPlayer.seekToPrevious()
        }
      }
      return super.onCustomCommand(session, controller, command, args)
    }

    override fun onSetRating(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      rating: Rating,
    ): ListenableFuture<SessionResult> {
      player.onRatingChanged(rating)
      return super.onSetRating(session, controller, rating)
    }

    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
      Timber.tag("RNTP").d("onGetLibraryRoot: { package: ${browser.packageName} }")
      val rootExtras = Bundle().apply {
        putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
//        putInt(
//          "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT",
//          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
//        )
//        putInt(
//          "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",
//          MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
//        )
      }
      val libraryParams = LibraryParams.Builder().setExtras(rootExtras).build()
      // https://github.com/androidx/media/issues/1731#issuecomment-2411109462
      val mRootItem = when (browser.packageName) {
        "com.google.android.googlequicksearchbox" -> {
          // TODO: make "For You" work
          // if (mediaTree[AA_FOR_YOU_KEY] == null) rootItem else forYouItem
          rootItem
        }

        else -> rootItem
      }
      return Futures.immediateFuture(
        LibraryResult.ofItem(rootItem, libraryParams)
      )
    }

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      Timber.tag("RNTP").d("onGetChildren: {parentId: $parentId, page: $page, pageSize: $pageSize }")

      val requestId = UUID.randomUUID().toString()
      val future = SettableFuture.create<List<MediaItem>>()

      // Store the future for later resolution
      pendingGetChildrenRequests[requestId] = future

      // Emit event to JavaScript via module
      try {
        Timber.d("Getting module: requestId=$requestId, parentId=$parentId")
        // Use the already-registered module, or wait briefly for it to register
        var module = registeredModule
        if (module == null) {
          Timber.d("Module not yet registered, waiting briefly...")
          // Wait a short time for the module to register (app is already running)
          Thread.sleep(100)
          module = registeredModule
        }

          Timber.d("Emitting onGetChildrenRequest to JS: requestId=$requestId, parentId=$parentId")
          module?.emitGetChildrenRequest(requestId, parentId, page, pageSize)
        Timber.d("Emitted onGetChildrenRequest to JS: requestId=$requestId, parentId=$parentId")
      } catch (e: Exception) {
        Timber.e(e, "Failed to emit onGetChildrenRequest to JS")
        // Fallback: resolve with default items
        pendingGetChildrenRequests.remove(requestId)
        throw e
      }

      return Futures.transform(future, { items ->
        LibraryResult.ofItemList(ImmutableList.copyOf(items), null)
      }, MoreExecutors.directExecutor())
    }

    override fun onGetItem(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
      Timber.tag("RNTP").d("onGetItem: ${browser.packageName}, mediaId = $mediaId")

      val requestId = UUID.randomUUID().toString()
      val future = SettableFuture.create<MediaItem>()

      // Store the future for later resolution
      pendingGetItemRequests[requestId] = future

      // Emit event to JavaScript via module
        // Use the already-registered module, or wait briefly for it to register
        var module = registeredModule
        if (module == null) {
          Timber.d("Module not yet registered, waiting briefly...")
          // Wait a short time for the module to register (app is already running)
          Thread.sleep(100)
          module = registeredModule
        }

      module?.emitGetItemRequest(requestId, mediaId)
      Timber.d("Emitted onGetItemRequest to JS: requestId=$requestId, mediaId=$mediaId")

      return Futures.transform(future, { item ->
        LibraryResult.ofItem(item, null)
      }, MoreExecutors.directExecutor())
    }

    override fun onSearch(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      query: String,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
      Timber.tag("RNTP").d("onSearch: ${browser.packageName}, query = $query")

      // Emit event to JavaScript via module for search initiation
      try {
        val requestId = UUID.randomUUID().toString()
        // Use the already-registered module instead of blocking getModule().get()
        val module = registeredModule
        if (module != null) {
          val extrasMap = params?.extras?.let { bundle ->
            Arguments.createMap().apply {
              for (key in bundle.keySet()) {
                when (val value = bundle.get(key)) {
                  is String -> putString(key, value)
                  is Int -> putInt(key, value)
                  is Double -> putDouble(key, value)
                  is Boolean -> putBoolean(key, value)
                  // Add other types as needed
                }
              }
            }
          }
          module.emitSearchResultRequest(requestId, query, extrasMap, 0, 50) // Default page parameters
          Timber.d("Emitted onGetSearchResultRequest to JS: requestId=$requestId, query=$query")
        } else {
          Timber.w("No module registered - cannot emit onGetSearchResultRequest")
        }
      } catch (e: Exception) {
        Timber.e(e, "Failed to emit onGetSearchResultRequest to JS")
      }

      // Return standard void result - search completion is handled separately
      return super.onSearch(session, browser, query, params)
    }

    override fun onAddMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> {
      Timber.tag("RNTP").d("onAddMediaItems: ${controller.packageName}, ${mediaItems[0].mediaId}, ${mediaItems.size}")
      Timber.d("Received MediaItem extras: ${mediaItems.first().mediaMetadata.extras?.keySet()}")

      // Launch coroutine and return ListenableFuture
      return CoroutineScope(Dispatchers.Main).future {
        // Resolve stub MediaItems using our lookup map
        val resolvedItems = mediaItems.mapNotNull { mediaItem ->
          val mediaId = mediaItem.mediaId
          if (mediaId != null && isStubMediaItem(mediaItem)) {
            // This is a stub MediaItem from Android Auto - look up the full one
            val fullMediaItem = mediaItemById[mediaId]
            if (fullMediaItem != null) {
              Timber.d("Resolved stub MediaItem: mediaId=$mediaId -> title=${fullMediaItem.mediaMetadata.title}")
              fullMediaItem
            } else {
              Timber.w("No stored MediaItem found for mediaId=$mediaId")
              mediaItem // Return original if no lookup found
            }
          } else {
            // Already a full MediaItem or no mediaId
            mediaItem
          }
        }

        Timber.d("Returning ${resolvedItems.size} resolved MediaItems to MediaSession")
        return@future resolvedItems.toMutableList()
      }
    }

    // Helper to detect if a MediaItem is a stub (has minimal data)
    private fun isStubMediaItem(mediaItem: MediaItem): Boolean {
      return mediaItem.localConfiguration?.uri == null &&
             mediaItem.mediaMetadata.title == null &&
             mediaItem.mediaId != null
    }

    override fun onSetMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: MutableList<MediaItem>,
      startIndex: Int,
      startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      Timber.tag("RNTP").d("onSetMediaItems: ${controller.packageName}, mediaId=${mediaItems[0].mediaId}, uri=${mediaItems[0].localConfiguration?.uri}, title=${mediaItems[0].mediaMetadata.title}")

      // Launch coroutine to resolve stub MediaItems
      return CoroutineScope(Dispatchers.Main).future {
        // Resolve stub MediaItems using our lookup map
        val resolvedItems = mediaItems.mapNotNull { mediaItem ->
          val mediaId = mediaItem.mediaId
          if (mediaId != null && isStubMediaItem(mediaItem)) {
            // This is a stub MediaItem from Android Auto - look up the full one
            val fullMediaItem = mediaItemById[mediaId]
            if (fullMediaItem != null) {
              Timber.d("Resolved stub MediaItem in onSetMediaItems: mediaId=$mediaId -> title=${fullMediaItem.mediaMetadata.title}")
              fullMediaItem
            } else {
              Timber.w("No stored MediaItem found for mediaId=$mediaId in onSetMediaItems")
              mediaItem // Return original if no lookup found
            }
          } else {
            // Already a full MediaItem or no mediaId
            mediaItem
          }
        }

        Timber.d("Returning ${resolvedItems.size} resolved MediaItems to MediaSession for onSetMediaItems")

        // Return resolved items with original start position - MediaSession will handle queue management
        MediaSession.MediaItemsWithStartPosition(
          resolvedItems.toMutableList(),
          startIndex,
          startPositionMs
        )
      }
    }
  }

  companion object {
    const val TASK_KEY = "TrackPlayer"
    const val CALLBACK_REGISTRATION_TASK = "TrackPlayerCallbackRegistration"
  }
}
