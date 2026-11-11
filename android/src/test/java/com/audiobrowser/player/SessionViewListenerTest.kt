package com.audiobrowser.player

import androidx.media3.common.Player as MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionViewListenerTest {
  @Test
  fun `forwards every callback media3's listener declares`() {
    val declared =
      MediaPlayer.Listener::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }
    val overridden =
      SessionViewListener::class
        .java
        .declaredMethods
        .map { it.name to it.parameterTypes.toList() }
        .toSet()

    assertEquals(emptyList<Any>(), declared.filterNot { it in overridden })
  }
}
