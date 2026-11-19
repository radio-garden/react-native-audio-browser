/** @vitest-environment happy-dom */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LifecycleHarness, trackA } from './lifecycleHarness'

/**
 * The position state the OS media controls get, with the default options:
 * no `progressUpdateEventInterval`, so no progress timer runs.
 */

let h: LifecycleHarness
let setPositionState: ReturnType<typeof vi.fn>
let element: HTMLMediaElement

function setDuration(seconds: number): void {
  Object.defineProperty(element, 'duration', {
    value: seconds,
    configurable: true
  })
}

beforeEach(async () => {
  setPositionState = vi.fn()
  vi.stubGlobal('navigator', {
    onLine: true,
    mediaSession: {
      metadata: null,
      playbackState: 'none',
      setActionHandler: () => {},
      setPositionState
    }
  })
  vi.stubGlobal('MediaMetadata', class {})

  h = new LifecycleHarness()
  await h.setup()
  element = (h.browser as unknown as { element: HTMLMediaElement }).element

  h.browser.setPlayWhenReady(true)
  h.browser.load(trackA)
  await h.flush()
  await h.completeLoad()
  setPositionState.mockClear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('media session position state', () => {
  it('reports the position when playback starts', async () => {
    h.browser.pause()
    await h.flush()
    h.setPosition(12)
    setPositionState.mockClear()

    h.browser.play()
    await h.flush()

    expect(setPositionState).toHaveBeenLastCalledWith({
      duration: 100,
      position: 12,
      playbackRate: 1
    })
  })

  it('reports the new rate when the rate changes', () => {
    h.setPosition(20)
    h.browser.setRate(1.5)
    element.dispatchEvent(new Event('ratechange'))

    expect(setPositionState).toHaveBeenLastCalledWith({
      duration: 100,
      position: 20,
      playbackRate: 1.5
    })
  })

  it('reports the new position after a seek', () => {
    h.browser.seekTo(40)
    element.dispatchEvent(new Event('seeked'))

    expect(setPositionState).toHaveBeenLastCalledWith({
      duration: 100,
      position: 40,
      playbackRate: 1
    })
  })

  it('reports the duration once the element knows it', () => {
    setDuration(240)
    element.dispatchEvent(new Event('durationchange'))

    expect(setPositionState).toHaveBeenLastCalledWith({
      duration: 240,
      position: 0,
      playbackRate: 1
    })
  })

  it('clears the position when the element is emptied for a new load', () => {
    setDuration(NaN)
    element.dispatchEvent(new Event('emptied'))

    expect(setPositionState).toHaveBeenLastCalledWith()
  })
})
