import { describe, expect, it } from 'vitest'
import type { Track } from '../types'
import { NativeAudioBrowser } from './NativeAudioBrowser'
import { BrowserPathHelper } from './util/BrowserPathHelper'

type Resolved = { tracks: Track[]; startIndex: number; startPositionMs: number }

/** A browser whose queue expansions resolve when the test says so. */
class TestBrowser extends NativeAudioBrowser {
  private pending = new Map<string, (result: Resolved) => void>()

  constructor() {
    super()
    this.element = {
      play: () => Promise.resolve(),
      pause: () => {},
      currentTime: 0,
      duration: 100,
      buffered: { length: 0, end: () => 0 }
    } as unknown as HTMLMediaElement
    this.player = {
      load: () => Promise.resolve(),
      unload: () => Promise.resolve()
    } as unknown as typeof this.player
    ;(
      this as unknown as {
        browserManager: {
          resolveMediaItemsForPlayback(tracks: Track[]): Promise<Resolved>
        }
      }
    ).browserManager.resolveMediaItemsForPlayback = ([track]) =>
      new Promise((resolve) => this.pending.set(track!.src!, resolve))
  }

  /** Resolves the expansion of `track`'s selection to `queue`. */
  async resolveExpansion(track: Track, queue: Track[]): Promise<void> {
    await tick()
    this.pending.get(track.src!)!({
      tracks: queue,
      startIndex: queue.indexOf(track),
      startPositionMs: 0
    })
    await tick()
  }
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

function contextualTrack(parentPath: string, id: string): Track {
  const src = `https://example.com/${id}.mp3`
  return {
    id,
    src,
    title: id,
    path: BrowserPathHelper.build(parentPath, src)
  }
}

const x = contextualTrack('/p1', 'x')
const y = contextualTrack('/p2', 'y')

describe('NativeAudioBrowser track selection order', () => {
  it('a selection whose expansion resolves last does not replace a later one', async () => {
    const browser = new TestBrowser()
    browser.navigateTrack(x)
    browser.navigateTrack(y)

    await browser.resolveExpansion(y, [y])
    await browser.resolveExpansion(x, [x])

    expect(browser.getQueue().map((track) => track.id)).toEqual(['y'])
    expect(browser.getActiveTrack()?.id).toBe('y')
  })

  it('a pending expansion does not replace a later skip within the queue', async () => {
    const browser = new TestBrowser()
    const y2 = contextualTrack('/p2', 'y2')
    browser.navigateTrack(y)
    await browser.resolveExpansion(y, [y, y2])
    ;(
      browser as unknown as { browserManager: { queueSourcePath: string } }
    ).browserManager.queueSourcePath = '/p2'

    browser.navigateTrack(x)
    browser.navigateTrack(y2)
    await browser.resolveExpansion(x, [x])

    expect(browser.getQueue().map((track) => track.id)).toEqual(['y', 'y2'])
    expect(browser.getActiveTrack()?.id).toBe('y2')
  })

  it('a pending expansion does not replace a later track with no path', async () => {
    const browser = new TestBrowser()
    const bare: Track = {
      id: 'bare',
      src: 'https://example.com/bare.mp3',
      title: 'bare'
    }

    browser.navigateTrack(x)
    browser.navigateTrack(bare)
    await browser.resolveExpansion(x, [x])

    expect(browser.getActiveTrack()?.id).toBe('bare')
  })

  it('a superseded selection does not reach handleTrackLoad', async () => {
    const browser = new TestBrowser()
    const loaded: (string | undefined)[] = []
    browser.configuration = {
      handleTrackLoad: (event) => {
        loaded.push(event.track.id)
      }
    }

    browser.navigateTrack(x)
    browser.navigateTrack(y)
    await browser.resolveExpansion(y, [y])
    await browser.resolveExpansion(x, [x])

    expect(loaded).toEqual(['y'])
  })
})
