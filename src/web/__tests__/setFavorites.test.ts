/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import type { ResolvedTrack, Track } from '../../types'
import { LifecycleHarness, trackA, trackB } from './lifecycleHarness'

let h: LifecycleHarness

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
})

async function play(tracks: Track[]): Promise<void> {
  h.browser.setQueue(tracks, 0)
  await h.flush()
  await h.completeLoad()
  h.clear()
}

describe('setFavorites on the queue', () => {
  it('hearts the active track and its queued copy, without a track change', async () => {
    await play([trackA, trackB])

    h.browser.setFavorites(['a'])

    const active = h.browser.getActiveTrack()
    expect(active?.favorited).toBe(true)
    expect(h.browser.getQueue()[0]).toBe(active)
    expect(h.recorded).toEqual(['queue'])
  })

  it('unhearts a track that was hydrated as a favorite', async () => {
    h.browser.setFavorites(['a'])
    await play([trackA])
    expect(h.browser.getActiveTrack()?.favorited).toBe(true)

    h.browser.setFavorites([])

    expect(h.browser.getActiveTrack()?.favorited).toBe(false)
    expect(h.browser.getQueue()[0]?.favorited).toBe(false)
  })

  it('restamps every queued copy of the track', async () => {
    await play([trackA, trackB, trackA])

    h.browser.setFavorites(['a'])

    expect(h.browser.getQueue().map((track) => track.favorited)).toEqual([
      true,
      undefined,
      true
    ])
  })

  it('leaves a track whose identity did not change', async () => {
    await play([trackA, { ...trackB, favorited: true }])

    h.browser.setFavorites(['a'])

    expect(h.browser.getQueue()[1]?.favorited).toBe(true)
  })

  it('emits no queue change when no queued track changes', async () => {
    await play([trackA])

    h.browser.setFavorites(['elsewhere'])

    expect(h.recorded).toEqual([])
  })

  it('keeps a later re-prepare of the active track from reading as a track change', async () => {
    await play([trackA])
    h.browser.setFavorites(['a'])
    h.browser.stop()
    await h.flush()
    h.clear()

    h.browser.play()
    await h.flush()
    await h.completeLoad()

    expect(h.recorded).not.toContain('activeTrack')
    expect(h.browser.getActiveTrack()?.favorited).toBe(true)
  })
})

describe('setFavorites on the content', () => {
  let contents: (ResolvedTrack | undefined)[]

  beforeEach(async () => {
    h.browser.configuration = {
      path: '/p',
      routes: [
        {
          path: '/p',
          browseStatic: {
            path: '/p',
            title: 'P',
            sections: [{ children: [trackA] }, { children: [trackB] }]
          }
        }
      ]
    }
    await h.flush()
    contents = []
    h.browser.onContentChanged = (content) => contents.push(content)
  })

  const hearts = () =>
    h.browser
      .getContent()
      ?.sections?.map((section) => section.children.map((t) => t.favorited))

  it('restamps the page on screen and emits it', () => {
    h.browser.setFavorites(['b'])

    expect(hearts()).toEqual([[undefined], [true]])
    expect(contents).toEqual([h.browser.getContent()])

    h.browser.setFavorites([])

    expect(hearts()).toEqual([[undefined], [false]])
    expect(contents).toHaveLength(2)
  })

  it('emits nothing when no track on the page changes', () => {
    h.browser.setFavorites(['elsewhere'])

    expect(contents).toEqual([])
  })
})
