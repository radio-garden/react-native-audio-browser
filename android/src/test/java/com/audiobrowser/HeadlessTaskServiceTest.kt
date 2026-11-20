package com.audiobrowser

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.audiobrowser.HeadlessTaskService.Companion.TASK_KEY
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.ReactInstanceManager
import com.facebook.react.ReactNativeHost
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.LifecycleState
import com.facebook.react.internal.featureflags.ReactNativeNewArchitectureFeatureFlags
import com.facebook.react.modules.appregistry.AppRegistry
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@Suppress("DEPRECATION")
internal class ReactHostApplication : Application(), ReactApplication {
  override val reactHost: ReactHost = mock(ReactHost::class.java)
  val reactInstanceManager: ReactInstanceManager = mock(ReactInstanceManager::class.java)
  override val reactNativeHost: ReactNativeHost =
    mock(ReactNativeHost::class.java).also {
      `when`(it.reactInstanceManager).thenReturn(reactInstanceManager)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ReactHostApplication::class)
class HeadlessTaskServiceTest {

  private val app = RuntimeEnvironment.getApplication() as ReactHostApplication
  private val host = app.reactHost
  private val manager = app.reactInstanceManager
  private val appRegistry = mock(AppRegistry::class.java)
  private val reactContext =
    mock(ReactContext::class.java).also {
      `when`(it.hasActiveReactInstance()).thenReturn(true)
      `when`(it.lifecycleState).thenReturn(LifecycleState.BEFORE_CREATE)
      `when`(it.getJSModule(AppRegistry::class.java)).thenReturn(appRegistry)
    }
  private val taskData = JavaOnlyMap()

  private val controller = Robolectric.buildService(HeadlessTaskService::class.java, Intent())

  // Both reach into JNI, which a JVM test cannot load.
  private lateinit var arguments: MockedStatic<Arguments>
  private lateinit var architectureFlags: MockedStatic<ReactNativeNewArchitectureFeatureFlags>

  @Before
  fun stubJni() {
    arguments = mockStatic(Arguments::class.java)
    arguments.`when`<Any> { Arguments.createMap() }.thenReturn(taskData)
    architectureFlags = mockStatic(ReactNativeNewArchitectureFeatureFlags::class.java)
    architectureFlags
      .`when`<Boolean> { ReactNativeNewArchitectureFeatureFlags.enableBridgelessArchitecture() }
      .thenReturn(true)
  }

  @After
  fun destroy() {
    controller.destroy()
    architectureFlags.close()
    arguments.close()
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  @Test
  fun `binding with a React context takes no wake lock`() {
    `when`(host.currentReactContext).thenReturn(reactContext)

    controller.create().bind()
    idle()

    assertNull(ShadowPowerManager.getLatestWakeLock())
  }

  @Test
  fun `binding without a React context takes no wake lock`() {
    controller.create().bind()
    idle()

    assertNull(ShadowPowerManager.getLatestWakeLock())
  }

  @Test
  fun `a start command starts no task and takes no wake lock`() {
    `when`(host.currentReactContext).thenReturn(reactContext)

    controller.create().startCommand(0, 1)
    idle()

    verify(appRegistry, never()).startHeadlessTask(1, TASK_KEY, taskData)
    assertNull(ShadowPowerManager.getLatestWakeLock())
  }

  @Test
  fun `binding starts the task on the React context`() {
    `when`(host.currentReactContext).thenReturn(reactContext)

    controller.create().bind()
    idle()

    verify(appRegistry).startHeadlessTask(1, TASK_KEY, taskData)
    verify(host, never()).start()
  }

  @Test
  fun `binding without a React context starts the host, and the task once the context is there`() {
    controller.create().bind()
    idle()

    val listener = addedListener()
    verify(host).start()
    verify(appRegistry, never()).startHeadlessTask(1, TASK_KEY, taskData)

    listener.onReactContextInitialized(reactContext)
    idle()

    verify(appRegistry).startHeadlessTask(1, TASK_KEY, taskData)
    verify(host).removeReactInstanceEventListener(listener)
  }

  @Test
  fun `with the bridge, binding starts the task on its React context`() {
    bridge()
    `when`(manager.currentReactContext).thenReturn(reactContext)

    controller.create().bind()
    idle()

    verify(appRegistry).startHeadlessTask(1, TASK_KEY, taskData)
    verify(manager, never()).createReactContextInBackground()
    verify(host, never()).start()
    assertNull(ShadowPowerManager.getLatestWakeLock())
  }

  @Test
  fun `with the bridge, binding without a React context creates one, and starts the task on it`() {
    bridge()

    controller.create().bind()
    idle()

    val listener = ArgumentCaptor.forClass(ReactInstanceEventListener::class.java)
    verify(manager).addReactInstanceEventListener(listener.capture())
    verify(manager).createReactContextInBackground()
    verify(host, never()).start()

    listener.value.onReactContextInitialized(reactContext)
    idle()

    verify(appRegistry).startHeadlessTask(1, TASK_KEY, taskData)
    verify(manager).removeReactInstanceEventListener(listener.value)
    assertNull(ShadowPowerManager.getLatestWakeLock())
  }

  // ArgumentCaptor.capture() passes null, which ReactHost's Kotlin parameter refuses.
  private fun addedListener(): ReactInstanceEventListener =
    mockingDetails(host)
      .invocations
      .single { it.method.name == "addReactInstanceEventListener" }
      .getArgument(0)

  private fun bridge() {
    architectureFlags
      .`when`<Boolean> { ReactNativeNewArchitectureFeatureFlags.enableBridgelessArchitecture() }
      .thenReturn(false)
  }
}
