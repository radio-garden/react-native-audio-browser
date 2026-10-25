import type {
  PlaybackErrorEvent,
  PlaybackError,
  RepeatMode as RepeatModeType,
  PlaybackProgressUpdatedEvent,
  PlaybackQueueEndedEvent,
  PlayingState,
  RepeatModeChangedEvent,
  NativeUpdateOptions,
  Options,
  Playback,
  PlaybackActiveTrackChangedEvent,
  ChapterMetadata,
  TrackMetadata,
  TimedMetadata,
  PlaybackPlayWhenReadyChangedEvent,
  RemoteJumpBackwardEvent,
  RemoteJumpForwardEvent,
  RemotePlayIdEvent,
  RemotePlaySearchEvent,
  RemoteSeekEvent,
  RemoteSkipEvent,
  SleepTimer,
  SleepTimerChangedEvent,
  FavoriteChangedEvent,
  NativeGate,
  NativeGateRequest,
  GateDecision,
  GateEvent,
  NavigationError,
  NavigationErrorEvent,
  FormattedNavigationError,
  NowPlayingMetadata,
  NowPlayingUpdate,
  EqualizerSettings,
  BatteryOptimizationStatus,
  BatteryOptimizationStatusChangedEvent,
  BatteryWarningPendingChangedEvent,
  NativeSetupPlayerOptions
} from '../features'
import type {
  AudioBrowser as AudioBrowserSpec,
  Output
} from '../specs/audio-browser.nitro'
import type {
  ResolvedTrack,
  Track,
  TrackLoadEvent,
  TransformableRequestConfig
} from '../types'
import type { NativeBrowserConfiguration } from '../types/browser-native'
import { getTrackIdentity } from '../utils/getTrackIdentity'
import { BrowserManager } from './browser/BrowserManager'
import { classifyTrackNavigation } from './browser/classifyTrackNavigation'
import { FavoriteManager } from './browser/FavoriteManager'
import { NavigationErrorManager } from './browser/NavigationErrorManager'
import { SearchManager } from './browser/SearchManager'
import { followMediaRedirect } from './http/followMediaRedirect'
import { HttpClient } from './http/HttpClient'
import {
  RequestConfigBuilder,
  type ResolvedMediaRequest
} from './http/RequestConfigBuilder'
import { NowPlayingManager } from './player/NowPlayingManager'
import { OptionsManager } from './player/OptionsManager'
import { RemoteCommandController } from './player/RemoteCommandController'
import { QueuePlayer, SleepTimerManager, VolumeFader } from './TrackPlayer'
import { PlaybackTimer } from './TrackPlayer/PlaybackTimer'
import { derivePlayingState } from './TrackPlayer/PlayingStateFactory'
import { BrowserPathHelper } from './util/BrowserPathHelper'

/**
 * Web implementation of AudioBrowser (unified browser + player)
 */
export class NativeAudioBrowser
  extends QueuePlayer
  implements AudioBrowserSpec
{
  // HybridObject stuff
  readonly name = 'WebAudioBrowser'
  equals() {
    return true
  }
  dispose() {
    this.clearUpdateEventInterval()
    this.setPlaybackIntervalEnabled(false)
    this.remoteCommands.dispose()
    // Remove window event listeners to prevent memory leaks
    if (typeof window !== 'undefined' && this.onlineHandler) {
      window.removeEventListener('online', this.onlineHandler)
    }
    if (typeof window !== 'undefined' && this.offlineHandler) {
      window.removeEventListener('offline', this.offlineHandler)
    }
  }

  // Managers
  private httpClient: HttpClient
  private browserManager: BrowserManager
  private favoriteManager: FavoriteManager
  private navigationErrorManager: NavigationErrorManager
  private searchManager: SearchManager
  private optionsManager: OptionsManager
  private nowPlayingManager: NowPlayingManager
  private remoteCommands: RemoteCommandController

  // Player state
  private progressTimer = new PlaybackTimer()
  private intervalTimer = new PlaybackTimer()
  private _online: boolean =
    typeof navigator !== 'undefined' ? navigator.onLine : true
  private onlineHandler: (() => void) | undefined
  private offlineHandler: (() => void) | undefined
  private sleepFader = new VolumeFader(
    () => this.getVolume(),
    (volume) => this.setVolume(volume)
  )
  private sleepTimer = new (class extends SleepTimerManager {
    constructor(private parent: NativeAudioBrowser) {
      super()
    }
    protected onComplete(): void {
      console.log('Sleep timer completed, pausing playback')
      this.parent.sleepFader.resolve(() => this.parent.pause())
      this.parent.onSleepTimerChanged(null)
    }
    protected onFadeStart(durationSeconds: number): void {
      this.parent.sleepFader.start(durationSeconds)
    }
    protected onFadeCancel(): void {
      this.parent.sleepFader.cancel(true)
    }
  })(this)

  // MARK: Browser properties
  get path(): string | undefined {
    return this.browserManager.path
  }

  set path(value: string | undefined) {
    this.browserManager.path = value
  }

  get tabs(): Track[] | undefined {
    return this.browserManager.tabs
  }

  set tabs(_value: Track[] | undefined) {
    // tabs are set internally via configuration
  }

  get configuration(): NativeBrowserConfiguration {
    return this.browserManager.configuration
  }

  set configuration(value: NativeBrowserConfiguration) {
    this.browserManager.configuration = value
  }

  // MARK: Browser event callbacks
  onPathChanged: (path: string) => void = () => {}
  onContentChanged: (content: ResolvedTrack | undefined) => void = () => {}
  onTabsChanged: (tabs: Track[]) => void = () => {}
  onNavigationError: (data: NavigationErrorEvent) => void = () => {}
  onFormattedNavigationError: (
    formattedError: FormattedNavigationError | undefined
  ) => void = () => {}

  // MARK: Player event callbacks
  onChapterMetadata: (chapters: ChapterMetadata[]) => void = () => {}
  onTrackMetadata: (metadata: TrackMetadata) => void = () => {}
  onTimedMetadata: (metadata: TimedMetadata) => void = () => {}
  onPlaybackActiveTrackChanged: (
    data: PlaybackActiveTrackChangedEvent
  ) => void = () => {}
  onPlaybackError: (data: PlaybackErrorEvent) => void = () => {}
  onPlaybackPlayWhenReadyChanged: (
    data: PlaybackPlayWhenReadyChangedEvent
  ) => void = () => {}
  onPlaybackPlayingState: (data: PlayingState) => void = () => {}
  onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) => void =
    () => {}
  onPlaybackInterval: () => void = () => {}
  onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) => void = () => {}
  onPlaybackQueueChanged: (queue: Track[]) => void = () => {}
  onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) => void = () => {}
  onPlaybackShuffleModeChanged: (enabled: boolean) => void = () => {}
  onSleepTimerChanged: (data: SleepTimerChangedEvent) => void = () => {}
  onPlaybackChanged: (data: Playback) => void = () => {}
  onRemoteJumpBackward: (event: RemoteJumpBackwardEvent) => void = () => {}
  onRemoteJumpForward: (event: RemoteJumpForwardEvent) => void = () => {}
  onRemoteNext: () => void = () => {}
  onRemotePause: () => void = () => {}
  onRemotePlay: () => void = () => {}
  onRemotePlayId: (event: RemotePlayIdEvent) => void = () => {}
  onRemotePlaySearch: (event: RemotePlaySearchEvent) => void = () => {}
  onRemotePrevious: () => void = () => {}
  onRemoteSeek: (event: RemoteSeekEvent) => void = () => {}
  onRemoteSkip: (event: RemoteSkipEvent) => void = () => {}
  onRemoteStop: () => void = () => {}
  onOptionsChanged: (event: Options) => void = () => {}
  onFavoriteChanged: (event: FavoriteChangedEvent) => void = () => {}
  onNowPlayingChanged: (metadata: NowPlayingMetadata) => void = () => {}
  onOnlineChanged: (online: boolean) => void = () => {}
  onEqualizerChanged: (settings: EqualizerSettings) => void = () => {}
  onBatteryWarningPendingChanged: (
    event: BatteryWarningPendingChangedEvent
  ) => void = () => {}
  onBatteryOptimizationStatusChanged: (
    event: BatteryOptimizationStatusChangedEvent
  ) => void = () => {}
  onSystemVolumeChanged: (volume: number) => void = () => {}
  onOutputChanged: (output: Output) => void = () => {}
  onGate: (event: GateEvent) => void = () => {}
  // Fail closed by default: only reachable in the init window before gate.ts
  // re-binds resolveGate. Web has no serve sites, so this is never called in
  // practice, but the default must agree with the fail-closed contract.
  resolveGate: (request: NativeGateRequest) => Promise<GateDecision> =
    async () => ({ gated: true })
  onCarConnectedChanged: (connected: boolean) => void = () => {}

  // MARK: Remote handlers
  handleRemoteJumpBackward:
    | ((event: RemoteJumpBackwardEvent) => void)
    | undefined = undefined
  handleRemoteJumpForward:
    | ((event: RemoteJumpForwardEvent) => void)
    | undefined = undefined
  handleRemoteNext: (() => void) | undefined = undefined
  handleRemotePause: (() => void) | undefined = undefined
  handleRemotePlay: (() => void) | undefined = undefined
  handleRemotePlayId: ((event: RemotePlayIdEvent) => void) | undefined =
    undefined
  handleRemotePlaySearch: ((event: RemotePlaySearchEvent) => void) | undefined =
    undefined
  handleRemotePrevious: (() => void) | undefined = undefined
  handleRemoteSeek: ((event: RemoteSeekEvent) => void) | undefined = undefined
  handleRemoteSkip: (() => void) | undefined = undefined
  handleRemoteStop: (() => void) | undefined = undefined

  // MARK: Constructor
  constructor() {
    super()

    // Initialize managers
    this.httpClient = new HttpClient()
    this.favoriteManager = new FavoriteManager()
    this.navigationErrorManager = new NavigationErrorManager()
    this.optionsManager = new OptionsManager()
    this.nowPlayingManager = new NowPlayingManager()
    this.remoteCommands = new RemoteCommandController(this)

    this.browserManager = new BrowserManager(
      this.httpClient,
      this.favoriteManager,
      this.navigationErrorManager
    )

    this.searchManager = new SearchManager(this.browserManager)

    // Wire up event callbacks from managers to class callbacks
    this.browserManager.onPathChanged = (path) => this.onPathChanged(path)
    this.browserManager.onContentChanged = (content) =>
      this.onContentChanged(content)
    this.browserManager.onTabsChanged = (tabs) => this.onTabsChanged(tabs)
    this.navigationErrorManager.onNavigationError = (data) =>
      this.onNavigationError(data)
    this.navigationErrorManager.onFormattedNavigationError = (error) =>
      this.onFormattedNavigationError(error)
    this.optionsManager.onOptionsChanged = (options) =>
      this.onOptionsChanged(options)
    this.nowPlayingManager.onNowPlayingChanged = (metadata) =>
      this.publishNowPlaying(metadata)

    // Setup online/offline listeners
    if (typeof window !== 'undefined') {
      this.onlineHandler = () => {
        this._online = true
        this.onOnlineChanged(true)
      }
      this.offlineHandler = () => {
        this._online = false
        this.onOnlineChanged(false)
      }
      window.addEventListener('online', this.onlineHandler)
      window.addEventListener('offline', this.offlineHandler)
    }
  }

  // Override state setter to emit events
  protected get state(): Playback {
    return super.state
  }

  protected set state(newState: Playback) {
    const oldState = super.state
    const didStateChange = newState.state !== oldState.state
    const didErrorChange =
      newState.state === 'error' && oldState.state === 'error'
        ? newState.error?.code !== oldState.error?.code ||
          newState.error?.message !== oldState.error?.message
        : false

    super.state = newState

    if (!didStateChange && !didErrorChange) {
      return
    }

    // Call callbacks
    this.onPlaybackChanged(newState)
    this.refreshPlayingState()
    this.remoteCommands.syncPlaybackState()

    if (newState.state === 'error' && newState.error) {
      this.onPlaybackError({ error: newState.error })
    } else if (oldState.state === 'error') {
      // Leaving the error state clears it, as it does natively. The getter
      // already reads undefined — consumers subscribe to the event, so without
      // this a stale error outlives the next successful load.
      this.onPlaybackError({ error: undefined })
    }
  }

  private lastPlayingState?: PlayingState

  /**
   * Re-derives the playing state and emits on change. Called from both of its
   * inputs' change points — the state setter and the playWhenReady setter —
   * with a dedupe so identical derivations don't double-emit (parity with
   * Android's refreshPlayingState / iOS's Player.playingState).
   */
  private refreshPlayingState(): void {
    const next = derivePlayingState(this._playWhenReady, this.state.state)
    if (
      this.lastPlayingState !== undefined &&
      this.lastPlayingState.playing === next.playing &&
      this.lastPlayingState.buffering === next.buffering
    ) {
      return
    }
    this.lastPlayingState = next
    this.onPlaybackPlayingState(next)
  }

  /**
   * Emits now-playing metadata to JS consumers and mirrors it to the OS media
   * controls (lockscreen / notification / media keys).
   */
  private publishNowPlaying(metadata: NowPlayingMetadata): void {
    this.onNowPlayingChanged(metadata)
    this.remoteCommands.setMetadata(metadata)
  }

  protected setupProgressUpdates(interval?: number) {
    // Match Android: emit progress during loading, buffering, and playing.
    this.progressTimer.start(
      (interval ?? 0) * 1000,
      () => {
        const state = this.state.state
        return (
          state === 'playing' || state === 'loading' || state === 'buffering'
        )
      },
      () => {
        const progress = this.getProgress()
        this.onPlaybackProgressUpdated({
          ...progress,
          track: this.queue.currentIndex || 0
        })
        this.remoteCommands.updateProgress()
      }
    )
  }

  protected clearUpdateEventInterval() {
    this.progressTimer.stop()
  }

  setPlaybackIntervalEnabled(enabled: boolean): void {
    this.intervalTimer.start(
      enabled ? 1000 : 0,
      () => this.state.state === 'playing',
      () => this.onPlaybackInterval()
    )
  }

  protected onQueueEnded() {
    super.onQueueEnded()
    this.onPlaybackQueueEnded({
      track: this.queue.currentIndex ?? 0,
      position: this.element?.currentTime ?? 0
    })
  }

  // MARK: Browser API
  navigatePath(path: string): void {
    void this.browserManager.navigatePath(path)
  }

  navigateTrack(track: Track): void {
    // Execute async navigation logic without blocking
    void this.navigateTrackAsync(track)
  }

  /**
   * Centralizes handleTrackLoad interception logic.
   * If handleTrackLoad is set on configuration, calls it (intercepted). Otherwise runs defaultBehavior.
   *
   * @returns true if the handler intercepted, false if defaultBehavior ran
   */
  private async handleLoad(
    track: Track,
    queue: Track[],
    startIndex: number,
    defaultBehavior: () => void
  ): Promise<boolean> {
    const handler = this.configuration.handleTrackLoad
    if (handler) {
      const event: TrackLoadEvent = { track, queue, startIndex }
      await handler(event)
      return true
    }
    defaultBehavior()
    return false
  }

  /**
   * Attempts to skip to a track already in the current queue.
   * Used as an optimization to avoid re-expanding the queue.
   *
   * @param track The tapped track (its contextual path pins the exact copy)
   * @param trackId The track's identity (id, falling back to src)
   * @param parentPath The parent path to check against queueSourcePath
   * @returns true if successfully skipped to existing track, false otherwise
   */
  private async trySkipToExistingQueueTrack(
    track: Track,
    trackId: string,
    parentPath: string
  ): Promise<boolean> {
    if (parentPath !== this.browserManager.queueSourcePath) {
      return false
    }

    // Exact-surface match first: a contextual path carries the tapped page
    // position (__index), so path equality pins the exact copy when the page
    // holds the same identity more than once. The identity match remains for
    // index-less paths (e.g. pre-stamp persisted state); an index-stamped path
    // with no exact match falls through to expansion, which re-scopes the
    // queue to the tapped section.
    const queue = this.getQueue()
    let index = queue.findIndex((t) => t.path != null && t.path === track.path)
    if (
      index < 0 &&
      track.path != null &&
      BrowserPathHelper.extractIndex(track.path) === undefined
    ) {
      index = queue.findIndex((t) => getTrackIdentity(t) === trackId)
    }

    if (index < 0) {
      return false
    }

    await this.handleLoad(queue[index]!, queue, index, () => {
      this.skip(index)
      this.play()
    })
    return true
  }

  /**
   * Expands a contextual URL into a full queue and starts playback.
   *
   * @param track The track with contextual URL
   * @returns true if queue was expanded and playback started, false otherwise
   */
  private async expandQueueAndPlay(track: Track): Promise<boolean> {
    const result = await this.browserManager.resolveMediaItemsForPlayback(
      [track],
      0,
      0
    )

    if (result.tracks.length === 0) {
      return false
    }

    await this.handleLoad(track, result.tracks, result.startIndex, () => {
      // setQueue keeps the current play/pause state, so start playback
      // explicitly — selecting a track is an intent to play (matches the
      // skip-to-existing-queue-track path). Without this the first track of a
      // freshly expanded queue loads but stays paused.
      this.setQueue(result.tracks, result.startIndex)
      this.play()
    })
    return true
  }

  /**
   * Async implementation of track navigation with queue expansion support.
   * Matches Android's MediaSessionCallback behavior.
   */
  private async navigateTrackAsync(track: Track): Promise<void> {
    try {
      // A disabled track is unavailable — it never plays, whichever surface
      // or stale resume path delivered the selection (Track.disabled; mirrors
      // iOS TrackSelector.select and Android navigateTrack).
      if (track.disabled === true) return

      const nav = classifyTrackNavigation(track)
      switch (nav.kind) {
        case 'contextual':
          // Optimization: skip to the track if it's already in the queue.
          if (
            nav.trackId &&
            (await this.trySkipToExistingQueueTrack(
              track,
              nav.trackId,
              nav.parentPath
            ))
          ) {
            return
          }
          // Otherwise expand the queue from the contextual URL, falling back to
          // loading the single track if expansion yields nothing.
          if (await this.expandQueueAndPlay(track)) return
          await this.playSingleTrack(track)
          return

        case 'browse':
          this.browserManager.navigateTrack(track).catch((error: unknown) => {
            console.error('Failed to navigate to track:', error)
          })
          return

        case 'playable':
          await this.playSingleTrack(track)
          return

        case 'invalid':
          throw new Error("Track must have either 'path' or 'src' property")
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error'
      this.navigationErrorManager.setNavigationError('unknown-error', message)
    }
  }

  /** Loads a single track as its own queue and starts playback. */
  private async playSingleTrack(track: Track): Promise<void> {
    await this.handleLoad(track, [track], 0, () => {
      this.load(track)
      this.play()
    })
  }

  async search(query: string): Promise<Track[]> {
    // Wrap query string in SearchParams (matches Android's search(query: String) overload)
    return this.searchManager.search({ query, reference: 'unknown' })
  }

  getContent(): ResolvedTrack | undefined {
    return this.browserManager.content
  }

  getNavigationError(): NavigationError | undefined {
    return this.navigationErrorManager.getNavigationError()
  }

  getFormattedNavigationError(): FormattedNavigationError | undefined {
    return this.navigationErrorManager.getFormattedNavigationError()
  }

  notifyContentChanged(path: string): void {
    this.browserManager.notifyContentChanged(path)
  }

  invalidateAllContent(): void {
    this.browserManager.invalidateAllContent()
  }

  setFavorites(favorites: string[]): void {
    this.favoriteManager.setFavorites(favorites)
  }

  // MARK: Player init and config
  async setupPlayer(options: NativeSetupPlayerOptions): Promise<void> {
    await super.setupPlayer(options)
    // Apply the launch options and initial state bundled in setup — same
    // atomic contract as the native platforms.
    if (options.options) this.updateOptions(options.options)
    if (options.repeatMode !== undefined) this.setRepeatMode(options.repeatMode)
    if (options.playWhenReady !== undefined) {
      this.setPlayWhenReady(options.playWhenReady)
    }
  }

  updateOptions(options: NativeUpdateOptions): void {
    // Delegate to options manager
    this.optionsManager.updateOptions(options)

    // Update progress interval if specified (local concern)
    if (options.progressUpdateEventInterval !== undefined) {
      this.setupProgressUpdates(
        options.progressUpdateEventInterval === null
          ? undefined
          : options.progressUpdateEventInterval
      )
    }
  }

  getOptions(): Options {
    return this.optionsManager.getOptions()
  }

  // MARK: Player API

  /**
   * Resolves a media URL using the browser's media configuration.
   * Combines baseUrl with relative paths to create full URLs.
   * Supports the transform callback for URL manipulation.
   * Mirrors Android's and iOS's `resolveMediaUrl`.
   */
  private async resolveMediaRequest(
    src: string,
    track: Track
  ): Promise<ResolvedMediaRequest> {
    // The *resolved* request layer, not `configuration.request`: a
    // resolver-only config would otherwise reach media with no baseUrl and no
    // headers. Browse, search and browse artwork all read it this way too.
    //
    // Caught, because unlike those three this one cannot fail the load: a
    // resolver hiccup — a token refresh timing out — would otherwise take down
    // a track whose `src` is already absolute and public and needed no layer at
    // all. Native degrades the same way, `getMediaRequestConfig` returning null
    // and the original URL playing on.
    let request: TransformableRequestConfig | undefined
    try {
      request = await this.browserManager.resolvedRequestConfig()
    } catch (e) {
      console.error('Failed to resolve the request layer for media', e)
    }
    const { media } = this.browserManager.configuration
    return RequestConfigBuilder.resolveMediaRequest(src, request, media, track)
  }

  /**
   * The queue's own entry, so the queue stays the source of truth for what is
   * playing. `current` preserves the track as queued — the resolved URL is
   * passed to `load()` separately — so either is safe to re-feed.
   */
  protected override trackToReload(): Track | undefined {
    const index = this.queue.currentIndex
    const queued = index !== undefined ? this.queue.getTrack(index) : undefined
    // The queue entry wins, so it stays the source of truth for what is
    // playing; `current` is the fallback for a player loaded without one.
    return queued ?? super.trackToReload()
  }

  /**
   * Makes `track` the queue's current entry, emitting the change.
   *
   * Matches Android, where `load()` modifies the queue: an empty queue takes
   * the track as its only entry, a populated one has its current entry
   * replaced.
   */
  private syncQueueTo(track: Track): void {
    if (this.queue.length === 0) {
      this.queue.setTracks([track])
      this.queue.currentIndex = 0
      this.emitQueueChanged()
      return
    }
    if (
      this.queue.currentIndex !== undefined &&
      this.queue.getTrack(this.queue.currentIndex) !== track
    ) {
      this.queue.replaceTrack(this.queue.currentIndex, track)
      this.emitQueueChanged()
    }
  }

  /**
   * Everything native does in its media-item transition, all of it before any
   * network work: announce the new track, publish its now-playing metadata,
   * cut the outgoing one, and report `loading`.
   *
   * Android's `onMediaItemTransition` fires at `prepare()` and does exactly
   * this, with its LOADING following from `onEvents`; resolution happens later
   * still, inside `TransformingDataSource.open()`. Doing it here rather than
   * after the URL resolves is what gives the resolution window a state, and
   * keeps `activeTrack` ahead of `loading` — the order a consumer that reads
   * `getActiveTrack()` on the loading edge depends on.
   */
  private transitionTo(track: Track): void {
    const element = this.requireElement()
    const lastTrack = this.current
    const lastPosition = element.currentTime
    const lastIndex = this.queue.lastIndex
    const currentIndex = this.queue.currentIndex

    // A re-prepare of the item already current is not a transition, and must
    // not be announced as one: ExoPlayer's `prepare()` does not re-fire
    // `onMediaItemTransition` for an unchanged media item, so a consumer that
    // resets on a track change would have its UI wiped by every retry. The
    // `lastTrack === track` payload was the tell.
    const isTransition = this.current !== track

    // `current` first, so `getActiveTrack()` already reads the new track inside
    // a handler and so `getNowPlaying()` publishes the new track's metadata. It
    // also stops `getActiveTrackIndex()` — which moved with the queue already,
    // synchronously — from disagreeing with it for the length of the
    // resolution.
    this.current = track

    // Separately guarded: a throwing consumer handler must not skip the
    // publish, and neither may fail the load.
    if (isTransition) {
      // As Android's onMediaItemTransition does: a new item is a clean slate,
      // a retry of the same one keeps what the consumer set.
      this.nowPlayingManager.clearNowPlayingOverride()

      try {
        this.onPlaybackActiveTrackChanged({
          lastTrack,
          lastPosition,
          lastIndex,
          index: currentIndex,
          track
        })
      } catch (error) {
        console.error('Failed to announce the active track change:', error)
      }

      try {
        // Once, here: duration reaches the media session through
        // `updateProgress()` on each tick, so only metadata needs publishing.
        // Android publishes from the same transition handler that announces.
        const nowPlaying = this.getNowPlaying()
        if (nowPlaying) {
          this.publishNowPlaying(nowPlaying)
        }
      } catch (error) {
        console.error('Failed to publish now-playing metadata:', error)
      }
    }

    // Stop the outgoing track now rather than when Shaka eventually gets a URL.
    // Announcing the new track while the old one is still audible is worse than
    // the silence, and native has no such window — prepare() cuts the old item
    // at the moment it announces. This adds no teardown: `player.load()`
    // already unloads first (shaka player.js:1757-1762). It only moves when.
    // Verified in a real browser to emit no `pause`, so no state comes of it.
    this.requirePlayer()
      .unload()
      .catch((error: unknown) =>
        console.error('Failed to unload the previous track:', error)
      )

    // Loading covers the resolution too. Shaka dispatches its own `loading`
    // once `player.load()` is called — after resolution — and the state setter
    // drops that as a no-op. The `buffering` that follows is Shaka's to report.
    this.dispatch({ type: 'trackLoading' })
  }

  load(track: Track, callback?: (track: Track) => void): void {
    // Before the queue is touched, so a load before `setupPlayer()` throws
    // rather than half-applying.
    this.requireElement()

    this.syncQueueTo(track)

    // Begun here, at the call, so a newer load supersedes this one immediately
    // rather than once its own URL has resolved. Both continuations below carry
    // it, as does `super.load()`.
    const attempt = this.beginLoadAttempt()
    // Read now: `reloadCurrent` holds the flag only for this synchronous call,
    // and `super.load()` runs after the URL has resolved.
    const reprepare = this._repreparing

    // Loading ends being stopped. `dispatch()` drops every event while
    // `_isStopped`, to keep Shaka's teardown noise off the stopped state, so
    // the `trackLoading` in `transitionTo` would be swallowed on the stop → play
    // path and the resolution window would report nothing. `super.load()` clears
    // this too, but only after the URL resolves.
    this._isStopped = false

    // Set loading flag early so seekTo() calls during async URL resolution
    // are captured as pending seeks rather than silently dropped
    this._loadInProgress = true
    this._pendingSeek = undefined

    this.transitionTo(track)

    // Resolve the media URL before loading (async but we don't await)
    const doLoad = async () => {
      const resolvedRequest = track.src
        ? await this.resolveMediaRequest(track.src, track)
        : undefined
      // Web-only, and forced: a progressive file plays via `mediaElement.src`,
      // which cannot carry headers. Native sends them with the media request
      // and follows the redirect inline, so it needs none of this.
      const resolvedMedia = resolvedRequest
        ? await followMediaRedirect(
            resolvedRequest.path ?? track.src!,
            resolvedRequest.headers,
            { mediaLayerHeaders: resolvedRequest.mediaLayerHeaders }
          )
        : undefined

      // Something took over while the URL was resolving — discard this result
      if (!attempt.isCurrent) return

      super.load(
        track,
        (loadedTrack) => {
          // Call the provided callback if any
          if (callback) {
            callback(loadedTrack)
          }
        },
        {
          headers: resolvedMedia?.headers,
          src: resolvedMedia?.src,
          attempt,
          reprepare
        }
      )
    }

    // Execute async load without blocking, with error handling
    doLoad().catch((error: unknown) => {
      // A backstop with no reachable trigger today, kept because an unhandled
      // rejection would be worse than a dead branch. Every await inside
      // `doLoad` catches internally; `shaka.Player.load` is `async`, so it
      // rejects rather than throwing and its rejection is handled against the
      // attempt inside `Player.load`; and `this.player` is only ever assigned,
      // so `requirePlayer()` cannot start succeeding and then fail mid-load.
      //
      // No attempt check either, for the same reason it would be dead: nothing
      // awaits between the guard above and `super.load()`, so the attempt
      // cannot have changed. Add an await there and this needs the check back.
      this._loadInProgress = false
      this._pendingSeek = undefined
      console.error('Error loading track:', error)
      const message =
        error instanceof Error ? error.message : 'Failed to load track'
      // Track resolution failed before playback was ever attempted, so nothing
      // is known about the stream itself.
      const playbackError: PlaybackError = {
        kind: 'unknown',
        code: 'load-error',
        message
      }
      this.state = { state: 'error', error: playbackError }
    })
  }

  togglePlayback(): void {
    super.togglePlayback()
  }

  /**
   * Copies before emitting: the queue mutates in place, and handing out the
   * live array defeats React's reference-equality change detection.
   */
  private emitQueueChanged(): void {
    this.onPlaybackQueueChanged([...this.queue.tracks])
  }

  // Queue mutations must reach onPlaybackQueueChanged (the contract covers
  // added/removed/reordered; Android emits via onTimelineChanged).
  override add(tracks: Track[], insertBeforeIndex?: number): void {
    super.add(tracks, insertBeforeIndex)
    this.emitQueueChanged()
  }

  override remove(indexes: number[]): void {
    super.remove(indexes)
    this.emitQueueChanged()
  }

  override move(fromIndex: number, toIndex: number): void {
    super.move(fromIndex, toIndex)
    this.emitQueueChanged()
  }

  override removeUpcomingTracks(): void {
    super.removeUpcomingTracks()
    this.emitQueueChanged()
  }

  override reset(): void {
    super.reset()
    this.emitQueueChanged()
  }

  // Override playWhenReady to emit events (mirrors the state override): the
  // base transport methods (play/pause/stop, the queue-end intent clear)
  // assign through this accessor, so the change event and MediaSession sync
  // fire for every writer — not only setPlayWhenReady().
  public override get playWhenReady(): boolean {
    return super.playWhenReady
  }

  public override set playWhenReady(pwr: boolean) {
    const didChange = pwr !== super.playWhenReady
    super.playWhenReady = pwr
    if (didChange) {
      this.onPlaybackPlayWhenReadyChanged({ playWhenReady: pwr })
      this.refreshPlayingState()
      this.remoteCommands.syncPlaybackState()
    }
  }

  setPlayWhenReady(pwr: boolean): void {
    if (!pwr) {
      if (this.element) {
        // pause() halts first and its override then clears a fading sleep
        // timer — restoring the volume before the halt would let full-volume
        // audio slip out (VolumeFader's invariant).
        this.pause()
      } else {
        this.clearSleepTimerIfFading()
        this.playWhenReady = false
      }
      return
    }
    // Mirror native: raising the intent restarts playback from terminal
    // states (reload/replay via play()) and resumes a settled paused/ready
    // player. While loading/buffering the flag alone is correct — load()'s
    // auto-play starts playback once the source is ready.
    const { state } = this.state
    if (
      this.current !== undefined &&
      (state === 'ended' ||
        state === 'stopped' ||
        state === 'error' ||
        state === 'paused' ||
        state === 'ready')
    ) {
      this.play()
      return
    }
    this.playWhenReady = true
  }

  getPlayWhenReady(): boolean {
    return super.playWhenReady
  }

  getPlayback(): Playback {
    return this.state
  }

  getPlayingState(): PlayingState {
    return derivePlayingState(this._playWhenReady, this.state.state)
  }

  getRepeatMode(): RepeatModeType {
    return this.optionsManager.getRepeatMode()
  }

  setRepeatMode(mode: RepeatModeType): void {
    const didChange = this.queue.repeatMode !== mode
    super.setRepeatMode(mode)
    this.optionsManager.setRepeatMode(mode)

    if (didChange) {
      this.onPlaybackRepeatModeChanged({
        repeatMode: mode
      })
    }
  }

  getShuffleEnabled(): boolean {
    return super.getShuffleEnabled()
  }

  setShuffleEnabled(enabled: boolean): void {
    super.setShuffleEnabled(enabled)
    this.onPlaybackShuffleModeChanged(enabled)
  }

  getPlaybackError(): PlaybackError | undefined {
    if (this.state.state === 'error') {
      return this.state.error
    }
    return undefined
  }

  getSleepTimer(): SleepTimer {
    if (this.sleepTimer.time !== null) {
      return { time: this.sleepTimer.time }
    } else if (this.sleepTimer.sleepWhenPlayedToEnd) {
      return { sleepWhenPlayedToEnd: true }
    }
    return null
  }

  setSleepTimer(seconds: number, fadeDuration?: number): void {
    this.sleepTimer.sleepAfter(seconds, fadeDuration)
    this.onSleepTimerChanged(this.getSleepTimer())
  }

  /**
   * An explicit pause intent during the sleep fade is the timer's goal
   * arriving early: clear the timer (which restores the pre-fade volume).
   * Called after the halt lands so the restore never precedes it. Mirrors
   * the native playWhenReady hooks.
   */
  private clearSleepTimerIfFading(): void {
    if (this.sleepFader.isActive) this.clearSleepTimer()
  }

  override pause(): void {
    super.pause()
    this.clearSleepTimerIfFading()
  }

  override stop(): void {
    // `Player.stop()` cancels the load attempt, so an in-flight load's
    // continuation cannot re-arm the player and revive it out of Stopped.
    super.stop()
    this.clearSleepTimerIfFading()
  }

  setSleepTimerToEndOfTrack(): void {
    this.sleepTimer.setToEndOfTrack()
    this.onSleepTimerChanged(this.getSleepTimer())
  }

  clearSleepTimer(): boolean {
    const wasRunning = this.sleepTimer.clear()
    if (wasRunning) {
      this.onSleepTimerChanged(null)
    }
    return wasRunning
  }

  /**
   * Override to check for sleep timer when track ends
   */
  protected onTrackEnded(): boolean {
    // Check if sleep timer is set to end on track completion
    if (this.sleepTimer.sleepWhenPlayedToEnd) {
      console.log('Sleep timer triggered on track end, pausing playback')
      this.sleepTimer.clear()
      this.pause()
      this.onSleepTimerChanged(null)
      // Nothing is loading, so the caller must still report the end — the
      // element has stopped and its own `pause` is swallowed as end-of-track.
      return false
    }

    // Otherwise proceed with normal track end behavior
    return super.onTrackEnded()
  }

  // MARK: Queue management
  setQueue(tracks: Track[], startIndex?: number, startPosition?: number): void {
    this.stop()
    // Clear stale references from previous queue
    this.current = undefined
    // Hydrate favorites and transform artwork URLs on all tracks in the queue
    const { request, artwork } = this.browserManager.configuration
    this.queue.setTracks(
      tracks.map((track) => {
        try {
          const hydratedTrack = this.favoriteManager.hydrateFavorite(track)
          return RequestConfigBuilder.transformTrackArtwork(
            hydratedTrack,
            request,
            artwork
          )
        } catch (error) {
          console.error('Failed to transform track:', error)
          return track // Use original track as fallback
        }
      })
    )
    this.emitQueueChanged()

    if (startIndex !== undefined && this.queue.getTrack(startIndex)) {
      this.skip(startIndex, startPosition)
    }
  }

  getQueue(): Track[] {
    // Copy — see emitQueueChanged.
    return [...this.queue.tracks]
  }

  getActiveTrackIndex(): number | undefined {
    this.requireElement()
    this.requirePlayer()
    return this.queue.currentIndex
  }

  getActiveTrack(): Track | undefined {
    return this.current
  }

  setActiveTrackFavorited(favorited: boolean): void {
    const track = this.getActiveTrack()
    const index = this.getActiveTrackIndex()
    if (!track || getTrackIdentity(track) === undefined || index === undefined)
      return

    // Update favorites set via manager (keyed by track identity)
    if (favorited) {
      this.favoriteManager.addFavorite(track)
    } else {
      this.favoriteManager.removeFavorite(track)
    }

    // Create updated track with new favorited state
    const updatedTrack: Track = {
      ...track,
      favorited
    }

    // Replace the track in the queue
    this.queue.replaceTrack(index, updatedTrack)

    // Emit favorite changed only. A favorite toggle is an in-place mutation of
    // the active track, not a transition — onPlaybackActiveTrackChanged and
    // onPlaybackQueueChanged stay transition-only, and the useActiveTrack /
    // useQueue hooks subscribe to onFavoriteChanged themselves.
    this.onFavoriteChanged({ track: updatedTrack, favorited })
  }

  toggleActiveTrackFavorited(): void {
    const track = this.getActiveTrack()
    if (!track || getTrackIdentity(track) === undefined) return

    const isFavorited = this.favoriteManager.isFavorited(track)
    this.setActiveTrackFavorited(!isFavorited)
  }

  // MARK: Now playing metadata
  updateNowPlaying(update: NowPlayingUpdate | undefined): void {
    const track = this.getActiveTrack()
    const duration = this.getProgress().duration
    this.nowPlayingManager.updateNowPlaying(update, track, duration)
  }

  getNowPlaying(): NowPlayingMetadata | undefined {
    const track = this.getActiveTrack()
    const duration = this.getProgress().duration
    return this.nowPlayingManager.getNowPlaying(track, duration)
  }

  private nowPlayingFlashRevert: ReturnType<typeof setTimeout> | undefined

  flashNowPlaying(update: NowPlayingUpdate, durationMs: number): void {
    // Web approximation: an override + timer is sufficient here — the
    // background-timer and formatter-priority concerns are native-only.
    this.updateNowPlaying(update)
    if (this.nowPlayingFlashRevert) clearTimeout(this.nowPlayingFlashRevert)
    this.nowPlayingFlashRevert = setTimeout(() => {
      this.nowPlayingFlashRevert = undefined
      this.updateNowPlaying(undefined)
    }, durationMs)
  }

  clearNowPlayingFlash(): void {
    if (!this.nowPlayingFlashRevert) return
    clearTimeout(this.nowPlayingFlashRevert)
    this.nowPlayingFlashRevert = undefined
    this.updateNowPlaying(undefined)
  }

  // MARK: Network connectivity
  getOnline(): boolean {
    return this._online
  }

  // MARK: Equalizer (not supported on web)
  getEqualizerSettings(): EqualizerSettings | undefined {
    return undefined
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  setEqualizerEnabled(_enabled: boolean): void {
    // No-op on web
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  setEqualizerPreset(_preset: string): void {
    // No-op on web
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  setEqualizerLevels(_levels: number[]): void {
    // No-op on web
  }

  // MARK: Battery optimization (not supported on web)
  getBatteryWarningPending(): boolean {
    return false
  }

  getBatteryOptimizationStatus(): BatteryOptimizationStatus {
    return 'unrestricted'
  }

  dismissBatteryWarning(): void {
    // No-op on web
  }

  openBatterySettings(): void {
    // No-op on web
  }

  // MARK: System volume (not accessible on web)
  getSystemVolume(): number {
    // Web browsers don't expose system volume, return 1.0 as default
    return 1.0
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  setSystemVolume(_volume: number): void {
    // No-op on web - browsers can't set system volume
  }

  // MARK: Gate (no-op — web has no external browse surfaces)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  setGate(_gate: NativeGate | undefined, _hasResolver: boolean): void {}

  clearGate(): void {}

  // MARK: Car connection (not applicable on web)
  isCarConnected(): boolean {
    return false
  }

  // MARK: audio output (not applicable on web)
  getOutput(): Output | undefined {
    return undefined
  }

  openOutputPicker(): void {
    // No-op on web
  }

  supportsOutputSwitcher(): boolean {
    return false
  }
}
