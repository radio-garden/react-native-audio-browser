import { type HybridObject } from 'react-native-nitro-modules';
import type { BrowserItem, BrowserTrack } from '../../types/browser-nodes.ts';
import type {
  PlaybackError,
  PlaybackProgress,
  PlaybackState,
  RepeatMode,
} from '../../types/player.ts';

export interface AudioBrowser
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  /* Browser Methods: */

  // configure(config: BrowserConfig): void

  /**
   * Navigate to a specific path in the browser.
   */
  navigate(path: string): void

  /**
   * Get the current browser item being displayed.
   */
  getCurrentItem(): BrowserItem

  /**
   * Search for tracks using the configured search source.
   */
  search(query: string): Promise<BrowserTrack[]>

  /* Player Methods: */

  /**
   * Sets the player to start playing as soon as a track is loaded and ready.
   */
  play(): void
  /**
   * Pauses playback, but leaves the current track loaded and ready to resume.
   */
  pause(): void
  /**
   * Stops playback and stops loading the current track.
   *
   * To resume playback, `play()` must be called again, which will reload the
   * current track and start playing from the beginning.
   */
  stop(): void
  /**
   * Sets the player volume from 0.0 (silent) to 1.0 (full volume).
   */
  setVolume(volume: number): void
  /**
   * Returns the current playback progress information.
   *
   * @returns An object containing:
   * - `position`: Current playback position in seconds (0.0 to duration)
   * - `duration`: Total track duration in seconds (0.0 if unknown)
   * - `buffered`: Amount of content buffered ahead in seconds (0.0 to duration)
   */
  getPlaybackProgress(): PlaybackProgress
  /**
   * Returns any current player error, or null if no error.
   */
  getPlaybackError(): PlaybackError | null
  /**
   * Returns the current playback state of the player.
   *
   * @returns The current state: 'idle' (no track), 'stopped' (track loaded, position reset),
   * 'loading' (buffering), 'playing' (active playback), 'paused' (paused at position), or 'error' (playback failed)
   */
  getPlaybackState(): PlaybackState
  /**
   * Loads the given track into the player, replacing the current track.
   *
   * @param track The track to load
   */
  load(track: BrowserTrack): void
  /**
   * Adds the given tracks to the queue.
   *
   * @param tracks The tracks to add to the queue
   * @param index Optional position to insert the tracks. If not provided, tracks are added to the end of the queue.
   */
  add(tracks: BrowserTrack[], index?: number): void
  /**
   * Gets the current queue of tracks.
   */
  getQueue(): BrowserTrack[]
  /**
   * Gets the currently playing or loaded track.
   *
   * @returns The current track, or null if no track is loaded
   */
  getCurrentTrack(): BrowserTrack | null
  /**
   * Gets the index of the current track in the queue.
   *
   * @returns The zero-based index of the current track, or -1 if no track is current
   */
  getCurrentIndex(): number
  /**
   * Sets the queue to the given tracks, replacing any existing queue.
   */
  setQueue(tracks: BrowserTrack[], startIndex?: number): void
  /**
   * Clears the queue and stops playback.
   */
  clear(): void
  setRepeatMode(mode: RepeatMode): void
}
