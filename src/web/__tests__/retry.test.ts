/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import type { Track } from '../../types'
import { LifecycleHarness } from './lifecycleHarness'

/**
 * `retry()` after a failure, and `play()` from the error state — which on
 * Android are the same operation.
 *
 * `retry()` is `player.prepare()` there; `play()` is `playWhenReady = true`
 * followed by the same `prepare()`, which early-returns unless ExoPlayer is
 * STATE_IDLE. iOS's `retry()` is `reloadResolving(startFromCurrentTime: true)`,
 * with the reason in its own comment: "short-lived URLs/tokens may have expired
 * since the failure".
 *
 * Web used to call `shaka.Player.retryStreaming()`, which returns false and
 * does nothing unless the load mode is MEDIA_SOURCE — so for a progressive
 * file, the case a signed URL exists for, `retry()` was a silent no-op.
 *
 * The point of these tests is the re-resolution: the resolver hands back a
 * different URL each call, so a retry that replays the cached one is visible as
 * a stale URL reaching the player.
 */

let h: LifecycleHarness

/** Its `src` is the unsigned path; the resolver signs it freshly each call. */
const signed: Track = { id: 'signed', title: 'Signed', src: '/stream/7' }

/** A media resolver that mints a new URL every time it runs. */
function freshlySigning(): {
  media: { resolve: () => Promise<{ path: string }> }
} {
  let issued = 0
  return {
    media: {
      resolve: () => {
        issued += 1
        return Promise.resolve({
          path: `https://cdn.example.com/${issued}.mp3`
        })
      }
    }
  }
}

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
})

describe('retry re-resolves', () => {
  it('hands the player a freshly resolved url, not the one that failed', async () => {
    h.browser.configuration = freshlySigning()

    h.failLoad()
    h.browser.load(signed)
    await h.flush()
    expect(h.loadedUrls).toEqual(['https://cdn.example.com/1.mp3'])
    expect(h.browser.getPlayback().state).toBe('error')

    h.succeedLoads()
    h.browser.retry()
    await h.flush()

    // the retry resolved again rather than replaying the expired url
    expect(h.loadedUrls).toEqual([
      'https://cdn.example.com/1.mp3',
      'https://cdn.example.com/2.mp3'
    ])
  })

  it('play() from the error state re-resolves the same way', async () => {
    h.browser.configuration = freshlySigning()

    h.failLoad()
    h.browser.load(signed)
    await h.flush()

    h.succeedLoads()
    h.browser.play()
    await h.flush()

    expect(h.loadedUrls[1]).toBe('https://cdn.example.com/2.mp3')
    // play() also sets the intent; retry() does not
    expect(h.browser.getPlayWhenReady()).toBe(true)
  })

  it('retry() does not set the play intent', async () => {
    h.failLoad()
    h.browser.load({ id: 'a', title: 'A', src: 'https://x.example.com/a.mp3' })
    await h.flush()

    h.succeedLoads()
    h.browser.retry()
    await h.flush()

    expect(h.browser.getPlayWhenReady()).toBe(false)
  })

  it('is a no-op on a healthy player, as ExoPlayer.prepare() is', async () => {
    h.browser.load({ id: 'a', title: 'A', src: 'https://x.example.com/a.mp3' })
    await h.flush()
    await h.completeLoad()
    const before = h.loadedUrls.length

    h.browser.retry()
    await h.flush()

    expect(h.loadedUrls.length).toBe(before)
  })

  it('does nothing without a current track', async () => {
    h.browser.retry()
    await h.flush()

    expect(h.loadedUrls).toEqual([])
  })
})

describe('retry resumes position', () => {
  it('resumes from where playback failed', async () => {
    const track: Track = {
      id: 'a',
      title: 'A',
      src: 'https://x.example.com/a.mp3'
    }
    h.browser.load(track)
    await h.flush()
    await h.completeLoad()

    h.setPosition(42)
    h.failLoad()
    h.raiseError()
    await h.flush()

    h.succeedLoads()
    h.browser.retry()
    await h.flush()
    await h.completeLoad()

    expect(h.currentTime).toBe(42)
  })

  it('does not resume a live stream — a fresh connection is the live edge', async () => {
    const live: Track = {
      id: 'live',
      title: 'Live',
      src: 'https://x.example.com/live.mp3',
      live: true
    }
    h.browser.load(live)
    await h.flush()
    await h.completeLoad()

    h.setPosition(3600)
    h.raiseError()
    await h.flush()

    h.browser.retry()
    await h.flush()
    await h.completeLoad()

    // seeking to an hour into a connection that no longer exists asks for a
    // point the new stream does not have
    expect(h.currentTime).toBe(0)
  })
})
