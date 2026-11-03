package com.audiobrowser.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class NetworkConnectivityMonitorTest {
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var shadow: ShadowConnectivityManager
  private lateinit var monitor: NetworkConnectivityMonitor

  private val mobile = ShadowNetwork.newInstance(102)
  private val wifi = ShadowNetwork.newInstance(103)

  private val callback: ConnectivityManager.NetworkCallback
    get() = shadow.networkCallbacks.single()

  @Before
  fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    shadow = shadowOf(connectivityManager)
    shadow.setNetworkCapabilities(mobile, validated())
    shadow.setNetworkCapabilities(wifi, validated())
    monitor = NetworkConnectivityMonitor(context)
  }

  @After fun tearDown() = monitor.destroy()

  private fun validated() =
    ShadowNetworkCapabilities.newInstance().also {
      shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

  private fun unvalidated() =
    ShadowNetworkCapabilities.newInstance().also {
      shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

  private fun becomesDefault(network: Network) {
    callback.onAvailable(network)
    callback.onCapabilitiesChanged(network, connectivityManager.getNetworkCapabilities(network)!!)
  }

  @Test
  fun `stays online when a network it moved away from is lost`() {
    becomesDefault(mobile)
    becomesDefault(wifi)
    callback.onLost(mobile)
    assertTrue(monitor.getOnline())
  }

  @Test
  fun `goes offline when the default network is lost`() {
    becomesDefault(wifi)
    callback.onLost(wifi)
    assertFalse(monitor.getOnline())
  }

  @Test
  fun `is offline while the default network is not validated`() {
    shadow.setNetworkCapabilities(wifi, unvalidated())
    becomesDefault(wifi)
    assertFalse(monitor.getOnline())
    callback.onCapabilitiesChanged(wifi, validated())
    assertTrue(monitor.getOnline())
  }

  @Test
  fun `takes the capabilities of a new default network on its arrival`() {
    shadow.setNetworkCapabilities(wifi, unvalidated())
    becomesDefault(mobile)
    callback.onAvailable(wifi)
    assertFalse(monitor.getOnline())
  }

  @Test
  fun `ignores capability changes of a network that is not the default`() {
    becomesDefault(wifi)
    callback.onCapabilitiesChanged(mobile, unvalidated())
    assertTrue(monitor.getOnline())
  }
}
