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

@RunWith(RobolectricTestRunner::class)
class ServiceBindingTest {

  private val app = RuntimeEnvironment.getApplication()
  private val intent = Intent(app, Service::class.java)
  private val unbindableIntent = Intent("unbindable").setPackage(app.packageName)

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

      override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

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

  @Test
  fun unbindWithoutBindIsANoOp() {
    val binding = ServiceBinding(app)

    binding.unbind(connection)

    assertFalse(binding.isBound)
    assertTrue(shadowOf(app).unboundServiceConnections.isEmpty())
  }

  @Test
  fun failedBindStillHoldsAConnectionToRelease() {
    val binding = ServiceBinding(app)
    shadowOf(app).declareActionUnbindable("unbindable")

    assertFalse(binding.bind(unbindableIntent, connection))

    assertTrue(binding.isBound)

    binding.unbind(connection)

    assertEquals(1, shadowOf(app).unboundServiceConnections.size)
  }

  @Test
  fun bindingAgainAfterAFailedBindReportsTheFailure() {
    val binding = ServiceBinding(app)
    shadowOf(app).declareActionUnbindable("unbindable")

    assertFalse(binding.bind(unbindableIntent, connection))
    assertFalse(binding.bind(unbindableIntent, connection))
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
