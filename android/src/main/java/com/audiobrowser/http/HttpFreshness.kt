package com.audiobrowser.http

/** How long an HTTP response may be reused, from its `Cache-Control` and `Age` headers. */
object HttpFreshness {
  private const val MAX_DELTA_SECONDS = 2_147_483_648L

  /**
   * `max-age` minus `Age`, in ms, or 0 for `no-cache` or `no-store`; null when [headers] state
   * neither. Header names match in any case.
   */
  fun lifetimeMs(headers: Map<String, String>): Long? {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    val cacheControl = header("Cache-Control") ?: return null
    var maxAge: Long? = null
    for (directive in cacheControl.split(",")) {
      val parts = directive.split("=", limit = 2)
      val name = parts[0].trim().lowercase()
      val value = parts.getOrNull(1)?.trim()?.trim('"')
      when {
        // `no-cache="field"` restricts reuse of that field only, not of the response.
        name == "no-cache" && value == null || name == "no-store" -> return 0
        // Of duplicates, the first valid one (RFC 9111 §4.2.1 allows the first, or stale).
        name == "max-age" -> maxAge = maxAge ?: value?.let(::deltaSeconds)
      }
    }
    val age = header("Age")?.let(::deltaSeconds) ?: 0
    return maxAge?.let { maxOf(0, it - age) * 1000 }
  }

  /**
   * The lifetime [headers] state when it is positive: what a browse page is kept for. Null for
   * none, 0, `no-cache` or `no-store`, which keep a page with no expiry.
   */
  fun positiveLifetimeMs(headers: Map<String, String>): Long? =
    lifetimeMs(headers)?.takeIf { it > 0 }

  /** RFC 9111 delta-seconds: digits only, capped at 2^31. */
  private fun deltaSeconds(value: String): Long? {
    if (value.isEmpty() || !value.all { it in '0'..'9' }) return null
    return value.toBigInteger().min(MAX_DELTA_SECONDS.toBigInteger()).toLong()
  }
}
