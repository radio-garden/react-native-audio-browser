import type { PlaybackState } from '../../features'

/**
 * Every racy observation that can move the playback state — the web analog of
 * iOS's `PlaybackEvent` / Android's `PlaybackEvent`. Each is produced by an
 * HTML5 `<audio>` or Shaka event:
 *
 * | event                | source                          |
 * |----------------------|---------------------------------|
 * | `trackLoading`       | Shaka `loading`                 |
 * | `loadSeekCompleted`  | Shaka `loaded`                  |
 * | `waiting`            | Shaka `buffering` (`true`)      |
 * | `bufferingSufficient`| Shaka `buffering` (`false`)     |
 * | `playing`            | element `playing`               |
 * | `paused`             | element `pause`                 |
 * | `trackEndedNaturally`| element `ended`                 |
 *
 * Deliberate *commands* (stop, error) set their terminal state directly rather
 * than flowing through here — they are intentions, not observations. The two
 * iOS-only events `trackUnloaded` and `audioFrameDecoded` are omitted: web has
 * no unload-to-none source, and a decoded frame is indistinguishable from
 * `playing` in the browser.
 */
export type PlaybackEvent =
  | { type: 'trackLoading' }
  | { type: 'trackEndedNaturally' }
  | { type: 'loadSeekCompleted' }
  | { type: 'paused'; hasAsset: boolean }
  | { type: 'waiting' }
  | { type: 'playing' }
  | { type: 'bufferingSufficient' }

/**
 * The next playback state for an event from the current state, or `null` to
 * suppress the transition. A faithful port of iOS's `nextPlaybackState(from:on:)`.
 *
 * Guards here are **state-related** (e.g. "only from loading"). Context-related
 * guards (does an asset exist, near track end, playWhenReady) are decided at the
 * call site and arrive as event fields / whether the event fires at all.
 *
 * Pure: side effects (event emission, now-playing sync, timers, error payloads)
 * stay with the caller.
 */
export function nextPlaybackState(
  current: PlaybackState,
  event: PlaybackEvent
): PlaybackState | null {
  switch (event.type) {
    case 'trackLoading':
      return 'loading'
    case 'trackEndedNaturally':
      return 'ended'
    case 'waiting':
      return 'buffering'
    case 'playing':
      return 'playing'
    case 'loadSeekCompleted':
      // The settle after a load; meaningless from anything but a fresh load.
      return current === 'loading' ? 'ready' : null
    case 'paused':
      // A stopped player owns its state; a stray pause must not disturb it.
      if (current === 'stopped') return null
      // No asset means the element paused because it was emptied → nothing loaded.
      if (!event.hasAsset) return 'none'
      // Preserve a terminal error: the unload that follows an error pauses the
      // element, and that pause must not clear the error the UI is rendering.
      if (current === 'error') return null
      return 'paused'
    case 'bufferingSufficient':
      // A rebuffer that finishes mid-playback should stay playing, not flash ready.
      return current === 'playing' ? null : 'ready'
  }
}
