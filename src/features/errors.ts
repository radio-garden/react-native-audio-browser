import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

export type PlaybackError = {
  code: string
  message: string
}

/**
 * Emitted when a playback error occurs.
 */
export interface PlaybackErrorEvent {
  error?: PlaybackError
}

/**
 * Type of navigation error that occurred.
 */
export type NavigationErrorType = 'content-not-found' | 'network-error' | 'http-error' | 'unknown-error'

export type NavigationError = {
  code: NavigationErrorType
  message: string
  statusCode?: number
}

/**
 * Emitted when a navigation error occurs.
 */
export interface NavigationErrorEvent {
  error?: NavigationError
}

// MARK: - Getters

/**
 * Gets the current playback error, if any.
 * @returns The current error or undefined if there is no error
 */
export function getPlaybackError(): PlaybackError | undefined {
  return nativeBrowser.getPlaybackError()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback error events.
 * @param callback - Called when a playback error occurs
 * @returns Cleanup function to unsubscribe
 */
export const onPlaybackError = NativeUpdatedValue.emitterize<PlaybackErrorEvent>(
  (cb) => (nativeBrowser.onPlaybackError = cb)
)

// MARK: - Hooks

/**
 * Hook that returns the current playback error and updates when it changes.
 * @returns The current playback error or undefined
 */
export function usePlaybackError(): PlaybackError | undefined {
  return useNativeUpdatedValue(getPlaybackError, onPlaybackError, 'error')
}

// MARK: - Navigation Error

/**
 * Gets the current navigation error, if any.
 * @returns The current navigation error or undefined if there is no error
 */
export function getNavigationError(): NavigationError | undefined {
  const error = nativeBrowser.getNavigationError()
  return error ?? undefined
}

/**
 * Subscribes to navigation error events.
 * @param callback - Called when a navigation error occurs
 * @returns Cleanup function to unsubscribe
 */
export const onNavigationError = NativeUpdatedValue.emitterize<NavigationErrorEvent>(
  (cb) => (nativeBrowser.onNavigationError = cb)
)

/**
 * Hook that returns the current navigation error and updates when it changes.
 * @returns The current navigation error or undefined
 */
export function useNavigationError(): NavigationError | undefined {
  return useNativeUpdatedValue(getNavigationError, onNavigationError, 'error')
}
