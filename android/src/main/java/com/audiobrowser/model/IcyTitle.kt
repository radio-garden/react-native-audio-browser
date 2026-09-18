package com.audiobrowser.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Decodes an ICY metadata block's `StreamTitle` with a charset the track declares. */
internal object IcyTitle {
  // The same element grammar media3's IcyDecoder parses; a title may contain quotes.
  private val STREAM_TITLE = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL)

  /**
   * The `StreamTitle` in [raw] decoded with [charset]. Returns null when the bytes are valid UTF-8
   * (media3's own decode stands), when [charset] is not one the runtime can decode with, or when
   * the block carries no non-empty `StreamTitle`.
   */
  fun decode(raw: ByteArray, charset: String): String? {
    if (isUtf8(raw)) return null
    val cs = runCatching { Charset.forName(charset) }.getOrNull() ?: return null
    return STREAM_TITLE.find(String(raw, cs))?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
  }

  private fun isUtf8(bytes: ByteArray): Boolean =
    try {
      StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
      true
    } catch (_: CharacterCodingException) {
      false
    }
}
