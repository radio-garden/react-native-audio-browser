import { describe, expect, it, vi } from 'vitest'
import type { Track } from '../types'
import { NativeAudioBrowser } from './NativeAudioBrowser'
import { BrowserPathHelper } from './util/BrowserPathHelper'

class TestBrowser extends NativeAudioBrowser {
  skipped: number[] = []
  queued: Track[][] = []
  loaded: Track[] = []

  tap(track: Track): Promise<void> {
    return this.navigateTrackAsync(track)
  }

  override load(track: Track): void {
    this.loaded.push(track)
  }

  override skip(index: number): void {
    this.skipped.push(index)
  }

  override setQueue(tracks: Track[], startIndex = 0): void {
    this.queued.push(tracks)
    this.queue.setTracks(tracks)
    this.queue.currentIndex = startIndex
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

/** A browser whose queue came from a tap on `/recent` = [a, b], at `playing`. */
async function playingFromRecent(playing: number): Promise<TestBrowser> {
  const browser = new TestBrowser()
  browser.configuration = {
    path: '/recent',
    routes: [
      {
        path: '/recent',
        browseStatic: {
          path: '/recent',
          title: 'Recent',
          children: [
            { title: 'a', src: 'a' },
            { title: 'b', src: 'b' }
          ]
        }
      }
    ]
  }
  await browser.tap(playing === 0 ? station('a', 0) : station('b', 1))
  return browser
}

describe('a tap on the queue’s own page', () => {
  it('skips to the playing track after the page moved it', async () => {
    const browser = await playingFromRecent(1)

    // Playing "b" moved it to the top of the page; the queue holds it at 1.
    await browser.tap(station('b', 0))

    expect(browser.skipped).toEqual([1])
    expect(browser.queued).toHaveLength(1)
  })

  it('re-expands for a track that is not playing when the page moved it', async () => {
    const browser = await playingFromRecent(0)

    await browser.tap(station('b', 5))

    expect(browser.skipped).toEqual([])
    expect(browser.queued).toHaveLength(2)
  })
})

describe('a tap in a preview that queues its linked page', () => {
  it('plays nothing when the linked page disables the track', async () => {
    const browser = new TestBrowser()
    const preview = { title: 'b', src: 'b.mp3' }
    browser.configuration = {
      path: '/home',
      routes: [
        {
          path: '/home',
          browseStatic: {
            path: '/home',
            title: 'Home',
            sections: [{ path: '/recent', queue: 'path', children: [preview] }]
          }
        },
        {
          path: '/recent',
          browseStatic: {
            path: '/recent',
            title: 'Recent',
            children: [{ ...preview, disabled: true }]
          }
        }
      ]
    }
    browser.navigatePath('/home')
    const tapped = await vi.waitFor(() => {
      const child = browser.getContent()?.sections?.[0]?.children[0]
      if (!child) throw new Error('no preview track yet')
      return child
    })

    await browser.tap(tapped)

    expect(browser.queued).toEqual([])
    expect(browser.loaded).toEqual([])
    expect(browser.getNavigationError()).toBeUndefined()
  })
})
