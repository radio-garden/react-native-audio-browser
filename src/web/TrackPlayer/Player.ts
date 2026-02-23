import type shaka from 'shaka-player/dist/shaka-player.ui'
import type {
  Progress,
  PartialSetupPlayerOptions,
  Playback,
  PlaybackError
} from '../../features'
import type { Track } from '../../types'
import type { State as StateType } from './State'
import { SetupNotCalledError } from './SetupNotCalledError'
import { State } from './State'

// Extend Window interface for debug purposes
declare global {
  interface Window {
    rnab?: shaka.Player
  }
}

// Shaka event type definitions
interface ShakaErrorEvent extends CustomEvent {
  detail: {
    code: number
    message: string
  }
}

interface ShakaBufferingEvent extends CustomEvent {
  detail: {
    buffering: boolean
  }
}

export class Player {
  protected hasInitialized = false
  protected element?: HTMLMediaElement
  protected player?: shaka.Player
  protected _current?: Track = undefined
  protected _playWhenReady = false
  protected _state: Playback = { state: State.None }

  // current getter/setter
  public get current(): Track | undefined {
    return this._current
  }
  public set current(cur: Track | undefined) {
    this._current = cur
  }

  // state getter/setter
  protected get state(): Playback {
    return this._state
  }
  protected set state(newState: Playback) {
    this._state = newState
  }

  // playWhenReady getter/setter
  public get playWhenReady(): boolean {
    return this._playWhenReady
  }
  public set playWhenReady(pwr: boolean) {
    this._playWhenReady = pwr
  }

  /**
   * Returns the HTML media element, throwing if setup hasn't been called.
   */
  protected requireElement(): HTMLMediaElement {
    if (!this.element) throw new SetupNotCalledError()
    return this.element
  }

  /**
   * Returns the Shaka player instance, throwing if setup hasn't been called.
   */
  protected requirePlayer(): shaka.Player {
    if (!this.player) throw new SetupNotCalledError()
    return this.player
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  async setupPlayer(_options: PartialSetupPlayerOptions = {}): Promise<void> {
    // shaka only runs in a browser
    if (typeof window === 'undefined') return
    if (this.hasInitialized === true) {
      const error: PlaybackError = {
        code: 'player_already_initialized',
        message: 'The player has already been initialized via setupPlayer.'
      }
      // eslint-disable-next-line @typescript-eslint/only-throw-error
      throw error
    }

    const shaka = (await import('shaka-player/dist/shaka-player.ui')).default
    // Install built-in polyfills to patch browser incompatibilities.
    shaka.polyfill.installAll()
    // Check to see if the browser supports the basic APIs Shaka needs.
    if (!shaka.Player.isBrowserSupported()) {
      // This browser does not have the minimum set of APIs we need.
      this.state = {
        state: State.Error,
        error: {
          code: 'not_supported',
          message: 'Browser not supported...'
        }
      }
      throw new Error('Browser not supported.')
    }

    // build dom element and attach shaka-player
    const element = document.createElement('audio')
    element.setAttribute('id', 'react-native-audio-browser')
    document.body.appendChild(element)
    this.element = element

    const player = new shaka.Player()
    await player.attach(element)
    this.player = player

    // Listen for relevant events
    player.addEventListener('error', (event: Event) => {
      const errorEvent = event as ShakaErrorEvent
      this.onError(errorEvent.detail)
    })

    element.addEventListener('ended', () => this.onStateUpdate(State.Ended))
    element.addEventListener('playing', () => this.onStateUpdate(State.Playing))
    element.addEventListener('pause', () => this.onStateUpdate(State.Paused))

    player.addEventListener('loading', () => this.onStateUpdate(State.Loading))
    player.addEventListener('loaded', () => this.onStateUpdate(State.Ready))

    player.addEventListener('buffering', (event: Event) => {
      const bufferingEvent = event as ShakaBufferingEvent
      if (bufferingEvent.detail.buffering === true) {
        this.onStateUpdate(State.Buffering)
      } else {
        this.onStateUpdate(State.Ready)
      }
    })

    // Attach player to the window to make it easy to access in the JS console.
    if (__DEV__) {
      window.rnab = this.player
    }

    this.hasInitialized = true
  }

  /**
   * event handlers
   */
  protected onStateUpdate(state: Exclude<StateType, typeof State.Error>): void {
    this.state = { state }
  }

  private toNormalizedError(err: unknown): { code: number; message: string } {
    if (
      typeof err === 'object' &&
      err !== null &&
      'code' in err &&
      typeof (err as Record<string, unknown>).code === 'number'
    ) {
      const e = err as { code: number; message?: string }
      return { code: e.code, message: e.message ?? 'Unknown error' }
    }

    const message = err instanceof Error ? err.message : String(err)
    return { code: -1, message }
  }

  protected onError(shakaError: { code: number; message: string }): void {
    // unload the current track to allow for clean playback on other
    this.player?.unload().catch((err) => {
      console.error(`Error unloading player on 'onError'`, err)
    })

    const error: PlaybackError = {
      code: shakaError.code.toString(),
      message: shakaError.message
    }

    this.state = {
      state: State.Error,
      error
    }

    // Log the error.
    console.debug('Error code', shakaError.code, 'object', shakaError)
  }

  /**
   * NOTE: this method is sync despite the actual load being async. This
   * behavior is intentional as it mirrors what happens in Android. State
   * changes should be captured by event listeners.
   */
  public load(track: Track, onLoaded?: (track: Track) => void): void {
    const player = this.requirePlayer()

    if (!track.src) {
      const error: PlaybackError = {
        code: 'invalid_track',
        message: 'Track does not have a valid src URL'
      }
      this.state = {
        state: State.Error,
        error
      }
      return
    }

    player
      .load(track.src)
      .then(() => {
        this.current = track
        onLoaded?.(track)

        // Auto-play if playWhenReady is true
        if (this.playWhenReady) {
          this.play()
        }
      })
      .catch((err: unknown) => {
        this.onError(this.toNormalizedError(err))
      })
  }

  /**
   * NOTE: this method is sync despite the actual load being async. This
   * behavior is intentional as it mirrors what happens in Android. State
   * changes should be captured by event listeners.
   */
  public stop(onComplete?: () => void): void {
    const player = this.requirePlayer()

    this.current = undefined
    player
      .unload()
      .then(() => onComplete?.())
      .catch((err: unknown) => {
        console.error('Error unloading player:', err)
        onComplete?.() // Still call onComplete so callers aren't left hanging
      })
  }

  /**
   * NOTE: this method is sync despite the actual load being async. This
   * behavior is intentional as it mirrors what happens in Android. State
   * changes should be captured by event listeners.
   */
  public play(): void {
    const element = this.requireElement()
    this.playWhenReady = true

    if (this.state.state === State.Error && this.current) {
      this.load(this.current)
      return
    }

    element.play().catch((err: unknown) => console.error(err))
  }

  public retry(): void {
    const player = this.requirePlayer()
    player.retryStreaming()
  }

  public pause(): void {
    const element = this.requireElement()
    this.playWhenReady = false
    element.pause()
  }

  public togglePlayback(): void {
    this.requireElement()
    if (this.playWhenReady) {
      this.pause()
    } else {
      this.play()
    }
  }

  public setRate(rate: number): void {
    const element = this.requireElement()
    element.defaultPlaybackRate = rate
    element.playbackRate = rate
  }

  public getRate(): number {
    const element = this.requireElement()
    return element.playbackRate
  }

  public seekBy(offset: number): void {
    const element = this.requireElement()
    element.currentTime += offset
  }

  public seekTo(seconds: number): void {
    const element = this.requireElement()
    element.currentTime = seconds
  }

  public setVolume(volume: number): void {
    const element = this.requireElement()
    element.volume = volume
  }

  public getVolume(): number {
    const element = this.requireElement()
    return element.volume
  }

  public getProgress(): Progress {
    const element = this.requireElement()
    let buffered = 0
    if (element.buffered.length > 0) {
      buffered = element.buffered.end(element.buffered.length - 1)
    }
    return {
      position: element.currentTime,
      duration: element.duration || 0,
      buffered
    }
  }
}
