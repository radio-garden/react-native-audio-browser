package com.audiobrowser

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Robolectric: bindService/unbindService only exist under the framework, and ShadowApplication
// records both sides — which is precisely the leak being guarded against.
@RunWith(RobolectricTestRunner::class)
class ServiceBindingTest {

  private val app = RuntimeEnvironment.getApplication()
  private val intent = Intent(app, Service::class.java)

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

      override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

  /** Both the auto-bind at construction and setup's bind reach the same object. */
  @Test
  fun bindingTwiceTakesOnlyOneBinding() {
    val binding = ServiceBinding(app)

    assertTrue(binding.bind(intent, connection))
    assertTrue(binding.bind(intent, connection))

    assertEquals(1, shadowOf(app).boundServiceConnections.size)

    binding.unbind(connection)

    assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    assertEquals(1, shadowOf(app).unboundServiceConnections.size)
  }

  /** dispose() must not throw, and Android throws when unbinding a connection it never had. */
  @Test
  fun unbindWithoutBindIsANoOp() {
    val binding = ServiceBinding(app)

    binding.unbind(connection)

    assertFalse(binding.isBound)
    assertTrue(shadowOf(app).unboundServiceConnections.isEmpty())
  }

  /**
   * Android registers the connection before it asks the ActivityManager, so a false return still
   * leaves a binding to release. Treating false as "nothing bound" strands it permanently — the
   * leak this class exists to prevent.
   */
  @Test
  fun failedBindStillHoldsAConnectionToRelease() {
    val binding = ServiceBinding(app)
    shadowOf(app).declareActionUnbindable("unbindable")

    assertFalse(binding.bind(Intent("unbindable"), connection))

    assertTrue(binding.isBound)

    binding.unbind(connection)

    assertEquals(1, shadowOf(app).unboundServiceConnections.size)
  }

  @Test
  fun unbindIsIdempotent() {
    val binding = ServiceBinding(app)
    binding.bind(intent, connection)

    binding.unbind(connection)
    binding.unbind(connection)

    assertEquals(1, shadowOf(app).unboundServiceConnections.size)
  }
}
