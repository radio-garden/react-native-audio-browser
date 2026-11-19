import type { NowPlayingUpdate, NowPlayingMetadata } from '../../features'
import type { Track } from '../../types'
import { artworkUrl } from '../../utils/artwork'
import { getTrackIdentity } from '../../utils/getTrackIdentity'

/**
 * The now-playing metadata of the current track: each line from a flash, else
 * the override, else the track, as native renders them.
 */
export class NowPlayingManager {
  private override: NowPlayingUpdate | undefined
  private flash: NowPlayingUpdate | undefined
  private flashRevert: ReturnType<typeof setTimeout> | undefined

  // Event callback
  onNowPlayingChanged: (metadata: NowPlayingMetadata) => void = () => {}

  /** @param current reads the current track and its duration at each render. */
  constructor(
    private readonly current: () => {
      track: Track | undefined
      duration: number
    }
  ) {}

  /** Sets the override, or clears it with `undefined`, and publishes. */
  updateNowPlaying(update: NowPlayingUpdate | undefined): void {
    this.override = update
    this.publish()
  }

  /** Shows `update` over the override for `durationMs`, then reverts. */
  flashNowPlaying(update: NowPlayingUpdate, durationMs: number): void {
    this.cancelFlash()
    this.flash = update
    this.flashRevert = setTimeout(() => this.clearNowPlayingFlash(), durationMs)
    this.publish()
  }

  clearNowPlayingFlash(): void {
    if (!this.flash) return
    this.cancelFlash()
    this.publish()
  }

  /** The metadata as rendered, or undefined with no track. */
  getNowPlaying(): NowPlayingMetadata | undefined {
    const { track, duration } = this.current()
    if (!track) return undefined
    const { flash, override } = this

    return {
      title: flash?.title ?? override?.title ?? track.title,
      artist: flash?.artist ?? override?.artist ?? track.artist,
      album: flash?.album ?? override?.album ?? track.album,
      artwork: artworkUrl(track.artwork),
      description: track.description,
      mediaId: getTrackIdentity(track) ?? track.path,
      genre: track.genre,
      duration
    }
  }

  /** Drops the override and any flash without publishing: call on a track change. */
  clearNowPlayingOverride(): void {
    this.override = undefined
    this.cancelFlash()
  }

  private cancelFlash(): void {
    clearTimeout(this.flashRevert)
    this.flashRevert = undefined
    this.flash = undefined
  }

  private publish(): void {
    const metadata = this.getNowPlaying()
    if (metadata) this.onNowPlayingChanged(metadata)
  }
}
