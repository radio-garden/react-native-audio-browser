import type { RepeatMode as RepeatModeType } from '../../features'

export const RepeatMode = {
  Off: 'off' as const,
  Track: 'track' as const,
  Queue: 'queue' as const
} satisfies Record<string, RepeatModeType>
