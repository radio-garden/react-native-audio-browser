import type { FormattedNavigationError } from '../types/browser'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// Re-export for Nitro spec
export type { FormattedNavigationError } from '../types/browser'

/**
 * Normalized, cross-platform classification of a playback failure.
 *
 * Both platforms map their native failure onto this set, so consumers can
 * branch on it without knowing whether the error came from AVFoundation or
 * ExoPlayer. Use this — not `code` — for anything user-visible.
 *
 * - `'offline'` - The device had no network connection at the moment of
 *   failure. The stream itself may be fine.
 * - `'unreachable'` - The stream host could not be reached: DNS failure,
 *   connection refused, timed out, or the connection dropped while loading.
 * - `'not-found'` - The server said this stream is gone (HTTP 404 / 410).
 *   Retrying will not help; the station is dead.
 * - `'rejected'` - The server refused the request (HTTP 401 / 403), e.g.
 *   geo-blocking or an expired token.
 * - `'server-error'` - The server responded with 5xx. Usually transient.
 * - `'unplayable'` - The stream was fetched but cannot be played: unknown
 *   container, unsupported codec, or a decoder failure.
 * - `'stalled'` - Playback had started and then stopped, and the player
 *   exhausted its recovery budget without recovering.
 * - `'unknown'` - The failure could not be classified. Inspect `code`.
 */
export type PlaybackErrorKind =
  | 'offline'
  | 'unreachable'
  | 'not-found'
  | 'rejected'
  | 'server-error'
  | 'unplayable'
  | 'stalled'
  | 'unknown'

export type PlaybackError = {
  /** Normalized classification. Branch on this, not on `code`. */
  kind: PlaybackErrorKind
  /**
   * The raw native failure identifier, for diagnostics and telemetry only.
   * Platform-specific and unstable: loader cases on iOS
   * (`failed-to-load`, `playback-failed`, …), lower-cased ExoPlayer error
   * names on Android (`io-bad-http-status`, `parsing-container-malformed`, …).
   */
  code: string
  message: string
  /** HTTP status code, when the failure came from an HTTP response. */
  statusCode?: number
  /**
   * True while the player is still retrying this failure in the background.
   *
   * A retrying error arrives via `onPlaybackChanged` with the playback state
   * still `loading`/`buffering` (or `paused`) — show it as advisory ("Stream
   * unreachable" over a spinner), because playback may yet recover. It clears
   * (the next `Playback` carries no error) once data flows again, and hardens
   * into a terminal error (state `error`, `retrying` absent) when the retry
   * budget runs out. Absent when retry is disabled: every error is then
   * terminal. Native platforms only — the web implementation has no automatic
   * retry, so its errors are always terminal.
   */
  retrying?: boolean
}

/**
 * Emitted when a playback error occurs.
 *
 * Terminal errors only: this fires on both edges of the `error` state —
 * entering it (with the error) and leaving it (with `error` undefined).
 * Errors the player is still retrying (`retrying: true`) are surfaced through
 * `onPlaybackChanged` instead, attached to the `loading`/`buffering` state.
 */
export interface PlaybackErrorEvent {
  error?: PlaybackError
}

/**
 * Type of navigation error that occurred.
 *
 * - `'content-not-found'` - No route configured for the requested path, or route resolution
 *   returned no content. This is a configuration issue, not an HTTP 404 from the server.
 * - `'network-error'` - Network request failed (connection error, timeout, no internet).
 * - `'http-error'` - Server returned a non-2xx HTTP status code, or returned 2xx but response
 *   parsing failed. Check `statusCode` and `statusCodeSuccess` for details.
 * - `'callback-error'` - The browse callback returned an error (e.g., `{ error: 'message' }`).
 *   Use this for business logic errors like authentication failures or subscription requirements.
 * - `'unknown-error'` - An unexpected error occurred (e.g., invalid configuration).
 * - `'empty-content'` - The path resolved successfully but the container has no children (e.g. an
 *   empty Favorites tab, a search with no results).
 * - `'timeout'` - The resolve did not complete within the allotted time (a stalled request or
 *   unresponsive bridge). Surfaced on External surfaces that render their own browse list.
 */
export type NavigationErrorType =
  | 'content-not-found'
  | 'network-error'
  | 'http-error'
  | 'callback-error'
  | 'unknown-error'
  | 'empty-content'
  | 'timeout'

export type NavigationError = {
  code: NavigationErrorType
  message: string
  /** HTTP status code when code is 'http-error' */
  statusCode?: number
  /** True if HTTP status code was 2xx (success), useful when error is due to response parsing */
  statusCodeSuccess?: boolean
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
export const onPlaybackError =
  NativeUpdatedValue.emitterize<PlaybackErrorEvent>(
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
export const onNavigationError =
  NativeUpdatedValue.emitterize<NavigationErrorEvent>(
    (cb) => (nativeBrowser.onNavigationError = cb)
  )

/**
 * Hook that returns the current navigation error and updates when it changes.
 * @returns The current navigation error or undefined
 */
export function useNavigationError(): NavigationError | undefined {
  return useNativeUpdatedValue(getNavigationError, onNavigationError, 'error')
}

// MARK: - Formatted Navigation Error

/**
 * Gets the current navigation error formatted for display.
 * Native formats the error using the configured `formatNavigationError` callback,
 * or falls back to default formatting.
 *
 * @returns Formatted error with title and message, or undefined if no error
 */
export function getFormattedNavigationError():
  | FormattedNavigationError
  | undefined {
  return nativeBrowser.getFormattedNavigationError() ?? undefined
}

/**
 * Subscribes to formatted navigation error changes.
 * @param callback - Called when the formatted navigation error changes
 * @returns Cleanup function to unsubscribe
 */
export const onFormattedNavigationError = NativeUpdatedValue.emitterize<
  FormattedNavigationError | undefined
>((cb) => (nativeBrowser.onFormattedNavigationError = cb))

/**
 * Hook that returns the current navigation error formatted for display.
 *
 * Native formats the error using the `formatNavigationError` callback configured
 * in `BrowserConfiguration`, or falls back to default English messages.
 *
 * The same formatted error is used by CarPlay and Android Auto error dialogs,
 * ensuring consistent error presentation across your app and external controllers.
 *
 * @returns Formatted error with title and message, or undefined if no error
 *
 * @example
 * ```tsx
 * // Basic usage
 * const error = useFormattedNavigationError()
 *
 * if (error) {
 *   return <ErrorView title={error.title} message={error.message} />
 * }
 * ```
 *
 * @example
 * ```tsx
 * // Configure custom formatting - override only specific errors or routes
 * configureBrowser({
 *   formatNavigationError: ({ error, defaultFormatted, path }) => {
 *     // Custom message for specific routes
 *     if (error.code === 'network-error' && path.startsWith('/json-api')) {
 *       return {
 *         title: 'API Server Not Running',
 *         message: 'Start the API server first'
 *       }
 *     }
 *     if (error.code === 'http-error') {
 *       return {
 *         title: t('error.serverError'),
 *         message: t('error.httpMessage', { status: error.statusCode })
 *       }
 *     }
 *     // Use default for other error types
 *     return defaultFormatted
 *   }
 * })
 * ```
 *
 * @see `BrowserConfiguration.formatNavigationError` - Configure custom error formatting
 * @see {@link useNavigationError} - Access the raw error with code and status details
 */
export function useFormattedNavigationError():
  | FormattedNavigationError
  | undefined {
  return useNativeUpdatedValue(
    getFormattedNavigationError,
    onFormattedNavigationError
  )
}
