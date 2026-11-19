type Callback<T> = (arg: T) => void

/**
 * The subscription object behind most `on*` exports (`onPlaybackChanged`,
 * `onQueueChanged`, `onTrackMetadata`, …). Subscribe with {@link addListener};
 * the returned function unsubscribes. The emitter is not callable itself.
 *
 * It listens to native from module load. The remote-control exports and
 * `onGate` use `LazyNativeEmitter`, which listens only once a listener is
 * added. It keeps no payload: use the getter (`getQueue`, `getActiveTrack`, …)
 * for the current value.
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
   * Events received so far, so a hook can tell whether one landed between its
   * render and its subscription.
   * @internal
   */
  eventCount = 0

  /** @internal */
  constructor(setter: (callback: (data: T) => void) => void) {
    // Install native callback immediately at module load
    setter((data: T) => {
      this.eventCount++
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
   * @returns The subscription instance
   * @internal
   */
  static emitterize<T>(
    setter: (callback: (data: T) => void) => void
  ): NativeUpdatedValue<T> {
    return new NativeUpdatedValue(setter)
  }
}
