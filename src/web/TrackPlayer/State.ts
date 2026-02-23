import type { PlaybackState } from '../../features'

export const State = {
  None: 'none',
  Ready: 'ready',
  Playing: 'playing',
  Paused: 'paused',
  Stopped: 'stopped',
  Loading: 'loading',
  Buffering: 'buffering',
  Error: 'error',
  Ended: 'ended'
} satisfies Record<string, PlaybackState>

export type State = (typeof State)[keyof typeof State]
