import type { NowPlayingMetadata } from '../../features'

/**
 * Player actions a Media Session command routes into. The host wires these to
 * its remote-dispatch methods (handle-or-default, then emit) so browser media
 * controls behave exactly like the native lockscreen/CarPlay/Android Auto path.
 */
export interface MediaSessionActions {
  play(): void
  pause(): void
  stop(): void
  next(): void
  previous(): void
  /** Absolute seek, in seconds. */
  seek(position: number): void
  /** Relative seek forward, in seconds. */
  jumpForward(interval: number): void
  /** Relative seek backward, in seconds. */
  jumpBackward(interval: number): void
}

/** Fallback skip interval (seconds) when the browser omits a seek offset. */
const DEFAULT_SEEK_INTERVAL = 10

/**
 * Bridges the web player to the browser's Media Session API, so the OS media
 * controls (lockscreen, notification shade, hardware media keys, Bluetooth
 * controls) show the current track and route their commands back into the
 * player.
 *
 * The native platforms publish now-playing metadata and handle remote commands
 * via MPNowPlayingInfoCenter / MediaSession; this is the web equivalent. It is a
 * no-op when the API is unavailable (server-side rendering, older browsers).
 */
export class MediaSessionManager {
  private readonly session: MediaSession | undefined

  /** Actions registered as handlers — tracked so dispose() can clear them. */
  private static readonly ACTIONS: MediaSessionAction[] = [
    'play',
    'pause',
    'stop',
    'nexttrack',
    'previoustrack',
    'seekto',
    'seekforward',
    'seekbackward'
  ]

  constructor(actions: MediaSessionActions) {
    this.session =
      typeof navigator !== 'undefined' &&
      'mediaSession' in navigator &&
      typeof MediaMetadata !== 'undefined'
        ? navigator.mediaSession
        : undefined
    if (this.session) this.registerHandlers(this.session, actions)
  }

  /**
   * Publishes now-playing metadata to the OS media controls.
   */
  setMetadata(metadata: NowPlayingMetadata): void {
    if (!this.session) return
    this.session.metadata = new MediaMetadata({
      title: metadata.title ?? '',
      artist: metadata.artist ?? '',
      album: metadata.album ?? '',
      artwork: metadata.artwork ? [{ src: metadata.artwork }] : []
    })
  }

  /**
   * Reflects whether the player is playing/paused/stopped in the OS controls.
   */
  setPlaybackState(state: MediaSessionPlaybackState): void {
    if (!this.session) return
    this.session.playbackState = state
  }

  /**
   * Reports the scrubber position. Live streams (infinite or unknown duration)
   * have no meaningful position, so the state is cleared instead.
   */
  setPositionState(state: {
    duration: number
    position: number
    playbackRate: number
  }): void {
    if (!this.session?.setPositionState) return
    const { duration, position, playbackRate } = state
    if (!Number.isFinite(duration) || duration <= 0) {
      // No seekable timeline (live radio) — clear any stale position.
      this.session.setPositionState()
      return
    }
    this.session.setPositionState({
      duration,
      position: Math.max(0, Math.min(position, duration)),
      playbackRate: playbackRate > 0 ? playbackRate : 1
    })
  }

  /**
   * Removes handlers and clears metadata/state. Call when tearing down.
   */
  dispose(): void {
    if (!this.session) return
    for (const action of MediaSessionManager.ACTIONS) {
      try {
        this.session.setActionHandler(action, null)
      } catch {
        // Browser doesn't support this action — nothing to clear.
      }
    }
    this.session.metadata = null
    this.session.playbackState = 'none'
  }

  private registerHandlers(
    session: MediaSession,
    actions: MediaSessionActions
  ): void {
    const set = (
      action: MediaSessionAction,
      handler: MediaSessionActionHandler
    ) => {
      // Not every browser supports every action; ignore the ones it rejects.
      try {
        session.setActionHandler(action, handler)
      } catch {
        // Unsupported action — skip it.
      }
    }

    set('play', () => actions.play())
    set('pause', () => actions.pause())
    set('stop', () => actions.stop())
    set('nexttrack', () => actions.next())
    set('previoustrack', () => actions.previous())
    set('seekto', (details) => {
      if (details.seekTime != null) actions.seek(details.seekTime)
    })
    set('seekforward', (details) => {
      actions.jumpForward(details.seekOffset ?? DEFAULT_SEEK_INTERVAL)
    })
    set('seekbackward', (details) => {
      actions.jumpBackward(details.seekOffset ?? DEFAULT_SEEK_INTERVAL)
    })
  }
}
