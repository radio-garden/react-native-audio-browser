package com.audiobrowser

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import timber.log.Timber

class AudioBrowserHeadlessTaskService : HeadlessJsTaskService() {

  /**
   * Local binder for service binding. This allows the main AudioBrowserService to bind to this
   * headless service and control its lifecycle.
   */
  inner class LocalBinder : Binder() {
    fun getService() = this@AudioBrowserHeadlessTaskService
  }

  private val binder = LocalBinder()

  override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig {
    Timber.d("TrackPlayerHeadlessTaskService creating task config")
    return HeadlessJsTaskConfig(
      TASK_KEY,
      Arguments.createMap(),
      0, // No timeout
      true, // Allow running in foreground
    )
  }

  override fun onBind(intent: Intent): IBinder {
    Timber.d("TrackPlayerHeadlessTaskService onBind - starting headless task")
    // Start the headless task when bound
    startTask(getTaskConfig(intent))
    return binder
  }

  override fun onCreate() {
    Timber.d("TrackPlayerHeadlessTaskService onCreate")
    super.onCreate()
  }

  override fun onDestroy() {
    Timber.d("TrackPlayerHeadlessTaskService onDestroy")
    super.onDestroy()
  }

  override fun onHeadlessJsTaskFinish(taskId: Int) {
    Timber.d("TrackPlayerHeadlessTaskService task finished: $taskId")
    // Empty implementation prevents service termination
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Timber.d("TrackPlayerHeadlessTaskService onStartCommand")
    return super.onStartCommand(intent, flags, startId)
  }

  companion object {
    const val TASK_KEY = "TrackPlayer"
  }
}
