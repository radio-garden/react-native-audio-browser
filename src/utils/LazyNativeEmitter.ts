type Callback<T> = (arg: T) => void

/**
 * The subscription object behind the remote-control exports (`onRemotePlay`,
 * `onRemoteSeek`, …) and `onGate`. Subscribe with {@link addListener}; the
 * returned function unsubscribes. The emitter is not callable itself.
 *
 * Native starts delivering the event only once the first listener is added.
 * The other `on*` exports use `NativeUpdatedValue`, which listens from module
 * load.
 *
 * @example
 * ```typescript
 * import { onRemotePlay } from 'react-native-audio-browser'
 *
 * const unsubscribe = onRemotePlay.addListener(() => console.log('play'))
 * // later:
 * unsubscribe()
 * ```
 */
export class LazyNativeEmitter<T> {
  private setter: (callback: (data: T) => void) => void
  private listeners: Set<(arg: T) => void> | undefined

  /** @internal */
  constructor(setter: (callback: (data: T) => void) => void) {
    this.setter = setter
  }

  private getListeners(): Set<Callback<T>> {
    if (this.listeners) return this.listeners
    const listeners = (this.listeners = new Set())
    this.setter((data: T) => {
      for (const listener of listeners) {
        listener(data)
      }
    })
    return listeners
  }

  /**
   * Subscribes to the event.
   *
   * @param callback - Invoked with each event
   * @returns A function that removes the listener
   */
  addListener(callback: Callback<T>): () => void {
    const listeners = this.getListeners()
    listeners.add(callback)
    return () => listeners.delete(callback)
  }

  /**
   * Creates a lazy emitter for a native callback.
   *
   * @param setter - Function that sets the native callback property
   * @returns The emitter instance — subscribe with `addListener`, which returns
   * an unsubscribe function
   * @internal
   */
  static emitterize<T>(
    setter: (callback: (data: T) => void) => void
  ): LazyNativeEmitter<T> {
    return new LazyNativeEmitter(setter)
  }
}
