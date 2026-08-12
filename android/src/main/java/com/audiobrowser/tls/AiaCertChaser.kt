package com.audiobrowser.tls

import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

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
  /** How many CA-Issuers URLs from one certificate are worth trying. */
  const val MAX_URLS_PER_CERT = 3

  /** Wall-clock ceiling on one chain-completion attempt, across all of its fetches. */
  const val MAX_CHASE_MILLIS = 20_000L

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
   * Issuers" pointer, until it reaches a certificate issued by one of [anchorSubjects], a
   * self-signed certificate, an issuer that cannot be fetched, or [maxIntermediates] additions.
   * Returns the (possibly extended) chain.
   *
   * Only ever *adds* certificates — the caller is still responsible for validating the completed
   * chain against the system trust anchors, so this can never cause an untrusted chain to be
   * accepted. From each URL's candidates the genuine issuer of the preceding certificate is
   * selected (defends against an AIA pointer to an unrelated certificate, and picks the right one
   * out of a multi-certificate PKCS#7 bundle).
   *
   * @param anchorSubjects the subjects of the caller's trust anchors. A chain whose top is already
   *   issued by one of them is not missing anything, so nothing is fetched — chasing could not help
   *   and the fetch would be a blocking round trip to a host named by a certificate that just
   *   failed validation. This is what keeps an expired-but-complete chain, a hostname mismatch or
   *   any other non-path failure from triggering a chase, and it stops the genuine
   *   missing-intermediate case one hop early, where the chain reaches a root the caller already
   *   trusts. Empty means "unknown", and the walk runs to one of the other stopping conditions.
   * @param maxUrlsPerCert how many of a certificate's CA-Issuers URLs are tried before giving up on
   *   it. The extension is authored by the same server that failed validation and nothing limits
   *   how many URLs it may list — a leaf can carry hundreds — while each one costs a blocking
   *   connect on the handshake thread. Real CAs publish one.
   * @param budgetMs wall-clock ceiling on the whole walk, checked before each fetch. Per-fetch
   *   limits bound one round trip; this bounds their sum, so no arrangement of URLs and hops can
   *   hold the handshake thread indefinitely.
   * @param fetch resolves a CA-Issuers URL to its candidate certificates (e.g. every certificate in
   *   a PKCS#7 bundle); empty if nothing could be retrieved.
   */
  fun completeChain(
    presented: List<X509Certificate>,
    anchorSubjects: Set<X500Principal> = emptySet(),
    maxIntermediates: Int = 5,
    maxUrlsPerCert: Int = MAX_URLS_PER_CERT,
    budgetMs: Long = MAX_CHASE_MILLIS,
    nowNanos: () -> Long = System::nanoTime,
    fetch: (url: String) -> List<X509Certificate>,
  ): List<X509Certificate> {
    if (presented.isEmpty()) return presented
    val chain = presented.toMutableList()
    val seen = HashSet(chain)
    val deadline = nowNanos() + budgetMs * 1_000_000
    var added = 0
    while (added < maxIntermediates) {
      val last = chain.last()
      if (last.subjectX500Principal == last.issuerX500Principal) break // self-signed root
      if (last.issuerX500Principal in anchorSubjects) break // already reaches a trust anchor
      val issuer =
        extractCaIssuerUrls(last)
          .asSequence()
          .take(maxUrlsPerCert)
          // Subtraction, not `>`: overflow-safe across nanoTime's arbitrary origin.
          .takeWhile { nowNanos() - deadline < 0 }
          .flatMap { url -> runCatching { fetch(url) }.getOrElse { emptyList() } }
          .firstOrNull { it.subjectX500Principal == last.issuerX500Principal } ?: break
      if (!seen.add(issuer)) break // cycle guard
      chain.add(issuer)
      added++
    }
    return chain
  }

  /**
   * Minimal DER TLV reader over a byte buffer. Malformed input throws [IllegalArgumentException],
   * which the callers above expect and turn into "no AIA".
   *
   * Every read is bounds-checked, and a declared length is rejected unless it is non-negative and
   * within the buffer. Both matter: the extension bytes come from a server-presented certificate,
   * so a length that shifts into a negative Int would rewind `pos` and spin [extractCaIssuerUrls]'s
   * loop forever on the handshake thread rather than failing.
   */
  private class DerReader(private val buf: ByteArray) {
    private var pos = 0

    fun hasNext(): Boolean = pos < buf.size

    fun read(): Tlv {
      val tag = readByte()
      var len = readByte()
      if (len and 0x80 != 0) {
        // Long form: the low bits give the number of length bytes. Four already covers any
        // buffer that fits in memory; more would overflow the Int we shift them into. The
        // indefinite form (0x80, i.e. zero length bytes) is not valid DER.
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
