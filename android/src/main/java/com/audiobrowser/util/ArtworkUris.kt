package com.audiobrowser.util

import android.net.Uri
import java.security.MessageDigest

/** Builds and parses the opaque `content://<pkg>.audiobrowser.artwork/art/<token>` URIs. */
object ArtworkUris {
  const val AUTHORITY_SUFFIX = "audiobrowser.artwork"
  private const val PATH = "art"

  fun authorityFor(packageName: String): String = "$packageName.$AUTHORITY_SUFFIX"

  /** Stable, opaque token for a resolved artwork URL (SHA-256 hex). */
  fun tokenFor(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun contentUri(authority: String, token: String): String = "content://$authority/$PATH/$token"

  /** The `<token>` for a `…/art/<token>` content URI, or null if the shape is wrong. */
  fun parseToken(uri: Uri): String? {
    if (uri.scheme != "content") return null
    val segments = uri.pathSegments
    if (segments.size != 2 || segments[0] != PATH) return null
    return segments[1].takeIf { it.isNotEmpty() }
  }
}
