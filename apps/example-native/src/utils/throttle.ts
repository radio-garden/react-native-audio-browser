type ThrottleOptions = {
  /** If true, invoke on the leading edge (first call). Default: true */
  leading?: boolean
  /** If true, invoke on the trailing edge (last call in window). Default: false */
  trailing?: boolean
}

/**
 * Creates a throttled version of a function that only invokes at most once
 * per `wait` milliseconds.
 */
export function throttle<Args extends unknown[]>(
  fn: (...args: Args) => void,
  wait: number,
  { leading = true, trailing = false }: ThrottleOptions = {}
): (...args: Args) => void {
  let lastCall = leading ? 0 : Date.now()
  let timer: ReturnType<typeof setTimeout> | null = null
  let lastArgs: Args | null = null

  return (...args: Args) => {
    const now = Date.now()

    if (now - lastCall >= wait) {
      // Window has passed - fire immediately
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      lastCall = now
      lastArgs = null
      fn(...args)
    } else if (trailing) {
      // Within window - store last args for trailing call
      lastArgs = args
      // Schedule trailing call if not already scheduled
      if (!timer) {
        const remaining = wait - (now - lastCall)
        timer = setTimeout(() => {
          timer = null
          lastCall = Date.now()
          if (lastArgs) {
            fn(...lastArgs)
            lastArgs = null
          }
        }, remaining)
      }
    }
  }
}
