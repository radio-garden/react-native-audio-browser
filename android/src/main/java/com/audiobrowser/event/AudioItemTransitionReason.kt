package com.audiobrowser.event

/**
 * Indicates the reason why an [com.audiobrowser.model.Track] transitioned to another.
 */
enum class AudioItemTransitionReason {
  /**
   * Playback has automatically transitioned to the next
   * [com.audiobrowser.model.Track].
   *
   * This reason also indicates a transition caused by another player.
   */
  AUTO,

  /**
   * A seek to another [com.audiobrowser.model.Track] has occurred. Usually triggered
   * when calling [AudioPlayer.next][com.audiobrowser.AudioPlayer.next] or
   * [AudioPlayer.previous][com.audiobrowser.AudioPlayer.previous].
   */
  SEEK_TO_ANOTHER_AUDIO_ITEM,

  /** The [com.audiobrowser.model.Track] has been repeated. */
  REPEAT,

  /**
   * The current [com.audiobrowser.model.Track] has changed because of a change in the
   * queue. This can either be if the [com.audiobrowser.model.Track] previously being
   * played has been removed, or when the queue becomes non-empty after being empty.
   */
  QUEUE_CHANGED,
}

/**
 * Represents a transition from one [com.audiobrowser.model.Track] to another.
 * Examples include changes to [com.audiobrowser.model.Track] queue, an
 * [com.audiobrowser.model.Track] on repeat, skipping an
 * [com.audiobrowser.model.Track], or simply when the
 * [com.audiobrowser.model.Track] has finished.
 */
data class AudioItemTransition(val reason: AudioItemTransitionReason, val oldPosition: Long)
