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
 * Payload of {@link onRemotePlayId}. No platform emits it.
 */
export interface RemotePlayIdEvent {
  /** The ID of the track to play */
  id: string
  /** Optional index in the queue */
  index?: number
}

/**
 * Payload of {@link onRemotePlaySearch}. No platform emits it.
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
 * Payload of {@link onRemoteSkip}. No platform emits it.
 */
export interface RemoteSkipEvent {
  /** The index to skip to */
  index: number
}

// MARK: - Handler Override Functions

/**
 * Sets a custom handler for remote play events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user presses the play button. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemotePlay(callback: (() => void) | undefined) {
  nativeBrowser.handleRemotePlay = callback
}

/**
 * Sets a custom handler for remote pause events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user presses the pause button. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemotePause(callback: (() => void) | undefined) {
  nativeBrowser.handleRemotePause = callback
}

/**
 * Sets a custom handler for remote next events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user presses the next track button. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemoteNext(callback: (() => void) | undefined) {
  nativeBrowser.handleRemoteNext = callback
}

/**
 * Sets a custom handler for remote previous events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user presses the previous track button. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemotePrevious(callback: (() => void) | undefined) {
  nativeBrowser.handleRemotePrevious = callback
}

/**
 * Sets a custom handler for remote stop events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user presses the stop button. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemoteStop(callback: (() => void) | undefined) {
  nativeBrowser.handleRemoteStop = callback
}

/**
 * Sets a custom handler for remote seek events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user changes the position of the timeline. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemoteSeek(
  callback: ((event: RemoteSeekEvent) => void) | undefined
) {
  nativeBrowser.handleRemoteSeek = callback
}

/**
 * Sets a custom handler for remote jump forward events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user presses the jump forward button. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemoteJumpForward(
  callback: ((event: RemoteJumpForwardEvent) => void) | undefined
) {
  nativeBrowser.handleRemoteJumpForward = callback
}

/**
 * Sets a custom handler for remote jump backward events, overriding the default behavior.
 * `onRemote*` listeners still fire.
 *
 * @param callback - Called when the user presses the jump backward button. Pass
 *   `undefined` to restore the default behavior.
 */
export function handleRemoteJumpBackward(
  callback: ((event: RemoteJumpBackwardEvent) => void) | undefined
) {
  nativeBrowser.handleRemoteJumpBackward = callback
}

// MARK: - Event Callbacks

/**
 * Subscribes to remote jump backward events.
 * Fires when the user presses the jump backward button, including when a
 * `handleRemoteJumpBackward` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteJumpBackward =
  LazyNativeEmitter.emitterize<RemoteJumpBackwardEvent>(
    (cb) => (nativeBrowser.onRemoteJumpBackward = cb)
  )

/**
 * Subscribes to remote jump forward events.
 * Fires when the user presses the jump forward button, including when a
 * `handleRemoteJumpForward` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteJumpForward =
  LazyNativeEmitter.emitterize<RemoteJumpForwardEvent>(
    (cb) => (nativeBrowser.onRemoteJumpForward = cb)
  )

/**
 * Subscribes to remote next events.
 * Fires when the user presses the next track button, including when a
 * `handleRemoteNext` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteNext = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemoteNext = cb)
)

/**
 * Subscribes to remote pause events.
 * Fires when the user presses the pause button, including when a
 * `handleRemotePause` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePause = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemotePause = cb)
)

/**
 * Subscribes to remote play events.
 * Fires when the user presses the play button, including when a
 * `handleRemotePlay` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePlay = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemotePlay = cb)
)

/**
 * Subscribes to remote play ID events. Never fires: no platform (iOS,
 * Android or web) emits them yet.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePlayId = LazyNativeEmitter.emitterize<RemotePlayIdEvent>(
  (cb) => (nativeBrowser.onRemotePlayId = cb)
)

/**
 * Subscribes to remote play search events. Never fires: no platform (iOS,
 * Android or web) emits them yet. Voice search goes through the `search`
 * source passed to `configureBrowser`.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePlaySearch =
  LazyNativeEmitter.emitterize<RemotePlaySearchEvent>(
    (cb) => (nativeBrowser.onRemotePlaySearch = cb)
  )

/**
 * Subscribes to remote previous events.
 * Fires when the user presses the previous track button, including when a
 * `handleRemotePrevious` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemotePrevious = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemotePrevious = cb)
)

/**
 * Subscribes to remote seek events.
 * Fires when the user changes the position of the timeline, including when a
 * `handleRemoteSeek` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteSeek = LazyNativeEmitter.emitterize<RemoteSeekEvent>(
  (cb) => (nativeBrowser.onRemoteSeek = cb)
)

/**
 * Subscribes to remote skip events. Never fires: no platform (iOS, Android
 * or web) emits them yet.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteSkip = LazyNativeEmitter.emitterize<RemoteSkipEvent>(
  (cb) => (nativeBrowser.onRemoteSkip = cb)
)

/**
 * Subscribes to remote stop events.
 * Fires when the user presses the stop button, including when a
 * `handleRemoteStop` override is set.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onRemoteStop = LazyNativeEmitter.emitterize<void>(
  (cb) => (nativeBrowser.onRemoteStop = cb)
)
