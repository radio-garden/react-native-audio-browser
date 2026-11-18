import type { NavigationErrorType } from '../../features'
import type { RequestConfig } from '../../types'
import { RequestConfigBuilder } from './RequestConfigBuilder'

type HttpError = Error & {
  isHttpError: true
  status: number
  statusText: string
}

function isHttpError(error: unknown): error is HttpError {
  return (
    error instanceof Error &&
    'isHttpError' in error &&
    (error as HttpError).isHttpError === true &&
    typeof (error as HttpError).status === 'number' &&
    typeof (error as HttpError).statusText === 'string'
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
   * Executes an HTTP request and returns the JSON response.
   * Throws navigation errors for HTTP and network failures.
   */
  async executeRequest(config: RequestConfig): Promise<unknown> {
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
        const error = new Error(
          `HTTP ${response.status}: ${response.statusText}`
        ) as HttpError
        error.isHttpError = true
        error.status = response.status
        error.statusText = response.statusText
        throw error
      }

      // await so JSON parse errors are caught by the surrounding try/catch
      return await response.json()
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
