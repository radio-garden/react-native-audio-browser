import { useEffect, useState } from 'react'
import { nativePlayer } from '../native'
import { NativeUpdatedValue } from '../utils/NativeUpdatedValue'
import { useNativeUpdatedValue } from '../utils/useNativeUpdatedValue'
import { onPlaybackChanged } from './playbackState'

// MARK: - Types

export interface Progress {
  /**
   * The playback position of the current track in seconds.
   * See https://rntp.dev/docs/api/functions/player#getposition
   **/
  position: number
  /** The duration of the current track in seconds.
   * See https://rntp.dev/docs/api/functions/player#getduration
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
  return nativePlayer.getProgress()
}

// MARK: - Event Callbacks

/**
 * Subscribes to playback progress updates.
 * @param callback - Called periodically with playback progress updates
 * @returns Cleanup function to unsubscribe
 */
export const onProgressUpdated =
  NativeUpdatedValue.emitterize<PlaybackProgressUpdatedEvent>(
    (cb) => (nativePlayer.onPlaybackProgressUpdated = cb)
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
    let mounted = true

    const update = () => {
      try {
        const { position, duration, buffered } = getProgress()
        if (!mounted) return

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

    // Update immediately on playback state changes
    const unsubscribeState = onPlaybackChanged.addListener(update)

    const poll = () => {
      update()
      if (!mounted) return
      setTimeout(() => {
        if (mounted) poll()
      }, updateInterval)
    }

    poll()

    return () => {
      mounted = false
      unsubscribeState()
    }
  }, [updateInterval])

  return state
}
