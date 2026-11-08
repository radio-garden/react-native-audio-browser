import { useEffect, useRef, useState } from 'react'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'

// MARK: - Types

/**
 * Time-based sleep timer that pauses playback after a specific time.
 */
export type SleepTimerTime = {
  /** Time when playback should pause (milliseconds since epoch) */
  time: number
}

/**
 * End-of-track sleep timer that pauses playback when current track finishes.
 */
export type SleepTimerEndOfTrack = {
  /** Whether to sleep when current track finishes playing */
  sleepWhenPlayedToEnd: boolean
}

/**
 * Sleep timer configuration:
 * - `SleepTimerTime`: Pause playback after a specific time
 * - `SleepTimerEndOfTrack`: Pause playback after current track finishes
 * - `null`: No sleep timer active
 */
export type SleepTimer = SleepTimerTime | SleepTimerEndOfTrack | null

/**
 * Payload of `onSleepTimerChanged`: the new timer, or `null` once it is
 * cleared or has fired.
 */
export type SleepTimerChangedEvent = SleepTimer

// MARK: - Getters and Setters

/**
 * Gets the current sleep timer state.
 */
export function getSleepTimer(): SleepTimer {
  return nativeBrowser.getSleepTimer()
}

/**
 * Options for `setSleepTimer`.
 */
export type SleepTimerOptions = {
  /**
   * Seconds over which to fade the volume out before the timer completes.
   * The fade ends exactly at the deadline (clamped to the timer duration if
   * longer), playback is paused, and the pre-fade volume is restored so the
   * next play starts at normal loudness. During the fade the timer wins:
   * skips and seeks play into the remaining ramp; only pausing or
   * clearing/replacing the timer cancels it.
   */
  fadeDuration?: number
}

/**
 * Sets a sleep timer to pause playback after the specified duration.
 * @param seconds - Number of seconds until playback pauses
 * @param options - Optional fade-out configuration
 */
export function setSleepTimer(
  seconds: number,
  options?: SleepTimerOptions
): void {
  nativeBrowser.setSleepTimer(seconds, options?.fadeDuration)
}

/**
 * Sets a sleep timer to pause playback when the current track finishes playing.
 * A live stream never ends, so on one this timer never fires; use
 * `setSleepTimer` instead. On iOS a change of current track before it ends
 * (skip, new queue) cancels the timer. On Android and web it stays set until a
 * track plays to its end. On Android it never fires while the repeat mode is
 * `track`.
 */
export function setSleepTimerToEndOfTrack(): void {
  nativeBrowser.setSleepTimerToEndOfTrack()
}

/**
 * Clears the active sleep timer.
 * @returns true if a timer was cleared, false if no timer was active
 */
export function clearSleepTimer(): boolean {
  return nativeBrowser.clearSleepTimer()
}

// MARK: - Event Callbacks

/**
 * Subscribes to sleep timer changes.
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onSleepTimerChanged = NativeUpdatedValue.emitterize<SleepTimer>(
  (cb) => (nativeBrowser.onSleepTimerChanged = cb)
)

// MARK: - Hooks

/**
 * Hook that returns whether a sleep timer is currently active.
 * This is a lightweight alternative to useSleepTimer when you only need to know if a timer is set.
 *
 * @returns true if any sleep timer is active, false otherwise
 */
export function useSleepTimerActive(): boolean {
  const eventCountAtRender = useRef(onSleepTimerChanged.eventCount)
  const [isActive, setIsActive] = useState(() => getSleepTimer() !== null)

  useEffect(() => {
    if (onSleepTimerChanged.eventCount !== eventCountAtRender.current) {
      setIsActive(getSleepTimer() !== null)
    }
    return onSleepTimerChanged.addListener((timer) => {
      setIsActive(timer !== null)
    })
  }, [])

  return isActive
}

/**
 * Hook that returns the current sleep timer state and time left.
 *
 * Note that time left is not updated when the app is in the background.
 *
 * @param params - Optional configuration object
 * @param params.updateInterval - ms interval at which the time left is updated. Defaults to 1000 (1 second).
 * @param params.inactive - Whether the app is in the background. If true, time left updates are paused.
 * @returns The current sleep timer state with secondsLeft calculated
 */
export function useSleepTimer(params?: {
  updateInterval?: number
  inactive?: boolean
}): SleepTimerState | undefined {
  const updateInterval = params?.updateInterval ?? 1000
  const inactive = params?.inactive ?? false

  const eventCountAtRender = useRef(onSleepTimerChanged.eventCount)
  const [state, setState] = useState<SleepTimerState | undefined>(() => {
    const timerState = getSleepTimer()
    return addSecondsLeft(timerState)
  })

  const time = state && 'time' in state ? state.time : undefined

  useEffect(() => {
    if (onSleepTimerChanged.eventCount !== eventCountAtRender.current) {
      setState(addSecondsLeft(getSleepTimer()))
    }
    return onSleepTimerChanged.addListener((event) => {
      setState(addSecondsLeft(event))
    })
  }, [])

  useEffect(() => {
    if (inactive || time === undefined) return

    const update = () => {
      setState((sleepTimer) => {
        const result = addSecondsLeft(
          sleepTimer
            ? 'time' in sleepTimer
              ? { time: sleepTimer.time }
              : sleepTimer
            : null
        )
        if (result && 'secondsLeft' in result && result.secondsLeft === 0) {
          clear()
        }
        return result
      })
    }

    // In order to make time update reaching 0 sync with firing of completion,
    // first wait for the next interval to start before starting update loop
    const initialTimeoutId = setTimeout(
      () => {
        update()
        intervalId = setInterval(update, updateInterval)
      },
      updateInterval - (time % updateInterval)
    )
    let intervalId: ReturnType<typeof setInterval> | undefined
    const clear = () => {
      clearTimeout(initialTimeoutId)
      if (intervalId) clearInterval(intervalId)
    }
    update()
    return clear
  }, [inactive, time, updateInterval])

  return state
}

// MARK: - Private Helpers

/**
 * State returned by `useSleepTimer` hook.
 * - Time-based timer includes `secondsLeft` countdown
 * - End-of-track timer includes `sleepWhenPlayedToEnd` flag
 */
export type SleepTimerState =
  | { time: number; secondsLeft: number }
  | { sleepWhenPlayedToEnd: boolean }

function addSecondsLeft(state: SleepTimer): SleepTimerState | undefined {
  if (!state) return undefined
  if ('time' in state) {
    return {
      ...state,
      secondsLeft: Math.max(0, Math.round((state.time - Date.now()) / 1000))
    }
  }
  return state
}
