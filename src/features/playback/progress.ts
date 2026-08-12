import { useEffect, useState } from 'react'
import { AppState } from 'react-native'
import { nativeBrowser } from '../../native'
import { NativeUpdatedValue } from '../../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../../utils/useNativeUpdatedValue'
import { onPlaybackChanged } from './state'

// MARK: - Types

export interface Progress {
  /**
   * The playback position of the current track in seconds.
   **/
  position: number
  /**
   * The duration of the current track in seconds.
   **/
  duration: number
  /**
   * The buffered position of the current track in seconds.
   **/
  buffered: number
}

/**
 * Event data for playback progress updates.
 */
export interface PlaybackProgressUpdatedEvent extends Progress {
  /** The current track index */
  track: number
}

// MARK: - Getters

/**
 * Gets information on the progress of the currently active track, including its
 * current playback position in seconds, buffered position in seconds and
 * duration in seconds.
 */
export function getProgress(): Progress {
  return nativeBrowser.getProgress()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback progress updates.
 * @param callback - Called periodically with playback progress updates
 * @returns An emitter — subscribe with `addListener(callback)`, which returns a cleanup function
 */
export const onProgressUpdated =
  NativeUpdatedValue.emitterize<PlaybackProgressUpdatedEvent>(
    (cb) => (nativeBrowser.onPlaybackProgressUpdated = cb)
  )

// MARK: - Hooks

/**
 * Hook that returns the current playback progress and updates when it changes.
 *
 * Progress update frequency is controlled globally via `updateOptions({ progressUpdateEventInterval })`.
 * @returns The current playback progress
 */
export function useProgress(): Progress {
  return useNativeUpdatedValue(getProgress, onProgressUpdated)
}

/**
 * Hook that returns the current playback progress and updates via polling.
 *
 * Use this when you need custom polling behavior instead of event-based updates.
 *
 * Skips updates while the app is backgrounded and refreshes once it becomes
 * active again.
 *
 * @param updateInterval - Update interval in milliseconds (default: 1000)
 * @returns The current playback progress
 */
export function usePolledProgress(updateInterval = 1000): Progress {
  const [state, setState] = useState<Progress>({
    position: 0,
    duration: 0,
    buffered: 0
  })

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    let unsubscribeState: (() => void) | undefined

    const update = () => {
      try {
        const { position, duration, buffered } = getProgress()

        setState((currentState) =>
          position === currentState.position &&
          duration === currentState.duration &&
          buffered === currentState.buffered
            ? currentState
            : { position, duration, buffered }
        )
      } catch {
        // Ignore failures (e.g., before setup)
      }
    }

    const poll = () => {
      update()
      timer = setTimeout(poll, updateInterval)
    }

    // We only poll and listen for playback changes while the app is active. On
    // return to the foreground we update once and restart the loop so the
    // interval timing stays correct.
    const start = () => {
      if (timer !== undefined) return
      unsubscribeState = onPlaybackChanged.addListener(update)
      poll()
    }

    const stop = () => {
      clearTimeout(timer)
      timer = undefined
      unsubscribeState?.()
      unsubscribeState = undefined
    }

    const appStateSub = AppState.addEventListener('change', (next) => {
      if (next === 'active') start()
      else stop()
    })

    if (AppState.currentState === 'active') start()

    return () => {
      stop()
      appStateSub.remove()
    }
  }, [updateInterval])

  return state
}
