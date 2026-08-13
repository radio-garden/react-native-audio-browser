import { useEffect, useState } from 'react'

type Subscribable = {
  addListener(callback: (event: unknown) => void): () => void
}

/**
 * Hook for a native-fetched value that must stay fresh across SEVERAL event
 * sources — e.g. the active track, which changes on a queue transition
 * (`onActiveTrackChanged`) but is also mutated in place by a favorite toggle
 * (`onFavoriteChanged`).
 *
 * Unlike {@link useNativeUpdatedValue}, this never reads a cached event
 * payload: every trigger re-reads `getter()`, and the mount effect re-reads
 * once after the subscriptions attach (so an event firing between render and
 * effect cannot be missed). The getters are cheap synchronous Nitro calls.
 *
 * @param getter - Synchronous native getter for the current value
 * @param emitters - Event sources that invalidate the value (a stable,
 *   module-level array — identity changes resubscribe)
 */
export function useNativeValueRefreshedBy<T>(
  getter: () => T,
  emitters: readonly Subscribable[]
): T {
  const [value, setValue] = useState(getter)

  useEffect(() => {
    setValue(getter())
    const cleanups = emitters.map((emitter) =>
      emitter.addListener(() => setValue(getter()))
    )
    return () => cleanups.forEach((cleanup) => cleanup())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [getter, ...emitters])

  return value
}
