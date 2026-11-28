package com.audiobrowser.http

/** What a response's `Cache-Control` and `Age` headers say about reusing it. */
sealed interface HttpFreshness {
  /** No valid `max-age`, and no `no-cache` or `no-store`. */
  object Unstated : HttpFreshness

  /** `no-cache`, `no-store`, or `max-age=0`: reuse only after asking again. */
  object Revalidate : HttpFreshness

  /** A positive `max-age`, and the `Age` the response already had. */
  data class MaxAge(val maxAgeMs: Long, val ageMs: Long) : HttpFreshness

  companion object {
    private const val MAX_DELTA_SECONDS = 2_147_483_648L

    /** Reads [headers], whose names match in any case. */
    fun of(headers: Map<String, String>): HttpFreshness {
      fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
      val cacheControl = header("Cache-Control") ?: return Unstated
      var maxAge: Long? = null
      for (directive in cacheControl.split(",")) {
        val parts = directive.split("=", limit = 2)
        val name = parts[0].trim().lowercase()
        val value = parts.getOrNull(1)?.trim()?.trim('"')
        when {
          // `no-cache="field"` restricts reuse of that field only, not of the response.
          name == "no-cache" && value == null || name == "no-store" -> return Revalidate
          // Of duplicates, the first valid one.
          name == "max-age" -> maxAge = maxAge ?: value?.let(::deltaSeconds)
        }
      }
      if (maxAge == null) return Unstated
      if (maxAge == 0L) return Revalidate
      // Of repeated Age lines, joined with ", ", the first.
      val age = header("Age")?.substringBefore(',')?.trim()?.let(::deltaSeconds) ?: 0
      return MaxAge(maxAge * 1000, age * 1000)
    }

    /** RFC 9111 delta-seconds: digits only, capped at 2^31. */
    private fun deltaSeconds(value: String): Long? {
      if (value.isEmpty() || !value.all { it in '0'..'9' }) return null
      return value.toBigInteger().min(MAX_DELTA_SECONDS.toBigInteger()).toLong()
    }
  }
}
