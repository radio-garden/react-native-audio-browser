import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../native', () => ({
  nativeBrowser: {
    setupPlayer: vi.fn().mockResolvedValue(undefined),
    updateOptions: vi.fn(),
    setRepeatMode: vi.fn(),
    setPlayWhenReady: vi.fn()
  }
}))

import { nativeBrowser } from '../../native'
import { setupPlayer } from './setup'

const native = vi.mocked(nativeBrowser)

beforeEach(() => {
  vi.clearAllMocks()
  native.setupPlayer.mockResolvedValue(undefined)
})

function orderOf(fn: { mock: { invocationCallOrder: number[] } }): number {
  expect(fn.mock.invocationCallOrder).toHaveLength(1)
  return fn.mock.invocationCallOrder[0]!
}

describe('setupPlayer launch options', () => {
  it('applies update-options before native setup, player state after it', async () => {
    await setupPlayer({
      playWhenReady: true,
      repeatMode: 'queue',
      capabilities: { favorite: true }
    })

    expect(native.updateOptions).toHaveBeenCalledWith({
      capabilities: { favorite: true }
    })
    expect(native.setRepeatMode).toHaveBeenCalledWith('queue')
    expect(native.setPlayWhenReady).toHaveBeenCalledWith(true)

    // Options land before the player is constructed; commands after it exists.
    expect(orderOf(native.updateOptions)).toBeLessThan(
      orderOf(native.setupPlayer)
    )
    expect(orderOf(native.setupPlayer)).toBeLessThan(
      orderOf(native.setRepeatMode)
    )
    expect(orderOf(native.setupPlayer)).toBeLessThan(
      orderOf(native.setPlayWhenReady)
    )
  })

  it('skips the extra calls when no launch options are given', async () => {
    await setupPlayer({ retry: true })

    expect(native.updateOptions).not.toHaveBeenCalled()
    expect(native.setRepeatMode).not.toHaveBeenCalled()
    expect(native.setPlayWhenReady).not.toHaveBeenCalled()
  })

  it('keeps the launch options out of the native setup payload', async () => {
    await setupPlayer({
      retry: true,
      keepSessionAliveOnError: true,
      playWhenReady: true,
      repeatMode: 'queue',
      capabilities: { favorite: true },
      forwardJumpInterval: 30,
      android: { audioContentType: 'music' }
    })

    expect(native.setupPlayer).toHaveBeenCalledWith({
      retry: true,
      keepSessionAliveOnError: true,
      android: { audioContentType: 'music' },
      autoUpdateNowPlayingMetadata: true,
      nowPlayingMetadataFormatter: undefined
    })
  })

  it('forwards an explicit progressUpdateEventInterval: null (meaning: disabled)', async () => {
    await setupPlayer({ progressUpdateEventInterval: null })

    expect(native.updateOptions).toHaveBeenCalledWith({
      progressUpdateEventInterval: null
    })
  })

  it('does not apply playWhenReady/repeatMode when native setup fails', async () => {
    native.setupPlayer.mockRejectedValueOnce(new Error('bind failed'))

    await expect(
      setupPlayer({ playWhenReady: true, repeatMode: 'queue' })
    ).rejects.toThrow('bind failed')

    expect(native.setRepeatMode).not.toHaveBeenCalled()
    expect(native.setPlayWhenReady).not.toHaveBeenCalled()
  })

  it('normalizes a formatter callback and coalesces its undefined to {}', async () => {
    const formatter = vi.fn(() => undefined)
    await setupPlayer({ autoUpdateNowPlaying: formatter })

    const payload = native.setupPlayer.mock.calls[0]![0]
    expect(payload.autoUpdateNowPlayingMetadata).toBe(true)
    // The wrapper never lets `undefined` cross the Nitro boundary.
    expect(
      payload.nowPlayingMetadataFormatter!({
        track: { title: 'Station' },
        playWhenReady: true,
        stalled: false
      })
    ).toEqual({})
  })
})
