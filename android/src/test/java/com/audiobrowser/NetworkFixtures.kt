package com.audiobrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.ServerSocket
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
 * A server socket on a free port of 127.0.0.1, which the tests' URLs name. Bound to every address
 * instead, it lets another process on macOS bind 127.0.0.1 on the same port and take the test's
 * connections; Linux refuses that bind unless both sockets set SO_REUSEPORT.
 */
fun loopbackServerSocket() = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

/** A URL on a port of 127.0.0.1 this left free, so its connections are refused. */
fun refusedUrl() = "https://127.0.0.1:${loopbackServerSocket().use { it.localPort }}/stream"
