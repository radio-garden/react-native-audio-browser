type Callback<T> = (arg: T) => void

/**
 * The subscription object behind most `on*` exports (`onPlaybackChanged`,
 * `onQueueChanged`, `onTrackMetadata`, …). Subscribe with {@link addListener};
 * the returned function unsubscribes. The emitter is not callable itself.
 *
 * It listens to native from module load and keeps the latest payload in
 * {@link lastValue}. The remote-control exports and `onGate` use
 * `LazyNativeEmitter`, which listens only once a listener is added.
 *
 * `lastValue` is the last payload native sent, not a fresh reading: it is
 * `undefined` before the first event, and for the queue and active track it
 * can be stale after an in-place change (a favorite toggle). Use the getter
 * (`getQueue`, `getActiveTrack`, …) when you need the current value.
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
   * The last payload native sent, or `undefined` before the first. Not a fresh
   * reading: use the matching getter (`getQueue`, …) for the current value.
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
