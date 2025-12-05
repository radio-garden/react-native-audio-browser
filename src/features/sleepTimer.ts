import { useEffect, useState } from 'react'
import { nativeBrowser } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'

// MARK: - Types

/**
 * Time-based sleep timer that stops playback after a specific time.
 */
export type SleepTimerTime = {
  /** Time when playback should stop (milliseconds since epoch) */
  time: number
}

/**
 * End-of-track sleep timer that stops playback when current track finishes.
 */
export type SleepTimerEndOfTrack = {
  /** Whether to sleep when current track finishes playing */
  sleepWhenPlayedToEnd: boolean
}

/**
 * Sleep timer configuration:
 * - `SleepTimerTime`: Stop playback after a specific time
 * - `SleepTimerEndOfTrack`: Stop playback after current track finishes
 * - `null`: No sleep timer active
 */
export type SleepTimer = SleepTimerTime | SleepTimerEndOfTrack | null

export type SleepTimerChangedEvent = SleepTimer

// MARK: - Getters and Setters

/**
 * Gets the current sleep timer state.
 */
export function getSleepTimer(): SleepTimer {
  return nativeBrowser.getSleepTimer()
}

/**
 * Sets a sleep timer to stop playback after the specified duration.
 * @param seconds - Number of seconds until playback stops
 */
export function setSleepTimer(seconds: number): void {
  nativeBrowser.setSleepTimer(seconds)
}

/**
 * Sets a sleep timer to stop playback when the current track finishes playing.
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
 * @param callback - Called when the sleep timer state changes
 * @returns Cleanup function to unsubscribe
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
  const [isActive, setIsActive] = useState(() => getSleepTimer() !== null)

  useEffect(() => {
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

  const [state, setState] = useState<SleepTimerState | undefined>(() => {
    const timerState = getSleepTimer()
    return addSecondsLeft(timerState)
  })

  const time = state && 'time' in state ? state.time : undefined

  useEffect(() => {
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
    const initialTimeoutId = setTimeout(() => {
      update()
      intervalId = setInterval(update, updateInterval)
    }, updateInterval - (time % updateInterval))
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

function addSecondsLeft(
  state: SleepTimer
): SleepTimerState | undefined {
  if (!state) return undefined
  if ('time' in state) {
    return {
      ...state,
      secondsLeft: Math.max(0, Math.round((state.time - Date.now()) / 1000)),
    }
  }
  return state
}
