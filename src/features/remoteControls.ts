import { nativeBrowser } from '../native'
import { LazyNativeEmitter } from '../utils/LazyNativeEmitter'

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
 * Remote skip event (Android only).
 */
export interface RemoteSkipEvent {
  /** The index to skip to */
  index: number
}

// MARK: - Handler Override Functions
//
// Use these functions when you want to OVERRIDE the default remote control behavior.
// These will replace the default handlers with your custom logic.
// If you just want to listen to events for debugging/logging, use the onRemote* emitters below.

/**
 * Sets a custom handler for remote play events, overriding the default behavior.
 * @param callback - Called when the user presses the play button. Pass undefined to disable.
 */
export function handleRemotePlay(callback: (() => void) | undefined) {
  nativeBrowser.handleRemotePlay = callback
}

/**
 * Sets a custom handler for remote pause events, overriding the default behavior.
 * @param callback - Called when the user presses the pause button. Pass undefined to disable.
 */
export function handleRemotePause(callback: (() => void) | undefined) {
  nativeBrowser.handleRemotePause = callback
}

/**
 * Sets a custom handler for remote next events, overriding the default behavior.
 * @param callback - Called when the user presses the next track button. Pass undefined to disable.
 */
export function handleRemoteNext(callback: (() => void) | undefined) {
  nativeBrowser.handleRemoteNext = callback
}

/**
 * Sets a custom handler for remote previous events, overriding the default behavior.
 * @param callback - Called when the user presses the previous track button. Pass undefined to disable.
 */
export function handleRemotePrevious(callback: (() => void) | undefined) {
  nativeBrowser.handleRemotePrevious = callback
}

/**
 * Sets a custom handler for remote stop events, overriding the default behavior.
 * @param callback - Called when the user presses the stop button. Pass undefined to disable.
 */
export function handleRemoteStop(callback: (() => void) | undefined) {
  nativeBrowser.handleRemoteStop = callback
}

/**
 * Sets a custom handler for remote seek events, overriding the default behavior.
 * @param callback - Called when the user changes the position of the timeline. Pass undefined to disable.
 */
export function handleRemoteSeek(
  callback: ((event: RemoteSeekEvent) => void) | undefined
) {
  nativeBrowser.handleRemoteSeek = callback
}

/**
 * Sets a custom handler for remote jump forward events, overriding the default behavior.
 * @param callback - Called when the user presses the jump forward button. Pass undefined to disable.
 */
export function handleRemoteJumpForward(
  callback: ((event: RemoteJumpForwardEvent) => void) | undefined
) {
  nativeBrowser.handleRemoteJumpForward = callback
}

/**
 * Sets a custom handler for remote jump backward events, overriding the default behavior.
 * @param callback - Called when the user presses the jump backward button. Pass undefined to disable.
 */
export function handleRemoteJumpBackward(
  callback: ((event: RemoteJumpBackwardEvent) => void) | undefined
) {
  nativeBrowser.handleRemoteJumpBackward = callback
}

// MARK: - Event Callbacks (for listening/debugging only)
//
// Use these emitters when you want to LISTEN to remote control events without overriding
// the default behavior. These are perfect for logging, analytics, or debugging.
// Multiple listeners can be registered for the same event.
// To override the default behavior, use the handleRemote* functions above.

/**
 * Subscribes to remote jump backward events.
 * @param callback - Called when the user presses the jump backward button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteJumpBackward =
  LazyNativeEmitter.emitterize<RemoteJumpBackwardEvent>(
    (cb) => (nativeBrowser.onRemoteJumpBackward = cb)
  )

/**
 * Subscribes to remote jump forward events.
 * @param callback - Called when the user presses the jump forward button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteJumpForward =
  LazyNativeEmitter.emitterize<RemoteJumpForwardEvent>(
    (cb) => (nativeBrowser.onRemoteJumpForward = cb)
  )

/**
 * Subscribes to remote next events.
 * @param callback - Called when the user presses the next track button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteNext = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemoteNext = cb)
)

/**
 * Subscribes to remote pause events.
 * @param callback - Called when the user presses the pause button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePause = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemotePause = cb)
)

/**
 * Subscribes to remote play events.
 * @param callback - Called when the user presses the play button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePlay = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemotePlay = cb)
)

/**
 * Subscribes to remote play ID events (Android only).
 * @param callback - Called when the user selects a track from an external device
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePlayId = LazyNativeEmitter.emitterize<RemotePlayIdEvent>(
  (cb) => (nativeBrowser.onRemotePlayId = cb)
)

/**
 * Subscribes to remote play search events (Android only).
 * @param callback - Called when the user searches for a track (usually voice search)
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePlaySearch =
  LazyNativeEmitter.emitterize<RemotePlaySearchEvent>(
    (cb) => (nativeBrowser.onRemotePlaySearch = cb)
  )

/**
 * Subscribes to remote previous events.
 * @param callback - Called when the user presses the previous track button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePrevious = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemotePrevious = cb)
)

/**
 * Subscribes to remote seek events.
 * @param callback - Called when the user changes the position of the timeline
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteSeek = LazyNativeEmitter.emitterize<RemoteSeekEvent>(
  (cb) => (nativeBrowser.onRemoteSeek = cb)
)

/**
 * Subscribes to remote skip events (Android only).
 * @param callback - Called when the user presses the skip button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteSkip = LazyNativeEmitter.emitterize<RemoteSkipEvent>(
  (cb) => (nativeBrowser.onRemoteSkip = cb)
)

/**
 * Subscribes to remote stop events.
 * @param callback - Called when the user presses the stop button
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteStop = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemoteStop = cb)
)
