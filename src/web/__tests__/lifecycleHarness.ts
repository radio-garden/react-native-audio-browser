import { vi } from 'vitest'
import type { Track } from '../../types'
import { NativeAudioBrowser } from '../NativeAudioBrowser'

/**
 * A recording harness for the web player's event lifecycle.
 *
 * Unlike the other web tests, this does **not** stub `this.player` /
 * `this.element` — it runs the real `setupPlayer()`, so the real listeners,
 * state machine and emitters are all wired up. Only the two *sources* are
 * modelled: Shaka, via the mock installed below, and the media element's own
 * events, which the harness fires explicitly.
 *
 * The model is not a browser. Every behaviour it reproduces is one verified in
 * `shaka-player` 4.16.15's source, cited at the point it is modelled, so the
 * recordings are only as true as those citations. A real-browser run is still
 * the final word — see `lifecycle.test.ts` for which recordings are waiting on
 * one.
 */

/** Events the harness records, in emission order. */
export type Recorded = string

export class FakeShakaPlayer {
  private listeners = new Map<string, ((event: unknown) => void)[]>()
  private assetUri: string | undefined
  /** Set by the harness so `unload()` can reproduce the element teardown. */
  onTeardown: (() => void) | undefined
  private pendingLoad: (() => void) | undefined
  private rejectLoad: ((reason: unknown) => void) | undefined
  private failNextLoad = false
  /** Every URL `load()` was called with, in order. */
  readonly loadedUrls: string[] = []

  static isBrowserSupported(): boolean {
    return true
  }

  attach(): Promise<void> {
    return Promise.resolve()
  }

  getNetworkingEngine(): { registerRequestFilter: () => void } {
    return { registerRequestFilter: () => {} }
  }

  addEventListener(type: string, cb: (event: unknown) => void): void {
    const existing = this.listeners.get(type) ?? []
    existing.push(cb)
    this.listeners.set(type, existing)
  }

  emit(type: string, detail?: unknown): void {
    for (const cb of this.listeners.get(type) ?? []) cb({ type, detail })
  }

  /**
   * Mirrors `shaka.Player.load` (player.js:1717+) in the two respects that
   * matter to our listeners:
   *
   * 1. it unloads the previous asset first when one is loaded —
   *    `if (this.assetUri_) { await this.unload(false) }` at :1757-1762;
   * 2. it dispatches `loading` *after* that, at :1807, behind several awaits —
   *    which is why the event never arrives synchronously.
   */
  async load(uri: string): Promise<void> {
    this.loadedUrls.push(uri)
    if (this.assetUri !== undefined) await this.unload()
    this.assetUri = uri
    this.emit('loading')
    if (this.failNextLoad) throw new Error('401')
    // Shaka's load() promise settles once the media is ready, i.e. after the
    // buffering/loaded events — `finishLoad()` drives that.
    await new Promise<void>((resolve, reject) => {
      this.pendingLoad = resolve
      this.rejectLoad = reject
    })
  }

  finishLoad(): void {
    const resolve = this.pendingLoad
    this.pendingLoad = undefined
    this.rejectLoad = undefined
    resolve?.()
  }

  failLoads(): void {
    this.failNextLoad = true
  }

  succeedLoads(): void {
    this.failNextLoad = false
  }

  /** Raise a Shaka error, as a mid-playback failure would. */
  raiseError(): void {
    this.emit('error', { code: 3016, message: 'playback failed' })
  }

  /**
   * Mirrors `unload()` (player.js:1445+). For `src=` content it clears the
   * element and re-runs its load algorithm (:1603-1610 via
   * `Dom.clearSourceFromVideo`).
   *
   * Verified in a real browser: that teardown fires `emptied` and `loadstart`
   * — **not** `pause`, even with the element mid-playback. Reading the HTML
   * load algorithm suggested otherwise; the browser disagreed. Nothing here
   * dispatches an element event as a result.
   */
  async unload(): Promise<void> {
    // Real Shaka aborts an in-flight load() when unload() is called, rejecting
    // it with LOAD_INTERRUPTED (7000). Modelled because a caller that unloads
    // eagerly must invalidate that load first, or its rejection surfaces as a
    // playback error.
    const reject = this.rejectLoad
    this.rejectLoad = undefined
    this.pendingLoad = undefined
    reject?.({ code: 7000, message: 'LOAD_INTERRUPTED' })

    this.assetUri = undefined
    this.onTeardown?.()
    return Promise.resolve()
  }

  retryStreaming(): void {}
}

vi.mock('shaka-player/dist/shaka-player.compiled', () => ({
  default: {
    polyfill: { installAll: () => {} },
    Player: FakeShakaPlayer
  }
}))

export class LifecycleHarness {
  readonly browser = new NativeAudioBrowser()
  readonly recorded: Recorded[] = []
  private element!: HTMLMediaElement
  private shaka!: FakeShakaPlayer
  private elementPlaying = false

  async setup(): Promise<void> {
    // `setupPlayer` attaches the Shaka instance to `window` under __DEV__,
    // which the bundler defines and the test environment does not.
    ;(globalThis as unknown as { __DEV__: boolean }).__DEV__ = false

    await this.browser.setupPlayer({})

    const browser = this.browser as unknown as {
      element: HTMLMediaElement
      player: FakeShakaPlayer
    }
    this.element = browser.element
    this.shaka = browser.player

    // happy-dom's media element has no TimeRanges; `getProgress()` reads it.
    Object.defineProperty(this.element, 'buffered', {
      value: { length: 0, end: () => 0 },
      configurable: true
    })
    Object.defineProperty(this.element, 'duration', {
      value: 100,
      configurable: true
    })
    Object.defineProperty(this.element, 'ended', {
      value: false,
      configurable: true
    })
    Object.defineProperty(this.element, 'currentTime', {
      value: 0,
      writable: true,
      configurable: true
    })
    Object.defineProperty(this.element, 'seekable', {
      value: { length: 0, end: () => 0 },
      configurable: true
    })
    // happy-dom's play()/pause() do not fire media events, so the harness
    // fires them where a browser would.
    this.element.play = () => {
      this.startedPlaying()
      return Promise.resolve()
    }
    this.element.pause = () => {
      this.pausedByCommand()
    }

    // No element event on teardown — see FakeShakaPlayer.unload(). It does
    // reset the position though: clearing `src` and re-running the element's
    // load algorithm puts `currentTime` back to 0, which is why a resume has
    // to be remembered before the teardown rather than read after it.
    this.shaka.onTeardown = () => {
      this.elementPlaying = false
      this.setPosition(0)
    }

    this.record()
  }

  private record(): void {
    const push = (label: string) => this.recorded.push(label)
    this.browser.onPlaybackChanged = (p) => push(`state:${p.state}`)
    this.browser.onPlaybackActiveTrackChanged = () => push('activeTrack')
    this.browser.onNowPlayingChanged = () => push('nowPlaying')
    this.browser.onPlaybackQueueChanged = () => push('queue')
    this.browser.onPlaybackQueueEnded = () => push('queueEnded')
    this.browser.onPlaybackError = (e) =>
      push(e.error ? `error:${e.error.code}` : 'errorCleared')
    this.browser.onPlaybackPlayingState = (s) =>
      push(`playing:${s.playing}|buffering:${s.buffering}`)
    this.browser.onPlaybackPlayWhenReadyChanged = (e) =>
      push(`playWhenReady:${e.playWhenReady}`)
  }

  clear(): void {
    this.recorded.length = 0
  }

  private fireElement(type: string): void {
    this.element.dispatchEvent(new Event(type))
  }

  private startedPlaying(): void {
    this.elementPlaying = true
    this.fireElement('playing')
  }

  private pausedByCommand(): void {
    if (!this.elementPlaying) return
    this.elementPlaying = false
    this.fireElement('pause')
  }

  /**
   * The rest of a successful Shaka load: `loaded`, then the `load()` promise
   * settles, which is what lets the library's own `playWhenReady` handling
   * start playback.
   *
   * Verified in a real browser: a load that does not stall emits **no**
   * `buffering` event, so the PlaybackState goes `loading → ready → playing`
   * with no `buffering` in it. Use {@link rebuffer} for the stalling case.
   */
  async completeLoad(): Promise<void> {
    this.shaka.emit('loaded')
    this.shaka.finishLoad()
    await this.flush()
  }

  /** A mid-playback stall, which is where Shaka's `buffering` event does fire. */
  async rebuffer(): Promise<void> {
    this.shaka.emit('buffering', { buffering: true })
    await this.flush()
    this.shaka.emit('buffering', { buffering: false })
    await this.flush()
  }

  /** Shaka rejecting the load, as a 401 would. */
  failLoad(): void {
    this.shaka.failLoads()
  }

  /** Stop rejecting loads, so a retry can succeed. */
  succeedLoads(): void {
    this.shaka.succeedLoads()
  }

  /** Resolves Shaka's pending load() without emitting its events. */
  finishLoadOnly(): void {
    this.shaka.finishLoad()
  }

  /** A mid-playback failure, rather than one raised by the load itself. */
  raiseError(): void {
    this.shaka.raiseError()
  }

  /** Every URL handed to Shaka's `load()`, in order. */
  get loadedUrls(): string[] {
    return this.shaka.loadedUrls
  }

  setPosition(seconds: number): void {
    Object.defineProperty(this.element, 'currentTime', {
      value: seconds,
      writable: true,
      configurable: true
    })
  }

  get currentTime(): number {
    return this.element.currentTime
  }

  /**
   * A track playing out. Verified in a real browser: the element fires `pause`
   * and then `ended`. `ended` is already true on the element by then — it is
   * the ended-playback condition that triggers both — which is what the pause
   * listener keys off to tell this apart from a real pause.
   */
  endTrack(): void {
    this.elementPlaying = false
    Object.defineProperty(this.element, 'ended', {
      value: true,
      configurable: true
    })
    this.fireElement('pause')
    this.fireElement('ended')
  }

  flush(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0))
  }
}

/**
 * A `media.resolve` whose every call hangs until released, so two loads can be
 * interleaved deliberately — the only way to exercise what happens to a load
 * that a newer one supersedes mid-resolution.
 */
export function deferredResolver(): {
  configuration: { media: { resolve: () => Promise<{ path: string }> } }
  /** Releases the nth call (1-based) with its own distinct URL. */
  release: (n: number) => void
  calls: () => number
} {
  const pending: Array<(value: { path: string }) => void> = []
  return {
    configuration: {
      media: {
        resolve: () =>
          new Promise<{ path: string }>((resolve) => {
            pending.push(resolve)
          })
      }
    },
    release: (n) =>
      pending[n - 1]?.({ path: `https://cdn.example.com/${n}.mp3` }),
    calls: () => pending.length
  }
}

export const trackA: Track = {
  id: 'a',
  title: 'Track A',
  src: 'https://media.example.com/a.mp3'
}
export const trackB: Track = {
  id: 'b',
  title: 'Track B',
  src: 'https://media.example.com/b.mp3'
}
