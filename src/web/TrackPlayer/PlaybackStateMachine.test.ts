import { describe, expect, it } from 'vitest'
import type { PlaybackState } from '../../features'
import { nextPlaybackState } from './PlaybackStateMachine'

// Ported from the native truth tables (ios/Tests/PlaybackStateMachineTests.swift,
// android/.../PlaybackStateMachineTest.kt) so web stays behaviourally in sync.
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

describe('nextPlaybackState', () => {
  describe('unconditional events — always produce their target state', () => {
    it('trackLoading → loading from any state', () => {
      for (const state of ALL_STATES) {
        expect(nextPlaybackState(state, { type: 'trackLoading' })).toBe('loading')
      }
    })

    it('trackEndedNaturally → ended from any state', () => {
      for (const state of ALL_STATES) {
        expect(nextPlaybackState(state, { type: 'trackEndedNaturally' })).toBe(
          'ended'
        )
      }
    })

    it('waiting → buffering from any state', () => {
      for (const state of ALL_STATES) {
        expect(nextPlaybackState(state, { type: 'waiting' })).toBe('buffering')
      }
    })

    it('playing → playing from any state', () => {
      for (const state of ALL_STATES) {
        expect(nextPlaybackState(state, { type: 'playing' })).toBe('playing')
      }
    })
  })

  describe('loadSeekCompleted — only transitions from loading', () => {
    it('from loading → ready', () => {
      expect(nextPlaybackState('loading', { type: 'loadSeekCompleted' })).toBe(
        'ready'
      )
    })

    it('from any other state → suppressed', () => {
      for (const state of ALL_STATES.filter((s) => s !== 'loading')) {
        expect(
          nextPlaybackState(state, { type: 'loadSeekCompleted' })
        ).toBeNull()
      }
    })
  })

  describe('paused — conditional on current state and hasAsset', () => {
    it('from stopped → suppressed', () => {
      expect(
        nextPlaybackState('stopped', { type: 'paused', hasAsset: true })
      ).toBeNull()
      expect(
        nextPlaybackState('stopped', { type: 'paused', hasAsset: false })
      ).toBeNull()
    })

    it('from error with an asset → suppressed (preserves the error)', () => {
      expect(
        nextPlaybackState('error', { type: 'paused', hasAsset: true })
      ).toBeNull()
    })

    it('without an asset → none from any non-stopped state', () => {
      for (const state of ALL_STATES.filter((s) => s !== 'stopped')) {
        expect(
          nextPlaybackState(state, { type: 'paused', hasAsset: false })
        ).toBe('none')
      }
    })

    it('with an asset → paused from states other than stopped/error', () => {
      for (const state of ALL_STATES.filter(
        (s) => s !== 'stopped' && s !== 'error'
      )) {
        expect(
          nextPlaybackState(state, { type: 'paused', hasAsset: true })
        ).toBe('paused')
      }
    })
  })

  describe('bufferingSufficient — suppressed while playing', () => {
    it('from playing → suppressed', () => {
      expect(
        nextPlaybackState('playing', { type: 'bufferingSufficient' })
      ).toBeNull()
    })

    it('from any other state → ready', () => {
      for (const state of ALL_STATES.filter((s) => s !== 'playing')) {
        expect(
          nextPlaybackState(state, { type: 'bufferingSufficient' })
        ).toBe('ready')
      }
    })
  })
})
