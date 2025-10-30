import TrackPlayer from '../NativeTrackPlayer'
import { pause, play, seekBy, seekTo, stop } from './playback'
import { skipToNext, skipToPrevious } from './queue'

// MARK: - Handlers State

const customHandlers = new Map<string, any>()

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
  /** The rating value */
  rating: number | string
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
TrackPlayer.onRemotePlay(() => {
  const customHandler = customHandlers.get('play')
  if (customHandler) {
    customHandler()
  } else {
    play()
  }
})

TrackPlayer.onRemotePause(() => {
  const customHandler = customHandlers.get('pause')
  if (customHandler) {
    customHandler()
  } else {
    pause()
  }
})

TrackPlayer.onRemoteNext(() => {
  const customHandler = customHandlers.get('next')
  if (customHandler) {
    customHandler()
  } else {
    skipToNext()
  }
})

TrackPlayer.onRemotePrevious(() => {
  const customHandler = customHandlers.get('previous')
  if (customHandler) {
    customHandler()
  } else {
    skipToPrevious()
  }
})

TrackPlayer.onRemoteStop(() => {
  const customHandler = customHandlers.get('stop')
  if (customHandler) {
    customHandler()
  } else {
    stop()
  }
})

// Seek controls
TrackPlayer.onRemoteSeek((event: any) => {
  const customHandler = customHandlers.get('seek')
  if (customHandler) {
    customHandler(event)
  } else {
    seekTo(event.position)
  }
})

TrackPlayer.onRemoteJumpForward((event: any) => {
  const customHandler = customHandlers.get('jumpForward')
  if (customHandler) {
    customHandler(event)
  } else {
    seekBy(event.interval)
  }
})

TrackPlayer.onRemoteJumpBackward((event: any) => {
  const customHandler = customHandlers.get('jumpBackward')
  if (customHandler) {
    customHandler(event)
  } else {
    seekBy(-event.interval)
  }
})

// MARK: - Handler Override Functions
//
// Use these functions when you want to OVERRIDE the default remote control behavior.
// These will replace the default handlers with your custom logic.
// If you just want to listen to events for debugging/logging, use the onRemote* functions below.

/**
 * Sets a custom handler for remote play events, overriding the default behavior.
 * @param callback - Called when the user presses the play button
 * @returns Cleanup function to restore default behavior
 */
export function handleRemotePlay(callback: () => void): () => void {
  customHandlers.set('play', callback)
  return () => {
    customHandlers.delete('play')
  }
}

/**
 * Sets a custom handler for remote pause events, overriding the default behavior.
 * @param callback - Called when the user presses the pause button
 * @returns Cleanup function to restore default behavior
 */
export function handleRemotePause(callback: () => void): () => void {
  customHandlers.set('pause', callback)
  return () => {
    customHandlers.delete('pause')
  }
}

/**
 * Sets a custom handler for remote next events, overriding the default behavior.
 * @param callback - Called when the user presses the next track button
 * @returns Cleanup function to restore default behavior
 */
export function handleRemoteNext(callback: () => void): () => void {
  customHandlers.set('next', callback)
  return () => {
    customHandlers.delete('next')
  }
}

/**
 * Sets a custom handler for remote previous events, overriding the default behavior.
 * @param callback - Called when the user presses the previous track button
 * @returns Cleanup function to restore default behavior
 */
export function handleRemotePrevious(callback: () => void): () => void {
  customHandlers.set('previous', callback)
  return () => {
    customHandlers.delete('previous')
  }
}

/**
 * Sets a custom handler for remote stop events, overriding the default behavior.
 * @param callback - Called when the user presses the stop button
 * @returns Cleanup function to restore default behavior
 */
export function handleRemoteStop(callback: () => void): () => void {
  customHandlers.set('stop', callback)
  return () => {
    customHandlers.delete('stop')
  }
}

/**
 * Sets a custom handler for remote seek events, overriding the default behavior.
 * @param callback - Called when the user changes the position of the timeline
 * @returns Cleanup function to restore default behavior
 */
export function handleRemoteSeek(
  callback: (event: RemoteSeekEvent) => void
): () => void {
  customHandlers.set('seek', callback)
  return () => {
    customHandlers.delete('seek')
  }
}

/**
 * Sets a custom handler for remote jump forward events, overriding the default behavior.
 * @param callback - Called when the user presses the jump forward button
 * @returns Cleanup function to restore default behavior
 */
export function handleRemoteJumpForward(
  callback: (event: RemoteJumpForwardEvent) => void
): () => void {
  customHandlers.set('jumpForward', callback)
  return () => {
    customHandlers.delete('jumpForward')
  }
}

/**
 * Sets a custom handler for remote jump backward events, overriding the default behavior.
 * @param callback - Called when the user presses the jump backward button
 * @returns Cleanup function to restore default behavior
 */
export function handleRemoteJumpBackward(
  callback: (event: RemoteJumpBackwardEvent) => void
): () => void {
  customHandlers.set('jumpBackward', callback)
  return () => {
    customHandlers.delete('jumpBackward')
  }
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
export function onRemoteBookmark(callback: () => void): () => void {
  return TrackPlayer.onRemoteBookmark(callback).remove
}

/**
 * Subscribes to remote dislike events (iOS only).
 * @param callback - Called when the user presses the dislike button
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteDislike(callback: () => void): () => void {
  return TrackPlayer.onRemoteDislike(callback).remove
}

/**
 * Subscribes to remote jump backward events.
 * @param callback - Called when the user presses the jump backward button
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteJumpBackward(
  callback: (event: RemoteJumpBackwardEvent) => void
): () => void {
  return TrackPlayer.onRemoteJumpBackward(callback as () => void).remove
}

/**
 * Subscribes to remote jump forward events.
 * @param callback - Called when the user presses the jump forward button
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteJumpForward(
  callback: (event: RemoteJumpForwardEvent) => void
): () => void {
  return TrackPlayer.onRemoteJumpForward(callback as () => void).remove
}

/**
 * Subscribes to remote like events (iOS only).
 * @param callback - Called when the user presses the like button
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteLike(callback: () => void): () => void {
  return TrackPlayer.onRemoteLike(callback).remove
}

/**
 * Subscribes to remote next events.
 * @param callback - Called when the user presses the next track button
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteNext(callback: () => void): () => void {
  return TrackPlayer.onRemoteNext(callback).remove
}

/**
 * Subscribes to remote pause events.
 * @param callback - Called when the user presses the pause button
 * @returns Cleanup function to unsubscribe
 */
export function onRemotePause(callback: () => void): () => void {
  return TrackPlayer.onRemotePause(callback).remove
}

/**
 * Subscribes to remote play events.
 * @param callback - Called when the user presses the play button
 * @returns Cleanup function to unsubscribe
 */
export function onRemotePlay(callback: () => void): () => void {
  return TrackPlayer.onRemotePlay(callback).remove
}

/**
 * Subscribes to remote play ID events (Android only).
 * @param callback - Called when the user selects a track from an external device
 * @returns Cleanup function to unsubscribe
 */
export function onRemotePlayId(
  callback: (event: RemotePlayIdEvent) => void
): () => void {
  return TrackPlayer.onRemotePlayId(callback as () => void).remove
}

/**
 * Subscribes to remote play search events (Android only).
 * @param callback - Called when the user searches for a track (usually voice search)
 * @returns Cleanup function to unsubscribe
 */
export function onRemotePlaySearch(
  callback: (event: RemotePlaySearchEvent) => void
): () => void {
  return TrackPlayer.onRemotePlaySearch(callback as () => void).remove
}

/**
 * Subscribes to remote previous events.
 * @param callback - Called when the user presses the previous track button
 * @returns Cleanup function to unsubscribe
 */
export function onRemotePrevious(callback: () => void): () => void {
  return TrackPlayer.onRemotePrevious(callback).remove
}

/**
 * Subscribes to remote seek events.
 * @param callback - Called when the user changes the position of the timeline
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteSeek(
  callback: (event: RemoteSeekEvent) => void
): () => void {
  return TrackPlayer.onRemoteSeek(callback as () => void).remove
}

/**
 * Subscribes to remote set rating events.
 * @param callback - Called when the user changes the rating for the track remotely
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteSetRating(
  callback: (event: RemoteSetRatingEvent) => void
): () => void {
  return TrackPlayer.onRemoteSetRating(callback as () => void).remove
}

/**
 * Subscribes to remote skip events (Android only).
 * @param callback - Called when the user presses the skip button
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteSkip(
  callback: (event: RemoteSkipEvent) => void
): () => void {
  return TrackPlayer.onRemoteSkip(callback as () => void).remove
}

/**
 * Subscribes to remote stop events.
 * @param callback - Called when the user presses the stop button
 * @returns Cleanup function to unsubscribe
 */
export function onRemoteStop(callback: () => void): () => void {
  return TrackPlayer.onRemoteStop(callback).remove
}
