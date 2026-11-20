package androidx.media3.session

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [setPeriodicPositionUpdatesPaused] takes from media3's internals: a media3 upgrade that
 * fails here must be checked against MediaSessionImpl's periodic position updates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PeriodicPositionUpdatesTest {

  @Test
  fun `media3 still takes a session position update delay`() {
    val method =
      MediaSession::class
        .java
        .getDeclaredMethod("setSessionPositionUpdateDelayMs", Long::class.javaPrimitiveType)

    assertEquals(Void.TYPE, method.returnType)
    assertFalse(Modifier.isStatic(method.modifiers))
  }

  @Test
  fun `a resume restores media3's default delay`() {
    val default =
      MediaSessionImpl::class
        .java
        .getDeclaredField("DEFAULT_SESSION_POSITION_UPDATE_DELAY_MS")
        .apply { isAccessible = true }
        .getLong(null)

    assertEquals(default, PERIODIC_POSITION_UPDATE_DELAY_MS)
  }
}
