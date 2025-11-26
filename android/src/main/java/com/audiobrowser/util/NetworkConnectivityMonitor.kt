package com.audiobrowser.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Monitors network connectivity state and notifies listeners when the connection state changes.
 *
 * This class uses Android's ConnectivityManager.NetworkCallback to detect when the device goes
 * online or offline, which is useful for updating UI in Android Auto and other contexts.
 *
 * Monitoring starts automatically on construction.
 */
class NetworkConnectivityMonitor(private val context: Context) {
  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private var destroyed = false

  // Compute initial state before creating StateFlow to avoid race condition
  private val _isOnline = MutableStateFlow(checkInitialNetworkState())
  val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

  /**
   * Gets the current network connectivity state.
   *
   * @return true if device is online, false otherwise
   */
  fun getOnline(): Boolean = _isOnline.value

  /**
   * Observes network state changes and invokes the callback when state changes. The callback is
   * invoked on the scope's dispatcher.
   */
  fun observeOnline(scope: CoroutineScope, onStateChanged: (Boolean) -> Unit) {
    isOnline.onEach { isOnline -> onStateChanged(isOnline) }.launchIn(scope)
  }

  private fun checkInitialNetworkState(): Boolean {
    return connectivityManager.activeNetwork
      ?.let { connectivityManager.getNetworkCapabilities(it) }
      ?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      } ?: false
  }

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        Timber.d("Network available: $network")
        updateNetworkState(true)
      }

      override fun onLost(network: Network) {
        Timber.d("Network lost: $network")
        updateNetworkState(false)
      }

      override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
      ) {
        val hasInternet =
          networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated =
          networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Timber.d("Network capabilities changed: hasInternet=$hasInternet, isValidated=$isValidated")
        updateNetworkState(hasInternet && isValidated)
      }
    }

  private fun updateNetworkState(isOnline: Boolean) {
    if (_isOnline.value != isOnline) {
      _isOnline.value = isOnline
    }
  }

  init {
    try {
      val networkRequest =
        NetworkRequest.Builder()
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
          .build()

      connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

      Timber.d(
        "Network monitoring started, initial state: ${if (_isOnline.value) "online" else "offline"}"
      )
    } catch (e: Exception) {
      Timber.e(e, "Failed to start network monitoring")
    }
  }

  /** Stops monitoring and cleans up resources. */
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
