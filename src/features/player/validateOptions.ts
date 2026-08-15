import type { IOSUpdateOptions } from './options'

const MAX_CARPLAY_NOW_PLAYING_BUTTONS = 5

/**
 * Warns when more CarPlay now-playing buttons are configured than CarPlay renders.
 * Shared by `updateOptions` and `setupPlayer` (both can carry `ios` options).
 * Kept out of `options.ts`, which the barrel re-exports — see
 * public-surface.test.ts.
 *
 * @internal
 */
export function validateIOSUpdateOptions(ios?: IOSUpdateOptions): void {
  const buttons = ios?.carPlayNowPlayingButtons
  if (buttons && buttons.length > MAX_CARPLAY_NOW_PLAYING_BUTTONS) {
    console.warn(
      `[react-native-audio-browser] ${buttons.length} CarPlay now-playing ` +
        `buttons configured; CarPlay shows at most ${MAX_CARPLAY_NOW_PLAYING_BUTTONS}.`
    )
  }
}
