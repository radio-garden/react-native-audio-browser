/**
 * Resolves a media URL that is authenticated by a header.
 *
 * Shaka only routes manifest requests through its networking engine, where a
 * request filter can attach headers. A progressive file is played by assigning
 * `mediaElement.src`, and a media element cannot send custom headers — so such
 * a URL is unplayable that way. Following the redirect here yields the signed,
 * public URL these endpoints hand back, which needs no auth of its own.
 *
 * Best effort: anything that fails falls back to the original URL, so the load
 * fails — or succeeds — on its own terms rather than ours.
 */

/** URLs the browser resolves locally; there is no redirect to follow. */
const isLocalUrl = (url: string): boolean =>
  /^(file|blob|data|mediasource):/i.test(url)

export interface FollowMediaRedirectOptions {
  /** Injected for testing; defaults to the global `fetch`. */
  fetchImpl?: typeof fetch
}

export interface ResolvedMedia {
  /** The URL to play. */
  src: string
  /**
   * The headers still safe to send. Cleared once the redirect leaves the
   * origin: the credential was addressed to the authenticating host, and
   * browsers strip `Authorization` on that hop for the same reason.
   */
  headers?: Record<string, string>
}

/** Origin of an absolute http(s) URL, or undefined for anything else. */
const originOf = (url: string): string | undefined => {
  const match = /^https?:\/\/[^/?#]+/i.exec(url)
  return match ? match[0].toLowerCase() : undefined
}

export async function followMediaRedirect(
  url: string,
  headers: Record<string, string> | undefined,
  { fetchImpl }: FollowMediaRedirectOptions = {}
): Promise<ResolvedMedia> {
  if (!headers || Object.keys(headers).length === 0)
    return { src: url, headers }
  if (isLocalUrl(url)) return { src: url, headers }

  const doFetch = fetchImpl ?? globalThis.fetch
  if (typeof doFetch !== 'function') return { src: url, headers }

  try {
    // HEAD walks the chain without tripping download counting, which keys off
    // ranged GETs.
    const response = await doFetch(url, {
      method: 'HEAD',
      headers,
      redirect: 'follow'
    })

    // Let a refusal surface through the player's own error handling.
    if (!response.ok) return { src: url, headers }

    const src = response.url || url
    const crossOrigin = originOf(src) !== originOf(url)
    return { src, headers: crossOrigin ? undefined : headers }
  } catch {
    return { src: url, headers }
  }
}
