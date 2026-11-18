/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import type { Track } from '../../types'
import { LifecycleHarness, trackA, trackB } from './lifecycleHarness'

/**
 * The edges the main lifecycle recordings miss, each one a bug the hoisted
 * load path introduced or exposed. All were reproduced before being fixed;
 * reverting any fix fails the test that names it.
 */

let h: LifecycleHarness

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
})

describe('a load superseded while still in flight', () => {
  /**
   * The eager `unload()` aborts the in-flight `player.load()`, and Shaka
   * rejects an aborted load with LOAD_INTERRUPTED (7000). Unless the stale
   * load is abandoned first, its own `catch` reports that as a playback
   * error — an error banner on every fast track switch.
   */
  it('does not report the interrupted load as an error', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush() // player.load(A) is now pending
    h.clear()

    h.browser.load(trackB)
    await h.flush()
    await h.completeLoad()

    expect(h.recorded).not.toContain('state:error')
    expect(h.recorded.some((r) => r.startsWith('error:'))).toBe(false)
    expect(h.browser.getPlayback().state).toBe('playing')
  })
})

describe('a track end that does not advance the queue', () => {
  /**
   * Suppressing `ended` mid-queue is only safe when a load takes over the
   * state. The sleep timer set to end-of-track pauses instead, and its
   * `element.pause()` is a no-op on an already-ended element — so nothing
   * moved the state and the player sat reporting `playing` with silence.
   */
  it('reports ended when the sleep timer stops instead of advancing', async () => {
    h.browser.setQueue([trackA, trackB])
    h.browser.setPlayWhenReady(true)
    h.browser.skip(0)
    await h.flush()
    await h.completeLoad()
    h.browser.setSleepTimerToEndOfTrack()
    h.clear()

    h.endTrack()
    await h.flush()

    expect(h.browser.getPlayback().state).toBe('ended')
  })

  it('still suppresses ended when the queue does advance', async () => {
    h.browser.setQueue([trackA, trackB])
    h.browser.setPlayWhenReady(true)
    h.browser.skip(0)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.endTrack()
    await h.flush()

    expect(h.recorded).not.toContain('state:ended')
  })
})

describe('loading after a stop', () => {
  /**
   * `dispatch()` drops every event while `_isStopped`, to keep Shaka's
   * teardown noise off the stopped state. The `trackLoading` raised when
   * `load()` is called was caught by that too, so the resolution window
   * reported `stopped` rather than `loading` on the stop → play path.
   */
  it('reports loading while the url resolves', async () => {
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.browser.stop()
    await h.flush()

    h.browser.play()

    expect(h.browser.getPlayback().state).toBe('loading')
  })
})

describe('the remembered resume position', () => {
  /**
   * `onError` also runs for a *load* failure, where the element was just torn
   * down and reads 0. Recording that overwrote the real position, so a retry
   * that failed cost the place a later one would have resumed from.
   */
  it('survives a retry that also fails', async () => {
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()

    h.setPosition(42)
    h.raiseError()
    await h.flush()

    h.failLoad()
    h.browser.retry()
    await h.flush()

    h.succeedLoads()
    h.browser.retry()
    await h.flush()
    await h.completeLoad()

    expect(h.currentTime).toBe(42)
  })

  it('is not applied to a different track', async () => {
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.setPosition(42)
    h.raiseError()
    await h.flush()

    // a different track becomes current, then fails at the very start
    h.browser.load(trackB)
    await h.flush()
    await h.completeLoad()
    h.raiseError()
    await h.flush()

    h.browser.retry()
    await h.flush()
    await h.completeLoad()

    expect(h.currentTime).toBe(0)
  })

  /**
   * A stop remembers the position for `play()` to resume from. A fresh
   * `load()` of the same track afterwards starts from the top, as ExoPlayer's
   * does for a re-set media item — so if that load fails, its retry must not
   * dig up the position the stop left behind.
   */
  it('is forgotten by a fresh load of the same track', async () => {
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.setPosition(42)
    h.browser.stop()
    await h.flush()

    h.failLoad()
    h.browser.load(trackA)
    await h.flush()

    h.succeedLoads()
    h.browser.retry()
    await h.flush()
    await h.completeLoad()

    expect(h.currentTime).toBe(0)
  })
})

describe('a rejected play() while a load is in flight', () => {
  /**
   * `load()` then `play()` is the library's own sequence (`playSingleTrack`,
   * `skip`), and until Shaka has a source the element may refuse the play —
   * Firefox rejects a source-less element with `NotSupportedError`. That is
   * not the browser refusing the intent: the load calls `play()` itself on
   * success, so the intent has to survive until then.
   */
  it('keeps the intent for the load to honour', async () => {
    const element = (h.browser as unknown as { element: HTMLMediaElement })
      .element
    const refuse = Object.assign(new Error('no source'), {
      name: 'NotSupportedError'
    })
    element.play = () => Promise.reject(refuse)

    h.browser.load(trackA)
    h.browser.play()
    await h.flush()

    expect(h.browser.getPlayWhenReady()).toBe(true)
    expect(h.recorded).not.toContain('playWhenReady:false')
  })
})

describe('re-preparing the current item', () => {
  /**
   * ExoPlayer's `prepare()` does not re-fire `onMediaItemTransition` for an
   * unchanged item, so neither should a retry: a consumer that resets on a
   * track change would have its UI wiped every time one ran. The
   * `lastTrack === track` payload was the tell.
   */
  it('does not announce a transition on retry', async () => {
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.raiseError()
    await h.flush()
    h.clear()

    h.browser.retry()
    await h.flush()

    expect(h.recorded).not.toContain('activeTrack')
    expect(h.recorded).not.toContain('nowPlaying')
  })

  it('still announces when the track actually changes', async () => {
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.browser.load(trackB)
    await h.flush()

    expect(h.recorded).toContain('activeTrack')
  })

  it('announces the first load of all', async () => {
    const fresh: Track = {
      id: 'f',
      title: 'F',
      src: 'https://x.example.com/f.mp3'
    }
    h.browser.load(fresh)
    await h.flush()

    expect(h.recorded).toContain('activeTrack')
  })
})
