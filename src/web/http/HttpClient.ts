import type { NavigationErrorType } from '../../features'
import type { RequestConfig } from '../../types'
import { RequestConfigBuilder } from './RequestConfigBuilder'

type HttpError = Error & {
  isHttpError: true
  status: number
}

function httpError(message: string, status: number): HttpError {
  const error = new Error(message) as HttpError
  error.isHttpError = true
  error.status = status
  return error
}

function isHttpError(error: unknown): error is HttpError {
  return (
    error instanceof Error &&
    'isHttpError' in error &&
    (error as HttpError).isHttpError === true &&
    typeof (error as HttpError).status === 'number'
  )
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
   * JSON or that `decode` throws on; `network-error` when no complete response
   * arrives.
   */
  async executeRequest<T>(
    config: RequestConfig,
    decode: (body: unknown) => T
  ): Promise<T> {
    // Use RequestConfigBuilder to build the URL
    const url = RequestConfigBuilder.buildUrl(config)
    const headers = config.headers ?? {}
    const method = config.method ?? 'GET'

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), TIMEOUT_MS)

    try {
      const response = await fetch(url, {
        method,
        headers,
        signal: controller.signal
      })

      if (!response.ok) {
        throw httpError(
          `HTTP ${response.status}: ${response.statusText}`,
          response.status
        )
      }

      const text = await response.text()
      try {
        return decode(JSON.parse(text))
      } catch (error: unknown) {
        throw httpError(
          error instanceof Error ? error.message : String(error),
          response.status
        )
      }
    } catch (error: unknown) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        const navError = new Error(
          `Request timed out after ${TIMEOUT_MS}ms`
        ) as Error & { code: NavigationErrorType }
        navError.code = 'network-error'
        throw navError
      }
      // Distinguish between network errors and HTTP errors
      if (isHttpError(error)) {
        const navError = new Error(error.message) as Error & {
          code: NavigationErrorType
          statusCode: number
        }
        navError.code = 'http-error'
        navError.statusCode = error.status
        throw navError
      }

      // Network error (fetch failed, CORS, etc.)
      const message = error instanceof Error ? error.message : 'Failed to fetch'
      console.error(`Network error fetching ${url}:`, error)
      const navError = new Error(message) as Error & {
        code: NavigationErrorType
      }
      navError.code = 'network-error'
      throw navError
    } finally {
      clearTimeout(timeoutId)
    }
  }
}
