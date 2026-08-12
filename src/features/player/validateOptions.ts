import type { IOSUpdateOptions } from './options'

const MAX_CARPLAY_NOW_PLAYING_BUTTONS = 5

/**
 * Warns when more CarPlay now-playing buttons are configured than CarPlay renders.
 * Shared by `updateOptions` and `setupPlayer` (both can carry `ios` options).
 *
 * Lives outside `options.ts` because `features/player/index.ts` re-exports that
 * module wholesale: anything exported there joins the package's runtime surface,
 * `@internal` or not. This file is imported directly by the two callers and by
 * nothing in the barrel.
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
