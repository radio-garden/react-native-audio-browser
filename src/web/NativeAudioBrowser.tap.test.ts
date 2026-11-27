import { describe, expect, it } from 'vitest'
import type { Track } from '../types'
import { NativeAudioBrowser } from './NativeAudioBrowser'
import { BrowserPathHelper } from './util/BrowserPathHelper'

class TestBrowser extends NativeAudioBrowser {
  skipped: number[] = []
  queued: Track[][] = []

  constructor(queue: Track[] = [], currentIndex?: number, sourcePath?: string) {
    super()
    this.queue.setTracks(queue)
    this.queue.currentIndex = currentIndex
    ;(
      this as unknown as { browserManager: { queueSourcePath?: string } }
    ).browserManager.queueSourcePath = sourcePath
  }

  override skip(index: number): void {
    this.skipped.push(index)
  }

  override setQueue(tracks: Track[]): void {
    this.queued.push(tracks)
  }

  override play(): void {}
}

const stamped = (id: string, index: number) =>
  BrowserPathHelper.build('/recent', id, index)
const station = (id: string, index: number): Track => ({
  title: id,
  src: id,
  path: stamped(id, index)
})

async function settle() {
  for (let i = 0; i < 20; i++) await Promise.resolve()
}

describe('a tap on the queue’s own page', () => {
  it('skips to the playing track after the page moved it', async () => {
    // Playing "b" moved it to the top of the page; the queue holds it at 1.
    const browser = new TestBrowser(
      [station('a', 0), station('b', 1)],
      1,
      '/recent'
    )

    browser.navigateTrack({ ...station('b', 0) })
    await settle()

    expect(browser.skipped).toEqual([1])
    expect(browser.queued).toEqual([])
  })

  it('does not skip to a track that is not playing when the page moved it', async () => {
    const browser = new TestBrowser(
      [station('a', 0), station('b', 1)],
      0,
      '/recent'
    )

    browser.navigateTrack({ ...station('b', 5) })
    await settle()

    expect(browser.skipped).toEqual([])
  })
})
