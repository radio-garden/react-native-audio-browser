package com.audiobrowser.model

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

class IcyTitleTest {
  /** What media3's ISO-8859-1 fallback yields for [text] sent as [charset]. */
  private fun latin1Shaped(text: String, charset: String) =
    String(text.toByteArray(Charset.forName(charset)), Charsets.ISO_8859_1)

  @Test
  fun `re-decodes a legacy code page title`() {
    val title = latin1Shaped("Алия Акылбекова - Жылдызым", "windows-1251")
    assertEquals("Алия Акылбекова - Жылдызым", IcyTitle.redecode(title, "windows-1251"))
  }

  @Test
  fun `re-decodes thai`() {
    val title = latin1Shaped("ลืมไปแล้วว่าลืมยังไง", "windows-874")
    assertEquals("ลืมไปแล้วว่าลืมยังไง", IcyTitle.redecode(title, "windows-874"))
  }

  @Test
  fun `re-decodes a title the station utf-8 encoded from legacy bytes`() {
    // media3 decodes the valid UTF-8 to the same Latin-1-shaped string the fallback would give.
    val doubleEncoded = latin1Shaped("Rabbit - กาลเวลา", "windows-874").toByteArray(Charsets.UTF_8)
    assertEquals(
      "Rabbit - กาลเวลา",
      IcyTitle.redecode(String(doubleEncoded, Charsets.UTF_8), "windows-874"),
    )
  }

  @Test
  fun `leaves a title already in another script`() {
    assertEquals("Алия", IcyTitle.redecode("Алия", "windows-1251"))
  }

  @Test
  fun `leaves ascii`() {
    assertEquals("Plain Title", IcyTitle.redecode("Plain Title", "windows-1251"))
  }

  @Test
  fun `ignores a charset the runtime cannot decode with`() {
    val title = latin1Shaped("Алия", "windows-1251")
    assertEquals(title, IcyTitle.redecode(title, "no-such-charset"))
  }
}
