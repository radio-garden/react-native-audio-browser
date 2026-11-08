import { useEffect, useRef, useState } from 'react'
import { NativeUpdatedValue } from './NativeUpdatedValue'

/**
 * Generic hook for values that are fetched from native and updated via callbacks.
 *
 * Reads the getter on mount, and again on subscribing only if an event landed
 * since, so a change between render and subscription is not missed.
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
  const eventCountAtRender = useRef(emitter.eventCount)
  const [value, setValue] = useState(getter)

  useEffect(() => {
    if (emitter.eventCount !== eventCountAtRender.current) setValue(getter)
    // A re-run, for another emitter or eventKey, always reads afresh.
    eventCountAtRender.current = NaN
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
