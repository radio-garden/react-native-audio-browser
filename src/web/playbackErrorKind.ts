import type { PlaybackErrorKind } from '../features/errors'

/**
 * Maps Shaka's error codes onto {@link PlaybackErrorKind}, the same
 * cross-platform contract iOS derives from AVFoundation and Android from
 * ExoPlayer.
 *
 * Shaka numbers its codes `<category><nnn>`: 1xxx network, 2xxx text, 3xxx
 * media, 4xxx manifest, 5xxx streaming, 6xxx DRM, 7xxx player.
 *
 * As on the native side, codes that name no cause stay `'unknown'` rather than
 * being guessed into a friendlier bucket.
 */

const BAD_HTTP_STATUS = 1001
const HTTP_ERROR = 1002
const TIMEOUT = 1003
const ATTEMPTS_EXHAUSTED = 1010

export function kindForHttpStatus(status: number): PlaybackErrorKind {
  if (status === 404 || status === 410) return 'not-found'
  if (status >= 500 && status <= 599) return 'server-error'
  // Every other 4xx is the server refusing us — auth, geo-blocking, a rate
  // limit. All of them mean "you can't have this stream", not "retry".
  if (status >= 400 && status <= 499) return 'rejected'
  return 'unknown'
}

/**
 * @param code - a `shaka.util.Error.Code`
 * @param httpStatus - the status Shaka reported for `BAD_HTTP_STATUS`, which it
 *   carries in `error.data[1]`
 * @param online - `navigator.onLine`. Shaka's codes cannot tell a dead station
 *   from a dead connection; only the browser can.
 */
export function playbackErrorKind(
  code: number,
  httpStatus?: number,
  online = true
): PlaybackErrorKind {
  if (!online) return 'offline'
  if (code === BAD_HTTP_STATUS && httpStatus !== undefined) {
    return kindForHttpStatus(httpStatus)
  }
  if (code === HTTP_ERROR || code === TIMEOUT || code === ATTEMPTS_EXHAUSTED) {
    return 'unreachable'
  }
  const category = Math.floor(code / 1000)
  // Media, manifest and streaming failures all mean the same thing to a
  // listener: it arrived, and it is not playable.
  if (category === 2 || category === 3 || category === 4 || category === 5) {
    return 'unplayable'
  }
  if (category === 6) return 'rejected'
  return 'unknown'
}
