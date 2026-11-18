import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RemoteCommandHost } from './RemoteCommandController'
import { RemoteCommandController } from './RemoteCommandController'

type Handler = ((details: any) => void) | null

class FakeMediaMetadata {
  constructor(public init: Record<string, unknown>) {}
}

/** Captures the action handlers the controller registers on the session. */
function stubMediaSession() {
  const handlers = new Map<string, Handler>()
  const session = {
    metadata: null as unknown,
    playbackState: 'none' as 'none' | 'paused' | 'playing',
    setActionHandler: (action: string, handler: Handler) =>
      handlers.set(action, handler),
    setPositionState: vi.fn()
  }
  vi.stubGlobal('navigator', { mediaSession: session })
  vi.stubGlobal('MediaMetadata', FakeMediaMetadata)
  return { session, handlers }
}

function makeHost(
  overrides: Partial<RemoteCommandHost> = {}
): RemoteCommandHost {
  return {
    play: vi.fn(),
    pause: vi.fn(),
    stop: vi.fn(),
    skipToNext: vi.fn(),
    skipToPrevious: vi.fn(),
    seekTo: vi.fn(),
    seekBy: vi.fn(),
    handleRemotePlay: undefined,
    handleRemotePause: undefined,
    handleRemoteStop: undefined,
    handleRemoteNext: undefined,
    handleRemotePrevious: undefined,
    handleRemoteSeek: undefined,
    handleRemoteJumpForward: undefined,
    handleRemoteJumpBackward: undefined,
    onRemotePlay: vi.fn(),
    onRemotePause: vi.fn(),
    onRemoteStop: vi.fn(),
    onRemoteNext: vi.fn(),
    onRemotePrevious: vi.fn(),
    onRemoteSeek: vi.fn(),
    onRemoteJumpForward: vi.fn(),
    onRemoteJumpBackward: vi.fn(),
    getPlayback: vi.fn(() => ({ state: 'playing' })) as any,
    getPlayingState: vi.fn(() => ({ playing: true, buffering: false })),
    getProgress: vi.fn(() => ({ position: 0, duration: 0, buffered: 0 })),
    ...overrides
  }
}

describe('RemoteCommandController', () => {
  let session: ReturnType<typeof stubMediaSession>['session']
  let handlers: ReturnType<typeof stubMediaSession>['handlers']

  beforeEach(() => {
    ;({ session, handlers } = stubMediaSession())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // Each media-session action → its default transport method + emitted event.
  const VOID_COMMANDS = [
    { action: 'play', transport: 'play', emit: 'onRemotePlay' },
    { action: 'pause', transport: 'pause', emit: 'onRemotePause' },
    { action: 'stop', transport: 'stop', emit: 'onRemoteStop' },
    { action: 'nexttrack', transport: 'skipToNext', emit: 'onRemoteNext' },
    {
      action: 'previoustrack',
      transport: 'skipToPrevious',
      emit: 'onRemotePrevious'
    }
  ] as const

  it.each(VOID_COMMANDS)(
    '$action runs $transport and emits $emit by default',
    ({ action, transport, emit }) => {
      const host = makeHost()
      new RemoteCommandController(host)

      handlers.get(action)?.(undefined)

      expect(host[transport]).toHaveBeenCalledOnce()
      expect(host[emit]).toHaveBeenCalledOnce()
    }
  )

  it('prefers a consumer handler over the default action, still emitting', () => {
    const handleRemotePlay = vi.fn()
    const host = makeHost({ handleRemotePlay })
    new RemoteCommandController(host)

    handlers.get('play')?.(undefined)

    expect(handleRemotePlay).toHaveBeenCalledOnce()
    expect(host.play).not.toHaveBeenCalled()
    expect(host.onRemotePlay).toHaveBeenCalledOnce()
  })

  it('reads handlers live, honouring ones assigned after construction', () => {
    const host = makeHost()
    new RemoteCommandController(host)

    const lateHandler = vi.fn()
    host.handleRemotePause = lateHandler
    handlers.get('pause')?.(undefined)

    expect(lateHandler).toHaveBeenCalledOnce()
    expect(host.pause).not.toHaveBeenCalled()
  })

  it('passes seek/jump payloads to default actions and events', () => {
    const host = makeHost()
    new RemoteCommandController(host)

    handlers.get('seekto')?.({ seekTime: 12 })
    handlers.get('seekforward')?.({ seekOffset: 25 })
    handlers.get('seekbackward')?.({})

    expect(host.seekTo).toHaveBeenCalledWith(12)
    expect(host.onRemoteSeek).toHaveBeenCalledWith({ position: 12 })
    expect(host.seekBy).toHaveBeenCalledWith(25)
    expect(host.onRemoteJumpForward).toHaveBeenCalledWith({ interval: 25 })
    expect(host.seekBy).toHaveBeenCalledWith(-10)
    expect(host.onRemoteJumpBackward).toHaveBeenCalledWith({ interval: 10 })
  })

  it('mirrors playback state from the host', () => {
    const playback = { state: 'ready' as const }
    const playingState = { playing: false, buffering: false }
    const host = makeHost({
      getPlayback: vi.fn(() => playback) as any,
      getPlayingState: vi.fn(() => playingState)
    })
    const controller = new RemoteCommandController(host)

    controller.syncPlaybackState()
    expect(session.playbackState).toBe('paused')

    playingState.playing = true
    controller.syncPlaybackState()
    expect(session.playbackState).toBe('playing')

    ;(playback as { state: string }).state = 'none'
    controller.syncPlaybackState()
    expect(session.playbackState).toBe('none')
  })

  it('pushes scrubber position from host progress', () => {
    const host = makeHost({
      getProgress: vi.fn(() => ({ position: 30, duration: 180, buffered: 0 }))
    })
    new RemoteCommandController(host).updateProgress()

    expect(session.setPositionState).toHaveBeenCalledWith({
      duration: 180,
      position: 30,
      playbackRate: 1
    })
  })
})
