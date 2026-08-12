import type { NowPlayingMetadata } from '../../features'
import type { AudioBrowser as AudioBrowserSpec } from '../../specs/audio-browser.nitro'
import { MediaSessionManager } from './MediaSessionManager'

/**
 * The slice of the player the remote-command bridge drives. Picked straight from
 * the spec so the contract can never drift from the real surface.
 */
export type RemoteCommandHost = Pick<
  AudioBrowserSpec,
  // Default transport actions (run when the consumer provides no handler).
  | 'play'
  | 'pause'
  | 'stop'
  | 'skipToNext'
  | 'skipToPrevious'
  | 'seekTo'
  | 'seekBy'
  // Consumer-provided command overrides.
  | 'handleRemotePlay'
  | 'handleRemotePause'
  | 'handleRemoteStop'
  | 'handleRemoteNext'
  | 'handleRemotePrevious'
  | 'handleRemoteSeek'
  | 'handleRemoteJumpForward'
  | 'handleRemoteJumpBackward'
  // Observation events (always emitted after a command).
  | 'onRemotePlay'
  | 'onRemotePause'
  | 'onRemoteStop'
  | 'onRemoteNext'
  | 'onRemotePrevious'
  | 'onRemoteSeek'
  | 'onRemoteJumpForward'
  | 'onRemoteJumpBackward'
  // State mirrored out to the OS controls.
  | 'getPlayback'
  | 'getPlayingState'
  | 'getProgress'
>

/**
 * Bridges the OS media controls (lockscreen / notification / hardware media
 * keys, via the browser Media Session API) to the player:
 *
 *  - mirrors now-playing metadata, playback state and scrubber position *out*, and
 *  - routes incoming commands *in* — preferring a consumer-provided handler,
 *    otherwise running the default transport action, then always emitting the
 *    observation event. This matches the native HybridAudioBrowser/AudioBrowser
 *    contract exactly.
 *
 * A no-op when the Media Session API is unavailable (handled by the underlying
 * {@link MediaSessionManager}).
 */
export class RemoteCommandController {
  private readonly mediaSession: MediaSessionManager

  constructor(private readonly host: RemoteCommandHost) {
    // Handlers are read live (inside each arrow) so consumers can assign them
    // after construction and still be honoured.
    this.mediaSession = new MediaSessionManager({
      play: () =>
        this.run(
          host.handleRemotePlay,
          undefined,
          () => host.play(),
          () => host.onRemotePlay()
        ),
      pause: () =>
        this.run(
          host.handleRemotePause,
          undefined,
          () => host.pause(),
          () => host.onRemotePause()
        ),
      stop: () =>
        this.run(
          host.handleRemoteStop,
          undefined,
          () => host.stop(),
          () => host.onRemoteStop()
        ),
      next: () =>
        this.run(
          host.handleRemoteNext,
          undefined,
          () => host.skipToNext(),
          () => host.onRemoteNext()
        ),
      previous: () =>
        this.run(
          host.handleRemotePrevious,
          undefined,
          () => host.skipToPrevious(),
          () => host.onRemotePrevious()
        ),
      seek: (position) =>
        this.run(
          host.handleRemoteSeek,
          { position },
          () => host.seekTo(position),
          (event) => host.onRemoteSeek(event)
        ),
      jumpForward: (interval) =>
        this.run(
          host.handleRemoteJumpForward,
          { interval },
          () => host.seekBy(interval),
          (event) => host.onRemoteJumpForward(event)
        ),
      jumpBackward: (interval) =>
        this.run(
          host.handleRemoteJumpBackward,
          { interval },
          () => host.seekBy(-interval),
          (event) => host.onRemoteJumpBackward(event)
        )
    })
  }

  /** Publishes now-playing metadata to the OS media controls. */
  setMetadata(metadata: NowPlayingMetadata): void {
    this.mediaSession.setMetadata(metadata)
  }

  /** Reflects the current playing/paused/stopped state in the OS controls. */
  syncPlaybackState(): void {
    const playback = this.host.getPlayback()
    if (playback.state === 'none') {
      this.mediaSession.setPlaybackState('none')
    } else if (this.host.getPlayingState().playing) {
      this.mediaSession.setPlaybackState('playing')
    } else {
      this.mediaSession.setPlaybackState('paused')
    }
  }

  /** Pushes the latest scrubber position to the OS controls. */
  updateProgress(): void {
    const { duration, position } = this.host.getProgress()
    // The web player has no variable-rate playback, so the rate is always 1.
    // (Live radio has no seekable timeline anyway — the position is cleared.)
    this.mediaSession.setPositionState({ duration, position, playbackRate: 1 })
  }

  /** Removes handlers and clears metadata/state. Call on teardown. */
  dispose(): void {
    this.mediaSession.dispose()
  }

  /**
   * The remote-command contract: run the consumer handler if present, otherwise
   * the default transport action, then always emit the observation event.
   */
  private run<E = void>(
    handler: ((event: E) => void) | undefined,
    event: E,
    fallback: () => void,
    emit: (event: E) => void
  ): void {
    if (handler) handler(event)
    else fallback()
    emit(event)
  }
}
