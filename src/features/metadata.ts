import type { Rating } from './rating'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'

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

/**
 * Timed metadata received during playback.
 * Contains metadata from ICY streams (radio) or ID3 frames.
 */
export interface TimedMetadata {
  title?: string
  artist?: string
  album?: string
  date?: string
  genre?: string
}

/**
 * Static track metadata from the media file.
 * Contains standardized metadata fields.
 */
export interface TrackMetadata {
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
 * Chapter metadata with time range.
 * Used for podcast chapters, audiobook chapters, etc.
 */
export interface ChapterMetadata {
  /** Chapter start time in seconds */
  startTime: number
  /** Chapter end time in seconds */
  endTime: number
  /** Chapter title */
  title?: string
  /** URL associated with the chapter */
  url?: string
}

// MARK: - Event Callbacks

/**
 * Subscribes to chapter metadata events.
 * @param callback - Called when chapter metadata is received
 * @returns Cleanup function to unsubscribe
 */
export const onChapterMetadata = NativeUpdatedValue.emitterize<
  ChapterMetadata[]
>((cb) => (nativeBrowser.onChapterMetadata = cb))

/**
 * Subscribes to track metadata events.
 * @param callback - Called when static track metadata is received
 * @returns Cleanup function to unsubscribe
 */
export const onTrackMetadata = NativeUpdatedValue.emitterize<TrackMetadata>(
  (cb) => (nativeBrowser.onTrackMetadata = cb)
)

/**
 * Subscribes to timed metadata events (ICY/ID3 from live streams).
 * @param callback - Called when stream metadata is received (title, artist from ICY/ID3 tags)
 * @returns Cleanup function to unsubscribe
 */
export const onTimedMetadata = NativeUpdatedValue.emitterize<TimedMetadata>(
  (cb) => (nativeBrowser.onTimedMetadata = cb)
)
