package com.audiobrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Brings Robolectric's network up, with internet, for every connectivity monitor already
 * registered. Robolectric starts offline, where the retry loop waits for the network instead of
 * failing.
 */
fun goOnline(context: Context) {
  val connectivity =
    shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
  val wifi = ShadowNetwork.newInstance(103)
  val capabilities =
    ShadowNetworkCapabilities.newInstance().also {
      shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
  connectivity.setNetworkCapabilities(wifi, capabilities)
  connectivity.networkCallbacks.forEach { it.onAvailable(wifi) }
}

/**
 * Ends the first connection unanswered, then holds each reconnect open without an answer until
 * closed.
 */
class ReconnectHangingServer : Closeable {
  private val server = ServerSocket(0)
  val connections = AtomicInteger()
  val url = "http://127.0.0.1:${server.localPort}/live"

  init {
    thread(isDaemon = true) {
      val held = mutableListOf<Socket>()
      while (!server.isClosed) {
        try {
          val socket = server.accept()
          if (connections.incrementAndGet() == 1) socket.close() else held += socket
        } catch (_: IOException) {
          held.forEach { it.close() }
          return@thread
        }
      }
    }
  }

  override fun close() = server.close()
}
