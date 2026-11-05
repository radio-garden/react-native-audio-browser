package com.audiobrowser.model

import java.nio.charset.Charset

internal object IcyTitle {
  /**
   * [title] re-decoded with [charset] when it is Latin-1 shaped: every char at or below U+00FF and
   * at least one at or above U+0080. media3 yields that shape both when it falls back to ISO-8859-1
   * for bytes that are not UTF-8 and when a station UTF-8-encoded its legacy bytes; ISO-8859-1 is
   * lossless, so both re-decode. Returns [title] unchanged when it has another shape, is pure
   * ASCII, or the runtime has no [charset].
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
