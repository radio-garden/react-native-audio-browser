import { nativePlayer } from '../native'
import { LazyEmitter } from '../utils/LazyEmitter'
import type {
  HeartRating,
  PercentageRating,
  StarRating,
  ThumbsRating
} from './rating'

// MARK: - Event Interfaces

/**
 * Remote jump backward event.
 */
export interface RemoteJumpBackwardEvent {
  /** Jump interval in seconds */
  interval: number
}

/**
 * Remote jump forward event.
 */
export interface RemoteJumpForwardEvent {
  /** Jump interval in seconds */
  interval: number
}

/**
 * Remote play ID event (Android only).
 */
export interface RemotePlayIdEvent {
  /** The ID of the track to play */
  id: string
  /** Optional index in the queue */
  index?: number
}

/**
 * Remote play search event (Android only).
 */
export interface RemotePlaySearchEvent {
  /** The search query */
  query: string
}

/**
 * Remote seek event.
 */
export interface RemoteSeekEvent {
  /** The position to seek to in seconds */
  position: number
}

/**
 * Remote set rating event.
 */
export interface RemoteSetRatingEvent {
  rating: HeartRating | ThumbsRating | StarRating | PercentageRating
}

/**
 * Remote skip event (Android only).
 */
export interface RemoteSkipEvent {
  /** The index to skip to */
  index: number
}

// MARK: - Default Handlers

// Install remote control handlers with default behavior immediately when module loads
// Custom handlers can override the defaults using handleRemote* functions

// Basic playback controls
// AudioBrowser.onRemotePlay(() => {
//   const customHandler = customHandlers.get('play')
//   if (customHandler) {
//     customHandler()
//   } else {
//     play()
//   }
// })

// AudioBrowser.onRemotePause(() => {
//   const customHandler = customHandlers.get('pause')
//   if (customHandler) {
//     customHandler()
//   } else {
//     pause()
//   }
// })

// AudioBrowser.onRemoteNext(() => {
//   const customHandler = customHandlers.get('next')
//   if (customHandler) {
//     customHandler()
//   } else {
//     skipToNext()
//   }
// })

// AudioBrowser.onRemotePrevious(() => {
//   const customHandler = customHandlers.get('previous')
//   if (customHandler) {
//     customHandler()
//   } else {
//     skipToPrevious()
//   }
// })

// AudioBrowser.onRemoteStop(() => {
//   const customHandler = customHandlers.get('stop')
//   if (customHandler) {
//     customHandler()
//   } else {
//     stop()
//   }
// })

// // Seek controls
// AudioBrowser.onRemoteSeek((event: any) => {
//   const customHandler = customHandlers.get('seek')
//   if (customHandler) {
//     customHandler(event)
//   } else {
//     seekTo(event.position)
//   }
// })

// AudioBrowser.onRemoteJumpForward((event: any) => {
//   const customHandler = customHandlers.get('jumpForward')
//   if (customHandler) {
//     customHandler(event)
//   } else {
//     seekBy(event.interval)
//   }
// })

// AudioBrowser.onRemoteJumpBackward((event: any) => {
//   const customHandler = customHandlers.get('jumpBackward')
//   if (customHandler) {
//     customHandler(event)
//   } else {
//     seekBy(-event.interval)
//   }
// })

// MARK: - Handler Override Functions
//
// Use these functions when you want to OVERRIDE the default remote control behavior.
// These will replace the default handlers with your custom logic.
// If you just want to listen to events for debugging/logging, use the onRemote* functions below.

/**
 * Sets a custom handler for remote play events, overriding the default behavior.
 * @param callback - Called when the user presses the play button. Pass undefined to disable.
 */
export function handleRemotePlay(callback: (() => void) | undefined) {
  nativePlayer.handleRemotePlay = callback
}

/**
 * Sets a custom handler for remote pause events, overriding the default behavior.
 * @param callback - Called when the user presses the pause button. Pass undefined to disable.
 */
export function handleRemotePause(callback: (() => void) | undefined) {
  nativePlayer.handleRemotePause = callback
}

/**
 * Sets a custom handler for remote next events, overriding the default behavior.
 * @param callback - Called when the user presses the next track button. Pass undefined to disable.
 */
export function handleRemoteNext(callback: (() => void) | undefined) {
  nativePlayer.handleRemoteNext = callback
}

/**
 * Sets a custom handler for remote previous events, overriding the default behavior.
 * @param callback - Called when the user presses the previous track button. Pass undefined to disable.
 */
export function handleRemotePrevious(callback: (() => void) | undefined) {
  nativePlayer.handleRemotePrevious = callback
}

/**
 * Sets a custom handler for remote stop events, overriding the default behavior.
 * @param callback - Called when the user presses the stop button. Pass undefined to disable.
 */
export function handleRemoteStop(callback: (() => void) | undefined) {
  nativePlayer.handleRemoteStop = callback
}

/**
 * Sets a custom handler for remote seek events, overriding the default behavior.
 * @param callback - Called when the user changes the position of the timeline. Pass undefined to disable.
 */
export function handleRemoteSeek(
  callback: ((event: RemoteSeekEvent) => void) | undefined
) {
  nativePlayer.handleRemoteSeek = callback
}

/**
 * Sets a custom handler for remote jump forward events, overriding the default behavior.
 * @param callback - Called when the user presses the jump forward button. Pass undefined to disable.
 */
export function handleRemoteJumpForward(
  callback: ((event: RemoteJumpForwardEvent) => void) | undefined
) {
  nativePlayer.handleRemoteJumpForward = callback
}

/**
 * Sets a custom handler for remote jump backward events, overriding the default behavior.
 * @param callback - Called when the user presses the jump backward button. Pass undefined to disable.
 */
export function handleRemoteJumpBackward(
  callback: ((event: RemoteJumpBackwardEvent) => void) | undefined
) {
  nativePlayer.handleRemoteJumpBackward = callback
}

// MARK: - Event Callbacks (for listening/debugging only)
//
// Use these functions when you want to LISTEN to remote control events without overriding
// the default behavior. These are perfect for logging, analytics, or debugging.
// Multiple listeners can be registered for the same event.
// To override the default behavior, use the handleRemote* functions above.

/**
 * Subscribes to remote bookmark events (iOS only).
 * @param callback - Called when the user presses the bookmark button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteBookmark = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemoteBookmark = cb)
)

/**
 * Subscribes to remote dislike events (iOS only).
 * @param callback - Called when the user presses the dislike button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteDislike = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemoteDislike = cb)
)

/**
 * Subscribes to remote jump backward events.
 * @param callback - Called when the user presses the jump backward button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteJumpBackward =
  LazyEmitter.emitterize<RemoteJumpBackwardEvent>(
    (cb) => (nativePlayer.onRemoteJumpBackward = cb)
  )

/**
 * Subscribes to remote jump forward events.
 * @param callback - Called when the user presses the jump forward button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteJumpForward =
  LazyEmitter.emitterize<RemoteJumpForwardEvent>(
    (cb) => (nativePlayer.onRemoteJumpForward = cb)
  )

/**
 * Subscribes to remote like events (iOS only).
 * @param callback - Called when the user presses the like button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteLike = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemoteLike = cb)
)

/**
 * Subscribes to remote next events.
 * @param callback - Called when the user presses the next track button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteNext = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemoteNext = cb)
)

/**
 * Subscribes to remote pause events.
 * @param callback - Called when the user presses the pause button
 * @returns Cleanup function to unsubscribe
 */
export const onRemotePause = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemotePause = cb)
)

/**
 * Subscribes to remote play events.
 * @param callback - Called when the user presses the play button
 * @returns Cleanup function to unsubscribe
 */
export const onRemotePlay = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemotePlay = cb)
)

/**
 * Subscribes to remote play ID events (Android only).
 * @param callback - Called when the user selects a track from an external device
 * @returns Cleanup function to unsubscribe
 */
export const onRemotePlayId = LazyEmitter.emitterize<RemotePlayIdEvent>(
  (cb) => (nativePlayer.onRemotePlayId = cb)
)

/**
 * Subscribes to remote play search events (Android only).
 * @param callback - Called when the user searches for a track (usually voice search)
 * @returns Cleanup function to unsubscribe
 */
export const onRemotePlaySearch = LazyEmitter.emitterize<RemotePlaySearchEvent>(
  (cb) => (nativePlayer.onRemotePlaySearch = cb)
)

/**
 * Subscribes to remote previous events.
 * @param callback - Called when the user presses the previous track button
 * @returns Cleanup function to unsubscribe
 */
export const onRemotePrevious = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemotePrevious = cb)
)

/**
 * Subscribes to remote seek events.
 * @param callback - Called when the user changes the position of the timeline
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteSeek = LazyEmitter.emitterize<RemoteSeekEvent>(
  (cb) => (nativePlayer.onRemoteSeek = cb)
)

/**
 * Subscribes to remote set rating events.
 * @param callback - Called when the user changes the rating for the track remotely
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteSetRating = LazyEmitter.emitterize<RemoteSetRatingEvent>(
  (cb) => (nativePlayer.onRemoteSetRating = cb)
)

/**
 * Subscribes to remote skip events (Android only).
 * @param callback - Called when the user presses the skip button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteSkip = LazyEmitter.emitterize<RemoteSkipEvent>(
  (cb) => (nativePlayer.onRemoteSkip = cb)
)

/**
 * Subscribes to remote stop events.
 * @param callback - Called when the user presses the stop button
 * @returns Cleanup function to unsubscribe
 */
export const onRemoteStop = LazyEmitter.emitterize<void>(
  (cb) => (nativePlayer.onRemoteStop = cb)
)
