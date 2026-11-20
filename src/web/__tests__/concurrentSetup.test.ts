/** @vitest-environment happy-dom */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NativeAudioBrowser } from '../NativeAudioBrowser'
import { FakeShakaPlayer } from './lifecycleHarness'

const audioElements = () =>
  document.querySelectorAll('audio#react-native-audio-browser')

beforeEach(() => {
  ;(globalThis as unknown as { __DEV__: boolean }).__DEV__ = false
  document.body.innerHTML = ''
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('overlapping setupPlayer() calls', () => {
  it('build one audio element and one Shaka player', async () => {
    const attach = vi.spyOn(FakeShakaPlayer.prototype, 'attach')
    const browser = new NativeAudioBrowser()

    await Promise.all([browser.setupPlayer({}), browser.setupPlayer({})])

    expect(audioElements()).toHaveLength(1)
    expect(attach).toHaveBeenCalledTimes(1)
  })

  it('share a failed build, and a later call builds again', async () => {
    const attach = vi
      .spyOn(FakeShakaPlayer.prototype, 'attach')
      .mockRejectedValueOnce(new Error('attach failed'))
    const browser = new NativeAudioBrowser()

    const results = await Promise.allSettled([
      browser.setupPlayer({}),
      browser.setupPlayer({})
    ])

    expect(results.map((result) => result.status)).toEqual([
      'rejected',
      'rejected'
    ])

    await browser.setupPlayer({})

    expect(attach).toHaveBeenCalledTimes(2)
  })
})
