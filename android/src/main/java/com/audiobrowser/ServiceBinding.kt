package com.audiobrowser

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import timber.log.Timber

/**
 * Takes a [ServiceConnection]'s binding at most once, and releases it once and only if taken:
 * [Context.unbindService] throws for a connection that was never registered.
 */
internal class ServiceBinding(private val context: Context) {

  @Volatile
  var isBound: Boolean = false
    private set

  @Volatile private var bindSucceeded: Boolean = false

  /**
   * Binds unless already bound, and returns what [Context.bindService] returned for the binding
   * held: a second call while bound repeats that answer without binding again.
   */
  fun bind(intent: Intent, connection: ServiceConnection): Boolean {
    if (isBound) return bindSucceeded
    // Bound even when bindService returns false: Android has already registered the connection,
    // and "regardless of the return value, you should later call unbindService()".
    isBound = true
    bindSucceeded = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    return bindSucceeded
  }

  /**
   * Releases the binding if one is held; a no-op otherwise. Still required after
   * [ServiceConnection.onServiceDisconnected], which means the service died, not the binding.
   */
  fun unbind(connection: ServiceConnection) {
    if (!isBound) return
    isBound = false
    // A throw means the binding is already gone, which is the outcome wanted.
    runCatching { context.unbindService(connection) }
      .onFailure { Timber.w(it, "unbindService failed") }
  }
}
