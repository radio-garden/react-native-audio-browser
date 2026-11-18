package com.audiobrowser.model

import java.nio.charset.Charset

internal object IcyTitle {
  /**
   * [title] re-decoded with [charset] when every char is at or below U+00FF and one is at or above
   * U+0080: media3 yields that shape when it falls back to ISO-8859-1, which is lossless. Returns
   * [title] unchanged otherwise, or when the runtime has no [charset].
   */
  fun redecode(title: String, charset: String): String {
    var high = false
    for (c in title) {
      if (c.code > 0xFF) return title
      if (c.code >= 0x80) high = true
    }
    if (!high) return title
    val cs = runCatching { Charset.forName(charset) }.getOrNull() ?: return title
    return String(title.toByteArray(Charsets.ISO_8859_1), cs)
  }
}
