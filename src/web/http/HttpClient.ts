import type {
  RequestConfig,
  TransformableRequestConfig,
} from '../../types'
import type { NavigationErrorType } from '../../features'

/**
 * HTTP client for making browser API requests.
 * Handles request config merging, URL building, and error handling.
 */
export class HttpClient {
  private baseRequestConfig: Partial<TransformableRequestConfig>

  constructor(baseRequestConfig: Partial<TransformableRequestConfig> = {}) {
    this.baseRequestConfig = baseRequestConfig
  }

  /**
   * Updates the base request configuration used for all requests.
   */
  setBaseRequestConfig(config: Partial<TransformableRequestConfig>): void {
    this.baseRequestConfig = config
  }

  /**
   * Merges multiple request configs with proper header and query parameter merging.
   * Later configs override earlier ones, but headers and query params are merged.
   */
  mergeRequestConfig(
    base: Partial<TransformableRequestConfig>,
    overrides: Partial<RequestConfig>
  ): RequestConfig {
    return {
      ...this.baseRequestConfig,
      ...base,
      ...overrides,
      headers: {
        ...this.baseRequestConfig.headers,
        ...base?.headers,
        ...overrides?.headers,
      },
      query: {
        ...this.baseRequestConfig.query,
        ...base?.query,
        ...overrides?.query,
      },
    }
  }

  /**
   * Executes an HTTP request and returns the JSON response.
   * Throws navigation errors for HTTP and network failures.
   */
  async executeRequest(config: RequestConfig): Promise<unknown> {
    const url = this.buildUrl(config)
    const headers = config.headers ?? {}
    const method = config.method ?? 'GET'

    try {
      const response = await fetch(url, {
        method,
        headers,
      })

      if (!response.ok) {
        const error = new Error(`HTTP ${response.status}: ${response.statusText}`) as Error & {
          isHttpError: boolean
          status: number
          statusText: string
        }
        error.isHttpError = true
        error.status = response.status
        error.statusText = response.statusText
        throw error
      }

      return response.json()
    } catch (error: unknown) {
      // Distinguish between network errors and HTTP errors
      if (
        error instanceof Error &&
        'isHttpError' in error &&
        error.isHttpError &&
        'status' in error &&
        'statusText' in error &&
        typeof error.status === 'number' &&
        typeof error.statusText === 'string'
      ) {
        const httpError = error as Error & { status: number; statusText: string }
        const navError = new Error(httpError.message) as Error & {
          code: NavigationErrorType
          statusCode: number
        }
        navError.code = 'http-error'
        navError.statusCode = httpError.status
        throw navError
      } else {
        // Network error (fetch failed, CORS, etc.)
        const message = error instanceof Error ? error.message : 'Failed to fetch'
        console.error(`Network error fetching ${url}:`, error)
        const navError = new Error(message) as Error & {
          code: NavigationErrorType
        }
        navError.code = 'network-error'
        throw navError
      }
    }
  }

  /**
   * Builds a complete URL from request config.
   * Handles baseUrl, path, and query parameters.
   */
  private buildUrl(config: RequestConfig): string {
    let url = config.baseUrl ?? ''
    const path = config.path ?? ''

    // Append path
    if (path) {
      url = url.endsWith('/') ? url.slice(0, -1) : url
      url += path.startsWith('/') ? path : `/${path}`
    }

    // Append query parameters
    const query = config.query
    if (query && Object.keys(query).length > 0) {
      const params = new URLSearchParams()
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== null) {
          params.append(key, String(value))
        }
      }
      const queryString = params.toString()
      if (queryString) {
        url += `?${queryString}`
      }
    }

    return url
  }
}
