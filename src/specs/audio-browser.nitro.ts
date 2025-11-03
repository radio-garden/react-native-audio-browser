import { type HybridObject } from 'react-native-nitro-modules'
import type {
  PlaybackError,
  PlaybackState,
  PlayerOptions,
  PlayingState,
  Progress,
  RepeatMode,
  Track,
} from '../features'

type EmitterCallback<T> = (data: T) => void
type EventEmitter<T> = (callback: EmitterCallback<T>) => () => void

export interface AudioBrowser
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // MARK: init and config
  setupPlayer(options: PlayerOptions): Promise<void>
  // updateOptions(options: UpdateOptions): void
  // getOptions(): UpdateOptions

  // // MARK: events
  // readonly onMetadataChapterReceived: EventEmitter<AudioMetadataReceivedEvent>
  // readonly onMetadataCommonReceived: EventEmitter<AudioCommonMetadataReceivedEvent>
  // readonly onMetadataTimedReceived: EventEmitter<AudioMetadataReceivedEvent>
  // readonly onPlaybackActiveTrackChanged: EventEmitter<PlaybackActiveTrackChangedEvent>
  // readonly onPlaybackError: EventEmitter<PlaybackErrorEvent>
  // // readonly onPlaybackMetadata: EventEmitter<UnsafeObject>
  // readonly onPlaybackPlayWhenReadyChanged: EventEmitter<PlaybackPlayWhenReadyChangedEvent>
  // readonly onPlaybackPlayingState: EventEmitter<PlayingState>
  // readonly onPlaybackProgressUpdated: EventEmitter<PlaybackProgressUpdatedEvent>
  // readonly onPlaybackQueueEnded: EventEmitter<PlaybackQueueEndedEvent>
  // readonly onPlaybackRepeatModeChanged: EventEmitter<RepeatModeChangedEvent>
  // readonly onPlaybackState: EventEmitter<PlaybackState>
  // readonly onRemoteBookmark: EventEmitter<void>
  // readonly onRemoteDislike: EventEmitter<void>
  // readonly onRemoteJumpBackward: EventEmitter<RemoteJumpBackwardEvent>
  // readonly onRemoteJumpForward: EventEmitter<RemoteJumpForwardEvent>
  // readonly onRemoteLike: EventEmitter<void>
  // readonly onRemoteNext: EventEmitter<void>
  // readonly onRemotePause: EventEmitter<void>
  // readonly onRemotePlay: EventEmitter<void>
  // readonly onRemotePlayId: EventEmitter<RemotePlayIdEvent>
  // readonly onRemotePlaySearch: EventEmitter<RemotePlaySearchEvent>
  // readonly onRemotePrevious: EventEmitter<void>
  // readonly onRemoteSeek: EventEmitter<RemoteSeekEvent>
  // readonly onRemoteSetRating: EventEmitter<RemoteSetRatingEvent>
  // readonly onRemoteSkip: EventEmitter<RemoteSkipEvent>
  // readonly onRemoteStop: EventEmitter<void>
  // readonly onOptionsChanged: EventEmitter<Options>

  // MARK: player api
  load(track: Track): void
  reset(): void
  play(): void
  pause(): void
  togglePlayback(): void
  stop(): void
  setPlayWhenReady(playWhenReady: boolean): void
  getPlayWhenReady(): boolean
  seekTo(position: number): void
  seekBy(offset: number): void
  setVolume(level: number): void
  getVolume(): number
  setRate(rate: number): void
  getRate(): number
  getProgress(): Progress
  getPlaybackState(): PlaybackState
  getPlayingState(): PlayingState
  getRepeatMode(): RepeatMode
  setRepeatMode(mode: RepeatMode): void
  getPlaybackError(): PlaybackError | null
  retry(): void

  // MARK: playlist management
  add(tracks: Track[], insertBeforeIndex?: number): void
  move(fromIndex: number, toIndex: number): void
  remove(indexes: number[]): void
  removeUpcomingTracks(): void
  skip(index: number, initialPosition?: number): void
  skipToNext(initialPosition?: number): void
  skipToPrevious(initialPosition?: number): void
  // updateMetadataForTrack(trackIndex: number, metadata: TrackMetadataBase): void
  // updateNowPlayingMetadata(metadata: TrackMetadataBase): void
  setQueue(tracks: Track[]): void
  getQueue(): Track[]
  getTrack(index: number): Track | undefined
  getActiveTrackIndex(): number | undefined
  getActiveTrack(): Track | undefined

  // MARK: Android methods
  acquireWakeLock(): void
  abandonWakeLock(): void
}
