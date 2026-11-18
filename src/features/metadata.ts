import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeValueRefreshedBy } from '../utils/useNativeValueRefreshedBy'
import { onActiveTrackChanged } from './queue/activeTrack'

// MARK: - Types

/** The track fields {@link NowPlayingMetadata} carries. */
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
  /**
   * The track's `src`, or its `path` when it has none, on iOS and Android.
   * On web, the track's Identity (`id` when set, else `src`), or its `path`:
   * a track with an `id` reports different values on web and native.
   */
  mediaId?: string
  /** The track genre */
  genre?: string
}

export interface NowPlayingMetadata extends TrackMetadataBase {
  /**
   * Playback position in seconds when this metadata was emitted or read, not
   * a live value. iOS only; absent on Android and web. For a live position use
   * `useProgress`.
   */
  elapsedTime?: number
}

/**
 * Metadata update for the now playing notification.
 * Pass to `updateNowPlaying()` to override what's shown in the
 * notification/lock screen; pass it `null` to clear the override.
 */
export type NowPlayingUpdate = {
  /** Title shown in notification */
  title?: string
  /** Artist shown in notification */
  artist?: string
  /**
   * Album line, on surfaces that render one (CarPlay's third line, some
   * Bluetooth head units). Never shown on the iOS lock screen or the Android
   * notification.
   */
  album?: string
}

/**
 * Timed metadata received during playback: ICY (radio) or ID3 frames.
 * On iOS, also a file's own tags, once on load, when the file has no chapters.
 */
export interface TimedMetadata {
  title?: string
  artist?: string
  album?: string
  date?: string
  genre?: string
}

/**
 * Static track metadata. On iOS, the media file's own tags. On Android,
 * Media3's merged metadata, where the Track's fields and the now-playing lines win over
 * the file's tags. `compilation` and `url` are never set.
 */
export interface TrackMetadata {
  title?: string
  artist?: string
  albumTitle?: string
  subtitle?: string
  description?: string
  /**
   * Android only: the artwork URL published for the Track, not embedded
   * cover art.
   */
  artworkUri?: string
  trackNumber?: string
  composer?: string
  conductor?: string
  genre?: string
  compilation?: string
  /** Android only: the station name from a Shoutcast / Icecast stream. */
  station?: string
  /** Android only: a Media3 `MEDIA_TYPE_*` constant, as a number string. */
  mediaType?: string
  /**
   * iOS: the tag's date string. Android: day and month only, as `DDMM` (or
   * `DD` or `MM`), no year.
   */
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
  /** Chapter title. Android falls back to the chapter's element ID. */
  title?: string
  /** URL associated with the chapter. Android only. */
  url?: string
}

// MARK: - Getters

/**
 * Returns the last timed metadata received for the current track, or
 * `undefined` when none has arrived since the track changed.
 *
 * @platform ios android
 * Always `undefined` on web — see {@link onTimedMetadata} for why.
 */
export function getTimedMetadata(): TimedMetadata | undefined {
  return nativeBrowser.getTimedMetadata() ?? undefined
}

// MARK: - Event Callbacks

/**
 * Subscribes to chapter metadata events.
 *
 * @platform ios android
 * Never fires on web — the web player does not read chapter markers. See
 * {@link https://github.com/radio-garden/react-native-audio-browser/issues/118 | #118}.
 *
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onChapterMetadata = NativeUpdatedValue.emitterize<
  ChapterMetadata[]
>((cb) => (nativeBrowser.onChapterMetadata = cb))

/**
 * Subscribes to track metadata events. On iOS it fires once on load, when the
 * file has tags. On Android it fires whenever the player's merged metadata
 * changes.
 *
 * @platform ios android
 * Never fires on web — the web player does not read embedded file tags. See
 * {@link https://github.com/radio-garden/react-native-audio-browser/issues/118 | #118}.
 *
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onTrackMetadata = NativeUpdatedValue.emitterize<TrackMetadata>(
  (cb) => (nativeBrowser.onTrackMetadata = cb)
)

/**
 * Subscribes to timed metadata events (ICY/ID3 from live streams). On iOS it
 * also fires on load with a file's own tags when the file has no chapters.
 *
 * @platform ios android
 * Never fires on web: the browser strips ICY metadata before the player sees
 * it, and the web player does not read in-band ID3 from HLS. See
 * {@link https://github.com/radio-garden/react-native-audio-browser/issues/118 | #118}.
 *
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onTimedMetadata = NativeUpdatedValue.emitterize<TimedMetadata>(
  (cb) => (nativeBrowser.onTimedMetadata = cb)
)

// MARK: - Hooks

// Module-level so the hook's subscription identity is stable across renders.
const timedMetadataInvalidators = [onTimedMetadata, onActiveTrackChanged]

/**
 * Hook that returns the current timed metadata, updating when new timed
 * metadata arrives and on a track change.
 * @returns The current timed metadata or undefined
 */
export function useTimedMetadata(): TimedMetadata | undefined {
  return useNativeValueRefreshedBy(getTimedMetadata, timedMetadataInvalidators)
}
