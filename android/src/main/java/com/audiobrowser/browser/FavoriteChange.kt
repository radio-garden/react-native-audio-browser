package com.audiobrowser.browser

import com.audiobrowser.extension.identity
import com.margelo.nitro.audiobrowser.ResolvedTrack
import com.margelo.nitro.audiobrowser.Track

/**
 * A change to the favorite set: the [identities] whose state changed, and the set after it. Every
 * copy of a track already stamped with `favorited` (on the page on screen, in the content and track
 * caches, in the queue) follows it through [restamp].
 */
class FavoriteChange(val identities: Set<String>, private val favorites: Set<String>) {
  /**
   * [track] with `favorited` as the set has it, for a track whose identity changed. Unlike
   * hydration it overwrites the value the track carries and ignores whether favoriting is on, so a
   * change made while it is off still reaches the copies stamped before. Null when the track
   * stands.
   */
  fun restamp(track: Track): Track? {
    val identity = track.identity?.takeIf { it in identities } ?: return null
    val favorited = identity in favorites
    return if (track.favorited == favorited) null else track.copy(favorited = favorited)
  }

  /**
   * As [restamp], for a track that carries `favorited`. One without follows the set through
   * hydration, and stamping it would keep a value after favoriting is turned off.
   */
  fun restampStamped(track: Track): Track? = if (track.favorited == null) null else restamp(track)

  /** A copy of [page] with each track [transform] returns replaced; null when none is. */
  fun restamp(page: ResolvedTrack, transform: (Track) -> Track? = { restamp(it) }): ResolvedTrack? {
    val sections = page.sections ?: return null
    var replaced = false
    val updated =
      sections.map { section ->
        section.copy(
          children =
            section.children
              .map { track -> transform(track)?.also { replaced = true } ?: track }
              .toTypedArray()
        )
      }
    return if (replaced) page.copy(sections = updated.toTypedArray()) else null
  }

  companion object {
    /** The change from replacing [previous] with [favorites]; null when they hold the same. */
    fun between(previous: Set<String>, favorites: Set<String>): FavoriteChange? {
      val changed = (previous - favorites) + (favorites - previous)
      return if (changed.isEmpty()) null else FavoriteChange(changed, favorites)
    }

    /**
     * A toggle of [identity]. It restamps even when the set already agreed, since a copy may carry
     * the opposite value from the API.
     */
    fun toggle(identity: String, favorited: Boolean) =
      FavoriteChange(setOf(identity), if (favorited) setOf(identity) else emptySet())
  }
}
