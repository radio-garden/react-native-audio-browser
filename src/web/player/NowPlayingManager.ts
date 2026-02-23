import type { NowPlayingUpdate, NowPlayingMetadata } from '../../features'
import type { Track } from '../../types'

/**
 * Manages now playing metadata overrides.
 * Allows temporary metadata changes for the currently playing track
 * (e.g., updating stream metadata for live radio).
 */
export class NowPlayingManager {
  private override: NowPlayingUpdate | undefined

  // Event callback
  onNowPlayingChanged: (metadata: NowPlayingMetadata) => void = () => {}

  /**
   * Updates the now playing metadata with an override.
   * Pass null or undefined to clear overrides and revert to track metadata.
   *
   * @param update Metadata override or null/undefined to clear
   * @param currentTrack Current track for base metadata
   * @param duration Current track duration
   */
  updateNowPlaying(
    update: NowPlayingUpdate | undefined,
    currentTrack: Track | undefined,
    duration: number
  ): void {
    this.override = update

    if (!currentTrack) return

    const metadata: NowPlayingMetadata = update
      ? {
          title: update.title ?? currentTrack.title,
          artist: update.artist ?? currentTrack.artist,
          album: currentTrack.album,
          artwork: currentTrack.artwork,
          description: currentTrack.description,
          mediaId: currentTrack.src ?? currentTrack.url,
          genre: currentTrack.genre,
          duration
        }
      : {
          title: currentTrack.title,
          artist: currentTrack.artist,
          album: currentTrack.album,
          artwork: currentTrack.artwork,
          description: currentTrack.description,
          mediaId: currentTrack.src ?? currentTrack.url,
          genre: currentTrack.genre,
          duration
        }

    this.onNowPlayingChanged(metadata)
  }

  /**
   * Gets the current now playing metadata (with override applied if set).
   *
   * @param currentTrack Current track for base metadata
   * @param duration Current track duration
   * @returns Now playing metadata or undefined if no track
   */
  getNowPlaying(
    currentTrack: Track | undefined,
    duration: number
  ): NowPlayingMetadata | undefined {
    if (!currentTrack) return undefined

    return {
      title: this.override?.title ?? currentTrack.title,
      artist: this.override?.artist ?? currentTrack.artist,
      album: currentTrack.album,
      artwork: currentTrack.artwork,
      description: currentTrack.description,
      mediaId: currentTrack.src ?? currentTrack.url,
      genre: currentTrack.genre,
      duration
    }
  }

  /**
   * Clears the current metadata override.
   * Should be called when the track changes.
   */
  clearNowPlayingOverride(): void {
    this.override = undefined
  }
}
