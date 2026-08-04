import type { PlaybackErrorKind } from 'react-native-audio-browser'

/**
 * Turns a playback failure into a line a listener can act on.
 *
 * Branch on `kind`, never on `message` — the message is hard-coded developer
 * English ("Failed to load audio track") and is never localized. `code` is for
 * telemetry only: it is the underlying engine's own identifier, so it differs
 * between iOS, Android and web.
 *
 * A real app would localize these and could collapse kinds further; what
 * matters is that the listener can tell "fix your connection" from "this one is
 * gone" from "try again in a minute".
 */
export function playbackErrorMessage(kind: PlaybackErrorKind): string {
  switch (kind) {
    case 'offline':
      return 'No internet connection'
    // Both transient — another attempt may well work.
    case 'unreachable':
    case 'server-error':
      return 'Could not reach this stream'
    // Both permanent — the server answered, and the answer was no.
    case 'not-found':
      return 'Stream not found'
    case 'rejected':
      return 'Access to this stream was denied'
    case 'unplayable':
      return 'This stream cannot be played'
    case 'stalled':
      return 'Lost connection to this stream'
    // No `default`: TypeScript then flags a newly added kind here, instead of
    // it silently falling into the generic line.
    case 'unknown':
      return 'Playback failed'
  }
}
