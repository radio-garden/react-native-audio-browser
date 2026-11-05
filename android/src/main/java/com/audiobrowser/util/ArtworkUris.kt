package com.audiobrowser.util

import android.net.Uri
import java.security.MessageDigest

/** Builds and parses the `content://<pkg>.audiobrowser.artwork/art/<token>` artwork URIs. */
object ArtworkUris {
  // Must match the provider's `android:authorities` in AndroidManifest.xml.
  const val AUTHORITY_SUFFIX = "audiobrowser.artwork"
  private const val PATH = "art"

  fun authorityFor(packageName: String): String = "$packageName.$AUTHORITY_SUFFIX"

  /** A stable token for [url] that does not reveal it to whoever sees the URI (SHA-256 hex). */
  fun tokenFor(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun contentUri(authority: String, token: String): String = "content://$authority/$PATH/$token"

  /** The `<token>` of a `content://…/art/<token>` URI, whatever its authority; else null. */
  fun parseToken(uri: Uri): String? {
    if (uri.scheme != "content") return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments[0] != PATH) return null
    return segments[1].takeIf { it.isNotEmpty() }
  }
}
