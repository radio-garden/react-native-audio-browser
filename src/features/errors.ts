import type { FormattedNavigationError } from '../types/browser'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'

// Re-export for Nitro spec
export type { FormattedNavigationError } from '../types/browser'

/**
 * Normalized, cross-platform classification of a playback failure.
 *
 * Every platform (AVFoundation on iOS, ExoPlayer on Android, Shaka on web)
 * maps its native failure onto this set, so consumers can branch on it without
 * knowing where the error came from. Use this — not `code` — for anything
 * user-visible.
 *
 * - `'offline'` - The device had no network connection at the moment of
 *   failure. The stream itself may be fine.
 * - `'unreachable'` - The stream host could not be reached: DNS failure,
 *   connection refused, timed out, or the connection dropped while loading.
 * - `'not-found'` - The server said this stream is gone (HTTP 404 / 410).
 *   Retrying will not help; the station is dead.
 * - `'rejected'` - The server refused the request: any HTTP 4xx other than
 *   404 / 410 (auth, geo-blocking, rate limiting), a DRM or licence refusal
 *   (Android and web), or a region, account or parental-control refusal
 *   (Android).
 * - `'server-error'` - The server responded with 5xx. Usually transient.
 * - `'unplayable'` - The stream cannot be played: an invalid source URL, a
 *   blocked cleartext URL (Android), an unknown container, an unsupported
 *   codec, or a decoder failure.
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

/** A classified playback failure: branch on `kind`, log `code`. */
export type PlaybackError = {
  /** Normalized classification. Branch on this, not on `code`. */
  kind: PlaybackErrorKind
  /**
   * The raw native failure identifier, for diagnostics and telemetry only.
   * Platform-specific and unstable: loader cases on iOS
   * (`failed-to-load`, `playback-failed`, …), lower-cased ExoPlayer error
   * names on Android (`io-bad-http-status`, `parsing-container-malformed`, …),
   * Shaka error codes as strings on web.
   */
  code: string
  /** A diagnostic description, not copy for the user. */
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
 *   empty Favorites tab, a search with no results). Reaches only the `formatNavigationError`
 *   callback, for car-surface copy, never `useNavigationError`.
 * - `'timeout'` - The resolve did not complete within the allotted time (a stalled request or
 *   unresponsive bridge). CarPlay only, and like `'empty-content'` it reaches only the
 *   `formatNavigationError` callback, never `useNavigationError`.
 */
export type NavigationErrorType =
  | 'content-not-found'
  | 'network-error'
  | 'http-error'
  | 'callback-error'
  | 'unknown-error'
  | 'empty-content'
  | 'timeout'

/** A failed browse or search resolve. */
export type NavigationError = {
  code: NavigationErrorType
  /** A diagnostic description, not copy for the user. */
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
 * Emits when the playback error is set or cleared (`error` is `undefined`
 * when it clears). Subscribe with `addListener`, which returns the
 * unsubscribe function.
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
 * Emits when the navigation error is set or cleared (`error` is `undefined`
 * when it clears). Subscribe with `addListener`, which returns the
 * unsubscribe function.
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
 * Emits each time the formatted navigation error changes. Subscribe with
 * `addListener`, which returns the unsubscribe function.
 */
export const onFormattedNavigationError = NativeUpdatedValue.emitterize<
  FormattedNavigationError | undefined
>((cb) => (nativeBrowser.onFormattedNavigationError = cb))

/**
 * Hook that returns the current navigation error formatted for display.
 *
 * Native formats the error using the `formatNavigationError` callback configured
 * in `BrowserConfiguration`, or falls back to built-in English titles with the
 * error's own message. An HTTP error's title is its status text,
 * system-localized on iOS; on Android an English name for common codes. It is
 * `Server Error` when there is no status code, for codes Android does not
 * name, and always on web.
 *
 * CarPlay and Android Auto format their own browse errors with the same
 * `formatNavigationError` callback, so the copy matches across your app and
 * the car.
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
