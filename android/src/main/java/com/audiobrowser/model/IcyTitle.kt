package com.audiobrowser.model

import java.nio.charset.Charset

/** Re-decodes an ICY `StreamTitle` with the charset the track declares. */
internal object IcyTitle {
  /**
   * [title] re-decoded through ISO-8859-1 with [charset] when it is Latin-1 shaped: every char at
   * or below U+00FF, at least one at or above U+0080. That shape is what media3's ISO-8859-1
   * fallback yields for bytes that are not UTF-8, and also what a station that UTF-8-encoded its
   * legacy bytes yields; ISO-8859-1 is lossless, so both re-decode. Returns [title] as is when it
   * has another shape, is pure ASCII, or [charset] is not one the runtime can decode with.
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
