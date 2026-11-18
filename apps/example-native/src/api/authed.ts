/**
 * Media authenticated by a header, then redirected to somewhere public — the
 * usual shape for entitled audio, and the case where the platforms differ most.
 *
 * Native sends the header with the media request; web cannot, for a progressive
 * file played via `mediaElement.src`, so the library follows the redirect and
 * plays what it lands on. The one config below covers all three.
 */

import type { MediaRequestConfig } from 'react-native-audio-browser'

/** The example API accepts any bearer token; it only checks that one arrives. */
export const EXAMPLE_TOKEN = 'open-sesame'

/** Everything the example API serves. */
export const API_PREFIX = '/api/'

/** Paths the bearer token is attached to. The probe's first hop needs it too. */
export const AUTHED_PREFIXES = ['/api/authed/', '/api/redirect-probe/start']

/**
 * Where the example API is served. Media requests don't inherit the `/api/**`
 * browse route's base, so the transform supplies it — a relative `src` would
 * never resolve on native.
 */
export const EXAMPLE_API_BASE_URL = 'http://localhost:3003'

/**
 * Points example-API media at the API, and attaches the token to authenticated
 * paths only. Keeping those separate is what lets `/api/unauthed/…` reach the
 * same endpoint on the same host and simply arrive without a token — the 401
 * side of the demo.
 */
export const authedMediaTransform: NonNullable<
  MediaRequestConfig['transform']
> = async (request) => {
  if (!request.path?.startsWith(API_PREFIX)) return request

  const resolved = {
    ...request,
    baseUrl: request.baseUrl ?? EXAMPLE_API_BASE_URL
  }

  const isAuthed = AUTHED_PREFIXES.some((prefix) =>
    request.path!.startsWith(prefix)
  )
  if (!isAuthed) return resolved

  return {
    ...resolved,
    headers: {
      ...resolved.headers,
      Authorization: `Bearer ${EXAMPLE_TOKEN}`
    }
  }
}
