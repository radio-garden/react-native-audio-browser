type Callback<T> = (arg: T) => void

/**
 * The subscription object behind the library's **state** events — every
 * `on<Thing>Changed`-style export (`onPlaybackChanged`, `onQueueChanged`, …)
 * is one of these. Subscribe with {@link addListener}; the returned function
 * unsubscribes. The emitter is not callable itself.
 *
 * It tracks a value that always has a *current* reading (playback state,
 * progress, options): {@link lastValue} caches the most recent one so hooks —
 * and you — can read it synchronously without a getter round-trip. Discrete
 * events with no current value (remote-control presses) use
 * `LazyNativeEmitter` instead.
 *
 * @example
 * ```typescript
 * import { onPlaybackChanged } from 'react-native-audio-browser'
 *
 * const unsubscribe = onPlaybackChanged.addListener((p) => {
 *   console.log(p.state)
 * })
 * // later:
 * unsubscribe()
 * ```
 */
export class NativeUpdatedValue<T> {
  private listeners = new Set<Callback<T>>()

  /**
   * The most recent value delivered by native, or `undefined` before the
   * first update. Subscription starts at module load, so this is safe to
   * read synchronously at any time.
   */
  lastValue: T | undefined = undefined

  /** @internal */
  constructor(setter: (callback: (data: T) => void) => void) {
    // Install native callback immediately at module load
    setter((data: T) => {
      this.lastValue = data
      for (const listener of this.listeners) {
        listener(data)
      }
    })
  }

  /**
   * Subscribes to value changes.
   *
   * @param callback - Invoked with each new value
   * @returns A function that removes the listener
   */
  addListener(callback: Callback<T>): () => void {
    this.listeners.add(callback)
    return () => this.listeners.delete(callback)
  }

  /**
   * Creates a native value subscription.
   *
   * @param setter - Function that sets the native callback property
   * @returns The subscription instance with lastValue and addListener
   * @internal
   */
  static emitterize<T>(
    setter: (callback: (data: T) => void) => void
  ): NativeUpdatedValue<T> {
    return new NativeUpdatedValue(setter)
  }
}
