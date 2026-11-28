package com.audiobrowser.player

import com.audiobrowser.browser.BrowserManager
import com.audiobrowser.browser.ExpandedQueue
import com.audiobrowser.browser.RestoreExpansion
import com.audiobrowser.extension.identity
import com.margelo.nitro.audiobrowser.RepeatMode
import com.margelo.nitro.audiobrowser.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Loads a persisted session back into the player: at setup, paused (`SetupPlayerOptions.restore`),
 * and for a system resumption request. Both queue it by the same rule, [queue].
 */
internal object PlaybackRestore {
  /** [sourcePath] is the page an expansion came from; null for the stored queue or the track. */
  data class Queue(val tracks: Array<Track>, val startIndex: Int, val sourcePath: String?)

  /** [expanded], else the stored queue at [state]'s track, else the track alone. */
  fun queue(state: PlaybackStateStore.PersistedState, expanded: ExpandedQueue?): Queue {
    if (expanded != null) return Queue(expanded.tracks, expanded.startIndex, expanded.sourcePath)
    state.storedQueue?.let { (tracks, index) ->
      return Queue(tracks.toTypedArray(), index, null)
    }
    return Queue(arrayOf(state.track), 0, null)
  }

  /**
   * The fresh queue of [state]'s track's contextual path, as [BrowserManager.expandQueueForRestore]
   * gives it. [browserManager] is asked for only when the track has a path.
   */
  suspend fun expand(
    state: PlaybackStateStore.PersistedState,
    browserManager: suspend () -> BrowserManager,
  ): RestoreExpansion {
    val path = state.track.path ?: return RestoreExpansion.Unavailable
    val stored = state.storedQueue?.first.orEmpty().mapNotNull { it.identity }.toSet()
    return browserManager().expandQueueForRestore(path, state.track, stored)
  }

  /**
   * The session a setup restores into [player]: the persisted one when [restore] asks and the queue
   * is empty, else null. A [repeatMode] the setup sets explicitly replaces the stored one.
   */
  fun sessionForSetup(
    player: Player,
    restore: Boolean,
    repeatMode: RepeatMode?,
  ): PlaybackStateStore.PersistedState? {
    if (!restore || !player.isEmpty) return null
    val session = player.playbackStateStore.session() ?: return null
    return repeatMode?.let { session.copy(repeatMode = it) } ?: session
  }

  /**
   * The intent a setup applies: paused when it restores [session], whatever it asked for, so a play
   * arriving after setup is the one the restored queue keeps.
   */
  fun setupIntent(requested: Boolean?, session: PlaybackStateStore.PersistedState?): Boolean? =
    if (session == null) requested else false

  /**
   * Starts restoring [session] into [player] in [scope], after setup returns: [run] from the
   * player's queue version now and [clearGeneration], the store's clear generation read before
   * [session] was.
   */
  fun start(
    player: Player,
    session: PlaybackStateStore.PersistedState,
    clearGeneration: Int,
    scope: CoroutineScope,
    expand: suspend (PlaybackStateStore.PersistedState) -> RestoreExpansion,
    isCurrent: () -> Boolean,
  ) {
    val queueVersion = player.queueVersion
    scope.launch { run(player, session, queueVersion, clearGeneration, expand, isCurrent) }
  }

  /**
   * Restores [session] into [player], keeping the current intent, so a play or pause sent while
   * [expand] runs is the one the queue keeps. Leaves the player alone when its queue moved from
   * [queueVersion], the session was cleared since [clearGeneration], it is no longer [isCurrent],
   * or the page now disables the track.
   */
  suspend fun run(
    player: Player,
    session: PlaybackStateStore.PersistedState,
    queueVersion: Int,
    clearGeneration: Int,
    expand: suspend (PlaybackStateStore.PersistedState) -> RestoreExpansion,
    isCurrent: () -> Boolean,
  ) {
    val expanded =
      when (val expansion = expand(session)) {
        is RestoreExpansion.Queue -> expansion.queue
        RestoreExpansion.Refused -> return
        RestoreExpansion.Unavailable -> null
      }
    if (!isCurrent() || player.queueVersion != queueVersion) return
    if (PlaybackStateStore.clearGeneration != clearGeneration) return
    player.restore(session, expanded)
  }
}
