import type { PlaybackState } from '../../features'

export const State = {
  None: 'none' as const,
  Ready: 'ready' as const,
  Playing: 'playing' as const,
  Paused: 'paused' as const,
  Stopped: 'stopped' as const,
  Loading: 'loading' as const,
  Buffering: 'buffering' as const,
  Error: 'error' as const,
  Ended: 'ended' as const
} satisfies Record<string, PlaybackState>

export type State = (typeof State)[keyof typeof State]
