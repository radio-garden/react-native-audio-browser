import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Track } from '../../types'
import { NowPlayingManager } from './NowPlayingManager'

const track = {
  src: 'https://example.com/stream',
  title: 'Track Title',
  artist: 'Track Artist',
  album: 'Track Album'
} as Track

const playing = () => ({ track, duration: 0 })

describe('NowPlayingManager', () => {
  it('applies the same override fields on push and pull', () => {
    const manager = new NowPlayingManager(playing)
    let pushed
    manager.onNowPlayingChanged = (metadata) => {
      pushed = metadata
    }

    manager.updateNowPlaying({
      title: 'Live Title',
      artist: 'Live Artist',
      album: 'Live Album'
    })
    const pulled = manager.getNowPlaying()

    // The pull path used to drop the album override while push applied it.
    expect(pulled).toMatchObject({
      title: 'Live Title',
      artist: 'Live Artist',
      album: 'Live Album'
    })
    expect(pulled).toMatchObject(pushed!)
  })

  it('falls back to track metadata for unset override fields', () => {
    const manager = new NowPlayingManager(playing)
    manager.updateNowPlaying({ title: 'Live Title' })

    expect(manager.getNowPlaying()).toMatchObject({
      title: 'Live Title',
      artist: 'Track Artist',
      album: 'Track Album'
    })
  })

  describe('flash', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })
    afterEach(() => {
      vi.useRealTimers()
    })

    it('shows over the override, field by field, and reverts to it', () => {
      const manager = new NowPlayingManager(playing)
      const pushed: (string | undefined)[] = []
      manager.onNowPlayingChanged = (metadata) => pushed.push(metadata.title)
      manager.updateNowPlaying({ title: 'Live Title', artist: 'Live Artist' })

      manager.flashNowPlaying({ title: 'Flash' }, 1000)
      expect(manager.getNowPlaying()).toMatchObject({
        title: 'Flash',
        artist: 'Live Artist'
      })

      vi.advanceTimersByTime(1000)
      expect(manager.getNowPlaying()).toMatchObject({ title: 'Live Title' })
      expect(pushed).toEqual(['Live Title', 'Flash', 'Live Title'])
    })

    it('does not outlive a track change', () => {
      const manager = new NowPlayingManager(playing)
      const pushed: (string | undefined)[] = []
      manager.flashNowPlaying({ title: 'Flash' }, 1000)

      manager.clearNowPlayingOverride()
      manager.onNowPlayingChanged = (metadata) => pushed.push(metadata.title)
      manager.updateNowPlaying({ title: 'Next Title' })
      vi.advanceTimersByTime(1000)

      expect(manager.getNowPlaying()).toMatchObject({ title: 'Next Title' })
      expect(pushed).toEqual(['Next Title'])
    })

    it('reverts at once when cleared', () => {
      const manager = new NowPlayingManager(playing)
      manager.updateNowPlaying({ title: 'Live Title' })
      manager.flashNowPlaying({ title: 'Flash' }, 1000)

      manager.clearNowPlayingFlash()

      expect(manager.getNowPlaying()).toMatchObject({ title: 'Live Title' })
    })
  })
})
