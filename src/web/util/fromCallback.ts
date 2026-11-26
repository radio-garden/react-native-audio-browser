import type { NavigationErrorType } from '../../features'

/**
 * Runs `call`, a call into a JS callback: what it throws or rejects with
 * becomes an `Error` with its message, `code: 'callback-error'`, and the
 * original as `cause`.
 */
export async function fromCallback<T>(call: () => T | Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (error) {
    throw Object.assign(
      new Error(error instanceof Error ? error.message : String(error), {
        cause: error
      }),
      { code: 'callback-error' satisfies NavigationErrorType }
    )
  }
}
