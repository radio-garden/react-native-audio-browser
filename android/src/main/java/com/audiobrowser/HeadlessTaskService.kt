package com.audiobrowser

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext
import timber.log.Timber

/** Runs the JS headless task while [Service] holds its binding; binding starts the task. */
class HeadlessTaskService : HeadlessJsTaskService() {

  inner class LocalBinder : Binder() {
    fun getService() = this@HeadlessTaskService
  }

  private val binder = LocalBinder()

  override fun onBind(intent: Intent): IBinder {
    Timber.d("starting headless task")
    startTaskWithoutWakeLock()
    return binder
  }

  /**
   * Starts the task as [startTask] does, starting the React host when it has no context, but
   * without the partial wake lock that [startTask] acquires and only [onDestroy] releases.
   */
  private fun startTaskWithoutWakeLock() {
    UiThreadUtil.assertOnUiThread()
    val host = checkNotNull(reactHost) { "ReactHost is not initialized" }
    val context = host.currentReactContext
    if (context != null) {
      startTaskOn(context)
      return
    }
    host.addReactInstanceEventListener(
      object : ReactInstanceEventListener {
        override fun onReactContextInitialized(context: ReactContext) {
          startTaskOn(context)
          host.removeReactInstanceEventListener(this)
        }
      }
    )
    host.start()
  }

  private fun startTaskOn(context: ReactContext) {
    val taskContext = HeadlessJsTaskContext.getInstance(context)
    taskContext.addTaskEventListener(this)
    UiThreadUtil.runOnUiThread {
      taskContext.startTask(
        HeadlessJsTaskConfig(
          "AudioBrowser",
          Arguments.createMap(),
          0, // No timeout
          true, // Allowed while the app is in the foreground
        )
      )
    }
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
