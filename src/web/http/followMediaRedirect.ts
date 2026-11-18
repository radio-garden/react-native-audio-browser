/**
 * Resolves a media URL that is authenticated by a header.
 *
 * Shaka only routes manifest requests through its networking engine, where a
 * request filter can attach headers. A progressive file is played by assigning
 * `mediaElement.src`, and a media element cannot send custom headers — so such
 * a URL is unplayable that way. Following the redirect here yields the signed,
 * public URL these endpoints hand back, which needs no auth of its own.
 *
 * Best effort and bounded: anything that fails — including taking too long, see
 * {@link PROBE_TIMEOUT_MS} — falls back to the original URL, so the load fails
 * or succeeds on its own terms rather than ours. It must never be the reason a
 * load hangs: `load()` awaits this before Shaka sees the URL at all.
 *
 * Gated, because the probe is not free: a HEAD at a third-party stream host
 * costs a CORS preflight, and most will refuse it, so an app that never
 * authenticates its media would pay a round trip on every play for a result it
 * always discards. It runs only for headers that look like media credentials
 * ({@link isMediaAuth}), on media a media element actually has to play
 * ({@link isManifestUrl}), and never on a URL the browser resolves locally.
 */

/** URLs the browser resolves locally; there is no redirect to follow. */
const isLocalUrl = (url: string): boolean =>
  /^(file|blob|data|mediasource):/i.test(url)

/**
 * Extensions Shaka types as a manifest, from its own `EXTENSIONS_TO_MIME_TYPES_`
 * map: HLS, DASH and Smooth Streaming.
 */
const MANIFEST_EXTENSIONS = new Set(['m3u8', 'mpd', 'ism'])

/**
 * Whether Shaka will parse this as a manifest rather than hand it to the media
 * element — in which case the probe has nothing to offer and something to cost.
 *
 * A manifest goes through Shaka's networking engine, where the request filter
 * attaches the headers to the manifest, its segments and any key requests. The
 * probe cannot improve on that, and it actively breaks it: on a cross-origin
 * redirect the probe clears the headers, so the segments that Android still
 * authenticates would go out bare and 401. So this is a correctness fix as much
 * as it is one fewer round trip.
 *
 * Extension-based, and therefore partial — Shaka guesses the same way
 * (`NetworkingUtils.getExtension`), but plenty of HLS endpoints have no
 * extension at all. Those still get probed, which is the status quo rather than
 * a regression. Nothing on `Track` carries a MIME type to do better with.
 */
const isManifestUrl = (url: string): boolean => {
  const path = url.split(/[?#]/)[0] ?? ''
  const filename = path.slice(path.lastIndexOf('/') + 1)
  const pieces = filename.split('.')
  return (
    pieces.length > 1 &&
    MANIFEST_EXTENSIONS.has(pieces[pieces.length - 1]!.toLowerCase())
  )
}

export interface FollowMediaRedirectOptions {
  /** Injected for testing; defaults to the global `fetch`. */
  fetchImpl?: typeof fetch
  /**
   * Whether the `media` layer contributed any of these headers, rather than
   * their all arriving from the shared `request` layer.
   */
  mediaLayerHeaders?: boolean
  /** Milliseconds before the probe gives up. Defaults to {@link PROBE_TIMEOUT_MS}. */
  timeoutMs?: number
}

/**
 * How long the probe may take before the original URL is played instead.
 *
 * The same 8s the Android media client allows a connect or a read
 * (`MediaFactory.mediaHttpClient`), which is generous for a HEAD but errs the
 * safe way: a slow-but-working auth endpoint should not be abandoned, since
 * giving up means playing a URL that will 401.
 */
const PROBE_TIMEOUT_MS = 8_000

/**
 * `AbortSignal.timeout` where it exists (Safari 16+, Chrome 103+, Node 17.3+),
 * else a hand-rolled equivalent, else nothing — an environment with neither
 * still gets the deadline below, just without cancelling the request.
 */
const timeoutSignal = (ms: number): AbortSignal | undefined => {
  if (typeof AbortSignal === 'undefined') return undefined
  if (typeof AbortSignal.timeout === 'function') return AbortSignal.timeout(ms)
  if (typeof AbortController === 'undefined') return undefined
  const controller = new AbortController()
  setTimeout(() => controller.abort(), ms)
  return controller.signal
}

/**
 * The probe, with a deadline. `load()` awaits this before Shaka is asked to
 * play anything, so an unreachable host would otherwise stall the load
 * indefinitely — no audio, and no error to show for it.
 *
 * Two mechanisms, each with its own job: the signal cancels the request, so a
 * dead host does not hold a connection open behind us, and the race bounds *us*
 * even where the fetch implementation ignores the signal. Whichever fires, the
 * caller treats it as any other probe failure and plays the original URL.
 */
const fetchWithDeadline = async (
  doFetch: typeof fetch,
  url: string,
  headers: Record<string, string>,
  timeoutMs: number
): Promise<Response> => {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      doFetch(url, {
        method: 'HEAD',
        headers,
        redirect: 'follow',
        signal: timeoutSignal(timeoutMs)
      }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(
          () =>
            reject(
              new Error(`Media redirect probe timed out (${timeoutMs}ms)`)
            ),
          timeoutMs
        )
      })
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

/**
 * Whether these headers look like a media credential — the only thing the
 * probe can fix, and the only thing worth a preflight.
 *
 * A header the `media` layer set is media-specific by construction. Otherwise
 * only `Authorization` counts: an app authenticating one API for browse and
 * media alike puts it on the shared `request` layer, and that is the common
 * shape. Shared plumbing that also rides browse requests — an `X-Client-Version`
 * — is not a credential and must not trigger the probe.
 *
 * The residual case is a shared layer carrying a custom auth header, say
 * `X-Api-Key`, for media that needs it: move it to the `media` layer and the
 * probe runs.
 */
const isMediaAuth = (
  headers: Record<string, string>,
  mediaLayerHeaders: boolean
): boolean =>
  mediaLayerHeaders ||
  Object.keys(headers).some((key) => key.toLowerCase() === 'authorization')

export interface ResolvedMedia {
  /** The URL to play. */
  src: string
  /**
   * The headers still safe to send. Cleared once the redirect leaves the
   * origin: the credential was addressed to the authenticating host, and
   * browsers strip `Authorization` on that hop for the same reason.
   *
   * Only for what we send *after* the probe. The probe's own hop is the
   * browser's to make: `redirect: 'follow'` drops `Authorization` across
   * origins but re-sends every other header, so a custom-named credential can
   * reach the redirect target if that target's preflight admits it. There is no
   * way around it from JS — `redirect: 'manual'` yields an opaque response in
   * CORS mode, with no `Location` to walk by hand — so a custom-named media
   * credential is only as private as the hosts it may be redirected to.
   * Android has no such limit: [CrossOriginHeaderStripper] strips the hop
   * there.
   */
  headers?: Record<string, string>
}

/**
 * Origin of an http(s) URL, or undefined when it cannot be determined.
 *
 * Relative URLs are resolved against the document, because that is what the
 * probe's own `fetch` does — a `src` of `/api/authed/7` is a request to the
 * page's origin, so the credential went there. Without this a relative `src`
 * has no origin, never matches the absolute URL the response reports, and the
 * headers are dropped from every load that uses one.
 *
 * Parsed rather than pattern-matched so the comparison normalizes the way
 * Android's `CrossOriginHeaderStripper.sameOrigin` does: `URL.host` omits a
 * default port, so `https://a.com` and `https://a.com:443` are one origin, and
 * it excludes userinfo, so `https://user@a.com` is not a third.
 */
const originOf = (url: string): string | undefined => {
  try {
    const base = typeof location === 'undefined' ? undefined : location.href
    const { protocol, host } = new URL(url, base)
    if (protocol !== 'http:' && protocol !== 'https:') return undefined
    return `${protocol}//${host}`
  } catch {
    return undefined
  }
}

/**
 * Whether the redirect left the origin the credential was addressed to.
 *
 * Only when both origins are known and differ. An origin we could not work out
 * — a relative URL with no document base, outside a browser — is not evidence
 * of a hop, and treating it as one is how the headers went missing before.
 */
const leftTheOrigin = (from: string, to: string): boolean => {
  const origin = originOf(from)
  return origin !== undefined && originOf(to) !== origin
}

export async function followMediaRedirect(
  url: string,
  headers: Record<string, string> | undefined,
  {
    fetchImpl,
    mediaLayerHeaders = false,
    timeoutMs = PROBE_TIMEOUT_MS
  }: FollowMediaRedirectOptions = {}
): Promise<ResolvedMedia> {
  if (!headers || Object.keys(headers).length === 0)
    return { src: url, headers }
  if (!isMediaAuth(headers, mediaLayerHeaders)) return { src: url, headers }
  if (isManifestUrl(url)) return { src: url, headers }
  if (isLocalUrl(url)) return { src: url, headers }

  const doFetch = fetchImpl ?? globalThis.fetch
  if (typeof doFetch !== 'function') return { src: url, headers }

  try {
    // HEAD walks the chain without tripping download counting, which keys off
    // ranged GETs. Deadlined, because the load waits on it.
    const response = await fetchWithDeadline(doFetch, url, headers, timeoutMs)

    const src = response.url || url

    // The status belongs to the *last* hop, so it only speaks for the
    // authenticating endpoint when there was no redirect. Once one has
    // happened, hop one authenticated and handed us its target, and a refusal
    // past that is routinely about the method rather than the credential:
    // presigned S3 and GCS URLs sign it — SigV4's canonical request opens with
    // HTTPMethod, GCS V4's with HTTP_VERB — so a URL signed for GET answers a
    // HEAD with 403. Keep the signed URL, which is the whole point of the walk,
    // and let a real error surface through the player.
    if (!response.ok && src === url) return { src: url, headers }

    return { src, headers: leftTheOrigin(url, src) ? undefined : headers }
  } catch {
    return { src: url, headers }
  }
}
