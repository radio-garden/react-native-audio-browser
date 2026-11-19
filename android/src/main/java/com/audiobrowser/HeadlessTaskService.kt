package com.audiobrowser

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.facebook.react.ReactApplication
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.internal.featureflags.ReactNativeNewArchitectureFeatureFlags
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext
import com.facebook.react.jstasks.HeadlessJsTaskEventListener
import timber.log.Timber

/**
 * Runs the JS headless task while [Service] holds its binding; binding starts the task. Unlike
 * React Native's `HeadlessJsTaskService`, it takes no wake lock.
 */
class HeadlessTaskService : Service(), HeadlessJsTaskEventListener {

  inner class LocalBinder : Binder() {
    fun getService() = this@HeadlessTaskService
  }

  private val binder = LocalBinder()
  private var taskContext: HeadlessJsTaskContext? = null

  override fun onBind(intent: Intent): IBinder {
    Timber.d("starting headless task")
    startTask()
    return binder
  }

  /** Starts the task on the app's React context, starting React first when it has none. */
  @Suppress("DEPRECATION")
  private fun startTask() {
    UiThreadUtil.assertOnUiThread()
    val app = application as ReactApplication
    if (ReactNativeNewArchitectureFeatureFlags.enableBridgelessArchitecture()) {
      val host = checkNotNull(app.reactHost) { "ReactHost is not initialized" }
      host.currentReactContext?.let {
        startTaskOn(it)
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
    } else {
      val manager = app.reactNativeHost.reactInstanceManager
      manager.currentReactContext?.let {
        startTaskOn(it)
        return
      }
      manager.addReactInstanceEventListener(
        object : ReactInstanceEventListener {
          override fun onReactContextInitialized(context: ReactContext) {
            startTaskOn(context)
            manager.removeReactInstanceEventListener(this)
          }
        }
      )
      manager.createReactContextInBackground()
    }
  }

  private fun startTaskOn(context: ReactContext) {
    val taskContext = HeadlessJsTaskContext.getInstance(context).also { this.taskContext = it }
    taskContext.addTaskEventListener(this)
    UiThreadUtil.runOnUiThread {
      taskContext.startTask(
        HeadlessJsTaskConfig(
          TASK_KEY,
          Arguments.createMap(),
          0, // No timeout
          true, // Allowed while the app is in the foreground
        )
      )
    }
  }

  override fun onDestroy() {
    Timber.d("onDestroy")
    taskContext?.removeTaskEventListener(this)
    super.onDestroy()
  }

  override fun onHeadlessJsTaskStart(taskId: Int) {}

  override fun onHeadlessJsTaskFinish(taskId: Int) {
    Timber.d("task finished: $taskId")
  }

  companion object {
    const val TASK_KEY = "AudioBrowser"
  }
}
