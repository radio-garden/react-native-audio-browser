package com.audiobrowser.sonos

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Response
import org.w3c.dom.Document

/**
 * Parses XML from a device on the network, or null if it isn't XML. A document type declaration,
 * which a speaker never sends, is refused, so no entity it declares can expand.
 */
internal fun parseSpeakerXml(xml: String): Document? {
  if (xml.contains("<!DOCTYPE", ignoreCase = true)) return null
  return runCatching {
      DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }
    .getOrNull()
}

/** The response's body, cut at [MAX_SPEAKER_BODY_BYTES]: a longer one fails to parse. */
internal fun Response.speakerBody(): String = peekBody(MAX_SPEAKER_BODY_BYTES).string()

internal const val MAX_SPEAKER_BODY_BYTES = 1L shl 20
