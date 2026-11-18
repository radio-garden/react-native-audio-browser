package com.audiobrowser

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import timber.log.Timber

/** Runs the JS headless task while [Service] holds its binding; binding starts the task. */
class HeadlessTaskService : HeadlessJsTaskService() {

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
      true, // Allowed while the app is in the foreground
    )
  }

  override fun onBind(intent: Intent): IBinder {
    Timber.d("starting headless task")
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
    // Skips super, which stops the service once no task runs; it lives while Service binds it.
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Timber.d("onStartCommand")
    return super.onStartCommand(intent, flags, startId)
  }
}
