// MARK: - Types

import type { Track } from '../../types'
import type { PlaybackError } from '../errors'
import type { NowPlayingUpdate, TimedMetadata } from '../metadata'
import type { RepeatMode } from '../queue/repeatMode'
import type {
  AndroidUpdateOptions,
  IOSUpdateOptions,
  NativeUpdateOptions,
  UpdateOptions
} from './options'
import { nativeBrowser } from '../../native'
import { validateIOSUpdateOptions } from './validateOptions'

/**
 * Parameters passed to the {@link FormatNowPlayingCallback}.
 *
 * The formatter owns every line shown on the now-playing surface — the track text, the live song,
 * *and* any transient status (reconnecting / error). The library no longer renders status copy
 * itself, so these fields give you what you need to render (and localize) it yourself. The formatter
 * is re-invoked on track change, each timed-metadata update, play/pause + error/rebuffer
 * transitions, and device connectivity changes.
 */
export type FormatNowPlayingParams = {
  /** The currently playing track. */
  track: Track
  /**
   * Timed metadata (ICY / ID3 "now playing song") received during playback, if any.
   * Always `undefined` on web — see `onTimedMetadata` for why.
   */
  timedMetadata?: TimedMetadata
  /** The play/pause intent — `false` while paused. Stays `true` through buffers, so the song line won't flicker. */
  playWhenReady: boolean
  /**
   * Present (truthy) only while ongoing playback has stalled waiting for data — a mid-stream halt,
   * never an initial connect or a seek (it won't flash on track start or bleed into playback). The
   * value classifies *why*, so a "Reconnecting…" vs "No internet connection" line needs no separate
   * connectivity read:
   *  - `'buffering'` — the stream is rebuffering while the device is online (rejoining the live edge);
   *  - `'offline'` — the device has no connectivity.
   * Safe to use on its own (`if (stalled)`); compare the value (`stalled === 'offline'`) for the reason.
   */
  stalled?: StallReason
  /** The current playback error, if playback has failed. */
  error?: PlaybackError
}

/**
 * Why ongoing playback has stalled, surfaced via {@link FormatNowPlayingParams.stalled}:
 * - `'buffering'` — rebuffering while online (the live stream is rejoining the live edge);
 * - `'offline'` — the device has no network connectivity.
 */
export type StallReason = 'buffering' | 'offline'

/**
 * Customizes what's rendered on the now-playing surface — lock screen, notification, Control Center,
 * CarPlay, and Android Auto — for the currently playing track.
 *
 * Configure it via {@link SetupPlayerOptions.nowPlaying}. Where the default behavior
 * simply publishes the track's own `title` / `artist`, the formatter hands you the two now-playing
 * text lines outright: render the live timed metadata (the ICY / ID3 "now playing song"), surface a
 * transient status line ("Reconnecting…", an error message), drop the song while paused — anything
 * you can derive from the {@link FormatNowPlayingParams} for that moment.
 *
 * The callback is **synchronous** and should stay cheap: it's a pure formatting function, run on the
 * JS thread and awaited natively. Don't do I/O in it. If you need asynchronously-fetched data (e.g.
 * album art), resolve it out-of-band and stamp it imperatively rather than awaiting inside here.
 *
 * ### When it's invoked
 * The player re-invokes the formatter whenever the now-playing could change:
 * - on track change (a new item becomes current);
 * - on each timed-metadata update ({@link FormatNowPlayingParams.timedMetadata} arrives or changes);
 * - on every playback transition — play / pause, a stall starting or recovering, an error.
 *
 * Identical results are de-duplicated natively before they reach the media session, so returning the
 * same fields across a rapid burst of transitions is cheap and won't flicker the surface.
 *
 * ### What to return
 * Return a {@link NowPlayingUpdate} with the fields to display. Each field falls back **independently**
 * to the track's own value when omitted — so `{ artist: 'Some Song' }` replaces only the secondary
 * line and leaves the title as the track's title. Return `undefined` (or `{}`) to use the library
 * default entirely (the track's own `title` / `artist`).
 *
 * @param params - The current track, latest timed metadata, and playback signals for this moment
 *   ({@link FormatNowPlayingParams}), including a {@link StallReason} that already classifies an
 *   offline stall — no separate connectivity read needed for the now-playing line.
 * @returns The now-playing fields to display, or `undefined` to fall back to the track's own
 *   `title` / `artist`.
 *
 * @example
 * ```ts
 * setupPlayer({
 *   nowPlaying: ({ timedMetadata, playWhenReady, stalled, error }) => {
 *     if (error)   return { artist: error.message ?? 'Playback error' }
 *     if (stalled) return { artist: stalled === 'offline' ? 'No internet connection' : 'Reconnecting…' }
 *     // Show the live song only while actually playing; otherwise fall back to the track default.
 *     if (!playWhenReady || !timedMetadata?.title) return
 *     return {
 *       artist: timedMetadata.artist
 *         ? `${timedMetadata.artist} — ${timedMetadata.title}`
 *         : timedMetadata.title
 *     }
 *   }
 * })
 * ```
 *
 * @see {@link FormatNowPlayingParams} - the per-invocation signals passed in
 * @see {@link NowPlayingUpdate} - the shape returned
 * @see {@link SetupPlayerOptions.nowPlaying} - where the callback is configured
 */
export type FormatNowPlayingCallback = (
  params: FormatNowPlayingParams
) => NowPlayingUpdate | undefined

/**
 * AndroidAudioContentType options:
 * - `'music'`: Content type value to use when the content type is music. See
 *   https://developer.android.com/reference/android/media/AudioAttributes#CONTENT_TYPE_MUSIC
 * - `'speech'`: Content type value to use when the content type is speech. See
 *   https://developer.android.com/reference/android/media/AudioAttributes#CONTENT_TYPE_SPEECH
 * - `'sonification'`: Content type value to use when the content type is a
 *   sound used to accompany a user action, such as a beep or sound effect
 *   expressing a key click, or event, such as the type of a sound for a bonus
 *   being received in a game. These sounds are mostly synthesized or short
 *   Foley sounds. See
 *   https://developer.android.com/reference/android/media/AudioAttributes#CONTENT_TYPE_SONIFICATION
 * - `'movie'`: Content type value to use when the content type is a soundtrack,
 *   typically accompanying a movie or TV program.
 * - `'unknown'`: Content type value to use when the content type is unknown, or
 *   other than the ones defined. See
 *   https://developer.android.com/reference/android/media/AudioAttributes#CONTENT_TYPE_UNKNOWN
 */
export type AndroidAudioContentType =
  | 'music'
  | 'speech'
  | 'sonification'
  | 'movie'
  | 'unknown'

/**
 * IOSCategory options:
 * - `'playback'`: The category for playing recorded music or other sounds that
 *   are central to the successful use of your app. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/category/1616509-playback
 * - `'playAndRecord'`: The category for recording (input) and playback (output)
 *   of audio, such as for a Voice over Internet Protocol (VoIP) app. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/category/1616568-playandrecord
 * - `'multiRoute'`: The category for routing distinct streams of audio data to
 *   different output devices at the same time. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/category/1616484-multiroute
 * - `'ambient'`: The category for an app in which sound playback is nonprimary
 *   — that is, your app also works with the sound turned off. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/category/1616560-ambient
 * - `'soloAmbient'`: The default audio session category. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/category/1616488-soloambient
 * - `'record'`: The category for recording audio while also silencing playback
 *   audio. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/category/1616451-record
 */
export type IOSCategory =
  | 'playback'
  | 'playAndRecord'
  | 'multiRoute'
  | 'ambient'
  | 'soloAmbient'
  | 'record'

/**
 * IOSCategoryMode options:
 * - `'default'`: The default audio session mode. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616579-default
 * - `'gameChat'`: A mode that the GameKit framework sets on behalf of an
 *   application that uses GameKit's voice chat service. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616511-gamechat
 * - `'measurement'`: A mode that indicates that your app is performing
 *   measurement of audio input or output. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616608-measurement
 * - `'moviePlayback'`: A mode that indicates that your app is playing back
 *   movie content. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616623-movieplayback
 * - `'spokenAudio'`: A mode used for continuous spoken audio to pause the audio
 *   when another app plays a short audio prompt. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616510-spokenaudio
 * - `'videoChat'`: A mode that indicates that your app is engaging in online
 *   video conferencing. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616590-videochat
 * - `'videoRecording'`: A mode that indicates that your app is recording a
 *   movie. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616535-videorecording
 * - `'voiceChat'`: A mode that indicates that your app is performing two-way
 *   voice communication, such as using Voice over Internet Protocol (VoIP). See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/1616455-voicechat
 * - `'voicePrompt'`: A mode that indicates that your app plays audio using
 *   text-to-speech. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/mode/2962803-voiceprompt
 */
export type IOSCategoryMode =
  | 'default'
  | 'gameChat'
  | 'measurement'
  | 'moviePlayback'
  | 'spokenAudio'
  | 'videoChat'
  | 'videoRecording'
  | 'voiceChat'
  | 'voicePrompt'

/**
 * IOSCategoryOptions options:
 * - `'mixWithOthers'`: An option that indicates whether audio from this session
 *   mixes with audio from active sessions in other audio apps. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions/1616611-mixwithothers
 * - `'duckOthers'`: An option that reduces the volume of other audio sessions
 *   while audio from this session plays. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions/1616618-duckothers
 * - `'interruptSpokenAudioAndMixWithOthers'`: An option that determines whether
 *   to pause spoken audio content from other sessions when your app plays its
 *   audio. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions/1616534-interruptspokenaudioandmixwithot
 * - `'allowBluetooth'`: An option that determines whether Bluetooth hands-free
 *   devices appear as available input routes. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions/1616518-allowbluetooth
 * - `'allowBluetoothA2DP'`: An option that determines whether you can stream
 *   audio from this session to Bluetooth devices that support the Advanced
 *   Audio Distribution Profile (A2DP). See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions/1771735-allowbluetootha2dp
 * - `'allowAirPlay'`: An option that determines whether you can stream audio
 *   from this session to AirPlay devices. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions/1771736-allowairplay
 * - `'defaultToSpeaker'`: An option that determines whether audio from the
 *   session defaults to the built-in speaker instead of the receiver. See
 *   https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions/1616462-defaulttospeaker
 */
export type IOSCategoryOptions =
  | 'mixWithOthers'
  | 'duckOthers'
  | 'interruptSpokenAudioAndMixWithOthers'
  | 'allowBluetooth'
  | 'allowBluetoothA2DP'
  | 'allowAirPlay'
  | 'defaultToSpeaker'

/**
 * IOSCategoryPolicy options:
 * - `'default'`: See
 *   https://developer.apple.com/documentation/avfoundation/avaudiosession/routesharingpolicy/default
 * - `'longFormAudio'`: See
 *   https://developer.apple.com/documentation/avfoundation/avaudiosession/routesharingpolicy/longformaudio
 * - `'longFormVideo'`: See
 *   https://developer.apple.com/documentation/avfoundation/avaudiosession/routesharingpolicy/longformvideo
 */
export type IOSCategoryPolicy = 'default' | 'longFormAudio' | 'longFormVideo'

export interface AndroidAudioOffloadSettings {
  /**
   * Whether gapless playback support is required for offload.
   * Enables smooth transitions between tracks without silence gaps.
   * @default true
   */
  gaplessSupportRequired?: boolean

  /**
   * Whether playback rate change support is required for offload.
   * Enables variable playback speeds (0.5x, 1.25x, 2x, etc.) during offload.
   * @default true
   */
  rateChangeSupportRequired?: boolean
}

/**
 * Configuration for retry behavior.
 *
 * Two budgets apply, chosen by whether the current load has ever produced
 * audio:
 *
 * - **First connect** (the load has never played): retries are bounded by
 *   `firstConnectMaxRetryDurationMs`. A stream that fails before ever playing
 *   is usually dead, and the listener is actively waiting for a verdict —
 *   seconds, not minutes.
 * - **Recovery** (the load played, then failed): retries are bounded by
 *   `maxRetryDurationMs`. Playback proved the stream works; drops are usually
 *   transient (tunnels, network handovers, a station's encoder restarting)
 *   and patience is what recovers them unattended.
 *
 * While the device is offline, the first-connect budget does not apply: the
 * player waits for connectivity to return (bounded by `maxRetryDurationMs`),
 * so a station tapped in a tunnel still starts when the network comes back.
 * Any offline observation restarts the first-connect clock, so it only ever
 * measures a contiguous online stretch.
 *
 * Native platforms only — the web implementation has no automatic retry.
 */
export type RetryConfig = {
  /**
   * Maximum number of retry attempts before giving up. Omit to retry without an attempt
   * limit, bounded only by the duration budgets.
   */
  maxRetries?: number

  /**
   * Maximum duration in milliseconds to keep retrying a load that had been
   * playing before it failed, and any load while the device is offline.
   * This prevents surprising playback resumption after long periods offline.
   *
   * @default 120000 (2 minutes)
   */
  maxRetryDurationMs?: number

  /**
   * Maximum duration in milliseconds to keep retrying a load that has never
   * produced audio, while the device is online. When it runs out, the terminal
   * error carries the real diagnosis (`unreachable`, `not-found`, …).
   *
   * @default 12000 (12 seconds)
   */
  firstConnectMaxRetryDurationMs?: number
}

/**
 * AndroidWakeMode options:
 * - `'none'`: No wake locks are held. The device may go to sleep during playback.
 * - `'local'`: Holds a PowerManager.WakeLock during playback to prevent CPU sleep.
 *   Suitable for local media playback with the screen off.
 * - `'network'`: Holds both PowerManager.WakeLock and WifiManager.WifiLock during playback.
 *   Suitable for streaming media over WiFi with the screen off.
 */
export type AndroidPlayerWakeMode = 'none' | 'local' | 'network'

/**
 * Android setup options: the engine-construction fields plus the Android runtime options
 * ({@link AndroidUpdateOptions} — `remoteButtonLayout`, `skipSilence`, …), all expressible at
 * launch. The consumer never has to know which is which; `setupPlayer` routes each field to
 * where it's applied.
 */
export interface AndroidSetupOptions extends AndroidUpdateOptions {
  /**
   * Minimum duration of media that the player will attempt to buffer in ms.
   *
   * @throws Will throw if min buffer is higher than max buffer.
   * @default 50000
   */
  minBuffer?: number

  /**
   * Enable audio offload for power-efficient playback.
   *
   * When enabled, audio decoding is offloaded to dedicated hardware, reducing
   * CPU usage and extending battery life during long playback sessions.
   *
   * - `true`: Enable with default settings (gapless + rate change support required)
   * - `false`/`undefined`: Disabled
   * - `{ gaplessSupportRequired: boolean, rateChangeSupportRequired: boolean }`:
   *   Enable with specific requirements for gapless playback and playback rate changes
   *
   * @default false
   */
  audioOffload?: boolean | AndroidAudioOffloadSettings

  /**
   * Maximum duration of media that the player will attempt to buffer in ms.
   * Max buffer may not be lower than min buffer.
   *
   * @throws Will throw if max buffer is lower than min buffer.
   * @default 50000
   */
  maxBuffer?: number

  /**
   * Duration in ms that should be kept in the buffer behind the current
   * playhead time.
   *
   * @default 0
   */
  backBuffer?: number

  /**
   * Duration of media in ms that must be buffered for playback to start or
   * resume following a user action such as a seek.
   *
   * @default 2500
   */
  playBuffer?: number

  /**
   * Duration of media in ms that must be buffered for playback to resume
   * after a rebuffer (when the buffer runs empty during playback).
   *
   * When null (the default), uses automatic mode:
   * - Starts at `playBuffer` value
   * - On rebuffer, measures how fast the buffer drained
   * - Calculates how much buffer is needed to sustain 60s of playback
   * - Increases threshold accordingly (up to 8000ms max)
   * - Resets when changing tracks
   *
   * Set to a number for a fixed threshold in ms. Should be >= playBuffer,
   * otherwise playback may rebuffer repeatedly (resuming with less buffer
   * than initial start).
   *
   * @default null (automatic)
   */
  rebufferBuffer?: number | null

  /**
   * Maximum cache size in MB.
   *
   * @default 0
   */
  maxCacheSize?: number

  /**
   * The audio content type indicates to the android system how
   * you intend to use audio in your app.
   *
   * With `audioContentType: AndroidAudioContentType.Speech`, the audio will be
   * paused during short interruptions, such as when a message arrives.
   * Otherwise the playback volume is reduced while the notification is playing.
   *
   * @default AndroidAudioContentType.Music
   */
  audioContentType?: AndroidAudioContentType

  /**
   * Whether the player should automatically pause when audio becomes noisy
   * (e.g., when headphones are unplugged).
   *
   * @default true
   */
  handleAudioBecomingNoisy?: boolean

  /**
   * Wake mode for the player to use.
   *
   * Determines whether wake locks are held to keep the CPU and/or
   * WiFi active during playback.
   *
   * @default 'none'
   */
  wakeMode?: AndroidPlayerWakeMode
}

/** iOS setup options: the audio-session fields plus the iOS runtime options. */
export interface IOSSetupOptions extends IOSUpdateOptions {
  /**
   * Preferred forward buffer duration in ms. When set to 0 (default), AVPlayer
   * chooses an appropriate level of buffering automatically.
   *
   * Setting this to a value greater than 0 disables `automaticallyWaitsToMinimizeStalling`.
   *
   * [Read more from Apple Documentation](https://developer.apple.com/documentation/avfoundation/avplayeritem/1643630-preferredforwardbufferduration)
   *
   * @default 0
   */
  buffer?: number

  /**
   * [AVAudioSession.Category](https://developer.apple.com/documentation/avfoundation/avaudiosession/1616615-category)
   * for iOS. Sets on `play()`.
   */
  category?: IOSCategory

  /**
   * The audio session mode, together with the audio session category,
   * indicates to the system how you intend to use audio in your app. You can use
   * a mode to configure the audio system for specific use cases such as video
   * recording, voice or video chat, or audio analysis.
   * Sets on `play()`.
   *
   * See https://developer.apple.com/documentation/avfoundation/avaudiosession/1616508-mode
   */
  categoryMode?: IOSCategoryMode

  /**
   * [AVAudioSession.CategoryOptions](https://developer.apple.com/documentation/avfoundation/avaudiosession/1616503-categoryoptions) for iOS.
   * Sets on `play()`.
   */
  categoryOptions?: IOSCategoryOptions[]

  /**
   * [AVAudioSession.RouteSharingPolicy](https://developer.apple.com/documentation/AVFAudio/AVAudioSession/RouteSharingPolicy-swift.enum) for iOS.
   * Sets on `play()`.
   */
  categoryPolicy?: IOSCategoryPolicy
}

/**
 * @internal The wire shape `setupPlayer` sends across the bridge: the platform bags hold only
 * construction fields, the runtime options nest under `options`, and the public `nowPlaying`
 * option arrives normalized into the two now-playing fields.
 */
export interface NativeSetupPlayerOptions extends Pick<
  SetupPlayerOptions,
  'playWhenReady' | 'repeatMode' | 'retry' | 'keepSessionAliveOnError'
> {
  /** Android-specific configuration options for setup */
  android?: NativeAndroidSetupOptions
  /** iOS-specific configuration options for setup */
  ios?: NativeIOSSetupOptions

  /**
   * Player options to apply atomically with setup. They're stored before the player is
   * constructed, so e.g. Android's media session derives its remote button layout from
   * `capabilities` the moment the service connects — no window with default controls.
   * Normalized from the flat fields on {@link SetupPlayerOptions}.
   */
  options?: NativeUpdateOptions

  /**
   * Normalized from the public `nowPlaying` option. Whether the player
   * publishes/refreshes track metadata on the now-playing surface.
   * @default true
   */
  autoUpdateNowPlayingMetadata?: boolean

  /**
   * Normalized from `nowPlaying` when it's a function. Customizes what's
   * rendered on the now-playing surface.
   */
  nowPlayingMetadataFormatter?: FormatNowPlayingCallback
}

// MARK: - Lifecycle

/**
 * @internal Wire shape for {@link AndroidSetupOptions}: just the engine-construction fields —
 * the runtime options travel separately under the wire payload's `options.android`.
 */
export type NativeAndroidSetupOptions = Omit<
  AndroidSetupOptions,
  keyof AndroidUpdateOptions
>

/**
 * @internal Wire shape for {@link IOSSetupOptions}: just the audio-session fields — the
 * runtime options travel separately under the wire payload's `options.ios`.
 */
export type NativeIOSSetupOptions = Omit<
  IOSSetupOptions,
  keyof IOSUpdateOptions
>

/**
 * Public setup options: the full launch description of a player in one bag — engine
 * construction, runtime options (`capabilities`, jump intervals, …), and initial player state
 * (`playWhenReady`, `repeatMode`). Everything is applied natively in one atomic setup; there
 * is no call ordering to get right. `setupPlayer` normalizes this into the wire taxonomy —
 * consumers never see the split.
 */
export type SetupPlayerOptions = Pick<
  UpdateOptions,
  | 'capabilities'
  | 'forwardJumpInterval'
  | 'backwardJumpInterval'
  | 'progressUpdateEventInterval'
> & {
  /**
   * The initial play/pause intent, applied natively as soon as the player exists. Set it to
   * `true` so the first queued track starts playing the moment it loads.
   *
   * Interleaves with `setPlayWhenReady` by strict last-write-wins, whether or not the
   * player exists yet.
   *
   * @default false
   */
  playWhenReady?: boolean

  /**
   * The initial repeat mode, applied natively as soon as the player exists. Interleaves with
   * `setRepeatMode` by strict last-write-wins, whether or not the player exists yet.
   *
   * @default 'off'
   */
  repeatMode?: RepeatMode

  /**
   * Retry policy for load errors (network failures, timeouts, etc.)
   * - `true`: Retry indefinitely with exponential backoff, under the default
   *   duration budgets (12s before first audio, 2 minutes after — see
   *   {@link RetryConfig})
   * - `false`/`undefined`: No automatic retry (default)
   * - `{ maxRetries: n }`: Retry up to n times with exponential backoff
   * - `RetryConfig`: custom attempt and duration budgets
   *
   * Exponential backoff delays: 1s → 1.5s → 2.3s → 3.4s → 5s (capped)
   *
   * @default false
   */
  retry?: boolean | RetryConfig

  /**
   * Keep the media session alive and controllable through a terminal playback error (e.g. a dead
   * stream), so external controllers (Android Auto / CarPlay) keep their transport controls
   * (next / previous) instead of tearing the session down. The error is still reported via
   * `onPlaybackError` / `playbackState`; this only affects what the OS media session observes.
   *
   * Only Android can tear the session down, so this is the Android opt-out; on iOS the media
   * session always stays controllable through errors (the player resolves a terminal error to
   * paused and retains the now-playing).
   * @default true
   */
  keepSessionAliveOnError?: boolean

  /** Android-specific options: engine construction and runtime, merged. */
  android?: AndroidSetupOptions
  /** iOS-specific options: audio session and runtime, merged. */
  ios?: IOSSetupOptions
  /**
   * Controls what the player renders on the now-playing surface (lock screen / notification /
   * Control Center / CarPlay / Android Auto).
   *
   * - `true` (default): publish the track's own title / artist.
   * - `false`: don't manage the now-playing metadata at all.
   * - a {@link FormatNowPlayingCallback}: render it yourself — the callback owns every line,
   *   including any transient status (buffering / reconnecting / offline / error), which the
   *   library no longer renders on your behalf.
   *
   * @default true
   */
  nowPlaying?: boolean | FormatNowPlayingCallback
}

/** The normalized native now-playing fields, resolved from the public option. */
type NormalizedNowPlaying = Pick<
  NativeSetupPlayerOptions,
  'autoUpdateNowPlayingMetadata' | 'nowPlayingMetadataFormatter'
>

/** Resolves the public `nowPlaying` option to the normalized native fields. */
function resolveNowPlaying(
  value: SetupPlayerOptions['nowPlaying']
): NormalizedNowPlaying {
  if (typeof value === 'function') {
    return {
      autoUpdateNowPlayingMetadata: true,
      nowPlayingMetadataFormatter: wrapNowPlayingFormatter(value)
    }
  }
  // `undefined` defaults to on; `true`/`false` toggle the default mapping.
  return {
    autoUpdateNowPlayingMetadata: value ?? true,
    nowPlayingMetadataFormatter: undefined
  }
}

/**
 * Wraps a {@link FormatNowPlayingCallback} so it never resolves the native callback to `null`.
 *
 * The callback contract lets consumers `return undefined` to mean "use the default". But Nitro's
 * `Promise<T | undefined>.await()` throws `Failed to cast Object to T!` when the callback resolves
 * to null/undefined — which surfaces on Android as the misleading "Cannot reject Promise … it is
 * already resolved!" (the cast error is swallowed, then a reject is attempted on the
 * already-resolved promise). So we coalesce `undefined` to an empty update before it crosses the
 * boundary: the native side already falls back to the track's own title/artist for each missing
 * field, so `{}` is equivalent to "use the default" without ever sending a null across.
 */
function wrapNowPlayingFormatter(
  formatter: FormatNowPlayingCallback
): FormatNowPlayingCallback {
  return (params) => formatter(params) ?? {}
}

/**
 * The object's entries whose values aren't `undefined`. `null` stays: it's meaningful on some
 * fields (`progressUpdateEventInterval: null` disables progress events, `remoteButtonLayout:
 * null` empties the layout), so only `undefined` means "not provided".
 */
function definedFields<T extends object>(obj: T): Partial<T> {
  const out: Partial<T> = {}
  for (const key in obj) {
    if (obj[key] !== undefined) out[key] = obj[key]
  }
  return out
}

/** Spreads `value` under `key` only when it has at least one field. */
function nonEmpty<K extends string, T extends object>(
  key: K,
  value: T
): { [P in K]?: T } {
  return Object.keys(value).length > 0
    ? ({ [key]: value } as { [P in K]?: T })
    : {}
}

/**
 * Initializes the player with the specified options — the full launch description in one
 * declarative, atomic call. Runtime options (`capabilities`, jump intervals,
 * `remoteButtonLayout`, …) and the initial `playWhenReady` / `repeatMode` are applied
 * natively as part of setup itself, so there is no setup-then-command ordering for consumers
 * to know about, and no window where the player exists without its options.
 *
 * Calling it again reconfigures the player in place: newly provided fields are merged over
 * the previous ones, and the audio engine is rebuilt when construction-bound fields (buffers,
 * wake mode, …) changed.
 *
 * @param options - The options to initialize the player with.
 *
 * @example
 * ```ts
 * await setupPlayer({
 *   retry: true,
 *   playWhenReady: true,
 *   repeatMode: 'queue',
 *   capabilities: { favorite: true },
 *   android: {
 *     audioContentType: 'music',
 *     remoteButtonLayout: {
 *       back: 'jump-backward',
 *       forward: 'jump-forward',
 *       overflow: ['favorite']
 *     }
 *   },
 *   ios: { category: 'playback' }
 * })
 * ```
 */
export async function setupPlayer(
  options: SetupPlayerOptions = {}
): Promise<void> {
  const {
    nowPlaying,
    capabilities,
    forwardJumpInterval,
    backwardJumpInterval,
    progressUpdateEventInterval,
    android = {},
    ios = {},
    ...nativeSetup
  } = options

  // Split the merged public bags back into the wire taxonomy: construction fields stay under
  // `android` / `ios`, runtime options nest under `options`. The consumer never has to learn
  // which is which.
  const {
    appKilledPlaybackBehavior,
    skipSilence,
    remoteButtonLayout,
    ...androidSetup
  } = android
  const {
    playbackRates,
    carPlayUpNextButton,
    carPlayNowPlayingButtons,
    ...iosSetup
  } = ios
  const iosUpdate = definedFields({
    playbackRates,
    carPlayUpNextButton,
    carPlayNowPlayingButtons
  })
  validateIOSUpdateOptions(iosUpdate)

  const updates: NativeUpdateOptions = definedFields({
    capabilities,
    forwardJumpInterval,
    backwardJumpInterval,
    progressUpdateEventInterval,
    ...nonEmpty(
      'android',
      definedFields({
        appKilledPlaybackBehavior,
        skipSilence,
        remoteButtonLayout
      })
    ),
    ...nonEmpty('ios', iosUpdate)
  })

  return nativeBrowser.setupPlayer({
    ...nativeSetup,
    ...nonEmpty('android', definedFields(androidSetup)),
    ...nonEmpty('ios', definedFields(iosSetup)),
    ...resolveNowPlaying(nowPlaying),
    ...nonEmpty('options', updates)
  })
}
