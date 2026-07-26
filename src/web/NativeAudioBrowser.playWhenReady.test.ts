import { describe, expect, it } from 'vitest'
import { NativeAudioBrowser } from './NativeAudioBrowser'

// Transport calls must emit the intent change through the playWhenReady
// accessor override — they previously wrote the raw base field, so JS
// consumers never heard about play()/pause()/stop() intent changes (only
// setPlayWhenReady()'s) and MediaSession never synced.
class TestBrowser extends NativeAudioBrowser {
  constructor() {
    super()
    // Minimal fakes so transport calls run without setupPlayer/DOM.
    this.element = {
      play: () => Promise.resolve(),
      pause: () => {}
    } as unknown as HTMLMediaElement
    this.player = {
      unload: () => Promise.resolve()
    } as unknown as typeof this.player
  }
}

function makeBrowser(): { browser: TestBrowser; emitted: boolean[] } {
  const browser = new TestBrowser()
  const emitted: boolean[] = []
  browser.onPlaybackPlayWhenReadyChanged = (event) =>
    emitted.push(event.playWhenReady)
  return { browser, emitted }
}

describe('NativeAudioBrowser playWhenReady emission', () => {
  it('play() emits the intent change', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    expect(emitted).toEqual([true])
  })

  it('pause() emits the intent change', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    browser.pause()
    expect(emitted).toEqual([true, false])
  })

  it('stop() emits the intent change', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    browser.stop()
    expect(emitted).toEqual([true, false])
  })

  it('does not emit when the value is unchanged', () => {
    const { browser, emitted } = makeBrowser()
    browser.play()
    browser.play()
    expect(emitted).toEqual([true])
  })
})
