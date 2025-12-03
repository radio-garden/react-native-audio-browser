export type PlayerContentType = 'music' | 'speech'

export interface PlayerConfig {
  contentType?: PlayerContentType
  debug?: boolean
  autoPlay?: boolean
}

export interface MediaSessionConfig {
  /**
   * When true, activates next/previous track controls in the media notification / lock screen.
   * Default is false.
   */
  skipItemControls?: boolean
  /**
   * When true, activates skip forward/backward controls in the media notification / lock screen.
   * Default is false. By default these controls skip 15000ms (15s), but a number value can be provided to customize the skip interval in milliseconds.
   */
  skipTimeControls?: boolean | number
  /**
   * Configures whether to show a pause or stop button in the media notification / lock screen.
   * - 'pause': Shows pause button (default behavior)
   * - 'stop': Shows stop button that stops playback / loading and resets position to 0
   *
   * Note: On iOS, stop button only shows for live streams.
   */
  stopControl?: 'pause' | 'stop'
}

export interface PlaybackError {
  error: string
  errorCode?: number
}

/**
 * Represents the current playback state of the audio player.
 */
export type PlaybackState =
  /** Empty queue */
  | 'idle'
  /**
   * Playback is stopped but the track remains loaded. Position is reset to 0.
   * Media session controls remain visible.
   */
  | 'stopped'
  /**
   * The track is being loaded or buffered and is not yet ready for playback.
   */
  | 'loading'
  /**
   * The track is currently playing.
   */
  | 'playing'
  /**
   * Playback is paused and can be resumed or started by calling play().
   */
  | 'paused'
  /**
   * An error occurred during playback. Check getError() for details.
   */
  | 'error'

export type RepeatMode = 'off' | 'queue' | 'track'

export interface PlaybackProgress {
  position: number
  duration: number
  buffered: number
}
