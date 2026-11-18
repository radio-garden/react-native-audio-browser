import { describe, expect, it } from 'vitest'
import type { Track } from '../../types'
import { NowPlayingManager } from './NowPlayingManager'

const track = {
  src: 'https://example.com/stream',
  title: 'Track Title',
  artist: 'Track Artist',
  album: 'Track Album'
} as Track

describe('NowPlayingManager', () => {
  it('applies the same override fields on push and pull', () => {
    const manager = new NowPlayingManager()
    let pushed
    manager.onNowPlayingChanged = (metadata) => {
      pushed = metadata
    }

    manager.updateNowPlaying(
      { title: 'Live Title', artist: 'Live Artist', album: 'Live Album' },
      track,
      0
    )
    const pulled = manager.getNowPlaying(track, 0)

    // The pull path used to drop the album override while push applied it.
    expect(pulled).toMatchObject({
      title: 'Live Title',
      artist: 'Live Artist',
      album: 'Live Album'
    })
    expect(pulled).toMatchObject(pushed!)
  })

  it('falls back to track metadata for unset override fields', () => {
    const manager = new NowPlayingManager()
    manager.updateNowPlaying({ title: 'Live Title' }, track, 0)

    expect(manager.getNowPlaying(track, 0)).toMatchObject({
      title: 'Live Title',
      artist: 'Track Artist',
      album: 'Track Album'
    })
  })
})
