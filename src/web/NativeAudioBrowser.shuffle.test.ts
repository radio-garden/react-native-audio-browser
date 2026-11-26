import { expect, it } from 'vitest'
import { NativeAudioBrowser } from './NativeAudioBrowser'

it('emits the shuffle mode only when it changes', () => {
  const browser = new NativeAudioBrowser()
  const emitted: boolean[] = []
  browser.onPlaybackShuffleModeChanged = (enabled) => emitted.push(enabled)

  browser.setShuffleEnabled(true)
  browser.setShuffleEnabled(true)
  browser.setShuffleEnabled(false)
  browser.setShuffleEnabled(false)

  expect(emitted).toEqual([true, false])
})
