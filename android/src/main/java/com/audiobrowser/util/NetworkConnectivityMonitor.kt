package com.audiobrowser.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/** Whether the default network has INTERNET and VALIDATED. Monitoring starts on construction. */
class NetworkConnectivityMonitor(private val context: Context) {
  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private var destroyed = false

  @Volatile private var defaultNetwork: Network? = connectivityManager.activeNetwork

  private val _isOnline = MutableStateFlow(isOnline(defaultNetwork?.let(::capabilitiesOf)))
  val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

  fun getOnline(): Boolean = _isOnline.value

  /** Calls [onStateChanged] with the current state and on each change, on [scope]'s dispatcher. */
  fun observeOnline(scope: CoroutineScope, onStateChanged: (Boolean) -> Unit) {
    isOnline.onEach { isOnline -> onStateChanged(isOnline) }.launchIn(scope)
  }

  private fun capabilitiesOf(network: Network): NetworkCapabilities? =
    connectivityManager.getNetworkCapabilities(network)

  // INTERNET alone is also true behind a captive portal; VALIDATED means the system reached the
  // internet through it.
  private fun isOnline(capabilities: NetworkCapabilities?): Boolean =
    capabilities != null &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        Timber.d("Default network available: $network")
        defaultNetwork = network
        _isOnline.value = isOnline(capabilitiesOf(network))
      }

      override fun onLost(network: Network) {
        Timber.d("Network lost: $network")
        if (network != defaultNetwork) return
        defaultNetwork = null
        _isOnline.value = false
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (network != defaultNetwork) return
        val online = isOnline(capabilities)
        Timber.d("Default network $network capabilities changed: online=$online")
        _isOnline.value = online
      }
    }

  init {
    try {
      connectivityManager.registerDefaultNetworkCallback(networkCallback)
      Timber.d(
        "Network monitoring started, initial state: ${if (_isOnline.value) "online" else "offline"}"
      )
    } catch (e: Exception) {
      Timber.e(e, "Failed to start network monitoring")
    }
  }

  /** Stops monitoring. A failed unregister leaves it running, so a later call retries. */
  fun destroy() {
    if (destroyed) {
      return
    }

    try {
      connectivityManager.unregisterNetworkCallback(networkCallback)
      destroyed = true
      Timber.d("Network monitoring stopped")
    } catch (e: Exception) {
      Timber.e(e, "Failed to stop network monitoring")
    }
  }
}
