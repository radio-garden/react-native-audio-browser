package com.doublesymmetry.trackplayer.event

/**
 * Indicates the reason why an [com.doublesymmetry.trackplayer.model.Track] transitioned to another.
 */
enum class AudioItemTransitionReason {
  /**
   * Playback has automatically transitioned to the next
   * [com.doublesymmetry.trackplayer.model.Track].
   *
   * This reason also indicates a transition caused by another player.
   */
  AUTO,

  /**
   * A seek to another [com.doublesymmetry.trackplayer.model.Track] has occurred. Usually triggered
   * when calling [AudioPlayer.next][com.doublesymmetry.trackplayer.TrackPlayer.next] or
   * [AudioPlayer.previous][com.doublesymmetry.trackplayer.TrackPlayer.previous].
   */
  SEEK_TO_ANOTHER_AUDIO_ITEM,

  /** The [com.doublesymmetry.trackplayer.model.Track] has been repeated. */
  REPEAT,

  /**
   * The current [com.doublesymmetry.trackplayer.model.Track] has changed because of a change in the
   * queue. This can either be if the [com.doublesymmetry.trackplayer.model.Track] previously being
   * played has been removed, or when the queue becomes non-empty after being empty.
   */
  QUEUE_CHANGED,
}

/**
 * Represents a transition from one [com.doublesymmetry.trackplayer.model.Track] to another.
 * Examples include changes to [com.doublesymmetry.trackplayer.model.Track] queue, an
 * [com.doublesymmetry.trackplayer.model.Track] on repeat, skipping an
 * [com.doublesymmetry.trackplayer.model.Track], or simply when the
 * [com.doublesymmetry.trackplayer.model.Track] has finished.
 */
data class AudioItemTransition(val reason: AudioItemTransitionReason, val oldPosition: Long)
