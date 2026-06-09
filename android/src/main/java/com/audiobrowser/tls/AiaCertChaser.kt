package com.audiobrowser.tls

import java.security.cert.X509Certificate

/**
 * Fills in missing intermediate CA certificates by following the Authority Information Access (AIA)
 * "CA Issuers" pointer in a certificate, the way Apple's Secure Transport does automatically but
 * Android's default trust manager does not.
 *
 * Many servers (a common misconfiguration) present only their leaf certificate and omit the
 * intermediate(s) needed to chain up to a trusted root. Browsers and iOS fetch the missing
 * intermediate from the URL in the leaf's AIA extension; Android's `X509TrustManager` validates
 * only what the server sent, so such streams fail with "Trust anchor for certification path not
 * found".
 */
object AiaCertChaser {
  /** Authority Information Access extension. */
  private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

  /** DER-encoded OID value of id-ad-caIssuers (1.3.6.1.5.5.7.48.2). */
  private val CA_ISSUERS_OID = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

  private const val TAG_SEQUENCE = 0x30
  private const val TAG_OID = 0x06
  private const val TAG_OCTET_STRING = 0x04

  /**
   * GeneralName.uniformResourceIdentifier = [6] IMPLICIT IA5String (context-specific, primitive).
   */
  private const val TAG_URI = 0x86

  /**
   * Returns the "CA Issuers" URLs from the certificate's AIA extension (the locations from which
   * the issuing CA certificate can be fetched), or an empty list if the extension is absent or
   * unparseable.
   */
  fun extractCaIssuerUrls(cert: X509Certificate): List<String> {
    val raw = cert.getExtensionValue(AIA_OID) ?: return emptyList()
    return try {
      // getExtensionValue() returns the extension value wrapped in an OCTET STRING.
      val wrapper = DerReader(raw).read()
      if (wrapper.tag != TAG_OCTET_STRING) return emptyList()

      // AuthorityInfoAccessSyntax ::= SEQUENCE OF AccessDescription
      val aiaSeq = DerReader(wrapper.content()).read()
      if (aiaSeq.tag != TAG_SEQUENCE) return emptyList()

      val urls = mutableListOf<String>()
      val descriptions = DerReader(aiaSeq.content())
      while (descriptions.hasNext()) {
        // AccessDescription ::= SEQUENCE { accessMethod OID, accessLocation GeneralName }
        val desc = descriptions.read()
        if (desc.tag != TAG_SEQUENCE) continue
        val fields = DerReader(desc.content())
        val method = fields.read()
        if (method.tag != TAG_OID || !method.content().contentEquals(CA_ISSUERS_OID)) continue
        if (!fields.hasNext()) continue
        val location = fields.read()
        if (location.tag == TAG_URI) {
          urls.add(String(location.content(), Charsets.US_ASCII))
        }
      }
      urls
    } catch (e: Exception) {
      // Malformed DER: treat as "no AIA" rather than crashing the handshake.
      emptyList()
    }
  }

  /**
   * Walks up from the presented chain, fetching each missing issuer via its predecessor's AIA "CA
   * Issuers" pointer, until it reaches a self-signed certificate, an issuer that cannot be fetched,
   * or [maxIntermediates] additions. Returns the (possibly extended) chain.
   *
   * Only ever *adds* certificates — the caller is still responsible for validating the completed
   * chain against the system trust anchors, so this can never cause an untrusted chain to be
   * accepted. A fetched certificate is appended only if it is genuinely the issuer of the preceding
   * one (defends against an AIA pointer to an unrelated certificate).
   *
   * @param fetch resolves a CA-Issuers URL to a certificate, or null if it cannot be retrieved.
   */
  fun completeChain(
    presented: List<X509Certificate>,
    maxIntermediates: Int = 5,
    fetch: (url: String) -> X509Certificate?,
  ): List<X509Certificate> {
    if (presented.isEmpty()) return presented
    val chain = presented.toMutableList()
    val seen = HashSet(chain)
    var added = 0
    while (added < maxIntermediates) {
      val last = chain.last()
      if (last.subjectX500Principal == last.issuerX500Principal) break // self-signed root
      val issuer =
        extractCaIssuerUrls(last)
          .asSequence()
          .mapNotNull { url -> runCatching { fetch(url) }.getOrNull() }
          .firstOrNull { it.subjectX500Principal == last.issuerX500Principal } ?: break
      if (!seen.add(issuer)) break // cycle guard
      chain.add(issuer)
      added++
    }
    return chain
  }

  /** Minimal DER TLV reader over a byte buffer. */
  private class DerReader(private val buf: ByteArray) {
    private var pos = 0

    fun hasNext(): Boolean = pos < buf.size

    fun read(): Tlv {
      val tag = buf[pos++].toInt() and 0xFF
      var len = buf[pos++].toInt() and 0xFF
      if (len and 0x80 != 0) {
        val numBytes = len and 0x7F
        len = 0
        repeat(numBytes) { len = (len shl 8) or (buf[pos++].toInt() and 0xFF) }
      }
      val tlv = Tlv(buf, tag, pos, len)
      pos += len
      return tlv
    }
  }

  /** A parsed tag-length-value: the tag and a view of its content bytes. */
  private class Tlv(
    private val buf: ByteArray,
    val tag: Int,
    private val offset: Int,
    private val length: Int,
  ) {
    fun content(): ByteArray = buf.copyOfRange(offset, offset + length)
  }
}
