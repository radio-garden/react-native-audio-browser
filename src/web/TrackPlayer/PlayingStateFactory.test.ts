import { describe, expect, it } from 'vitest'
import type { PlaybackState } from '../../features'
import { derivePlayingState } from './PlayingStateFactory'

// Ported from the native truth table (android PlayingStateFactoryTest.kt /
// ios PlayingStateManager) so web derives PlayingState identically.
const ALL_STATES: PlaybackState[] = [
  'none',
  'ready',
  'playing',
  'paused',
  'stopped',
  'loading',
  'buffering',
  'error',
  'ended'
]

describe('derivePlayingState', () => {
  it('playWhenReady false is never playing nor buffering', () => {
    for (const state of ALL_STATES) {
      expect(derivePlayingState(false, state)).toEqual({
        playing: false,
        buffering: false
      })
    }
  })

  it('playWhenReady true plays except in terminal or empty states', () => {
    const notPlaying = new Set<PlaybackState>(['error', 'ended', 'none'])
    for (const state of ALL_STATES) {
      expect(derivePlayingState(true, state).playing).toBe(!notPlaying.has(state))
    }
  })

  it('buffers only while loading or rebuffering with playWhenReady', () => {
    const buffering = new Set<PlaybackState>(['loading', 'buffering'])
    for (const state of ALL_STATES) {
      expect(derivePlayingState(true, state).buffering).toBe(
        buffering.has(state)
      )
    }
  })
})
