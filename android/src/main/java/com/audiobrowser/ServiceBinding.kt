package com.audiobrowser

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import timber.log.Timber

/**
 * Exactly-once bookkeeping for a [ServiceConnection].
 *
 * `bindService` may be called from more than one place — an auto-bind at construction and again
 * from setup — but the binding must be released exactly once, and only if one was ever taken:
 * [Context.unbindService] throws when the connection was never registered. Kept apart from the
 * caller so the rule is one object's job, and so it can be tested without a JSI runtime.
 */
internal class ServiceBinding(private val context: Context) {

  @Volatile
  var isBound: Boolean = false
    private set

  /**
   * Binds if not already bound, and returns whether a binding is held afterwards. A second call
   * while bound is a no-op: Android would otherwise count the extra bind and expect a matching
   * unbind.
   */
  fun bind(intent: Intent, connection: ServiceConnection): Boolean {
    if (isBound) return true
    val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    if (bound) isBound = true
    return bound
  }

  /**
   * Releases the binding if one is held. Safe to call repeatedly, and safe when the service has
   * already died — [onServiceDisconnected][ServiceConnection.onServiceDisconnected] means the
   * service went away, not that the binding did, so the unbind is still required.
   */
  fun unbind(connection: ServiceConnection) {
    if (!isBound) return
    isBound = false
    // Throws if the binding is somehow already gone, which is the outcome we wanted anyway.
    runCatching { context.unbindService(connection) }
      .onFailure { Timber.w(it, "unbindService failed") }
  }
}
