package com.audiobrowser

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
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
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

class ReactHostApplication : Application(), ReactApplication {
  override val reactHost: ReactHost = mock(ReactHost::class.java)
}

@RunWith(RobolectricTestRunner::class)
@Config(application = ReactHostApplication::class)
class HeadlessTaskServiceTest {

  private val host = (RuntimeEnvironment.getApplication() as ReactHostApplication).reactHost
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

    verify(appRegistry, never()).startHeadlessTask(1, "AudioBrowser", taskData)
    assertNull(ShadowPowerManager.getLatestWakeLock())
  }

  @Test
  fun `binding starts the task on the React context`() {
    `when`(host.currentReactContext).thenReturn(reactContext)

    controller.create().bind()
    idle()

    verify(appRegistry).startHeadlessTask(1, "AudioBrowser", taskData)
    verify(host, never()).start()
  }

  @Test
  fun `binding without a React context starts the host, and the task once the context is there`() {
    controller.create().bind()
    idle()

    val listener = ArgumentCaptor.forClass(ReactInstanceEventListener::class.java)
    verify(host).addReactInstanceEventListener(listener.capture())
    verify(host).start()
    verify(appRegistry, never()).startHeadlessTask(1, "AudioBrowser", taskData)

    listener.value.onReactContextInitialized(reactContext)
    idle()

    verify(appRegistry).startHeadlessTask(1, "AudioBrowser", taskData)
    verify(host).removeReactInstanceEventListener(listener.value)
  }
}
