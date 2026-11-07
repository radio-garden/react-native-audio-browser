import { useEffect, useState } from 'react'
import { NativeUpdatedValue } from './NativeUpdatedValue'

/**
 * Generic hook for values that are fetched from native and updated via callbacks.
 *
 * Reads the getter rather than `emitter.lastValue`, which is stale after a
 * change that emits nothing, and reads it again on subscribing so a change
 * between render and subscription is not missed.
 *
 * @param getter - Synchronous function returning the current value
 * @param emitter - NativeUpdatedValue that provides the subscription
 * @param eventKey - Optional key to extract from the event object passed to the callback
 * @returns The current value, updated when the callback fires
 */
export function useNativeUpdatedValue<T, E = T>(
  getter: () => T,
  emitter: NativeUpdatedValue<E>,
  eventKey?: keyof E
): T {
  const [value, setValue] = useState(getter)

  useEffect(() => {
    setValue(getter)
    return emitter.addListener((event) => {
      const newValue = eventKey !== undefined ? event[eventKey] : event
      setValue(newValue as T)
    })
    // The getter is read once per subscription: an inline one, new each render,
    // would otherwise overwrite every event with a re-read.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [emitter, eventKey])

  return value
}
