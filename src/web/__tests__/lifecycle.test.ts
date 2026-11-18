/** @vitest-environment happy-dom */

import { beforeEach, describe, expect, it } from 'vitest'
import { LifecycleHarness, trackA, trackB } from './lifecycleHarness'

/**
 * What the web player actually emits, recorded rather than reasoned about.
 *
 * Two halves, deliberately kept apart:
 *
 * - **Characterization** records today's behaviour. These assertions describe
 *   what happens, not what should — a change to any of them means the emission
 *   lifecycle moved, which is worth a deliberate look even when the move is an
 *   improvement.
 * - **Parity** asserts what native emits for the same flow, derived from
 *   `PlaybackStateMachine.kt` and `PlayerListener.kt`. A divergence that is
 *   known but not yet closed belongs here as `it.fails`: the target is
 *   documented, the suite stays green while the gap stands, and the test
 *   starts failing the moment someone closes it — at which point flip it to a
 *   plain `it`.
 *
 * Fidelity: the harness runs the real wiring — `setupPlayer`, the real
 * listeners, state machine and emitters — against a modelled Shaka whose
 * behaviours are each cited to shaka-player 4.16.15's source. Every sequence
 * below was also recorded in a real browser (Cypress against the Next.js
 * example) and matched. Two modelled behaviours were *corrected* by that run:
 * Shaka's teardown fires `emptied`/`loadstart` rather than `pause`, and a load
 * that does not stall emits no Shaka `buffering` event at all.
 */

let h: LifecycleHarness

beforeEach(async () => {
  h = new LifecycleHarness()
  await h.setup()
})

describe('characterization — what the player emits today', () => {
  it('load announces the active track, then hands off to Shaka', async () => {
    h.browser.load(trackA)
    await h.flush()

    expect(h.recorded).toEqual([
      'queue',
      'activeTrack',
      'nowPlaying',
      'state:loading',
      'playing:false|buffering:false',
      'state:buffering'
    ])
  })

  it('the rest of a first load, paused', async () => {
    h.browser.load(trackA)
    await h.flush()
    h.clear()
    await h.completeLoad()

    expect(h.recorded).toEqual(['state:ready'])
  })

  it('the rest of a first load, playWhenReady', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    h.clear()
    await h.completeLoad()

    // no `ready`: suppressed while the intent is to play, as native does
    expect(h.recorded).toEqual([
      'state:playing',
      'playing:true|buffering:false'
    ])
  })

  it('switching tracks while playing', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.browser.load(trackB)
    await h.flush()
    await h.completeLoad()

    // No `paused`: the teardown inside Shaka's load() fires `emptied` and
    // `loadstart`, not `pause` — confirmed in a real browser.
    expect(h.recorded).toEqual([
      'queue',
      'activeTrack',
      'nowPlaying',
      'state:loading',
      'playing:true|buffering:true',
      'state:buffering',
      'state:playing',
      'playing:true|buffering:false'
    ])
  })

  it('pause then play', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.browser.pause()
    await h.flush()
    h.browser.play()
    await h.flush()

    expect(h.recorded).toEqual([
      'playWhenReady:false',
      'playing:false|buffering:false',
      'state:paused',
      'playWhenReady:true',
      'playing:true|buffering:false',
      'state:playing'
    ])
  })

  it('mid-queue track end auto-advances', async () => {
    h.browser.setQueue([trackA, trackB])
    h.browser.setPlayWhenReady(true)
    h.browser.skip(0)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.endTrack()
    await h.flush()

    // Neither `paused` nor `ended`: the element fires both when a track plays
    // out, and both are suppressed so an advance looks like native's.
    expect(h.recorded).toEqual([
      'activeTrack',
      'nowPlaying',
      'state:loading',
      'playing:true|buffering:true',
      'state:buffering'
    ])
  })

  it('last track end ends the queue', async () => {
    h.browser.setQueue([trackA])
    h.browser.setPlayWhenReady(true)
    h.browser.skip(0)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.endTrack()
    await h.flush()

    // the real end does report `ended`; only the end-of-track `paused` is gone
    expect(h.recorded).toEqual([
      'playWhenReady:false',
      'playing:false|buffering:false',
      'state:ended',
      'queueEnded'
    ])
  })

  it('stop', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.browser.stop()
    await h.flush()

    expect(h.recorded).toEqual([
      'playWhenReady:false',
      'playing:false|buffering:false',
      'state:stopped'
    ])
  })

  it('a load Shaka rejects', async () => {
    h.failLoad()
    h.browser.load(trackA)
    await h.flush()

    // the code is whatever the rejection carried; -1 is the normalizer's
    // fallback for a plain Error
    expect(h.recorded).toEqual([
      'queue',
      'activeTrack',
      'nowPlaying',
      'state:loading',
      'playing:false|buffering:false',
      'state:buffering',
      'state:error',
      'error:-1'
    ])
  })

  it('a track with no src', async () => {
    h.browser.load({ id: 'x', title: 'No src' })
    await h.flush()

    expect(h.recorded).toEqual([
      'queue',
      'activeTrack',
      'nowPlaying',
      'state:loading',
      'playing:false|buffering:false',
      'state:error',
      'error:invalid_track'
    ])
  })
})

describe('parity — what native emits for the same flow', () => {
  /**
   * Android fires the active-track callback from `onMediaItemTransition`
   * (PlayerListener.kt:106) and maps the same event to LOADING in `onEvents`
   * (:216-224), which ExoPlayer runs after the individual callbacks.
   */
  it('announces the active track before reporting loading', async () => {
    h.browser.load(trackA)
    await h.flush()

    const active = h.recorded.indexOf('activeTrack')
    const loading = h.recorded.indexOf('state:loading')
    expect(active).toBeGreaterThanOrEqual(0)
    expect(loading).toBeGreaterThan(active)
  })

  /**
   * Android always passes through STATE_BUFFERING after prepare. Shaka only
   * fires its own `buffering` event when a load actually stalls, so a load
   * served from cache used to skip the state entirely. `Player`'s `loading`
   * listener now dispatches `waiting` alongside `trackLoading` so every load
   * passes through it, as native's does.
   */
  it('reports buffering between loading and playing', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()

    expect(h.recorded).toContain('state:buffering')
  })

  /**
   * Android suppresses READY while playWhenReady — "READY is a transient
   * before PLAYING — suppress it so consumers don't flash a settled/non-loading
   * state mid-startup" (PlaybackStateMachine.kt, STATE_READY branch). The web
   * machine now takes `playWhenReady` on the two edges that produce `ready` and
   * suppresses it the same way.
   *
   * The web-only hazard that comes with it: a browser can refuse `play()`.
   * `Player.onPlayRejected` clears the intent on anything but an `AbortError`,
   * so a blocked autoplay settles instead of stranding on `buffering`.
   */
  it('does not report ready mid-startup while playWhenReady', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    h.clear()
    await h.completeLoad()

    expect(h.recorded).not.toContain('state:ready')
  })

  /**
   * Android never reports ENDED between tracks: STATE_ENDED is only reached at
   * the end of the playlist, and a mid-playlist advance stays READY/BUFFERING.
   * Web loads one track at a time, so the element's own `ended` used to surface
   * and drop `PlayingState.playing` to false between every pair of tracks.
   * `QueuePlayer.applyState` now advances without emitting it; the true queue
   * end still reports `ended`.
   */
  it('does not report ended when the queue advances', async () => {
    h.browser.setQueue([trackA, trackB])
    h.browser.setPlayWhenReady(true)
    h.browser.skip(0)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.endTrack()
    await h.flush()

    expect(h.recorded).not.toContain('state:ended')
    expect(h.recorded).not.toContain('playing:false|buffering:false')
  })

  /**
   * Android's transition cuts the outgoing item and reports LOADING, with no
   * PAUSED in between — and web matches. The teardown inside Shaka's load()
   * fires `emptied` and `loadstart`, never `pause`, even mid-playback.
   * Confirmed in a real browser; reading the HTML load algorithm had suggested
   * otherwise.
   */
  it('does not report paused when switching tracks', async () => {
    h.browser.setPlayWhenReady(true)
    h.browser.load(trackA)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.browser.load(trackB)
    await h.flush()

    expect(h.recorded).not.toContain('state:paused')
  })

  /**
   * A track playing out fires `pause` before `ended` on the element, so web
   * used to report `paused` at the end of every track where native reports
   * nothing. The pause listener now ignores a pause raised once `ended` is
   * already true.
   */
  it('does not report paused when a track plays out', async () => {
    h.browser.setQueue([trackA, trackB])
    h.browser.setPlayWhenReady(true)
    h.browser.skip(0)
    await h.flush()
    await h.completeLoad()
    h.clear()

    h.endTrack()
    await h.flush()

    expect(h.recorded).not.toContain('state:paused')
  })

  /**
   * Android reports LOADING off the media-item transition, before any network
   * work, so the URL-resolution window has a state. Web now does the same: the
   * announcement and `trackLoading` both happen when `load()` is called, so the
   * pair keeps native's order and the window is no longer silent. Shaka's own
   * `loading` arrives later and the state setter drops it as a no-op.
   */
  it('reports loading while the url is still resolving', async () => {
    h.browser.load(trackA)

    expect(h.browser.getPlayback().state).toBe('loading')
  })
})
