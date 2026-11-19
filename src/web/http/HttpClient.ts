import type { NavigationErrorType } from '../../features'
import type { RequestConfig } from '../../types'
import { RequestConfigBuilder } from './RequestConfigBuilder'

type RequestError = Error & {
  code: NavigationErrorType
  statusCode?: number
}

function requestError(
  code: NavigationErrorType,
  message: string,
  statusCode?: number
): RequestError {
  const error = new Error(message) as RequestError
  error.code = code
  if (statusCode !== undefined) error.statusCode = statusCode
  return error
}

const TIMEOUT_MS = 30_000

/**
 * HTTP client for making browser API requests.
 * Mirrors Android's HttpClient.kt
 *
 * Config layering (the shared `request` → `<kind>` → route chain) is handled by
 * the callers via RequestConfigBuilder.applyLayer; this client just builds the
 * URL from the fully-merged config and executes the request.
 */
export class HttpClient {
  /**
   * Executes an HTTP request and returns its JSON body as `decode` reads it.
   *
   * Rejects with an error carrying a navigation error `code`: `http-error`
   * with `statusCode` for a non-2xx status, and for a 2xx body that is not
   * JSON or that `decode` throws or rejects on; `network-error` when no
   * complete response arrives.
   */
  async executeRequest<T>(
    config: RequestConfig,
    decode: (body: unknown) => T
  ): Promise<T> {
    const url = RequestConfigBuilder.buildUrl(config)
    const headers = config.headers ?? {}
    const method = config.method ?? 'GET'

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), TIMEOUT_MS)

    let response: Response
    let text: string
    try {
      response = await fetch(url, {
        method,
        headers,
        signal: controller.signal
      })
      text = response.ok ? await response.text() : ''
    } catch (error: unknown) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw requestError(
          'network-error',
          `Request timed out after ${TIMEOUT_MS}ms`
        )
      }
      console.error(`Network error fetching ${url}:`, error)
      throw requestError(
        'network-error',
        error instanceof Error ? error.message : 'Failed to fetch'
      )
    } finally {
      clearTimeout(timeoutId)
    }

    if (!response.ok) {
      throw requestError(
        'http-error',
        `HTTP ${response.status}: ${response.statusText}`,
        response.status
      )
    }

    try {
      return await decode(JSON.parse(text))
    } catch (error: unknown) {
      throw requestError(
        'http-error',
        error instanceof Error ? error.message : String(error),
        response.status
      )
    }
  }
}
