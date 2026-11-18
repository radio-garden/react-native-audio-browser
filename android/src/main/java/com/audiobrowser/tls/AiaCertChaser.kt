package com.audiobrowser.tls

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * Fills in intermediate certificates a server failed to send, by following each certificate's
 * Authority Information Access (AIA) "CA Issuers" URL, as browsers and iOS do and Android's default
 * trust manager does not.
 */
object AiaCertChaser {
  /**
   * A certificate's AIA extension is written by the server that failed validation and may list
   * hundreds of URLs, each a blocking connect on the handshake thread. Real CAs publish one.
   */
  const val MAX_URLS_PER_CERT = 3

  /**
   * Ceiling on one whole chain completion, across all its fetches. A DNS lookup already in flight
   * cannot be cut short (Java has no resolver timeout), so the real bound is this plus one lookup.
   */
  const val MAX_CHASE_MILLIS = 20_000L

  private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

  /** DER encoding of id-ad-caIssuers, 1.3.6.1.5.5.7.48.2. */
  private val CA_ISSUERS_OID = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

  private const val TAG_SEQUENCE = 0x30
  private const val TAG_OID = 0x06
  private const val TAG_OCTET_STRING = 0x04

  /** GeneralName's uniformResourceIdentifier: context-specific primitive [6]. */
  private const val TAG_URI = 0x86

  /**
   * The certificate's AIA "CA Issuers" URLs; empty when the extension is absent or malformed. Never
   * throws.
   */
  fun extractCaIssuerUrls(cert: X509Certificate): List<String> {
    val raw = cert.getExtensionValue(AIA_OID) ?: return emptyList()
    return try {
      // getExtensionValue() returns the value still wrapped in an OCTET STRING.
      val wrapper = DerReader(raw).read()
      if (wrapper.tag != TAG_OCTET_STRING) return emptyList()

      val aiaSeq = DerReader(wrapper.content()).read()
      if (aiaSeq.tag != TAG_SEQUENCE) return emptyList()

      val urls = mutableListOf<String>()
      val descriptions = DerReader(aiaSeq.content())
      while (descriptions.hasNext()) {
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
      emptyList()
    }
  }

  /**
   * Appends to [presented] each missing issuer, fetched through its predecessor's CA-Issuers URLs,
   * until the top is self-signed or issued by one of [anchorSubjects], an issuer cannot be found or
   * repeats, or [maxIntermediates] have been added. Returns the chain, unchanged if nothing was
   * found. Only adds certificates and validates nothing: the caller must still check the result.
   *
   * @param anchorSubjects subjects of the caller's trust anchors. A chain already reaching one is
   *   left alone, so a failure that is not a missing intermediate fetches nothing. Empty means
   *   unknown.
   * @param maxUrlsPerCert distinct URLs tried per certificate; duplicates do not count.
   * @param budgetMs ceiling on the whole walk, checked before each fetch.
   * @param fetch the candidate certificates at a URL, empty if none; must give up by
   *   `deadlineNanos` (absolute, on [nowNanos]'s clock). A throw counts as empty.
   */
  fun completeChain(
    presented: List<X509Certificate>,
    anchorSubjects: Set<X500Principal> = emptySet(),
    maxIntermediates: Int = 5,
    maxUrlsPerCert: Int = MAX_URLS_PER_CERT,
    budgetMs: Long = MAX_CHASE_MILLIS,
    nowNanos: () -> Long = System::nanoTime,
    fetch: (url: String, deadlineNanos: Long) -> List<X509Certificate>,
  ): List<X509Certificate> {
    if (presented.isEmpty()) return presented
    val chain = presented.toMutableList()
    val seen = HashSet(chain)
    val deadline = nowNanos() + budgetMs * 1_000_000
    var added = 0
    while (added < maxIntermediates) {
      val last = chain.last()
      if (last.subjectX500Principal == last.issuerX500Principal) break
      if (last.issuerX500Principal in anchorSubjects) break
      // Lazy, so no URL is fetched after one yields the issuer.
      val issuer =
        extractCaIssuerUrls(last)
          .asSequence()
          .distinct()
          .take(maxUrlsPerCert)
          // Subtraction, not `<`: nanoTime's origin is arbitrary, so its values may overflow.
          .takeWhile { nowNanos() - deadline < 0 }
          .flatMap { url -> runCatching { fetch(url, deadline) }.getOrElse { emptyList() } }
          .firstOrNull { it.subjectX500Principal == last.issuerX500Principal } ?: break
      if (!seen.add(issuer)) break
      chain.add(issuer)
      added++
    }
    return chain
  }

  /**
   * Minimal DER TLV reader. Malformed input throws [IllegalArgumentException]. The bytes come from
   * a server's certificate: a length that went negative would rewind `pos` and loop forever on the
   * handshake thread, so every length is checked against the buffer.
   */
  private class DerReader(private val buf: ByteArray) {
    private var pos = 0

    fun hasNext(): Boolean = pos < buf.size

    fun read(): Tlv {
      val tag = readByte()
      var len = readByte()
      if (len and 0x80 != 0) {
        // More than four length bytes overflows the Int; zero is BER's indefinite form, not DER.
        val numBytes = len and 0x7F
        require(numBytes in 1..4) { "unsupported DER length form: $numBytes length bytes" }
        len = 0
        repeat(numBytes) { len = (len shl 8) or readByte() }
        require(len >= 0) { "DER length overflows Int" }
      }
      require(len <= buf.size - pos) { "DER length $len runs past the end of the buffer" }
      val tlv = Tlv(buf, tag, pos, len)
      pos += len
      return tlv
    }

    private fun readByte(): Int {
      require(pos < buf.size) { "truncated DER" }
      return buf[pos++].toInt() and 0xFF
    }
  }

  private class Tlv(
    private val buf: ByteArray,
    val tag: Int,
    private val offset: Int,
    private val length: Int,
  ) {
    fun content(): ByteArray = buf.copyOfRange(offset, offset + length)
  }
}
