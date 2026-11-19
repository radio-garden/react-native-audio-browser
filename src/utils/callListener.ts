/**
 * Calls `listener` with `arg`. An error it throws is rethrown from a microtask,
 * so it reaches the global error handler and the caller's loop goes on to the
 * listeners after it.
 */
export function callListener<T>(listener: (arg: T) => void, arg: T): void {
  try {
    listener(arg)
  } catch (error) {
    queueMicrotask(() => {
      throw error
    })
  }
}
