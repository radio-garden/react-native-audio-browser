import type {
  NavigationError,
  NavigationErrorType,
  FormattedNavigationError,
  NavigationErrorEvent,
} from '../../features'
import type { NativeBrowserConfiguration } from '../../types/browser-native'

const ERROR_TITLES: Record<NavigationErrorType, string> = {
  'network-error': 'Network Error',
  'http-error': 'Server Error',
  'content-not-found': 'Content Not Found',
  'callback-error': 'Error',
  'unknown-error': 'Error',
}

/**
 * Manages navigation error state and formatting.
 * Handles both raw error objects and formatted user-facing error messages.
 */
export class NavigationErrorManager {
  private _navigationError: NavigationError | undefined
  private _formattedNavigationError: FormattedNavigationError | undefined
  private formatCallback?: NativeBrowserConfiguration['formatNavigationError']

  // Event callbacks
  onNavigationError: (data: NavigationErrorEvent) => void = () => {}
  onFormattedNavigationError: (formattedError: FormattedNavigationError | undefined) => void = () => {}

  /**
   * Sets the custom error formatter callback from configuration.
   */
  setFormatCallback(callback: NativeBrowserConfiguration['formatNavigationError']): void {
    this.formatCallback = callback
  }

  /**
   * Clears the current navigation error and emits clear events.
   */
  clearNavigationError(): void {
    if (this._navigationError !== undefined || this._formattedNavigationError !== undefined) {
      this._navigationError = undefined
      this._formattedNavigationError = undefined
      this.onNavigationError({ error: undefined })
      this.onFormattedNavigationError(undefined)
    }
  }

  /**
   * Sets a navigation error with the given code and message.
   * Automatically formats the error using the configured formatter or default formatting.
   *
   * @param code Error type code
   * @param message Error message
   * @param path Optional path where the error occurred
   * @param statusCode Optional HTTP status code for http-error types
   */
  setNavigationError(
    code: NavigationErrorType,
    message: string,
    path?: string,
    statusCode?: number
  ): void {
    const navError: NavigationError = {
      code,
      message,
      statusCode,
      statusCodeSuccess: undefined,
    }
    this._navigationError = navError
    this.onNavigationError({ error: navError })

    // Default formatted error
    const defaultFormatted: FormattedNavigationError = {
      title: ERROR_TITLES[code],
      message,
    }

    // Call custom formatter if available
    if (this.formatCallback && path) {
      try {
        const customFormatted = this.formatCallback({
          error: navError,
          defaultFormatted,
          path,
        })
        this._formattedNavigationError = customFormatted || defaultFormatted
      } catch (error) {
        console.error('Error in formatNavigationError callback:', error)
        this._formattedNavigationError = defaultFormatted
      }
    } else {
      this._formattedNavigationError = defaultFormatted
    }

    this.onFormattedNavigationError(this._formattedNavigationError)
  }

  /**
   * Gets the current raw navigation error.
   */
  getNavigationError(): NavigationError | undefined {
    return this._navigationError
  }

  /**
   * Gets the current formatted navigation error.
   */
  getFormattedNavigationError(): FormattedNavigationError | undefined {
    return this._formattedNavigationError
  }
}
