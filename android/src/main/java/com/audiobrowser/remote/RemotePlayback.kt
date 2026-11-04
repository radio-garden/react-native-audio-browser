package com.audiobrowser.remote

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MediaPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * A remote output's side of [RemoteSwitchingPlayer]: a Cast receiver or a Sonos room, each kept
 * apart from its SDK or protocol so the rest of the library compiles without them.
 */
internal interface RemotePlayback {
  /**
   * Plays on the receiver, read while [isSessionAvailable]: each session can bring a new player, so
   * the switch keeps the one it handed over to until the handback. Setting its items loads them
   * there and plays as its play intent says, without [MediaPlayer.prepare].
   */
  val player: MediaPlayer

  val isSessionAvailable: Boolean

  /** Calls [listener] on the main thread whenever [isSessionAvailable] changes. */
  fun setSessionListener(listener: (() -> Unit)?)

  /**
   * Calls [listener] on the main thread when the receiver fails to play what it was sent: [player]
   * reports no error of its own.
   */
  fun setErrorListener(listener: ((PlaybackException) -> Unit)?)

  /** Ends the session on this side; the receiver stops or plays on as it was told. */
  fun release()
}

/**
 * What the receiver loads for a media item: it fetches [url] and [artworkUrl] itself, without
 * request headers. A [live] item loads as a live stream, at its live edge.
 */
internal data class RemoteMedia(
  val url: String,
  val contentType: String,
  val artworkUrl: String?,
  val live: Boolean,
)

/**
 * The [RemoteMedia] of each media item handed to the receiver. A receiver's media item converter
 * runs synchronously on the main thread, and resolving a URL can need the JS thread, so items are
 * resolved into here first and the converter only reads.
 */
internal class RemoteMediaResolutions {
  private val byKey = ConcurrentHashMap<String, RemoteMedia>()

  operator fun get(item: MediaItem): RemoteMedia? = byKey[key(item)]

  operator fun contains(item: MediaItem): Boolean = byKey.containsKey(key(item))

  operator fun set(item: MediaItem, media: RemoteMedia) {
    byKey[key(item)] = media
  }

  fun clear() = byKey.clear()

  private fun key(item: MediaItem) = "${item.mediaId}\u0000${item.localConfiguration?.uri}"
}
