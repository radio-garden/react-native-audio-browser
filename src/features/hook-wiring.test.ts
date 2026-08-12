/** @vitest-environment happy-dom */

import { renderHook, act, cleanup } from '@testing-library/react'
import { describe, it, expect, afterEach, vi } from 'vitest'

/**
 * The 22 hooks that are a single delegation to `useNativeUpdatedValue`. Their
 * behaviour is covered once in `utils/useNativeUpdatedValue.test.ts`; what is
 * per-hook and untestable there is the wiring — which native callback slot the
 * emitter claims, and which `eventKey` is pulled out of the payload.
 *
 * Both are easy to get wrong and silent when wrong: the hook simply never
 * updates, or yields the whole event where a field was meant. Five slots
 * already differ in name from the emitter that claims them
 * (`onPlaybackPlayWhenReadyChanged` behind `onPlayWhenReadyChanged`, and
 * friends), so the names cannot be derived — they have to be pinned.
 */
const slots = vi.hoisted(() => new Map<string, (event: unknown) => void>())

vi.mock('../native', () => ({
  nativeBrowser: new Proxy({} as Record<string, unknown>, {
    get(target, prop: string) {
      // Getters are the pre-native fallback; undefined stands in for "native
      // has not answered yet", which is what these tests start from.
      target[prop] ??= vi.fn(() => undefined)
      return target[prop]
    },
    set(target, prop: string, value) {
      if (typeof value === 'function') slots.set(prop, value)
      target[prop] = value
      return true
    }
  })
}))

const { useBatteryOptimizationStatus, useBatteryWarningPending } =
  await import('./battery')
const { useContent, usePath, useTabs } = await import('./browser')
const { useCarConnected } = await import('./carConnection')
const { useEqualizerSettings } = await import('./equalizer')
const { useFormattedNavigationError, useNavigationError, usePlaybackError } =
  await import('./errors')
const { useOnline } = await import('./network')
const { useNowPlaying } = await import('./nowPlaying')
const { useOutput } = await import('./output')
const { useOptions } = await import('./player/options')
const { usePlayingState } = await import('./playback/playing')
const { usePlayWhenReady } = await import('./playback/playWhenReady')
const { useProgress } = await import('./playback/progress')
const { usePlayback } = await import('./playback/state')
const { useSystemVolume } = await import('./playback/volume')
const { useActiveTrack } = await import('./queue/activeTrack')
const { useQueue } = await import('./queue/queue')
const { useRepeatMode } = await import('./queue/repeatMode')
const { useShuffle } = await import('./queue/shuffle')

afterEach(cleanup)

type Wiring = {
  name: string
  hook: () => unknown
  /** The property on the Nitro object the emitter assigns its callback to. */
  slot: string
  event: unknown
  /** What the hook returns — differs from `event` exactly when an eventKey applies. */
  expected: unknown
}

const wirings: Wiring[] = [
  // Payload passed through whole (no eventKey).
  {
    name: 'useOutput',
    hook: useOutput,
    slot: 'onOutputChanged',
    event: { type: 'bluetooth' },
    expected: { type: 'bluetooth' }
  },
  {
    name: 'useNowPlaying',
    hook: useNowPlaying,
    slot: 'onNowPlayingChanged',
    event: { title: 'Song' },
    expected: { title: 'Song' }
  },
  {
    name: 'useOnline',
    hook: useOnline,
    slot: 'onOnlineChanged',
    event: true,
    expected: true
  },
  {
    name: 'useCarConnected',
    hook: useCarConnected,
    slot: 'onCarConnectedChanged',
    event: true,
    expected: true
  },
  {
    name: 'useEqualizerSettings',
    hook: useEqualizerSettings,
    slot: 'onEqualizerChanged',
    event: { enabled: true },
    expected: { enabled: true }
  },
  {
    name: 'usePath',
    hook: usePath,
    slot: 'onPathChanged',
    event: '/artists/1',
    expected: '/artists/1'
  },
  {
    name: 'useContent',
    hook: useContent,
    slot: 'onContentChanged',
    event: { src: 'a' },
    expected: { src: 'a' }
  },
  {
    name: 'useTabs',
    hook: useTabs,
    slot: 'onTabsChanged',
    event: [{ src: 'a' }],
    expected: [{ src: 'a' }]
  },
  {
    name: 'usePlayback',
    hook: usePlayback,
    slot: 'onPlaybackChanged',
    event: { state: 'playing' },
    expected: { state: 'playing' }
  },
  {
    name: 'useSystemVolume',
    hook: useSystemVolume,
    slot: 'onSystemVolumeChanged',
    event: 0.4,
    expected: 0.4
  },
  {
    name: 'useProgress',
    hook: useProgress,
    slot: 'onPlaybackProgressUpdated',
    event: { position: 1, duration: 2, buffered: 3 },
    expected: { position: 1, duration: 2, buffered: 3 }
  },
  {
    name: 'usePlayingState',
    hook: usePlayingState,
    slot: 'onPlaybackPlayingState',
    event: { state: 'ready' },
    expected: { state: 'ready' }
  },
  {
    name: 'useShuffle',
    hook: useShuffle,
    slot: 'onPlaybackShuffleModeChanged',
    event: true,
    expected: true
  },
  {
    name: 'useQueue',
    hook: useQueue,
    slot: 'onPlaybackQueueChanged',
    event: [{ src: 'a' }],
    expected: [{ src: 'a' }]
  },
  {
    name: 'useFormattedNavigationError',
    hook: useFormattedNavigationError,
    slot: 'onFormattedNavigationError',
    event: { title: 'Offline' },
    expected: { title: 'Offline' }
  },
  {
    name: 'useOptions',
    hook: useOptions,
    slot: 'onOptionsChanged',
    event: { playbackSpeed: 1.5 },
    expected: { playbackSpeed: 1.5 }
  },

  // A field lifted out of the payload — the eventKey cases.
  {
    name: 'usePlaybackError',
    hook: usePlaybackError,
    slot: 'onPlaybackError',
    event: { error: { code: 'network' } },
    expected: { code: 'network' }
  },
  {
    name: 'useNavigationError',
    hook: useNavigationError,
    slot: 'onNavigationError',
    event: { error: { code: 'notFound' } },
    expected: { code: 'notFound' }
  },
  {
    name: 'usePlayWhenReady',
    hook: usePlayWhenReady,
    slot: 'onPlaybackPlayWhenReadyChanged',
    event: { playWhenReady: true },
    expected: true
  },
  {
    name: 'useRepeatMode',
    hook: useRepeatMode,
    slot: 'onPlaybackRepeatModeChanged',
    event: { repeatMode: 'queue' },
    expected: 'queue'
  },
  {
    name: 'useActiveTrack',
    hook: useActiveTrack,
    slot: 'onPlaybackActiveTrackChanged',
    event: { track: { src: 'a' } },
    expected: { src: 'a' }
  },
  {
    name: 'useBatteryWarningPending',
    hook: useBatteryWarningPending,
    slot: 'onBatteryWarningPendingChanged',
    event: { pending: true },
    expected: true
  },
  {
    name: 'useBatteryOptimizationStatus',
    hook: useBatteryOptimizationStatus,
    slot: 'onBatteryOptimizationStatusChanged',
    event: { status: 'unrestricted' },
    expected: 'unrestricted'
  }
]

describe.each(wirings)('$name', ({ hook, slot, event, expected }) => {
  it(`reads $slot`, () => {
    // A slot nothing claimed means the emitter assigns a different property
    // than the one named here — the hook would never update in production.
    expect(slots.has(slot)).toBe(true)

    const { result } = renderHook(() => hook())
    act(() => slots.get(slot)!(event))
    expect(result.current).toEqual(expected)
  })
})

describe('hook wiring table', () => {
  it('covers every delegating hook exactly once', () => {
    const names = wirings.map((w) => w.name)
    expect(new Set(names).size).toBe(names.length)
    expect(new Set(wirings.map((w) => w.slot)).size).toBe(wirings.length)
  })
})
