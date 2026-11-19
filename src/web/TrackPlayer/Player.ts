import type shaka from 'shaka-player/dist/shaka-player.compiled'
import type {
  Progress,
  NativeSetupPlayerOptions,
  Playback,
  PlaybackError,
  PlaybackState
} from '../../features'
import type { Track } from '../../types'
import type { PlaybackEvent } from './PlaybackStateMachine'
import { playbackErrorKind } from '../playbackErrorKind'
import { LoadAttempt } from './LoadAttempt'
import { nextPlaybackState } from './PlaybackStateMachine'
import { derivePlayingState } from './PlayingStateFactory'
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
  detail: ShakaError
}

interface ShakaError {
  code: number
  message: string
  /** Shaka's per-code payload; for BAD_HTTP_STATUS `data[1]` is the status. */
  data?: unknown[]
}

/** Per-load overrides that don't belong on the track itself. */
export interface LoadOptions {
  /**
   * The attempt this load belongs to, when a subclass began one before
   * resolving the URL. Internal plumbing: without it a direct `load()` is the
   * outermost call and starts its own, so two of them supersede each other.
   */
  attempt?: LoadAttempt
  /**
   * Whether this load is a re-prepare of the current item, when a subclass
   * captured {@link Player._repreparing} before an async stage and calls
   * `super.load()` after it, once the flag has been reset.
   */
  reprepare?: boolean
  /** Headers resolved from the media configuration, applied to Shaka's requests. */
  headers?: Record<string, string>
  /**
   * The URL to play, when it differs from `track.src` — a followed redirect,
   * say. Kept here rather than rewritten onto the track so the caller's own
   * identity for it survives the load.
   */
  src?: string
}

/** The mutable request Shaka hands to a networking-engine request filter. */
interface ShakaRequest {
  headers: Record<string, string>
}

/**
 * `shaka.util.Error.Code.LOAD_INTERRUPTED`. Inlined because shaka is imported
 * for types only here, as the codes in `playbackErrorKind.ts` are.
 */
const LOAD_INTERRUPTED = 7000

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
  protected _isStopped = false
  protected _loadInProgress = false
  protected _pendingSeek: number | undefined
  /**
   * Headers for the track currently loaded, applied by the request filter
   * installed in `setupPlayer`. On the instance rather than per request so the
   * manifest, its segments and any key requests all carry them.
   */
  protected mediaHeaders?: Record<string, string>

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
  async setupPlayer(_options: NativeSetupPlayerOptions = {}): Promise<void> {
    // shaka only runs in a browser
    if (typeof window === 'undefined') return
    // Re-setup reconfigures: web has no construction-bound engine options, so
    // there's nothing to rebuild — the caller re-applies options/state on top.
    if (this.hasInitialized === true) return

    const shaka = (await import('shaka-player/dist/shaka-player.compiled'))
      .default
    // Install built-in polyfills to patch browser incompatibilities.
    shaka.polyfill.installAll()
    // Check to see if the browser supports the basic APIs Shaka needs.
    if (!shaka.Player.isBrowserSupported()) {
      // This browser does not have the minimum set of APIs we need.
      this.state = {
        state: State.Error,
        error: {
          kind: 'unplayable',
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

    // Apply the media config's headers to Shaka's requests, as native does to
    // the AVURLAsset / ExoPlayer DataSpec. Without this they resolve and are
    // then dropped, and authenticated media 401s on web alone.
    player
      .getNetworkingEngine()
      ?.registerRequestFilter((_type: unknown, request: ShakaRequest) => {
        const headers = this.mediaHeaders
        if (!headers) return
        request.headers = { ...request.headers, ...headers }
      })

    // Listen for relevant events
    player.addEventListener('error', (event: Event) => {
      const errorEvent = event as ShakaErrorEvent
      this.onError(errorEvent.detail)
    })

    element.addEventListener('ended', () =>
      this.dispatch({ type: 'trackEndedNaturally' })
    )
    element.addEventListener('playing', () =>
      this.dispatch({ type: 'playing' })
    )
    element.addEventListener('pause', () => {
      // A track playing out fires `pause` and then `ended`. Only the `ended`
      // is meaningful — reporting `paused` first flashes a state Android never
      // reports, mid-queue or at the true end.
      if (element.ended) return
      this.dispatch({ type: 'paused', hasAsset: this.current !== undefined })
    })

    player.addEventListener('loading', () => {
      this.dispatch({ type: 'trackLoading' })
      // Every load passes through `buffering`, as ExoPlayer's always does via
      // STATE_BUFFERING. Shaka only fires its own `buffering` event when a load
      // actually stalls, so a load served from cache would otherwise skip a
      // state native always reports.
      this.dispatch({ type: 'waiting' })
    })
    player.addEventListener('loaded', () =>
      this.dispatch({
        type: 'loadSeekCompleted',
        playWhenReady: this.playWhenReady
      })
    )

    player.addEventListener('buffering', (event: Event) => {
      const bufferingEvent = event as ShakaBufferingEvent
      this.dispatch(
        bufferingEvent.detail.buffering === true
          ? { type: 'waiting' }
          : {
              type: 'bufferingSufficient',
              playWhenReady: this.playWhenReady
            }
      )
    })

    // Attach player to the window to make it easy to access in the JS console.
    if (__DEV__) {
      window.rnab = this.player
    }

    this.hasInitialized = true
  }

  /**
   * Routes a racy element/Shaka observation through the state machine. The
   * machine decides the next state (or suppresses the transition); commands
   * (stop, error) set their terminal state directly instead.
   */
  protected dispatch(event: PlaybackEvent): void {
    // Ignore element/Shaka events while stopped. This is broader than the
    // machine's own stopped guards on purpose: native engines are torn down on
    // stop so they emit nothing, but Shaka's unload() emits spurious events
    // (pause, buffering) that would otherwise clobber the stopped state.
    if (this._isStopped) return
    const next = nextPlaybackState(this.state.state, event)
    if (next !== null) this.applyState(next)
  }

  /**
   * Applies a machine-decided state. Overridable so subclasses can react to
   * specific transitions (e.g. QueuePlayer advancing the queue on `ended`).
   */
  protected applyState(state: PlaybackState): void {
    this.state = { state }
  }

  private toNormalizedError(err: unknown): ShakaError {
    if (
      typeof err === 'object' &&
      err !== null &&
      'code' in err &&
      typeof (err as Record<string, unknown>).code === 'number'
    ) {
      const e = err as ShakaError
      return {
        code: e.code,
        message: e.message ?? 'Unknown error',
        data: e.data
      }
    }

    const message = err instanceof Error ? err.message : String(err)
    return { code: -1, message }
  }

  protected onError(shakaError: ShakaError): void {
    // An interrupted load is not a failure. Shaka raises it when a `load()` is
    // cut short by a newer `load()` or an `unload()` — always something we
    // asked for — and names it accordingly: `createAbortLoadError_()`. Its own
    // code skips its cleanup unload for this code too (player.js:2010).
    //
    // Recognising it here rather than pre-emptively invalidating the load keeps
    // the two independent: any teardown, in any order, can abort a load without
    // the abort reaching a consumer as an error. Eagerly unloading to stop the
    // outgoing track used to flash an error banner on every fast track switch.
    if (shakaError.code === LOAD_INTERRUPTED) {
      console.debug('Load interrupted by a newer load or an unload')
      return
    }

    // Before the unload below resets it: a retry resumes from where playback
    // failed, as native does.
    this.rememberResumePosition()

    // unload the current track to allow for clean playback on other
    this.player?.unload().catch((err) => {
      console.error(`Error unloading player on 'onError'`, err)
    })

    const status = shakaError.data?.[1]
    const httpStatus = typeof status === 'number' ? status : undefined
    const error: PlaybackError = {
      kind: playbackErrorKind(
        shakaError.code,
        httpStatus,
        // `navigator` is absent when this runs outside a browser (SSR, tests).
        typeof navigator === 'undefined' || navigator.onLine
      ),
      code: shakaError.code.toString(),
      message: shakaError.message,
      statusCode: httpStatus
    }

    this.state = {
      state: State.Error,
      error
    }

    // Log the error.
    console.debug('Error code', shakaError.code, 'object', shakaError)
  }

  /**
   * The load attempt that owns the player, or none. See {@link LoadAttempt}.
   */
  private _attempt: LoadAttempt | undefined

  /**
   * Starts an attempt, superseding whatever was in flight.
   *
   * Called by the outermost `load()` so supersession is registered when the
   * caller asks for it, not when some inner stage happens to reach it.
   */
  protected beginLoadAttempt(): LoadAttempt {
    this._attempt?.cancel()
    const attempt = new LoadAttempt()
    this._attempt = attempt
    return attempt
  }

  /** Abandons the attempt in flight without starting another — `stop()`. */
  protected cancelLoadAttempt(): void {
    this._attempt?.cancel()
    this._attempt = undefined
  }

  public load(
    track: Track,
    onLoaded?: (track: Track) => void,
    options?: LoadOptions
  ): void {
    const player = this.requirePlayer()
    this._isStopped = false
    this._loadInProgress = true
    const headers = options?.headers
    this.mediaHeaders =
      headers && Object.keys(headers).length > 0 ? headers : undefined
    // What Shaka plays may differ from what the caller queued; `track` is left
    // alone so `current` and `getActiveTrack()` keep the caller's identity for
    // it rather than a transport detail.
    const playbackSrc = options?.src ?? track.src
    // Current on the attempt, not on success — native derives the active track
    // from the queue, so a failed load is still the active track there and its
    // error has somewhere to show. `getActiveTrackIndex()` already reported the
    // queue's index either way; this makes the pair agree.
    this.current = track
    // Only a re-prepare carries the remembered position, and `reprepare()`
    // has already read it by now.
    if (!(options?.reprepare ?? this._repreparing)) this._resume = undefined
    // The attempt a subclass began before resolving the URL, so its resolution
    // stage and both continuations below share one token. Passed rather than
    // read off the instance: a direct `load()` is itself the outermost call and
    // must start a fresh attempt, superseding whatever was in flight.
    const attempt = options?.attempt ?? this.beginLoadAttempt()

    if (!playbackSrc) {
      this._loadInProgress = false
      this._pendingSeek = undefined
      const error: PlaybackError = {
        kind: 'unplayable',
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
      .load(playbackSrc)
      .then(() => {
        if (!attempt.isCurrent) return
        this._loadInProgress = false
        onLoaded?.(track)

        // Execute any pending seek that arrived during loading
        if (this._pendingSeek !== undefined) {
          this.requireElement().currentTime = this._pendingSeek
          this._pendingSeek = undefined
        }

        // Auto-play if playWhenReady is true
        if (this.playWhenReady) {
          this.play()
        }
      })
      .catch((err: unknown) => {
        if (!attempt.isCurrent) return
        this._loadInProgress = false
        this._pendingSeek = undefined
        this.onError(this.toNormalizedError(err))
      })
  }

  public stop(onComplete?: () => void): void {
    const player = this.requirePlayer()

    // Whatever was loading no longer owns the player: its continuations, at
    // either boundary, must not revive it.
    this.cancelLoadAttempt()

    // Match Android: stop sets playWhenReady=false and state=stopped,
    // but keeps the current track so play() can resume — from where it
    // stopped, which ExoPlayer preserves across its own stop/prepare pair.
    this.rememberResumePosition()
    this._isStopped = true
    this._loadInProgress = false
    this._pendingSeek = undefined
    this.playWhenReady = false
    this.state = { state: State.Stopped }

    player
      .unload()
      .then(() => onComplete?.())
      .catch((err: unknown) => {
        console.error('Error unloading player:', err)
        onComplete?.() // Still call onComplete so callers aren't left hanging
      })
  }

  /**
   * Where playback was when it failed or was stopped, so a re-prepare can
   * resume rather than restart.
   *
   * Kept against the track it belongs to rather than as a bare number, so it
   * self-invalidates when a different track becomes current and cannot be
   * applied to the wrong one. A fresh `load()` clears it; a re-prepare keeps
   * it, so a retry that fails still knows where the next one resumes.
   */
  private _resume: { track: Track; position: number } | undefined

  /**
   * Whether the load in progress is a re-prepare of the current item rather
   * than a new one — set by {@link reloadCurrent} for the duration of its
   * `load()` call. A re-prepare keeps the remembered position; every other
   * load resets it.
   */
  protected _repreparing = false

  /**
   * Records the current position, when there is one worth recording.
   *
   * A zero is never recorded: `onError` runs for a *load* failure too, where
   * the element has just been torn down and reads 0, and overwriting a good
   * position with that made a failed retry lose the place a successful one
   * would have resumed from.
   */
  private rememberResumePosition(): void {
    const position = this.element?.currentTime
    const track = this.current
    if (!track || typeof position !== 'number' || position <= 0) return
    this._resume = { track, position }
  }

  /**
   * Android's `prepare()`, which both `play()` and `retry()` call.
   *
   * It early-returns unless ExoPlayer is STATE_IDLE, so it reconnects after an
   * error or a stop and never re-buffers a healthy stream; `error`/`_isStopped`
   * is web's equivalent of idle. Re-preparing re-resolves the URL, which is the
   * whole point — a signed URL or token may have expired since the failure, and
   * replaying the cached one just fails again. iOS says the same thing in its
   * own `retry()`: "Re-resolve rather than replay the cached URL".
   *
   * Returns whether it handled the call, so `play()` knows not to also poke the
   * element.
   */
  protected reprepare(): boolean {
    if (!this.current) return false
    if (this.state.state !== State.Error && !this._isStopped) return false

    // Not for live: `currentTime` there is elapsed time on a connection that no
    // longer exists, and a fresh connection already *is* the live edge. Seeking
    // to the old value asks for a point the new stream does not have. The
    // track's own `live` declaration is the gate, as it is in `seekToLiveEdge`
    // and on both native platforms.
    const remembered =
      this._resume?.track === this.current ? this._resume.position : undefined
    const resume = this.current.live === true ? undefined : remembered

    this.reloadCurrent(resume)
    return true
  }

  public play(): void {
    const element = this.requireElement()
    this.playWhenReady = true

    // Matches Android's play(): set the intent, then prepare — which does
    // something only when idle, i.e. after an error or a stop.
    if (this.reprepare()) return

    element.play().catch((err: unknown) => this.onPlayRejected(err))
  }

  /**
   * A rejected `element.play()`. Web-only: neither native platform can refuse
   * a play intent, so there is no parity target here.
   *
   * `AbortError` means a newer load interrupted this one — that load owns the
   * state and the intent stands. So does a rejection while a load is still in
   * flight: `load()` then `play()` is the library's own sequence, and until
   * Shaka has a source the element may refuse (Firefox rejects a source-less
   * element with `NotSupportedError`; Chrome leaves it pending). The load
   * calls `play()` again on success, and a real refusal repeats there.
   * Anything else, autoplay policy above all, is the browser refusing: the
   * intent cannot be honoured, so it is cleared and the state settles rather
   * than stranding on `loading`/`buffering`, which is where suppressing
   * `ready` under `playWhenReady` would otherwise leave it.
   */
  private onPlayRejected(err: unknown): void {
    console.error(err)
    const name =
      typeof err === 'object' && err !== null && 'name' in err
        ? (err as { name?: unknown }).name
        : undefined
    if (name === 'AbortError' || this._loadInProgress) return

    this.playWhenReady = false
    this.dispatch({ type: 'paused', hasAsset: this.current !== undefined })
  }

  /**
   * The track a reload re-feeds. `current` holds it as the caller queued it —
   * `load()` takes the resolved URL separately, via `LoadOptions.src` — so
   * re-feeding it is safe.
   *
   * The overridable part of {@link reloadCurrent}, rather than the whole
   * method: a subclass backed by a queue changes only where the track comes
   * from, and the reload itself stays in one place.
   */
  protected trackToReload(): Track | undefined {
    return this.current
  }

  /** Reloads the track {@link trackToReload} names, resuming if asked to. */
  protected reloadCurrent(resumePosition?: number): void {
    const track = this.trackToReload()
    if (!track) return
    this._repreparing = true
    try {
      this.load(
        track,
        resumePosition === undefined
          ? undefined
          : () => this.seekTo(resumePosition)
      )
    } finally {
      this._repreparing = false
    }
  }

  /**
   * Android's `retry()` is `player.prepare()` — the same call `play()` makes,
   * without setting the intent. So this is `play()` minus the intent.
   *
   * It is emphatically not `shaka.Player.retryStreaming()`, which this used to
   * call: that returns false and does nothing at all unless the load mode is
   * MEDIA_SOURCE, so for a progressive file — the case a signed URL is for — it
   * was a silent no-op, while the `play()` the same docs offer as the
   * alternative recovered properly.
   */
  public retry(): void {
    this.requirePlayer()
    this.reprepare()
  }

  public pause(): void {
    const element = this.requireElement()
    this.playWhenReady = false
    element.pause()
  }

  public togglePlayback(): void {
    this.requireElement()
    if (derivePlayingState(this.playWhenReady, this.state.state).playing) {
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
    if (this._loadInProgress) {
      this._pendingSeek = (this._pendingSeek ?? 0) + offset
      return
    }
    const element = this.requireElement()
    element.currentTime += offset
  }

  public seekTo(seconds: number): void {
    if (this._loadInProgress) {
      this._pendingSeek = seconds
      return
    }
    const element = this.requireElement()
    element.currentTime = seconds
  }

  public seekToLiveEdge(): void {
    if (this.current?.live !== true) return
    const element = this.requireElement()
    // Live with a seekable window (HLS): jump to the window end. Without one
    // (non-seekable stream): reload to reconnect at the live edge.
    const { seekable } = element
    const end = seekable.length > 0 ? seekable.end(seekable.length - 1) : 0
    if (end > 0) {
      element.currentTime = end
    } else {
      this.reloadCurrent()
    }
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
