package com.audiobrowser

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import timber.log.Timber

class HeadlessTaskService : HeadlessJsTaskService() {

  /**
   * Local binder for service binding. This allows the main AudioBrowserService to bind to this
   * headless service and control its lifecycle.
   */
  inner class LocalBinder : Binder() {
    fun getService() = this@HeadlessTaskService
  }

  private val binder = LocalBinder()

  override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig {
    Timber.d("creating task config")
    return HeadlessJsTaskConfig(
      "AudioBrowser",
      Arguments.createMap(),
      0, // No timeout
      true, // Allow running in foreground
    )
  }

  override fun onBind(intent: Intent): IBinder {
    Timber.d("starting headless task")
    // Start the headless task when bound
    startTask(getTaskConfig(intent))
    return binder
  }

  override fun onCreate() {
    Timber.d("onCreate")
    super.onCreate()
  }

  override fun onDestroy() {
    Timber.d("onDestroy")
    super.onDestroy()
  }

  override fun onHeadlessJsTaskFinish(taskId: Int) {
    Timber.d("task finished: $taskId")
    // Empty implementation prevents service termination
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Timber.d("onStartCommand")
    return super.onStartCommand(intent, flags, startId)
  }

}
