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

// export type Browser = {
//   navigate(path: string): void
//   getCurrentItem(): BrowserItem
//   search(query: string): Promise<BrowserTrack[]>
// }

// export type Player = {
//   /**
//    * Sets the player to start playing as soon as a track is loaded and ready.
//    */
//   play(): void
//   /**
//    * Pauses playback, but leaves the current track loaded and ready to resume.
//    */
//   pause(): void
//   /**
//    * Stops playback and stops loading the current track.
//    *
//    * To resume playback, `play()` must be called again, which will reload the
//    * current track and start playing from the beginning.
//    */
//   stop(): void
//   /**
//    * Sets the player volume from 0.0 (silent) to 1.0 (full volume).
//    */
//   setVolume(volume: number): void
//   /**
//    * Returns the current playback progress information.
//    *
//    * @returns An object containing:
//    * - `position`: Current playback position in seconds (0.0 to duration)
//    * - `duration`: Total track duration in seconds (0.0 if unknown)
//    * - `buffered`: Amount of content buffered ahead in seconds (0.0 to duration)
//    */
//   getProgress(): {
//     position: number
//     duration: number
//     buffered: number
//   }
//   /**
//    * Returns any current player error, or null if no error.
//    */
//   getPlaybackError(): PlaybackError | null
//   /**
//    * Returns the current playback state of the player.
//    *
//    * @returns The current state: 'idle' (no track), 'stopped' (track loaded, position reset),
//    * 'loading' (buffering), 'playing' (active playback), 'paused' (paused at position), or 'error' (playback failed)
//    */
//   getPlaybackState(): PlaybackState
//   /**
//    * Loads the given track into the player, replacing the current track.
//    *
//    * @param track The track to load
//    */
//   load(track: BrowserTrack): void
//   /**
//    * Adds the given tracks to the queue.
//    *
//    * @param tracks The tracks to add to the queue
//    * @param index Optional position to insert the tracks. If not provided, tracks are added to the end of the queue.
//    */
//   add(tracks: BrowserTrack[], index?: number): void
//   /**
//    * Gets the current queue of tracks.
//    */
//   getQueue(): BrowserTrack[]
//   /**
//    * Gets the currently playing or loaded track.
//    *
//    * @returns The current track, or null if no track is loaded
//    */
//   getCurrentTrack(): BrowserTrack | null
//   /**
//    * Gets the index of the current track in the queue.
//    *
//    * @returns The zero-based index of the current track, or -1 if no track is current
//    */
//   getCurrentIndex(): number
//   /**
//    * Sets the queue to the given tracks, replacing any existing queue.
//    */
//   setQueue(tracks: BrowserTrack[], startIndex?: number): void
//   /**
//    * Clears the queue and stops playback.
//    */
//   clear(): void
//   setRepeatMode(mode: 'off' | 'queue' | 'track'): void
// }
