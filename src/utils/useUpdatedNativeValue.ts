import { useEffect, useState } from 'react'

/**
 * Generic hook for values that are fetched from native and updated via callbacks.
 *
 * @param getter - Synchronous function to get the current value from native
 * @param subscribe - Function that takes a callback and returns a cleanup function
 * @param eventKey - Optional key to extract from the event object passed to the callback
 * @returns The current value, updated when the callback fires
 */
export function useUpdatedNativeValue<T, E = T>(
  getter: () => T,
  subscribe: (callback: (event: E) => void) => () => void,
  eventKey?: keyof E
): T {
  const [value, setValue] = useState(() => getter())

  useEffect(() => {
    return subscribe((event) => {
      const newValue = eventKey !== undefined ? event[eventKey] : event
      setValue(newValue as T)
    })
  }, [subscribe, eventKey])

  return value
}
