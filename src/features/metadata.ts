import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import type { Rating } from './rating'

// MARK: - Types

export type RatingType =
  | 'heart'
  | 'thumbs-up-down'
  | 'three-stars'
  | 'four-stars'
  | 'five-stars'
  | 'percentage'
  | 'none'

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
  /** The track rating */
  rating?: Rating
}

export interface NowPlayingMetadata extends TrackMetadataBase {
  elapsedTime?: number
}

/**
 * Metadata update for the now playing notification.
 * Pass to `updateNowPlaying()` to override what's shown in the notification/lock screen.
 * Pass `null` to clear overrides and revert to track metadata.
 */
export type NowPlayingUpdate = {
  /** Title shown in notification */
  title?: string
  /** Artist shown in notification */
  artist?: string
}

export interface PlaybackMetadata {
  source: string
  title?: string
  url?: string
  artist?: string
  album?: string
  date?: string
  genre?: string
}

/**
 * An object representing the common metadata received for a track.
 * This is used for common metadata events which do not include raw metadata.
 */
export interface AudioMetadata {
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
  url?: string
}

/**
 * Common metadata received event.
 * Contains standardized metadata fields without raw metadata entries.
 * Available on both iOS and Android.
 */
export interface AudioCommonMetadataReceivedEvent {
  metadata: AudioMetadata
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

// MARK: - Event Callbacks

/**
 * Subscribes to chapter metadata events (iOS only).
 * @param callback - Called when chapter metadata is received
 * @returns Cleanup function to unsubscribe
 */
export const onMetadataChapterReceived =
  NativeUpdatedValue.emitterize<AudioMetadataReceivedEvent>(
    (cb) => (nativeBrowser.onMetadataChapterReceived = cb)
  )

/**
 * Subscribes to common metadata events.
 * @param callback - Called when common (static) metadata is received
 * @returns Cleanup function to unsubscribe
 */
export const onMetadataCommonReceived =
  NativeUpdatedValue.emitterize<AudioCommonMetadataReceivedEvent>(
    (cb) => (nativeBrowser.onMetadataCommonReceived = cb)
  )

/**
 * Subscribes to playback metadata events (ICY/ID3 from live streams).
 * @param callback - Called when stream metadata is received (title, artist from ICY/ID3 tags)
 * @returns Cleanup function to unsubscribe
 */
export const onPlaybackMetadata =
  NativeUpdatedValue.emitterize<PlaybackMetadata>(
    (cb) => (nativeBrowser.onPlaybackMetadata = cb)
  )

/**
 * Subscribes to timed metadata events.
 * @param callback - Called when timed metadata is received
 * @returns Cleanup function to unsubscribe
 */
export const onMetadataTimedReceived =
  NativeUpdatedValue.emitterize<AudioMetadataReceivedEvent>(
    (cb) => (nativeBrowser.onMetadataTimedReceived = cb)
  )
