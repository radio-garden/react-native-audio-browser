type Callback<T> = (arg: T) => void

/**
 * A lightweight event emitter that lazily bridges native callbacks to multiple JavaScript listeners.
 *
 * The native callback is only assigned when the first listener is added, making the emitter
 * creation lightweight with no side effects until actually used.
 *
 * @example
 * ```typescript
 * let emitter = new LazyEmitter(TrackPlayer.onPlaybackStateChanged)
 *
 * function onPlaybackState(callback: (state: PlaybackState) => void) {
 *   return emitter.addListener(callback)
 * }
 * ```
 */
export class LazyEmitter<T> {
  private setter: (callback: (data: T) => void) => void
  private listeners: Set<(arg: T) => void> | undefined

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

  addListener(callback: Callback<T>): () => void {
    const listeners = this.getListeners()
    listeners.add(callback)
    return () => listeners.delete(callback)
  }

  /**
   * Creates a lazy emitter subscription function for a native callback.
   * @param setter - Function that sets the native callback property
   * @returns A subscription function that returns an unsubscribe function
   */
  static emitterize<T>(
    setter: (callback: (data: T) => void) => void
  ): (callback: Callback<T>) => () => void {
    const emitter = new LazyEmitter(setter)
    return (callback: Callback<T>) => emitter.addListener(callback)
  }
}
