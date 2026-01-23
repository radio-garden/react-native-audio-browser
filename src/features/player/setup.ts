
// MARK: - Types

import { nativeBrowser } from "../../native"

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
 */
export type RetryConfig = {
  /**
   * Maximum number of retry attempts before giving up.
   */
  maxRetries: number

  /**
   * Maximum duration in milliseconds to keep retrying before giving up.
   * This prevents surprising playback resumption after long periods offline.
   *
   * @default 120000 (2 minutes)
   */
  maxRetryDurationMs?: number
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
 * Android-specific player setup options.
 */
export type PartialAndroidSetupPlayerOptions = {
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

export interface AndroidSetupPlayerOptions {
  /**
   * Minimum duration of media that the player will attempt to buffer in ms.
   *
   * @throws Will throw if min buffer is higher than max buffer.
   * @default 50000
   */
  minBuffer: number

  /**
   * Audio offload configuration for power-efficient playback.
   *
   * - `true`: Enable with default settings (gapless and rate change support required)
   * - `false`: Disable audio offload
   * - `{ gaplessSupportRequired?, rateChangeSupportRequired? }`: Enable with custom requirements
   *
   * Audio offload moves audio processing to dedicated hardware when available, saving battery
   * during longer playbacks, especially with screen off. Requirements determine which features
   * must be supported for offload to activate:
   * - `gaplessSupportRequired`: Smooth track transitions without silence
   * - `rateChangeSupportRequired`: Variable playback speeds (0.5x, 1.25x, 2x, etc.)
   *
   * @see https://developer.android.com/media/media3/exoplayer/track-selection#audioOffload
   */
  audioOffload: boolean | AndroidAudioOffloadSettings

  /**
   * Maximum duration of media that the player will attempt to buffer in ms.
   * Max buffer may not be lower than min buffer.
   *
   * @throws Will throw if max buffer is lower than min buffer.
   * @default 50000
   */
  maxBuffer: number

  /**
   * Duration in ms that should be kept in the buffer behind the current
   * playhead time.
   *
   * @default 0
   */
  backBuffer: number

  /**
   * Duration of media in ms that must be buffered for playback to start or
   * resume following a user action such as a seek.
   *
   * @default 2500
   */
  playBuffer: number

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
  rebufferBuffer: number | null

  /**
   * Maximum cache size in MB.
   *
   * @default 0
   */
  maxCacheSize: number

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
  audioContentType: AndroidAudioContentType

  /**
   * Whether the player should automatically pause when audio becomes noisy
   * (e.g., when headphones are unplugged).
   *
   * @default true
   */
  handleAudioBecomingNoisy: boolean

  /**
   * Wake mode for the player to use.
   *
   * Determines whether wake locks are held to keep the CPU and/or
   * WiFi active during playback.
   *
   * @default 'none'
   */
  wakeMode: AndroidPlayerWakeMode
}

export interface PartialIOSSetupPlayerOptions {
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

export interface IOSSetupPlayerOptions {
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

export interface PartialSetupPlayerOptions {
  /** Android-specific configuration options for setup */
  android?: PartialAndroidSetupPlayerOptions
  /** iOS-specific configuration options for setup */
  ios?: PartialIOSSetupPlayerOptions
  /**
   * Indicates whether the player should automatically update now playing metadata data in control center / notification.
   * Defaults to `true`.
   */
  autoUpdateMetadata?: boolean

  /**
   * Retry policy for load errors (network failures, timeouts, etc.)
   * - `true`: Retry indefinitely with exponential backoff (2 minute timeout)
   * - `false`/`undefined`: No automatic retry (default)
   * - `{ maxRetries: n }`: Retry up to n times with exponential backoff
   * - `{ maxRetries: n, maxRetryDurationMs: m }`: Retry with custom timeout
   *
   * Exponential backoff delays: 1s → 1.5s → 2.3s → 3.4s → 5s (capped)
   *
   * @default false
   */
  retry?: boolean | RetryConfig
}

export interface PlayerOptions {
  /** Android-specific configuration options for setup */
  android?: AndroidSetupPlayerOptions
  /** iOS-specific configuration options for setup */
  ios?: PartialIOSSetupPlayerOptions
  /**
   * Indicates whether the player should automatically update now playing metadata data in control center / notification.
   * Defaults to `true`.
   */
  autoUpdateMetadata: boolean

  /**
   * Retry policy for load errors (network failures, timeouts, etc.)
   * - `true`: Retry indefinitely with exponential backoff (2 minute timeout)
   * - `false`/`undefined`: No automatic retry (default)
   * - `{ maxRetries: n }`: Retry up to n times with exponential backoff
   * - `{ maxRetries: n, maxRetryDurationMs: m }`: Retry with custom timeout
   *
   * Exponential backoff delays: 1s → 1.5s → 2.3s → 3.4s → 5s (capped)
   *
   * @default false
   */
  retry?: boolean | RetryConfig
}

// MARK: - Lifecycle

/**
 * Initializes the player with the specified options.
 * @param options - The options to initialize the player with.
 */
export async function setupPlayer(
  options: PartialSetupPlayerOptions = {}
): Promise<void> {
  return nativeBrowser.setupPlayer(options)
}
