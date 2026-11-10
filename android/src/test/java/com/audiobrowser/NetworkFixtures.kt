package com.audiobrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Brings Robolectric's network up, validated, for every connectivity monitor already registered.
 * Robolectric starts offline, where the retry loop waits for the network instead of failing.
 */
fun goOnline(context: Context) {
  val connectivity =
    shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
  val wifi = ShadowNetwork.newInstance(103)
  val capabilities =
    ShadowNetworkCapabilities.newInstance().also {
      shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
  connectivity.setNetworkCapabilities(wifi, capabilities)
  connectivity.networkCallbacks.forEach { it.onAvailable(wifi) }
}
