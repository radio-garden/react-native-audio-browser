import type { PlaybackState, PlayingState } from '../../features'

/**
 * Derives the two-flag {@link PlayingState} (playing / buffering) from the
 * player's intent (`playWhenReady`) and its current {@link PlaybackState}.
 *
 * A faithful port of the native derivations (android `PlayingStateFactory.derive`,
 * ios `PlayingStateManager.update`) so all platforms agree on what "playing" and
 * "buffering" mean for the now-playing UI.
 */
export function derivePlayingState(
  playWhenReady: boolean,
  state: PlaybackState
): PlayingState {
  return {
    playing:
      playWhenReady &&
      state !== 'error' &&
      state !== 'ended' &&
      state !== 'none',
    buffering: playWhenReady && (state === 'loading' || state === 'buffering')
  }
}
