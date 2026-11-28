package com.audiobrowser.http

/** A fetched value and the positive lifetime in ms its response states, if any. */
data class Fetched<V>(val value: V, val lifetimeMs: Long? = null) {
  fun <T> map(transform: (V) -> T) = Fetched(transform(value), lifetimeMs)
}
