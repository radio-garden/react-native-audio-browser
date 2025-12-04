/** @internal */
type Callback<T> = (arg: T) => void

/**
 * @internal
 * Bridges native value changes to JavaScript, storing the latest value for immediate access.
 *
 * Subscribes to native changes immediately at module load to ensure no updates are missed.
 * The `lastValue` property caches the most recent value, allowing hooks to read the current
 * state synchronously without calling a separate getter function.
 *
 * Use this for state-based values that have a "current value" (playback state, options, progress).
 * For discrete events without a current value (remote controls), use LazyNativeEmitter instead.
 *
 * @example
 * ```typescript
 * export const onPlaybackChanged = NativeUpdatedValue.emitterize<Playback>(
 *   (cb) => (nativePlayer.onPlaybackChanged = cb)
 * )
 *
 * export function usePlayback(): Playback {
 *   return useUpdatedNativeValue(getPlayback, onPlaybackChanged)
 * }
 * ```
 */
export class NativeUpdatedValue<T> {
  private listeners = new Set<Callback<T>>()
  lastValue: T | undefined = undefined

  constructor(setter: (callback: (data: T) => void) => void) {
    // Install native callback immediately at module load
    setter((data: T) => {
      this.lastValue = data
      for (const listener of this.listeners) {
        listener(data)
      }
    })
  }

  addListener(callback: Callback<T>): () => void {
    this.listeners.add(callback)
    return () => this.listeners.delete(callback)
  }

  /**
   * Creates a native value subscription.
   *
   * @param setter - Function that sets the native callback property
   * @returns The subscription instance with lastValue and addListener
   */
  static emitterize<T>(
    setter: (callback: (data: T) => void) => void
  ): NativeUpdatedValue<T> {
    return new NativeUpdatedValue(setter)
  }
}
