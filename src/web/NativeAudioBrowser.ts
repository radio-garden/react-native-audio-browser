import type { AudioBrowser as AudioBrowserSpec } from '../specs/audio-browser.nitro'
import type {
  ResolvedTrack,
  Track,
} from '../types'
import type { NativeBrowserConfiguration } from '../types/browser-native'
import type {
  PlaybackErrorEvent,
  PlaybackError,
  RepeatMode as RepeatModeType,
  PlaybackProgressUpdatedEvent,
  PlaybackQueueEndedEvent,
  PlayingState,
  RepeatModeChangedEvent,
  NativeUpdateOptions,
  UpdateOptions,
  Options,
  Playback,
  PlaybackActiveTrackChangedEvent,
  AudioMetadataReceivedEvent,
  AudioCommonMetadataReceivedEvent,
  PlaybackMetadata,
  PlaybackPlayWhenReadyChangedEvent,
  RemoteJumpBackwardEvent,
  RemoteJumpForwardEvent,
  RemotePlayIdEvent,
  RemotePlaySearchEvent,
  RemoteSeekEvent,
  RemoteSetRatingEvent,
  RemoteSkipEvent,
  SleepTimer,
  SleepTimerChangedEvent,
  FavoriteChangedEvent,
  NavigationError,
  NavigationErrorEvent,
  FormattedNavigationError,
  NowPlayingMetadata,
  NowPlayingUpdate,
  EqualizerSettings,
  BatteryOptimizationStatus,
  BatteryOptimizationStatusChangedEvent,
  BatteryWarningPendingChangedEvent,
  PartialSetupPlayerOptions,
} from '../features'
import { PlaylistPlayer, SleepTimerManager } from './TrackPlayer'
import { SetupNotCalledError } from './TrackPlayer/SetupNotCalledError'
import { HttpClient } from './http/HttpClient'
import { BrowserManager } from './browser/BrowserManager'
import { FavoriteManager } from './browser/FavoriteManager'
import { NavigationErrorManager } from './browser/NavigationErrorManager'
import { SearchManager } from './browser/SearchManager'
import { OptionsManager } from './player/OptionsManager'
import { NowPlayingManager } from './player/NowPlayingManager'

/**
 * Web implementation of AudioBrowser (unified browser + player)
 */
export class NativeAudioBrowser extends PlaylistPlayer implements AudioBrowserSpec {
  // HybridObject stuff
  readonly name = 'WebAudioBrowser'
  equals() {
    return true
  }
  dispose() {
    this.clearUpdateEventInterval()
  }

  // Managers
  private httpClient: HttpClient
  private browserManager: BrowserManager
  private favoriteManager: FavoriteManager
  private navigationErrorManager: NavigationErrorManager
  private searchManager: SearchManager
  private optionsManager: OptionsManager
  private nowPlayingManager: NowPlayingManager

  // Player state
  private progressUpdateEventInterval: NodeJS.Timeout | undefined
  private _online: boolean = typeof navigator !== 'undefined' ? navigator.onLine : true
  private sleepTimer = new (class extends SleepTimerManager {
    constructor(private parent: NativeAudioBrowser) {
      super()
    }
    protected onComplete(): void {
      console.log('Sleep timer completed, stopping playback')
      this.parent.stop()
      this.parent.onSleepTimerChanged(null)
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
  onFormattedNavigationError: (formattedError: FormattedNavigationError | undefined) => void = () => {}

  // MARK: Player event callbacks
  onMetadataChapterReceived: (event: AudioMetadataReceivedEvent) => void = () => {}
  onMetadataCommonReceived: (event: AudioCommonMetadataReceivedEvent) => void = () => {}
  onMetadataTimedReceived: (event: AudioMetadataReceivedEvent) => void = () => {}
  onPlaybackMetadata: (data: PlaybackMetadata) => void = () => {}
  onPlaybackActiveTrackChanged: (data: PlaybackActiveTrackChangedEvent) => void = () => {}
  onPlaybackError: (data: PlaybackErrorEvent) => void = () => {}
  onPlaybackPlayWhenReadyChanged: (data: PlaybackPlayWhenReadyChangedEvent) => void = () => {}
  onPlaybackPlayingState: (data: PlayingState) => void = () => {}
  onPlaybackProgressUpdated: (data: PlaybackProgressUpdatedEvent) => void = () => {}
  onPlaybackQueueEnded: (data: PlaybackQueueEndedEvent) => void = () => {}
  onPlaybackQueueChanged: (queue: Track[]) => void = () => {}
  onPlaybackRepeatModeChanged: (data: RepeatModeChangedEvent) => void = () => {}
  onPlaybackShuffleModeChanged: (enabled: boolean) => void = () => {}
  onSleepTimerChanged: (data: SleepTimerChangedEvent) => void = () => {}
  onPlaybackChanged: (data: Playback) => void = () => {}
  onRemoteBookmark: () => void = () => {}
  onRemoteDislike: () => void = () => {}
  onRemoteJumpBackward: (event: RemoteJumpBackwardEvent) => void = () => {}
  onRemoteJumpForward: (event: RemoteJumpForwardEvent) => void = () => {}
  onRemoteLike: () => void = () => {}
  onRemoteNext: () => void = () => {}
  onRemotePause: () => void = () => {}
  onRemotePlay: () => void = () => {}
  onRemotePlayId: (event: RemotePlayIdEvent) => void = () => {}
  onRemotePlaySearch: (event: RemotePlaySearchEvent) => void = () => {}
  onRemotePrevious: () => void = () => {}
  onRemoteSeek: (event: RemoteSeekEvent) => void = () => {}
  onRemoteSetRating: (event: RemoteSetRatingEvent) => void = () => {}
  onRemoteSkip: (event: RemoteSkipEvent) => void = () => {}
  onRemoteStop: () => void = () => {}
  onOptionsChanged: (event: Options) => void = () => {}
  onFavoriteChanged: (event: FavoriteChangedEvent) => void = () => {}
  onNowPlayingChanged: (metadata: NowPlayingMetadata) => void = () => {}
  onOnlineChanged: (online: boolean) => void = () => {}
  onEqualizerChanged: (settings: EqualizerSettings) => void = () => {}
  onBatteryWarningPendingChanged: (event: BatteryWarningPendingChangedEvent) => void = () => {}
  onBatteryOptimizationStatusChanged: (event: BatteryOptimizationStatusChangedEvent) => void = () => {}

  // MARK: Remote handlers
  handleRemoteBookmark: (() => void) | undefined = undefined
  handleRemoteDislike: (() => void) | undefined = undefined
  handleRemoteJumpBackward: ((event: RemoteJumpBackwardEvent) => void) | undefined = undefined
  handleRemoteJumpForward: ((event: RemoteJumpForwardEvent) => void) | undefined = undefined
  handleRemoteLike: (() => void) | undefined = undefined
  handleRemoteNext: (() => void) | undefined = undefined
  handleRemotePause: (() => void) | undefined = undefined
  handleRemotePlay: (() => void) | undefined = undefined
  handleRemotePlayId: ((event: RemotePlayIdEvent) => void) | undefined = undefined
  handleRemotePlaySearch: ((event: RemotePlaySearchEvent) => void) | undefined = undefined
  handleRemotePrevious: (() => void) | undefined = undefined
  handleRemoteSeek: ((event: RemoteSeekEvent) => void) | undefined = undefined
  handleRemoteSetRating: ((event: RemoteSetRatingEvent) => void) | undefined = undefined
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

    this.browserManager = new BrowserManager(
      this.httpClient,
      this.favoriteManager,
      this.navigationErrorManager
    )

    this.searchManager = new SearchManager(
      this.browserManager,
      this.httpClient
    )

    // Wire up event callbacks from managers to class callbacks
    this.browserManager.onPathChanged = (path) => this.onPathChanged(path)
    this.browserManager.onContentChanged = (content) => this.onContentChanged(content)
    this.browserManager.onTabsChanged = (tabs) => this.onTabsChanged(tabs)
    this.navigationErrorManager.onNavigationError = (data) => this.onNavigationError(data)
    this.navigationErrorManager.onFormattedNavigationError = (error) => this.onFormattedNavigationError(error)
    this.optionsManager.onOptionsChanged = (options) => this.onOptionsChanged(options)
    this.nowPlayingManager.onNowPlayingChanged = (metadata) => this.onNowPlayingChanged(metadata)

    // Setup online/offline listeners
    if (typeof window !== 'undefined') {
      window.addEventListener('online', () => {
        this._online = true
        this.onOnlineChanged(true)
      })
      window.addEventListener('offline', () => {
        this._online = false
        this.onOnlineChanged(false)
      })
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
        ? newState.error !== oldState.error
        : false

    super.state = newState

    if (!didStateChange && !didErrorChange) {
      return
    }

    // Call callbacks
    this.onPlaybackChanged(newState)
    this.onPlaybackPlayingState(this.getPlayingStateFromPlayback(newState))

    if (newState.state === 'error' && newState.error) {
      this.onPlaybackError({ error: newState.error })
    }
  }

  private getPlayingStateFromPlayback(playback: Playback): PlayingState {
    return {
      playing: playback.state === 'playing',
      buffering: playback.state === 'buffering',
    }
  }

  protected setupProgressUpdates(interval?: number) {
    this.clearUpdateEventInterval()
    if (interval) {
      this.progressUpdateEventInterval = setInterval(() => {
        if (this.state.state === 'playing') {
          const progress = this.getProgress()
          const event: PlaybackProgressUpdatedEvent = {
            ...progress,
            track: this.currentIndex || 0,
          }
          this.onPlaybackProgressUpdated(event)
        }
      }, interval * 1000)
    }
  }

  protected clearUpdateEventInterval() {
    if (this.progressUpdateEventInterval) {
      clearInterval(this.progressUpdateEventInterval)
    }
  }

  protected onPlaylistEnded() {
    super.onPlaylistEnded()
    this.onPlaybackQueueEnded({
      track: this.currentIndex ?? 0,
      position: this.element!.currentTime,
    })
  }

  // MARK: Browser API
  navigatePath(path: string): void {
    void this.browserManager.navigatePath(path)
  }

  navigateTrack(track: Track): void {
    const url = track.url
    try {
      // Check if this is a contextual URL (playable-only track with queue context)
      if (url?.includes('?__trackId=')) {
        const parentPath = url.split('?')[0]
        const trackId = new URLSearchParams(url.split('?')[1]).get('__trackId')

        // Optimization: Check if queue already came from this parent path - just skip to the track
        // (matches Android behavior at AudioBrowser.kt:514-522)
        if (trackId && parentPath === this.browserManager.path) {
          const queue = this.getQueue()
          const index = queue.findIndex(t => t.src === trackId)
          if (index >= 0) {
            this.skip(index)
            this.play()
            return
          }
        }

        // Queue expansion: load full context from parent path
        const content = this.browserManager.content
        if (trackId && content?.children) {
          // Get all playable tracks from current content
          const playableTracks = content.children.filter(t => t.src)

          if (playableTracks.length > 0) {
            // Find the index of the clicked track
            const trackIndex = playableTracks.findIndex(t => t.src === trackId)

            if (trackIndex >= 0) {
              // Set the full queue and start at the clicked track
              this.setQueue(playableTracks, trackIndex)
              return
            }
          }
        }

        // Fallback: just load the single track if expansion fails
        this.load(track)
      } else if (url) {
        // Navigate to browsable track to show browsing UI
        void this.browserManager.navigateTrack(track)
      } else if (track.src) {
        // Load playable track into player (no contextual URL, no queue expansion)
        this.load(track)
      } else {
        throw new Error("Track must have either 'url' or 'src' property")
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error'
      this.navigationErrorManager.setNavigationError('unknown-error', message)
    }
  }

  async onSearch(query: string): Promise<Track[]> {
    return this.searchManager.search(query)
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

  setFavorites(favorites: string[]): void {
    this.favoriteManager.setFavorites(favorites)
  }

  // MARK: Player init and config
  async setupPlayer(options: PartialSetupPlayerOptions): Promise<void> {
    await super.setupPlayer(options)
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

  getOptions(): UpdateOptions {
    return this.optionsManager.getOptions()
  }

  // MARK: Player API
  load(track: Track, callback?: (track: Track) => void): void {
    if (!this.element) throw new SetupNotCalledError()
    const lastTrack = this.current
    const lastPosition = this.element.currentTime
    const lastIndex = this.lastIndex
    const currentIndex = this.currentIndex

    super.load(track, (loadedTrack) => {
      this.onPlaybackActiveTrackChanged({
        lastTrack,
        lastPosition,
        lastIndex,
        index: currentIndex,
        track,
      })

      // Update now playing metadata
      const nowPlaying = this.getNowPlaying()
      if (nowPlaying) {
        this.onNowPlayingChanged(nowPlaying)
      }

      // Call the provided callback if any
      if (callback) {
        callback(loadedTrack)
      }
    })
  }

  togglePlayback(): void {
    super.togglePlayback()
  }

  setPlayWhenReady(pwr: boolean): void {
    const didChange = pwr !== this._playWhenReady
    super.playWhenReady = pwr

    if (didChange) {
      this.onPlaybackPlayWhenReadyChanged({
        playWhenReady: this._playWhenReady,
      })
    }
  }

  getPlayWhenReady(): boolean {
    return super.playWhenReady
  }

  getPlayback(): Playback {
    return this.state
  }

  getPlayingState(): PlayingState {
    return this.getPlayingStateFromPlayback(this.state)
  }

  getRepeatMode(): RepeatModeType {
    return this.optionsManager.getRepeatMode()
  }

  setRepeatMode(mode: RepeatModeType): void {
    const didChange = this.repeatMode !== mode
    super.setRepeatMode(mode)
    this.optionsManager.setRepeatMode(mode)

    if (didChange) {
      this.onPlaybackRepeatModeChanged({
        repeatMode: mode,
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

  setSleepTimer(seconds: number): void {
    this.sleepTimer.sleepAfter(seconds)
    this.onSleepTimerChanged(this.getSleepTimer())
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
  protected onTrackEnded(): void {
    // Check if sleep timer is set to end on track completion
    if (this.sleepTimer.sleepWhenPlayedToEnd) {
      console.log('Sleep timer triggered on track end, stopping playback')
      this.sleepTimer.clear()
      this.stop()
      this.onSleepTimerChanged(null)
      return
    }

    // Otherwise proceed with normal track end behavior
    super.onTrackEnded()
  }

  // MARK: Queue management
  setQueue(tracks: Track[], startIndex?: number, startPositionMs?: number): void {
    this.stop()
    // Hydrate favorites on all tracks in the queue
    this.playlist = tracks.map(track => this.favoriteManager.hydrateFavorite(track))
    this.onPlaybackQueueChanged(this.playlist)

    // Regenerate shuffle order when queue is set
    if (super.getShuffleEnabled()) {
      super.setShuffleEnabled(true) // Regenerates shuffle order
    }

    if (startIndex !== undefined && this.playlist[startIndex]) {
      this.skip(startIndex, startPositionMs)
    }
  }

  getQueue(): Track[] {
    return this.playlist
  }

  getActiveTrackIndex(): number | undefined {
    if (!this.element || !this.player) throw new SetupNotCalledError()
    return this.currentIndex
  }

  getActiveTrack(): Track | undefined {
    return this.current
  }

  setActiveTrackFavorited(favorited: boolean): void {
    const track = this.getActiveTrack()
    const index = this.getActiveTrackIndex()
    if (!track || !track.src || index === undefined) return

    // Update favorites set via manager
    if (favorited) {
      this.favoriteManager.addFavorite(track.src)
    } else {
      this.favoriteManager.removeFavorite(track.src)
    }

    // Create updated track with new favorited state
    const updatedTrack: Track = {
      ...track,
      favorited,
    }

    // Replace the track in the playlist
    this.playlist[index] = updatedTrack

    // Emit favorite changed event
    this.onFavoriteChanged({ track: updatedTrack, favorited })

    // Emit active track changed so useActiveTrack() hook updates
    this.onPlaybackActiveTrackChanged({
      lastIndex: index,
      lastTrack: track,
      lastPosition: this.element?.currentTime ?? 0,
      index,
      track: updatedTrack,
    })

    // Emit queue changed so useQueue() hook updates
    this.onPlaybackQueueChanged(this.playlist)
  }

  toggleActiveTrackFavorited(): void {
    const track = this.getActiveTrack()
    if (!track || !track.src) return

    const isFavorited = this.favoriteManager.isFavorited(track.src)
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

}
