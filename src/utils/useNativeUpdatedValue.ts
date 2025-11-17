import { useEffect, useState } from 'react'
import { NativeUpdatedValue } from './NativeUpdatedValue'

/**
 * Generic hook for values that are fetched from native and updated via callbacks.
 *
 * Reads the initial value from emitter.lastValue to avoid race conditions between
 * initial getter() call and subscription setup.
 *
 * @param getter - Fallback synchronous function to get the current value if emitter has no lastValue yet
 * @param emitter - NativeUpdatedValue that stores lastValue and provides subscription
 * @param eventKey - Optional key to extract from the event object passed to the callback
 * @returns The current value, updated when the callback fires
 */
export function useNativeUpdatedValue<T, E = T>(
  getter: () => T,
  emitter: NativeUpdatedValue<E>,
  eventKey?: keyof E
): T {
  const [value, setValue] = useState(() => {
    const { lastValue } = emitter
    return (lastValue !== undefined
      ? eventKey !== undefined
        ? lastValue?.[eventKey]
        : lastValue
      : getter()) as T
  })

  useEffect(() => {
    if (emitter.lastValue !== undefined) {
      setValue(emitter.lastValue as T)
    }
    return emitter.addListener((event) => {
      const newValue = eventKey !== undefined ? event[eventKey] : event
      setValue(newValue as T)
    })
  }, [emitter, eventKey])

  return value
}
