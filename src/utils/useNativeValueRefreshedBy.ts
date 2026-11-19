import { useEffect, useRef, useState } from 'react'

type Subscribable = {
  addListener(callback: (event: unknown) => void): () => void
  eventCount: number
}

const eventCountOf = (emitters: readonly Subscribable[]) =>
  emitters.reduce((sum, emitter) => sum + emitter.eventCount, 0)

/**
 * Hook for a native-fetched value that must stay fresh across SEVERAL event
 * sources — e.g. the active track, which changes on a queue transition
 * (`onActiveTrackChanged`) but is also mutated in place by a favorite toggle
 * (`onFavoriteChanged`).
 *
 * Every trigger re-reads `getter()`, and the mount effect re-reads it only if
 * an event landed since the render read, so none is missed.
 *
 * @param getter - Synchronous native getter for the current value
 * @param emitters - Event sources that invalidate the value (a stable,
 *   module-level array — identity changes resubscribe)
 */
export function useNativeValueRefreshedBy<T>(
  getter: () => T,
  emitters: readonly Subscribable[]
): T {
  const eventCountAtRender = useRef(eventCountOf(emitters))
  const [value, setValue] = useState(getter)

  useEffect(() => {
    if (eventCountOf(emitters) !== eventCountAtRender.current) {
      setValue(getter())
    }
    // A re-run, for another getter or emitters, always reads afresh.
    eventCountAtRender.current = NaN
    const cleanups = emitters.map((emitter) =>
      emitter.addListener(() => setValue(getter()))
    )
    return () => cleanups.forEach((cleanup) => cleanup())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [getter, ...emitters])

  return value
}
