/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import { LifecycleHarness, trackA, trackB } from './lifecycleHarness'

let h: LifecycleHarness

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
})

async function playTrackA(): Promise<void> {
  h.browser.setPlayWhenReady(true)
  h.browser.load(trackA)
  await h.flush()
  await h.completeLoad()
  h.clear()
}

describe('an element pause the library did not ask for', () => {
  it('lowers the play intent, as an interruption does on iOS', async () => {
    await playTrackA()

    h.pausedFromOutside()

    expect(h.recorded).toEqual([
      'playWhenReady:false',
      'playing:false|buffering:false',
      'state:paused'
    ])
    expect(h.browser.getPlayWhenReady()).toBe(false)
    expect(h.browser.getPlayingState().playing).toBe(false)
  })

  it('resumes on the first togglePlayback()', async () => {
    await playTrackA()
    h.pausedFromOutside()
    h.clear()

    h.browser.togglePlayback()

    expect(h.recorded).toEqual([
      'playWhenReady:true',
      'playing:true|buffering:false',
      'state:playing'
    ])
  })

  it('keeps the intent while a load is in flight', async () => {
    await playTrackA()

    h.browser.load(trackB)
    h.pausedFromOutside()
    await h.flush()
    await h.completeLoad()

    expect(h.recorded).not.toContain('playWhenReady:false')
    expect(h.browser.getPlayback().state).toBe('playing')
  })

  it('keeps the intent after an error', async () => {
    await playTrackA()
    h.raiseError()

    h.pausedFromOutside()

    expect(h.browser.getPlayWhenReady()).toBe(true)
    expect(h.browser.getPlayback().state).toBe('error')
  })

  it('keeps the intent when a play() has overtaken the pause event', async () => {
    await playTrackA()

    const element = (h.browser as unknown as { element: HTMLMediaElement })
      .element
    element.dispatchEvent(new Event('pause'))

    expect(h.browser.getPlayWhenReady()).toBe(true)
  })
})

describe('an element play the library did not ask for', () => {
  it('raises the play intent, as the end of an interruption does on iOS', async () => {
    await playTrackA()
    h.pausedFromOutside()
    h.clear()

    h.resumedFromOutside()

    expect(h.recorded).toEqual([
      'playWhenReady:true',
      'playing:true|buffering:false',
      'state:playing'
    ])
  })

  it('keeps a lowered intent after an error', async () => {
    await playTrackA()
    h.browser.pause()
    h.raiseError()

    h.resumedFromOutside()

    expect(h.browser.getPlayWhenReady()).toBe(false)
  })

  it('keeps a lowered intent after stop()', async () => {
    await playTrackA()
    h.browser.stop()

    h.resumedFromOutside()

    expect(h.browser.getPlayWhenReady()).toBe(false)
  })

  it('leaves a lowered intent alone when a pause() has overtaken it', async () => {
    await playTrackA()
    h.browser.pause()
    h.clear()

    const element = (h.browser as unknown as { element: HTMLMediaElement })
      .element
    element.dispatchEvent(new Event('play'))

    expect(h.browser.getPlayWhenReady()).toBe(false)
  })
})
