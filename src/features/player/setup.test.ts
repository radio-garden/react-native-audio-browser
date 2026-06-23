import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../native', () => ({
  nativeBrowser: {
    setupPlayer: vi.fn().mockResolvedValue(undefined)
  }
}))

import { nativeBrowser } from '../../native'
import { setupPlayer } from './setup'

const native = vi.mocked(nativeBrowser)

beforeEach(() => {
  vi.clearAllMocks()
  native.setupPlayer.mockResolvedValue(undefined)
})

function payload() {
  expect(native.setupPlayer).toHaveBeenCalledTimes(1)
  return native.setupPlayer.mock.calls[0]![0]
}

describe('setupPlayer wire regrouping', () => {
  it('sends the full launch description in a single native call', async () => {
    await setupPlayer({
      retry: true,
      playWhenReady: true,
      repeatMode: 'queue',
      capabilities: { favorite: true }
    })

    expect(payload()).toEqual({
      retry: true,
      playWhenReady: true,
      repeatMode: 'queue',
      autoUpdateNowPlayingMetadata: true,
      nowPlayingMetadataFormatter: undefined,
      options: { capabilities: { favorite: true } }
    })
  })

  it('omits the options bag when no runtime options are given', async () => {
    await setupPlayer({ retry: true })

    expect(payload()).not.toHaveProperty('options')
  })

  it('splits the merged android bag into construction and runtime fields', async () => {
    await setupPlayer({
      android: {
        minBuffer: 50_000,
        wakeMode: 'network',
        skipSilence: true,
        notificationButtons: { overflow: ['favorite'] }
      }
    })

    const sent = payload()
    expect(sent.android).toEqual({ minBuffer: 50_000, wakeMode: 'network' })
    expect(sent.options).toEqual({
      android: {
        skipSilence: true,
        notificationButtons: { overflow: ['favorite'] }
      }
    })
  })

  it('moves ios playbackRates into the runtime options', async () => {
    await setupPlayer({
      ios: { category: 'playback', playbackRates: [0.5, 1, 2] }
    })

    const sent = payload()
    expect(sent.ios).toEqual({ category: 'playback' })
    expect(sent.options).toEqual({ ios: { playbackRates: [0.5, 1, 2] } })
  })

  it('omits a platform bag that only carried runtime fields', async () => {
    await setupPlayer({ android: { skipSilence: true } })

    expect(payload()).not.toHaveProperty('android')
  })

  it('forwards meaningful nulls (progress disabled, empty button layout)', async () => {
    await setupPlayer({
      progressUpdateEventInterval: null,
      android: { notificationButtons: null }
    })

    expect(payload().options).toEqual({
      progressUpdateEventInterval: null,
      android: { notificationButtons: null }
    })
  })

  it('normalizes a formatter callback and coalesces its undefined to {}', async () => {
    const formatter = vi.fn(() => undefined)
    await setupPlayer({ nowPlaying: formatter })

    const sent = payload()
    expect(sent.autoUpdateNowPlayingMetadata).toBe(true)
    // The wrapper never lets `undefined` cross the Nitro boundary.
    expect(
      sent.nowPlayingMetadataFormatter!({
        track: { title: 'Station' },
        playWhenReady: true
      })
    ).toEqual({})
  })

  it('maps nowPlaying: false to disabled metadata publishing', async () => {
    await setupPlayer({ nowPlaying: false })

    expect(payload().autoUpdateNowPlayingMetadata).toBe(false)
  })
})
