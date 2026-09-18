package com.audiobrowser.model

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcyTitleTest {
  private fun block(title: String, charset: String) =
    "StreamTitle='$title';StreamUrl='';".toByteArray(Charset.forName(charset))

  @Test
  fun `decodes a legacy code page title`() {
    val raw = block("Алия Акылбекова - Жылдызым", "windows-1251")
    assertEquals("Алия Акылбекова - Жылдызым", IcyTitle.decode(raw, "windows-1251"))
  }

  @Test
  fun `decodes thai`() {
    val raw = block("ลืมไปแล้วว่าลืมยังไง", "windows-874")
    assertEquals("ลืมไปแล้วว่าลืมยังไง", IcyTitle.decode(raw, "windows-874"))
  }

  @Test
  fun `leaves valid utf-8 to the default decode`() {
    assertNull(IcyTitle.decode(block("Алия", "UTF-8"), "windows-1251"))
    assertNull(IcyTitle.decode(block("Plain Title", "UTF-8"), "windows-1251"))
  }

  @Test
  fun `ignores a charset the runtime cannot decode with`() {
    assertNull(IcyTitle.decode(block("Алия", "windows-1251"), "no-such-charset"))
  }

  @Test
  fun `needs a non-empty StreamTitle`() {
    assertNull(
      IcyTitle.decode(
        "StreamTitle='';".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xC0.toByte()),
        "windows-1251",
      )
    )
    assertNull(IcyTitle.decode(byteArrayOf(0xC0.toByte(), 0xEB.toByte()), "windows-1251"))
  }

  @Test
  fun `keeps quotes inside the title`() {
    val raw = block("Rock'n'Roll - Кино", "windows-1251")
    assertEquals("Rock'n'Roll - Кино", IcyTitle.decode(raw, "windows-1251"))
  }
}
