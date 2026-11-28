package com.audiobrowser.http

/**
 * A fetched value and how long it may be kept: its response's positive `max-age` in ms, if any, of
 * which [ageMs] had already passed when it arrived.
 */
data class Fetched<V>(val value: V, val lifetimeMs: Long? = null, val ageMs: Long = 0) {
  /** Kept for [freshness]'s `max-age`; any other freshness keeps the value with no lifetime. */
  constructor(
    value: V,
    freshness: HttpFreshness,
  ) : this(
    value,
    (freshness as? HttpFreshness.MaxAge)?.maxAgeMs,
    (freshness as? HttpFreshness.MaxAge)?.ageMs ?: 0,
  )

  fun <T> map(transform: (V) -> T) = Fetched(transform(value), lifetimeMs, ageMs)
}
