import { useEffect, useRef, useState } from 'react'

/**
 * Debounces value changes in one direction. When value changes to the
 * debounced value, waits for delay before updating. Changes away from
 * the debounced value are immediate.
 *
 * Useful for loading states where you want to delay showing a spinner
 * but hide it immediately when done.
 *
 * @param value - The value to debounce
 * @param debouncedValue - The value to delay transitioning to
 * @param delay - Delay in ms (default: 200ms)
 *
 * @example
 * // Delay showing loading, hide immediately
 * const showLoading = useDebouncedValue(!content, true)
 */
export function useDebouncedValue<T>(
  value: T,
  debouncedValue: T,
  delay = 200
): T {
  const [current, setCurrent] = useState(value)
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (value !== debouncedValue) {
      // Transitioning away from debounced value - update immediately
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
        timeoutRef.current = null
      }
      setCurrent(value)
    } else {
      // Transitioning to debounced value - delay
      timeoutRef.current = setTimeout(() => setCurrent(value), delay)
    }

    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
      }
    }
  }, [value, debouncedValue, delay])

  return current
}
