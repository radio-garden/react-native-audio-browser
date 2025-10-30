import TrackPlayer from '../NativeTrackPlayer'
import resolveAssetSource from '../utils/resolveAssetSource'

// MARK: - Types

export type RatingType =
  | 'heart'
  | 'thumbs-up-down'
  | '3-stars'
  | '4-stars'
  | '5-stars'
  | 'percentage'

export interface TrackMetadataBase {
  /** The track title */
  title?: string
  /** The track album */
  album?: string
  /** The track artist */
  artist?: string
  /** The track duration in seconds */
  duration?: number
  /** The track artwork */
  artwork?: string
  /** track description */
  description?: string
  /** track mediaId */
  mediaId?: string
  /** The track genre */
  genre?: string
  /** The track release date in [RFC 3339](https://www.ietf.org/rfc/rfc3339.txt) */
  date?: string
  /** The track rating */
  rating?: RatingType
  /**
   * (iOS only) Whether the track is presented in the control center as being
   * live
   **/
  isLiveStream?: boolean
}

export interface NowPlayingMetadata extends TrackMetadataBase {
  elapsedTime?: number
}

/**
 * Standardized common metadata field names that raw metadata keys can map to.
 */
export enum CommonMetadataKey {
  Title = 'title',
  Artist = 'artist',
  AlbumName = 'albumName',
  Genre = 'genre',
  CreationDate = 'creationDate',
}

/**
 * Metadata key spaces (namespaces) that identify the format or source of metadata.
 */
export enum MetadataKeySpace {
  /** ID3 metadata (MP3 files) */
  ID3 = 'org.id3',
  /** ICY metadata (streaming audio) */
  ICY = 'icy',
  /** Vorbis comments (OGG/FLAC files) */
  Vorbis = 'org.vorbis',
  /** QuickTime/MP4/M4A metadata */
  QuickTime = 'com.apple.quicktime',
}

/**
 * Represents a raw metadata entry from timed or chapter metadata events.
 * Only available in AudioMetadata, not in AudioCommonMetadata.
 */
export interface RawEntry {
  /**
   * The common key that maps to standardized metadata fields.
   * Might be undefined if the key doesn't map to a common field.
   */
  commonKey?: CommonMetadataKey
  /**
   * The key space/namespace for the metadata that identifies the format or source.
   * Might be undefined for some metadata formats.
   */
  keySpace?: MetadataKeySpace
  /**
   * The time position for timed metadata entries in seconds.
   * Might be undefined for non-timed metadata.
   */
  time?: number
  // /**
  //  * The metadata value. Can be string, number, boolean, or other types depending on the metadata.
  //  */
  // value?: unknown;
  /**
   * The metadata key identifier (e.g., "TIT2", "StreamTitle", "TITLE").
   */
  key: string
}

/**
 * An object representing the common metadata received for a track.
 * This is used for common metadata events which do not include raw metadata.
 */
export interface AudioCommonMetadata {
  title?: string
  artist?: string
  albumTitle?: string
  subtitle?: string
  description?: string
  artworkUri?: string
  trackNumber?: string
  composer?: string
  conductor?: string
  genre?: string
  compilation?: string
  station?: string
  mediaType?: string
  creationDate?: string
  creationYear?: string
}

/**
 * An extension of AudioCommonMetadata that includes the raw metadata.
 * This is used for timed and chapter metadata events which include access to raw metadata entries.
 */
export interface AudioMetadata extends AudioCommonMetadata {
  /**
   * The raw metadata that was used to populate. May contain other non common keys. May be empty.
   * Only available in timed and chapter metadata events, not in common metadata events.
   */
  raw: RawEntry[]
}

/**
 * Common metadata received event.
 * Contains standardized metadata fields without raw metadata entries.
 * Available on both iOS and Android.
 */
export interface AudioCommonMetadataReceivedEvent {
  metadata: AudioCommonMetadata
}

/**
 * Timed and chapter metadata received event.
 * Contains standardized metadata fields plus raw metadata entries.
 * Available on both iOS and Android for timed metadata.
 * Chapter metadata is iOS-only.
 */
export interface AudioMetadataReceivedEvent {
  metadata: AudioMetadata[]
}

// MARK: - Helpers

function resolveImportedAssetOrPath(pathOrAsset: string | number | undefined) {
  return pathOrAsset === undefined
    ? undefined
    : typeof pathOrAsset === 'string'
      ? pathOrAsset
      : resolveImportedAsset(pathOrAsset)
}

function resolveImportedAsset(id?: number) {
  return id
    ? ((resolveAssetSource(id) as { uri: string } | null) ?? undefined)
    : undefined
}

// MARK: - Metadata Updates

/**
 * Updates the metadata of a track in the queue. If the current track is updated,
 * the notification and the Now Playing Center will be updated accordingly.
 *
 * @param trackIndex - The index of the track whose metadata will be updated.
 * @param metadata - The metadata to update.
 */
export function updateMetadataForTrack(
  trackIndex: number,
  metadata: TrackMetadataBase
): void {
  TrackPlayer.updateMetadataForTrack(trackIndex, {
    ...metadata,
    artwork: resolveImportedAssetOrPath(metadata.artwork),
  })
}

/**
 * Updates the metadata content of the notification (Android) and the Now Playing Center (iOS)
 * without affecting the data stored for the current track.
 */
export function updateNowPlayingMetadata(metadata: NowPlayingMetadata): void {
  TrackPlayer.updateNowPlayingMetadata({
    ...metadata,
    artwork: resolveImportedAssetOrPath(metadata.artwork),
  })
}

// MARK: - Event Callbacks

/**
 * Subscribes to chapter metadata events (iOS only).
 * @param callback - Called when chapter metadata is received
 * @returns Cleanup function to unsubscribe
 */
export function onMetadataChapterReceived(
  callback: (event: AudioMetadataReceivedEvent) => void
): () => void {
  return TrackPlayer.onMetadataChapterReceived(callback as () => void).remove
}

/**
 * Subscribes to common metadata events.
 * @param callback - Called when common (static) metadata is received
 * @returns Cleanup function to unsubscribe
 */
export function onMetadataCommonReceived(
  callback: (event: AudioCommonMetadataReceivedEvent) => void
): () => void {
  return TrackPlayer.onMetadataCommonReceived(callback as () => void).remove
}

/**
 * Subscribes to timed metadata events.
 * @param callback - Called when timed metadata is received
 * @returns Cleanup function to unsubscribe
 */
export function onMetadataTimedReceived(
  callback: (event: AudioMetadataReceivedEvent) => void
): () => void {
  return TrackPlayer.onMetadataTimedReceived(callback as () => void).remove
}

/**
 * Subscribes to playback metadata events.
 * @param callback - Called when playback metadata is received
 * @returns Cleanup function to unsubscribe
 */
export function onPlaybackMetadata(callback: () => void): () => void {
  return TrackPlayer.onPlaybackMetadata(callback).remove
}
